package io.github.letdown2491.noscat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.noscat.Event
import uniffi.noscat.EventListener
import uniffi.noscat.NoscatClient
import uniffi.noscat.NoscatError
import uniffi.noscat.generateIdentity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@Composable
private fun App() {
    MaterialTheme {
        Scaffold { padding -> StatusScreen(Modifier.padding(padding)) }
    }
}

@Composable
private fun StatusScreen(modifier: Modifier = Modifier) {
    // Smoke-test surface for the FFI layer: identity generation plus a live
    // event log. Phase 1 replaces this with the real dial / transfer UI.
    val lines = remember { mutableStateListOf<String>() }
    val client = remember {
        try {
            NoscatClient.new(
                uniffi.noscat.ClientConfig(
                    identitySecret = null,
                    relays = null,
                    tailnetDir = null,
                    listenPort = null,
                ),
                object : EventListener {
                    override fun onEvent(event: Event) {
                        lines.add(0, event.toString())
                    }
                },
            ).getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("noscat", style = MaterialTheme.typography.headlineMedium)
        Text(
            client?.npub() ?: "identity unavailable: ${
                runCatching { generateIdentity().npub }.getOrElse { "no FFI library loaded" }
            }",
            style = MaterialTheme.typography.bodySmall,
        )
        lines.take(20).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
