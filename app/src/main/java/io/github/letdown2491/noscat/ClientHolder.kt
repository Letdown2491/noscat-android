package io.github.letdown2491.noscat

import android.os.Handler
import android.os.Looper
import uniffi.noscat.Event
import uniffi.noscat.EventListener
import uniffi.noscat.NoscatClient

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
     */
    @Synchronized
    fun getOrStart(secretHex: String?): NoscatClient? {
        client?.let { return it }
        return try {
            val c = NoscatClient(
                uniffi.noscat.ClientConfig(
                    identitySecret = secretHex,
                    relays = null,
                    tailnetDir = null,
                    listenPort = null,
                ),
                object : EventListener {
                    override fun onEvent(event: Event) = logLine(render(event))
                },
            )
            client = c
            logLine("client started")
            c
        } catch (t: Throwable) {
            logLine("client failed to start: $t")
            null
        }
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
