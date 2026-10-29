package ai.mitovoid.saylo

import android.content.Context

/** The user's Groq key, kept in app-private storage on this phone. */
object Prefs {
    private fun prefs(c: Context) = c.getSharedPreferences("saylo", Context.MODE_PRIVATE)
    fun key(c: Context): String = prefs(c).getString("groq_key", "").orEmpty()
    fun setKey(c: Context, key: String) = prefs(c).edit().putString("groq_key", key).apply()
}
