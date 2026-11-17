package com.tomclaw.appsend.screen.tv

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.JavascriptInterface
import androidx.appcompat.app.AppCompatActivity
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.screen.home.HomeActivity
import com.tomclaw.appsend.dto.AppEntity
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.json.JSONArray
import org.json.JSONObject

class TvActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private val jobs = CompositeDisposable()
    private val known = mutableMapOf<String, AppEntity>()
    private var generation = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.settings.domStorageEnabled = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        web.addJavascriptInterface(Bridge(), "Tv")
        web.loadUrl("file:///android_asset/tv/index.html")
        web.requestFocus()
    }
    private inner class Bridge {
        @JavascriptInterface fun load(query: String, offset: String) = runOnUiThread {
            val token = ++generation
            val api = appComponent.tvStoreApi()
            val request = if (query.isBlank()) api.getTopList(offset.ifBlank { null }, "fr")
                else api.searchApps(query.take(200), null, offset.toIntOrNull() ?: 0, "fr")
            jobs.add(request.subscribeOn(Schedulers.io()).subscribe({ response ->
                runOnUiThread {
                    if (token == generation && !isDestroyed) {
                        val rows = JSONArray()
                        response.result.files.forEach { item ->
                            known[item.appId] = item
                            rows.put(JSONObject().put("id", item.appId).put("title", item.title)
                                .put("version", item.verName).put("icon", item.icon ?: ""))
                        }
                        web.evaluateJavascript("window.receive(" + rows.toString() + ")", null)
                    }
                }
            }, { runOnUiThread { if (token == generation && !isDestroyed) web.evaluateJavascript("window.failed()", null) } }))
        }
        @JavascriptInterface fun details(id: String) = runOnUiThread {
            known[id]?.let { startActivity(createDetailsActivityIntent(this@TvActivity, appId = it.appId, label = it.title, finishOnly = true)) }
        }
        @JavascriptInterface fun classic() = runOnUiThread {
            startActivity(android.content.Intent(this@TvActivity, HomeActivity::class.java))
        }
    }
    override fun onDestroy() {
        jobs.dispose()
        web.removeJavascriptInterface("Tv")
        web.destroy()
        super.onDestroy()
    }
}
