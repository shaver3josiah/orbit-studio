package com.orbitstudio.capture.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.orbitstudio.capture.R

// Home Sketch design system tokens (tokens/colors.css) — drafting-canvas look,
// independent of OrbitColors (the app's global theme).
@Immutable
data class SketchPalette(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val sheet: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val lineSoft: Color,
    val grid: Color,
    val accent: Color,
    val accent2: Color,
    val accentDeep: Color,
    val accentTint: Color,
    val accentInk: Color,
    val danger: Color,
    val marker: Color,
    val photoGreen: Color,
    val star: Color,
    val chip: Color,
)

object SketchColors {
    val Light = SketchPalette(
        bg = Color(0xFFE7EAEF),
        panel = Color(0xFFFFFFFF),
        panel2 = Color(0xFFF5F7FA),
        sheet = Color(0xFFFBFAF5),
        ink = Color(0xFF13181F),
        muted = Color(0xFF606A76),
        line = Color(0xFFDCE1E8),
        lineSoft = Color(0xFFE9EDF2),
        grid = Color(0xFFE6E8EB),
        accent = Color(0xFF1763C2),
        accent2 = Color(0xFF2A7EE0),
        accentDeep = Color(0xFF114E9C),
        accentTint = Color(0xFFEAF2FC),
        accentInk = Color(0xFFFFFFFF),
        danger = Color(0xFFC62828),
        marker = Color(0xFFC0392B),
        photoGreen = Color(0xFF2E7D32),
        star = Color(0xFFF0A500),
        chip = Color(0xFFEEF1F6),
    )

    val Dark = SketchPalette(
        bg = Color(0xFF0A0F1A),
        panel = Color(0xFF131826),
        panel2 = Color(0xFF1A2138),
        sheet = Color(0xFF10151B),
        ink = Color(0xFFE8ECF4),
        muted = Color(0xFF94A3B8),
        line = Color(0xFF586A90),
        lineSoft = Color(0xFF222C44),
        grid = Color(0xFF243039),
        accent = Color(0xFF2F80E8),
        accent2 = Color(0xFF4F9BEC),
        accentDeep = Color(0xFF1763C2),
        accentTint = Color(0xFF16243D),
        accentInk = Color(0xFFFFFFFF),
        danger = Color(0xFFFF5A52),
        marker = Color(0xFFFF6B5E),
        photoGreen = Color(0xFF4CAF50),
        star = Color(0xFFF0A500),
        chip = Color(0xFF1A2138),
    )

    fun palette(dark: Boolean): SketchPalette = if (dark) Dark else Light
}

// IBM Plex Sans/Mono (OFL 1.1), res/font. Google/fonts ships IBM Plex Sans as a
// variable font; instantiate the three UI weights via font-variation settings
// (supported on this app's minSdk 26).
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val PlexSans: FontFamily = FontFamily(
    Font(
        R.font.ibm_plex_sans_variable,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.ibm_plex_sans_variable,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.ibm_plex_sans_variable,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

val PlexMono: FontFamily = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)
