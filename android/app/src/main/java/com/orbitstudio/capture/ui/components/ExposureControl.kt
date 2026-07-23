package com.orbitstudio.capture.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlin.math.roundToInt

@Composable
fun ExposureChip(
    evLabel: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = tween(150),
        label = "exposureChipScale",
    )
    Row(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) OrbitColors.accentSoft else OrbitColors.hairline12)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = evLabel,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) OrbitColors.accent else OrbitColors.textSecondary,
        )
    }
}

@Composable
private fun EvStepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) OrbitColors.hairline12 else OrbitColors.hairline12.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (enabled) OrbitColors.textPrimary else OrbitColors.textTertiary,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
fun ExposureSlider(
    index: Int,
    range: IntRange,
    step: Float,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fixed = range.isEmpty() || range.first == range.last
    var dragValue by remember { mutableFloatStateOf(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    // Resync from the committed index only when idle, so an async apply landing
    // mid-drag cannot yank the thumb out from under the user's finger.
    LaunchedEffect(index) { if (!dragging) dragValue = index.toFloat() }

    fun ev(v: Int): String {
        val e = v * step
        return if (e == 0f) "0.0" else String.format(java.util.Locale.US, "%+.1f", e)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(OrbitColors.elevated)
            .border(1.dp, OrbitColors.hairline20, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                text = if (fixed) "EV fixed" else "EV ${ev(dragValue.roundToInt())}",
                fontFamily = FontFamily.Monospace,
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Big steppers: one-thumb, glanceable EV nudges mid-scan. The slider stays
            // for coarse jumps; the steppers are the on-the-fly path.
            EvStepButton(
                label = "-",
                enabled = !fixed && dragValue.roundToInt() > range.first,
                onClick = {
                    val next = (dragValue.roundToInt() - 1).coerceAtLeast(range.first)
                    dragValue = next.toFloat()
                    onIndexChange(next)
                },
            )
            Slider(
                value = dragValue,
                onValueChange = { dragging = true; dragValue = it },
                onValueChangeFinished = { dragging = false; onIndexChange(dragValue.roundToInt()) },
                valueRange = if (fixed) 0f..0f else range.first.toFloat()..range.last.toFloat(),
                steps = if (fixed) 0 else (range.last - range.first - 1).coerceAtLeast(0),
                enabled = !fixed,
                colors = SliderDefaults.colors(
                    thumbColor = OrbitColors.accent,
                    activeTrackColor = OrbitColors.accent,
                    inactiveTrackColor = OrbitColors.hairline20,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            EvStepButton(
                label = "+",
                enabled = !fixed && dragValue.roundToInt() < range.last,
                onClick = {
                    val next = (dragValue.roundToInt() + 1).coerceAtMost(range.last)
                    dragValue = next.toFloat()
                    onIndexChange(next)
                },
            )
        }
        if (!fixed) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(ev(range.first), fontFamily = FontFamily.Monospace, color = OrbitColors.textTertiary, style = MaterialTheme.typography.labelSmall)
                Text(ev(0), fontFamily = FontFamily.Monospace, color = OrbitColors.textTertiary, style = MaterialTheme.typography.labelSmall)
                Text(ev(range.last), fontFamily = FontFamily.Monospace, color = OrbitColors.textTertiary, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (dragValue.roundToInt() != 0) {
            TextButton(onClick = { dragValue = 0f; onIndexChange(0) }) {
                Text("Reset", color = OrbitColors.accent, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
