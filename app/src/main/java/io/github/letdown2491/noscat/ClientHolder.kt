package io.github.letdown2491.noscat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.getValue
import uniffi.noscat.MeshMember
import uniffi.noscat.meshLeave
import uniffi.noscat.meshMembers
import uniffi.noscat.meshSync
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import uniffi.noscat.Event
import uniffi.noscat.EventListener
import uniffi.noscat.ForwardInfo
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

    /** The running client, or null while (re)starting or disconnected
     * (Compose observable). */
    var client: NoscatClient? by mutableStateOf(null)
        private set

    /** Disconnected on purpose: a member, but not running until the user
     * reconnects (Compose observable). */
    var offline by mutableStateOf(false)
        private set

    /** This device's npub, read off the main thread once at start: even a
     * trivial FFI getter must not run during composition, where it can
     * stall behind the node's relay work and ANR the app. */
    var myNpub: String? by mutableStateOf(null)
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
     * Last known mesh roster, cached across navigation AND restarts: the
     * device list renders from disk at startup. The FFI's member fetch
     * does a relay advert round-trip per device, so the list must never
     * wait on it; the background sync updates the cache in place.
     */
    var roster: List<MeshMember>? by mutableStateOf(null)
        private set

    private val rosterFile: File
        get() = File(appContext!!.filesDir, "members.json")

    /** Epoch ms when [roster]'s lastSeenSecs were measured (persisted
     * alongside the roster), so cached presence can be aged forward. */
    private var rosterAgeBase = 0L

    /** Refresh the cached roster (called after a successful member fetch). */
    fun cacheRoster(list: List<MeshMember>) {
        roster = list
        rosterAgeBase = System.currentTimeMillis()
        runCatching {
            val arr = JSONArray()
            list.forEach { m ->
                arr.put(JSONObject().apply {
                    put("npub", m.npub)
                    put("admin", m.admin)
                    put("thisDevice", m.thisDevice)
                    m.name?.let { put("name", it) } ?: put("name", JSONObject.NULL)
                    m.lastSeenSecs?.let { put("lastSeenSecs", it.toLong()) }
                        ?: put("lastSeenSecs", JSONObject.NULL)
                })
            }
            rosterFile.writeText(
                JSONObject().apply {
                    put("fetchedAt", rosterAgeBase)
                    put("members", arr)
                }.toString(),
            )
        }
    }

    private fun loadRoster(): List<MeshMember>? = runCatching {
        val o = JSONObject(rosterFile.readText())
        rosterAgeBase = o.optLong("fetchedAt", 0L)
        val arr = o.getJSONArray("members")
        List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            MeshMember(
                npub = m.getString("npub"),
                name = if (m.isNull("name")) null else m.getString("name"),
                lastSeenSecs = if (m.isNull("lastSeenSecs")) null
                    else m.getLong("lastSeenSecs").toULong(),
                // Recomputed from the aged lastSeenSecs where shown.
                online = false,
                admin = m.optBoolean("admin", true),
                thisDevice = m.optBoolean("thisDevice", false),
            )
        }
    }.getOrNull()

    /** True while a roster sync runs, for the pull-to-refresh spinner. */
    var syncing by mutableStateOf(false)
        private set

    /** Serializes roster syncs and invalidates the refresh loop of a
     * stopped client. */
    private val syncGen = AtomicInteger(0)
    private val syncBusy = AtomicBoolean(false)

    /**
     * Sync the roster from the relays: meshSync for membership, then the
     * per-device advert fetch (names, lastSeenSecs presence) that
     * meshSync skips. Runs on its own thread; UI just observes [roster].
     * Cheap no-op when nothing is running or a sync is already out.
     */
    fun syncRoster(waitSecs: ULong = 5uL) {
        val dir = meshDir ?: return
        // meshSync would run the device just for the call: never while
        // disconnected (or mid-forget).
        if (client == null) return
        if (!syncBusy.compareAndSet(false, true)) return
        val gen = syncGen.get()
        syncing = true
        Thread {
            val res = runCatching { meshSync(dir, waitSecs, null) }
                // meshSync returns membership only (no advert data);
                // enrich with names and presence from the per-device
                // advert fetch, keeping the membership from meshSync on
                // the rare failure.
                .mapCatching { synced ->
                    runCatching { meshMembers(dir, null) }
                        .onSuccess { l ->
                            logLine(
                                "enriched ${l.count { it.name != null }}/" +
                                    "${l.size} member adverts",
                            )
                        }
                        .getOrDefault(synced)
                }
            syncBusy.set(false)
            syncing = false
            res.fold(
                { fetched ->
                    // A relay fetch can come back empty for a peer (no
                    // name, no presence). Keep the previous values for
                    // that peer, aging the presence forward, so one
                    // missed lookup does not flash a live peer red under
                    // a generic label. A dead peer still goes red on its
                    // own: the aged lastSeenSecs crosses the advert TTL.
                    val prev = roster
                    val elapsedSecs =
                        ((System.currentTimeMillis() - rosterAgeBase)
                            .coerceAtLeast(0) / 1000).toULong()
                    val merged = fetched.map { m ->
                        val p = prev?.firstOrNull { it.npub == m.npub }
                        if (p != null && m.name == null && m.lastSeenSecs == null) {
                            p.copy(
                                npub = m.npub,
                                admin = m.admin,
                                lastSeenSecs = p.lastSeenSecs?.let { it + elapsedSecs },
                            )
                        } else {
                            m
                        }
                    }
                    roster = merged.filter { it.npub != myNpub }
                    cacheRoster(roster!!)
                },
                { if (gen == syncGen.get()) logLine("sync failed: ${it.message}") },
            )
        }.start()
    }

    /**
     * Refresh the roster on a timer for as long as this client runs.
     * Never tied to navigation: screens observe [roster], so freshness
     * arrives whether or not a list is on screen. One sync fires as
     * soon as the node listens (a sync before that sees nothing).
     */
    private fun startRosterSyncLoop() {
        val gen = syncGen.incrementAndGet()
        Thread {
            try {
                while (syncGen.get() == gen) {
                    if (nodeUp) syncRoster()
                    Thread.sleep(120_000)
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
    }

    /** Chat history per peer npub (in memory, oldest first). */
    val chats = HashMap<String, SnapshotStateList<ChatMsg>>()

    /** Unread chat count per peer npub (Compose observable). */
    val unread = mutableStateMapOf<String, Int>()

    /** Port forwards this device is running (the Services list): rows
     * created from device dialogs, reconciled by ForwardStopped. */
    val forwards = mutableStateListOf<ForwardInfo>()

    /** Peers a clipboard share is in flight to; ChatDelivered for one of
     * these completes a share (and toasts) rather than a chat bubble. */
    private val pendingClipboard = mutableSetOf<String>()

    /** Peers with a live tunnel right now (Compose observable). The
     * presence dot on the device and chat rows reads this. One-shot
     * tunnels (chat, file sends) blink up and down in a moment, so a
     * close only clears the dot a minute later: "reached recently"
     * reads better than a flicker. */
    val liveTunnels = mutableStateMapOf<String, Boolean>()
    private val pendingClose = HashMap<String, Runnable>()

    /** True once the node is listening on the relays. Until this flips,
     * relay-backed work (advert lookups, roster sync) returns nothing:
     * connecting to the relays can take tens of seconds, so screens must
     * not assume a sync at app start saw anything. */
    var nodeUp by mutableStateOf(false)
        private set

    /** Total unread chat messages, for the bottom-nav badge. */
    val unreadTotal: Int get() = unread.values.sum()

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    /** A file transfer in flight (sends carry live progress; receives wait). */
    data class TransferState(
        val name: String,
        val peer: String,
        val path: String,
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
        /** Outgoing only: the member acknowledged it (ChatDelivered). */
        val delivered: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    /** Call once from the activity so transfer outcomes can toast. */
    fun init(context: Context) {
        appContext = context.applicationContext
        roster = loadRoster()
    }

    /**
     * Computes the identity and mesh state from prefs and starts (or
     * returns) the client. Blocking FFI work: run it off the main thread.
     * After joining a mesh, the phone's mesh identity is its mesh
     * device identity; before that it is the standalone app identity.
     */
    fun startFromPrefs(context: Context): NoscatClient? {
        // Fall back to standalone when the stored mesh dir is gone
        // (uninstalled data, cleared prefs, a disconnect that raced a crash).
        val stored = AppPrefs.meshDir(context)
        val dir = when {
            stored != null && File(stored).exists() -> stored
            else -> {
                if (stored != null) AppPrefs.setMeshDir(context, null)
                null
            }
        }
        offline = dir != null && AppPrefs.disconnected(context)
        if (offline) return null
        val secret = when {
            dir != null -> File(dir, "identity.nsec").readText().trim()
            else -> IdentityStore.load(context) ?: generateIdentity().secretHex.also {
                IdentityStore.save(context, it)
            }
        }
        return getOrStart(secret, dir).also {
            // Trash from the previous session never survived an undo.
            purgeInboxTrash(context)
        }
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
     * `meshDir` is the joined device directory (its mesh decides who can
     * reach this phone), or null before joining. With a mesh, the client
     * also runs node mode: inbound files land in the app inbox on their
     * own, and chat messages arrive as events - no arming.
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
                            event is Event.TransferPartial ||
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
            myNpub = runCatching { c.npub() }.getOrNull()
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
                        nodeUp = true
                        startRosterSyncLoop()
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
                path = event.path,
                sent = event.sent.toLong(),
                total = event.total.toLong(),
                incoming = false,
            )
            is Event.TransferDone -> {
                transfer = null
                inboxVersion++
            }
            is Event.TransferFailed -> transfer = null
            is Event.TransferPartial -> {
                // A receive that ended without a verified whole file
                // (0.4.1 honest reporting): the kept .noscat-part is a
                // resume seed only, not a usable file, so no inbox bump.
                transfer = null
                logLine("transfer incomplete: ${File(event.path).name} (kept as a partial, resume by sending again)")
            }
            is Event.TransferCancelled -> {
                transfer = null
                logLine(
                    "transfer cancelled: ${File(event.path).name} " +
                        "(receiver keeps the partial)",
                )
            }
            is Event.ChatMessage -> {
                chatWith(event.peer).add(ChatMsg(event.text, outgoing = false))
                unread[event.peer] = (unread[event.peer] ?: 0) + 1
            }
            is Event.ChatDelivered -> {
                // The member acknowledged it: mark the newest
                // optimistic bubble. Also completes a clipboard share,
                // which rides the same wire shape.
                val msgs = chats[event.peer]
                if (msgs != null) {
                    val idx = msgs.indexOfLast { it.outgoing && !it.delivered && !it.failed }
                    if (idx >= 0) msgs[idx] = msgs[idx].copy(delivered = true)
                }
                if (pendingClipboard.remove(event.peer) != null) {
                    Toast.makeText(
                        appContext,
                        "clipboard delivered",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            is Event.ForwardStopped -> {
                forwards.removeAll { it.id == event.id }
                logLine(
                    if (event.error != null) "forward ${event.id} failed: ${event.error}"
                    else "forward ${event.id} stopped",
                )
            }
            is Event.TunnelUp -> {
                pendingClose.remove(event.peer)?.let { main.removeCallbacks(it) }
                liveTunnels[event.peer] = true
            }
            is Event.TunnelClosed -> {
                pendingClose.remove(event.peer)?.let { main.removeCallbacks(it) }
                val clear = Runnable {
                    pendingClose.remove(event.peer)
                    liveTunnels[event.peer] = false
                }
                pendingClose[event.peer] = clear
                main.postDelayed(clear, 60_000)
            }
            is Event.Clipboard -> {
                // A peer shared their clipboard: it becomes ours. This is
                // the receiving end of the row "paste" action.
                setClipboardText(event.text)
                logLine("clipboard updated by ${event.peer.take(16)}... (${event.text.length} chars)")
            }
            is Event.ChatFailed -> {
                // Mark the newest undelivered optimistic bubble.
                val msgs = chats[event.peer] ?: return
                val idx = msgs.indexOfLast { it.outgoing && !it.failed }
                if (idx >= 0) msgs[idx] = msgs[idx].copy(failed = true)
            }
            is Event.Listening -> {
                // Serve side is up; render() logs it, nothing to track.
            }
            is Event.Stopped -> {
                // A background op (listener, expose, forward) stopped.
                logLine(
                    if (event.error != null) "${event.what} stopped: ${event.error}"
                    else "${event.what} stopped",
                )
            }
            // Membership and presence changes: the running client is the
            // source of truth, so a sync reads it straight back.
            is Event.MeshJoined, is Event.Roster, is Event.MemberSeen,
            is Event.MeshLeft -> syncRoster()
            is Event.MeshChangeUndone -> {}
            // Inviting from the phone needs an approve prompt (not built
            // yet); render() logs the request.
            is Event.JoinRequest -> {}
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
    /** The received-files inbox (external storage when available). */
    fun inboxDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /** Deleted files wait here: restorable while the app lives, purged at
     * the next start. */
    private fun trashDir(context: Context): File =
        File(inboxDir(context), ".trash").apply { mkdirs() }

    /** Move a received file to the trash: the row disappears, and the
     * snackbar's Undo can put it back. */
    fun trashInboxFile(context: Context, file: File): Boolean {
        val ok = runCatching {
            file.renameTo(File(trashDir(context), file.name))
        }.getOrDefault(false)
        if (ok) inboxVersion++
        return ok
    }

    /** Undo: put a trashed file back in the inbox under its old name. */
    fun restoreInboxFile(context: Context, name: String): Boolean {
        val ok = runCatching {
            File(trashDir(context), name).renameTo(File(inboxDir(context), name))
        }.getOrDefault(false)
        Log.d("noscat", "restore $name ok=$ok")
        if (ok) {
            inboxVersion++
            // The emulated-storage view can serve a stale directory right
            // after a rename; relist once more to heal that.
            main.postDelayed({ inboxVersion++ }, 500)
        }
        return ok
    }

    /** Empty the trash. Called at startup: trashed files stay restorable
     * for the whole session, but never outlive the app. */
    fun purgeInboxTrash(context: Context) {
        trashDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Sends a file, tracking progress. Errors land in the log, not the void. */
    fun sendFile(path: String, targetNpub: String) {
        val c = client ?: run { logLine("send aborted: client is not running"); return }
        val name = File(path).name
        transfer = TransferState(
            name = name,
            peer = targetNpub,
            path = path,
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

    /** Share this device's clipboard text with a peer: it becomes the
     * clipboard on the receiving device. Delivery confirmation (the
     * member's acknowledgement) arrives as ChatDelivered, which completes the
     * pending share with a toast. */
    fun sendClipboard(peer: String, text: String) {
        val c = client ?: run { logLine("clipboard send aborted: client is not running"); return }
        pendingClipboard.add(peer)
        Thread {
            try {
                c.sendClipboard(peer, text)
            } catch (t: Throwable) {
                pendingClipboard.remove(peer)
                logLine("clipboard send failed: $t")
            }
        }.start()
    }

    /** Forward a local port to a peer's exposed service. The id-keyed
     * row appears in [forwards]; ForwardStopped reconciles it. */
    fun forward(target: String, port: UShort) {
        val c = client ?: run { logLine("forward aborted: client is not running"); return }
        Thread {
            runCatching { c.forward(target, port) }.fold(
                { id ->
                    logLine("forward $id dialled: localhost:$port -> ${target.take(16)}...")
                    refreshForwards()
                },
                { logLine("forward failed: ${it.message}") },
            )
        }.start()
    }

    /** Stop a forward by id. The row leaves [forwards] when the
     * confirming ForwardStopped event lands, not here. */
    fun cancelForward(id: ULong) {
        val c = client ?: return
        Thread {
            val ok = runCatching { c.cancelForward(id) }.getOrDefault(false)
            logLine(
                if (ok) "cancelling forward $id" else "forward $id already gone",
            )
        }.start()
    }

    /** Snapshot the client's live forward registry into [forwards]. An
     * in-memory read, but still off the main thread (rule 6). */
    fun refreshForwards() {
        val c = client ?: return
        Thread {
            val list = runCatching { c.listForwards() }.getOrDefault(emptyList())
            main.post {
                forwards.clear()
                forwards.addAll(list)
            }
        }.start()
    }

    /** Cancel an in-flight outgoing send. The progress card clears when
     * the TransferCancelled event lands; the receiver keeps its partial. */
    fun cancelTransfer(path: String, peer: String) {
        val c = client ?: return
        Thread {
            val ok = runCatching { c.cancelTransfer(path, peer) }.getOrDefault(false)
            logLine(
                if (ok) "cancelling transfer of ${File(path).name}"
                else "transfer already finished",
            )
        }.start()
    }

    /** Put text on the Android system clipboard (main-thread API). */
    private fun setClipboardText(text: String) {
        val ctx = appContext ?: return
        main.post {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("noscat", text))
        }
    }

    /** Sends a chat message; the optimistic bubble is marked delivered by
     * the ChatDelivered event (0.4.0 sends are fire and forget), and
     * failed by ChatFailed. */
    fun sendChat(peer: String, text: String) {
        val c = client ?: run { logLine("chat aborted: client is not running"); return }
        chatWith(peer).add(ChatMsg(text, outgoing = true))
        Thread {
            try {
                c.sendChat(peer, text)
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
     * Stop the client now, not whenever the garbage collector gets to it:
     * close() frees the native client, which stops the device and frees
     * its directory for the next client.
     */
    private fun closeClient() {
        val c = client ?: return
        client = null
        logLine("client stopped")
        runCatching { c.close() }
    }

    /**
     * Disconnect: stop the client but stay a member. The device dir and
     * the cached roster stay, so [reconnect] resumes as the same device.
     */
    @Synchronized
    fun disconnect(context: Context) {
        AppPrefs.setDisconnected(context, true)
        offline = true
        syncGen.incrementAndGet() // stop the roster sync loop
        closeClient()
        meshDir = null
        nodeUp = false
        syncing = false
        forwards.clear()
        pendingClipboard.clear()
        main.post { liveTunnels.clear() }
    }

    /** Undo [disconnect]: run the device again. Blocking. */
    fun reconnect(context: Context): NoscatClient? {
        AppPrefs.setDisconnected(context, false)
        offline = false
        return startFromPrefs(context)
    }

    /**
     * Forget the mesh: leave it (the other members remove this phone; with
     * none online in 30 seconds it forgets locally and the next member to
     * come online completes the removal), then delete the device dir and
     * the cached roster, so joining again starts as a new device. Received
     * files stay. Blocking. Returns null on success, or why leaving failed
     * (then nothing is deleted).
     */
    fun forget(context: Context): String? {
        val dir = AppPrefs.meshDir(context)
        if (dir != null && File(dir).exists()) {
            // Runs on the app's client, or on a device started just for
            // the call when disconnected.
            val err = runCatching { meshLeave(dir, 30uL, null) }.exceptionOrNull()
            if (err != null && "not in a mesh" !in err.toString()) {
                logLine("leaving failed: $err")
                return err.message ?: err.toString()
            }
        }
        disconnect(context)
        // A roster sync still in flight holds the device; let it finish
        // before deleting the directory under it.
        repeat(100) { if (syncBusy.get()) Thread.sleep(100) }
        roster = null
        runCatching { rosterFile.delete() }
        dir?.let { File(it).deleteRecursively() }
        AppPrefs.setMeshDir(context, null)
        AppPrefs.setDisconnected(context, false)
        offline = false
        main.post {
            chats.clear()
            unread.clear()
        }
        logLine("left the mesh; device data deleted")
        return null
    }

    /**
     * Stops the running client and starts a new one with the given config.
     */
    @Synchronized
    fun restart(secretHex: String?, meshDir: String?): NoscatClient? {
        syncGen.incrementAndGet() // stop any roster sync loop
        closeClient()
        myNpub = null
        nodeUp = false
        syncing = false
        roster = null
        forwards.clear()
        pendingClipboard.clear()
        // A stale persisted roster would show mesh peers while standalone.
        runCatching { rosterFile.delete() }
        return getOrStart(secretHex, meshDir)
    }

    /**
     * Restart on the config prefs currently hold (identity, mesh, device
     * name) - for changes that only apply at client start, like a rename.
     */
    @Synchronized
    fun restartFromPrefs(context: Context): NoscatClient? {
        syncGen.incrementAndGet()
        closeClient()
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
        is Event.TransferPartial ->
            "transfer incomplete: ${File(event.path).name} (kept as a partial)"
        is Event.TransferCancelled ->
            "transfer cancelled: ${File(event.path).name} (receiver keeps the partial)"
        is Event.ChatMessage -> "message from ${event.peer}: ${event.text}"
        is Event.ChatDelivered -> "delivered: ${event.peer}"
        is Event.ChatFailed -> "message not delivered: ${event.peer}: ${event.error}"
        is Event.ForwardStopped ->
            if (event.error != null) "forward ${event.id} failed: ${event.error}"
            else "forward ${event.id} stopped"
        is Event.Clipboard -> "clipboard shared by ${event.peer} (${event.text.length} chars)"
        is Event.Stopped -> "stopped: ${event.what}" +
            (event.error?.let { " ($it)" } ?: "")
        is Event.MeshJoined -> "in mesh ${event.name}"
        is Event.Roster -> "mesh now has ${event.members.size} members"
        is Event.MemberSeen -> "seen: ${event.name ?: event.peer}"
        is Event.MeshChangeUndone ->
            "another device changed the mesh at the same moment and won; try again"
        is Event.MeshLeft -> "this device is no longer in the mesh"
        is Event.JoinRequest -> "${event.name ?: event.peer} asks to join the mesh"
    }
}
