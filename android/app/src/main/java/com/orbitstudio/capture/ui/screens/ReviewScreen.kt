package com.orbitstudio.capture.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orbitstudio.capture.data.Scan
import com.orbitstudio.capture.data.ScanStage
import com.orbitstudio.capture.data.ScanStatus
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.data.ShotMeta
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.components.StageBadges
import com.orbitstudio.capture.ui.components.ThumbImage
import com.orbitstudio.capture.ui.components.overlapColor
import com.orbitstudio.capture.ui.theme.OrbitColors
import java.io.File

@Composable
fun ReviewScreen(nav: NavController, scanId: String) {
    var scan by remember(scanId) { mutableStateOf(Scans.repo.getScan(scanId)) }
    fun refresh() { scan = Scans.repo.getScan(scanId) }
    var showRescanConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas).safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { nav.popBackStack() }) {
                Text("Back", color = OrbitColors.accent)
            }
            Text(
                "Review coverage",
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        val currentScan = scan
        when {
            currentScan == null -> CenteredMessage(
                modifier = Modifier.weight(1f),
                title = "Scan not found.",
                body = "It may have been deleted.",
                actionLabel = "Back to home",
                onAction = { nav.navigate("home") },
            )
            currentScan.shots.isEmpty() -> CenteredMessage(
                modifier = Modifier.weight(1f),
                title = "No photos yet.",
                body = "Go back and walk the perimeter first.",
                actionLabel = "Back to capture",
                onAction = { nav.popBackStack() },
            )
            else -> {
                ReviewContent(
                    scan = currentScan,
                    modifier = Modifier.weight(1f),
                    onRemoveShot = { fileName ->
                        Scans.repo.deleteShot(scanId, fileName)
                        refresh()
                    },
                )
                ReviewActions(
                    onKeepShooting = { nav.popBackStack() },
                    onBundle = {
                        Scans.repo.updateScan(currentScan.copy(status = ScanStatus.REVIEWED))
                        nav.navigate("bundle/$scanId")
                    },
                    onRescanRoom = { showRescanConfirm = true },
                    onDeleteScan = { showDeleteConfirm = true },
                )
            }
        }

        if (showRescanConfirm && currentScan != null) {
            RescanRoomDialog(
                shotCount = currentScan.shots.size,
                onConfirm = {
                    showRescanConfirm = false
                    Scans.repo.clearShots(scanId)
                    // Replace this Review (and the capture beneath it) with a fresh capture,
                    // so repeated rescans don't pile up stale screens in the back stack.
                    nav.navigate("capture/$scanId") { popUpTo("capture/$scanId") { inclusive = true } }
                },
                onDismiss = { showRescanConfirm = false },
            )
        }

        if (showDeleteConfirm && currentScan != null) {
            DeleteScanDialog(
                scanName = currentScan.name,
                shotCount = currentScan.shots.size,
                onConfirm = {
                    showDeleteConfirm = false
                    Scans.repo.deleteScan(scanId)
                    // Go to a clean Home, NOT back to the live Capture screen — its background
                    // writes would otherwise resurrect the scan we just deleted.
                    nav.navigate("home") { popUpTo("home") { inclusive = true } }
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }
    }
}

@Composable
private fun ReviewContent(
    scan: Scan,
    onRemoveShot: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shots = scan.shots
    val total = shots.size
    val avgOverlap = shots.sumOf { it.overlapPct } / total
    val weakShots = shots.filter { it.blurScore < 80.0 || it.overlapPct < 60 }
    val weakDecile = (weakShots.size * 10) / total
    val predictedReg = (avgOverlap.coerceIn(0, 100) - weakDecile * 4).coerceIn(20, 98)
    val furthestStage = shots.maxByOrNull { it.stage.ordinal }?.stage ?: ScanStage.PERIMETER
    val photosDir = Scans.repo.photosDir(scan.id)

    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            StageBadges(current = furthestStage, onAdvance = null, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    StatTile("$total", "Total photos", Modifier.weight(1f).padding(end = 4.dp))
                    StatTile("$avgOverlap%", "Avg. overlap", Modifier.weight(1f).padding(start = 4.dp), overlapColor(avgOverlap))
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    StatTile("${weakShots.size}", "Weak spots", Modifier.weight(1f).padding(end = 4.dp))
                    StatTile("$predictedReg%", "Predicted registration — estimate", Modifier.weight(1f).padding(start = 4.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel(if (weakShots.isEmpty()) "Flagged gaps — none" else "Flagged gaps (${weakShots.size})")
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            if (weakShots.isEmpty()) {
                Text(
                    "No weak spots — coverage looks solid.",
                    color = OrbitColors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                Column {
                    weakShots.forEach { shot ->
                        WeakSpotRow(
                            dir = photosDir,
                            shot = shot,
                            onRemove = { onRemoveShot(shot.fileName) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel("Photos ($total)")
        }
        items(shots, key = { it.fileName }) { shot ->
            ShotThumbnail(
                dir = photosDir,
                shot = shot,
                weak = shot in weakShots,
                modifier = Modifier
                    .padding(3.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = OrbitColors.textSecondary,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = OrbitColors.textPrimary) {
    Column(
        modifier = modifier
            .background(OrbitColors.elevated, RoundedCornerShape(16.dp))
            .border(1.dp, OrbitColors.hairline12, RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(label, color = OrbitColors.textSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun WeakSpotRow(dir: File, shot: ShotMeta, onRemove: () -> Unit) {
    val cause = buildString {
        if (shot.blurScore < 80.0) append("Blurry frame")
        if (shot.overlapPct < 60) {
            if (isNotEmpty()) append(" · ")
            append("Overlap dropped to ${shot.overlapPct}%")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OrbitColors.elevated, RoundedCornerShape(14.dp))
            .border(1.dp, OrbitColors.hairline12, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShotThumbnail(
            dir = dir,
            shot = shot,
            weak = true,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            cause,
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        TextButton(onClick = onRemove) {
            Text("Remove", color = OrbitColors.danger)
        }
    }
}

@Composable
private fun ShotThumbnail(
    dir: File,
    shot: ShotMeta,
    weak: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = "Photo ${shot.fileName}" + if (weak) ", weak, needs another pass" else ""
    Box(modifier = modifier.background(OrbitColors.hairline12)) {
        ThumbImage(
            file = File(dir, shot.fileName),
            sizePx = 200,
            contentDescription = description,
            modifier = Modifier.fillMaxSize(),
        )
        if (weak) {
            Box(modifier = Modifier.fillMaxSize().background(OrbitColors.dangerSoft))
        }
    }
}

@Composable
private fun ReviewActions(
    onKeepShooting: () -> Unit,
    onBundle: () -> Unit,
    onRescanRoom: () -> Unit,
    onDeleteScan: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onRescanRoom, modifier = Modifier.weight(1f)) {
                Text("Rescan room", color = OrbitColors.textSecondary)
            }
            TextButton(onClick = onDeleteScan, modifier = Modifier.weight(1f)) {
                Text("Delete scan", color = OrbitColors.danger)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = onKeepShooting, modifier = Modifier.weight(1f)) {
                Text("Keep shooting", color = OrbitColors.textPrimary)
            }
            Spacer(Modifier.width(8.dp))
            PrimaryButton(text = "Looks good — Bundle", onClick = onBundle, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RescanRoomDialog(shotCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitColors.elevated,
        title = { Text("Rescan room?", color = OrbitColors.textPrimary) },
        text = {
            Text(
                "This deletes the $shotCount photo${if (shotCount == 1) "" else "s"} in this room and starts the walk again. " +
                    "The room stays in your plan.",
                color = OrbitColors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Rescan", color = OrbitColors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OrbitColors.textSecondary) }
        },
    )
}

@Composable
private fun DeleteScanDialog(scanName: String, shotCount: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitColors.elevated,
        title = { Text("Delete scan?", color = OrbitColors.textPrimary) },
        text = {
            Text(
                "This deletes \"$scanName\" and its $shotCount photo${if (shotCount == 1) "" else "s"}. " +
                    "This can't be undone.",
                color = OrbitColors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = OrbitColors.danger, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OrbitColors.accent) }
        },
    )
}

@Composable
private fun CenteredMessage(
    modifier: Modifier,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = OrbitColors.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            body,
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        PrimaryButton(text = actionLabel, onClick = onAction)
    }
}
