package com.tomclaw.appsend.screen.tvaccount

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tomclaw.appsend.R
import com.tomclaw.appsend.target.TvAppsClient
import com.tomclaw.appsend.target.TvRemoteException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ATV (bug Gonzo 25/11) : en mode 📺, le téléphone connecte la TV associée à un compte Appteka,
 * comme « Paramètres → Connexion au compte Appteka » sur la TV.
 *
 * Le téléphone ne transmet aucune session : il relaie l'adresse e-mail puis le code reçu ;
 * c'est la TV, avec son propre identifiant d'appareil, qui appelle le serveur Appteka.
 */
class TvAccountActivity : AppCompatActivity() {

    private var client: TvAppsClient? = null
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "tv-account").apply { isDaemon = true } }

    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var message: TextView
    private lateinit var switchButton: MaterialButton
    private lateinit var emailBox: LinearLayout
    private lateinit var email: TextInputEditText
    private lateinit var send: MaterialButton
    private lateinit var codeBox: LinearLayout
    private lateinit var code: TextInputEditText
    private lateinit var nameLayout: TextInputLayout
    private lateinit var name: TextInputEditText
    private lateinit var verify: MaterialButton

    private var requestId: String? = null
    private var registered = true
    private var sentTo = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tv_account_activity)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.tv_account_title)
        toolbar.setNavigationOnClickListener { finish() }
        status = findViewById(R.id.tv_account_status)
        progress = findViewById(R.id.tv_account_progress)
        message = findViewById(R.id.tv_account_message)
        switchButton = findViewById(R.id.tv_account_switch)
        emailBox = findViewById(R.id.tv_account_email_box)
        email = findViewById(R.id.tv_account_email)
        send = findViewById(R.id.tv_account_send)
        codeBox = findViewById(R.id.tv_account_code_box)
        code = findViewById(R.id.tv_account_code)
        nameLayout = findViewById(R.id.tv_account_name_layout)
        name = findViewById(R.id.tv_account_name)
        verify = findViewById(R.id.tv_account_verify)

        switchButton.setOnClickListener { showEmail() }
        send.setOnClickListener { requestCode() }
        email.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_SEND).also { if (it) requestCode() } }
        verify.setOnClickListener { verifyCode() }
        code.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE && !nameLayout.isVisible).also { if (it) verifyCode() } }
        name.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) verifyCode() } }
        findViewById<MaterialButton>(R.id.tv_account_cancel).setOnClickListener {
            requestId = null
            showMessage(null)
            showEmail()
        }

        val tv = TvAppsClient.current(this)
        client = tv
        if (tv == null) {
            status.setText(R.string.tv_account_no_tv)
            return
        }
        loadStatus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private val tvName: String get() = client?.tvName.orEmpty()

    private fun loadStatus(done: String? = null) {
        val tv = client ?: return
        status.text = getString(R.string.tv_account_loading, tvName)
        run({ tv.accountStatus() }) { response ->
            showProfile(response.optJSONObject("profile") ?: JSONObject())
            if (done != null) showMessage(done)
        }
    }

    private fun showProfile(profile: JSONObject) {
        val registeredTv = profile.optBoolean("registered")
        if (registeredTv) {
            val who = listOf(profile.optString("name"), profile.optString("email"))
                .filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { "#" + profile.optString("userId") }
            status.text = getString(R.string.tv_account_signed_in, tvName, who)
            codeBox.isVisible = false
            emailBox.isVisible = false
            switchButton.isVisible = true
        } else {
            status.text = getString(R.string.tv_account_guest, tvName, profile.optString("userId"))
            switchButton.isVisible = false
            showEmail()
        }
    }

    private fun showEmail() {
        switchButton.isVisible = false
        codeBox.isVisible = false
        emailBox.isVisible = true
        email.requestFocus()
    }

    private fun requestCode() {
        val tv = client ?: return
        val address = email.text?.toString()?.trim().orEmpty()
        if (!address.matches(EMAIL)) {
            showMessage(getString(R.string.tv_account_bad_email), error = true)
            return
        }
        showMessage(null)
        run({ tv.accountRequestCode(address) }) { response ->
            requestId = response.optString("requestId")
            registered = response.optBoolean("registered", true)
            sentTo = address
            emailBox.isVisible = false
            codeBox.isVisible = true
            nameLayout.isVisible = !registered
            code.setText("")
            code.requestFocus()
            showMessage(getString(R.string.tv_account_code_sent, sentTo))
        }
    }

    private fun verifyCode() {
        val tv = client ?: return
        val id = requestId ?: return
        val value = code.text?.toString()?.trim().orEmpty()
        val nick = name.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) {
            showMessage(getString(R.string.tv_account_need_code), error = true)
            return
        }
        if (!registered && nick.isEmpty()) {
            showMessage(getString(R.string.tv_account_need_name), error = true)
            return
        }
        run({ tv.accountVerify(id, value, nick) }) { response ->
            requestId = null
            val profile = response.optJSONObject("profile")
            if (profile != null) showProfile(profile) else loadStatus()
            showMessage(getString(R.string.tv_account_done, tvName))
        }
    }

    /** Ordre bloquant sur le fil d'E/S, puis résultat (ou erreur lisible) sur le fil principal. */
    private fun run(call: () -> JSONObject, onOk: (JSONObject) -> Unit) {
        if (busy) return
        setBusy(true)
        io.execute {
            val result = try {
                Result.success(call())
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setBusy(false)
                result.onSuccess(onOk).onFailure { showMessage(errorText(it), error = true) }
            }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        progress.isVisible = value
        send.isEnabled = !value
        verify.isEnabled = !value
        switchButton.isEnabled = !value
    }

    private fun showMessage(text: String?, error: Boolean = false) {
        message.isVisible = !text.isNullOrEmpty()
        message.text = text.orEmpty()
        message.setTextColor(
            if (error) com.google.android.material.color.MaterialColors.getColor(message, androidx.appcompat.R.attr.colorError)
            else status.currentTextColor
        )
    }

    private fun errorText(error: Throwable): String = when ((error as? TvRemoteException)?.code) {
        null -> getString(R.string.tv_account_error_unreachable)
        "not_implemented", "unknown" -> getString(R.string.tv_account_error_old_tv)
        "unauthorized" -> getString(R.string.tv_account_error_unauthorized)
        "bad_email" -> getString(R.string.tv_account_bad_email)
        "rate_limit" -> getString(R.string.tv_account_error_rate_limit)
        "refused" -> getString(R.string.tv_account_error_refused)
        "network" -> getString(R.string.tv_account_error_network)
        else -> getString(R.string.tv_account_error_other, (error as TvRemoteException).code)
    }

    private companion object {
        val EMAIL = Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
    }
}

fun createTvAccountActivityIntent(context: Context): Intent = Intent(context, TvAccountActivity::class.java)
