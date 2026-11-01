package ai.mitovoid.saylo

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Speech to clean text via Groq: Whisper for the transcript, then an LLM cleanup pass.
 *  Same models, prompts (Prompts.kt) and checks as the Windows app (asr.py, cleaner.py); keep them in sync. */
object Groq {
    private const val BASE = "https://api.groq.com/openai/v1"
    const val ASR_MODEL = "whisper-large-v3-turbo"
    const val CLEANUP_MODEL = "qwen/qwen3.8-27b"
    const val FALLBACK_MODEL = "openai/gpt-oss-120b"

    /** Repeated after the transcript, where models weigh instructions most, so they don't answer or add to it. */
    private const val ANCHOR = "Output only the cleaned transcript."
    private val DEVANAGARI = Regex("[ऀ-ॿ]")

    private fun plain(s: String) = s.replace(Regex("[^\\w\\s']"), "").trim().lowercase()

    // Whisper's stock outputs for silence or noise (learned from video subtitles). Nobody dictates these alone.
    private val HALLUCINATIONS = listOf(
        "", "you", "Thanks for watching!", "Thank you for watching.", "Thank you so much for watching.",
        "Please subscribe.", "Subscribe to my channel.", "Like and subscribe.",
        "Subtitles by the Amara.org community",
    ).map(::plain).toSet()

    /** The dictionary as Whisper's prompt, which nudges it to these spellings (Whisper reads ~600 characters). */
    fun hint(words: List<String>): String {
        val out = StringBuilder()
        for (w in words) {
            if (out.length + w.length > 600) break
            if (out.isNotEmpty()) out.append(", ")
            out.append(w)
        }
        return if (out.isEmpty()) "" else "$out."
    }

    /** True for Whisper's silence outputs, and for the prompt read back to us (Whisper does that on silence). */
    fun isHallucination(text: String, prompt: String) =
        plain(text) in HALLUCINATIONS || (prompt.isNotEmpty() && plain(text) == plain(prompt))

    /** Letters and the marks joined to them, so Devanagari vowel signs count too. */
    private fun letters(s: String) = s.count {
        it.isLetter() || Character.getType(it).toByte().let { t ->
            t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK
        }
    }

    /** True when the text is the same words written twice in a row. */
    private fun doubled(s: String): Boolean {
        val w = s.lowercase().replace(Regex("[^\\w\\s']"), "").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val half = w.size / 2
        return half >= 2 && w.size % 2 == 0 && w.subList(0, half) == w.subList(half, w.size)
    }

    class GroqException(val status: Int, body: String) : IOException("Groq returned $status: ${body.take(300)}")

    private fun open(path: String, key: String, timeoutMs: Int): HttpURLConnection =
        (URL(BASE + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $key")
        }

    private fun read(conn: HttpURLConnection): JSONObject {
        val status = conn.responseCode
        if (status !in 200..299) {
            throw GroqException(status, conn.errorStream?.bufferedReader()?.readText().orEmpty())
        }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    /** Whisper detects the language (forcing English dropped most Hindi speech); prompt is hint(). Groq picks
     *  the audio format from the file name (.m4a, .wav). */
    fun transcribe(audio: ByteArray, key: String, prompt: String = "", filename: String = "audio.m4a"): String {
        val boundary = "saylo-" + UUID.randomUUID()
        val body = ByteArrayOutputStream()
        fun field(name: String, value: String) =
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
        field("model", ASR_MODEL)
        field("response_format", "json")
        field("temperature", "0")
        if (prompt.isNotEmpty()) field("prompt", prompt)
        body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
        body.write(audio)
        body.write("\r\n--$boundary--\r\n".toByteArray())

        val conn = open("/audio/transcriptions", key, 30_000)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return read(conn).optString("text").trim()
    }

    /** Turns "thinking" off or down; reasoning models are seconds slower otherwise. */
    private fun JSONObject.reasoning(model: String): JSONObject = apply {
        if (model.startsWith("qwen/")) put("reasoning_effort", "none")
        if (model.startsWith("openai/gpt-oss")) put("reasoning_effort", "low").put("include_reasoning", false)
    }

    /** Throws for a reply that was cut off, or one much longer than the transcript (the model answered it
     *  or wrote it out twice); cleanup only removes words. mode is "ai", "whatsapp" or "default"; translate
     *  writes English, otherwise Hindi comes out as Hinglish. */
    fun clean(raw: String, key: String, model: String = CLEANUP_MODEL, tone: String = "Balanced",
              words: List<String> = emptyList(), mode: String = "default", translate: Boolean = false): String {
        val hindi = DEVANAGARI.containsMatchIn(raw)
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            // Groq's free tier refuses requests that may produce over 1000 tokens; the default asks for 2048.
            .put("max_tokens", minOf(1000, raw.length / 2 + 300))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", Prompts.system(tone, words, mode, translate, hindi)))
                .put(JSONObject().put("role", "user").put("content", "<transcript>$raw</transcript>\n\n$ANCHOR")))
            .reasoning(model)
        val conn = open("/chat/completions", key, 6_000)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        val choice = read(conn).getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") throw IOException("reply was cut off")
        val text = choice.getJSONObject("message").optString("content")
            .trim().removePrefix("<transcript>").removeSuffix("</transcript>").trim().trim('"')
        // An AI prompt gains labels and spelled-out reasoning; Hinglish or English from Hindi comes out longer too.
        var (times, plus) = if (mode == "ai") 1.8 to 80 else 1.3 to 20
        if (hindi || translate) { times = maxOf(times, 1.6); plus = maxOf(plus, 40) }
        if (letters(text) > letters(raw) * times + plus || (doubled(text) && !doubled(raw))) {
            throw IOException("reply is much longer than the transcript: ${text.take(200)}")
        }
        return text
    }

    /** (text, the model that cleaned it): main model, then the fallback, else the transcript as heard ("raw"). */
    fun cleanWithFallback(raw: String, key: String, tone: String, words: List<String>, mode: String,
                          translate: Boolean): Pair<String, String> {
        for (model in listOf(CLEANUP_MODEL, FALLBACK_MODEL)) {
            try {
                val text = clean(raw, key, model, tone, words, mode, translate)
                if (text.isNotEmpty()) return text to model
            } catch (e: Exception) {
                android.util.Log.w("Saylo", "cleanup with $model failed", e)
            }
        }
        return raw to "raw"
    }

    /** Worth trying again later: no network, a timeout, Groq busy or down, or a rejected key (can be fixed). */
    fun retryable(e: Exception) = when (e) {
        is GroqException -> e.status in listOf(401, 403, 408, 429) || e.status >= 500
        is IOException -> true
        else -> false
    }

    fun isAuth(e: Exception) = e is GroqException && (e.status == 401 || e.status == 403)
    fun isRateLimit(e: Exception) = e is GroqException && e.status == 429

    /** Null when the key works, else a message for the user. */
    fun checkKey(key: String): String? = try {
        read(open("/models", key, 15_000))
        null
    } catch (e: GroqException) {
        if (e.status == 401 || e.status == 403) "That key was rejected. Check it and try again." else "Groq returned ${e.status}."
    } catch (e: IOException) {
        "Couldn't reach Groq. Check your internet connection."
    }

    fun friendly(e: Exception): String = when {
        isRateLimit(e) -> "Groq's limit for your key is reached. Saved; Saylo sends it when the limit resets."
        isAuth(e) -> "Groq rejected your key. Saved; open Saylo to update the key."
        else -> "No connection. Saved; Saylo sends it when you're back online."
    }
}
