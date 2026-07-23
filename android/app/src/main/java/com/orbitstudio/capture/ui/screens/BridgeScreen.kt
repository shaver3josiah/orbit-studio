package com.orbitstudio.capture.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import com.orbitstudio.capture.ui.theme.OrbitColors

// Bridge mode hosts the bundled Bridge Sketch tool verbatim from
// assets/bridge-sketch.html — it's a self-contained offline HTML app (no
// network, autosaves to localStorage), so this screen is just a WebView
// shell + a slim top bar. The WebView IS the app; we don't reimplement any
// of its UI.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BridgeScreen(nav: NavController) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else nav.popBackStack()
    }

    Column(modifier = Modifier.fillMaxSize().background(OrbitColors.canvas).safeDrawingPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OrbitColors.elevated)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { nav.popBackStack() }) {
                Text("Back", color = OrbitColors.accent)
            }
            Text(
                "Bridge layout",
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    loadUrl("file:///android_asset/bridge-sketch.html")
                    webView = this
                }
            },
            onRelease = { wv ->
                // Tear down the native/JS engine so repeated Home->Bridge->Back never leaks it.
                if (webView === wv) webView = null
                wv.destroy()
            },
        )
    }
}
