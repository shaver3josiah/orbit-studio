package com.orbitstudio.capture.ui.screens

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.orbitstudio.capture.bundle.BundleBuilder
import com.orbitstudio.capture.data.Scan
import com.orbitstudio.capture.data.ScanStatus
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private data class BundleSaveResult(val savedPath: String, val shareUri: Uri, val note: String? = null)

@Composable
fun BundleScreen(nav: NavController, scanId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scan by remember { mutableStateOf(Scans.repo.getScan(scanId)) }
    var manifestText by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<JSONObject?>(null) }
    var building by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var shareUri by remember { mutableStateOf<Uri?>(null) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scanId) {
        val loaded = Scans.repo.getScan(scanId) ?: return@LaunchedEffect
        scan = loaded
        withContext(Dispatchers.IO) {
            val built = BundleBuilder.buildManifestJson(loaded, Scans.repo.photosDir(scanId))
            manifest = built
            manifestText = built.toString(2)
        }
    }

    fun startBuild() {
        val current = scan ?: return
        scope.launch {
            building = true; error = null; progress = 0; savedPath = null; shareUri = null; savedNote = null
            try {
                val result = withContext(Dispatchers.IO) {
                    saveBundle(context, current, Scans.repo.photosDir(scanId)) { p -> progress = p }
                }
                savedPath = result.savedPath
                shareUri = result.shareUri
                savedNote = result.note
                val updated = current.copy(status = ScanStatus.BUNDLED)
                Scans.repo.updateScan(updated)
                scan = updated
            } catch (e: Exception) {
                error = e.message ?: "Couldn't build the bundle."
            } finally {
                building = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startBuild() else error = "Storage permission is needed to save the bundle."
    }

    fun onBuildClick() {
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else startBuild()
    }

    val rig = manifest?.optJSONObject("rig")
    val focalText = rig?.let { if (it.has("focal_length_35mm_equiv_mm")) "${it.optInt("focal_length_35mm_equiv_mm")}mm" else "—" } ?: "—"

    Column(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas).safeDrawingPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { nav.popBackStack() }) {
                Text("Back", color = OrbitColors.accent)
            }
            Text(
                "Bundle & handoff",
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
            SectionLabel("Manifest summary")
            Card {
                FieldRow("Project", manifest?.optString("project").orEmpty())
                FieldRow("Photos / crops", (manifest?.optInt("crops") ?: 0).toString())
                FieldRow("Lane", "phone")
                FieldRow("Device", rig?.optString("device").orEmpty())
                FieldRow("Focal (35mm eq.)", focalText)
                FieldRow("Exposure locked", (rig?.optBoolean("exposure_locked") ?: false).toString())
                FieldRow("Pattern", "perimeter+interior+loop", last = true)
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("manifest.json")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(OrbitColors.canvas)
                    .padding(1.dp),
            ) {
                Text(
                    text = manifestText,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp),
                    color = OrbitColors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Photos are stored uncompressed (ZIP_STORED) inside crops/, matching the pipeline's bundle.py contract.",
                modifier = Modifier.padding(top = 8.dp),
                color = OrbitColors.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            if (building) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = OrbitColors.accent,
                    trackColor = OrbitColors.hairline12,
                )
                Text(
                    "Zipping bundle… $progress%",
                    modifier = Modifier.padding(top = 6.dp),
                    color = OrbitColors.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OrbitColors.dangerSoft)
                        .padding(12.dp),
                ) {
                    Text(message, color = OrbitColors.danger, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onBuildClick() }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Retry", color = OrbitColors.danger, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            savedPath?.let { path ->
                Spacer(Modifier.height(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(OrbitColors.successSoft)
                        .padding(12.dp),
                ) {
                    Text(
                        "Bundle's ready. Open the notebook and run all cells.",
                        color = OrbitColors.success,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Saved to $path",
                        modifier = Modifier.padding(top = 2.dp),
                        color = OrbitColors.textSecondary,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    savedNote?.let { note ->
                        Text(
                            note,
                            modifier = Modifier.padding(top = 2.dp),
                            color = OrbitColors.textSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (savedPath == null) {
                PrimaryButton(
                    text = if (building) "Zipping…" else "Build bundle",
                    onClick = { onBuildClick() },
                    enabled = scan != null && !building,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val uri = shareUri ?: return@OutlinedButton
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share bundle"))
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Share bundle", color = OrbitColors.textPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    PrimaryButton(
                        text = "Continue to Done",
                        onClick = { nav.navigate("done/$scanId") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        modifier = Modifier.padding(bottom = 10.dp, top = 4.dp),
        color = OrbitColors.textSecondary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OrbitColors.elevated)
            .padding(horizontal = 14.dp),
        content = content,
    )
}

@Composable
private fun FieldRow(key: String, value: String, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, color = OrbitColors.textSecondary, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = OrbitColors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun saveBundle(
    context: Context,
    scan: Scan,
    photosDir: File,
    onProgress: (Int) -> Unit,
): BundleSaveResult {
    val displayName = "${scan.name.lowercase().replace(' ', '-')}-bundle.zip"

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/OrbitStudio")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        // ponytail: some Samsung/MediaTek OEM storage policies refuse the Downloads insert
        // (null return, or a thrown SecurityException) instead of granting it — fall back to
        // app-private storage rather than crash or dead-end the user.
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (e: SecurityException) {
            null
        }
        if (uri == null) {
            saveToAppStorage(context, scan, photosDir, displayName, onProgress)
        } else {
            resolver.openOutputStream(uri)?.use { out -> BundleBuilder.build(scan, photosDir, out, onProgress) }
                ?: throw IOException("Couldn't open the bundle for writing.")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            BundleSaveResult("Download/OrbitStudio/$displayName", uri)
        }
    } else {
        // ponytail: file_paths.xml only exposes filesDir/getExternalFilesDir, not the public
        // Downloads tree, so a legacy save can't get a FileProvider uri directly — mirror the
        // finished zip into filesDir for sharing instead of adding a new provider root.
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OrbitStudio")
            .apply { mkdirs() }
        val target = File(dir, displayName)
        FileOutputStream(target).use { out -> BundleBuilder.build(scan, photosDir, out, onProgress) }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("application/zip"), null)

        val shareCopy = File(context.filesDir, "bundles/$displayName").apply { parentFile?.mkdirs() }
        target.copyTo(shareCopy, overwrite = true)
        val shareUri = FileProvider.getUriForFile(context, "com.orbitstudio.capture.fileprovider", shareCopy)
        BundleSaveResult(target.absolutePath, shareUri)
    }
}

/**
 * Fallback when MediaStore refuses to hand out a Downloads uri (OEM storage policy, no
 * Downloads volume). Writes into app-owned storage instead, which always exists and is already
 * covered by file_paths.xml's external-files-path root, and shares it via the same FileProvider.
 */
private fun saveToAppStorage(
    context: Context,
    scan: Scan,
    photosDir: File,
    displayName: String,
    onProgress: (Int) -> Unit,
): BundleSaveResult {
    val dir = (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir)
        .apply { mkdirs() }
    val target = File(dir, displayName)
    FileOutputStream(target).use { out -> BundleBuilder.build(scan, photosDir, out, onProgress) }
    val shareUri = FileProvider.getUriForFile(context, "com.orbitstudio.capture.fileprovider", target)
    return BundleSaveResult(target.absolutePath, shareUri, note = "Saved to the app folder.")
}
