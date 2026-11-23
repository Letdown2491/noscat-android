package io.github.letdown2491.noscat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    /** Call once from the activity so transfer outcomes can toast. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Computes the identity and tailnet state from prefs and starts (or
     * returns) the client. Blocking FFI work: run it off the main thread.
     * After joining a tailnet, the phone's mesh identity is its tailnet
     * device identity; before that it is the standalone app identity.
     */
    fun startFromPrefs(context: Context): NoscatClient? {
        val dir = AppPrefs.tailnetDir(context)
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
     * `tailnetDir` scopes tunnels to a tailnet group (PSK auth) - pass the
     * joined device directory, or null for bare (non-tailnet) tunnels.
     */
    @Synchronized
    fun getOrStart(secretHex: String?, tailnetDir: String? = null): NoscatClient? {
        client?.let { return it }
        return try {
            val c = NoscatClient(
                uniffi.noscat.ClientConfig(
                    identitySecret = secretHex,
                    relays = null,
                    tailnetDir = tailnetDir,
                    listenPort = null,
                ),
                object : EventListener {
                    override fun onEvent(event: Event) {
                        logLine(render(event))
                        onEventState(event)
                        // Transfers complete out of band; surface the outcome
                        // as a toast so it is visible without scrolling.
                        if (event is Event.TransferDone || event is Event.TransferFailed) {
                            val text = render(event)
                            main.post {
                                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
            )
            client = c
            logLine(
                if (tailnetDir != null) "client started (tailnet joined)" else "client started",
            )
            c
        } catch (t: Throwable) {
            logLine("client failed to start: $t")
            null
        }
    }

    /** Keeps the transfer progress state in step with the event stream. */
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
            else -> {}
        }
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

    /** Arms the receiver; the outcome arrives as a transfer event. */
    fun receiveFile(outDir: String) {
        val c = client ?: run { logLine("receive aborted: client is not running"); return }
        transfer = TransferState(
            name = "incoming file",
            peer = "",
            sent = 0,
            total = 0,
            incoming = true,
        )
        logLine("waiting for an incoming file")
        main.post {
            Thread {
                try {
                    c.receiveFile(outDir)
                } catch (t: Throwable) {
                    logLine("receive failed to start: $t")
                    transfer = null
                }
            }.start()
        }
    }

    /**
     * Stops the running client and starts a new one with the given config.
     * Dropping the UniFFI object tears down its runtime and sockets.
     */
    @Synchronized
    fun restart(secretHex: String?, tailnetDir: String?): NoscatClient? {
        if (client != null) logLine("client stopped")
        client = null
        return getOrStart(secretHex, tailnetDir)
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
        is Event.Stopped -> "stopped: ${event.what}" +
            (event.error?.let { " ($it)" } ?: "")
    }
}
