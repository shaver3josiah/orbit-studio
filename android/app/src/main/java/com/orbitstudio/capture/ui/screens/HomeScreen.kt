package com.orbitstudio.capture.ui.screens

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orbitstudio.capture.data.FloorPlan
import com.orbitstudio.capture.data.Plans
import com.orbitstudio.capture.data.Scan
import com.orbitstudio.capture.data.ScanStatus
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// HomeScreen owns the scan list + drawings list. "New scan" creates a fresh
// floor-plan drawing and opens the editor directly — the editor now owns
// per-room scanning, so there's no precapture checklist here anymore.
// Ported from design/android-app-preview.html (#app-home).
// Deliberate deviations from the preview, noted for follow-up agents:
//  - No settings icon in the top bar: no settings screen exists yet (YAGNI).
//  - Monogram tile is a flat accent-soft fill, not the preview's decorative
//    gradient poster — DESIGN.md bans gradients-as-decoration.
//  - No registration/health badge on rows: ScanStatus/Scan carry no
//    registration-percentage field in the frozen contract, so there's nothing
//    real to show (the preview's number is demo data).
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(nav: NavController) {
    // ponytail: re-reading the repo on every composition of this screen (it's
    // recomposed fresh each time nav returns here) is simpler than wiring a
    // ViewModel/Flow for a local single-device file store.
    var scans by remember { mutableStateOf(Scans.repo.listScans()) }
    // Multi-select delete: selectionMode gates the checkbox UI on scan rows and
    // the top bar swap below; selectedScanIds is the source of truth for which
    // rows are checked. A Set would also work — mutableStateListOf reads fine
    // for the handful of rows this list ever holds.
    var selectionMode by remember { mutableStateOf(false) }
    val selectedScanIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var plans by remember { mutableStateOf<List<FloorPlan>>(emptyList()) }
    // Drawing management: rename via Edit; delete via the same long-press multi-select
    // flow scans use, so both lists behave identically.
    var editPlan by remember { mutableStateOf<FloorPlan?>(null) }
    var planSelectionMode by remember { mutableStateOf(false) }
    val selectedPlanIds = remember { mutableStateListOf<String>() }
    var showPlanDeleteConfirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val crashFile = remember { File(context.filesDir, "last-crash.txt") }
    var crashReport by remember { mutableStateOf<String?>(null) }

    // Read off the main thread — this is a file-existence + read check on every
    // return to Home, not something composition should block on.
    LaunchedEffect(Unit) {
        crashReport = withContext(Dispatchers.IO) {
            if (crashFile.exists()) crashFile.readText() else null
        }
    }

    // Off the main thread like the crash-file read above. If listPlans throws
    // (corrupt plans.json etc.) show nothing rather than crash Home.
    LaunchedEffect(Unit) {
        plans = withContext(Dispatchers.IO) {
            try {
                Plans.repo(context).listPlans()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas).safeDrawingPadding()) {
        if (selectionMode) {
            SelectionTopBar(
                selectedCount = selectedScanIds.size,
                noun = "scan",
                onDelete = { showDeleteConfirm = true },
                onCancel = {
                    selectionMode = false
                    selectedScanIds.clear()
                },
            )
        } else if (planSelectionMode) {
            SelectionTopBar(
                selectedCount = selectedPlanIds.size,
                noun = "drawing",
                onDelete = { showPlanDeleteConfirm = true },
                onCancel = {
                    planSelectionMode = false
                    selectedPlanIds.clear()
                },
            )
        } else {
            Text(
                "Orbit Capture",
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
        }

        if (crashReport != null) {
            CrashReportBanner(
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, crashReport)
                    }
                    context.startActivity(Intent.createChooser(send, "Share crash report"))
                },
                onDismiss = {
                    crashFile.delete()
                    crashReport = null
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            BridgeLayoutRow(onClick = { nav.navigate("bridge") { launchSingleTop = true } })
            Spacer(Modifier.height(8.dp))
            KuulaUploadRow(onClick = { nav.navigate("kuula") { launchSingleTop = true } })
        }

        if (plans.isNotEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "DRAWINGS",
                    color = OrbitColors.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                plans.forEach { plan ->
                    PlanRow(
                        plan = plan,
                        selectionMode = planSelectionMode,
                        selected = selectedPlanIds.contains(plan.id),
                        onClick = { nav.navigate("plan/" + plan.id) },
                        onEdit = { editPlan = plan },
                        onLongPress = {
                            selectionMode = false // one selection mode at a time
                            selectedScanIds.clear()
                            planSelectionMode = true
                            if (!selectedPlanIds.contains(plan.id)) selectedPlanIds.add(plan.id)
                        },
                        onToggleSelect = {
                            if (selectedPlanIds.contains(plan.id)) selectedPlanIds.remove(plan.id)
                            else selectedPlanIds.add(plan.id)
                        },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (scans.isEmpty()) {
                EmptyState(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            "SCANS",
                            color = OrbitColors.textSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    items(scans, key = { it.id }) { scan ->
                        ScanRow(
                            scan = scan,
                            selectionMode = selectionMode,
                            selected = selectedScanIds.contains(scan.id),
                            onClick = { nav.navigate(routeFor(scan)) },
                            onLongPress = {
                                if (!selectionMode) selectionMode = true
                                if (!selectedScanIds.contains(scan.id)) selectedScanIds.add(scan.id)
                            },
                            onToggleSelect = {
                                if (selectedScanIds.contains(scan.id)) {
                                    selectedScanIds.remove(scan.id)
                                } else {
                                    selectedScanIds.add(scan.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        // Selection mode hides the primary CTA rather than leaving it active
        // alongside checkboxes — one clear mode at a time avoids "New scan"
        // reading as an action on the selected rows.
        if (!selectionMode && !planSelectionMode) {
            Box(modifier = Modifier.padding(20.dp)) {
                NewScanButton(
                    onClick = {
                        val plan = Plans.repo(context).createPlan(defaultBuildingName())
                        nav.navigate("plan/" + plan.id)
                    },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        DeleteScansDialog(
            count = selectedScanIds.size,
            onConfirm = {
                selectedScanIds.forEach { id -> Scans.repo.deleteScan(id) }
                scans = Scans.repo.listScans()
                selectedScanIds.clear()
                selectionMode = false
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    editPlan?.let { plan ->
        EditPlanDialog(
            plan = plan,
            onRename = { name ->
                runCatching {
                    Plans.repo(context).updatePlan(plan.copy(name = name.ifBlank { plan.name }))
                }
                plans = runCatching { Plans.repo(context).listPlans() }.getOrDefault(plans)
                editPlan = null
            },
            onDelete = {
                runCatching { Plans.repo(context).deletePlan(plan.id) }
                plans = runCatching { Plans.repo(context).listPlans() }.getOrDefault(plans)
                editPlan = null
            },
            onDismiss = { editPlan = null },
        )
    }

    if (showPlanDeleteConfirm) {
        val count = selectedPlanIds.size
        AlertDialog(
            onDismissRequest = { showPlanDeleteConfirm = false },
            containerColor = OrbitColors.elevated,
            title = { Text("Delete drawing${if (count == 1) "" else "s"}?", color = OrbitColors.textPrimary) },
            text = {
                Text(
                    "Delete $count drawing${if (count == 1) "" else "s"}? Scans you already captured are kept.",
                    color = OrbitColors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedPlanIds.forEach { id -> runCatching { Plans.repo(context).deletePlan(id) } }
                    plans = runCatching { Plans.repo(context).listPlans() }.getOrDefault(plans)
                    selectedPlanIds.clear()
                    planSelectionMode = false
                    showPlanDeleteConfirm = false
                }) { Text("Delete", color = OrbitColors.danger, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showPlanDeleteConfirm = false }) { Text("Cancel", color = OrbitColors.accent) }
            },
        )
    }
}

@Composable
private fun EditPlanDialog(
    plan: FloorPlan,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(plan.id) { mutableStateOf(plan.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitColors.elevated,
        title = { Text("Edit drawing", color = OrbitColors.textPrimary) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name", color = OrbitColors.textSecondary) },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = OrbitColors.accent,
                    unfocusedBorderColor = OrbitColors.hairline20,
                    cursorColor = OrbitColors.accent,
                    focusedTextColor = OrbitColors.textPrimary,
                    unfocusedTextColor = OrbitColors.textPrimary,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) {
                Text("Save", color = OrbitColors.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = OrbitColors.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = OrbitColors.textSecondary) }
            }
        },
    )
}

private fun defaultBuildingName(): String =
    "Scan " + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())

private fun routeFor(scan: Scan): String = when (scan.status) {
    ScanStatus.IN_PROGRESS -> "capture/${scan.id}"
    ScanStatus.REVIEWED -> "bundle/${scan.id}"
    ScanStatus.BUNDLED, ScanStatus.DONE -> "done/${scan.id}"
}

private fun monogram(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase(Locale.US)
        else -> (words[0].take(1) + words[1].take(1)).uppercase(Locale.US)
    }
}

private fun formatCaptureTime(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun statusLabel(status: ScanStatus): String = when (status) {
    ScanStatus.IN_PROGRESS -> "In progress"
    ScanStatus.REVIEWED -> "Reviewed"
    ScanStatus.BUNDLED -> "Bundled"
    ScanStatus.DONE -> "Done"
}

private fun statusColor(status: ScanStatus): Color = when (status) {
    ScanStatus.IN_PROGRESS -> OrbitColors.warning
    ScanStatus.REVIEWED -> OrbitColors.accent
    ScanStatus.BUNDLED -> OrbitColors.accent
    ScanStatus.DONE -> OrbitColors.success
}

private fun statusSoftColor(status: ScanStatus): Color = when (status) {
    ScanStatus.IN_PROGRESS -> OrbitColors.warningSoft
    ScanStatus.REVIEWED -> OrbitColors.accentSoft
    ScanStatus.BUNDLED -> OrbitColors.accentSoft
    ScanStatus.DONE -> OrbitColors.successSoft
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No scans yet",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "A scan is a walk around a room — perimeter first, then the interior — " +
                "that turns into a 3D capture. Tap New scan below to start one.",
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanRow(
    scan: Scan,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OrbitColors.elevated)
            .border(
                1.dp,
                if (selected) OrbitColors.accent else OrbitColors.hairline12,
                RoundedCornerShape(16.dp),
            )
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelect else onClick,
                onLongClick = onLongPress,
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = OrbitColors.accent,
                    uncheckedColor = OrbitColors.textSecondary,
                    checkmarkColor = OrbitColors.textPrimary,
                ),
            )
            Spacer(Modifier.width(6.dp))
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(OrbitColors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                monogram(scan.name),
                color = OrbitColors.accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scan.name,
                    color = OrbitColors.textPrimary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                StatusChip(scan.status)
            }
            Spacer(Modifier.height(2.dp))
            val shotCount = scan.shots.size
            Text(
                "$shotCount photo${if (shotCount == 1) "" else "s"} · " +
                    "${formatCaptureTime(scan.captureSeconds)} capture time",
                color = OrbitColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun StatusChip(status: ScanStatus) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(statusSoftColor(status))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            statusLabel(status),
            color = statusColor(status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NewScanButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PrimaryButton(
        text = "New scan",
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
    )
}

// Reopens an existing floor-plan drawing. Same quiet secondary styling the
// old static "Plan a building" row used — New scan stays the single primary
// CTA on this screen.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanRow(
    plan: FloorPlan,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OrbitColors.elevated)
            .border(
                1.dp,
                if (selected) OrbitColors.accent else OrbitColors.hairline12,
                RoundedCornerShape(14.dp),
            )
            .combinedClickable(
                onClick = if (selectionMode) onToggleSelect else onClick,
                onLongClick = onLongPress,
            )
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(
                    checkedColor = OrbitColors.accent,
                    uncheckedColor = OrbitColors.textSecondary,
                    checkmarkColor = OrbitColors.textPrimary,
                ),
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                plan.name,
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            val roomCount = plan.rooms.size
            Text(
                "$roomCount room${if (roomCount == 1) "" else "s"}",
                color = OrbitColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!selectionMode) {
            TextButton(onClick = onEdit) {
                Text("Edit", color = OrbitColors.accent, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Quiet secondary entry to the bundled Bridge Sketch tool — same low-key
// row styling as PlanRow above, not the accent primary CTA.
@Composable
private fun BridgeLayoutRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OrbitColors.elevated)
            .border(1.dp, OrbitColors.hairline12, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            "Bridge layout",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Lay out a bridge with the drafting tool.",
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// Quiet secondary entry to the in-app Kuula upload screen — same low-key row
// styling as BridgeLayoutRow above.
@Composable
private fun KuulaUploadRow(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OrbitColors.elevated)
            .border(1.dp, OrbitColors.hairline12, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            "Upload to Kuula",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "Post 360 photos to your Kuula account.",
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun CrashReportBanner(onShare: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OrbitColors.warningSoft)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Last session crashed.",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShare) {
            Text("Share report", color = OrbitColors.accent, style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = OrbitColors.textSecondary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DeleteScansDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitColors.elevated,
        title = { Text("Delete scan${if (count == 1) "" else "s"}?", color = OrbitColors.textPrimary) },
        text = {
            Text(
                "Delete $count scan${if (count == 1) "" else "s"}? This can't be undone.",
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

// Swaps in for the normal "Orbit Capture" header while selection mode is
// active. Delete disables at zero selected rather than hiding, so the count
// and the row checkboxes stay the only things moving as the user taps.
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    noun: String,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text("Cancel", color = OrbitColors.accent, style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "$selectedCount $noun${if (selectedCount == 1) "" else "s"} selected",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onDelete, enabled = selectedCount > 0) {
            Text(
                "Delete",
                color = if (selectedCount > 0) OrbitColors.danger else OrbitColors.textTertiary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

// ponytail: precapture checklist (exposure lock / lighting / lens) removed —
// the floor-plan editor now owns per-room scanning, so there's no longer a
// single "start a scan" moment on Home to gate with a checklist. If per-room
// scanning inside the editor needs the same nudge, reintroduce it there.
