package ai.mitovoid.saylo

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Saylo in any app: shows the bubble while a text box is focused (above the keyboard), records when it's
 *  used, and types the cleaned text into that box. It reads only which app is in front, the address bar of a
 *  few browsers (for the writing style) and the focused text box; never password fields. */
class SayloAccessibility : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private lateinit var bubble: Bubble
    private var mode = "default"  // the dictation's style, from the app it's for
    private var target: Dictation.Target? = null  // its text box
    private val refresh = Runnable { refresh() }

    override fun onServiceConnected() {
        instance = this
        Dictation.init(this)
        bubble = Bubble(this)
        Dictation.addListener(bubble::onStatus)
    }

    override fun onDestroy() {
        if (::bubble.isInitialized) {
            Dictation.removeListener(bubble::onStatus)
            bubble.hide()
        }
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = refreshSoon()

    override fun onInterrupt() = Unit

    /** Content changes come in bursts (typing, scrolling), so the check runs once things settle. */
    fun refreshSoon() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 120)
    }

    private fun refresh() {
        if (!::bubble.isInitialized) return
        val keyboard = try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (e: Exception) { null }
        val field = focusedField()
        val sayloKeyboard = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true  // the Saylo keyboard has its own mic
        if (field != null && !sayloKeyboard) {
            // Above the on-screen keyboard; with a hardware keyboard there is none, so near the bottom edge.
            val bounds = Rect().also { keyboard?.getBoundsInScreen(it) }
            bubble.show(if (keyboard != null && bounds.height() > 0) resources.displayMetrics.heightPixels - bounds.top else 0)
        } else {
            bubble.hide()
        }
    }

    /** The focused text box, never a password field. In Jetpack Compose apps the input focus is the whole
     *  screen's view, so the focused editable node is looked for inside it. */
    private fun focusedField(): AccessibilityNodeInfo? {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val field = if (focus.isEditable) focus else focusedEditable(focus, 0)
        return field?.takeIf { !it.isPassword }
    }

    private fun focusedEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isFocused && child.isEditable) return child
            if (child.isVisibleToUser) focusedEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    fun level() = recorder.level

    /** Starts listening for the focused text box; returns its mode, or null when Saylo isn't set up yet. */
    fun startRecording(): String? {
        if (Store.key.isEmpty() || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return null
        }
        aim().let { (m, t) -> mode = m; target = t }
        return try {
            recorder.start()
            mode
        } catch (e: Exception) {
            Log.w("Saylo", "mic start failed", e)
            Dictation.toast("Microphone not available")
            null
        }
    }

    /** The style and the text box for a dictation into whatever is focused now. */
    internal fun aim(): Pair<String, Dictation.Target> {
        val into = focusedField()?.packageName?.toString() ?: rootInActiveWindow?.packageName?.toString().orEmpty()
        return Commands.modeFor(into, if (into in BROWSERS) url() else "") to Dictation.Target { text -> insert(text, into) }
    }

    /** Stops and sends; false when there was nothing worth sending. */
    fun finishRecording(): Boolean {
        if (!recorder.isRecording) return false
        val (pcm, seconds) = recorder.stop()
        return Dictation.submit(pcm, seconds, mode, target)
    }

    fun cancelRecording() {
        if (recorder.isRecording) recorder.stop()
    }

    /** Types text into the focused text box if it still belongs to the same app. Pasting keeps the app's own
     *  handling (WhatsApp, Gmail); setting the text at the cursor is the fallback. */
    private fun insert(text: String, into: String): Boolean {
        val field = focusedField() ?: return false
        if (field.packageName?.toString() != into) return false
        val current = if (field.isShowingHintText) "" else field.text?.toString().orEmpty()
        val start = field.textSelectionStart.takeIf { it in 0..current.length } ?: current.length
        val end = field.textSelectionEnd.takeIf { it in start..current.length } ?: start
        val spaced = if (start > 0 && !current[start - 1].isWhitespace()) " $text" else text
        Dictation.copy(spaced)
        if (field.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        val updated = current.substring(0, start) + spaced + current.substring(end)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        val cursor = start + spaced.length
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
        return true
    }

    /** The address in a browser's address bar, for the site's writing style. */
    private fun url(): String {
        val root = rootInActiveWindow ?: return ""
        for (id in URL_BARS) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.let { return it.toString() }
        }
        return ""
    }

    companion object {
        @Volatile var instance: SayloAccessibility? = null
            private set

        private val URL_BARS = listOf(
            "com.android.chrome:id/url_bar", "com.brave.browser:id/url_bar", "com.microsoft.emmx:id/url_bar",
            "com.chrome.beta:id/url_bar", "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text", "com.opera.browser:id/url_field",
        )
        private val BROWSERS = URL_BARS.map { it.substringBefore(":") }.toSet()

        /** Whether the user turned on Saylo in Accessibility settings. */
        fun isOn(c: Context): Boolean {
            val me = ComponentName(c, SayloAccessibility::class.java).flattenToString()
            return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
        }
    }
}
