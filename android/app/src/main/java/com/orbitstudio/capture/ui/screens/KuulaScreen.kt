package com.orbitstudio.capture.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.orbitstudio.capture.ui.components.PrimaryButton
import com.orbitstudio.capture.ui.theme.OrbitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val KUULA_URL = "https://kuula.co"

// Kuula has no public upload API (only a JS Player API for embeds), so this
// screen hosts kuula.co itself — their site supports mobile-browser upload,
// including batch. The two things a bare WebView is missing for that flow are
// wired up here: onShowFileChooser (Android WebViews never open <input
// type=file> pickers on their own) and persisted cookies (log in once, stay
// logged in). "Open in browser" is the escape hatch for Google/Facebook
// sign-in, which those providers block inside WebViews.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KuulaScreen(nav: NavController) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    // Bumped when the renderer process dies so key() rebuilds a fresh WebView
    // instead of the default behavior, which kills the whole app on API 26+.
    var webViewGen by remember { mutableIntStateOf(0) }
    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // One-time .insp hint — same flag-file pattern as Home's crash banner.
    val hintFile = remember { File(context.filesDir, "kuula-insp-hint-dismissed") }
    var showHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showHint = withContext(Dispatchers.IO) { !hintFile.exists() }
    }

    // Kuula's login is an XHR/SPA flow that fires no onPageFinished, and
    // Chromium persists cookies lazily — flush on pause so a fresh sign-in
    // survives the app being swiped away right after.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) CookieManager.getInstance().flush()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fileChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pendingFileCallback?.onReceiveValue(parseChooserResult(result.resultCode, result.data))
        pendingFileCallback = null
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            // Back-forward-cache restores can skip onPageStarted, so clear the
            // error overlay here too or it would sit on top of a good page.
            loadFailed = false
            wv.goBack()
        } else {
            nav.popBackStack()
        }
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
                "Kuula",
                color = OrbitColors.textPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { openInBrowser(context, webView?.url ?: KUULA_URL) }) {
                Text("Open in browser", color = OrbitColors.accent)
            }
        }

        if (showHint) {
            InspHintBanner(
                onDismiss = {
                    showHint = false
                    hintFile.createNewFile()
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (progress in 1..99 && !loadFailed) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = OrbitColors.accent,
                trackColor = OrbitColors.hairline12,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            key(webViewGen) {
                AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val url = request?.url ?: return false
                                // Google blocks OAuth inside WebViews (403
                                // disallowed_useragent) — finish sign-in and
                                // upload in the real browser instead of
                                // dead-ending here.
                                if (url.host == "accounts.google.com") {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                    } catch (_: ActivityNotFoundException) {
                                    }
                                    return true
                                }
                                if (url.scheme == "http" || url.scheme == "https") return false
                                // mailto:, intent:, market: — hand off to the system.
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                                } catch (_: ActivityNotFoundException) {
                                }
                                return true
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                // Any navigation that actually starts dismisses
                                // the error overlay; a failing load re-raises it.
                                loadFailed = false
                            }

                            override fun onRenderProcessGone(
                                view: WebView?,
                                detail: RenderProcessGoneDetail?,
                            ): Boolean {
                                // Renderer OOM or system kill — returning false
                                // here would crash the whole app. Drop the dead
                                // view; key(webViewGen) rebuilds a fresh one.
                                if (webView === view) webView = null
                                loadFailed = true
                                webViewGen++
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                // Subresource failures (an ad, a font) shouldn't
                                // blank the whole screen — only main-frame ones.
                                if (request?.isForMainFrame == true) loadFailed = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                // Persist the login session across app restarts.
                                CookieManager.getInstance().flush()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onShowFileChooser(
                                view: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                // A second chooser while one is pending: release
                                // the old callback or the page hangs forever.
                                pendingFileCallback?.onReceiveValue(null)
                                pendingFileCallback = filePathCallback
                                val intent = fileChooserParams?.createIntent()
                                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        type = "image/*"
                                    }
                                // createIntent() never sets this itself, even in
                                // MODE_OPEN_MULTIPLE — needed for batch upload.
                                if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                                }
                                return try {
                                    fileChooser.launch(intent)
                                    true
                                } catch (_: ActivityNotFoundException) {
                                    pendingFileCallback = null
                                    false
                                }
                            }
                        }
                        loadUrl(KUULA_URL)
                        webView = this
                    }
                },
                onRelease = { wv ->
                    // Same teardown as BridgeScreen — never leak the JS engine.
                    // Flush first so a session cookie set moments ago survives.
                    CookieManager.getInstance().flush()
                    if (webView === wv) webView = null
                    wv.destroy()
                },
                )
            }

            if (loadFailed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OrbitColors.canvas)
                        .padding(horizontal = 32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Can't reach kuula.co",
                        color = OrbitColors.textPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Check your connection, then try again. Your Kuula login is kept, " +
                            "so you won't need to sign in again.",
                        color = OrbitColors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        text = "Try again",
                        onClick = {
                            loadFailed = false
                            webView?.reload()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

// One-time coaching for the Insta360 lane: Kuula can't ingest .insp, and the
// failure mode without this hint is a confusing "file greyed out / upload
// does nothing" moment inside Kuula's own picker UI.
@Composable
private fun InspHintBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(OrbitColors.accentSoft)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Shot on Insta360? Export to your gallery from the Insta360 app first — " +
                ".insp files won't upload.",
            color = OrbitColors.textPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text("Got it", color = OrbitColors.accent, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// FileChooserParams.parseResult drops multi-select results (it ignores
// ClipData), so batch upload needs a hand-rolled parse.
private fun parseChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) {
        return Array(clip.itemCount) { clip.getItemAt(it).uri }
    }
    return data.data?.let { arrayOf(it) }
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
    }
}
