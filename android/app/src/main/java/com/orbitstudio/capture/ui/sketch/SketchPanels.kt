package com.orbitstudio.capture.ui.sketch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orbitstudio.capture.data.FeatureType
import com.orbitstudio.capture.ui.theme.PlexMono
import com.orbitstudio.capture.ui.theme.PlexSans
import com.orbitstudio.capture.ui.theme.SketchPalette
import java.util.Locale

// ---- Stamp catalog -----------------------------------------------------

// wM/hM: real-world size in metres for TAP-PLACED fixed-size stamps (furniture); 0 means the
// object is drag-drawn or edge-snapped as before (walls, doors, windows, generic obstacles).
data class Stamp(
    val type: FeatureType,
    val variant: String,
    val label: String,
    val glyph: String,
    val wM: Float = 0f,
    val hM: Float = 0f,
)

val STAMP_GROUPS: List<Pair<String, List<Stamp>>> = listOf(
    "Structure" to listOf(
        Stamp(FeatureType.WALL, "wall", "Wall", "▬"),
        Stamp(FeatureType.OBSTACLE, "column", "Column", "▦"),
        Stamp(FeatureType.OBSTACLE, "stairs", "Stairs", "≡"),
        Stamp(FeatureType.OBSTACLE, "void", "Void", "▢"),
    ),
    "Doors" to listOf(
        Stamp(FeatureType.DOOR, "single", "Single", "⊢"),
        Stamp(FeatureType.DOOR, "double", "Double", "⇔"),
        Stamp(FeatureType.DOOR, "sliding", "Sliding", "⇌"),
        Stamp(FeatureType.DOOR, "opening", "Opening", "▯"),
    ),
    "Windows" to listOf(
        Stamp(FeatureType.WINDOW, "standard", "Standard", "▭"),
        Stamp(FeatureType.WINDOW, "small", "Small", "▯"),
        Stamp(FeatureType.WINDOW, "bay", "Bay", "⌂"),
    ),
    "Furniture" to listOf(
        Stamp(FeatureType.OBSTACLE, "bed-queen", "Queen bed", "▭", wM = 1.53f, hM = 2.03f),
        Stamp(FeatureType.OBSTACLE, "bed-double", "Double bed", "▭", 1.35f, 1.90f),
        Stamp(FeatureType.OBSTACLE, "chair", "Chair", "◫", 0.5f, 0.5f),
        Stamp(FeatureType.OBSTACLE, "sofa", "Sofa", "▬", 2.0f, 0.9f),
        Stamp(FeatureType.OBSTACLE, "table", "Table", "▢", 1.2f, 0.8f),
        Stamp(FeatureType.OBSTACLE, "desk", "Desk", "▭", 1.4f, 0.7f),
    ),
)

@Composable
fun ColumnScope.StampPaletteBody(
    pal: SketchPalette,
    selectedVariant: String,
    onPick: (Stamp) -> Unit,
) {
    STAMP_GROUPS.forEach { (groupLabel, stamps) ->
        Text(
            groupLabel.uppercase(Locale.US),
            fontFamily = PlexMono,
            fontSize = 11.sp,
            color = pal.muted,
            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        stamps.chunked(4).forEach { rowStamps ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowStamps.forEach { stamp ->
                    PalItem(pal, stamp, selected = stamp.variant == selectedVariant, onClick = { onPick(stamp) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PalItem(pal: SketchPalette, stamp: Stamp, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .size(64.dp, 58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) pal.accentTint else pal.panel2)
            .then(if (selected) Modifier.border(2.dp, pal.accent, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stamp.glyph, fontSize = 20.sp, color = pal.ink)
        Spacer(Modifier.height(3.dp))
        Text(stamp.label, fontFamily = PlexSans, fontSize = 8.5.sp, color = pal.muted, maxLines = 1)
    }
}

// ---- Shared danger button ------------------------------------------------

@Composable
private fun DangerButton(pal: SketchPalette, text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(pal.danger)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// ---- Properties bodies ---------------------------------------------------

@Composable
fun ColumnScope.RoomPropertiesBody(
    pal: SketchPalette,
    name: String,
    widthM: Float,
    heightM: Float,
    areaM2: Float,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Text("NAME", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onRename,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = pal.accent,
            unfocusedBorderColor = pal.line,
            cursorColor = pal.accent,
            focusedTextColor = pal.ink,
            unfocusedTextColor = pal.ink,
        ),
    )
    Spacer(Modifier.height(16.dp))
    Text("DIMENSIONS", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DimensionCapsule(pal, String.format(Locale.US, "%.2f", widthM), "m")
        DimensionCapsule(pal, String.format(Locale.US, "%.2f", heightM), "m")
        DimensionCapsule(pal, String.format(Locale.US, "%.1f", areaM2), "m²")
    }
    Spacer(Modifier.height(20.dp))
    DangerButton(pal, "Delete", onDelete)
}

@Composable
fun ColumnScope.FeaturePropertiesBody(
    pal: SketchPalette,
    type: FeatureType,
    variant: String,
    variantsForType: List<Stamp>,
    onVariant: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Text("TYPE", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    Text(
        type.name.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) },
        fontFamily = PlexSans,
        fontSize = 15.sp,
        color = pal.ink,
    )
    Spacer(Modifier.height(16.dp))
    Text("VARIANT", fontFamily = PlexMono, fontSize = 11.sp, color = pal.muted, modifier = Modifier.padding(bottom = 6.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        variantsForType.forEach { stamp ->
            SketchChip(pal, stamp.label, active = stamp.variant == variant, onClick = { onVariant(stamp.variant) })
        }
    }
    Spacer(Modifier.height(20.dp))
    DangerButton(pal, "Delete", onDelete)
}

// ---- Scale by reference dialog ------------------------------------------

@Composable
fun ScaleByReferenceDialog(
    pal: SketchPalette,
    currentMPerCell: Float,
    referenceRoomWidthCells: Int,
    onConfirm: (metersPerCell: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(String.format(Locale.US, "%.2f", currentMPerCell * referenceRoomWidthCells)) }
    val entered = input.toFloatOrNull()
    val resultMPerCell = if (entered != null && entered > 0f && referenceRoomWidthCells > 0) {
        entered / referenceRoomWidthCells
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How wide is this room, in meters?", fontFamily = PlexSans, color = pal.ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = pal.accent,
                        unfocusedBorderColor = pal.line,
                        cursorColor = pal.accent,
                        focusedTextColor = pal.ink,
                        unfocusedTextColor = pal.ink,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (resultMPerCell != null) {
                        "1 cell = " + String.format(Locale.US, "%.2f", resultMPerCell) + " m"
                    } else {
                        "Enter a valid width to see the cell scale."
                    },
                    fontFamily = PlexMono,
                    fontSize = 13.sp,
                    color = if (resultMPerCell != null) pal.ink else pal.muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { resultMPerCell?.let(onConfirm) }, enabled = resultMPerCell != null) {
                Text("Set Scale", color = if (resultMPerCell != null) pal.accent else pal.muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = pal.muted)
            }
        },
    )
}
