package com.joernmht.paperdesk.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joernmht.paperdesk.R

/*
 * Bauhaus design system for PaperDesk: three colours, three forms, no ornament.
 *
 *   ● blue circle     KEEP / known     in the library, added, done, "Add to literature", "Include in…"
 *   ■ red square      NEW / STOP       new inbox item, dismiss, delete, failed, errors
 *   ▲ yellow triangle FORWARD / moving send to reMarkable, pending + running jobs, later, update available
 *
 * A form always carries its meaning by SHAPE, so colour is never the only signal. Surfaces are flat
 * (no shadows, no tonal elevation), corners are square, borders are ink.
 */

val BRed = Color(0xFFD8232A)
val BYellow = Color(0xFFF2C200)
val BBlue = Color(0xFF1E4FA3)
val BInk = Color(0xFF141414)
val BPaper = Color(0xFFF5F1E8)
private val BBlueDark = Color(0xFF6F9BEA)
private val BRedDark = Color(0xFFF0605D)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = BBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E0F3), onPrimaryContainer = Color(0xFF0E2A5C),
    inversePrimary = BBlueDark,
    secondary = BInk, onSecondary = BPaper,
    secondaryContainer = BInk, onSecondaryContainer = BPaper,   // "selected" (chips, segments) = solid ink block
    tertiary = BYellow, onTertiary = BInk,
    tertiaryContainer = Color(0xFFFBE9A0), onTertiaryContainer = BInk,
    error = BRed, onError = Color.White,
    errorContainer = Color(0xFFF8D4D2), onErrorContainer = Color(0xFF5C0B0E),
    background = BPaper, onBackground = BInk,
    surface = BPaper, onSurface = BInk,
    surfaceVariant = Color(0xFFE9E3D5), onSurfaceVariant = Color(0xFF4A4740),
    surfaceTint = Color.Transparent,
    inverseSurface = BInk, inverseOnSurface = BPaper,
    outline = BInk, outlineVariant = Color(0xFFB9B2A3),
    scrim = Color.Black,
    surfaceBright = BPaper, surfaceDim = Color(0xFFE6DFCF),
    surfaceContainerLowest = BPaper, surfaceContainerLow = BPaper, surfaceContainer = BPaper,
    surfaceContainerHigh = BPaper, surfaceContainerHighest = Color(0xFFEDE8DB),
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = BBlueDark, onPrimary = BInk,
    primaryContainer = Color(0xFF1B2F57), onPrimaryContainer = Color(0xFFD6E0F3),
    inversePrimary = BBlue,
    secondary = BPaper, onSecondary = BInk,
    secondaryContainer = BPaper, onSecondaryContainer = BInk,
    tertiary = BYellow, onTertiary = BInk,
    tertiaryContainer = Color(0xFF4A3C00), onTertiaryContainer = Color(0xFFFBE9A0),
    error = BRedDark, onError = BInk,
    errorContainer = Color(0xFF5C1513), onErrorContainer = Color(0xFFF8D4D2),
    background = BInk, onBackground = BPaper,
    surface = BInk, onSurface = BPaper,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFC9C3B5),
    surfaceTint = Color.Transparent,
    inverseSurface = BPaper, inverseOnSurface = BInk,
    outline = BPaper, outlineVariant = Color(0xFF4A4740),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2A2A2A), surfaceDim = BInk,
    surfaceContainerLowest = BInk, surfaceContainerLow = BInk, surfaceContainer = BInk,
    surfaceContainerHigh = Color(0xFF1B1B1B), surfaceContainerHighest = Color(0xFF222222),
)

private val Sq = RoundedCornerShape(0.dp)
private val Square = Shapes(extraSmall = Sq, small = Sq, medium = Sq, large = Sq, extraLarge = Sq)

/** Jost (SIL OFL 1.1, see android/licenses/Jost-OFL.txt): one variable font, weights picked by axis. */
@OptIn(ExperimentalTextApi::class)
private fun jost(w: Int) = Font(R.font.jost, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Jost = FontFamily(jost(400), jost(500), jost(600), jost(700))

private val BType: Typography = Typography().let { d ->
    fun TextStyle.j(weight: FontWeight, spacing: Double = 0.0) =
        copy(fontFamily = Jost, fontWeight = weight, letterSpacing = spacing.sp)
    d.copy(
        displayLarge = d.displayLarge.j(FontWeight.SemiBold), displayMedium = d.displayMedium.j(FontWeight.SemiBold),
        displaySmall = d.displaySmall.j(FontWeight.SemiBold),
        headlineLarge = d.headlineLarge.j(FontWeight.SemiBold), headlineMedium = d.headlineMedium.j(FontWeight.SemiBold),
        headlineSmall = d.headlineSmall.j(FontWeight.SemiBold),
        titleLarge = d.titleLarge.j(FontWeight.SemiBold), titleMedium = d.titleMedium.j(FontWeight.SemiBold, 0.1),
        titleSmall = d.titleSmall.j(FontWeight.SemiBold, 0.4),
        // labels: a touch of tracking gives the "set in capitals" feel without forcing uppercase
        labelLarge = d.labelLarge.j(FontWeight.Medium, 0.6), labelMedium = d.labelMedium.j(FontWeight.Medium, 0.6),
        labelSmall = d.labelSmall.j(FontWeight.Medium, 0.6),
        // body stays the system sans: abstracts and chat answers must read easily
    )
}

@Composable
fun PaperDeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme, shapes = Square, typography = BType) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

// ------------------------------------------------------------------ the three forms

enum class FormKind(val meaning: String) { Circle("keep"), Square("new or stop"), Triangle("in motion") }

@Composable
fun formColor(kind: FormKind): Color = when (kind) {
    FormKind.Circle -> MaterialTheme.colorScheme.primary
    FormKind.Square -> MaterialTheme.colorScheme.error
    FormKind.Triangle -> MaterialTheme.colorScheme.tertiary
}

/** One form, filled in its colour with a thin outline in the content colour (keeps yellow legible on paper).
 *  [filled] = false draws the outline only: "not reached yet". */
@Composable
fun Form(kind: FormKind, size: Dp = 12.dp, modifier: Modifier = Modifier, label: String? = null, filled: Boolean = true) {
    val fill = if (filled) formColor(kind) else Color.Transparent
    val line = LocalContentColor.current
    Canvas(modifier.size(size).semantics { contentDescription = label ?: kind.meaning }) {
        val sw = (this.size.minDimension / 10f).coerceAtLeast(1f)
        val st = Stroke(sw)
        when (kind) {
            FormKind.Circle -> {
                drawCircle(fill, radius = this.size.minDimension / 2f - sw / 2f)
                drawCircle(line, radius = this.size.minDimension / 2f - sw / 2f, style = st)
            }
            FormKind.Square -> {
                val o = Offset(sw / 2f, sw / 2f)
                val s = Size(this.size.width - sw, this.size.height - sw)
                drawRect(fill, o, s)
                drawRect(line, o, s, style = st)
            }
            FormKind.Triangle -> {
                val p = Path().apply {
                    moveTo(this@Canvas.size.width / 2f, sw)
                    lineTo(this@Canvas.size.width - sw / 2f, this@Canvas.size.height - sw / 2f)
                    lineTo(sw / 2f, this@Canvas.size.height - sw / 2f)
                    close()
                }
                drawPath(p, fill)
                drawPath(p, line, style = st)
            }
        }
    }
}

/** The mark: circle, square, triangle side by side. */
@Composable
fun FormMark(size: Dp = 12.dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(size / 4), verticalAlignment = Alignment.CenterVertically) {
        Form(FormKind.Circle, size, label = "PaperDesk"); Form(FormKind.Square, size, label = ""); Form(FormKind.Triangle, size, label = "")
    }
}

/** Paper / job status → form. Unknown states are "in motion". */
fun statusForm(status: String): FormKind = when (status) {
    "added", "included", "done", "library", "completed" -> FormKind.Circle
    "new", "failed", "dismissed", "crash", "error" -> FormKind.Square
    else -> FormKind.Triangle   // pending, queued, running, later
}

/** What the user decided → form. */
fun actionForm(action: String): FormKind = when (action) {
    "include", "literature" -> FormKind.Circle
    "dismiss", "restore", "delete" -> FormKind.Square
    else -> FormKind.Triangle   // remarkable, later
}

// ------------------------------------------------------------------ flat components

val BorderW = 1.5.dp

/** Flat card: paper ground, ink border, no shadow. */
@Composable
fun BCard(modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
        elevation = CardDefaults.cardElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        border = BorderStroke(BorderW, MaterialTheme.colorScheme.onSurface), content = content)
}

private val BtnPad = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** Primary action: solid ink, square. An optional leading form says what kind of action it is. */
@Composable
fun BFilledButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, form: FormKind? = null,
                  contentPadding: PaddingValues = BtnPad, content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier, enabled, shape = RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface),
        elevation = null, contentPadding = contentPadding) {
        if (form != null) { Form(form, 12.dp); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

/** Secondary action: ink outline, square. */
@Composable
fun BOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, form: FormKind? = null,
                    contentPadding: PaddingValues = BtnPad, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier, enabled, shape = RectangleShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(BorderW, if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
        contentPadding = contentPadding) {
        if (form != null) { Form(form, 12.dp); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

/** Small bordered label (bibkey, target, source). Not clickable, so not a chip. */
@Composable
fun BTag(text: String, form: FormKind? = null, modifier: Modifier = Modifier) {
    Row(modifier.border(1.dp, MaterialTheme.colorScheme.onSurface).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (form != null) { Form(form, 10.dp); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** An error: red square + the message in ink (colour alone never says "error"). */
@Composable
fun ErrorLine(text: String, modifier: Modifier = Modifier.padding(16.dp)) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Form(FormKind.Square, 12.dp, Modifier.padding(top = 4.dp), label = "error")
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 2dp ink rule: the only divider that separates chrome from content. */
@Composable
fun InkRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface))
}
