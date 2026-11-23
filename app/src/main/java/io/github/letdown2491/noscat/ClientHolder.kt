package io.github.letdown2491.noscat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import uniffi.noscat.MeshMember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import uniffi.noscat.Event
import uniffi.noscat.EventListener
import uniffi.noscat.NoscatClient
import uniffi.noscat.generateIdentity
import java.io.File

/**
 * Holds the running client across activity recreations. Events arrive on a
 * Rust thread; everything is hopped to the main thread here and exposed as
 * Compose snapshot state, so screens can just read it.
 */
object ClientHolder {
    /** One line per client event, newest first (Compose observable). */
    val log = mutableStateListOf<String>()

    /** The running client, or null while (re)starting. */
    var client: NoscatClient? = null
        private set

    /** An active file transfer, for the progress card on the devices screen. */
    var transfer: TransferState? by mutableStateOf(null)
        private set

    /** Increments whenever a received file lands, so the inbox re-lists. */
    var inboxVersion by mutableStateOf(0)
        private set

    /** The mesh dir the running client is scoped to, or null (standalone). */
    var meshDir: String? = null
        private set

    /**
     * Last known mesh roster, cached across navigation: the device list
     * and the connection pill render instantly from this while a fresh
     * fetch (advert names over relays) runs in the background.
     */
    var roster: List<MeshMember>? by mutableStateOf(null)
        private set

    /** Refresh the cached roster (called after a successful member fetch). */
    fun cacheRoster(list: List<MeshMember>) {
        roster = list
    }

    /** Chat history per peer npub (in memory, oldest first). */
    val chats = HashMap<String, SnapshotStateList<ChatMsg>>()

    /** Unread chat count per peer npub (Compose observable). */
    val unread = mutableStateMapOf<String, Int>()

    /** Total unread chat messages, for the bottom-nav badge. */
    val unreadTotal: Int get() = unread.values.sum()

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** A file transfer in flight (sends carry live progress; receives wait). */
    data class TransferState(
        val name: String,
        val peer: String,
        val sent: Long,
        val total: Long,
        val incoming: Boolean,
    ) {
        val percent: Int
            get() = if (total <= 0) 0 else ((sent * 100 / total).coerceAtMost(100)).toInt()
    }

    /** One chat bubble. Outgoing messages are optimistic; failure marks them. */
    data class ChatMsg(
        val text: String,
        val outgoing: Boolean,
        val failed: Boolean = false,
        /** Outgoing only: the send path returned without error (FIN echoed). */
        val delivered: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    /** Call once from the activity so transfer outcomes can toast. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Computes the identity and mesh state from prefs and starts (or
     * returns) the client. Blocking FFI work: run it off the main thread.
     * After joining a mesh, the phone's mesh identity is its mesh
     * device identity; before that it is the standalone app identity.
     */
    fun startFromPrefs(context: Context): NoscatClient? {
        // The tailnet -> mesh rename moved the device directory and the
        // prefs key. Resolve the stored path once: move the directory and
        // rewrite prefs if this is the first run since the rename, and
        // fall back to standalone when the stored path is simply gone.
        val dir = AppPrefs.meshDir(context)?.let { stored ->
            when {
                File(stored).exists() -> stored
                else -> {
                    val old = File(context.filesDir, "tailnet")
                    val new = File(context.filesDir, "mesh")
                    if (old.exists() && !new.exists()) old.renameTo(new)
                    if (new.exists()) {
                        new.absolutePath.also { AppPrefs.setMeshDir(context, it) }
                    } else {
                        AppPrefs.setMeshDir(context, null)
                        null
                    }
                }
            }
        }
        val secret = when {
            dir != null -> File(dir, "identity.nsec").readText().trim()
            else -> IdentityStore.load(context) ?: generateIdentity().secretHex.also {
                IdentityStore.save(context, it)
            }
        }
        return getOrStart(secret, dir)
    }

    fun logLine(line: String) {
        Log.d("noscat", line)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            log.add(0, line)
            if (log.size > 200) log.removeAt(log.size - 1)
        } else {
            main.post { logLine(line) }
        }
    }

    /**
     * Starts (or returns) the running client. Blocking FFI call, so run it off
     * the main thread. Returns null and logs when the native side fails.
     * `meshDir` scopes tunnels to a mesh group (PSK auth) - pass the
     * joined device directory, or null for bare (non-mesh) tunnels. With a
     * mesh, the client also runs node mode: inbound files land in the app
     * inbox on their own, and chat messages arrive as events - no arming.
     */
    @Synchronized
    fun getOrStart(secretHex: String?, meshDir: String? = null): NoscatClient? {
        client?.let { return it }
        return try {
            val c = NoscatClient(
                uniffi.noscat.ClientConfig(
                    identitySecret = secretHex,
                    relays = null,
                    meshDir = meshDir,
                    // Peer-facing friendly name: the device model reads far
                    // better than an npub (or Android's "localhost" hostname).
                    deviceName = AppPrefs.deviceName(appContext!!)
                        ?.takeIf { it.isNotBlank() }
                        ?: android.os.Build.MODEL.takeIf { it.isNotBlank() },
                    listenPort = null,
                ),
                object : EventListener {
                    override fun onEvent(event: Event) {
                        logLine(render(event))
                        onEventState(event)
                        // Transfers and chats complete out of band; surface
                        // the outcome as a toast so it needs no scrolling.
                        if (
                            event is Event.TransferDone ||
                            event is Event.TransferFailed ||
                            event is Event.ChatMessage
                        ) {
                            val text = render(event)
                            main.post {
                                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
            )
            client = c
            this.meshDir = meshDir
            logLine(
                if (meshDir != null) "client started (mesh joined)" else "client started",
            )
            if (meshDir != null) {
                // Node mode: publish the advert and dispatch inbound streams
                // (files -> inbox, chat -> events) until the client drops.
                Thread {
                    try {
                        val out = outDir()
                        c.up(out, null)
                        logLine("node up: files arrive in the inbox automatically")
                    } catch (t: Throwable) {
                        logLine("node failed to start: $t")
                    }
                }.start()
            }
            c
        } catch (t: Throwable) {
            logLine("client failed to start: $t")
            null
        }
    }

    /** The directory inbound files land in (the inbox). */
    fun outDir(): String {
        val ctx = appContext ?: error("ClientHolder.init not called")
        return (ctx.getExternalFilesDir(null) ?: ctx.filesDir).absolutePath
    }

    /** Keeps the transfer progress and chat state in step with the events. */
    private fun onEventState(event: Event) {
        when (event) {
            is Event.TransferProgress -> transfer = TransferState(
                name = File(event.path).name,
                peer = event.peer,
                sent = event.sent.toLong(),
                total = event.total.toLong(),
                incoming = false,
            )
            is Event.TransferDone -> {
                transfer = null
                inboxVersion++
            }
            is Event.TransferFailed -> transfer = null
            is Event.ChatMessage -> {
                chatWith(event.peer).add(ChatMsg(event.text, outgoing = false))
                unread[event.peer] = (unread[event.peer] ?: 0) + 1
            }
            is Event.ChatFailed -> {
                // Mark the newest undelivered optimistic bubble.
                val msgs = chats[event.peer] ?: return
                val idx = msgs.indexOfLast { it.outgoing && !it.failed }
                if (idx >= 0) msgs[idx] = msgs[idx].copy(failed = true)
            }
            else -> {}
        }
    }

    /** The (live) message list for a conversation, oldest first. */
    fun chatWith(peer: String): SnapshotStateList<ChatMsg> =
        chats.getOrPut(peer) { mutableStateListOf() }

    /** Clear the unread marker (the conversation is on screen). */
    fun markRead(peer: String) {
        unread.remove(peer)
    }

    /** Delete a received inbox file; bumps the inbox so the list refreshes. */
    fun deleteInboxFile(file: File): Boolean {
        val ok = file.delete()
        if (ok) inboxVersion++
        return ok
    }

    /** Sends a file, tracking progress. Errors land in the log, not the void. */
    fun sendFile(path: String, targetNpub: String) {
        val c = client ?: run { logLine("send aborted: client is not running"); return }
        val name = File(path).name
        transfer = TransferState(
            name = name,
            peer = targetNpub,
            sent = 0,
            total = 0,
            incoming = false,
        )
        logLine("sending $name to ${targetNpub.take(16)}...")
        main.post {
            Thread {
                try {
                    c.sendFile(path, targetNpub)
                } catch (t: Throwable) {
                    logLine("send failed to start: $t")
                    transfer = null
                }
            }.start()
        }
    }

    /** Sends a chat message; the optimistic bubble marks itself on failure. */
    fun sendChat(peer: String, text: String) {
        val c = client ?: run { logLine("chat aborted: client is not running"); return }
        chatWith(peer).add(ChatMsg(text, outgoing = true))
        Thread {
            try {
                // Returns only once the peer echoed our delivery FIN.
                c.sendChat(peer, text)
                main.post {
                    val msgs = chatWith(peer)
                    val idx = msgs.indexOfLast { it.outgoing && !it.delivered && !it.failed }
                    if (idx >= 0) msgs[idx] = msgs[idx].copy(delivered = true)
                }
            } catch (t: Throwable) {
                logLine("chat failed to start: $t")
                main.post {
                    val msgs = chatWith(peer)
                    val idx = msgs.indexOfLast { it.outgoing && !it.failed }
                    if (idx >= 0) msgs[idx] = msgs[idx].copy(failed = true)
                }
            }
        }.start()
    }

    /**
     * Stops the running client and starts a new one with the given config.
     * Dropping the UniFFI object tears down its runtime and sockets.
     */
    @Synchronized
    fun restart(secretHex: String?, meshDir: String?): NoscatClient? {
        if (client != null) logLine("client stopped")
        client = null
        roster = null
        return getOrStart(secretHex, meshDir)
    }

    /**
     * Restart on the config prefs currently hold (identity, mesh, device
     * name) - for changes that only apply at client start, like a rename.
     */
    @Synchronized
    fun restartFromPrefs(context: Context): NoscatClient? {
        if (client != null) logLine("client stopped")
        client = null
        roster = null
        return startFromPrefs(context)
    }

    fun render(event: Event): String = when (event) {
        is Event.Listening -> "listening as ${event.npub} (udp ${event.port})"
        is Event.TunnelUp -> "tunnel up: ${event.peer}"
        is Event.TunnelClosed -> "tunnel closed: ${event.peer}" +
            (event.error?.let { " ($it)" } ?: "")
        is Event.TransferProgress ->
            "transfer ${event.sent * 100uL / maxOf(event.total, 1uL)}%: ${File(event.path).name}"
        is Event.TransferDone -> "transfer done: ${File(event.path).name} <- ${event.peer}"
        is Event.TransferFailed -> "transfer failed: ${event.peer}: ${event.error}"
        is Event.ChatMessage -> "message from ${event.peer}: ${event.text}"
        is Event.ChatFailed -> "message not delivered: ${event.peer}: ${event.error}"
        is Event.Stopped -> "stopped: ${event.what}" +
            (event.error?.let { " ($it)" } ?: "")
    }
}
