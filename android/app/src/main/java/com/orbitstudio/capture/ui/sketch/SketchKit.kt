package com.orbitstudio.capture.ui.sketch

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbitstudio.capture.ui.theme.PlexMono
import com.orbitstudio.capture.ui.theme.PlexSans
import com.orbitstudio.capture.ui.theme.SketchPalette

// Tactile Home Sketch component kit (Bridge Sketch's .tool/.iconbtn/.chip/.float recipes,
// ported to Compose). No native `inset` box-shadow exists here, so the "glow" on active/selected
// states is faked with a second oversized `shadow` tinted to accent, and the inset top highlight
// is a 1dp white-alpha line drawn at the top edge of the shape.

private val PressTween = tween<Float>(150)
private val PressTweenDp = tween<Dp>(150)

/** Shared press physics: sinks 1dp and drops its resting shadow when pressed. 150ms, no bounce. */
@Composable
private fun rememberPress(interactionSource: MutableInteractionSource, restElevation: Dp): Pair<Dp, Dp> {
    val pressed by interactionSource.collectIsPressedAsState()
    val elevation by animateDpAsState(if (pressed) 0.dp else restElevation, PressTweenDp, label = "elevation")
    val offsetY by animateDpAsState(if (pressed) 1.dp else 0.dp, PressTweenDp, label = "offsetY")
    return elevation to offsetY
}

@Composable
private fun faceModifier(
    shape: RoundedCornerShape,
    active: Boolean,
    faceTop: Color,
    faceBottom: Color,
    activeTop: Color,
    activeBottom: Color,
    lineColor: Color,
    activeLineColor: Color,
    elevation: Dp,
    offsetY: Dp,
    accentGlow: Color,
): Modifier {
    var m: Modifier = Modifier.offset(y = offsetY)
    if (active) {
        // Oversized tinted shadow behind = the "glow" drop shadow under a selected tool.
        m = m.shadow(10.dp, shape, ambientColor = accentGlow, spotColor = accentGlow)
    }
    m = m.shadow(elevation, shape)
        .background(Brush.verticalGradient(if (active) listOf(activeTop, activeBottom) else listOf(faceTop, faceBottom)), shape)
        .border(1.dp, if (active) activeLineColor else lineColor, shape)
    return m
}

/** 54x46dp tool button — main drafting toolbar. Selected = accent gradient + glow. */
@Composable
fun SketchToolButton(
    pal: SketchPalette,
    glyph: String,
    caption: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val (elevation, offsetY) = rememberPress(interactionSource, 2.dp)
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = modifier
            .size(width = 54.dp, height = 46.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .then(
                faceModifier(
                    shape = shape,
                    active = selected,
                    faceTop = pal.panel,
                    faceBottom = pal.panel2,
                    activeTop = pal.accent2,
                    activeBottom = pal.accent,
                    lineColor = pal.line,
                    activeLineColor = pal.accentDeep,
                    elevation = elevation,
                    offsetY = offsetY,
                    accentGlow = pal.accent.copy(alpha = 0.55f),
                ),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(glyph, fontSize = 18.sp, color = if (selected) Color.White else pal.ink, fontFamily = PlexSans)
        Text(
            caption,
            fontSize = 9.5.sp,
            color = if (selected) Color.White.copy(alpha = 0.9f) else pal.muted,
            fontFamily = PlexSans,
        )
    }
}

/** 40dp square topbar chrome icon button, 11dp radius. */
@Composable
fun SketchIconButton(
    pal: SketchPalette,
    glyph: String,
    active: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val (elevation, offsetY) = rememberPress(interactionSource, 2.dp)
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier = modifier
            .size(40.dp)
            .then(
                faceModifier(
                    shape = shape,
                    active = active,
                    faceTop = pal.panel,
                    faceBottom = pal.panel2,
                    activeTop = pal.accent2,
                    activeBottom = pal.accent,
                    lineColor = pal.line,
                    activeLineColor = pal.accentDeep,
                    elevation = elevation,
                    offsetY = offsetY,
                    accentGlow = pal.accent.copy(alpha = 0.55f),
                ),
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 16.sp, color = if (active) Color.White else pal.ink, fontFamily = PlexSans)
    }
}

/** 36dp pill, 18dp radius. Active = accent gradient + 2dp ring. */
@Composable
fun SketchChip(
    pal: SketchPalette,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val (elevation, offsetY) = rememberPress(interactionSource, 1.dp)
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .offset(y = offsetY)
            .then(
                if (active) Modifier.shadow(8.dp, shape, ambientColor = pal.accent.copy(alpha = 0.55f), spotColor = pal.accent.copy(alpha = 0.55f))
                else Modifier.shadow(elevation, shape)
            )
            .background(Brush.verticalGradient(if (active) listOf(pal.accent2, pal.accent) else listOf(pal.panel, pal.panel2)), shape)
            .border(if (active) 2.dp else 1.dp, if (active) pal.accentDeep else pal.line, shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = if (active) Color.White else pal.ink, fontFamily = PlexSans, fontWeight = FontWeight.Medium)
    }
}

/** Accent solid pill with white text — a transient placement hint over the canvas. */
@Composable
fun PlaceHintPill(pal: SketchPalette, text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(pal.accent, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, color = Color.White, fontFamily = PlexSans, fontWeight = FontWeight.Medium)
    }
}

/** 40dp accentTint chip for the current drawing scale. Tap opens editor, long-press = shortcut. */
@Composable
fun ScaleChipButton(
    pal: SketchPalette,
    label: String,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .background(pal.accentTint, RoundedCornerShape(11.dp))
            .border(1.dp, pal.accent.copy(alpha = 0.4f), RoundedCornerShape(11.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, color = pal.accent, fontFamily = PlexMono, fontWeight = FontWeight.SemiBold)
    }
}

/** Live length readout capsule (e.g. while drag-drawing a wall or calibration line). */
@Composable
fun DimensionCapsule(pal: SketchPalette, value: String, unit: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(pal.accentTint, RoundedCornerShape(9.dp))
            .border(1.dp, pal.accent, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(value, fontSize = 18.sp, color = pal.accentDeep, fontFamily = PlexMono, fontWeight = FontWeight.SemiBold)
        Text(" $unit", fontSize = 11.sp, color = pal.accentDeep, fontFamily = PlexMono)
    }
}

/** Floating undo/redo pair — two 40dp icon buttons, disabled state dims. */
@Composable
fun UndoRedoFloat(
    pal: SketchPalette,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.alpha(if (canUndo) 1f else 0.4f)) {
            SketchIconButton(pal, "↶", onClick = { if (canUndo) onUndo() })
        }
        Box(Modifier.alpha(if (canRedo) 1f else 0.4f)) {
            SketchIconButton(pal, "↷", onClick = { if (canRedo) onRedo() })
        }
    }
}

/** ~300dp side drawer panel: gradient head with title + close, scrollable body. Caller positions
 * it (offset/slide-in) and supplies the scrim; this composable is just the panel surface. */
@Composable
fun SketchDrawerPanel(
    pal: SketchPalette,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .widthIn(max = 300.dp)
            .shadow(14.dp, RectangleShape)
            .background(pal.panel),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Brush.verticalGradient(listOf(pal.panel, pal.panel2)))
                .border(BorderStroke(1.dp, pal.line))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, fontFamily = PlexSans)
            SketchIconButton(pal, "✕", onClick = onClose, modifier = Modifier.size(32.dp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            content = content,
        )
    }
}

/** Full-width accent primary action button, 48dp, press scale .98. */
@Composable
fun SketchPrimaryButton(
    pal: SketchPalette,
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, PressTween, label = "primaryScale")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(scale)
            .alpha(if (enabled) 1f else 0.4f)
            .background(Brush.verticalGradient(listOf(pal.accent2, pal.accent)), RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, fontFamily = PlexSans)
    }
}
