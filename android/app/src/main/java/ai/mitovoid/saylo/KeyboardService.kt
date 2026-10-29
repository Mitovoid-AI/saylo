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

/** The Saylo keyboard: hold the mic, speak, let go, and the cleaned text is typed into the field.
 *  Space, backspace, enter and a key to switch back to your usual keyboard sit underneath. */
class KeyboardService : InputMethodService() {
    private val main = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private lateinit var status: TextView
    private lateinit var mic: View
    private var busy = false

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard, null)
        status = view.findViewById(R.id.status)
        mic = view.findViewById(R.id.mic)
        mic.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); startRecording() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopRecording()
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
        mic.isActivated = false
        super.onFinishInputView(finishingInput)
    }

    private fun showReady() {
        status.text = when {
            Prefs.key(this).isEmpty() -> getString(R.string.kb_need_key)
            !hasMic() -> getString(R.string.kb_need_mic)
            else -> getString(R.string.kb_ready)
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun openSetup() =
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun startRecording() {
        if (busy) return
        if (Prefs.key(this).isEmpty() || !hasMic()) return openSetup()
        try {
            recorder.start()
        } catch (e: Exception) {
            status.text = getString(R.string.kb_mic_error)
            return
        }
        mic.isActivated = true
        status.text = getString(R.string.kb_listening)
    }

    private fun stopRecording() {
        if (!recorder.isRecording) return
        mic.isActivated = false
        val (wav, seconds) = recorder.stop()
        if (seconds < MIN_CLIP_SECONDS) {
            status.text = getString(R.string.kb_too_short)
            return
        }
        busy = true
        status.text = getString(R.string.kb_writing)
        val key = Prefs.key(this)
        Thread {
            val result = try {
                val raw = Groq.transcribe(wav, key)
                if (raw.isEmpty()) Result.failure(Exception(getString(R.string.kb_not_heard)))
                else Result.success(Groq.cleanWithFallback(raw, key))
            } catch (e: Exception) {
                Result.failure(Exception(Groq.friendly(e)))
            }
            main.post {
                busy = false
                result.onSuccess { currentInputConnection?.commitText(it, 1); showReady() }
                    .onFailure { status.text = it.message }
            }
        }.start()
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
        const val MIN_CLIP_SECONDS = 0.3
    }
}
