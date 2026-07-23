package com.orbitstudio.capture.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.orbitstudio.capture.bundle.LaptopUpload
import com.orbitstudio.capture.data.ScanStatus
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Honest handoff explainer — no fake splat-return simulation, that was preview-only.
// Registration copy here is *information about what the notebook will print*, not a
// result this screen ever produces on-device.
@Composable
fun DoneScreen(nav: NavController, scanId: String) {
    var scanName by remember { mutableStateOf(scanId) }
    var shotCount by remember { mutableStateOf(0) }
    var showRescanConfirm by remember { mutableStateOf(false) }

    // Send-over-Wi-Fi state. The laptop address persists across sessions in a
    // flag file — it's one LAN IP, not worth a settings store.
    val context = LocalContext.current
    val addressFile = remember { File(context.filesDir, "laptop-address.txt") }
    var laptopAddress by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sendProgress by remember { mutableIntStateOf(0) }
    var sendNote by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scanId) {
        Scans.repo.getScan(scanId)?.let {
            scanName = it.name
            shotCount = it.shots.size
        }
        laptopAddress = withContext(Dispatchers.IO) {
            if (addressFile.exists()) addressFile.readText().trim() else ""
        }
    }

    fun sendToLaptop() {
        val scan = Scans.repo.getScan(scanId) ?: return
        sending = true
        sendNote = null
        sendProgress = 0
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    addressFile.writeText(laptopAddress.trim())
                    LaptopUpload.send(context, scan, Scans.repo.photosDir(scanId), laptopAddress) {
                        sendProgress = it
                    }
                }
            }
            sending = false
            sendNote = result.fold(
                { true to "Bundle is on your laptop — open localhost:7360 and it's there as a project." },
                { false to (it.message ?: "Couldn't reach the laptop.") },
            )
        }
    }

    fun startAnotherRoom() {
        Scans.repo.getScan(scanId)?.let { scan ->
            Scans.repo.updateScan(scan.copy(status = ScanStatus.DONE))
        }
        nav.navigate("home") { popUpTo("home") { inclusive = true } }
    }

    Column(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas).safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { nav.popBackStack() }) {
                Text("Back", color = OrbitColors.accent)
            }
            Text(
                "Handoff to Orbit Studio",
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(
                "$scanName's bundle is on this phone. Finish the room on your laptop.",
                color = OrbitColors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(20.dp))
            SendToLaptopCard(
                address = laptopAddress,
                onAddressChange = { laptopAddress = it },
                sending = sending,
                progress = sendProgress,
                note = sendNote,
                onSend = { sendToLaptop() },
            )

            Spacer(Modifier.height(20.dp))
            Text(
                "OR DO IT MANUALLY",
                color = OrbitColors.textSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            StepRow(1, "Move bundle.zip to your laptop", "Share sheet, USB cable, or a synced Drive folder — whatever's fastest.")
            StepRow(2, "Drop it into Orbit Studio", "Run start.bat, then open localhost:7360 and load the bundle.")
            StepRow(3, "Run the Colab notebook", "Open the notebook, drop bundle.zip in, and run all cells.")
            StepRow(4, "Get artifact.splat back", "The trained scene returns to the laptop viewer when the notebook finishes.", last = true)

            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OrbitColors.elevated)
                    .padding(14.dp),
            ) {
                Text(
                    "What the notebook will print",
                    color = OrbitColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Registration below 60% means real gaps — expect to reshoot that stretch. Around 95% is healthy and the scene held together.",
                    color = OrbitColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "localhost:7360 · via start.bat",
                color = OrbitColors.textTertiary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(
                onClick = { showRescanConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Rescan room", color = OrbitColors.textSecondary)
            }
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                text = "Start another room",
                onClick = { startAnotherRoom() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showRescanConfirm) {
        RescanRoomDialog(
            shotCount = shotCount,
            onConfirm = {
                showRescanConfirm = false
                Scans.repo.clearShots(scanId)
                nav.navigate("capture/$scanId") { popUpTo("capture/$scanId") { inclusive = true } }
            },
            onDismiss = { showRescanConfirm = false },
        )
    }
}

// The Wi-Fi lane: streams the bundle straight into server.py as a new studio
// project, replacing the USB/Drive shuffle when both devices share a network.
@Composable
private fun SendToLaptopCard(
    address: String,
    onAddressChange: (String) -> Unit,
    sending: Boolean,
    progress: Int,
    note: Pair<Boolean, String>?,
    onSend: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OrbitColors.elevated)
            .padding(14.dp),
    ) {
        Text(
            "Send over Wi-Fi",
            color = OrbitColors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Phone and laptop on the same network. Start the laptop with: python server.py --lan",
            color = OrbitColors.textSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            enabled = !sending,
            singleLine = true,
            placeholder = {
                Text("192.168.1.23:7360", color = OrbitColors.textTertiary, fontFamily = FontFamily.Monospace)
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = OrbitColors.textPrimary,
                unfocusedTextColor = OrbitColors.textPrimary,
                disabledTextColor = OrbitColors.textTertiary,
                cursorColor = OrbitColors.accent,
                focusedBorderColor = OrbitColors.accent,
                unfocusedBorderColor = OrbitColors.hairline20,
                disabledBorderColor = OrbitColors.hairline12,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (sending) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = OrbitColors.accent,
                trackColor = OrbitColors.hairline12,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (progress < 50) "Packing bundle… $progress%" else "Sending… $progress%",
                color = OrbitColors.textSecondary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            PrimaryButton(
                text = "Send bundle",
                onClick = onSend,
                enabled = address.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        note?.let { (ok, message) ->
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = if (ok) OrbitColors.success else OrbitColors.danger,
                style = MaterialTheme.typography.bodySmall,
            )
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
private fun StepRow(number: Int, title: String, body: String, last: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = if (last) 0.dp else 14.dp)) {
        Text(
            "$number",
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(OrbitColors.accentSoft)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            color = OrbitColors.accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, color = OrbitColors.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(body, color = OrbitColors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
