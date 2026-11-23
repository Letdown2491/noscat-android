package io.github.letdown2491.noscat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Devices
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.noscat.TailnetMember
import uniffi.noscat.generateIdentity
import uniffi.noscat.tailnetJoin
import uniffi.noscat.tailnetMembers
import uniffi.noscat.tailnetPrepare
import uniffi.noscat.tailnetSync

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClientHolder.init(this)
        NoscatService.start(this)
        setContent { NoscatApp() }
    }
}

@Composable
private fun NoscatApp() {
    MaterialTheme {
        Scaffold { padding -> Root(Modifier.padding(padding)) }
    }
}

@Composable
private fun Root(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tailnetDir by remember { mutableStateOf<String?>(null) }
    var screenReady by remember { mutableStateOf(false) }

    // One-time startup: the foreground service owns the client's lifetime,
    // but start it here too so the UI is usable immediately (idempotent).
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { ClientHolder.startFromPrefs(context) }
            tailnetDir = AppPrefs.tailnetDir(context)
        }.onFailure { ClientHolder.logLine("startup failed: $it") }
        screenReady = true
    }

    if (!screenReady) return

    if (tailnetDir != null) {
        var tab by remember { mutableStateOf("devices") }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == "devices",
                        onClick = { tab = "devices" },
                        icon = { Icon(Icons.Filled.Devices, contentDescription = null) },
                        label = { Text("Devices") },
                    )
                    NavigationBarItem(
                        selected = tab == "chat",
                        onClick = { tab = "chat" },
                        icon = { Icon(Icons.Filled.Chat, contentDescription = null) },
                        label = { Text("Chat") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    "chat" -> ChatScreen(tailnetDir = tailnetDir!!)
                    else -> DevicesScreen(
                        tailnetDir = tailnetDir!!,
                        onDisconnect = {
                            AppPrefs.setTailnetDir(context, null)
                            scope.launch {
                                val secret = withContext(Dispatchers.IO) {
                                    IdentityStore.load(context) ?: generateIdentity().secretHex.also {
                                        IdentityStore.save(context, it)
                                    }
                                }
                                withContext(Dispatchers.IO) { ClientHolder.restart(secret, null) }
                                tailnetDir = null
                            }
                        },
                    )
                }
            }
        }
    } else {
        JoinScreen(
            onJoined = { dir -> tailnetDir = dir },
        )
    }
}

// ---------------------------------------------------------------- join

/** Step 1 of joining: pick the owner to ask. */
@Composable
private fun JoinScreen(onJoined: (String) -> Unit) {
    val context = LocalContext.current
    val dir = remember { File(context.filesDir, "tailnet") }

    var ownerNpub by remember { mutableStateOf<String?>(null) }
    var ownerField by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var deviceNpub by remember { mutableStateOf<String?>(null) }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { extractNpub(it) }?.let {
            ownerNpub = it
            failed = false
        } ?: toast(context, "that QR does not contain a noscat npub")
    }

    if (ownerNpub == null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("noscat", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Join a tailnet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Ask the device that owns your tailnet for access. " +
                    "Its QR appears when 'noscat up --tailnet' starts.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = {
                    qrScanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("scan the owner device's QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan the owner's QR") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f))
                Text(
                    "  or  ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(Modifier.weight(1f))
            }

            OutlinedTextField(
                value = ownerField,
                onValueChange = { ownerField = it.trim() },
                label = { Text("Paste the owner npub") },
                placeholder = { Text("npub1...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    val np = extractNpub(ownerField)
                    if (np == null) {
                        toast(context, "that does not look like a npub")
                    } else {
                        ownerNpub = np
                        failed = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Use this owner") }
        }
        return
    }

    // Step 2: automatic. Publish this phone's key package (addressed to the
    // owner) and wait for their invitation - the phone joins by itself once
    // the owner approves, then the client restarts on the tailnet identity.
    LaunchedEffect(ownerNpub, attempt) {
        failed = false
        val republish = launch {
            while (isActive) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        tailnetPrepare(dir.absolutePath, ownerNpub)
                    }
                }.onSuccess { if (deviceNpub == null) deviceNpub = it }
                if (isActive) delay(30_000)
            }
        }
        val res = withContext(Dispatchers.IO) {
            runCatching { tailnetJoin(dir.absolutePath, 180uL, ownerNpub) }
        }
        republish.cancel()
        res.fold(
            { members ->
                AppPrefs.setTailnetDir(context, dir.absolutePath)
                val secret = withContext(Dispatchers.IO) {
                    File(dir, "identity.nsec").readText().trim()
                }
                withContext(Dispatchers.IO) {
                    ClientHolder.restart(secret, dir.absolutePath)
                }
                ClientHolder.logLine("joined: ${members.size} device(s)")
                onJoined(dir.absolutePath)
            },
            {
                ClientHolder.logLine("join failed: $it")
                failure = it.message
                failed = true
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        if (failed) {
            Text("Could not join", style = MaterialTheme.typography.headlineSmall)
            Text(
                failure ?: "the invitation never arrived",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Make sure the owner device is running 'noscat up --tailnet' and approves this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            OutlinedButton(
                onClick = {
                    ownerNpub = null
                    deviceNpub = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Change owner") }
        } else {
            Text("Joining", style = MaterialTheme.typography.headlineSmall)
            CircularProgressIndicator(Modifier.size(48.dp))
            Text(
                "Waiting for the owner to approve",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "On the owner device, answer 'y' when it asks to accept this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            deviceNpub?.let { npub ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "This phone's address",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Card(
                    onClick = { copy(context, npub) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        npub,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Text(
                    "tap to copy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = {
                ownerNpub = null
                deviceNpub = null
            }) { Text("Cancel") }
        }
    }
}

// ------------------------------------------------------------- devices

/** The tailnet home: devices, transfers, inbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(tailnetDir: String, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val members = remember { mutableStateOf<List<TailnetMember>?>(null) }
    var selectedNpub by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var showForward by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    val transfer = ClientHolder.transfer
    val inboxVersion = ClientHolder.inboxVersion

    // This phone is a tailnet member too (its device identity) - dialing
    // yourself is meaningless, so it never appears in the list.
    val myNpub = ClientHolder.client?.let { it.npub() }
    fun others(list: List<TailnetMember>) = list.filter { it.npub != myNpub }

    fun syncDevices(waitSecs: ULong) {
        scope.launch {
            syncing = true
            val res = withContext(Dispatchers.IO) {
                runCatching { tailnetSync(tailnetDir, waitSecs) }
            }
            syncing = false
            res.fold(
                { list ->
                    val visible = others(list)
                    members.value = visible
                    if (selectedNpub == null || visible.none { it.npub == selectedNpub }) {
                        selectedNpub = visible.firstOrNull()?.npub
                    }
                },
                { ClientHolder.logLine("sync failed: ${it.message}") },
            )
        }
    }

    LaunchedEffect(tailnetDir) {
        val res = withContext(Dispatchers.IO) {
            runCatching { tailnetMembers(tailnetDir) }
        }
        res.fold(
            { list ->
                val visible = others(list)
                members.value = visible
                selectedNpub = visible.firstOrNull()?.npub
            },
            { ClientHolder.logLine("cannot read tailnet: ${it.message}") },
        )
    }

    // The inbox re-lists whenever a transfer lands.
    val inbox = remember { mutableStateOf(inboxFiles(context)) }
    LaunchedEffect(inboxVersion) { inbox.value = inboxFiles(context) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            when {
                f == null -> toast(context, "could not read that file")
                selectedNpub == null -> toast(context, "select a device first")
                else -> ClientHolder.sendFile(f.absolutePath, selectedNpub!!)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("noscat", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            ConnectionChip(connected = members.value != null)
        }

        // Devices
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            if (syncing) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            TextButton(onClick = { syncDevices(5uL) }) { Text("Refresh") }
        }

        val list = members.value
        when {
            list == null -> Text(
                "loading devices...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.isEmpty() -> Text(
                "no other devices in this tailnet yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> list.forEach { m ->
                DeviceCard(
                    member = m,
                    selected = selectedNpub == m.npub,
                    onSelect = { selectedNpub = m.npub },
                )
            }
        }

        // Transfer progress card
        transfer?.let { t ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (t.incoming) "Receiving ${t.name}" else "Sending ${t.name}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (!t.incoming && t.total > 0) {
                        LinearProgressIndicator(
                            progress = { t.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${t.percent}%  (${t.sent / 1024} of ${t.total / 1024} KB)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Actions
        val selected = findMember(members.value, selectedNpub)
        val selectedLabel = selected?.let(::memberLabel) ?: short(selectedNpub)
        Text("Send to $selectedLabel", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Send a file") }
        OutlinedButton(
            onClick = { showForward = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Forward a service") }

        // Inbox: inbound files land here on their own (node mode).
        Text("Inbox", style = MaterialTheme.typography.titleMedium)
        Text(
            "files sent to this phone land here automatically",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val files = inbox.value
        if (files.isEmpty()) {
            Text(
                "nothing received yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            files.forEach { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        f.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatSize(f.length()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { showDiagnostics = true }) { Text("Diagnostics") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { confirmDisconnect = true },
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("Disconnect") }
        }
    }

    if (showForward) {
        ForwardDialog(
            target = selectedNpub ?: "",
            onDismiss = { showForward = false },
        )
    }
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            confirmButton = { Button(onClick = { showDiagnostics = false }) { Text("Close") } },
            title = { Text("Diagnostics") },
            text = {
                val lines = ClientHolder.log.take(40)
                if (lines.isEmpty()) {
                    Text("no events yet")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(lines) { line ->
                            Text(line, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
        )
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from the tailnet?") },
            text = { Text("You can rejoin later by scanning the owner's QR again.") },
            confirmButton = {
                Button(onClick = {
                    confirmDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDisconnect = false }) { Text("Cancel") }
            },
        )
    }
}

/** Connected indicator in the top bar. */
@Composable
private fun ConnectionChip(connected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                if (connected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                CircleShape,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (connected) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (connected) "Connected" else "Connecting",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** One tailnet device: address, owner badge, selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(member: TailnetMember, selected: Boolean, onSelect: () -> Unit) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Text(
                    memberLabel(member),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    member.npub.let { "${it.take(12)}...${it.takeLast(6)}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ForwardDialog(target: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var port by remember { mutableStateOf("8080") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forward a service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Dial ${short(target)} and reach its service at localhost:<port> on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Local port") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = ClientHolder.client
                val p = port.toUShortOrNull()
                when {
                    c == null -> toast(context, "client is not running")
                    p == null -> toast(context, "enter a port")
                    else -> scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching { c.forward(target, p) }
                        }
                        ok.fold(
                            {
                                ClientHolder.logLine("forward dialled: localhost:$p -> ${target.take(16)}...")
                                onDismiss()
                            },
                            { toast(context, "forward failed: ${it.message}") },
                        )
                    }
                }
            }) { Text("Connect") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ----------------------------------------------------------------- chat

/** Chat: pick a device to talk to, then a one-message-at-a-time thread. */
@Composable
private fun ChatScreen(tailnetDir: String) {
    val myNpub = ClientHolder.client?.let { it.npub() }
    var chatting by remember { mutableStateOf<String?>(null) }

    val members = remember { mutableStateOf<List<TailnetMember>?>(null) }
    LaunchedEffect(tailnetDir) {
        val res = withContext(Dispatchers.IO) {
            runCatching { tailnetMembers(tailnetDir) }
        }
        res.fold(
            { list -> members.value = list.filter { it.npub != myNpub } },
            { ClientHolder.logLine("cannot read tailnet: ${it.message}") },
        )
    }

    val peer = chatting
    if (peer == null) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Chat", style = MaterialTheme.typography.titleLarge)
            val list = members.value
            when {
                list == null -> Text(
                    "loading devices...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.isEmpty() -> Text(
                    "no other devices in this tailnet yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> list.forEach { m ->
                    Card(
                        onClick = { chatting = m.npub },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                memberLabel(m),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                ClientHolder.chatWith(m.npub).lastOrNull()?.text
                                    ?: "no messages yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        return
    }

    ConversationScreen(
        peer = peer,
        peerLabel = members.value?.firstOrNull { it.npub == peer }?.let(::memberLabel)
            ?: short(peer),
        onBack = { chatting = null },
    )
}

/** The thread with one device: bubbles plus a send box. */
@Composable
private fun ConversationScreen(peer: String, peerLabel: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val msgs = ClientHolder.chatWith(peer)

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text("< Back") }
            Column {
                Text(peerLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    peer.let { "${it.take(12)}...${it.takeLast(6)}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        if (msgs.isEmpty()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight(1f))
                Text("no messages yet", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "say hi - messages go device to device, encrypted, peer to peer",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
            }
        } else {
            // reverseLayout pins the newest message at the bottom.
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                reverseLayout = true,
            ) {
                items(msgs.size, key = { msgs[msgs.size - 1 - it].at }) { i ->
                    val m = msgs[msgs.size - 1 - i]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (m.outgoing) {
                            Arrangement.End
                        } else {
                            Arrangement.Start
                        },
                    ) {
                        Surface(
                            color = when {
                                m.failed -> MaterialTheme.colorScheme.errorContainer
                                m.outgoing -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(
                                if (m.failed) "${m.text}  (not delivered)" else m.text,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        ClientHolder.sendChat(peer, text)
                    }
                },
            ) { Text("Send") }
        }
    }
}

// ------------------------------------------------------------- helpers

/** Short npub for headings: first bytes and an ellipsis. */
private fun short(npub: String?): String =
    npub?.let { "${it.take(12)}..." } ?: "selected device"

/** The best label for a device: its friendly name, else a generic one. */
private fun memberLabel(member: TailnetMember): String =
    member.name?.takeIf { it.isNotBlank() }
        ?: if (member.isOwner) "Owner device" else "Device"

/** Look a member up by npub in the loaded roster. */
private fun findMember(members: List<TailnetMember>?, npub: String?): TailnetMember? =
    members?.firstOrNull { it.npub == npub }

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)} MB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)} KB"
    else -> "$bytes B"
}

/** Files received into the app inbox, newest first. */
private fun inboxFiles(context: Context): List<File> {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
}

/**
 * Pulls a bare npub out of scanned or pasted content: accepts a plain npub or
 * a "noscat:npub1..." URI (what the noscat CLI prints), and cuts anything
 * that follows the npub (query params such as relay hints, whitespace).
 */
private fun extractNpub(raw: String): String? {
    val fromScheme = raw.trim().removePrefix("noscat:").trim()
    val start = fromScheme.indexOf("npub1")
    if (start < 0) return null
    var s = fromScheme.substring(start)
    val end = s.indexOfFirst { it == '?' || it == '&' || it.isWhitespace() }
    if (end >= 0) s = s.substring(0, end)
    return s.ifEmpty { null }
}

/** Copies the picked content into the cache dir; sendFile needs a real path. */
private fun copyToCache(context: Context, uri: Uri): File? = runCatching {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        c.moveToFirst()
        c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            .let(c::getString)
            .takeIf { it.isNotBlank() }
    } ?: "picked.bin"
    val out = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use(input::copyTo)
    } ?: return null
    out
}.getOrNull()

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("npub", text))
    toast(context, "copied")
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
