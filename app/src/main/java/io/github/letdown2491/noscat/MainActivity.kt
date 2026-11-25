package io.github.letdown2491.noscat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import uniffi.noscat.MeshMember
import uniffi.noscat.generateIdentity
import uniffi.noscat.meshJoin
import uniffi.noscat.meshMembers
import uniffi.noscat.meshPrepare
import uniffi.noscat.meshSync

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClientHolder.init(this)
        ThemeState.load(this)
        NoscatService.start(this)
        NavRequest.from(intent)
        // Android 13+: notifications (messages, files, join requests) need
        // the user's yes; asked once, the app works either way.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { NoscatApp() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        NavRequest.from(intent)
    }

    override fun onStart() {
        super.onStart()
        ClientHolder.inForeground = true
    }

    override fun onStop() {
        ClientHolder.inForeground = false
        super.onStop()
    }
}

/** Where a tapped notification asks the app to go. */
object NavRequest {
    var chat by mutableStateOf<String?>(null)
    var tab by mutableStateOf<String?>(null)

    fun from(intent: android.content.Intent?) {
        intent ?: return
        intent.getStringExtra(Notifier.EXTRA_CHAT)?.let { chat = it }
        intent.getStringExtra(Notifier.EXTRA_TAB)?.let { tab = it }
    }
}

/** The short form of an npub that join screens and approve prompts show
 * to compare: "npub1abcdefg...uvwxyz". */
fun shortId(npub: String): String =
    if (npub.length > 20) "${npub.take(12)}...${npub.takeLast(6)}" else npub

/** The active theme, observable by Compose and persisted in prefs. Dark
 * is the default; Settings offers light and follow-the-system too. */
object ThemeState {
    var mode by mutableStateOf(AppPrefs.THEME_DARK)

    fun load(context: Context) {
        mode = AppPrefs.theme(context)
    }

    fun set(context: Context, value: String) {
        mode = value
        AppPrefs.setTheme(context, value)
    }
}

@Composable
private fun NoscatApp() {
    val view = LocalView.current
    val dark = when (ThemeState.mode) {
        AppPrefs.THEME_LIGHT -> false
        AppPrefs.THEME_SYSTEM -> isSystemInDarkTheme()
        else -> true
    }
    // Status bar icons follow the theme: light icons over the dark scheme,
    // dark icons over the light one.
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
    }
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
    ) {
        Scaffold { padding -> Root(Modifier.padding(padding)) }
    }
}

@Composable
private fun Root(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var meshDir by remember { mutableStateOf<String?>(null) }
    var screenReady by remember { mutableStateOf(false) }

    // One-time startup: the foreground service owns the client's lifetime,
    // but start it here too so the UI is usable immediately (idempotent).
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) { ClientHolder.startFromPrefs(context) }
            meshDir = AppPrefs.meshDir(context)
        }.onFailure { ClientHolder.logLine("startup failed: $it") }
        screenReady = true
    }

    if (!screenReady) return

    if (meshDir != null) {
        var tab by remember { mutableStateOf("devices") }
        // The conversation to open in the chat tab (device rows can jump
        // straight into one). null = the chat device list.
        var chatPeer by remember { mutableStateOf<String?>(null) }
        // Files-tab badge: inbox arrivals since the tab was last open.
        var seenInbox by remember { mutableStateOf(ClientHolder.inboxVersion) }
        LaunchedEffect(ClientHolder.inboxVersion, tab) {
            if (tab == "files") seenInbox = ClientHolder.inboxVersion
        }
        val newFiles = ClientHolder.inboxVersion - seenInbox
        // A tapped notification: open its conversation or tab.
        LaunchedEffect(NavRequest.chat, NavRequest.tab) {
            NavRequest.chat?.let {
                chatPeer = it
                tab = "chat"
                NavRequest.chat = null
            }
            NavRequest.tab?.let {
                tab = it
                NavRequest.tab = null
            }
        }
        // Go offline but stay a member; the chip or Settings reconnects.
        // Shared by every screen's chip.
        val disconnect: () -> Unit = {
            scope.launch { withContext(Dispatchers.IO) { ClientHolder.disconnect(context) } }
        }
        // After Forget (the mesh is left and the device deleted): run
        // standalone and drop back to the join flow.
        val forgotten: () -> Unit = {
            scope.launch {
                val secret = withContext(Dispatchers.IO) {
                    IdentityStore.load(context) ?: generateIdentity().secretHex.also {
                        IdentityStore.save(context, it)
                    }
                }
                withContext(Dispatchers.IO) { ClientHolder.restart(secret, null) }
                meshDir = null
            }
        }
        Scaffold(
            bottomBar = {
                // Five tabs, Devices in the center and slightly larger:
                // it is the hub the other tabs orbit (chat, forwards).
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == "chat",
                        onClick = { tab = "chat" },
                        icon = {
                            if (ClientHolder.unreadTotal > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge { Text("${ClientHolder.unreadTotal}") }
                                    },
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                    )
                                }
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                )
                            }
                        },
                        label = { Text("Chat") },
                    )
                    NavigationBarItem(
                        selected = tab == "files",
                        onClick = { tab = "files" },
                        icon = {
                            if (newFiles > 0) {
                                BadgedBox(
                                    badge = { Badge { Text("$newFiles") } },
                                ) {
                                    Icon(Icons.Filled.Folder, contentDescription = null)
                                }
                            } else {
                                Icon(Icons.Filled.Folder, contentDescription = null)
                            }
                        },
                        label = { Text("Files") },
                    )
                    NavigationBarItem(
                        selected = tab == "devices",
                        onClick = { tab = "devices" },
                        icon = {
                            Icon(
                                Icons.Filled.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                            )
                        },
                        label = { Text("Devices") },
                    )
                    NavigationBarItem(
                        selected = tab == "services",
                        onClick = { tab = "services" },
                        icon = {
                            if (ClientHolder.forwards.isNotEmpty()) {
                                BadgedBox(
                                    badge = { Badge { Text("${ClientHolder.forwards.size}") } },
                                ) {
                                    Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                                }
                            } else {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null)
                            }
                        },
                        label = { Text("Services") },
                    )
                    NavigationBarItem(
                        selected = tab == "settings",
                        onClick = { tab = "settings" },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (tab) {
                    "files" -> FilesScreen(onDisconnect = disconnect)
                    "services" -> ServicesScreen(onDisconnect = disconnect)
                    "settings" -> SettingsScreen(onDisconnect = disconnect, onForgotten = forgotten)
                    "devices" -> DevicesScreen(
                        meshDir = meshDir!!,
                        onOpenChat = { npub ->
                            chatPeer = npub
                            tab = "chat"
                        },
                        onDisconnect = disconnect,
                    )
                    else -> ChatScreen(
                        meshDir = meshDir!!,
                        openPeer = chatPeer,
                        onOpenPeer = { chatPeer = it },
                        onChatClosed = { chatPeer = null },
                        onDisconnect = disconnect,
                    )
                }
            }
        }
    } else {
        JoinScreen(
            onJoined = { dir -> meshDir = dir },
        )
    }
}

// ---------------------------------------------------------------- join

/** Step 1 of joining: pick the mesh device to ask. */
@Composable
private fun JoinScreen(onJoined: (String) -> Unit) {
    val context = LocalContext.current
    // ClientHolder.startFromPrefs owns the pre-rename directory migration;
    // a device that is not joined has no legacy dir, so just use the name.
    val dir = remember { File(context.filesDir, "device") }

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
                "Join a mesh",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Ask a device in your mesh for access. " +
                    "Its QR appears when 'noscat up' starts (or run 'noscat id').",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = {
                    qrScanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("scan the QR of a device in the mesh")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan a mesh device's QR") }

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
                label = { Text("Paste a mesh device's npub") },
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
            ) { Text("Use this device") }
        }
        return
    }

    // Step 2: automatic. meshJoin runs the device, sends the scanned
    // device a join request and waits for its invitation - the phone joins
    // by itself once that device approves, then the client restarts on the
    // mesh identity.
    LaunchedEffect(ownerNpub, attempt) {
        // The scanned device is the trust anchor: invitations from anyone
        // else are refused. (ownerNpub is that device; the name is 0.4's.)
        val owner = ownerNpub ?: return@LaunchedEffect
        failed = false
        val republish = launch {
            while (isActive) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        meshPrepare(dir.absolutePath, ownerNpub, null)
                    }
                }.onSuccess { if (deviceNpub == null) deviceNpub = it }
                if (isActive) delay(30_000)
            }
        }
        val res = withContext(Dispatchers.IO) {
            runCatching { meshJoin(dir.absolutePath, 180uL, owner, null) }
        }
        republish.cancel()
        res.fold(
            { members ->
                AppPrefs.setMeshDir(context, dir.absolutePath)
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
                "Make sure that device is running and approves this phone: on its Devices page in the apps, or the invite line 'noscat up' prints.",
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
            ) { Text("Change device") }
        } else {
            Text("Joining", style = MaterialTheme.typography.headlineSmall)
            CircularProgressIndicator(Modifier.size(48.dp))
            Text(
                "Waiting for an invite",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "That device shows a join request: approve it on its Devices page (or with the invite line 'noscat up' prints).",
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

/** The mesh home: who is on the mesh and what they can do. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(
    meshDir: String,
    onOpenChat: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The roster lives in ClientHolder (persisted, synced on a timer in
    // the background, self-filtered of this phone). This screen only
    // observes it; tab switches never trigger anything.
    val members = ClientHolder.roster
    // Which device row launched the file picker (send target).
    var sendTarget by remember { mutableStateOf<String?>(null) }
    // Which device the forward dialog targets (row action), or null = closed.
    var forwardTarget by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            when {
                f == null -> toast(context, "could not read that file")
                sendTarget == null -> toast(context, "select a device first")
                else -> withContext(Dispatchers.IO) {
                    ClientHolder.sendFile(f.absolutePath, sendTarget!!)
                }
            }
        }
    }

    // Pull down to refresh: the manual override for the background sync.
    PullToRefreshBox(
        isRefreshing = ClientHolder.syncing,
        onRefresh = { ClientHolder.syncRoster() },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Devices: the connection chip stays the row's right edge, as on
        // every tab.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Devices", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            ConnectionChip(connected = ClientHolder.client != null, onDisconnect = onDisconnect)
        }

        // Join requests: a device that scanned this phone's QR asks to be
        // invited. Its name is its own claim, so the short ID is shown to
        // compare with the joining device's screen.
        ClientHolder.joinRequests.forEach { r ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${r.name?.takeIf { it.isNotBlank() } ?: "A device"} wants to join the mesh",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "device ID ${shortId(r.npub)}. approve only a device you are " +
                            "setting up: check this ID on its screen",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { ClientHolder.declineJoin(r.npub) }) { Text("Decline") }
                        Button(
                            onClick = { ClientHolder.approveJoin(r.npub) },
                            enabled = !r.approving && ClientHolder.client != null,
                        ) { Text(if (r.approving) "Inviting..." else "Approve") }
                    }
                }
            }
        }

        val list = members
        when {
            list == null -> Text(
                "loading devices...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.isEmpty() -> Text(
                "no other devices in this mesh yet. on the new device, join " +
                    "with this phone's QR (Settings), then approve its request here",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> {
                list.forEach { m ->
                    DeviceCard(
                        member = m,
                        onSend = {
                            sendTarget = m.npub
                            filePicker.launch(arrayOf("*/*"))
                        },
                        onChat = { onOpenChat(m.npub) },
                        onForward = { forwardTarget = m.npub },
                    )
                }
                // This device, too: your npub is copyable from the same
                // list your peers' npubs live in.
                val selfNpub = ClientHolder.myNpub
                if (selfNpub != null) {
                    SelfCard(
                        name = AppPrefs.deviceName(context)?.takeIf { it.isNotBlank() }
                            ?: android.os.Build.MODEL,
                        npub = selfNpub,
                    )
                }
            }
        }
    }

    if (forwardTarget != null) {
        ForwardDialog(
            target = forwardTarget ?: "",
            onDismiss = { forwardTarget = null },
        )
    }
    }
}

/** Services: the port forwards this phone is running - a peer's exposed
 * service reaches this phone at localhost:<port>. The desktop app's
 * Services tab, dial-out half; expose (publishing this phone's services)
 * is phase 2. */
@Composable
private fun ServicesScreen(onDisconnect: () -> Unit) {
    // The holder's registry reconciles itself via ForwardStopped; this
    // picks up anything that changed since the tab was last open. An
    // in-memory snapshot, not a network call.
    LaunchedEffect(Unit) { ClientHolder.refreshForwards() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Services", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            ConnectionChip(connected = ClientHolder.client != null, onDisconnect = onDisconnect)
        }
        val roster = ClientHolder.roster
        val forwards = ClientHolder.forwards
        if (forwards.isEmpty()) {
            Text(
                "nothing forwarded yet. tap the forward icon on a device " +
                    "in the Devices tab: that device's service then reaches " +
                    "this phone at localhost:<port>",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        forwards.forEach { f ->
            val label = roster?.firstOrNull { it.npub == f.target }
                ?.let(::memberLabel) ?: short(f.target)
            val localPort = f.local.substringAfterLast(':')
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "localhost:$localPort",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "via $label",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { ClientHolder.cancelForward(f.id) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Stop forward localhost:$localPort",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Files: Received (the inbox, with who sent each file) and Sent (the
 * send history), each with its live transfers and progress on top. */
@Composable
private fun FilesScreen(onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The inbox re-lists whenever a transfer lands (or a file is deleted).
    val inboxVersion = ClientHolder.inboxVersion
    val inbox = remember { mutableStateOf(inboxFiles(context)) }
    LaunchedEffect(inboxVersion) {
        inbox.value = inboxFiles(context)
        Log.d("noscat", "relist v=$inboxVersion n=${inbox.value.size}")
    }

    // Delete: confirm first, then trash with an undo snackbar. The file
    // hides in .trash until the next app start, so Undo works even after
    // the snackbar is gone.
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<File?>(null) }

    Box(Modifier.fillMaxSize()) {
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
                Text("Files", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                ConnectionChip(connected = ClientHolder.client != null, onDisconnect = onDisconnect)
            }

            // Received | Sent, each with its live transfers on top.
            var side by rememberSaveable { mutableStateOf("received") }
            val incoming = side == "received"
            val transfers = ClientHolder.transfers
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = incoming,
                    onClick = { side = "received" },
                    label = { Text("Received") },
                )
                FilterChip(
                    selected = !incoming,
                    onClick = { side = "sent" },
                    label = { Text("Sent") },
                )
                Spacer(Modifier.weight(1f))
                if (!incoming) {
                    TextButton(
                        enabled = transfers.any {
                            !it.incoming && it.status != ClientHolder.TransferStatus.ACTIVE
                        },
                        onClick = { ClientHolder.clearSent() },
                    ) { Text("Clear history") }
                }
            }

            val roster = ClientHolder.roster
            val nameOf = { npub: String ->
                roster?.firstOrNull { it.npub == npub }?.let(::memberLabel) ?: "device"
            }
            // Sends of any state; receives only while they are under way
            // (a finished one is an inbox file below).
            val rows = transfers.asReversed().filter {
                it.incoming == incoming &&
                    (!incoming || (it.live && it.status != ClientHolder.TransferStatus.DONE))
            }
            rows.forEach { t -> TransferRow(t, nameOf(t.peer), scope) }

            if (incoming) {
                // Received files land here on their own (node mode).
                val files = inbox.value
                if (files.isNotEmpty()) {
                    Text(
                        "${files.size} file${if (files.size == 1) "" else "s"}, " +
                            formatSize(files.sumOf { it.length() }),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (files.isEmpty() && rows.isEmpty()) {
                    Text(
                        "nothing received yet. files sent to this phone land here " +
                            "even when the app is closed, and tapping one opens it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                files.forEach { f ->
                    val from = ClientHolder.senderOf(f)?.let(nameOf)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { openInboxFile(context, f) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            fileIcon(f.name),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                f.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    from?.let { "from $it" },
                                    formatSize(f.length()),
                                    whenText(f.lastModified()),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { confirmDelete = f }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Delete ${f.name}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (rows.isEmpty()) {
                Text(
                    "nothing sent yet. send a file from a device on the Devices tab; " +
                        "the last ${ClientHolder.SENT_KEEP} sends are kept here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("${f.name} will be removed from the inbox. you can undo for a few seconds after.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    if (ClientHolder.trashInboxFile(context, f)) {
                        scope.launch {
                            val r = snackbar.showSnackbar(
                                message = "deleted ${f.name}",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Long,
                            )
                            if (r == SnackbarResult.ActionPerformed) {
                                ClientHolder.restoreInboxFile(context, f.name)
                            }
                        }
                    } else {
                        toast(context, "could not delete ${f.name}")
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Settings: connection status, device name, and the diagnostics log -
 * the app plumbing the content tabs don't show. Disconnect lives on the
 * connection chip (click it), not here.
 */
@Composable
private fun SettingsScreen(onDisconnect: () -> Unit, onForgotten: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var forgetError by remember { mutableStateOf<String?>(null) }

    // Device name: what peers see in their rosters and chats. Empty falls
    // back to the phone model. Saving restarts the node to re-advertise.
    val currentName = AppPrefs.deviceName(context)
        ?: android.os.Build.MODEL.takeIf { it.isNotBlank() }
        ?: ""
    var nameField by remember { mutableStateOf(currentName) }

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
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            ConnectionChip(
                connected = ClientHolder.client != null,
                onDisconnect = onDisconnect,
            )
        }

        // Connection state lives on the chip above; the npub is copyable
        // from this device's row on the Devices tab.
        Spacer(Modifier.height(4.dp))

        // Theme: dark is the default, applied app-wide immediately.
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ThemeState.mode == AppPrefs.THEME_DARK,
                onClick = { ThemeState.set(context, AppPrefs.THEME_DARK) },
                label = { Text("Dark") },
            )
            FilterChip(
                selected = ThemeState.mode == AppPrefs.THEME_LIGHT,
                onClick = { ThemeState.set(context, AppPrefs.THEME_LIGHT) },
                label = { Text("Light") },
            )
            FilterChip(
                selected = ThemeState.mode == AppPrefs.THEME_SYSTEM,
                onClick = { ThemeState.set(context, AppPrefs.THEME_SYSTEM) },
                label = { Text("System") },
            )
        }

        // Device name.
        Text("Device name", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val name = nameField.trim()
                    scope.launch {
                        withContext(Dispatchers.IO) { ClientHolder.rename(context, name) }
                        toast(context, "saved - members see it in a few seconds")
                    }
                },
                enabled = nameField.trim() != currentName,
            ) { Text("Save") }
        }
        Text(
            "shown on your peers' device lists; saving restarts the node",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Diagnostics: the event log, with copy for bug reports.
        Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
        val lines = ClientHolder.log.take(40)
        if (lines.isEmpty()) {
            Text("no events yet", style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TextButton(onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(
                ClipData.newPlainText("noscat log", ClientHolder.log.joinToString("\n")),
            )
            toast(context, "log copied")
        }) { Text("Copy log") }

        // Disconnect / reconnect: also on the connection chip; repeated
        // here so the support answer "Settings > Disconnect" always works.
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (ClientHolder.offline) {
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ClientHolder.reconnect(context) }
                    }
                },
                enabled = !forgetting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reconnect to mesh") }
        } else {
            Button(
                onClick = { confirmDisconnect = true },
                enabled = !forgetting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect from mesh") }
        }
        // Forget: leave for good. Works while disconnected too.
        OutlinedButton(
            onClick = { forgetError = null; confirmForget = true },
            enabled = !forgetting,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (forgetting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Leaving the mesh...")
            } else {
                Text("Forget mesh")
            }
        }
        forgetError?.let {
            Text(
                "Could not leave the mesh ($it). Nothing was deleted; try again when online.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this mesh?") },
            text = {
                Text(
                    "The other devices remove this phone from the mesh, and its " +
                        "identity and mesh data are deleted here. Received files stay. " +
                        "To come back, join again as a new device.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirmForget = false
                    forgetting = true
                    scope.launch {
                        val err = withContext(Dispatchers.IO) { ClientHolder.forget(context) }
                        forgetting = false
                        if (err == null) onForgotten() else forgetError = err
                    }
                }) { Text("Forget") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect from the mesh?") },
            text = { Text(DISCONNECT_TEXT) },
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

private const val DISCONNECT_TEXT =
    "This phone goes offline but stays in the mesh; reconnect any time. " +
        "To leave the mesh for good, use Forget mesh in Settings."

/** Connected indicator in the top bar. Clicking it offers disconnect, or
 * reconnect while disconnected: the pill shows the connection, so it acts
 * on the connection too. */
@Composable
private fun ConnectionChip(connected: Boolean, onDisconnect: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offline = ClientHolder.offline
    var confirm by remember { mutableStateOf(false) }
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
            .clickable(enabled = (connected || offline) && onDisconnect != null) { confirm = true }
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
            when {
                connected -> "Connected"
                offline -> "Disconnected"
                else -> "Connecting"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
    if (confirm && offline) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Reconnect to the mesh?") },
            text = { Text("This phone comes back online as the same device.") },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { ClientHolder.reconnect(context) }
                    }
                }) { Text("Reconnect") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    } else if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Disconnect from the mesh?") },
            text = { Text(DISCONNECT_TEXT) },
            confirmButton = {
                Button(onClick = {
                    confirm = false
                    onDisconnect?.invoke()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirm = false }) { Text("Cancel") }
            },
        )
    }
}

/** One mesh device: name, presence, selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceCard(
    member: MeshMember,
    onSend: () -> Unit,
    onChat: () -> Unit,
    onForward: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copyNpub = {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("npub", member.npub))
        toast(context, "npub copied")
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            // Ticks so a silent peer's advert ages out while the list is open.
            val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
                while (true) {
                    delay(30_000)
                    value = System.currentTimeMillis()
                }
            }
            PresenceDot(member.presence(now))
            Spacer(Modifier.width(6.dp))
            // The name is the npub copy affordance: tap it and the full
            // npub is on the clipboard.
            Text(
                memberLabel(member),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = copyNpub),
            )
            // Row-level actions, in the desktop app's order: chat, send a
            // file, forward a service, share this phone's clipboard.
            IconButton(onClick = onChat) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Chat with ${memberLabel(member)}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onSend) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = "Send a file to ${memberLabel(member)}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onForward) {
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = "Forward a service of ${memberLabel(member)}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrEmpty()) {
                    toast(context, "clipboard is empty")
                } else {
                    // Delivered toast fires on the ChatDelivered event;
                    // the send returns before the peer even answers.
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            ClientHolder.sendClipboard(member.npub, text)
                        }
                    }
                }
            }) {
                Icon(
                    Icons.Filled.ContentPaste,
                    contentDescription = "Paste this phone's clipboard to ${memberLabel(member)}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
/** Matches noscat-core's presence window: members advertise every two
 * minutes, so an advert younger than ten means the peer is up. */
private const val ADVERT_TTL_SECS: ULong = 600uL

/** Online per the mesh presence: the peer's advert is fresh, aged forward
 * from when the roster was read so a silent peer goes stale between syncs. */
private fun MeshMember.fresh(nowMs: Long): Boolean {
    val elapsed = ((nowMs - ClientHolder.rosterAgeBase).coerceAtLeast(0) / 1000).toULong()
    return lastSeenSecs?.let { it + elapsed < ADVERT_TTL_SECS } ?: false
}

/** What a device's dot says. */
private enum class Presence { Connected, Unreachable, Offline }

/** Green: data reaches it now (a direct link or live connection). Amber:
 * its advert arrives over the relays but nothing connects (another network
 * with no reachable member). Red: offline. */
private fun MeshMember.presence(nowMs: Long): Presence = when {
    connected -> Presence.Connected
    fresh(nowMs) -> Presence.Unreachable
    else -> Presence.Offline
}

/** Presence dot: green connected, amber online but unreachable, red
 * offline. Absence of color would read as "not loaded yet", so offline is
 * stated outright. */
@Composable
private fun PresenceDot(presence: Presence) {
    Box(
        Modifier
            .size(8.dp)
            .background(
                when (presence) {
                    Presence.Connected -> Color(0xFF2E7D32)
                    Presence.Unreachable -> Color(0xFFF9A825)
                    Presence.Offline -> Color(0xFFB3261E)
                },
                CircleShape,
            )
            .semantics {
                contentDescription = when (presence) {
                    Presence.Connected -> "connected"
                    Presence.Unreachable -> "online, can't connect"
                    Presence.Offline -> "offline"
                }
            },
    )
}

/** This device, on the list too: copy your own npub from the same place
 * your peers' npubs live. No actions beyond that - it is you. */
@Composable
private fun SelfCard(name: String, npub: String) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Match the device rows: content starts at 8dp, and the chip's
            // right edge lines up with the last action icon's glyph (an
            // IconButton's visual sits 12dp inside its 48dp touch target).
            modifier = Modifier.padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // You are online exactly when the node is: the app is running,
            // listening, and advertising.
            PresenceDot(if (ClientHolder.client != null) Presence.Connected else Presence.Offline)
            Spacer(Modifier.width(6.dp))
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("npub", npub))
                        toast(context, "npub copied")
                    },
            )
            // A "You" badge, not actions: this row is you.
            Text(
                "You",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
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
                val p = port.toUShortOrNull()
                when {
                    p == null -> toast(context, "enter a port")
                    else -> scope.launch {
                        withContext(Dispatchers.IO) { ClientHolder.forward(target, p) }
                        onDismiss()
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
private fun ChatScreen(
    meshDir: String,
    openPeer: String?,
    onOpenPeer: (String) -> Unit,
    onChatClosed: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val myNpub = ClientHolder.myNpub

    // The roster lives in ClientHolder (persisted, synced on a timer in
    // the background). No fetch here, just observation.
    val members = ClientHolder.roster

    val peer = openPeer
    if (peer == null) {
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
                Text("Chat", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                ConnectionChip(connected = ClientHolder.client != null, onDisconnect = onDisconnect)
            }
            val list = members
            when {
                list == null -> Text(
                    "loading devices...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                list.isEmpty() -> Text(
                    "no other devices in this mesh yet. once a device " +
                        "joins, chat with it here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> list.forEach { m ->
                    Card(
                        onClick = { onOpenPeer(m.npub) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
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
                            // Unread marker only: presence dots live on
                            // the Devices list. Here a colored dot reads
                            // as "new message", so anything else misleads.
                            if ((ClientHolder.unread[m.npub] ?: 0) > 0) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    ConversationScreen(
        peer = peer,
        peerLabel = members?.firstOrNull { it.npub == peer }?.let(::memberLabel)
            ?: short(peer),
        onBack = onChatClosed,
    )
}

/** The thread with one device: bubbles plus a send box. */
@Composable
private fun ConversationScreen(peer: String, peerLabel: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    val msgs = ClientHolder.chatWith(peer)

    // This conversation is on screen: nothing from this peer counts as
    // unread (re-armed when the message count changes).
    LaunchedEffect(peer, msgs.size) { ClientHolder.markRead(peer) }
    // On screen: no notifications for this conversation, and any shown go.
    val context = LocalContext.current
    DisposableEffect(peer) {
        ClientHolder.visibleChat = peer
        Notifier.cancelChat(context, peer)
        onDispose { if (ClientHolder.visibleChat == peer) ClientHolder.visibleChat = null }
    }

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
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    m.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (m.outgoing) {
                                    // Delivery state: pending (sent, no echo
                                    // yet), a tick once the member
                                    // acknowledged it, red when it never did.
                                    Text(
                                        when {
                                            m.failed -> "not delivered"
                                            m.delivered -> "delivered ✓"
                                            else -> "sending…"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (m.failed) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                }
                            }
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
private fun memberLabel(member: MeshMember): String =
    member.name?.takeIf { it.isNotBlank() }
        ?: "Device"

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "${bytes / (1 shl 20)} MB"
    bytes >= 1 shl 10 -> "${bytes / (1 shl 10)} KB"
    else -> "$bytes B"
}

/** Files received into the app inbox, newest first. The trash directory
 * is not inbox content. */
private fun inboxFiles(context: Context): List<File> {
    val dir = context.getExternalFilesDir(null) ?: context.filesDir
    // Partials and their resume sidecars are not files yet.
    return dir.listFiles()
        ?.filter {
            it.isFile && !it.name.startsWith(".") &&
                !it.name.endsWith(".noscat-part") && !it.name.endsWith(".noscat-sha")
        }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
}

/** "14:05" today, "Nov 24, 2026" before. */
private fun whenText(at: Long): String = android.text.format.DateUtils.formatSameDayTime(
    at,
    System.currentTimeMillis(),
    java.text.DateFormat.MEDIUM,
    java.text.DateFormat.SHORT,
).toString()

/** A send (any state) or a receive under way: progress while active,
 * Stop for an active send, Send again for one that did not finish. */
@Composable
private fun TransferRow(
    t: ClientHolder.TransferEntry,
    peer: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val active = t.status == ClientHolder.TransferStatus.ACTIVE
    val (state, color) = when (t.status) {
        ClientHolder.TransferStatus.ACTIVE ->
            (if (t.sent == 0L) "connecting" else "${t.percent}%") to
                MaterialTheme.colorScheme.onSurfaceVariant
        ClientHolder.TransferStatus.DONE ->
            (if (t.incoming) "received" else "delivered") to Color(0xFF22C55E)
        // Incomplete is not a failure: sending the same file again resumes.
        ClientHolder.TransferStatus.PARTIAL -> "partial, will resume" to Color(0xFFEAB308)
        ClientHolder.TransferStatus.CANCELLED -> "stopped" to Color(0xFFEAB308)
        ClientHolder.TransferStatus.FAILED -> "failed" to MaterialTheme.colorScheme.error
    }
    val size = if (active && t.total > 0) {
        "${formatSize(t.sent)} of ${formatSize(t.total)}"
    } else {
        formatSize(t.total)
    }
    val path = t.path
    val resendable = !t.incoming && !active && t.status != ClientHolder.TransferStatus.DONE &&
        path != null && File(path).exists()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    fileIcon(t.name),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${if (t.incoming) "from" else "to"} $peer · $size · ${whenText(t.at)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    state,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (active) {
                // Determinate once bytes move; a sweeping bar while it connects.
                if (t.sent > 0 && t.total > 0) {
                    LinearProgressIndicator(
                        progress = { t.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if ((active && !t.incoming) || resendable) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (active) {
                        // The receiver keeps its resumable partial.
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ClientHolder.cancelTransfer(path ?: return@withContext, t.peer)
                                }
                            }
                        }) { Text("Stop") }
                    } else if (path != null) {
                        TextButton(onClick = { ClientHolder.sendFile(path, t.peer) }) {
                            Text("Send again")
                        }
                    }
                }
            }
        }
    }
}

/** Guess a MIME type from a file's extension ("" when unknown). */
/** The icon for a received file, by extension (case-insensitive): the
 * shape alone should say what kind of thing landed. Unknown extensions
 * get the generic file icon. */
private fun fileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Filled.Image
        "mp4", "mov", "mkv", "webm", "avi" -> Icons.Filled.Movie
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> Icons.Filled.MusicNote
        "pdf" -> Icons.Filled.PictureAsPdf
        "doc", "docx", "odt", "pages" -> Icons.Filled.Description
        "xls", "xlsx", "csv", "ods", "numbers" -> Icons.Filled.TableChart
        "ppt", "pptx", "odp", "key" -> Icons.Filled.Slideshow
        "zip", "tar", "gz", "7z", "rar", "bz2", "xz" -> Icons.Filled.FolderZip
        "kt", "rs", "py", "js", "ts", "c", "cpp", "java", "sh", "toml", "json" -> Icons.Filled.Code
        "txt", "md", "log" -> Icons.Filled.TextSnippet
        "apk" -> Icons.Filled.Android
        else -> Icons.Filled.InsertDriveFile
    }
}

private fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
}

/**
 * Open a received inbox file in whatever app handles its type.
 *
 * The inbox (Android/data) is invisible to file managers on modern Android,
 * so "open the file manager at that path" is not portable. The portable
 * path is a FileProvider content URI plus ACTION_VIEW: every device
 * resolves it to an installed viewer for the type (image, PDF, audio, a
 * text editor...), falling back to a chooser and then to a plain message
 * when nothing installed can open it.
 */
private fun openInboxFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeFor(file.name).ifEmpty { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No default handler for the type: let the user pick one.
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (_: ActivityNotFoundException) {
            toast(context, "no app can open this file type")
        }
    }
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
