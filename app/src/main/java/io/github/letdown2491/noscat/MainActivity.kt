package io.github.letdown2491.noscat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import uniffi.noscat.generateIdentity

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // MVP: keep the client running while the process lives. A foreground
        // service (phase 1 hardening) will own the lifecycle later.
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var started by remember { mutableStateOf(ClientHolder.client != null) }
    var peer by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8080") }
    var pickedFile by remember { mutableStateOf<File?>(null) }

    var showQr by remember { mutableStateOf(false) }
    val npub = ClientHolder.client?.npub()

    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { peer = it.trim() }
    }

    // One-time startup: restore or create the identity, then start the client.
    LaunchedEffect(Unit) {
        if (started) return@LaunchedEffect
        runCatching {
            val secret = withContext(Dispatchers.IO) {
                IdentityStore.load(context) ?: run {
                    val fresh = generateIdentity()
                    IdentityStore.save(context, fresh.secretHex)
                    fresh.secretHex
                }
            }
            withContext(Dispatchers.IO) { ClientHolder.getOrStart(secret) }
        }.onFailure {
            ClientHolder.logLine("startup failed: $it")
        }
        started = true
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

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("noscat", style = MaterialTheme.typography.headlineMedium)

        if (npub != null) {
            OutlinedButton(
                onClick = { copy(context, npub) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(npub, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showQr = true }) { Text("Show my QR") }
                Text("tap the npub to copy it", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Text("starting client...", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Peer npub (dial target)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = peer,
            onValueChange = { peer = it.trim() },
            label = { Text("npub1...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedButton(
            onClick = {
                qrScanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("scan a peer's noscat QR")
                        .setBeepEnabled(false)
                        .setOrientationLocked(true),
                )
            },
            enabled = started,
        ) { Text("Scan peer QR") }

        Text("Forward a local port to the peer's exposed service", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("local port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    val c = ClientHolder.client
                    val p = port.toUShortOrNull()
                    when {
                        c == null -> toast(context, "client is not running")
                        !peer.startsWith("npub1") -> toast(context, "enter a peer npub")
                        p == null -> toast(context, "enter a port")
                        else -> scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { c.forward(peer, p) }
                            }
                            ok.fold(
                                { ClientHolder.logLine("forward dialled: localhost:$p -> ${peer.take(16)}...") },
                                { toast(context, "forward failed: ${it.message}") },
                            )
                        }
                    }
                },
                enabled = started,
            ) { Text("Forward") }
        }

        Text("Send a file to the peer", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = started,
            ) { Text("Pick file") }
            Button(
                onClick = {
                    val c = ClientHolder.client
                    val f = pickedFile
                    when {
                        c == null -> toast(context, "client is not running")
                        !peer.startsWith("npub1") -> toast(context, "enter a peer npub")
                        f == null -> toast(context, "pick a file first")
                        else -> scope.launch {
                            withContext(Dispatchers.IO) { c.sendFile(f.absolutePath, peer) }
                            ClientHolder.logLine("sending ${f.name} to ${peer.take(16)}...")
                        }
                    }
                },
                enabled = started && pickedFile != null,
            ) { Text(pickedFile?.name?.let { "Send $it" } ?: "Send") }
        }

        Text("Receive a file from any peer", style = MaterialTheme.typography.titleSmall)
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
                        ClientHolder.logLine("waiting for an incoming file into $dir")
                    }
                }
            },
            enabled = started,
        ) { Text("Receive next file") }

        HorizontalDivider()
        Text("Events", style = MaterialTheme.typography.titleSmall)
        ClientHolder.log.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showQr && npub != null) {
        AlertDialog(
            onDismissRequest = { showQr = false },
            confirmButton = {
                Button(onClick = { showQr = false }) { Text("Close") }
            },
            title = { Text("Your npub") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(
                        bitmap = remember(npub) { qrBitmap(npub) }.asImageBitmap(),
                        contentDescription = "QR code of your npub",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "have the other device scan this",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )
    }
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
    toast(context, "npub copied")
}

private fun toast(context: Context, msg: String) {
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
