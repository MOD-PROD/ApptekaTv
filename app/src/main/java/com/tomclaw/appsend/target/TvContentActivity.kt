package com.tomclaw.appsend.target

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tomclaw.appsend.tvlink.TvLink
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * ATV (vraie TV, 24/11) : en mode 📺, « Applis » sur le téléphone montre le contenu de la TV
 * (applis installées et mémoire) au lieu de celui du téléphone. Ordre v2 `list_apps`.
 */
class TvContentActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val connection = TvTarget.current(this) ?: return finish()
        val pad = (16 * resources.displayMetrics.density).toInt()
        title = connection.name
        val title = TextView(this).apply {
            text = "📺 ${connection.name}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(pad, pad, pad, pad / 2)
        }
        header = TextView(this).apply {
            text = "Lecture du contenu de la TV…"
            setPadding(pad, 0, pad, pad / 2)
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply { this.adapter = this@TvContentActivity.adapter }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(title)
            addView(header)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
        load(connection)
    }

    private fun load(connection: com.tomclaw.appsend.tvlink.TvLinkSender.Connection) = thread(name = "tv-content") {
        val result = runCatching {
            TvLink.request(connection.address, JSONObject().put("type", "list_apps").put("token", connection.token))
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val response = result.getOrNull()
            when {
                response == null -> header.text = "TV injoignable. Vérifie qu'Appteka TV est ouverte sur la TV et sur le même réseau."
                !response.optBoolean("ok") -> header.text = when (response.optString("error")) {
                    "not_implemented" -> "La TV doit être mise à jour (canal Test) pour montrer son contenu."
                    "unauthorized" -> "Ce téléphone n'est plus associé à la TV. Réassocie-le avec l'icône 📺."
                    else -> "La TV a refusé : ${response.optString("error").take(32)}"
                }
                else -> show(response.optJSONObject("content") ?: JSONObject())
            }
        }
    }

    private fun show(content: JSONObject) {
        val apps = content.optJSONArray("apps")
        val count = apps?.length() ?: 0
        val storage = content.optJSONObject("storage")
        val memory = storage?.let {
            "Mémoire : ${Formatter.formatShortFileSize(this, it.optLong("free"))} libres sur " +
                Formatter.formatShortFileSize(this, it.optLong("total"))
        }
        header.text = listOfNotNull(memory, "$count applis installées").joinToString("\n")
        adapter.clear()
        for (i in 0 until count) {
            val app = apps!!.optJSONObject(i) ?: continue
            val version = app.optString("version").takeIf { it.isNotBlank() }?.let { " · v$it" }.orEmpty()
            val size = app.optLong("size").takeIf { it > 0 }
                ?.let { " · " + Formatter.formatShortFileSize(this, it) }.orEmpty()
            adapter.add("${app.optString("label")}$version$size\n${app.optString("package")}")
        }
    }
}

fun createTvContentActivityIntent(context: Context): Intent = Intent(context, TvContentActivity::class.java)
