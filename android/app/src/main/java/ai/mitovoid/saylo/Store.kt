package ai.mitovoid.saylo

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Everything Sayvra keeps on this phone, like the Windows app's store.py: settings and the Groq key (app-private
 *  preferences), the dictionary, history (history.jsonl), each recording for 30 days (audio/) and clips still
 *  waiting to be sent (pending/). Nothing leaves the phone except the clips sent to Groq. */
object Store {
    const val ONBOARDING_VERSION = 1
    private const val AUDIO_DAYS = 30
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    val audioDir get() = File(dir, "audio").apply { mkdirs() }
    val pendingDir get() = File(dir, "pending").apply { mkdirs() }
    private val historyFile get() = File(dir, "history.jsonl")

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        prefs = c.applicationContext.getSharedPreferences("saylo", Context.MODE_PRIVATE)
        dir = c.applicationContext.filesDir
    }

    // ---------- settings ----------
    var key: String
        get() = prefs.getString("groq_key", "").orEmpty()
        set(v) = prefs.edit().putString("groq_key", v).apply()
    var tone: String
        get() = prefs.getString("tone", "Balanced") ?: "Balanced"
        set(v) = prefs.edit().putString("tone", v).apply()
    var name: String
        get() = prefs.getString("name", "").orEmpty()
        set(v) = prefs.edit().putString("name", v).apply()
    var role: String
        get() = prefs.getString("role", "").orEmpty()
        set(v) = prefs.edit().putString("role", v).apply()
    var places: Set<String>
        get() = prefs.getStringSet("places", emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet("places", v).apply()
    var keepHistory: Boolean
        get() = prefs.getBoolean("keep_history", true)
        set(v) = prefs.edit().putBoolean("keep_history", v).apply()
    var agreedPrivacy: Boolean
        get() = prefs.getBoolean("agreed_privacy", false)
        set(v) = prefs.edit().putBoolean("agreed_privacy", v).apply()
    var onboarded: Boolean
        get() = prefs.getInt("onboarded", 0) == ONBOARDING_VERSION
        set(v) = prefs.edit().putInt("onboarded", if (v) ONBOARDING_VERSION else 0).apply()
    var bubbleY: Int
        get() = prefs.getInt("bubble_y", -1)
        set(v) = prefs.edit().putInt("bubble_y", v).apply()

    /** Counts a shown update dialog for today; false once the day's limit is used up. */
    fun takeUpdatePrompt(perDay: Int): Boolean {
        val today = LocalDate.now().toString()
        val count = if (prefs.getString("update_day", "") == today) prefs.getInt("update_count", 0) else 0
        if (count >= perDay) return false
        prefs.edit().putString("update_day", today).putInt("update_count", count + 1).apply()
        return true
    }

    // ---------- dictionary ----------
    var dictionary: List<String>
        get() = JSONArray(prefs.getString("dictionary", "[]")).let { a -> List(a.length()) { a.getString(it) } }
        set(v) = prefs.edit().putString("dictionary", JSONArray(v.map { it.trim() }.filter { it.isNotEmpty() }.distinct()).toString()).apply()

    // ---------- stats (kept even with history off) ----------
    data class Stats(val words: Int, val wpm: Int, val streak: Int)

    fun stats(): Stats {
        val words = prefs.getInt("words", 0)
        val seconds = prefs.getFloat("speech_seconds", 0f)
        val last = prefs.getString("last_day", "") ?: ""
        val streak = if (last == LocalDate.now().toString() || last == LocalDate.now().minusDays(1).toString())
            prefs.getInt("streak", 0) else 0
        return Stats(words, if (seconds > 30) (words / (seconds / 60)).toInt() else 0, streak)
    }

    private fun count(text: String, seconds: Double) {
        val today = LocalDate.now().toString()
        val last = prefs.getString("last_day", "")
        val streak = when (last) {
            today -> prefs.getInt("streak", 1)
            LocalDate.now().minusDays(1).toString() -> prefs.getInt("streak", 0) + 1
            else -> 1
        }
        prefs.edit()
            .putInt("words", prefs.getInt("words", 0) + text.split(Regex("\\s+")).count { it.isNotBlank() })
            .putFloat("speech_seconds", prefs.getFloat("speech_seconds", 0f) + seconds.toFloat())
            .putString("last_day", today).putInt("streak", streak).apply()
    }

    // ---------- clips waiting to be sent ----------
    data class Pending(val id: String, val wall: Long, val seconds: Double, val mode: String) {
        val audio get() = File(pendingDir, "$id.m4a")
    }

    /** Saves a new clip before it is sent, so nothing is lost when the network or Groq fails. */
    fun addPending(m4a: ByteArray, seconds: Double, mode: String): Pending {
        val p = Pending(System.currentTimeMillis().toString() + "-" + UUID.randomUUID().toString().take(6),
            System.currentTimeMillis(), seconds, mode)
        p.audio.writeBytes(m4a)
        File(pendingDir, "${p.id}.json").writeText(JSONObject().put("wall", p.wall).put("seconds", p.seconds)
            .put("mode", mode).toString())
        return p
    }

    fun pending(): List<Pending> = (pendingDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                val j = JSONObject(f.readText())
                Pending(f.nameWithoutExtension, j.getLong("wall"), j.getDouble("seconds"), j.optString("mode", "default"))
            }.getOrNull()?.takeIf { it.audio.exists() }
        }.sortedBy { it.wall }

    fun dropPending(p: Pending) {
        p.audio.delete()
        File(pendingDir, "${p.id}.json").delete()
    }

    /** Moves a sent clip into the history (or deletes it when history is off) and counts its words. */
    fun finishPending(p: Pending, text: String, raw: String, failed: Boolean = false, mode: String = p.mode): JSONObject {
        val entry = JSONObject().put("id", p.id).put("t", p.wall).put("text", text).put("raw", raw)
            .put("seconds", p.seconds).put("total_ms", System.currentTimeMillis() - p.wall).put("mode", mode)
        if (failed) entry.put("failed", true)
        if (!failed) count(text, p.seconds)
        if (keepHistory) {
            p.audio.renameTo(File(audioDir, "${p.id}.m4a"))
            entry.put("audio", "${p.id}.m4a")
            historyFile.appendText(entry.toString() + "\n")
        } else {
            p.audio.delete()
        }
        File(pendingDir, "${p.id}.json").delete()
        return entry
    }

    // ---------- history ----------
    fun history(query: String = ""): List<JSONObject> {
        if (!historyFile.exists()) return emptyList()
        return historyFile.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { query.isBlank() || it.optString("text").contains(query, ignoreCase = true) }
            .reversed()
    }

    /** Replaces an entry's text after "transcribe again". */
    fun updateHistory(id: String, text: String, raw: String) {
        if (!historyFile.exists()) return
        historyFile.writeText(historyFile.readLines().joinToString("") { line ->
            val j = runCatching { JSONObject(line) }.getOrNull()
            if (j?.optString("id") == id) j.put("text", text).put("raw", raw).remove("failed").let { j.toString() + "\n" }
            else "$line\n"
        })
    }

    fun audioFor(entry: JSONObject): File? =
        entry.optString("audio").takeIf { it.isNotEmpty() }?.let { File(audioDir, it) }?.takeIf { it.exists() }

    fun clearHistory() {
        historyFile.delete()
        audioDir.listFiles()?.forEach { it.delete() }
    }

    /** Deletes recordings older than AUDIO_DAYS; their text stays in the history. */
    fun pruneAudio() {
        val cutoff = System.currentTimeMillis() - AUDIO_DAYS * 86_400_000L
        audioDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
