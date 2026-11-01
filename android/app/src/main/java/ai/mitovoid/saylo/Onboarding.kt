package ai.mitovoid.saylo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** First run, one screen at a time, like the Windows onboarding: welcome, sign-in (a placeholder until accounts
 *  exist, see GitHub issue #4), about you, where you type, tone, how it works, microphone, the bubble
 *  (accessibility), privacy, the Groq key, a try-it box, and done. */
private const val STEPS = 12
private const val FIRST_BAR = 2  // the progress bar shows from "about you" to "try it"
private const val LAST_BAR = 10

@Composable
fun Onboarding(resumed: Int, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val next = { step = minOf(step + 1, STEPS - 1) }
    BackHandler(enabled = step > 0) { step-- }
    AnimatedContent(step, transitionSpec = {
        (slideInHorizontally { if (targetState > initialState) it / 4 else -it / 4 } + fadeIn()) togetherWith fadeOut()
    }, label = "onboarding") { s ->
        when (s) {
            0 -> Welcome(next)
            1 -> SignIn(next)
            else -> Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
                if (s in FIRST_BAR..LAST_BAR) Progress(s - FIRST_BAR, LAST_BAR - FIRST_BAR + 1) { step-- }
                when (s) {
                    2 -> AboutYou(next)
                    3 -> Places(next)
                    4 -> ToneStep(next)
                    5 -> HowItWorks(next)
                    6 -> MicStep(resumed, next)
                    7 -> BubbleStep(resumed, next)
                    8 -> PrivacyStep(next)
                    9 -> KeyStep(next)
                    10 -> TryIt(next)
                    else -> AllSet { Store.onboarded = true; onDone() }
                }
            }
        }
    }
}

@Composable
private fun Progress(index: Int, count: Int, back: () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.ink) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(count) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= index) C.accent else C.line))
            }
        }
    }

/** A step's body: title, content, and the main button pinned to the bottom. */
@Composable
private fun Step(title: String, sub: String = "", button: String = "Continue", enabled: Boolean = true,
                 onNext: () -> Unit, extra: (@Composable ColumnScope.() -> Unit)? = null,
                 content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 20.dp, bottom = 16.dp)) {
            Title(title)
            if (sub.isNotEmpty()) Body(sub, Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(24.dp))
            content()
        }
        PrimaryButton(button, enabled = enabled, onClick = onNext)
        extra?.invoke(this)
        Spacer(Modifier.height(16.dp))
    }

// ---------- 0: welcome ----------
@Composable
private fun Welcome(next: () -> Unit) =
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E3A34), C.night, Color(0xFF0B1512))))
        .statusBarsPadding().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Wordmark(Color.White)
        Spacer(Modifier.weight(1f))
        Title("Say it messy.", color = Color.White, size = 44, center = true)
        Title("Send it clean.", color = C.mint, size = 44, center = true)
        Spacer(Modifier.height(18.dp))
        Body("Talk the way you think. Saylo writes it up clean, in any app.", color = Color.White.copy(alpha = 0.75f), center = true)
        Spacer(Modifier.height(36.dp))
        Ribbon()
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Button(next, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = C.ink)) {
            Text("Get started", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }

@Composable
private fun Ribbon() {
    val shift by rememberInfiniteTransition(label = "ribbon").animateFloat(0f, -1f,
        infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart), label = "shift")
    val line = "Can you move our sync to Thursday?  ·  Here’s the plan for launch week.  ·  Thanks, I’ll send the report on Wednesday.  ·  "
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp))) {
        Text(line + line, Modifier.wrapContentWidth(unbounded = true, align = Alignment.Start)
            .graphicsLayer { translationX = shift * size.width / 2 },
            color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp, maxLines = 1, softWrap = false)
    }
}

// ---------- 1: sign-in (placeholder) ----------
@Composable
private fun SignIn(next: () -> Unit) {
    var policy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Wordmark()
        Spacer(Modifier.weight(1f))
        Text(buildAnnotatedString {
            append("Get started for ")
            withStyle(SpanStyle(color = C.accent, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append("free") }
        }, fontFamily = Serif, fontSize = 36.sp, color = C.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        // Accounts aren't built yet (GitHub issue #4): every button just moves on, and nothing leaves the phone.
        SecondaryButton("Continue with Google", onClick = next)
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Continue with email", onClick = next)
        Spacer(Modifier.height(12.dp))
        Text("More options", Modifier.clickable(onClick = next).padding(8.dp), color = C.ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(16.dp))
        Text(buildAnnotatedString {
            append("By continuing, you agree to Saylo’s Terms and ")
            withStyle(SpanStyle(color = C.ink, fontWeight = FontWeight.SemiBold)) { append("Privacy Policy") }
            append(". Accounts are coming soon; for now your details stay on this phone.")
        }, Modifier.clickable { policy = true }.padding(horizontal = 8.dp), color = C.muted, fontSize = 12.sp,
            textAlign = TextAlign.Center, lineHeight = 17.sp)
        Spacer(Modifier.weight(1f))
    }
    if (policy) PrivacyPolicy { policy = false }
}

// ---------- 2: about you ----------
private val ROLES = listOf("Founder / CEO", "Engineer", "Product", "Design", "Marketing", "Sales", "Support",
    "Writer / Creator", "Student", "Teacher", "Healthcare", "Legal", "Something else")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutYou(next: () -> Unit) {
    var name by remember { mutableStateOf(Store.name) }
    var role by remember { mutableStateOf(Store.role) }
    Step("Tell us about you", "Saylo tunes its writing to the way you work.", onNext = {
        Store.name = name.trim(); Store.role = role; next()
    }) {
        Label("Your name")
        Spacer(Modifier.height(8.dp))
        TextBox(name, { name = it }, "First name", capitalize = true)
        Spacer(Modifier.height(24.dp))
        Label("What do you do?")
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ROLES.forEach { r -> Chip(r, r == role) { role = if (role == r) "" else r } }
        }
    }
}

@Composable
fun TextBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
            capitalize: Boolean = false, secret: Boolean = false) =
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), placeholder = { Text(hint, color = C.muted) },
        singleLine = true, shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(capitalization = if (capitalize) KeyboardCapitalization.Words else KeyboardCapitalization.None),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
            focusedContainerColor = C.card, unfocusedContainerColor = C.card, cursorColor = C.accent))

// ---------- 3: where you type ----------
private val PLACES = listOf("🤖" to "Chatting with AI", "📝" to "Taking notes", "✉️" to "Drafting emails",
    "💬" to "Sending messages", "💼" to "Messaging at work", "🧑‍💻" to "Writing code")

@Composable
private fun Places(next: () -> Unit) {
    var picked by remember { mutableStateOf(Store.places) }
    Step("Where do you spend time typing right now?", "Pick all that apply.", enabled = picked.isNotEmpty(),
        onNext = { Store.places = picked; next() }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PLACES.forEach { (icon, p) ->
                Choice(p, p in picked, icon = icon) { picked = if (p in picked) picked - p else picked + p }
            }
        }
    }
}

// ---------- 4: tone ----------
private val TONES = listOf(
    Triple("Casual", "Relaxed, like a text to a friend", "hey, running 5 min late, see you soon"),
    Triple("Balanced", "Clean and natural for most things", "Hey, I’m running five minutes late. See you soon."),
    Triple("Formal", "Polished, for work and email", "Hello, I am running five minutes late. I will see you shortly."),
)

@Composable
fun ToneCards(tone: String, onPick: (String) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    TONES.forEach { (t, what, example) ->
        val sel = t == tone
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .border(if (sel) 1.5.dp else 1.dp, if (sel) C.accent else C.line, RoundedCornerShape(14.dp))
            .background(if (sel) C.accentSoft else C.card).clickable { onPick(t) }.padding(16.dp)) {
            Text(t, color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(what, color = C.muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Text("“$example”", color = if (sel) C.accentInk else C.ink, fontFamily = Serif, fontSize = 16.sp)
        }
    }
}

@Composable
private fun ToneStep(next: () -> Unit) {
    var tone by remember { mutableStateOf(Store.tone) }
    Step("How should Saylo sound?", "In WhatsApp and AI apps Saylo uses their own style. You can change this anytime.",
        onNext = { Store.tone = tone; next() }) {
        ToneCards(tone) { tone = it }
    }
}

// ---------- 5: how it works ----------
@Composable
private fun HowItWorks(next: () -> Unit) = Step("How it works", button = "Next", onNext = next) {
    PhoneDemo()
    Spacer(Modifier.height(24.dp))
    Bullet("1", "Tap any text box. The Saylo bubble shows up above your keyboard.")
    Bullet("2", "Hold the bubble and talk. Let go, and clean text appears in the box.")
    Bullet("3", "Or tap it once to talk hands-free, then tap ✓ to send.")
    Bullet("4", "Saylo writes for the app: a clear prompt in ChatGPT, relaxed in WhatsApp, polished everywhere else.")
}

@Composable
private fun Bullet(n: String, text: String) = Row(Modifier.padding(vertical = 6.dp)) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
        Text(n, color = C.accentInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.width(12.dp))
    Body(text, color = C.ink, size = 15)
}

/** A phone with a chat, a text box and the bubble over the keyboard. */
@Composable
private fun PhoneDemo() = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(C.ink).padding(16.dp)) {
    Text("hi!", Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2933)).padding(10.dp, 6.dp), color = Color.White.copy(0.7f), fontSize = 13.sp)
    Spacer(Modifier.height(6.dp))
    Text("are we still on for friday?", Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF2C2933)).padding(10.dp, 6.dp), color = Color.White.copy(0.7f), fontSize = 13.sp)
    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Yes! Friday works, see you at 7", Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2C2933))
            .padding(14.dp, 10.dp), color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(C.night).border(1.dp, C.mint.copy(0.4f), CircleShape), contentAlignment = Alignment.Center) {
            Logo(30.dp, square = false)
        }
    }
    Spacer(Modifier.height(12.dp))
    repeat(3) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(if (row == 1) 9 else 10) { Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF2C2933))) }
        }
    }
}

// ---------- 6: microphone ----------
@Composable
private fun MicStep(resumed: Int, next: () -> Unit) {
    val ctx = LocalContext.current
    fun granted() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var ok by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { yes ->
        ok = yes
        denied = !yes
        if (yes) next()
    }
    LaunchedEffect(resumed) { ok = granted() }
    Step("Allow the microphone", "Saylo listens only while you hold or tap the bubble, never in the background.",
        button = if (ok) "Next" else if (denied) "Open app settings" else "Allow microphone", onNext = {
            when {
                ok -> next()
                denied -> ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                else -> ask.launch(Manifest.permission.RECORD_AUDIO)
            }
        }) {
        Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(140.dp).clip(CircleShape).background(C.accentSoft), contentAlignment = Alignment.Center) {
                Logo(76.dp)
            }
        }
        if (ok) Body("Microphone allowed ✓", color = C.accentInk, center = true, modifier = Modifier.fillMaxWidth())
        if (denied) Body("Android blocked it. Open app settings, tap Permissions, then allow Microphone.", color = C.err, center = true)
    }
}

// ---------- 7: the bubble (accessibility) ----------
@Composable
private fun BubbleStep(resumed: Int, next: () -> Unit) {
    val ctx = LocalContext.current
    var agreed by rememberSaveable { mutableStateOf(false) }
    var on by remember { mutableStateOf(SayloAccessibility.isOn(ctx)) }
    val wasOn = remember { on }  // already on when this screen opened (setup run again)
    LaunchedEffect(resumed) {
        val now = SayloAccessibility.isOn(ctx)
        if (now && !on) next()  // just turned on in Settings
        on = now
    }
    if (wasOn && on) {
        Step("The Saylo bubble is on", "Saylo can show its bubble and type into other apps.", button = "Next", onNext = next) {}
    } else if (!agreed) {
        Step("Let Saylo type for you", "Saylo needs Android’s Accessibility permission to show its bubble and type into other apps.",
            button = "Agree", onNext = { agreed = true }, extra = {
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Not now", onClick = next)
            }) {
            Panel(color = C.panel) {
                Disclosure("What Saylo uses it for", "To see when a text box is focused, show the bubble above your keyboard, and type your dictation into that box.")
                Disclosure("What Saylo reads", "The app you’re in and your browser’s address bar, to pick the writing style (a prompt for ChatGPT, relaxed for WhatsApp), and the text box you’re dictating into.")
                Disclosure("What it never does", "It skips password fields, doesn’t store or send what’s on your screen, and doesn’t act on its own. Only your recording goes to Groq.", last = true)
            }
        }
    } else {
        val activity = ctx as Activity
        Step("Turn on the Saylo bubble", button = "Open settings", onNext = {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, extra = {
            Text("I’ll do it later", Modifier.fillMaxWidth().clickable(onClick = next).padding(12.dp), color = C.muted, textAlign = TextAlign.Center)
        }) {
            Instruction("1", "Open Installed apps (or Downloaded apps)", "Installed apps")
            Instruction("2", "Tap Saylo bubble and turn it on", "Saylo bubble          On")
            Instruction("3", "Leave the shortcut off", "Saylo bubble shortcut     Off")
            Spacer(Modifier.height(12.dp))
            Panel(color = C.warm) {
                Text("Greyed out or “Restricted setting”?", color = C.ink, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Body("Android blocks this for apps installed outside the Play Store. Open App info, tap ⋮ (top right), choose Allow restricted settings, then try again.", color = C.ink, size = 14)
                Spacer(Modifier.height(10.dp))
                SmallButton("Open App info") {
                    activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                }
            }
        }
    }
}

@Composable
private fun Disclosure(title: String, text: String, last: Boolean = false) {
    Text(title, color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    Body(text, size = 14)
    if (!last) Spacer(Modifier.height(14.dp))
}

@Composable
private fun Instruction(n: String, text: String, mock: String) {
    Bullet(n, text)
    Text(mock, Modifier.fillMaxWidth().padding(start = 38.dp, bottom = 10.dp).clip(RoundedCornerShape(10.dp))
        .background(C.card).border(1.dp, C.line, RoundedCornerShape(10.dp)).padding(12.dp), color = C.ink, fontSize = 13.sp)
}

// ---------- 8: privacy ----------
@Composable
private fun PrivacyStep(next: () -> Unit) {
    var keep by remember { mutableStateOf(Store.keepHistory) }
    var agree by remember { mutableStateOf(Store.agreedPrivacy) }
    var policy by remember { mutableStateOf(false) }
    Step("Your privacy", "Your recording goes from this phone straight to Groq to be turned into text. Saylo has no server of its own.",
        enabled = agree, onNext = { Store.keepHistory = keep; Store.agreedPrivacy = true; next() }) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep history on this phone", color = C.ink, fontWeight = FontWeight.SemiBold)
                    Body("Your dictations, plus each recording for 30 days so you can replay it.", size = 13)
                }
                Switch(keep, { keep = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.accent))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { agree = !agree }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(agree, { agree = it }, colors = CheckboxDefaults.colors(checkedColor = C.accent))
            Text(buildAnnotatedString {
                append("I agree to Saylo’s ")
                withStyle(SpanStyle(color = C.accent, fontWeight = FontWeight.SemiBold)) { append("Privacy Policy") }
            }, Modifier.weight(1f), color = C.ink, fontSize = 15.sp)
            SmallButton("Read") { policy = true }
        }
    }
    if (policy) PrivacyPolicy { policy = false }
}

// ---------- 9: the Groq key ----------
@Composable
private fun KeyStep(next: () -> Unit) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf(if (Store.key.isNotEmpty()) "Using your own Groq key ✓" else "") }
    var ok by remember { mutableStateOf(Store.key.isNotEmpty()) }
    var checking by remember { mutableStateOf(false) }
    Step("Add your free Groq key", "Saylo uses Groq to turn speech into text. The key is free, takes a minute, and stays on this phone.",
        button = if (ok) "Continue" else if (checking) "Checking…" else "Check and save", enabled = !checking && (ok || key.isNotBlank()),
        onNext = {
            if (ok && key.isBlank()) next() else {
                checking = true
                msg = ""
                Thread {
                    val error = Groq.checkKey(key.trim())
                    (ctx as Activity).runOnUiThread {
                        checking = false
                        if (error == null) {
                            Store.key = key.trim(); Dictation.wake(); ok = true; key = ""; msg = "Key saved ✓"; next()
                        } else msg = error
                    }
                }.start()
            }
        }) {
        Bullet("1", "Sign in at console.groq.com/keys and tap Create API Key.")
        SmallButton("Get a free key", Modifier.padding(start = 38.dp, bottom = 14.dp)) {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
        }
        Bullet("2", "Copy it, come back and paste it here.")
        Spacer(Modifier.height(8.dp))
        TextBox(key, { key = it }, if (ok) "Paste a new key to replace it" else "gsk_…", secret = true)
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp), color = if (msg.endsWith("✓")) C.accentInk else C.err, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Body("Groq’s free plan has a per-minute limit. If you reach it, Saylo keeps your recording and sends it when the limit resets.", size = 13)
    }
}

// ---------- 10: try it ----------
private val DEMOS = listOf(
    Triple("Team chat", "Maya", "Did the report go out yet?"),
    Triple("Email", "Re: Launch plan", "Can we move our sync to Thursday?"),
    Triple("AI chat", "ChatGPT", "How can I help you today?"),
)

@Composable
private fun TryIt(next: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var live by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val l: (Dictation.Status) -> Unit = { s ->
            live = when (s) {
                is Dictation.Working -> "Cleaning up…"
                is Dictation.Retrying -> s.text
                is Dictation.Message -> s.text
                is Dictation.Done -> { done = true; if (s.pasted) "Done ✓" else "Copied. Long-press the box and paste." }
            }
        }
        Dictation.addListener(l)
        onDispose { Dictation.removeListener(l) }
    }
    val bubbleOn = SayloAccessibility.isOn(ctx)
    Step("Give it a try", if (bubbleOn) "Tap the box below, then hold the Saylo bubble above your keyboard and talk. Let go when you’re done."
        else "The bubble is off. Switch to the Saylo keyboard and hold its mic, or turn the bubble on later in Settings.",
        button = if (done) "Next" else "Skip for now", onNext = next) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DEMOS.forEachIndexed { i, d -> Chip(d.first, i == tab) { tab = i; text = "" } }
        }
        Spacer(Modifier.height(14.dp))
        Panel {
            val (_, who, said) = DEMOS[tab]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(C.warm), contentAlignment = Alignment.Center) {
                    Text(who.take(1), color = C.warmInk, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(who, color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(said, color = C.muted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Tap here, then use the bubble…", color = C.muted) }, shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line))
        }
        if (live.isNotEmpty()) Text(live, Modifier.fillMaxWidth().padding(top = 12.dp), color = C.accentInk,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

// ---------- 11: all set ----------
@Composable
private fun AllSet(done: () -> Unit) = Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Spacer(Modifier.weight(1f))
    Text("✨", fontSize = 56.sp)
    Spacer(Modifier.height(12.dp))
    Title("You’re all set", size = 40, center = true)
    Spacer(Modifier.height(10.dp))
    Body("Saylo is ready. Tap any text box and talk.", color = C.ink, center = true)
    Spacer(Modifier.weight(1f))
    PrimaryButton("Start using Saylo", onClick = done)
}

// ---------- the privacy policy ----------
@Composable
fun PrivacyPolicy(close: () -> Unit) = androidx.compose.ui.window.Dialog(close,
    androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close", tint = C.ink) }
            Text("Privacy Policy", color = C.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            POLICY.forEach { (h, t) ->
                Column {
                    Text(h, color = C.ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(4.dp))
                    Body(t, size = 15)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val POLICY = listOf(
    "When Saylo listens" to "Only while you hold or tap the Saylo bubble or the mic on the Saylo keyboard. Never in the background.",
    "Where your voice goes" to "Each recording goes from your phone straight to Groq (groq.com) using your own Groq key, to be turned into text and cleaned up. Groq’s privacy policy covers that. Saylo has no server of its own and never sees your recordings.",
    "What stays on your phone" to "Your settings, your Groq key, your dictionary and, if you keep history on, your dictations with each recording for 30 days. You can turn history off or delete it anytime in Settings. Uninstalling Saylo deletes all of it.",
    "Accessibility" to "The bubble uses Android’s Accessibility permission to see which app you’re in, read your browser’s address bar (to pick a writing style), and type into the focused text box. It skips password fields and doesn’t store or send what’s on your screen.",
    "Your account" to "Sign-in isn’t connected to a server yet. Your name and choices from setup stay on this phone.",
    "Updates" to "Saylo checks GitHub for a newer version when you open it. That request contains no personal data.",
    "Questions" to "Open an issue at github.com/Mitovoid-AI/saylo.",
)
