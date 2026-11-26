package io.github.letdown2491.noscat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import java.util.concurrent.Executors
import uniffi.noscat.Terminal

/**
 * A terminal this phone has open on a member (one at a time). State is
 * Compose snapshot state; output and the emulator live on the main thread.
 * The member runs the shell; this side only draws and types.
 */
class RemoteTerminal(val peer: String, val name: String) {
    enum class State { Opening, Waiting, Open, Ended }

    var state by mutableStateOf(State.Opening)
    /** Why it ended, in the user's words; null while it runs. */
    var endText: String? by mutableStateOf(null)
    var title: String? by mutableStateOf(null)
    /** Sticky modifiers from the extra keys row. */
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    /** The session id its events carry; null until the first one. */
    var session: ULong? = null
    private var handle: Terminal? = null
    var emulator: TerminalEmulator? = null
        private set
    var view: RemoteTerminalView? = null
    private val pending = ArrayDeque<ByteArray>()
    /** FFI calls in order, never on the main thread (rule 6). */
    private val io = Executors.newSingleThreadExecutor()

    /** What the emulator sends back (key codes, pastes, terminal replies). */
    val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) =
            send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) { title = newTitle }
        override fun onCopyTextToClipboard(text: String) {
            view?.context?.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("terminal", text))
        }
        override fun onPasteTextFromClipboard() { view?.paste() }
        override fun onBell() {}
        override fun onColorsChanged() { view?.invalidate() }
    }

    fun send(bytes: ByteArray) {
        val h = handle ?: return
        io.execute { runCatching { h.write(bytes) } }
    }

    /** Main thread: the view knows its size. The first call starts the
     * session; later ones resize it. */
    fun onSize(cols: Int, rows: Int) {
        val e = emulator
        if (e == null) {
            emulator = TerminalEmulator(output, cols, rows, 2000, NoClient)
            while (pending.isNotEmpty()) pending.removeFirst().let { emulator!!.append(it, it.size) }
            ClientHolder.startTerminal(this, cols, rows)
        } else if (e.mColumns != cols || e.mRows != rows) {
            e.resize(cols, rows)
            val h = handle ?: return
            io.execute { runCatching { h.resize(cols.toUShort(), rows.toUShort()) } }
        }
        view?.invalidate()
    }

    /** Main thread. */
    fun started(h: Terminal) {
        if (state == State.Ended) {
            io.execute { runCatching { h.end(); h.close() } }
            return
        }
        handle = h
        session = h.id()
        state = State.Open
        view?.let { v -> emulator?.let { e -> onSize(e.mColumns, e.mRows) }; v.invalidate() }
    }

    /** Main thread. */
    fun onOutput(data: ByteArray) {
        val e = emulator
        if (e == null) pending.addLast(data) else e.append(data, data.size)
        view?.invalidate()
    }

    /** Main thread: ended by the member, the network or this side. */
    fun ended(text: String) {
        if (state == State.Ended) return
        state = State.Ended
        endText = text
        val h = handle
        handle = null
        io.execute { runCatching { h?.end(); h?.close() } }
        io.shutdown()
    }

    /** This side closes it. */
    fun close() = ended("Closed")
}

/** The emulator wants a client for logging and cursor state; nothing here. */
private object NoClient : TerminalSessionClient {
    override fun onTextChanged(s: TerminalSession) {}
    override fun onTitleChanged(s: TerminalSession) {}
    override fun onSessionFinished(s: TerminalSession) {}
    override fun onCopyTextToClipboard(s: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(s: TerminalSession?) {}
    override fun onBell(s: TerminalSession) {}
    override fun onColorsChanged(s: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** Draws [RemoteTerminal]'s emulator with Termux's renderer and turns the
 * keyboard into terminal bytes. Drag scrolls back; tap opens the keyboard. */
@SuppressLint("ViewConstructor")
class RemoteTerminalView(context: Context, private val t: RemoteTerminal) : View(context) {
    private val renderer = TerminalRenderer(
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics).toInt(),
        Typeface.MONOSPACE,
    )
    /** 0 = the live screen; negative = rows scrolled back. */
    private var topRow = 0
    private var scrollRemainder = 0f

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val emu = t.emulator ?: return true
            scrollRemainder += dy
            val rows = (scrollRemainder / renderer.fontLineSpacing).toInt()
            if (rows != 0) {
                scrollRemainder -= rows * renderer.fontLineSpacing
                val back = emu.screen.activeTranscriptRows
                topRow = (topRow + rows).coerceIn(-back, 0)
                invalidate()
            }
            return true
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(android.graphics.Color.BLACK)
        t.view = this
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cols = (w / renderer.fontWidth).toInt().coerceAtLeast(4)
        val rows = (h / renderer.fontLineSpacing).coerceAtLeast(2)
        t.onSize(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        val emu = t.emulator ?: return
        if (topRow < -emu.screen.activeTranscriptRows) topRow = -emu.screen.activeTranscriptRows
        renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        t.emulator?.paste(text)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect: every key goes to the shell as typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                typeText(text.toString())
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(1)) { t.send(byteArrayOf(0x7f)) }
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    /** Text from the keyboard, with the extra keys' Ctrl and Alt applied. */
    fun typeText(text: String) {
        for (ch in text) {
            var c = if (ch == '\n') '\r' else ch
            var bytes = c.toString().toByteArray()
            if (t.ctrl) {
                val lower = c.lowercaseChar()
                bytes = when {
                    lower in 'a'..'z' -> byteArrayOf((lower.code - 'a'.code + 1).toByte())
                    c == ' ' || c == '@' -> byteArrayOf(0)
                    c in "[\\]^_" -> byteArrayOf((c.code and 0x1f).toByte())
                    else -> bytes
                }
                t.ctrl = false
            }
            if (t.alt) {
                bytes = byteArrayOf(0x1b) + bytes
                t.alt = false
            }
            t.send(bytes)
        }
        scrollToBottom()
    }

    /** A special key (arrows, Esc, Tab...) as the terminal expects it. */
    fun sendKey(keyCode: Int) {
        val emu = t.emulator ?: return
        var mods = 0
        if (t.ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (t.alt) mods = mods or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(keyCode, mods, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
        t.ctrl = false
        t.alt = false
        if (code != null) t.send(code.toByteArray())
        scrollToBottom()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_ENTER -> { t.send(byteArrayOf('\r'.code.toByte())); return true }
            KeyEvent.KEYCODE_DEL -> { t.send(byteArrayOf(0x7f)); return true }
        }
        val emu = t.emulator ?: return true
        var mods = 0
        if (event.isCtrlPressed || t.ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || t.alt) mods = mods or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mods = mods or KeyHandler.KEYMOD_SHIFT
        KeyHandler.getCode(keyCode, mods, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)?.let {
            t.ctrl = false
            t.alt = false
            t.send(it.toByteArray())
            return true
        }
        val ch = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (ch != 0) {
            if (event.isCtrlPressed) t.ctrl = true
            if (event.isAltPressed) t.alt = true
            typeText(String(Character.toChars(ch)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }
}

/** The full-screen terminal: the member's name and state on top, the
 * screen, and a row of keys a phone keyboard lacks. Back closes it, which
 * ends the session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(t: RemoteTerminal, onClose: () -> Unit) {
    BackHandler { onClose() }
    var view by remember { mutableStateOf<RemoteTerminalView?>(null) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().imePadding()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close the terminal")
                }
            },
            title = {
                Column {
                    Text(t.name, maxLines = 1)
                    val sub = when (t.state) {
                        RemoteTerminal.State.Opening -> "Connecting..."
                        RemoteTerminal.State.Waiting -> "Waiting for approval"
                        RemoteTerminal.State.Open -> t.title ?: "Terminal"
                        RemoteTerminal.State.Ended -> t.endText ?: "Ended"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> RemoteTerminalView(ctx, t).also { view = it } },
                modifier = Modifier.fillMaxSize(),
            )
            when (t.state) {
                RemoteTerminal.State.Opening, RemoteTerminal.State.Waiting -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                    Text(
                        if (t.state == RemoteTerminal.State.Waiting) "Waiting for ${t.name} to approve"
                        else "Opening a terminal on ${t.name}",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
                RemoteTerminal.State.Ended -> Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(t.endText ?: "The session ended")
                    Button(onClick = onClose) { Text("Close") }
                }
                RemoteTerminal.State.Open -> {}
            }
        }
        if (t.state == RemoteTerminal.State.Open) ExtraKeys(t, view)
    }
}

@Composable
private fun ExtraKeys(t: RemoteTerminal, view: RemoteTerminalView?) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val key: @Composable (String, String, Int) -> Unit = { label, description, code ->
            TextButton(onClick = { view?.sendKey(code) }) {
                Text(label, modifier = Modifier.padding(horizontal = 2.dp))
            }
        }
        key("Esc", "Escape", KeyEvent.KEYCODE_ESCAPE)
        FilterChip(selected = t.ctrl, onClick = { t.ctrl = !t.ctrl }, label = { Text("Ctrl") })
        FilterChip(selected = t.alt, onClick = { t.alt = !t.alt }, label = { Text("Alt") })
        key("Tab", "Tab", KeyEvent.KEYCODE_TAB)
        key("←", "Left", KeyEvent.KEYCODE_DPAD_LEFT)
        key("↑", "Up", KeyEvent.KEYCODE_DPAD_UP)
        key("↓", "Down", KeyEvent.KEYCODE_DPAD_DOWN)
        key("→", "Right", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("Home", "Home", KeyEvent.KEYCODE_MOVE_HOME)
        key("End", "End", KeyEvent.KEYCODE_MOVE_END)
        TextButton(onClick = { view?.paste() }) { Text("Paste") }
    }
}
