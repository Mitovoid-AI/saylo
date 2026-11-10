package ai.mitovoid.saylo

import java.util.regex.Pattern

/** Where a dictation is going picks its cleanup style ("mode"), and "Hey Sayvra, ..." commands inside it change
 *  the mode or ask for English. A port of the Windows app's context.py: keep the two in sync. */
object Commands {
    val LABELS = mapOf("ai" to "AI prompt", "whatsapp" to "WhatsApp", "default" to "")
    private val SPOKEN = mapOf("ai" to "AI prompt mode", "whatsapp" to "WhatsApp mode", "default" to "email mode")

    /** The commands worth offering in this mode: the other styles, and translating. */
    fun hints(mode: String) =
        listOf("ai", "whatsapp", "default").filter { it != mode }.map { "Hey Sayvra, ${SPOKEN[it]}" } +
            "Hey Sayvra, translate to English"

    // Apps by package name: AI chat apps and terminals get the AI prompt style; personal messengers the relaxed one.
    private val APPS = mapOf(
        "ai" to setOf(
            "com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard", "ai.perplexity.app.android",
            "com.microsoft.copilot", "com.deepseek.chat", "ai.x.grok", "ai.mistral.chat", "com.quora.poe",
            "com.termux", "com.replit.app",
        ),
        "whatsapp" to setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thunderdog.challegram", "org.thoughtcrime.securesms", "com.facebook.orca", "com.discord",
        ),
    )
    private val SITES = mapOf(
        "ai" to listOf("chatgpt.com", "chat.openai.com", "claude.ai", "gemini.google.com", "aistudio.google.com",
            "perplexity.ai", "copilot.microsoft.com", "chat.deepseek.com", "grok.com", "chat.mistral.ai", "poe.com"),
        "whatsapp" to listOf("web.whatsapp.com", "web.telegram.org", "messenger.com", "discord.com"),
    )

    /** The mode for an app, and in a browser for the site in its address bar ("" when unknown). */
    fun modeFor(pkg: String?, url: String = ""): String {
        APPS.entries.firstOrNull { pkg in it.value }?.let { return it.key }
        val host = url.trim().substringAfter("://").substringBefore("/").lowercase().removePrefix("www.")
        if (host.isEmpty() || ' ' in host) return "default"
        return SITES.entries.firstOrNull { (_, sites) -> sites.any { host == it || host.endsWith(".$it") } }?.key
            ?: "default"
    }

    // Commands, anywhere in a dictation, in two forms:
    // - a sentence starting with Sayvra's name, then commands, then text: "Hey Sayvra, WhatsApp mode, I'll be late"
    // - sentences made only of command words, with Sayvra's name among them: "Switch mode. Sayvra. Use AI prompt mode."
    // A sentence with any other words is text, so "my buddy Sayvra" or "WhatsApp mode is great" is written as said.
    // The name includes the old name Saylo (still accepted) and what Whisper has written for them ("Shallow",
    // "Hello"); those only count before a command.
    private const val WAKE = "(?:sayvra|say vra|sayvera|say vera|saivra|savra|sevra|sayva|say bra|sabra|zavra|" +
        "saylo|say lo|say low|saylow|sailo|salo|sayla|silo|sylo|shailo|shylo|shilo|seylo|" +
        "say hello|shallow|hello|hallo|halo|solo|sello|shello)"
    private val MODE_WORDS = linkedMapOf(
        "ai" to "ai prompt|eia prompt|prompt|ai|eia|coding",  // "EIA": Whisper's "AI"
        "whatsapp" to "whatsapp|whats app|what's app|chat|texting",
        "default" to "email|e-mail|mail|normal|default|notes?|standard",
    )
    private val PREFIX = Pattern.compile("(?:(?:hey|hi|ok|okay|hesh)[\\s,.!]*)?$WAKE[\\s,.!:;-]+", Pattern.CASE_INSENSITIVE)
    private val ATOM = Pattern.compile(
        "(?:(?:(?:shift|switch|change|go|move)\\s+(?:it\\s+)?(?:to\\s+)?|use\\s+)?(?:the\\s+)?" +
            "(?<mode>${MODE_WORDS.values.joinToString("|")})\\s+(?:mode|style)\\b" +
            "|(?<translate>translate(?:\\s+(?:it|this|everything|all))?(?:\\s+(?:to|into|in))?\\s+english|english\\s+translation)\\b)" +
            "[\\s,.!:;-]*(?:(?:and|also|then)\\s+)?",
        Pattern.CASE_INSENSITIVE,
    )
    private val NAMED = Regex("\\b$WAKE\\b")
    private val VOCAB = """hey hi ok okay please now sayvra vra sayvera vera saivra savra sevra sayva bra sabra
        zavra saylo say lo low saylow sailo salo sayla silo sylo shailo shylo shilo
        seylo hello shallow hallo halo solo sello shello hesh switch shift change go move use to into in the a it this
        mode style translate translation english and also then on turn everything all whatsapp whats what's app chat
        texting ai eia prompt coding email e mail normal default note notes standard""".split(Regex("\\s+")).toSet()
    private val WORD_MODE = mapOf("whatsapp" to "whatsapp", "whats" to "whatsapp", "chat" to "whatsapp",
        "texting" to "whatsapp", "ai" to "ai", "eia" to "ai", "prompt" to "ai", "coding" to "ai", "email" to "default",
        "mail" to "default", "normal" to "default", "default" to "default", "note" to "default", "notes" to "default",
        "standard" to "default")

    data class Parsed(val mode: String?, val translate: Boolean, val text: String)

    private fun words(text: String) = Regex("[a-z']+").findAll(text.lowercase()).map { it.value }.toList()

    private fun modeOf(name: String) =
        MODE_WORDS.entries.first { Pattern.compile(it.value, Pattern.CASE_INSENSITIVE).matcher(name).matches() }.key

    private fun upperFirst(s: String) = s.replaceFirstChar { it.uppercaseChar() }

    fun parse(raw: String): Parsed {
        val sentences = raw.replace(Regex("\\b[Aa]\\.\\s?[Ii]\\.?"), "AI").trim().split(Regex("(?<=[.!?])\\s+"))
        var mode: String? = null
        var translate = false
        val text = mutableListOf<String>()
        var i = 0
        while (i < sentences.size) {
            var run = i
            while (run < sentences.size && words(sentences[run]).let { w -> w.isNotEmpty() && w.all { it in VOCAB } }) run++
            val ws = words(sentences.subList(i, run).joinToString(" "))
            val named = NAMED.containsMatchIn(ws.joinToString(" "))
            val runMode = if ("mode" in ws || "style" in ws) ws.firstNotNullOfOrNull { WORD_MODE[it] } else null
            val runTranslate = "translate" in ws || "translation" in ws
            if (run > i && named && (runMode != null || runTranslate)) {  // only command words, naming Sayvra
                mode = runMode ?: mode
                translate = translate || runTranslate
                i = run
                continue
            }
            var sentence = sentences[i]
            val p = PREFIX.matcher(sentence)
            if (p.lookingAt()) {
                var end = -1
                var pos = p.end()
                while (true) {  // "Hey Sayvra, WhatsApp mode and translate to English, ..." then the text
                    val a = ATOM.matcher(sentence).region(pos, sentence.length)
                    if (!a.lookingAt()) break
                    a.group("mode")?.let { mode = modeOf(it) }
                    translate = translate || a.group("translate") != null
                    end = a.end()
                    pos = end
                }
                if (end >= 0) sentence = upperFirst(sentence.substring(end))
            }
            if (sentence.isNotBlank()) text += sentence.trim()
            i++
        }
        return Parsed(mode, translate, upperFirst(text.joinToString(" ")))
    }
}
