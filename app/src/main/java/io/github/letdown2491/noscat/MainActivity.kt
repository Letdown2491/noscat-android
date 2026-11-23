package io.github.letdown2491.noscat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import kotlinx.coroutines.Dispatchers
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

    override fun onDestroy() {
        super.onDestroy()
        // MVP: keep the client running while the process lives. A foreground
        // service (phase 1 hardening) will own the lifecycle later.
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
    var started by remember { mutableStateOf(false) }

    // One-time startup: the foreground service owns the client's lifetime,
    // but start it here too so the UI is usable immediately (idempotent).
    LaunchedEffect(Unit) {
        if (screenReady) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) { ClientHolder.startFromPrefs(context) }
            tailnetDir = AppPrefs.tailnetDir(context)
        }.onFailure { ClientHolder.logLine("startup failed: $it") }
        started = true
        screenReady = true
    }

    if (!screenReady) return

    val scope2 = scope
    if (tailnetDir != null) {
        DevicesScreen(
            tailnetDir = tailnetDir!!,
            started = started,
            onForget = {
                AppPrefs.setTailnetDir(context, null)
                scope2.launch {
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
    } else {
        JoinScreen(
            started = started,
            onJoined = { dir -> tailnetDir = dir },
        )
    }
}

// ---------------------------------------------------------------- join

@Composable
private fun JoinScreen(started: Boolean, onJoined: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { File(context.filesDir, "tailnet") }

    var deviceNpub by remember { mutableStateOf<String?>(null) }
    var prepareError by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    // Step 1: publish the key package so the owner can invite this phone.
    LaunchedEffect(Unit) {
        if (deviceNpub != null || prepareError != null) return@LaunchedEffect
        val res = withContext(Dispatchers.IO) {
            runCatching { tailnetPrepare(dir.absolutePath) }
        }
        res.fold(
            { deviceNpub = it },
            {
                ClientHolder.logLine("key package publish failed: $it")
                prepareError = "${it.message}"
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("noscat", style = MaterialTheme.typography.headlineMedium)
        Text("Join a tailnet", style = MaterialTheme.typography.titleLarge)

        when {
            prepareError != null -> {
                Text("could not reach the relays:", style = MaterialTheme.typography.bodySmall)
                Text(prepareError!!, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    prepareError = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { tailnetPrepare(dir.absolutePath) }
                        }
                        r.fold({ deviceNpub = it }, { prepareError = "${it.message}" })
                    }
                }) { Text("Retry") }
            }

            deviceNpub == null -> Text("publishing this phone's key package...", style = MaterialTheme.typography.bodySmall)

            else -> {
                Text(
                    "This phone's device address. Invite it from the device that owns your tailnet:",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { copy(context, deviceNpub!!) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(deviceNpub!!, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showQr = true }) { Text("Show QR") }
                }
                Text(
                    "on the owner device, run:",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    "noscat tailnet invite ${deviceNpub!!.take(20)}... --dir ./device-a",
                    style = MaterialTheme.typography.bodySmall,
                )

                if (joining) {
                    Text("waiting for the invitation (up to 3 min)...", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(
                        onClick = {
                            joining = true
                            joinError = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    runCatching { tailnetJoin(dir.absolutePath, 180uL) }
                                }
                                joining = false
                                res.fold(
                                    { m ->
                                        AppPrefs.setTailnetDir(context, dir.absolutePath)
                                        ClientHolder.logLine("joined: ${m.size} device(s)")
                                        onJoined(dir.absolutePath)
                                    },
                                    { joinError = it.message },
                                )
                            }
                        },
                        enabled = started,
                    ) { Text("Join") }
                }
                joinError?.let {
                    Text("join failed: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = { showAdvanced = true }) { Text("Advanced") }
    }

    if (showQr && deviceNpub != null) {
        QrDialog(deviceNpub!!, onDismiss = { showQr = false })
    }
    if (showAdvanced) {
        AdvancedDialog(
            joined = false,
            onDismiss = { showAdvanced = false },
            onJoinedByImport = { dir -> onJoined(dir) },
            onForget = {},
        )
    }
}

// ------------------------------------------------------------- devices

@Composable
private fun DevicesScreen(tailnetDir: String, started: Boolean, onForget: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val members = remember { mutableStateListOf<TailnetMember>() }
    var selectedNpub by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var manualPeer by remember { mutableStateOf("") }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    var showForward by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

    fun refreshDevices() {
        scope.launch {
            syncing = true
            val res = withContext(Dispatchers.IO) {
                runCatching { tailnetSync(tailnetDir, 5uL) }
            }
            syncing = false
            res.fold(
                { list ->
                    members.clear()
                    members.addAll(list)
                    if (selectedNpub == null) {
                        selectedNpub = list.firstOrNull { it.isOwner }?.npub
                            ?: list.firstOrNull()?.npub
                    }
                },
                { toast(context, "sync failed: ${it.message}") },
            )
        }
    }

    LaunchedEffect(tailnetDir) {
        val res = withContext(Dispatchers.IO) {
            runCatching { tailnetMembers(tailnetDir) }
        }
        res.fold(
            { list ->
                members.clear()
                members.addAll(list)
                selectedNpub = list.firstOrNull { it.isOwner }?.npub
                    ?: list.firstOrNull()?.npub
            },
            { toast(context, "cannot read tailnet: ${it.message}") },
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            pickedFile = null
            scope.launch {
                val f = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                pickedFile = f
                if (f == null) toast(context, "could not read the picked file")
            }
        }
    }

    val npub = ClientHolder.client?.npub()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("noscat", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { showAdvanced = true }) { Text("Advanced") }
        }

        Text("Devices", style = MaterialTheme.typography.titleLarge)
        if (syncing) Text("syncing...", style = MaterialTheme.typography.labelSmall)
        members.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = selectedNpub == m.npub,
                    onClick = { selectedNpub = m.npub },
                )
                Text(
                    "${m.npub.take(20)}...${if (m.isOwner) "  (owner)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(onClick = { refreshDevices() }) { Text("Refresh") }

        HorizontalDivider()
        Text(
            "Talking to: ${selectedNpub?.take(20)?.plus("...") ?: "nothing selected"}",
            style = MaterialTheme.typography.titleSmall,
        )

        Text("Forward one of its services to this phone", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { showForward = true }, enabled = started) { Text("Forward a service") }

        Text("Send a file", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = started,
            ) { Text("Pick file") }
            Button(
                onClick = {
                    val c = ClientHolder.client
                    val f = pickedFile
                    val target = selectedNpub
                    when {
                        c == null -> toast(context, "client is not running")
                        target == null -> toast(context, "select a device first")
                        f == null -> toast(context, "pick a file first")
                        else -> scope.launch {
                            withContext(Dispatchers.IO) { c.sendFile(f.absolutePath, target) }
                            ClientHolder.logLine("sending ${f.name} to ${target.take(16)}...")
                        }
                    }
                },
                enabled = started && pickedFile != null,
            ) { Text(pickedFile?.name?.let { "Send $it" } ?: "Send") }
        }

        Text("Receive a file (lands in the inbox)", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                val c = ClientHolder.client
                if (c == null) {
                    toast(context, "client is not running")
                } else {
                    scope.launch {
                        val dir = withContext(Dispatchers.IO) {
                            (context.getExternalFilesDir(null) ?: context.filesDir).absolutePath
                        }
                        withContext(Dispatchers.IO) { c.receiveFile(dir) }
                        ClientHolder.logLine("waiting for an incoming file")
                    }
                }
            },
            enabled = started,
        ) { Text("Receive next file") }

        HorizontalDivider()
        Text("Inbox", style = MaterialTheme.typography.titleSmall)
        val inbox = remember {
            mutableStateListOf<File>().also { files ->
                files.addAll(inboxFiles(context))
            }
        }
        OutlinedButton(onClick = {
            inbox.clear()
            inbox.addAll(inboxFiles(context))
        }) { Text("Refresh inbox") }
        inbox.forEach { f ->
            Text(
                "${f.name}  (${f.length() / 1024} KB)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (inbox.isEmpty()) {
            Text("nothing received yet", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (showForward) {
        ForwardDialog(
            target = selectedNpub ?: "",
            onDismiss = { showForward = false },
        )
    }
    if (showQr && npub != null) {
        QrDialog(npub, onDismiss = { showQr = false })
    }
    if (showAdvanced) {
        AdvancedDialog(
            joined = true,
            onDismiss = { showAdvanced = false },
            onJoinedByImport = { /* already joined */ },
            onForget = onForget,
        )
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
                    "dial ${target.take(20)}... and expose its service at localhost:<port>",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("local port") },
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

@Composable
private fun QrDialog(npub: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        title = { Text("This device's address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = remember(npub) { qrBitmap(npub) }.asImageBitmap(),
                    contentDescription = "QR code of this device's npub",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { copy(context, npub) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Copy npub") }
            }
        },
    )
}

@Composable
private fun AdvancedDialog(
    joined: Boolean,
    onDismiss: () -> Unit,
    onJoinedByImport: (String) -> Unit,
    onForget: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manualPeer by remember { mutableStateOf("") }

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val np = result.contents?.let { extractNpub(it) }
        if (np != null) manualPeer = np
        else toast(context, "that QR does not contain a noscat npub")
    }
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dir = withContext(Dispatchers.IO) {
                val d = File(context.filesDir, "tailnet")
                d.deleteRecursively()
                copyTree(context, uri, d)
                d
            }
            if (!File(dir, "identity.nsec").exists()) {
                toast(context, "that folder has no identity.nsec; pick a tailnet device folder")
            } else {
                AppPrefs.setTailnetDir(context, dir.absolutePath)
                val secret = withContext(Dispatchers.IO) {
                    File(dir, "identity.nsec").readText().trim()
                }
                withContext(Dispatchers.IO) { ClientHolder.restart(secret, dir.absolutePath) }
                onDismiss()
                onJoinedByImport(dir.absolutePath)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } },
        title = { Text("Advanced") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("Manual dial (no tailnet)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = manualPeer,
                    onValueChange = { manualPeer = it.trim() },
                    label = { Text("peer npub1...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    qrScanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("scan a peer's noscat QR")
                            .setBeepEnabled(false)
                            .setOrientationLocked(true),
                    )
                }) { Text("Scan QR") }

                HorizontalDivider()
                Text("Tailnet device folder", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { treePicker.launch(null) }) {
                    Text("Import folder instead")
                }
                if (joined) {
                    OutlinedButton(onClick = {
                        onForget()
                        onDismiss()
                    }) { Text("Forget this tailnet") }
                }

                HorizontalDivider()
                Text("Events", style = MaterialTheme.typography.titleSmall)
                ClientHolder.log.take(30).forEach { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
    )
}

// ------------------------------------------------------------- helpers

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

/** Renders text (an npub) as a square black-and-white QR bitmap. */
private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        for (x in 0 until size) {
            pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
}

/**
 * Copies every file of a picked document tree into dest. Used to restore an
 * enrolled tailnet device folder onto the phone (Advanced path).
 */
private fun copyTree(context: Context, treeUri: Uri, dest: File): Int {
    dest.mkdirs()
    var count = 0
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    context.contentResolver.query(
        childrenUri,
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null, null, null,
    )?.use { c ->
        while (c.moveToNext()) {
            val docId = c.getString(0)
            val name = c.getString(1) ?: continue
            val mime = c.getString(2)
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            try {
                context.contentResolver.openInputStream(docUri)?.use { input ->
                    File(dest, name).outputStream().use(input::copyTo)
                } ?: continue
                count++
            } catch (_: Exception) {
                // Skip unreadable entries; the identity.nsec check catches
                // picking the wrong folder.
            }
        }
    }
    return count
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
