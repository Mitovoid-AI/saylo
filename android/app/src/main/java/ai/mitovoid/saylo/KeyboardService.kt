package ai.mitovoid.saylo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView

/** The Sayvra keyboard, the backup to the bubble: hold the mic, speak, let go, and the cleaned text is typed
 *  into the field; or tap the mic for hands-free and tap again to send. It writes in the app's style (Commands)
 *  and keeps clips safe while offline (Dictation). Space, backspace, enter and a key to switch back to your
 *  usual keyboard sit underneath. */
class KeyboardService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private lateinit var status: TextView
    private lateinit var mic: View
    private var busy = false
    private var locked = false  // hands-free: listening until the next tap
    private var downAt = 0L
    private var mode = "default"
    private val onStatus: (Dictation.Status) -> Unit = { s ->
        when (s) {
            is Dictation.Working -> Unit
            is Dictation.Retrying -> status.text = s.text
            is Dictation.Message -> { busy = false; status.text = s.text }
            is Dictation.Done -> { busy = false; if (s.pasted) showReady() else status.text = getString(R.string.kb_copied) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Dictation.init(this)
        Dictation.addListener(onStatus)
    }

    override fun onDestroy() {
        Dictation.removeListener(onStatus)
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard, null)
        status = view.findViewById(R.id.status)
        mic = view.findViewById(R.id.mic)
        mic.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (locked) stopRecording() else startRecording()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> when {
                    !recorder.isRecording || locked -> Unit
                    System.currentTimeMillis() - downAt < HOLD_MS -> {  // a tap: hands-free until the next tap
                        locked = true
                        status.text = listening(getString(R.string.kb_hands_free))
                    }
                    else -> stopRecording()
                }
            }
            true
        }
        view.findViewById<View>(R.id.space).setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        view.findViewById<View>(R.id.enter).setOnClickListener { enter() }
        view.findViewById<View>(R.id.switchKeyboard).setOnClickListener { switchKeyboard() }
        val backspace = view.findViewById<View>(R.id.backspace)
        backspace.setOnClickListener { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
        backspace.setOnLongClickListener { currentInputConnection?.deleteSurroundingText(100, 0); true }
        return view
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!busy) showReady()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (recorder.isRecording) recorder.stop()
        locked = false
        mic.isActivated = false
        super.onFinishInputView(finishingInput)
    }

    private fun showReady() {
        status.text = when {
            Store.key.isEmpty() -> getString(R.string.kb_need_key)
            !hasMic() -> getString(R.string.kb_need_mic)
            else -> getString(R.string.kb_ready)
        }
    }

    private fun listening(word: String) = Commands.LABELS[mode].orEmpty().let { if (it.isEmpty()) word else "$word · $it" }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openSetup() =
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun startRecording() {
        if (busy) return
        if (Store.key.isEmpty() || !hasMic()) return openSetup()
        try {
            recorder.start()
        } catch (e: Exception) {
            status.text = getString(R.string.kb_mic_error)
            return
        }
        downAt = System.currentTimeMillis()
        mode = Commands.modeFor(currentInputEditorInfo?.packageName)
        mic.isActivated = true
        status.text = listening(getString(R.string.kb_listening))
    }

    private fun stopRecording() {
        locked = false
        if (!recorder.isRecording) return
        mic.isActivated = false
        val (pcm, seconds) = recorder.stop()
        val field = currentInputEditorInfo?.let { it.packageName to it.fieldId }
        // Typed only if the same text box is still open when the text is back; else it goes on the clipboard.
        val sent = Dictation.submit(pcm, seconds, mode) { text ->
            val now = currentInputEditorInfo?.let { it.packageName to it.fieldId }
            val ic = currentInputConnection
            if (now == field && ic != null && isInputViewShown) ic.commitText(text, 1) else false
        }
        if (sent) {
            busy = true
            status.text = listening(getString(R.string.kb_writing))
        }
    }

    private fun enter() {
        val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val multiLine = ((currentInputEditorInfo?.inputType ?: 0) and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!multiLine && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
    }

    private fun switchKeyboard() {
        if (!switchToPreviousInputMethod()) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    companion object {
        private const val HOLD_MS = 350  // shorter is a tap (hands-free); longer is hold-to-talk
    }
}
