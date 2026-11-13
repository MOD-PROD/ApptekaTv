package com.tomclaw.appsend.screen.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tomclaw.appsend.R

/**
 * Android TV entry point that loads the local HTML interface.
 * Provides a simple JavaScript bridge for future native calls.
 */
class TvWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        webView.loadUrl("file:///android_asset/tv/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Improve TV experience
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Page is ready – native side can inject real data later
            }
        }

        webView.webChromeClient = WebChromeClient()

        // JavaScript bridge
        webView.addJavascriptInterface(TvJsBridge(), "Android")
    }

    /**
     * Bridge exposed to JavaScript as window.Android
     */
    inner class TvJsBridge {

        @JavascriptInterface
        fun openAppDetails(appId: String) {
            runOnUiThread {
                Toast.makeText(
                    this@TvWebActivity,
                    "Ouvrir détails app: $appId",
                    Toast.LENGTH_SHORT
                ).show()
                // TODO: launch real DetailsActivity when ready
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@TvWebActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun getVersion(): String {
            return "1.0-tv"
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
