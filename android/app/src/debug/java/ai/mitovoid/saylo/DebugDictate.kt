package ai.mitovoid.saylo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** Debug builds only: feeds a 16 kHz mono WAV from the app's files through a dictation, as if it was spoken into
 *  the focused text box with the bubble. For testing on emulators without a microphone:
 *  adb exec-in run-as ai.mitovoid.saylo sh -c 'cat > files/t.wav' < t.wav
 *  adb shell am broadcast -n ai.mitovoid.saylo/.DebugDictate --es file t.wav */
class DebugDictate : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val service = SayloAccessibility.instance ?: return
        val wav = File(c.filesDir, intent.getStringExtra("file") ?: "t.wav").readBytes()
        val pcm = wav.copyOfRange(String(wav, Charsets.ISO_8859_1).indexOf("data") + 8, wav.size)
        val (mode, target) = service.aim()
        Dictation.submit(pcm, pcm.size / (Recorder.RATE * 2.0), mode, target)
    }
}
