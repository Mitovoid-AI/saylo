package ai.mitovoid.saylo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** In-app updates, like the Windows app's updates.py: finds the latest GitHub release, downloads its APK
 *  inside Sayvra (checked against GitHub's size and SHA-256), then hands it to Android's installer, which asks
 *  the user to confirm. Local builds are "dev" and never get an update offered. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Mitovoid-AI/saylo/releases/latest"
    // The repo was called saylo before the rename to sayvra; GitHub redirects the old name to the new one.
    private val SOURCES = listOf("https://github.com/Mitovoid-AI/sayvra/releases/download/",  // APKs only from here
        "https://github.com/Mitovoid-AI/saylo/releases/download/")
    // The old name stays in releases until old copies have updated.
    private val APK_NAMES = listOf("sayvra-android.apk", "saylo-android.apk")
    const val PROMPTS_PER_DAY = 3  // the dialog on opening the app shows at most this often a day

    data class Info(val current: String, val latest: String, val available: Boolean)
    data class Job(val state: String = "idle", val version: String = "", val done: Long = 0, val total: Long = 0,
                   val error: String = "")

    @Volatile var job = Job()
        private set
    @Volatile private var release = JSONObject()

    val current: String get() = BuildConfig.VERSION_NAME

    private fun parse(v: String) = v.removePrefix("v").split(".").map { it.toInt() }

    /** True when latest is a later version than current, compared as numbers (0.1.10 is after 0.1.9). */
    fun newer(latest: String, current: String): Boolean = try {
        val a = parse(latest)
        val b = parse(current)
        (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { i ->
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) x > y else null
        } ?: false
    } catch (e: NumberFormatException) {
        false  // "dev", "" or anything else that isn't a version
    }

    /** Asks GitHub for the latest release. Call off the main thread; "" for latest when offline. */
    fun check(): Info {
        val latest = try {
            val conn = URL(LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
            val apk = APK_NAMES.firstNotNullOfOrNull { byName[it] }
            val version = json.getString("tag_name").removePrefix("v")
            release = JSONObject().put("version", version).put("url", apk?.optString("browser_download_url").orEmpty())
                .put("size", apk?.optLong("size") ?: 0).put("sha256", apk?.optString("digest").orEmpty().removePrefix("sha256:"))
            version
        } catch (e: Exception) {
            Log.i("Sayvra", "update check failed: $e")
            ""
        }
        return Info(current, latest, newer(latest, current))
    }

    private fun apk(c: Context, version: String) = File(File(c.cacheDir, "updates").apply { mkdirs() }, "sayvra-$version.apk")

    /** Starts downloading the latest APK in the background; follow it with [job]. */
    @Synchronized
    fun download(c: Context) {
        val version = release.optString("version")
        if ((job.state == "downloading" || job.state == "ready") && job.version == version) return
        val url = release.optString("url")
        if (SOURCES.none { url.startsWith(it) }) {
            job = Job("failed", version, error = "This release has no Android app to download.")
            return
        }
        job = Job("downloading", version, 0, release.optLong("size"))
        val app = c.applicationContext
        Thread {
            val file = apk(app, version)
            val part = File(file.path + ".part")
            try {
                File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
                val digest = MessageDigest.getInstance("SHA-256")
                var conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            job = job.copy(done = done)
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                val size = release.optLong("size")
                val expected = release.optString("sha256")
                if ((size > 0 && part.length() != size) || (expected.isNotEmpty() && sha != expected)) {
                    throw IllegalStateException("checksum mismatch")
                }
                part.renameTo(file)
                job = job.copy(state = "ready")
            } catch (e: Exception) {
                Log.w("Sayvra", "update download failed", e)
                part.delete()
                job = job.copy(state = "failed", error = if (e is IOException) "Check your internet connection."
                    else "The file didn’t match the release.")
            }
        }.start()
    }

    /** Opens Android's installer for the downloaded APK. Returns a message instead when Sayvra first needs
     *  "Install unknown apps" (Settings opens for it). */
    fun install(a: Activity): String? {
        val file = apk(a, job.version)
        if (job.state != "ready" || !file.exists()) return "The update isn’t downloaded yet."
        if (!a.packageManager.canRequestPackageInstalls()) {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            return "Allow Sayvra to install updates, then tap Install now again."
        }
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }
}
