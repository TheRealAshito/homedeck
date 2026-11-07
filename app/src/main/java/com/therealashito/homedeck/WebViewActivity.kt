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
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
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
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.model.Tile
import com.therealashito.homedeck.model.Urls

/**
 * In-app browser for one service. All cookies, localStorage and cache live in
 * this app's data directory, so sign-in sessions survive restarts until the
 * user clears them in Settings.
 */
class WebViewActivity : ThemedActivity() {

    private lateinit var settings: SettingsStore
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
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        webview.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webview.stopLoading()
        webview.destroy()
        super.onDestroy()
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
