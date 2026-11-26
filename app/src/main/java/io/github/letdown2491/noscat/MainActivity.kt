package io.github.letdown2491.noscat

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.noscat.AccessGroup
import uniffi.noscat.AccessRule
import uniffi.noscat.MeshMember
import uniffi.noscat.generateIdentity
import uniffi.noscat.meshJoin
import uniffi.noscat.meshPrepare

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

    override fun onNewIntent(intent: Intent) {
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

    fun from(intent: Intent?) {
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
    // Status and navigation bar icons follow the theme.
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = noscatColors(dark)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Root()
        }
    }
}

/** The four tabs, in the desktop sidebar's order. Settings is not a tab:
 * it opens from the mesh sheet, like the desktop's pinned Settings. */
private enum class Tab(val key: String, val label: String) {
    Devices("devices", "Devices"),
    Chat("chat", "Chat"),
    Files("files", "Files"),
    Services("services", "Services"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root() {
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

    if (meshDir == null) {
        JoinScreen(onJoined = { dir -> meshDir = dir })
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Devices) }
    // The open conversation (Chat tab), or null for the list.
    var chatPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var inviteOpen by remember { mutableStateOf(false) }
    // Services' New forward: the device picker dialog.
    var newForward by remember { mutableStateOf(false) }
    // Files: Received or Sent (the top bar's menu acts on Sent).
    var filesSide by rememberSaveable { mutableStateOf("received") }
    var filesMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Files-tab badge: inbox arrivals since the tab was last open.
    var seenInbox by remember { mutableStateOf(ClientHolder.inboxVersion) }
    LaunchedEffect(ClientHolder.inboxVersion, tab) {
        if (tab == Tab.Files) seenInbox = ClientHolder.inboxVersion
    }
    val newFiles = ClientHolder.inboxVersion - seenInbox

    // A tapped notification: open its conversation or tab.
    LaunchedEffect(NavRequest.chat, NavRequest.tab) {
        NavRequest.chat?.let {
            chatPeer = it
            tab = Tab.Chat
            settingsOpen = false
            NavRequest.chat = null
        }
        NavRequest.tab?.let { key ->
            if (key == "settings") {
                settingsOpen = true
            } else {
                Tab.entries.firstOrNull { it.key == key }?.let { tab = it }
                settingsOpen = false
            }
            NavRequest.tab = null
        }
    }

    val inConversation = tab == Tab.Chat && chatPeer != null && !settingsOpen
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = inConversation) { chatPeer = null }

    // Disconnect is undone by Reconnect, so it acts at once (as on
    // desktop); Forget is the one that asks.
    val disconnect: () -> Unit = {
        scope.launch { withContext(Dispatchers.IO) { ClientHolder.disconnect(context) } }
    }
    val reconnect: () -> Unit = {
        scope.launch { withContext(Dispatchers.IO) { ClientHolder.reconnect(context) } }
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
            settingsOpen = false
            meshDir = null
        }
    }
    val openChat: (String) -> Unit = { npub ->
        chatPeer = npub
        tab = Tab.Chat
    }

    // A terminal takes the whole screen while it is open; Back closes it.
    ClientHolder.terminal?.let { t ->
        TerminalScreen(t, onClose = { ClientHolder.closeTerminal() })
        return
    }

    Scaffold(
        topBar = {
            when {
                settingsOpen -> TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { settingsOpen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
                inConversation -> ConversationTopBar(chatPeer!!, onBack = { chatPeer = null })
                else -> TopAppBar(
                    title = { Text(tab.label) },
                    actions = {
                        // Sent history: clearing it is rare, so it lives
                        // in the overflow menu, not beside the switch.
                        if (tab == Tab.Files && filesSide == "sent") {
                            Box {
                                IconButton(onClick = { filesMenu = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More options")
                                }
                                DropdownMenu(expanded = filesMenu, onDismissRequest = { filesMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Clear sent history") },
                                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                        enabled = ClientHolder.transfers.any {
                                            !it.incoming && it.status != ClientHolder.TransferStatus.ACTIVE
                                        },
                                        onClick = { filesMenu = false; confirmClear = true },
                                    )
                                }
                            }
                        }
                        MeshButton(onClick = { sheetOpen = true })
                    },
                )
            }
        },
        bottomBar = {
            if (!settingsOpen && !inConversation) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        val count = when (t) {
                            Tab.Chat -> ClientHolder.unreadTotal
                            Tab.Devices -> ClientHolder.joinRequests.size
                            Tab.Files -> newFiles
                            Tab.Services -> 0
                        }
                        val selected = tab == t
                        NavigationBarItem(
                            selected = selected,
                            onClick = { tab = t },
                            icon = {
                                BadgedBox(badge = { if (count > 0) Badge { Text("$count") } }) {
                                    Icon(tabIcon(t, selected), contentDescription = null)
                                }
                            },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // New forward: the empty state carries the button until the
            // first forward exists.
            if (!settingsOpen && tab == Tab.Services && ClientHolder.forwards.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { newForward = true },
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("New forward") },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .fillMaxSize(),
        ) {
            when {
                settingsOpen -> SettingsScreen(
                    onInvite = { inviteOpen = true },
                    onDisconnect = disconnect,
                    onReconnect = reconnect,
                    onForgotten = forgotten,
                )
                tab == Tab.Devices -> DevicesScreen(onOpenChat = openChat, onInvite = { inviteOpen = true })
                tab == Tab.Chat -> {
                    val peer = chatPeer
                    if (peer == null) {
                        ChatListScreen(onOpen = { chatPeer = it }, onInvite = { inviteOpen = true })
                    } else {
                        ConversationScreen(peer)
                    }
                }
                tab == Tab.Files -> FilesScreen(side = filesSide, onSide = { filesSide = it })
                tab == Tab.Services -> ServicesScreen(onNewForward = { newForward = true })
            }
        }
    }

    if (sheetOpen) {
        MeshSheet(
            onDismiss = { sheetOpen = false },
            onInvite = { sheetOpen = false; inviteOpen = true },
            onSettings = { sheetOpen = false; settingsOpen = true },
            onDisconnect = { sheetOpen = false; disconnect() },
            onReconnect = { sheetOpen = false; reconnect() },
        )
    }
    if (inviteOpen) InviteDialog(onDismiss = { inviteOpen = false })
    if (newForward) ForwardDialog(target = null, onDismiss = { newForward = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear sent history?") },
            text = { Text("The list of sent files is cleared. The files themselves are not affected.") },
            confirmButton = {
                Button(onClick = { confirmClear = false; ClientHolder.clearSent() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Selected tabs fill their icon, the rest are outlined (Material's
 * convention). */
private fun tabIcon(t: Tab, selected: Boolean): ImageVector = when (t) {
    Tab.Devices -> if (selected) Icons.Filled.Devices else Icons.Outlined.Devices
    Tab.Chat -> if (selected) Icons.AutoMirrored.Filled.Chat else Icons.AutoMirrored.Outlined.Chat
    Tab.Files -> if (selected) Icons.Filled.Folder else Icons.Outlined.Folder
    Tab.Services -> if (selected) Icons.Filled.SwapHoriz else Icons.Outlined.SwapHoriz
}

// ---------------------------------------------------------------- join

/** Joining: paste or scan a mesh device's pairing code, then wait for
 * that device to approve this phone. */
@Composable
private fun JoinScreen(onJoined: (String) -> Unit) {
    val context = LocalContext.current
    // ClientHolder.startFromPrefs owns the pre-rename directory migration;
    // a device that is not joined has no legacy dir, so just use the name.
    val dir = remember { File(context.filesDir, "device") }

    var ownerNpub by remember { mutableStateOf<String?>(null) }
    // The whole scanned or pasted code: a private mesh's QR also names its
    // relays, and the join goes through them.
    var ownerTarget by remember { mutableStateOf<String?>(null) }
    var ownerField by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var deviceNpub by remember { mutableStateOf<String?>(null) }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { raw -> extractNpub(raw)?.let { raw.trim() to it } }?.let { (raw, np) ->
            ownerNpub = np
            ownerTarget = raw
            failed = false
        } ?: if (result.contents != null) toast(context, "That code is not a noscat pairing code") else Unit
    }

    val column = Modifier
        .fillMaxSize()
        .systemBarsPadding()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp)

    if (ownerNpub == null) {
        val pasted = extractNpub(ownerField)
        Column(column, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Logo(56.dp)
            Text("Join a mesh", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Ask a device in your mesh to let this phone in. On that device, open " +
                    "Invite a device (or run noscat mesh qr on a computer), then scan or paste its code.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    qrScanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the pairing code of a device in the mesh")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan pairing code")
            }
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
                label = { Text("Pairing code") },
                placeholder = { Text("noscat:npub1...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    ownerNpub = pasted
                    ownerTarget = ownerField
                    failed = false
                },
                enabled = pasted != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Continue") }
        }
        return
    }

    // Step 2: automatic. meshJoin runs the device, sends the scanned
    // device a join request and waits for its invitation; the phone joins
    // by itself once that device approves, then the client restarts on the
    // mesh identity.
    LaunchedEffect(ownerNpub, attempt) {
        // The scanned device is the trust anchor: invitations from anyone
        // else are refused.
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
            runCatching { meshJoin(dir.absolutePath, 180uL, ownerTarget ?: owner, null) }
        }
        republish.cancel()
        res.fold(
            { members ->
                AppPrefs.setMeshDir(context, dir.absolutePath)
                // No secret: the client reads the device directory's own
                // identity (the file is sealed under the storage key, so
                // the app must never read it as text).
                withContext(Dispatchers.IO) {
                    ClientHolder.restart(null, dir.absolutePath)
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

    val restart = {
        ownerNpub = null
        ownerTarget = null
        deviceNpub = null
    }
    Column(
        column,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        if (failed) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp),
            )
            Text("Could not join", style = MaterialTheme.typography.headlineSmall)
            Text(
                failure?.let(ClientHolder::scrub) ?: "The approval never arrived.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Make sure that device is running, then approve this phone on its Devices page " +
                    "(on a computer: noscat mesh requests).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { attempt++ }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Try again")
            }
            OutlinedButton(onClick = restart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Use another code")
            }
        } else {
            CircularProgressIndicator(Modifier.size(48.dp))
            Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall)
            Text(
                "The device you scanned now shows a join request on its Devices page. " +
                    "Approve it there; this phone joins on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            deviceNpub?.let { npub ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Check that the request shows this ID",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        shortId(npub),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = restart) { Text("Cancel") }
        }
    }
}

// ------------------------------------------------------------- devices

/** The mesh home: this phone, then the other devices and what each can
 * do. Tapping a device opens its chat; its icons send a file, forward a
 * port and share the clipboard; long-press lists them all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(onOpenChat: (String) -> Unit, onInvite: () -> Unit) {
    val context = LocalContext.current
    val now = rememberNow()
    val members = ClientHolder.roster
    var forwardTarget by remember { mutableStateOf<String?>(null) }
    var clipTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var renaming by remember { mutableStateOf(false) }
    val pickFile = rememberFileSender()

    PullToRefreshBox(
        isRefreshing = ClientHolder.syncing,
        onRefresh = { ClientHolder.syncRoster() },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            // Join requests on top: a device that scanned this phone's code
            // asks to join. Its name is its own claim, so the short ID is
            // shown to compare with the joining device's screen.
            items(ClientHolder.joinRequests.toList(), key = { "req-${it.npub}" }) { r ->
                JoinRequestCard(r)
            }

            item(key = "self-label") { SectionLabel("This phone") }
            item(key = "self") {
                val selfNpub = ClientHolder.myNpub
                val name = AppPrefs.deviceName(context)?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
                ListItem(
                    leadingContent = {
                        Avatar(
                            selfNpub ?: name,
                            name,
                            presence = if (meshState() == MeshState.Connected) Presence.Connected else Presence.Offline,
                        )
                    },
                    headlineContent = { Text(name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(if (meshState() == MeshState.Connected) "this phone" else "this phone, not connected")
                    },
                    trailingContent = {
                        IconButton(onClick = { renaming = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rename this phone")
                        }
                    },
                )
            }

            item(key = "others-label") { SectionLabel("Other devices") }
            val list = members
            when {
                list == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                list.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        Icons.Outlined.PersonAdd,
                        "No other devices yet",
                        "Show a device this phone's pairing code to add it.",
                    ) {
                        Button(onClick = onInvite) {
                            Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Invite a device")
                        }
                    }
                }
                else -> items(list, key = { it.npub }) { m ->
                    DeviceRow(
                        member = m,
                        now = now,
                        onChat = { onOpenChat(m.npub) },
                        onSend = { pickFile(m.npub) },
                        onForward = { forwardTarget = m.npub },
                        onClipboard = {
                            val text = clipboardText(context)
                            if (text.isNullOrEmpty()) toast(context, "The clipboard is empty")
                            else clipTarget = m.npub to text
                        },
                        onTerminal = if (m.control) {
                            { ClientHolder.openTerminal(m.npub, memberLabel(m)) }
                        } else null,
                    )
                }
            }
        }
    }

    forwardTarget?.let { ForwardDialog(target = it, onDismiss = { forwardTarget = null }) }
    clipTarget?.let { (peer, text) ->
        ClipboardDialog(peer, text, onDismiss = { clipTarget = null })
    }
    if (renaming) RenameDialog(onDismiss = { renaming = false })
}

@Composable
private fun JoinRequestCard(r: ClientHolder.JoinReq) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    "${r.name?.takeIf { it.isNotBlank() } ?: "A device"} wants to join",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                "Approve only a device you are setting up, and check that its screen shows this ID:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(shortId(r.npub), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { ClientHolder.declineJoin(r.npub) }) { Text("Decline") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { ClientHolder.approveJoin(r.npub) },
                    enabled = !r.approving && ClientHolder.client != null,
                ) { Text(if (r.approving) "Inviting..." else "Approve") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    member: MeshMember,
    now: Long,
    onChat: () -> Unit,
    onSend: () -> Unit,
    onForward: () -> Unit,
    onClipboard: () -> Unit,
    onTerminal: (() -> Unit)?,
) {
    val name = memberLabel(member)
    val refused = ClientHolder.terminalRefused[member.npub]
    val presence = member.presence(now)
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(onClick = onChat, onLongClick = { menu = true }),
            leadingContent = { Avatar(member.npub, name, presence = presence) },
            headlineContent = {
                Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(
                        member.presenceText(now),
                        color = if (presence == Presence.Unreachable) Palette.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    SendStatus(member.npub, Modifier.padding(top = 4.dp))
                }
            },
            trailingContent = {
                Row {
                    IconButton(onClick = onSend) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = "Send a file to $name")
                    }
                    IconButton(onClick = onForward) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = "Forward a port to $name's shared service")
                    }
                    IconButton(onClick = onClipboard) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Share this phone's clipboard with $name")
                    }
                    // Only devices that offer one (computers). After a
                    // refusal it greys out; trying again is still allowed,
                    // since the device may have changed its mind.
                    if (onTerminal != null) {
                        IconButton(onClick = onTerminal) {
                            Icon(
                                Icons.Outlined.Terminal,
                                contentDescription = refused ?: "Open a terminal on $name",
                                tint = if (refused != null) MaterialTheme.colorScheme.outline else LocalContentColor.current,
                            )
                        }
                    }
                }
            },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Chat") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                onClick = { menu = false; onChat() },
            )
            DropdownMenuItem(
                text = { Text("Send a file") },
                leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                onClick = { menu = false; onSend() },
            )
            DropdownMenuItem(
                text = { Text("Forward a port") },
                leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null) },
                onClick = { menu = false; onForward() },
            )
            DropdownMenuItem(
                text = { Text("Share clipboard") },
                leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                onClick = { menu = false; onClipboard() },
            )
            if (onTerminal != null) {
                DropdownMenuItem(
                    text = { Text(if (refused != null) "Open a terminal (refused before)" else "Open a terminal") },
                    leadingIcon = { Icon(Icons.Outlined.Terminal, null) },
                    onClick = { menu = false; onTerminal() },
                )
            }
        }
    }
}

/** Send progress where the user acted: under a device's row and above
 * its conversation's input. "Sending photo.jpg · 42%" with a thin bar,
 * then "Sent photo.jpg" for a moment. Failures are toasts. */
@Composable
private fun SendStatus(peer: String, modifier: Modifier = Modifier) {
    val sends = ClientHolder.transfers.filter { !it.incoming && it.live && it.peer == peer }
    val active = sends.filter { it.status == ClientHolder.TransferStatus.ACTIVE }
    val doneIds = sends.filter { it.status == ClientHolder.TransferStatus.DONE }.map { it.id }.toSet()
    var seen by remember(peer) { mutableStateOf<Set<Long>?>(null) }
    var justSent by remember(peer) { mutableStateOf<String?>(null) }
    LaunchedEffect(doneIds) {
        val before = seen
        seen = doneIds
        if (before != null) {
            sends.lastOrNull { it.status == ClientHolder.TransferStatus.DONE && it.id !in before }?.let {
                justSent = it.name
                delay(4_000)
                justSent = null
            }
        }
    }
    if (active.isNotEmpty()) {
        val sent = active.sumOf { it.sent }
        val total = active.sumOf { it.total }
        val what = if (active.size == 1) active[0].name else "${active.size} files"
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (sent == 0L) "Sending $what · connecting" else "Sending $what · ${(sent * 100 / total.coerceAtLeast(1))}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sent > 0 && total > 0) {
                LinearProgressIndicator(progress = { sent.toFloat() / total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    } else {
        justSent?.let {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Success, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "Sent $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Success,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A file picker that sends the picked file to the given device. */
@Composable
private fun rememberFileSender(): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val peer = target ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { copyToCache(context, uri) }
            if (f == null) {
                toast(context, "Could not read that file")
            } else {
                withContext(Dispatchers.IO) { ClientHolder.sendFile(f.absolutePath, peer) }
            }
        }
    }
    return { peer ->
        target = peer
        picker.launch(arrayOf("*/*"))
    }
}

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Share the clipboard, after showing what is on it: the clipboard can
 * hold anything, so nothing leaves the phone unseen. */
@Composable
private fun ClipboardDialog(peer: String, text: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val name = ClientHolder.nameOf(peer)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
        title = { Text("Share clipboard with $name?") },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                // Delivered toast fires on the ChatDelivered event.
                scope.launch { withContext(Dispatchers.IO) { ClientHolder.sendClipboard(peer, text) } }
            }) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = AppPrefs.deviceName(context) ?: android.os.Build.MODEL.orEmpty()
    var field by remember { mutableStateOf(current) }
    val name = field.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename this phone") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text("Name") },
                supportingText = { Text("How other devices see this phone.") },
            )
        },
        confirmButton = {
            Button(
                enabled = name.isNotEmpty() && name != current,
                onClick = {
                    onDismiss()
                    scope.launch { withContext(Dispatchers.IO) { ClientHolder.rename(context, name) } }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Start a port forward: a port on this phone reaches the service a
 * device shares. From a device row the device is known; from Services
 * the dialog asks which. */
@Composable
private fun ForwardDialog(target: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val roster = ClientHolder.roster.orEmpty()
    var device by remember { mutableStateOf(target ?: roster.singleOrNull()?.npub) }
    var port by remember { mutableStateOf("8080") }
    val p = port.toUShortOrNull()?.takeIf { it > 0u }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
        title = { Text(if (target != null) "Forward to ${ClientHolder.nameOf(target)}" else "New forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "A port on this phone reaches the service the device shares.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (target == null) {
                    Column {
                        roster.forEach { m ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = device == m.npub, onClick = { device = m.npub })
                                    .padding(vertical = 4.dp),
                            ) {
                                RadioButton(selected = device == m.npub, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Avatar(m.npub, memberLabel(m), size = 28.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(memberLabel(m))
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port on this phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = p != null && device != null,
                onClick = {
                    val d = device ?: return@Button
                    val pp = p ?: return@Button
                    scope.launch {
                        withContext(Dispatchers.IO) { ClientHolder.forward(d, pp) }
                        onDismiss()
                    }
                },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------ services

/** Services: the port forwards this phone runs; each makes a port here
 * reach a device's shared service. Expose (serving from the phone) is
 * phase 2. */
@Composable
private fun ServicesScreen(onNewForward: () -> Unit) {
    val context = LocalContext.current
    // The holder's registry reconciles itself via ForwardStopped; this
    // picks up anything that changed since the tab was last open.
    LaunchedEffect(Unit) { ClientHolder.refreshForwards() }
    val forwards = ClientHolder.forwards
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        if (forwards.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.SwapHoriz,
                    "No port forwards",
                    "A forward makes a port on this phone reach a service another device shares.",
                ) {
                    Button(onClick = onNewForward, enabled = !ClientHolder.roster.isNullOrEmpty()) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New forward")
                    }
                }
            }
        } else {
            item { SectionLabel("Port forwards") }
            items(forwards.toList(), key = { it.id.toString() }) { f ->
                val localPort = f.local.substringAfterLast(':')
                val address = "localhost:$localPort"
                ListItem(
                    leadingContent = { IconTile(Icons.Outlined.SwapHoriz) },
                    headlineContent = { Text(address, fontWeight = FontWeight.Medium) },
                    supportingContent = { Text("reaches ${ClientHolder.nameOf(f.target)}'s shared service") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { copy(context, address, "Address copied") }) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $address")
                            }
                            TextButton(onClick = { ClientHolder.cancelForward(f.id) }) { Text("Stop") }
                        }
                    },
                )
            }
        }
    }
}

/** A list row's leading icon on a tonal tile. */
@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --------------------------------------------------------------- files

/** Files: Received (the inbox, with who sent each file) and Sent (the
 * send history), each with its live transfers on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(side: String, onSide: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The inbox re-lists whenever a transfer lands (or a file is deleted).
    val inboxVersion = ClientHolder.inboxVersion
    val inbox = remember { mutableStateOf(inboxFiles(context)) }
    LaunchedEffect(inboxVersion) { inbox.value = inboxFiles(context) }

    // Delete: confirm first, then trash with an undo snackbar. The file
    // hides in .trash until the next app start, so Undo works even after
    // the snackbar is gone.
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf<File?>(null) }
    val incoming = side == "received"
    val transfers = ClientHolder.transfers

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        listOf("received" to "Received", "sent" to "Sent").forEachIndexed { i, (key, label) ->
                            SegmentedButton(
                                selected = side == key,
                                onClick = { onSide(key) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                }
            }

            // Sends of any state; receives only while under way (a
            // finished one is an inbox file below).
            val rows = transfers.asReversed().filter {
                it.incoming == incoming &&
                    (!incoming || (it.live && it.status != ClientHolder.TransferStatus.DONE))
            }
            items(rows, key = { "t-${it.incoming}-${it.id}-${it.at}" }) { t ->
                TransferRow(t, ClientHolder.nameOf(t.peer), scope)
            }

            if (incoming) {
                val files = inbox.value
                if (files.isEmpty() && rows.isEmpty()) {
                    item {
                        EmptyState(
                            Icons.Outlined.FolderOpen,
                            "Nothing received yet",
                            "Files your devices send land here, even while the app is closed.",
                        )
                    }
                } else if (files.isNotEmpty()) {
                    item {
                        SectionLabel(
                            "${files.size} file${if (files.size == 1) "" else "s"} · ${formatSize(files.sumOf { it.length() })}",
                        )
                    }
                }
                items(files, key = { "f-${it.name}" }) { f ->
                    val from = ClientHolder.senderOf(f)?.let(ClientHolder::nameOf)
                    ListItem(
                        modifier = Modifier.clickable { openInboxFile(context, f) },
                        leadingContent = { IconTile(fileIcon(f.name)) },
                        headlineContent = {
                            Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(from?.let { "from $it" }, formatSize(f.length()), whenText(f.lastModified()))
                                    .joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { confirmDelete = f }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete ${f.name}")
                            }
                        },
                    )
                }
            } else if (rows.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.Upload,
                        "Nothing sent yet",
                        "Send a file from a device on Devices, or from a conversation.",
                    )
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete file?") },
            text = { Text("${f.name} is removed from this phone. You can undo for a few seconds.") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        confirmDelete = null
                        if (ClientHolder.trashInboxFile(context, f)) {
                            scope.launch {
                                val r = snackbar.showSnackbar(
                                    message = "Deleted ${f.name}",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Long,
                                )
                                if (r == SnackbarResult.ActionPerformed) {
                                    ClientHolder.restoreInboxFile(context, f.name)
                                }
                            }
                        } else {
                            toast(context, "Could not delete ${f.name}")
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

/** A transfer: its state at the right as an icon carrying the colour and
 * a quiet word, progress while active, Stop or Send again. */
@Composable
private fun TransferRow(
    t: ClientHolder.TransferEntry,
    peer: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val active = t.status == ClientHolder.TransferStatus.ACTIVE
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (icon, tint, word) = when (t.status) {
        ClientHolder.TransferStatus.ACTIVE -> Triple(null, muted, if (t.sent == 0L) "Connecting" else "${t.percent}%")
        ClientHolder.TransferStatus.DONE ->
            Triple(Icons.Outlined.CheckCircle, Palette.Success, if (t.incoming) "Received" else "Delivered")
        // Incomplete is not a failure: sending the same file again resumes.
        ClientHolder.TransferStatus.PARTIAL -> Triple(Icons.Outlined.PauseCircle, Palette.Warning, "Incomplete")
        ClientHolder.TransferStatus.CANCELLED -> Triple(Icons.Outlined.Cancel, muted, "Stopped")
        ClientHolder.TransferStatus.FAILED -> Triple(Icons.Outlined.ErrorOutline, MaterialTheme.colorScheme.error, "Failed")
    }
    val size = if (active && t.total > 0) "${formatSize(t.sent)} of ${formatSize(t.total)}" else formatSize(t.total)
    val path = t.path
    val resendable = !t.incoming && !active && t.status != ClientHolder.TransferStatus.DONE &&
        path != null && File(path).exists()
    Column {
        ListItem(
            leadingContent = { IconTile(fileIcon(t.name)) },
            headlineContent = {
                Text(t.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    "${if (t.incoming) "from" else "to"} $peer · $size · ${whenText(t.at)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        word,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t.status == ClientHolder.TransferStatus.FAILED) tint else muted,
                    )
                    if (active && !t.incoming) {
                        // The receiver keeps its resumable partial.
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ClientHolder.cancelTransfer(path ?: return@withContext, t.peer)
                                }
                            }
                        }) { Icon(Icons.Outlined.StopCircle, contentDescription = "Stop sending") }
                    } else if (resendable) {
                        IconButton(onClick = { ClientHolder.sendFile(path!!, t.peer) }) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = if (t.status == ClientHolder.TransferStatus.PARTIAL) {
                                    "Send again (resumes where it stopped)"
                                } else {
                                    "Send again"
                                },
                            )
                        }
                    }
                }
            },
        )
        if (active) {
            // Determinate once bytes move; a sweeping bar while it connects.
            val bar = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 8.dp)
            if (t.sent > 0 && t.total > 0) {
                LinearProgressIndicator(progress = { t.percent / 100f }, modifier = bar)
            } else {
                LinearProgressIndicator(bar)
            }
        }
    }
}

// ------------------------------------------------------------ settings

/** Settings, grouped as on the desktop: This phone (name, pairing code),
 * Appearance, Diagnostics, and the Danger zone (Disconnect, reversible
 * and immediate; Forget mesh, behind a confirm). */
@Composable
private fun SettingsScreen(
    onInvite: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onForgotten: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmForget by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var forgetError by remember { mutableStateOf<String?>(null) }

    // Device name: what other devices see. Empty falls back to the model.
    var currentName by remember {
        mutableStateOf(AppPrefs.deviceName(context) ?: android.os.Build.MODEL.orEmpty())
    }
    var nameField by remember { mutableStateOf(currentName) }
    var savedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(savedAt) {
        if (savedAt > 0) {
            delay(2_000)
            savedAt = 0
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsCard("This phone") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Name", style = MaterialTheme.typography.titleSmall)
                val name = nameField.trim()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nameField,
                        onValueChange = { nameField = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = name.isNotEmpty() && name != currentName,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ClientHolder.rename(context, name) }
                                currentName = name
                                savedAt = System.currentTimeMillis()
                            }
                        },
                    ) { Text("Save") }
                }
                if (savedAt > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Check, null, tint = Palette.Success, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Saved", style = MaterialTheme.typography.bodySmall, color = Palette.Success)
                    }
                } else {
                    Text(
                        "How other devices see this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            CardDivider()
            SettingRow("Pairing code", "Scan or paste it on a new device to join.") {
                OutlinedButton(onClick = onInvite, enabled = ClientHolder.myNpub != null) { Text("Show") }
            }
        }

        AccessCard()

        SettingsCard("Appearance") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        AppPrefs.THEME_SYSTEM to "System",
                        AppPrefs.THEME_LIGHT to "Light",
                        AppPrefs.THEME_DARK to "Dark",
                    )
                    options.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = ThemeState.mode == mode,
                            onClick = { ThemeState.set(context, mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
            }
        }

        // Diagnostics: the event log, collapsed until asked for (it is
        // long and only useful for bug reports), with copy either way.
        SettingsCard("Diagnostics") {
            var showLog by rememberSaveable { mutableStateOf(false) }
            SettingRow("Event log", "${ClientHolder.log.size} events, for bug reports.") {
                Row {
                    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide" else "Show") }
                    TextButton(onClick = {
                        copy(context, ClientHolder.log.joinToString("\n"), "Log copied")
                    }) { Text("Copy") }
                }
            }
            if (showLog) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val lines = ClientHolder.log.take(40)
                    if (lines.isEmpty()) {
                        Text("No events yet", style = MaterialTheme.typography.bodySmall)
                    }
                    lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SettingsCard("Danger zone", danger = true) {
            if (ClientHolder.offline) {
                SettingRow("Reconnect", "Bring this phone back online as the same device.") {
                    Button(onClick = onReconnect, enabled = !forgetting) { Text("Reconnect") }
                }
            } else {
                SettingRow("Disconnect", "Go offline. This phone stays in the mesh; Reconnect brings it back.") {
                    OutlinedButton(onClick = onDisconnect, enabled = !forgetting) { Text("Disconnect") }
                }
            }
            CardDivider()
            // Forget: leave for good. Works while disconnected too.
            SettingRow("Forget mesh", "Leave the mesh and delete this phone's identity and mesh data.") {
                Button(
                    onClick = { forgetError = null; confirmForget = true },
                    enabled = !forgetting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (forgetting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Leaving")
                    } else {
                        Text("Forget")
                    }
                }
            }
            forgetError?.let {
                Text(
                    "Could not leave the mesh ($it). Nothing was deleted; try again when online.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this mesh?") },
            text = {
                Text(
                    "The other devices remove this phone from the mesh, and its " +
                        "identity, chats and mesh data are deleted here. Received files stay. " +
                        "To come back, join again as a new device.",
                )
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        confirmForget = false
                        forgetting = true
                        scope.launch {
                            val err = withContext(Dispatchers.IO) { ClientHolder.forget(context) }
                            forgetting = false
                            if (err == null) onForgotten() else forgetError = err
                        }
                    },
                ) { Text("Forget mesh") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

/** A titled card of settings rows. */
/** Who may send things to this phone (files, chat, clipboard). A phone
 * serves no ports and hosts no terminal, so "send things" is the only
 * group that applies here. Rules stay on this phone. */
@Composable
private fun AccessCard() {
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    val members = ClientHolder.roster.orEmpty().filter { !it.thisDevice }.displayOrder()
    val keys = members.map { it.npub }
    val rules by produceState<AccessView?>(null, version, keys, ClientHolder.client) {
        value = withContext(Dispatchers.IO) {
            val defaults = ClientHolder.accessDefaults() ?: return@withContext null
            val custom = ClientHolder.accessCustom()
            AccessView(
                everyone = defaults.send == AccessRule.ALLOW,
                members = keys.associateWith { (ClientHolder.accessOf(it) ?: defaults).send == AccessRule.ALLOW },
                custom = custom,
            )
        }
    }
    val set: (String?, Boolean?) -> Unit = { npub, allow ->
        scope.launch {
            withContext(Dispatchers.IO) {
                if (npub != null && allow == null) ClientHolder.resetAccess(npub)
                else ClientHolder.setAccess(npub, AccessGroup.SEND, if (allow == true) AccessRule.ALLOW else AccessRule.DENY)
            }
            version++
        }
    }
    SettingsCard("Access") {
        Text(
            "Which devices can send files, messages and clipboard text to this phone. The choice stays on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        val r = rules
        SettingRow("Every device", "Unless set below") {
            Switch(checked = r?.everyone ?: true, enabled = r != null, onCheckedChange = { set(null, it) })
        }
        members.forEach { m ->
            CardDivider()
            val custom = r != null && m.npub in r.custom
            SettingRow(memberLabel(m), if (custom) "Set for this device" else "As every device") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (custom) {
                        TextButton(onClick = { set(m.npub, null) }) { Text("Reset") }
                    }
                    Switch(
                        checked = r?.members?.get(m.npub) ?: true,
                        enabled = r != null,
                        onCheckedChange = { set(m.npub, it) },
                    )
                }
            }
        }
    }
}

private data class AccessView(val everyone: Boolean, val members: Map<String, Boolean>, val custom: Set<String>)

@Composable
private fun SettingsCard(title: String, danger: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp),
            border = if (danger) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)) else null,
            modifier = Modifier.fillMaxWidth(),
        ) { Column(content = content) }
    }
}

/** A setting: title and description on the left, its control on the right. */
@Composable
private fun SettingRow(title: String, description: String?, control: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

// ----------------------------------------------------------------- chat

/** Every device, latest conversation first, then device order. */
@Composable
private fun ChatListScreen(onOpen: (String) -> Unit, onInvite: () -> Unit) {
    val now = rememberNow()
    val list = ClientHolder.roster
    LazyColumn(Modifier.fillMaxSize()) {
        when {
            list == null -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            list.isEmpty() -> item {
                EmptyState(
                    Icons.AutoMirrored.Outlined.Chat,
                    "No one to chat with yet",
                    "Once another device joins the mesh, chat with it here.",
                ) {
                    Button(onClick = onInvite) { Text("Invite a device") }
                }
            }
            else -> {
                // A stable sort keeps device order among the never-chatted.
                val sorted = list.sortedByDescending { ClientHolder.chatWith(it.npub).lastOrNull()?.at ?: 0L }
                items(sorted, key = { it.npub }) { m ->
                    val last = ClientHolder.chatWith(m.npub).lastOrNull()
                    val unread = ClientHolder.unread[m.npub] ?: 0
                    ListItem(
                        modifier = Modifier.clickable { onOpen(m.npub) },
                        leadingContent = { Avatar(m.npub, memberLabel(m), presence = m.presence(now)) },
                        headlineContent = {
                            Text(
                                memberLabel(m),
                                fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        },
                        supportingContent = {
                            Text(
                                last?.let { (if (it.outgoing) "You: " else "") + it.text } ?: "No messages yet",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (unread > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                last?.let {
                                    Text(listTime(it.at), style = MaterialTheme.typography.labelSmall)
                                }
                                if (unread > 0) Badge { Text("$unread") }
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationTopBar(peer: String, onBack: () -> Unit) {
    val now = rememberNow()
    val member = ClientHolder.roster?.firstOrNull { it.npub == peer }
    val name = member?.let(::memberLabel) ?: ClientHolder.nameOf(peer)
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(peer, name, size = 36.dp, presence = member?.presence(now))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    member?.let {
                        Text(
                            it.presenceText(now),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/** Messages from one sender this close together form a group. */
private const val GROUP_WINDOW_MS = 5 * 60 * 1000L

/** The thread with one device. Tap a message that was not delivered to
 * send it again; long-press any message to copy or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationScreen(peer: String) {
    var draft by rememberSaveable(peer) { mutableStateOf("") }
    val msgs = ClientHolder.chatWith(peer)
    val name = ClientHolder.nameOf(peer)
    val pickFile = rememberFileSender()

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
    // "delivered" shows on the newest delivered message only.
    val lastDelivered = msgs.indexOfLast { it.outgoing && it.delivered }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.AutoMirrored.Outlined.Chat,
                    "Say hi to $name",
                    "Messages go straight to the device, encrypted.",
                )
            }
        } else {
            // reverseLayout pins the newest message at the bottom; items
            // are emitted newest first, each day's divider after (above)
            // its first message.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                reverseLayout = true,
            ) {
                for (k in msgs.indices.reversed()) {
                    val m = msgs[k]
                    val prev = msgs.getOrNull(k - 1)
                    val newDay = prev == null || localDate(prev.at) != localDate(m.at)
                    val grouped = !newDay && prev != null && prev.outgoing == m.outgoing &&
                        m.at - prev.at < GROUP_WINDOW_MS
                    item(key = "m-${m.at}-$k") {
                        Bubble(
                            m,
                            showDelivered = k == lastDelivered,
                            modifier = Modifier.padding(top = if (grouped) 2.dp else 10.dp),
                            onRetry = { ClientHolder.retryChat(peer, m) },
                            onDelete = { ClientHolder.deleteChat(peer, m) },
                        )
                    }
                    if (newDay) {
                        item(key = "d-${localDate(m.at)}-$k") { DayDivider(m.at) }
                    }
                }
            }
        }

        SendStatus(peer, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            IconButton(onClick = { pickFile(peer) }) {
                Icon(Icons.Outlined.AttachFile, contentDescription = "Send a file to $name")
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        ClientHolder.sendChat(peer, text)
                    }
                },
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: ClientHolder.ChatMsg,
    showDelivered: Boolean,
    modifier: Modifier,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val (bg, fg) = if (m.outgoing) {
        Palette.Blue to Palette.OnColor
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    }
    val time = clockTime(m.at)
    val meta = when {
        !m.outgoing -> time
        m.failed -> "$time · Not delivered, tap to try again"
        !m.delivered -> "$time · sending"
        showDelivered -> "$time · delivered"
        else -> time
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (m.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Box {
            Surface(
                color = bg,
                contentColor = fg,
                shape = RoundedCornerShape(18.dp),
                border = if (m.failed) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(
                        onClick = { if (m.failed) onRetry() },
                        onLongClick = { menu = true },
                    ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge, color = fg)
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.failed) Color(0xFFFCA5A5) else fg.copy(alpha = 0.7f),
                    )
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (m.failed) {
                    DropdownMenuItem(
                        text = { Text("Try again") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                        onClick = { menu = false; onRetry() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Copy") },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                    onClick = { menu = false; copy(context, m.text, "Copied") },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun DayDivider(at: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            dayLabel(at),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ------------------------------------------------------------- helpers

private fun localDate(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Yesterday", then "Mon, Nov 23" (with the year once it is
 * not this year's). */
private fun dayLabel(at: Long): String {
    val day = localDate(at)
    val today = LocalDate.now()
    return when {
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        else -> day.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
    }
}

private fun clockTime(at: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

/** The chat list's time: the clock today, the weekday this week, else
 * the date. */
private fun listTime(at: Long): String {
    val day = localDate(at)
    val today = LocalDate.now()
    return when {
        day == today -> clockTime(at)
        day.isAfter(today.minusDays(7)) -> day.format(DateTimeFormatter.ofPattern("EEE"))
        else -> day.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** The best label for a device: its friendly name, else a generic one. */
private fun memberLabel(member: MeshMember): String =
    member.name?.takeIf { it.isNotBlank() } ?: "Device"

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

/** The icon for a file, by extension (case-insensitive): the shape alone
 * should say what kind of thing it is. */
private fun fileIcon(name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "svg" -> Icons.Outlined.Image
        "mp4", "mov", "mkv", "webm", "avi" -> Icons.Outlined.Movie
        "mp3", "wav", "flac", "ogg", "m4a", "aac" -> Icons.Outlined.MusicNote
        "pdf" -> Icons.Outlined.PictureAsPdf
        "doc", "docx", "odt", "pages" -> Icons.Outlined.Description
        "xls", "xlsx", "csv", "ods", "numbers" -> Icons.Outlined.TableChart
        "ppt", "pptx", "odp", "key" -> Icons.Outlined.Slideshow
        "zip", "tar", "gz", "7z", "rar", "bz2", "xz" -> Icons.Outlined.FolderZip
        "kt", "rs", "py", "js", "ts", "c", "cpp", "java", "sh", "toml", "json" -> Icons.Outlined.Code
        "txt", "md", "log" -> Icons.AutoMirrored.Outlined.TextSnippet
        "apk" -> Icons.Outlined.Android
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
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
            toast(context, "No app on this phone opens this file type")
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

private fun copy(context: Context, text: String, confirmation: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("noscat", text))
    toast(context, confirmation)
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
