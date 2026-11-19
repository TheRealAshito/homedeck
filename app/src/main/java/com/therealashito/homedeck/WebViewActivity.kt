package com.therealashito.homedeck

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.therealashito.homedeck.data.CredentialStore
import com.therealashito.homedeck.data.SessionKeeper
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.debug.SessionJournal
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.model.Urls

/**
 * In-app browser for one service. All cookies, localStorage and cache live in
 * this app's data directory, so sign-in sessions survive restarts until the
 * user clears them in Settings.
 */
class WebViewActivity : ThemedActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var credentialStore: CredentialStore
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webview: WebView
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorDetail: TextView
    private lateinit var entryUrl: String
    private var initialName: String = ""
    private var desktopMode = false
    private var originalUserAgent: String = DEFAULT_UA
    private var desktopUserAgent: String = DEFAULT_UA
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val lastAutoSubmitByOrigin = mutableMapOf<String, Long>()
    private val triedHttpAuth = mutableSetOf<String>()

    /** Persists cookies every minute so an unclean process kill loses at
     *  most a minute of session state instead of everything since launch. */
    private val flushHandler = Handler(Looper.getMainLooper())
    private val flushTick = object : Runnable {
        override fun run() {
            CookieManager.getInstance().flush()
            if (settings.permanentSessions) {
                val activeUrl = webview.url ?: entryUrl
                SessionKeeper.immortalizeCookies(this@WebViewActivity, activeUrl)
                captureDomStorage(activeUrl)
            }
            flushHandler.postDelayed(this, 60_000)
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingFileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            pendingFileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_webview)
        settings = SettingsStore(this)
        credentialStore = CredentialStore(this)

        entryUrl = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        initialName = intent.getStringExtra(EXTRA_NAME) ?: Urls.hostOf(entryUrl)
        AppLog.i("WebView", "open ${Urls.hostOf(entryUrl)}")

        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        webview = findViewById(R.id.webview)
        errorPanel = findViewById(R.id.errorPanel)
        errorDetail = findViewById(R.id.errorDetail)

        toolbar.title = initialName
        if (settings.debugMode) toolbar.subtitle = entryUrl
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_webview)
        toolbar.setOnMenuItemClickListener { item -> handleMenu(item.itemId); true }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<MaterialButton>(R.id.retryBtn).setOnClickListener {
            errorPanel.visibility = View.GONE
            webview.reload()
        }

        swipe.setOnRefreshListener { webview.reload() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webview.canGoBack()) webview.goBack() else finish()
            }
        })

        setupWebview()
        if (settings.permanentSessions) {
            SessionKeeper.restoreIfMissing(this, entryUrl)
        }
        webview.loadUrl(entryUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebview() {
        originalUserAgent = webview.settings.userAgentString ?: DEFAULT_UA
        desktopUserAgent = originalUserAgent.replace("Mobile ", "")

        webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = if (settings.cacheFirst) {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            } else {
                WebSettings.LOAD_DEFAULT
            }
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            allowContentAccess = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webview, true)
        }

        webview.addJavascriptInterface(AuthBridge(), "HomedeckAuth")
        webview.webChromeClient = ChromeClient()
        webview.webViewClient = SiteClient()
    }

    private fun handleMenu(itemId: Int) {
        val currentUrl = webview.url ?: entryUrl
        when (itemId) {
            R.id.action_refresh -> webview.reload()
            R.id.action_desktop -> {
                desktopMode = !desktopMode
                webview.settings.userAgentString =
                    if (desktopMode) desktopUserAgent else originalUserAgent
                webview.settings.useWideViewPort = !desktopMode
                webview.reload()
            }
            R.id.action_saved_login -> showSavedLoginDialog(currentUrl)
            R.id.action_add_dashboard -> EditTileActivity.start(
                this,
                tileId = null,
                prefillName = webview.title ?: initialName,
                prefillUrl = currentUrl
            )
            R.id.action_open_external -> openExternally(currentUrl)
            R.id.action_copy_link -> copyLink(currentUrl)
            R.id.action_share -> share(currentUrl)
        }
    }

    private fun showSavedLoginDialog(url: String) {
        val origin = Urls.originOf(url)
        val existing = credentialStore.getForUrl(url)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 0)
        }
        val userField = EditText(this).apply {
            hint = "Username / Email (optional)"
            setText(existing?.username.orEmpty())
        }
        val passField = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.password.orEmpty())
        }
        container.addView(userField)
        container.addView(passField)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("Permanent login for ${Urls.hostOf(url)}")
            .setMessage("Automatically signs back in if $origin ever expires your session.")
            .setView(container)
            .setPositiveButton("Save & Sign In") { _, _ ->
                val user = userField.text.toString().trim()
                val pass = passField.text.toString()
                if (user.isEmpty() && pass.isEmpty()) {
                    credentialStore.removeForUrl(url)
                    Toast.makeText(this, "Saved login removed", Toast.LENGTH_SHORT).show()
                } else {
                    credentialStore.saveForUrl(url, user, pass, autoSubmit = true)
                    Toast.makeText(this, "Saved permanent login", Toast.LENGTH_SHORT).show()
                    maybeAutoLogin(url, forceSubmit = true)
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Clear") { _, _ ->
                credentialStore.removeForUrl(url)
                Toast.makeText(this, "Saved login cleared", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun captureDomStorage(url: String) {
        if (!settings.permanentSessions) return
        try {
            webview.evaluateJavascript(SessionKeeper.buildStorageCaptureJs()) { raw ->
                SessionKeeper.saveCapturedDomStorage(this, url, raw)
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreDomStorage(url: String) {
        if (!settings.permanentSessions) return
        val script = SessionKeeper.buildRestoreDomStorageScript(this, url) ?: return
        try {
            webview.evaluateJavascript(script, null)
        } catch (_: Throwable) {
        }
    }

    private fun maybeAutoLogin(url: String, forceSubmit: Boolean) {
        if (!settings.permanentSessions && !forceSubmit) return
        val cred = credentialStore.getForUrl(url) ?: return
        val origin = Urls.originOf(url)
        val now = System.currentTimeMillis()
        val lastSubmit = lastAutoSubmitByOrigin[origin] ?: 0L
        val shouldSubmit = forceSubmit || (cred.autoSubmit && (now - lastSubmit >= AUTO_SUBMIT_COOLDOWN_MS))
        val js = CredentialStore.buildAutoLoginJs(cred.username, cred.password, shouldSubmit)
        try {
            webview.evaluateJavascript(js) { rawResult ->
                val outcome = SessionKeeper.unwrapEvalResult(rawResult) ?: return@evaluateJavascript
                when (outcome) {
                    "SUBMITTED" -> {
                        lastAutoSubmitByOrigin[origin] = System.currentTimeMillis()
                        AppLog.i("AuthVault", "auto-signed in on $origin")
                        Toast.makeText(
                            this,
                            "Auto-signed in to ${Urls.hostOf(url)}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    "FILLED" -> {
                        AppLog.d("AuthVault", "pre-filled login on $origin")
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun openExternally(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun share(url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(send, "Share link"))
        } catch (e: Exception) {
            // No sharing target available; nothing to do.
        }
    }

    private fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 24, 64, 0)
        }
        val userField = EditText(this).apply { hint = "Username" }
        val passField = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(userField)
        container.addView(passField)

        MaterialAlertDialogBuilder(this)
            .setTitle("Sign in to $host")
            .setMessage(if (realm.isBlank()) null else "Realm: $realm")
            .setView(container)
            .setPositiveButton("Sign in") { _, _ ->
                val user = userField.text.toString()
                val pass = passField.text.toString()
                if (settings.permanentSessions && (user.isNotBlank() || pass.isNotBlank())) {
                    credentialStore.saveForUrl(webview.url ?: entryUrl, user, pass)
                }
                webview.setHttpAuthUsernamePassword(host, realm, user, pass)
                handler.proceed(user, pass)
            }
            .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        webview.onResume()
        flushHandler.post(flushTick)
    }

    override fun onPause() {
        flushHandler.removeCallbacks(flushTick)
        val activeUrl = webview.url ?: entryUrl
        if (settings.permanentSessions) {
            SessionKeeper.immortalizeCookies(this, activeUrl)
            captureDomStorage(activeUrl)
        }
        CookieManager.getInstance().flush()
        SessionJournal.recordUrl(this, entryUrl)
        webview.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        flushHandler.removeCallbacksAndMessages(null)
        webview.stopLoading()
        webview.destroy()
        super.onDestroy()
    }

    inner class AuthBridge {
        @JavascriptInterface
        fun onLoginSubmitted(pageUrl: String?, username: String?, password: String?) {
            val pass = password ?: return
            if (pass.isEmpty()) return
            val user = username?.trim().orEmpty()
            runOnUiThread {
                if (!settings.permanentSessions) return@runOnUiThread
                val targetUrl = pageUrl?.takeIf { it.isNotBlank() } ?: (webview.url ?: entryUrl)
                val existing = credentialStore.getForUrl(targetUrl)
                if (existing != null && existing.username == user && existing.password == pass) {
                    return@runOnUiThread
                }
                val host = Urls.hostOf(targetUrl)
                Snackbar.make(
                    findViewById(R.id.root),
                    "Save login for permanent sign-in on $host?",
                    Snackbar.LENGTH_LONG
                ).setDuration(10_000).setAction("Save") {
                    credentialStore.saveForUrl(targetUrl, user, pass, autoSubmit = true)
                    Toast.makeText(
                        this@WebViewActivity,
                        "Saved permanent login for $host",
                        Toast.LENGTH_SHORT
                    ).show()
                }.show()
            }
        }
    }

    inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.visibility = if (newProgress in 0..99) View.VISIBLE else View.GONE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) toolbar.title = title
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // Render popups / target=_blank inside this same WebView.
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = webview
            resultMsg.sendToTarget()
            return true
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback
            return try {
                fileChooserLauncher.launch(fileChooserParams.createIntent())
                true
            } catch (e: Exception) {
                pendingFileCallback = null
                false
            }
        }
    }

    inner class SiteClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase() ?: return false
            if (scheme == "http" || scheme == "https") return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
            } catch (e: Exception) {
                Toast.makeText(
                    this@WebViewActivity,
                    "No app handles ${request.url}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            errorPanel.visibility = View.GONE
            swipe.isRefreshing = false
            AppLog.d("WebView", "page started: $url")
            if (settings.debugMode) toolbar.subtitle = url
            if (settings.permanentSessions && !url.isNullOrBlank()) {
                SessionKeeper.restoreIfMissing(this@WebViewActivity, url)
                restoreDomStorage(url)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val activeUrl = url?.takeIf { it.isNotBlank() } ?: return
            if (settings.permanentSessions) {
                SessionKeeper.immortalizeCookies(this@WebViewActivity, activeUrl)
                restoreDomStorage(activeUrl)
                try {
                    view.evaluateJavascript(CredentialStore.buildCaptureHookJs(), null)
                } catch (_: Throwable) {
                }
                maybeAutoLogin(activeUrl, forceSubmit = false)
                // Second pass for SPAs (React/Vue/Svelte) that mount login inputs
                // or populate localStorage/sessionStorage asynchronously after load.
                flushHandler.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        val current = webview.url ?: activeUrl
                        SessionKeeper.immortalizeCookies(this@WebViewActivity, current)
                        captureDomStorage(current)
                        try {
                            webview.evaluateJavascript(CredentialStore.buildCaptureHookJs(), null)
                        } catch (_: Throwable) {
                        }
                        maybeAutoLogin(current, forceSubmit = false)
                    }
                }, 900)
            }
            SessionJournal.recordUrl(this@WebViewActivity, activeUrl)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            AppLog.w("WebView", "load failed: ${request.url} (${error.description})")
            errorPanel.visibility = View.VISIBLE
            errorDetail.text = "${error.description}\n\n${request.url}"
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = Urls.hostOf(error.url)
            if (settings.isHostTrusted(host)) {
                handler.proceed()
                return
            }
            MaterialAlertDialogBuilder(this@WebViewActivity)
                .setTitle("Untrusted certificate")
                .setMessage(
                    "The security certificate for $host is not issued by a trusted authority. " +
                        "This is common on home networks with self-signed certificates.\n\n" +
                        "Only continue if you trust this service."
                )
                .setPositiveButton("Trust anyway") { _, _ ->
                    settings.trustHost(host)
                    AppLog.i("WebView", "certificate accepted for $host")
                    handler.proceed()
                }
                .setNegativeButton("Go back") { _, _ ->
                    AppLog.w("WebView", "certificate rejected for $host")
                    handler.cancel()
                }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
        ) {
            val stored = view.getHttpAuthUsernamePassword(host, realm)
            if (stored != null && stored.size == 2 && !stored[0].isNullOrEmpty()) {
                handler.proceed(stored[0], stored[1])
                return
            }
            if (settings.permanentSessions) {
                val saved = credentialStore.getForUrl(view.url ?: entryUrl)
                val authKey = "$host|$realm"
                if (saved != null && (saved.username.isNotEmpty() || saved.password.isNotEmpty()) &&
                    triedHttpAuth.add(authKey)
                ) {
                    view.setHttpAuthUsernamePassword(host, realm, saved.username, saved.password)
                    handler.proceed(saved.username, saved.password)
                    AppLog.i("WebView", "auto-answered HTTP auth for $host")
                    return
                }
            }
            AppLog.i("WebView", "http auth challenge for $host")
            showHttpAuthDialog(handler, host, realm)
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            // A crashed renderer must not take the whole app with it.
            finish()
            return true
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_NAME = "extra_name"
        private const val AUTO_SUBMIT_COOLDOWN_MS = 15_000L

        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Homedeck/1.0 Mobile Safari/537.36"

        fun intent(context: Context, tile: Tile): Intent =
            Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, tile.url)
                putExtra(EXTRA_NAME, tile.name)
            }
    }
}
