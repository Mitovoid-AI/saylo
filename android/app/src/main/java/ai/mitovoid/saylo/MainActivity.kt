package ai.mitovoid.saylo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Setup: Groq key (checked against Groq), microphone permission, turning on and picking the keyboard,
 *  and a box to try it. The keyboard opens this screen when something is missing. */
class MainActivity : Activity() {
    private lateinit var keyInput: EditText
    private lateinit var keyMsg: TextView
    private lateinit var saveKey: Button
    private lateinit var allowMic: Button
    private lateinit var micMsg: TextView
    private lateinit var enableKb: Button
    private lateinit var kbMsg: TextView
    private lateinit var pickKb: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        keyInput = findViewById(R.id.key)
        keyMsg = findViewById(R.id.keyMsg)
        saveKey = findViewById(R.id.saveKey)
        allowMic = findViewById(R.id.allowMic)
        micMsg = findViewById(R.id.micMsg)
        enableKb = findViewById(R.id.enableKb)
        kbMsg = findViewById(R.id.kbMsg)
        pickKb = findViewById(R.id.pickKb)

        saveKey.setOnClickListener { saveKey() }
        findViewById<View>(R.id.getKey).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        allowMic.setOnClickListener { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
        enableKb.setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        pickKb.setOnClickListener { imm().showInputMethodPicker() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied for good: only the app settings page can turn it back on.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

    private fun keyboardEnabled() = imm().enabledInputMethodList.any { it.packageName == packageName }

    private fun render() {
        if (Prefs.key(this).isNotEmpty()) show(keyMsg, getString(R.string.key_saved), ok = true)

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        allowMic.visibility = if (mic) View.GONE else View.VISIBLE
        micMsg.visibility = if (mic) View.VISIBLE else View.GONE
        if (mic) show(micMsg, getString(R.string.mic_allowed), ok = true)

        val kb = keyboardEnabled()
        enableKb.visibility = if (kb) View.GONE else View.VISIBLE
        kbMsg.visibility = if (kb) View.VISIBLE else View.GONE
        if (kb) show(kbMsg, getString(R.string.kb_enabled), ok = true)
        pickKb.isEnabled = kb
    }

    private fun show(view: TextView, text: String, ok: Boolean) {
        view.text = text
        view.setTextColor(getColor(if (ok) R.color.accent_ink else R.color.err))
    }

    private fun saveKey() {
        val key = keyInput.text.toString().trim()
        if (key.isEmpty()) return show(keyMsg, getString(R.string.key_empty), ok = false)
        saveKey.isEnabled = false
        keyMsg.text = getString(R.string.key_checking)
        keyMsg.setTextColor(getColor(R.color.muted))
        Thread {
            val error = Groq.checkKey(key)
            runOnUiThread {
                saveKey.isEnabled = true
                if (error != null) return@runOnUiThread show(keyMsg, error, ok = false)
                Prefs.setKey(this, key)
                keyInput.text.clear()
                render()
            }
        }.start()
    }

    companion object {
        private const val REQ_MIC = 1
    }
}
