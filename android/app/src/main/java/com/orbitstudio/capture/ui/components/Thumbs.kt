package com.orbitstudio.capture.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.orbitstudio.capture.ui.theme.OrbitColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MEMORY_ARCHITECTURE.md #1: one process-wide bounded bitmap cache for review thumbnails.
// RGB_565 + inSampleSize decoding off the main thread, GC-driven eviction (no recycle(),
// which races in-flight draws). onTrimMemory clears the whole thing (see MainActivity).
object ThumbCache {
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024).toInt()

    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() = cache.evictAll()
}

@Composable
fun ThumbImage(
    file: File,
    sizePx: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val key = remember(file, sizePx) { "${file.absolutePath}@$sizePx" }
    val bitmap by produceState(initialValue = ThumbCache.get(key), key1 = key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { decodeSampledThumb(file, sizePx) }
                ?.also { ThumbCache.put(key, it) }
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier
                .background(OrbitColors.elevated)
                .border(1.dp, OrbitColors.hairline12),
        )
    }
}

private fun decodeSampledThumb(file: File, targetPx: Int): Bitmap? {
    if (!file.exists()) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    } catch (e: Exception) {
        null
    }
}
