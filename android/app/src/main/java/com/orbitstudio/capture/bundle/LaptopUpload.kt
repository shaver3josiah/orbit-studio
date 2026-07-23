package com.orbitstudio.capture.bundle

import android.content.Context
import com.orbitstudio.capture.data.Scan
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Sends a scan's bundle straight to the laptop's server.py over the LAN
// (POST /api/ingest/bundle, raw zip body). The laptop must be started with
// `python server.py --lan` — the default bind is localhost-only.
// ponytail: HttpURLConnection over adding an HTTP client dep — one POST on a LAN.
object LaptopUpload {

    fun normalizeAddress(raw: String): String {
        var addr = raw.trim().removePrefix("http://").removeSuffix("/")
        if (!addr.contains(':')) addr += ":7360"
        return addr
    }

    /**
     * Builds the bundle zip to a cache temp file (so Content-Length is known and
     * memory stays constant), then streams it to the laptop. Progress: 0-50 build,
     * 50-100 upload. Returns the studio project id. Throws IOException on failure.
     */
    fun send(
        context: Context,
        scan: Scan,
        photosDir: File,
        address: String,
        onProgress: (Int) -> Unit,
    ): String {
        val temp = File(context.cacheDir, "send-${scan.id}.zip")
        try {
            temp.outputStream().buffered().use { out ->
                BundleBuilder.build(scan, photosDir, out, { pct -> onProgress(pct / 2) }, context)
            }
            val name = URLEncoder.encode(scan.name, "UTF-8")
            val url = URL("http://${normalizeAddress(address)}/api/ingest/bundle?name=$name")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("Content-Type", "application/zip")
                conn.setFixedLengthStreamingMode(temp.length())
                conn.outputStream.use { out ->
                    temp.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        val total = temp.length().coerceAtLeast(1)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            sent += read
                            onProgress(50 + ((sent * 50) / total).toInt())
                        }
                    }
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText().orEmpty()
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull()
                    throw IOException(if (!detail.isNullOrBlank()) detail else "laptop replied HTTP $code")
                }
                return JSONObject(body).optString("id", "")
            } finally {
                conn.disconnect()
            }
        } finally {
            temp.delete()
        }
    }
}
