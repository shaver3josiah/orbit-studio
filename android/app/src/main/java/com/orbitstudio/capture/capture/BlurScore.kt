package com.orbitstudio.capture.capture

import android.graphics.BitmapFactory
import java.io.File

// ponytail: plain Kotlin loops over a downsampled grayscale copy — good enough for
// "did I shake the phone" coaching, not a print-quality sharpness metric. Upgrade path:
// RenderScript/vectorized convolution if this ever needs to run on full-resolution frames.
object BlurScore {
    private const val TARGET_LONGEST_EDGE = 256

    /** Laplacian variance of the shot, higher = sharper. Returns 0.0 if the file can't be read. */
    fun compute(file: File): Double {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0.0

        var sample = 1
        val longestEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longestEdge / (sample * 2) >= TARGET_LONGEST_EDGE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return 0.0

        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) {
            bitmap.recycle()
            return 0.0
        }

        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()

        val gray = DoubleArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }

        // 3x3 Laplacian kernel: [[0,1,0],[1,-4,1],[0,1,0]]
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x
                val lap = gray[idx - 1] + gray[idx + 1] + gray[idx - w] + gray[idx + w] - 4 * gray[idx]
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        return sumSq / count - mean * mean
    }
}
