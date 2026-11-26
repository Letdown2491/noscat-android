package io.github.letdown2491.noscat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uniffi.noscat.MeshMember

// ------------------------------------------------------------- palette

/** The app's fixed colours, the same values as the desktop app's
 * palette: one blue accent, the status colours, and avatar hues. */
object Palette {
    val Blue = Color(0xFF2563EB)
    val BlueLight = Color(0xFF3B82F6)
    val Success = Color(0xFF22C55E)
    val Warning = Color(0xFFEAB308)
    val Amber = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
    val Offline = Color(0xFF94A3B8)
    val OnColor = Color.White

    /** Mid tones that carry white text in both themes; no reds or pinks,
     * which read as errors next to status dots. */
    private val HUES = longArrayOf(
        0xFF2563EB, 0xFF7C3AED, 0xFF0891B2, 0xFF059669,
        0xFFD97706, 0xFF4F46E5, 0xFF0D9488, 0xFF9333EA,
    )

    /** A stable colour per device, from its npub: FNV-1a over the bytes,
     * the same hash as the desktop app, so a device has one colour on
     * every screen of both apps. */
    fun avatar(npub: String): Color {
        var h = 0x811c9dc5.toInt()
        for (b in npub.toByteArray()) {
            h = h xor (b.toInt() and 0xFF)
            h *= 0x01000193
        }
        val index = (h.toLong() and 0xFFFFFFFFL) % HUES.size
        return Color(HUES[index.toInt()])
    }
}

/** Neutral surfaces (Material's defaults are tinted purple) with the
 * brand blue as primary, matching the desktop app. */
fun noscatColors(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Palette.BlueLight,
        onPrimary = Palette.OnColor,
        primaryContainer = Color(0xFF1E3A8A),
        onPrimaryContainer = Color(0xFFDBEAFE),
        secondary = Palette.BlueLight,
        onSecondary = Palette.OnColor,
        secondaryContainer = Color(0xFF1E3050),
        onSecondaryContainer = Color(0xFFDBEAFE),
        tertiary = Palette.BlueLight,
        background = Color(0xFF0B0B0C),
        onBackground = Color(0xFFF4F4F5),
        surface = Color(0xFF0B0B0C),
        onSurface = Color(0xFFF4F4F5),
        surfaceVariant = Color(0xFF27272A),
        onSurfaceVariant = Color(0xFFA1A1AA),
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color(0xFF080809),
        surfaceContainerLow = Color(0xFF141416),
        surfaceContainer = Color(0xFF18181B),
        surfaceContainerHigh = Color(0xFF222225),
        surfaceContainerHighest = Color(0xFF2C2C30),
        outline = Color(0xFF52525B),
        outlineVariant = Color(0xFF27272A),
        error = Color(0xFFF87171),
        onError = Palette.OnColor,
        errorContainer = Color(0xFF450A0A),
        onErrorContainer = Color(0xFFFECACA),
    )
} else {
    lightColorScheme(
        primary = Palette.Blue,
        onPrimary = Palette.OnColor,
        primaryContainer = Color(0xFFDBEAFE),
        onPrimaryContainer = Color(0xFF1E3A8A),
        secondary = Palette.Blue,
        onSecondary = Palette.OnColor,
        secondaryContainer = Color(0xFFDBEAFE),
        onSecondaryContainer = Color(0xFF1E3A8A),
        tertiary = Palette.Blue,
        background = Color.White,
        onBackground = Color(0xFF09090B),
        surface = Color.White,
        onSurface = Color(0xFF09090B),
        surfaceVariant = Color(0xFFF4F4F5),
        onSurfaceVariant = Color(0xFF52525B),
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7F8),
        surfaceContainer = Color(0xFFF1F1F3),
        surfaceContainerHigh = Color(0xFFEAEAED),
        surfaceContainerHighest = Color(0xFFE4E4E7),
        outline = Color(0xFFA1A1AA),
        outlineVariant = Color(0xFFE4E4E7),
        error = Color(0xFFDC2626),
        onError = Palette.OnColor,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
    )
}

// ------------------------------------------------------------ presence

/** What a device's dot says. */
enum class Presence { Connected, Connecting, Unreachable, Offline }

/** Matches noscat-core's presence window: members advertise every two
 * minutes, so an advert younger than ten means the device is up. */
private const val ADVERT_TTL_SECS = 600L

/** Seconds since the device's latest advert, aged forward from when the
 * roster was read, or null when never seen. */
fun MeshMember.ageSecs(nowMs: Long): Long? {
    val elapsed = (nowMs - ClientHolder.rosterAgeBase).coerceAtLeast(0) / 1000
    return lastSeenSecs?.let { it.toLong() + elapsed }
}

/** Green: data reaches it now. Amber: its advert arrives but nothing
 * connects (another network with no reachable member). Grey: offline.
 * Online but unconnected reads "connecting" for the first dial round. */
fun MeshMember.presence(nowMs: Long): Presence {
    val age = ageSecs(nowMs)
    return when {
        connected -> Presence.Connected
        age != null && age < ADVERT_TTL_SECS ->
            if (ClientHolder.upSince > 0 && nowMs - ClientHolder.upSince < ClientHolder.CONNECT_GRACE_MS) {
                Presence.Connecting
            } else {
                Presence.Unreachable
            }
        else -> Presence.Offline
    }
}

/** The line under a device's name. */
fun MeshMember.presenceText(nowMs: Long): String = when (presence(nowMs)) {
    Presence.Connected -> "connected"
    Presence.Connecting -> "connecting..."
    Presence.Unreachable -> "online, can't connect"
    Presence.Offline -> ageSecs(nowMs)?.let { "last seen ${agoText(it)}" } ?: "offline"
}

fun Presence.color(): Color = when (this) {
    Presence.Connected -> Palette.Success
    Presence.Unreachable -> Palette.Amber
    Presence.Connecting, Presence.Offline -> Palette.Offline
}

/** "5 min ago", "3 h ago", "2 days ago". */
fun agoText(secs: Long): String = when {
    secs < 90 -> "just now"
    secs < 3600 -> "${secs / 60} min ago"
    secs < 86_400 -> "${secs / 3600} h ago"
    else -> (secs / 86_400).let { "$it day${if (it == 1L) "" else "s"} ago" }
}

/** Current time, ticking every 30 s so presence ages while on screen. */
@Composable
fun rememberNow(): Long {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

// ----------------------------------------------------------- components

/** A device's avatar: its initial on its colour, with an optional
 * presence dot ringed in the background colour. */
@Composable
fun Avatar(npub: String, name: String, size: Dp = 40.dp, presence: Presence? = null) {
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .background(Palette.avatar(npub), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().firstOrNull()?.uppercase() ?: "?",
                color = Palette.OnColor,
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.42f).sp,
            )
        }
        if (presence != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.34f)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp)
                    .background(presence.color(), CircleShape)
                    .semantics {
                        contentDescription = when (presence) {
                            Presence.Connected -> "connected"
                            Presence.Connecting -> "connecting"
                            Presence.Unreachable -> "online, can't connect"
                            Presence.Offline -> "offline"
                        }
                    },
            )
        }
    }
}

/** A section's label: small, semibold, muted, upper case. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

/** Every list's empty state: icon, title, one line, and the action that
 * fills it. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/** The Noscat mark (the launcher icon's two layers, cropped as a
 * launcher crops them), in a circle. */
@Composable
fun Logo(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape),
    ) {
        listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach { res ->
            Image(
                painterResource(res),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
            )
        }
    }
}

/** The connection as this phone sees it. */
enum class MeshState { Connected, Connecting, Offline }

fun meshState(): MeshState = when {
    ClientHolder.client != null && ClientHolder.nodeUp -> MeshState.Connected
    ClientHolder.offline -> MeshState.Offline
    else -> MeshState.Connecting
}

fun MeshState.color(): Color = when (this) {
    MeshState.Connected -> Palette.Success
    MeshState.Connecting -> Palette.Amber
    MeshState.Offline -> Palette.Danger
}

/** "Connected 2 of 2", counting other devices only. */
fun meshStatusText(): String = when (meshState()) {
    MeshState.Connected -> {
        val others = ClientHolder.roster.orEmpty()
        if (others.isEmpty()) "Connected" else "Connected ${others.count { it.connected }} of ${others.size}"
    }
    MeshState.Connecting -> "Connecting..."
    MeshState.Offline -> "Disconnected"
}

/** The mark with a ring in the connection's colour: green connected,
 * amber connecting, red offline. */
@Composable
fun LogoHalo(size: Dp) {
    Box(
        Modifier
            .border(BorderStroke(2.dp, meshState().color()), CircleShape)
            .padding(3.dp),
    ) { Logo(size) }
}

/** The top bar's mesh button: the haloed mark, opening the mesh sheet.
 * The description says the state in words (colour alone does not reach
 * everyone). */
@Composable
fun MeshButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = "Mesh: ${meshStatusText()}" },
    ) { LogoHalo(26.dp) }
}

/** The mesh sheet: which mesh, its state, and the mesh-level actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshSheet(
    onDismiss: () -> Unit,
    onInvite: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                LogoHalo(40.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        ClientHolder.meshName ?: "Your mesh",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(meshState().color(), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            meshStatusText(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetItem(Icons.Outlined.PersonAdd, "Invite a device", enabled = ClientHolder.myNpub != null, onClick = onInvite)
            SheetItem(Icons.Outlined.Settings, "Settings", onClick = onSettings)
            if (ClientHolder.offline) {
                SheetItem(Icons.Outlined.PowerSettingsNew, "Reconnect", onClick = onReconnect)
            } else {
                SheetItem(Icons.AutoMirrored.Outlined.Logout, "Disconnect", onClick = onDisconnect)
            }
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp)
            .graphicsLayer(alpha = if (enabled) 1f else 0.4f),
    )
}

/** Invite a device: this phone's pairing code as a QR, with Copy and
 * Share. The new device scans or pastes it and asks to join; its request
 * then shows on Devices. */
@Composable
fun InviteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val npub = ClientHolder.myNpub ?: return onDismiss()
    // Read when the dialog opens, never cached: a mesh can turn private
    // while the app runs, and its code then carries the mesh's relays
    // (`?relay=`). pairingUri() blocks, so it runs off the main thread; the
    // QR waits for it rather than showing a code without the relays.
    val code by produceState<String?>(null, npub) {
        value = withContext(Dispatchers.IO) {
            runCatching { ClientHolder.client?.pairingUri() }.getOrNull() ?: Pairing.encode(npub)
        }
    }
    val qr = remember(code) { code?.let { Pairing.qrBitmap(it, 720).asImageBitmap() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite a device") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "On the new device, scan this code or paste it, then approve its request on Devices.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(color = Color.White, shape = RoundedCornerShape(12.dp)) {
                    Box(Modifier.padding(8.dp).size(220.dp), contentAlignment = Alignment.Center) {
                        if (qr != null) {
                            Image(qr, contentDescription = "This phone's pairing code", modifier = Modifier.fillMaxSize())
                        } else {
                            CircularProgressIndicator(color = Color.Black.copy(alpha = 0.5f))
                        }
                    }
                }
                val code = code ?: ""
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = code.isNotEmpty(), onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Noscat pairing code", code))
                        android.widget.Toast.makeText(context, "Pairing code copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                    OutlinedButton(enabled = code.isNotEmpty(), onClick = { sharePairingCode(context, code) }) {
                        Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun sharePairingCode(context: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, code)
    }
    context.startActivity(Intent.createChooser(send, "Share pairing code"))
}
