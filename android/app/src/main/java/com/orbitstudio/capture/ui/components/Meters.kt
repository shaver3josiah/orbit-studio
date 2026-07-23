package com.orbitstudio.capture.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.data.ScanStage
import com.orbitstudio.capture.ui.theme.OrbitColors

// Overlap thresholds are identical everywhere: red <60, amber 60-75, green >75.
fun overlapColor(pct: Int) = when {
    pct < 60 -> OrbitColors.danger
    pct <= 75 -> OrbitColors.warning
    else -> OrbitColors.success
}

@Composable
fun OverlapMeter(pct: Int, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(
        targetValue = (pct.coerceIn(0, 100)) / 100f,
        animationSpec = tween(200),
        label = "overlap",
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(OrbitColors.hairline12),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(50))
                    .background(overlapColor(pct)),
            )
        }
        Text(
            text = "$pct%",
            modifier = Modifier.padding(start = 8.dp),
            color = OrbitColors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

// Same Perimeter / Interior / Loop closed trio on Capture and Review.
// When onAdvance is non-null the row is tappable and advances one stage per tap.
@Composable
fun StageBadges(current: ScanStage, onAdvance: (() -> Unit)?, modifier: Modifier = Modifier) {
    val rowModifier = if (onAdvance != null) {
        modifier.clickable(onClick = onAdvance)
    } else {
        modifier
    }
    val stages = listOf(
        ScanStage.PERIMETER to "Perimeter",
        ScanStage.INTERIOR to "Interior",
        ScanStage.LOOP_CLOSED to "Loop closed",
    )
    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stages.forEach { (stage, label) ->
            val lit = stage.ordinal <= current.ordinal
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (lit) OrbitColors.successSoft else OrbitColors.hairline12)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = label,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = if (lit) OrbitColors.success else OrbitColors.textTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
