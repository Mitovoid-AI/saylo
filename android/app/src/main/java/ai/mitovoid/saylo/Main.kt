package ai.mitovoid.saylo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The app after setup: Home, Dictionary, Style and History tabs, a side menu with Settings, Plans and
 *  updates, and the "new version" dialog (at most Updater.PROMPTS_PER_DAY times a day when the app opens). */
@Composable
fun MainApp(resumed: Int, rerunSetup: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf("tabs") }  // tabs, settings, plans
    var revision by remember { mutableIntStateOf(0) }  // bumped when a dictation lands, to refresh lists
    var update by remember { mutableStateOf<Updater.Info?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        val l: (Dictation.Status) -> Unit = { if (it is Dictation.Done || it is Dictation.Message) revision++ }
        Dictation.addListener(l)
        onDispose { Dictation.removeListener(l) }
    }
    LaunchedEffect(resumed) {
        val info = withContext(Dispatchers.IO) { Updater.check() }
        if (info.available && update == null && Store.takeUpdatePrompt(Updater.PROMPTS_PER_DAY)) update = info
    }
    val checkNow: () -> Unit = {
        scope.launch {
            val info = withContext(Dispatchers.IO) { Updater.check() }
            when {
                info.available -> update = info
                info.latest.isEmpty() -> toast(ctx, "Couldn’t check for updates. Check your internet connection.")
                info.current == "dev" -> toast(ctx, "This is a development build. The latest release is ${info.latest}.")
                else -> toast(ctx, "You’re on the latest version (${info.current}).")
            }
        }
    }
    BackHandler(enabled = screen != "tabs" || drawer.isOpen) {
        if (drawer.isOpen) scope.launch { drawer.close() } else screen = "tabs"
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = screen == "tabs", drawerContent = {
        ModalDrawerSheet(drawerContainerColor = C.bg) {
            Menu(onPick = { pick ->
                scope.launch { drawer.close() }
                when (pick) {
                    "update" -> checkNow()
                    "issue" -> open(ctx, "https://github.com/Mitovoid-AI/saylo/issues/new")
                    "feedback" -> open(ctx, "https://github.com/Mitovoid-AI/saylo/issues/new?title=Feedback")
                    else -> screen = pick
                }
            })
        }
    }) {
        Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
            when (screen) {
                "settings" -> {
                    TopBar("Settings") { screen = "tabs" }
                    SettingsScreen(resumed, checkNow, rerunSetup)
                }
                "plans" -> {
                    TopBar("Plans") { screen = "tabs" }
                    Plans()
                }
                else -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menu", tint = C.ink) }
                        Spacer(Modifier.weight(1f))
                        Wordmark()
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.width(48.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> HomeTab(resumed, revision) { screen = "settings" }
                            1 -> DictionaryTab()
                            2 -> StyleTab()
                            else -> HistoryTab(revision)
                        }
                    }
                    BottomBar(tab) { tab = it }
                }
            }
        }
    }
    update?.let { UpdateDialog(it) { update = null } }
}

private fun toast(ctx: android.content.Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
private fun open(ctx: android.content.Context, url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

@Composable
private fun TopBar(title: String, back: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.ink) }
        Text(title, color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }

@Composable
private fun BottomBar(tab: Int, pick: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().background(C.card).border(1.dp, C.line).padding(vertical = 8.dp)) {
        listOf("Home", "Dictionary", "Style", "History").forEachIndexed { i, name ->
            val on = i == tab
            Column(Modifier.weight(1f).clickable { pick(i) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.clip(RoundedCornerShape(99.dp)).background(if (on) C.accentSoft else Color.Transparent)
                    .padding(horizontal = 18.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
                    val tint = if (on) C.accentInk else C.muted
                    when (i) {
                        0 -> Icon(Icons.Filled.Home, name, tint = tint)
                        1 -> Text("Ab", color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp, fontFamily = Serif)
                        2 -> Text("Aa", color = tint, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        else -> Icon(Icons.Filled.DateRange, name, tint = tint)
                    }
                }
                Text(name, color = if (on) C.ink else C.muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }

// ---------- side menu ----------
@Composable
private fun Menu(onPick: (String) -> Unit) = Column(Modifier.fillMaxSize().padding(16.dp)) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.panel).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.accent), contentAlignment = Alignment.Center) {
            Text(Store.name.take(1).uppercase().ifEmpty { "S" }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(Store.name.ifEmpty { "Saylo user" }, color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("Free plan · your own Groq key", color = C.muted, fontSize = 13.sp)
    }
    Spacer(Modifier.height(12.dp))
    MenuItem("⚠️", "Report an issue") { onPick("issue") }
    MenuItem("💬", "Share feedback") { onPick("feedback") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = C.line)
    MenuItem("⚙️", "Settings") { onPick("settings") }
    MenuItem("⭐", "Plans") { onPick("plans") }
    MenuItem("🔄", "Check for updates") { onPick("update") }
    Spacer(Modifier.weight(1f))
    PrimaryButton("Get Saylo Pro", onClick = { onPick("plans") })
}

@Composable
private fun MenuItem(icon: String, text: String, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(14.dp))
        Text(text, color = C.ink, fontSize = 16.sp)
    }

// ---------- home ----------
@Composable
private fun HomeTab(resumed: Int, revision: Int, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val bubble = remember(resumed) { SayloAccessibility.isOn(ctx) }
    val mic = remember(resumed) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val stats = remember(revision, resumed) { Store.stats() }
    val today = remember(revision, resumed) {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        Store.pending().map { JSONObject().put("t", it.wall).put("pending", true) } +
            Store.history().filter { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(it.optLong("t"))) == day }
    }
    Page {
        when {
            Store.key.isEmpty() -> SetupCard("Add your Groq key", "Saylo needs a free Groq key to turn speech into text.", "Add key", openSettings)
            !mic -> SetupCard("Allow the microphone", "Saylo listens only while you use the bubble.", "Allow") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            }
            !bubble -> SetupCard("Turn on the Saylo bubble", "It appears above your keyboard in any app, so you can dictate anywhere.", "Turn on") {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        Title(greeting() + if (Store.name.isNotEmpty()) ", ${Store.name}" else "", size = 28)
        Panel {
            Text("${"%,d".format(stats.words)} words", color = C.accent, fontFamily = Serif, fontSize = 40.sp)
            Text("spoken so far", color = C.ink, fontSize = 15.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                StatSmall(if (stats.wpm > 0) "${stats.wpm}" else "–", "words per minute")
                StatSmall("${stats.streak}", "day streak")
            }
        }
        Label("Today")
        if (today.isEmpty()) Empty("Nothing yet today. Tap any text box and use the Saylo bubble.")
        today.forEach { Entry(it, compact = true) }
    }
}

private fun greeting() = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let {
    if (it < 12) "Good morning" else if (it < 18) "Good afternoon" else "Good evening"
}

@Composable
private fun StatSmall(big: String, small: String) = Column {
    Text(big, color = C.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(small, color = C.muted, fontSize = 13.sp)
}

@Composable
private fun SetupCard(title: String, text: String, button: String, onClick: () -> Unit) =
    Panel(color = C.warm) {
        Title(title, size = 24)
        Spacer(Modifier.height(6.dp))
        Body(text, color = C.ink, size = 15)
        Spacer(Modifier.height(14.dp))
        PrimaryButton(button, onClick = onClick)
    }

@Composable
private fun Empty(text: String) =
    Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, C.line, RoundedCornerShape(14.dp))
        .padding(24.dp), color = C.muted, textAlign = TextAlign.Center, fontSize = 14.sp)

// ---------- one dictation, in Home and History ----------
private var player: MediaPlayer? = null

@Composable
private fun Entry(e: JSONObject, compact: Boolean = false) {
    val ctx = LocalContext.current
    var text by remember(e) { mutableStateOf(e.optString("text")) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(e.optLong("t"))).lowercase()
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(time, color = C.muted, fontSize = 12.sp)
            if (e.optString("mode") in listOf("ai", "whatsapp")) {
                Spacer(Modifier.width(8.dp))
                Badge(Commands.LABELS[e.optString("mode")].orEmpty(), C.accentSoft, C.accentInk)
            }
        }
        Spacer(Modifier.height(6.dp))
        when {
            e.optBoolean("pending") -> Body("Waiting to send. Saved on this phone; it goes as soon as you’re back online.", size = 14)
            e.optBoolean("failed") && text.isEmpty() -> Body("Couldn’t transcribe this recording. Tap ↻ to try again.", size = 14)
            else -> Text(text, color = C.ink, fontSize = 15.sp, lineHeight = 22.sp)
        }
        if (!compact && !e.optBoolean("pending")) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Store.audioFor(e)?.let { file ->
                    SmallButton("▶ Play") {
                        player?.release()
                        player = MediaPlayer().apply { setDataSource(file.path); prepare(); start() }
                    }
                    SmallButton(if (busy) "Working…" else "↻ Again") {
                        if (busy) return@SmallButton
                        busy = true
                        scope.launch {
                            val error = withContext(Dispatchers.IO) { Dictation.retry(e) }
                            busy = false
                            if (error != null) toast(ctx, error)
                            else text = Store.history().firstOrNull { it.optString("id") == e.optString("id") }?.optString("text") ?: text
                        }
                    }
                }
                if (text.isNotEmpty()) SmallButton("Copy") { Dictation.copy(text); toast(ctx, "Copied") }
            }
        }
    }
}

// ---------- dictionary ----------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DictionaryTab() {
    var words by remember { mutableStateOf(Store.dictionary) }
    var input by remember { mutableStateOf("") }
    Page {
        Title("Dictionary", size = 28)
        Body("Names, brands and words Saylo should always spell your way. They go with every dictation as spelling hints.", size = 15)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextBox(input, { input = it }, "Add a word, or a few with commas", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SmallButton("Add") {
                Store.dictionary = words + input.split(",")
                words = Store.dictionary
                input = ""
            }
        }
        if (words.isEmpty()) Empty("No words yet. Add names or terms Saylo gets wrong, like your company or a product.")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            words.forEach { w ->
                Row(Modifier.clip(RoundedCornerShape(99.dp)).border(1.dp, C.line, RoundedCornerShape(99.dp)).background(C.card)
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w, color = C.ink, fontSize = 14.sp)
                    IconButton({ Store.dictionary = words - w; words = Store.dictionary }, Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, "Remove", tint = C.muted, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ---------- style ----------
@Composable
private fun StyleTab() {
    var tone by remember { mutableStateOf(Store.tone) }
    Page {
        Title("Style", size = 28)
        Body("Your everyday tone. In AI apps and messengers Saylo writes in their own style.", size = 15)
        ToneCards(tone) { tone = it; Store.tone = it }
        Spacer(Modifier.height(4.dp))
        Label("Writing style by app")
        StyleRow("AI prompt", "ChatGPT, Claude, Gemini, Perplexity, Copilot, Termux and AI websites in your browser. Your words become a clear prompt: what you want, then context, constraints and steps.")
        StyleRow("WhatsApp", "WhatsApp, Telegram, Signal, Messenger and Discord. Relaxed, like a text.")
        StyleRow("Standard", "Everything else: email, notes, docs. Clean and natural, in your tone.")
        Label("Say it to switch for one message")
        Panel(color = C.panel) {
            listOf("“Hey Saylo, AI prompt mode, …”", "“Hey Saylo, WhatsApp mode, …”", "“Hey Saylo, email mode, …”",
                "“Hey Saylo, translate to English, …”").forEach { Text(it, color = C.ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
            Spacer(Modifier.height(6.dp))
            Body("Hindi comes out as Hinglish (Hindi in English letters) unless you ask for English.", size = 13)
        }
    }
}

@Composable
private fun StyleRow(name: String, text: String) = Panel {
    Text(name, color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Spacer(Modifier.height(4.dp))
    Body(text, size = 14)
}

// ---------- history ----------
@Composable
private fun HistoryTab(revision: Int) {
    var query by remember { mutableStateOf("") }
    val items = remember(revision, query) {
        (if (query.isBlank()) Store.pending().reversed().map { JSONObject().put("t", it.wall).put("pending", true) } else emptyList()) +
            Store.history(query)
    }
    Page {
        Title("History", size = 28)
        TextBox(query, { query = it }, "Search your dictations")
        if (items.isEmpty()) Empty(if (query.isNotBlank()) "No matches." else if (Store.keepHistory) "Your dictations will show up here."
            else "History is off. Turn it on in Settings.")
        items.forEach { Entry(it) }
    }
}

// ---------- settings ----------
@Composable
private fun SettingsScreen(resumed: Int, checkNow: () -> Unit, rerunSetup: () -> Unit) {
    val ctx = LocalContext.current
    val bubble = remember(resumed) { SayloAccessibility.isOn(ctx) }
    val mic = remember(resumed) { ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val imm = ctx.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
    val keyboard = remember(resumed) { imm.enabledInputMethodList.any { it.packageName == ctx.packageName } }
    var keep by remember { mutableStateOf(Store.keepHistory) }
    var key by remember { mutableStateOf("") }
    var keyMsg by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(Store.key.isNotEmpty()) }
    var policy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Page {
        Label("Dictation")
        Panel {
            SettingRow("Saylo bubble", if (bubble) "On" else "Off: turn it on in Accessibility") {
                SmallButton(if (bubble) "Settings" else "Turn on") { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
            SettingRow("Microphone", if (mic) "Allowed" else "Not allowed") {
                if (!mic) SmallButton("Allow") {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }
            }
            SettingRow("Saylo keyboard", if (keyboard) "On. A backup for apps where the bubble can’t type." else "Off. A backup for apps where the bubble can’t type.", last = true) {
                SmallButton(if (keyboard) "Switch" else "Turn on") {
                    if (keyboard) imm.showInputMethodPicker() else ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            }
        }
        Label("Speech service")
        Panel {
            Text(if (hasKey) "Using your own Groq key ✓" else "No Groq key yet", color = if (hasKey) C.accentInk else C.err, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Body("Groq’s limits are per Groq account. Saved recordings are sent once a new key works.", size = 13)
            Spacer(Modifier.height(10.dp))
            TextBox(key, { key = it }, if (hasKey) "Paste a new key to replace it" else "gsk_…", secret = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Save key") {
                    keyMsg = "Checking…"
                    scope.launch {
                        val error = withContext(Dispatchers.IO) { Groq.checkKey(key.trim()) }
                        if (error == null) {
                            Store.key = key.trim(); Dictation.wake(); hasKey = true; key = ""; keyMsg = "Key saved ✓"
                        } else keyMsg = error
                    }
                }
                SmallButton("Get a free key") { open(ctx, "https://console.groq.com/keys") }
            }
            if (keyMsg.isNotEmpty()) Text(keyMsg, Modifier.padding(top = 8.dp), color = if (keyMsg.endsWith("✓")) C.accentInk else C.muted, fontSize = 14.sp)
        }
        Label("Privacy")
        Panel {
            SettingRow("Keep history on this phone", "Dictations, plus recordings for 30 days") {
                Switch(keep, { keep = it; Store.keepHistory = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
            }
            SettingRow("Delete all history", "Text and recordings on this phone") {
                SmallButton("Delete", danger = true) { Store.clearHistory(); toast(ctx, "History deleted") }
            }
            SettingRow("Privacy Policy", "What Saylo keeps and sends", last = true) { SmallButton("Read") { policy = true } }
        }
        Label("App")
        Panel {
            SettingRow("Version", Updater.current) { SmallButton("Check for updates", onClick = checkNow) }
            SettingRow("Run the setup again", "", last = true) { SmallButton("Run setup", onClick = rerunSetup) }
        }
    }
    if (policy) PrivacyPolicy { policy = false }
}

@Composable
private fun SettingRow(title: String, sub: String, last: Boolean = false, end: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = C.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 13.sp)
        }
        end()
    }
    if (!last) HorizontalDivider(color = C.line)
}

// ---------- plans ----------
@Composable
private fun Plans() = Page {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Free", size = 28)
            Spacer(Modifier.width(10.dp))
            Badge("Current plan", C.accentSoft, C.accentInk)
        }
        Spacer(Modifier.height(10.dp))
        listOf("Dictation in any app with your own free Groq key", "Writing styles for AI apps and messengers",
            "Hinglish and translate to English", "Dictionary and history on your phone").forEach { Feature(it) }
    }
    Panel(Modifier.border(1.5.dp, C.accent, RoundedCornerShape(18.dp))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Title("Pro", size = 28)
            Spacer(Modifier.width(10.dp))
            Badge("Coming soon")
        }
        Spacer(Modifier.height(10.dp))
        listOf("Everything in Free", "No API key needed: sign in and talk", "Unlimited words on every device",
            "Early access to new features").forEach { Feature(it) }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("Coming soon", enabled = false) {}
    }
    Body("Pricing will be announced in a future update. Until then, Saylo is free with your own Groq key.", size = 13, center = true,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Feature(text: String) = Row(Modifier.padding(vertical = 5.dp)) {
    Text("✓", color = C.accent, fontWeight = FontWeight.Bold)
    Spacer(Modifier.width(10.dp))
    Text(text, color = C.ink, fontSize = 15.sp)
}

// ---------- the update dialog ----------
@Composable
private fun UpdateDialog(info: Updater.Info, close: () -> Unit) {
    val activity = LocalContext.current as Activity
    var job by remember { mutableStateOf(Updater.job) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(job.state) {
        while (job.state == "downloading") {
            delay(200)
            job = Updater.job
        }
    }
    val busy = job.state == "downloading"
    Dialog(onDismissRequest = { if (!busy) close() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.card).padding(24.dp)) {
            Text("A new version of Saylo is out", color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Body("Saylo ${info.latest} is ready to install. You have ${info.current}.", color = C.ink, size = 15)
            if (job.state == "downloading" || job.state == "ready") {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator({ if (job.state == "ready" || job.total == 0L) 1f else job.done.toFloat() / job.total },
                    Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = C.accent, trackColor = C.panel)
            }
            Spacer(Modifier.height(10.dp))
            Body(note.ifEmpty {
                when (job.state) {
                    "downloading" -> "Downloading… ${if (job.total > 0) job.done * 100 / job.total else 0}% (%.1f of %.1f MB)".format(job.done / 1048576.0, job.total / 1048576.0)
                    "ready" -> "Downloaded. Tap Install now and confirm, and Android updates Saylo."
                    "failed" -> "The download didn’t finish. ${job.error}"
                    else -> "Saylo downloads the update, then Android asks you to confirm the install. Your settings, history and dictionary stay."
                }
            }, size = 14)
            if (!busy) {
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SmallButton("Later", onClick = close)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(C.ink).clickable {
                        note = ""
                        if (job.state == "ready") Updater.install(activity)?.let { note = it }
                        else { Updater.download(activity); job = Updater.job }
                    }.padding(horizontal = 16.dp, vertical = 9.dp)) {
                        Text(when (job.state) { "ready" -> "Install now"; "failed" -> "Try again"; else -> "Update now" },
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
