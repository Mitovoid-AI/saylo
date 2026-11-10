package ai.mitovoid.saylo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Sayvra's look on Android: the Windows app's palette (ui/app.css), serif headlines, and shared pieces. */
object C {
    val bg = Color(0xFFFBF9F6)
    val panel = Color(0xFFF2EDE5)
    val card = Color.White
    val ink = Color(0xFF16141B)
    val muted = Color(0xFF6E6A74)
    val line = Color(0xFFE7E1D8)
    val accent = Color(0xFF0E7C66)
    val accentSoft = Color(0xFFE2F3EC)
    val accentInk = Color(0xFF095A4A)
    val mint = Color(0xFF7FE0C2)
    val warm = Color(0xFFFDEBD8)
    val warmInk = Color(0xFFB5651D)
    val err = Color(0xFFC0392B)
    val night = Color(0xFF10201C)
}

val Serif = FontFamily.Serif

@Composable
fun SayloTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = lightColorScheme(primary = C.accent, onPrimary = Color.White, background = C.bg, surface = C.card,
        onSurface = C.ink, onBackground = C.ink, secondary = C.accent),
    content = content,
)

/** Big serif headline, like the onboarding titles. */
@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 32, color: Color = C.ink, center: Boolean = false) =
    Text(text, modifier, color = color, fontFamily = Serif, fontSize = size.sp, lineHeight = (size * 1.18).sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start)

@Composable
fun Body(text: String, modifier: Modifier = Modifier, color: Color = C.muted, size: Int = 16, center: Boolean = false) =
    Text(text, modifier, color = color, fontSize = size.sp, lineHeight = (size * 1.45).sp,
        textAlign = if (center) TextAlign.Center else TextAlign.Start)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.ink, contentColor = Color.White,
            disabledContainerColor = C.ink.copy(alpha = 0.3f), disabledContentColor = Color.White)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, C.line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = C.card, contentColor = C.ink)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun SmallButton(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) =
    Box(modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, C.line, RoundedCornerShape(10.dp))
        .background(C.card).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = if (danger) C.err else C.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: String = "", onClick: () -> Unit) =
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .border(if (selected) 1.5.dp else 1.dp, if (selected) C.accent else C.line, RoundedCornerShape(14.dp))
        .background(if (selected) C.accentSoft else C.card).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon.isNotEmpty()) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = if (selected) C.accentInk else C.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) =
    Box(Modifier.clip(RoundedCornerShape(99.dp))
        .border(if (selected) 1.5.dp else 1.dp, if (selected) C.accent else C.line, RoundedCornerShape(99.dp))
        .background(if (selected) C.accentSoft else C.card).clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, color = if (selected) C.accentInk else C.ink, fontSize = 14.sp)
    }

@Composable
fun Panel(modifier: Modifier = Modifier, color: Color = C.card, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, C.line, RoundedCornerShape(18.dp))
        .background(color).padding(20.dp), content = content)

@Composable
fun Label(text: String) =
    Text(text.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)

@Composable
fun Badge(text: String, color: Color = C.warm, ink: Color = C.warmInk) =
    Text(text, Modifier.clip(RoundedCornerShape(99.dp)).background(color).padding(horizontal = 10.dp, vertical = 4.dp),
        color = ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)

/** The Sayvra mark: four mint bars on a dark rounded square. */
@Composable
fun Logo(size: Dp = 56.dp, square: Boolean = true) =
    Canvas(Modifier.size(size)) {
        if (square) drawRoundRect(C.ink, cornerRadius = CornerRadius(this.size.width * 0.28f))
        val heights = floatArrayOf(0.2f, 0.38f, 0.5f, 0.28f)
        val gap = this.size.width * 0.13f
        val w = this.size.width * 0.07f
        heights.forEachIndexed { i, h ->
            val x = center.x + (i - 1.5f) * gap - w / 2
            val hh = this.size.height * h
            drawRoundRect(C.mint, Offset(x, center.y - hh / 2), Size(w, hh), CornerRadius(w / 2))
        }
    }

@Composable
fun Wordmark(color: Color = C.ink) = Row(verticalAlignment = Alignment.CenterVertically) {
    Logo(30.dp)
    Spacer(Modifier.width(10.dp))
    Text("Sayvra", color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
}

/** A scrolling page with side padding, for settings-like screens. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), content = content)

val Small = TextStyle(fontSize = 13.sp, color = C.muted)
