package com.orbitstudio.capture

import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.orbitstudio.capture.data.FileScanRepository
import com.orbitstudio.capture.data.Scans
import com.orbitstudio.capture.ui.components.ThumbCache
import com.orbitstudio.capture.ui.theme.OrbitTheme
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Scans.repo = FileScanRepository(applicationContext)
        installCrashHandler()
        setContent {
            OrbitTheme {
                OrbitNavHost()
            }
        }
    }

    // MEMORY_ARCHITECTURE.md #1: drop the bounded thumbnail cache once the UI is no longer visible.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ThumbCache.clear()
        }
    }

    // MEMORY_ARCHITECTURE.md #5: best-effort crash forensics, then die normally.
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(applicationContext.filesDir, "last-crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
                crashFile.writeText("${Date()}\n${Build.MODEL}\n\n$trace")
            } catch (e: Exception) {
                // ponytail: crash handler must never throw; best-effort write only.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
