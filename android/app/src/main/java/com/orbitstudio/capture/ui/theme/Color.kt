package com.orbitstudio.capture.ui.theme

import androidx.compose.ui.graphics.Color

// DESIGN.md tokens — dark-first, one accent, no pure black, no purple/neon.
object OrbitColors {
    val canvas = Color(0xFF0B0B0E)
    val elevated = Color(0xFF1C1C22)

    val hairline12 = Color(0x1FFFFFFF) // white 12%
    val hairline20 = Color(0x33FFFFFF) // white 20%

    val textPrimary = Color(0xFFF2F2F7)
    val textSecondary = Color(0x99FFFFFF) // white 60%
    val textTertiary = Color(0x61FFFFFF)  // white 38%

    val accent = Color(0xFF0A84FF)

    val success = Color(0xFF30D158)
    val warning = Color(0xFFFF9F0A)
    val danger = Color(0xFFFF453A)

    val successSoft = Color(0x2430D158) // ~14% alpha
    val warningSoft = Color(0x24FF9F0A)
    val dangerSoft = Color(0x24FF453A)
    val accentSoft = Color(0x330A84FF)
}
