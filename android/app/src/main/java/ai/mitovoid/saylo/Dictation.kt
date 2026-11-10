package ai.mitovoid.saylo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Every dictation, from the bubble or the keyboard: checks the clip, saves it, sends it to Groq and hands the
 *  text back to the text box it was spoken into (or the clipboard when that box is gone). While the network or
 *  Groq is down, clips stay saved and are retried, also after a restart. Like the worker in the Windows app.py. */
object Dictation {
    private const val TAG = "Sayvra"
    private val RETRY_WAITS = longArrayOf(2, 3, 5, 10, 20, 30, 60)  // seconds between tries while sending fails
    private const val RECONNECT_MS = 10_000L  // "Reconnecting…" this long, then "Saved…"
    const val MIN_CLIP_SECONDS = 0.3
    private const val MIN_SPEECH_MS = 120

    /** What the bubble or keyboard shows about a dictation. */
    sealed interface Status
    data object Working : Status
    data class Retrying(val text: String) : Status
    data class Message(val text: String) : Status
    data class Done(val text: String, val pasted: Boolean) : Status

    /** Where the text goes: deliver() types it if the same text box is still in front, else returns false. */
    fun interface Target {
        fun deliver(text: String): Boolean
    }

    class Outcome {
        var raw = ""
        var text = ""
        var skipped = ""
        var mode = "default"
        var translate = false
        var cleanedBy = ""
    }

    private class Clip(val pcm: ByteArray, val seconds: Double, val mode: String, val target: Target?)

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<Any>()  // a new Clip, or WAKE to try sending saved clips now
    private val WAKE = Any()
    private val targets = ConcurrentHashMap<String, Target>()  // saved clip -> where it was spoken (this run only)
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    @Synchronized
    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        Store.init(app)
        Prompts.init(app)
        Thread(::worker, "saylo-send").apply { isDaemon = true }.start()
        Thread { Store.pruneAudio() }.start()
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wake()  // back online: send what's saved
            })
        wake()  // clips left by an earlier run
    }

    fun addListener(l: (Status) -> Unit) = listeners.add(l)
    fun removeListener(l: (Status) -> Unit) = listeners.remove(l)
    private fun emit(s: Status) = main.post { listeners.forEach { it(s) } }

    /** Try sending saved clips now (a new key was saved, the network came back). */
    fun wake() {
        queue.offer(WAKE)
    }

    /** Called when the user finishes speaking. False (and says so) when the clip has no speech to send. */
    fun submit(pcm: ByteArray, seconds: Double, mode: String, target: Target?): Boolean {
        if (seconds < MIN_CLIP_SECONDS || Recorder.speechMs(pcm) < MIN_SPEECH_MS) {
            Log.i(TAG, "clip=%.2fs skipped: no speech".format(seconds))
            emit(Message("Didn’t catch that"))
            return false
        }
        queue.offer(Clip(pcm, seconds, mode, target))
        emit(Working)
        return true
    }

    private fun worker() {
        var wait: Long? = 0
        var fails = 0
        var failingSince = 0L
        var toldSaved = false
        while (true) {
            val item = wait?.let { queue.poll(it, TimeUnit.SECONDS) } ?: if (wait == null) queue.take() else null
            try {
                val new = item is Clip
                if (item is Clip) save(item)
                val error = sendPending() ?: run {
                    wait = null; fails = 0; failingSince = 0; toldSaved = false
                    null
                } ?: continue
                fails++
                if (failingSince == 0L) failingSince = System.currentTimeMillis()
                wait = RETRY_WAITS[minOf(fails, RETRY_WAITS.size) - 1]
                Log.w(TAG, "couldn't send; ${Store.pending().size} clip(s) saved, next try in ${wait}s: $error")
                when {
                    Store.key.isEmpty() -> emit(Message("Add your Groq key in Sayvra. Your dictation is saved."))
                    Groq.isAuth(error) -> emit(Message("Groq rejected your key. Saved; update it in Sayvra."))
                    System.currentTimeMillis() - failingSince < RECONNECT_MS ->
                        emit(Retrying(if (Groq.isRateLimit(error)) "Groq is busy, retrying…" else "Reconnecting…"))
                    new || !toldSaved -> {
                        emit(Message("Saved. Will send when online"))
                        toldSaved = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "dictation failed", e)
                emit(Message("Something went wrong"))
            }
        }
    }

    private fun save(c: Clip) {
        val m4a = Aac.encode(c.pcm, File(app.cacheDir, "clip.m4a"))
        val p = Store.addPending(m4a, c.seconds, c.mode)
        c.target?.let { targets[p.id] = it }
    }

    /** Sends saved clips oldest first. Returns the error that stopped it (the clip stays saved), or null. */
    private fun sendPending(): Exception? {
        val ready = mutableListOf<String>()  // texts whose text box is gone, for the clipboard
        for (p in Store.pending()) {
            val res = Outcome()
            try {
                if (Store.key.isEmpty()) throw Groq.GroqException(401, "no key")
                transcribe(p.audio.readBytes(), res, p.mode)
            } catch (e: Exception) {
                if (Groq.retryable(e)) return e
                Log.e(TAG, "couldn't transcribe ${p.id}", e)  // Groq refused this recording: keep it to retry by hand
                Store.finishPending(p, "", "", failed = true)
                emit(Message("Couldn’t transcribe a recording. It’s in History."))
                continue
            }
            val target = targets.remove(p.id)
            if (res.skipped.isNotEmpty()) {
                Store.dropPending(p)
                Log.i(TAG, "clip=${p.id} skipped=${res.skipped} raw=${res.raw}")
                emit(Message(if (res.skipped == "only a command") commandSaid(res, p.mode) else "Didn’t catch that"))
                continue
            }
            val pasted = target != null && onMain { target.deliver(res.text) }
            if (!pasted) {
                ready += res.text
                onMain { copy(ready.joinToString("\n\n")) }
            }
            Store.finishPending(p, res.text, res.raw, mode = res.mode)
            Log.i(TAG, "clip=${p.id} mode=${res.mode}${if (res.translate) "+english" else ""} by=${res.cleanedBy} pasted=$pasted")
            emit(Done(res.text, pasted))
        }
        return null
    }

    private fun commandSaid(res: Outcome, detected: String): String {
        val said = if (res.translate && res.mode == detected) "Translate to English"
        else "${Commands.LABELS[res.mode].orEmpty().ifEmpty { "Email" }} mode" + if (res.translate) " + English" else ""
        return "$said: say your text after it"
    }

    /** Fills res: the transcript, spoken commands, and the cleaned text, or res.skipped. Throws when the network
     *  or Groq fails. Like pipeline.transcribe in the Windows app. */
    fun transcribe(audio: ByteArray, res: Outcome, mode: String) {
        val key = Store.key
        val words = Store.dictionary
        val prompt = Groq.hint((listOf("Sayvra") + words).distinct())  // "Sayvra" helps Whisper hear the commands
        res.raw = Groq.transcribe(audio, key, prompt)
        if (Groq.isHallucination(res.raw, prompt)) {
            res.skipped = "no speech heard"
            return
        }
        val spoken = Commands.parse(res.raw)
        res.mode = spoken.mode ?: mode
        res.translate = spoken.translate
        if (spoken.text.isEmpty()) {
            res.skipped = "only a command"
            return
        }
        val (text, by) = Groq.cleanWithFallback(spoken.text, key, Store.tone, words, res.mode, res.translate)
        res.text = text
        res.cleanedBy = by
        if (text.isEmpty()) res.skipped = "cleanup returned nothing"
    }

    /** "Transcribe again" from History: null when done, else a message. Call off the main thread. */
    fun retry(entry: JSONObject): String? {
        val audio = Store.audioFor(entry) ?: return "This recording was deleted. Audio is kept for 30 days."
        val res = Outcome()
        try {
            transcribe(audio.readBytes(), res, entry.optString("mode", "default"))
        } catch (e: Exception) {
            return if (Groq.isRateLimit(e)) "Groq’s limit is reached. Try again in a minute."
            else if (Groq.isAuth(e)) "Groq rejected your key." else "No connection."
        }
        if (res.skipped.isNotEmpty()) return "No speech found in this recording."
        Store.updateHistory(entry.optString("id"), res.text, res.raw)
        return null
    }

    fun copy(text: String) {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Sayvra", text))
    }

    fun toast(text: String) = main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var out: Result<T>? = null
        val done = CountDownLatch(1)
        main.post {
            out = runCatching(block)
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        return out?.getOrThrow() ?: throw IllegalStateException("main thread busy")
    }
}
