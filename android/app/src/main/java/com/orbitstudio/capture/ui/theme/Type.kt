package com.orbitstudio.capture.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// One platform-sans family; counts/percentages/timers use monospace numerals.
val OrbitTypography = Typography()

val MonospaceNumerals = SpanStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontFeatureSettings = "tnum",
)
