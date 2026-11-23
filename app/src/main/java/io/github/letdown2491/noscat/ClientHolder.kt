package io.github.letdown2491.noscat

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import uniffi.noscat.Event
import uniffi.noscat.EventListener
import uniffi.noscat.NoscatClient
import uniffi.noscat.generateIdentity
import java.io.File

/**
 * Holds the running client across activity recreations and renders events to
 * text lines the UI can show. Events arrive on a Rust thread; everything is
 * hopped to the main thread here so callers never have to.
 */
object ClientHolder {
    /** Main-thread state. Compose snapshot state, so recomposition just works. */
    val log = mutableListOf<String>()
    var client: NoscatClient? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

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
     * imported device directory, or null for bare (non-tailnet) tunnels.
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
                        // Transfers complete out of band; surface the outcome
                        // as a toast so it is visible without scrolling.
                        if (event is Event.TransferDone || event is Event.TransferFailed) {
                            main.post {
                                Toast.makeText(
                                    appContext,
                                    render(event),
                                    Toast.LENGTH_LONG,
                                ).show()
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
        is Event.TransferDone -> "transfer done: ${event.path} <- ${event.peer}"
        is Event.TransferFailed -> "transfer failed: ${event.peer}: ${event.error}"
        is Event.Stopped -> "stopped: ${event.what}" +
            (event.error?.let { " ($it)" } ?: "")
    }
}
