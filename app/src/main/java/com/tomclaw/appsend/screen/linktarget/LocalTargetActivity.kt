package com.tomclaw.appsend.screen.linktarget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tomclaw.appsend.R
import com.tomclaw.appsend.tvlink.TvLink

/**
 * ATV (Gonzo 25/11) : « Piloter cet appareil à distance » sur un téléphone ou une tablette.
 * Même QR / code que la TV, liste des appareils associés (Oublier, Retirer l'accès fichiers).
 */
class LocalTargetActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var enabled: MaterialSwitch
    private lateinit var address: TextView
    private lateinit var pair: MaterialButton
    private lateinit var list: LinearLayout
    private lateinit var forgetAll: MaterialButton
    private lateinit var test: MaterialButton
    private var pairingDialog: AlertDialog? = null

    private val onChanged: () -> Unit = {
        // Une association vient d'être acceptée : la fenêtre du code se ferme, la liste se met à jour.
        pairingDialog?.takeIf { it.isShowing }?.dismiss()
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.local_target_activity)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.local_target_title)
        toolbar.setNavigationOnClickListener { finish() }
        enabled = findViewById(R.id.local_target_enabled)
        address = findViewById(R.id.local_target_address)
        pair = findViewById(R.id.local_target_pair)
        list = findViewById(R.id.local_target_list)
        forgetAll = findViewById(R.id.local_target_forget_all)

        enabled.isChecked = LocalLinkTarget.isEnabled(this)
        enabled.setOnCheckedChangeListener { _, checked ->
            LocalLinkTarget.setEnabled(this, checked)
            render()
        }
        pair.setOnClickListener { showPairingQr() }
        test = findViewById(R.id.local_target_test)
        test.setOnClickListener { selfTest() }
        forgetAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.local_target_forget_all_confirm)
                .setPositiveButton(R.string.local_target_forget_all) { _, _ ->
                    LocalLinkTarget.server?.forgetAll()
                    render()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        render()
    }

    override fun onStart() {
        super.onStart()
        LocalLinkTarget.addListener(onChanged)
        render()
    }

    override fun onStop() {
        LocalLinkTarget.removeListener(onChanged)
        super.onStop()
    }

    private fun render() {
        val server = LocalLinkTarget.server
        val on = server != null
        pair.isEnabled = on
        test.isEnabled = on
        address.isVisible = on
        address.text = if (on) getString(R.string.local_target_address, TvLink.deviceName(),
            (TvLink.localIpv4() ?: getString(R.string.local_target_no_wifi)) + ":" + server!!.port) else ""
        list.removeAllViews()
        val devices = server?.pairedDevices().orEmpty()
        forgetAll.isVisible = devices.isNotEmpty()
        if (devices.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(if (on) R.string.local_target_none else R.string.local_target_off)
                setPadding(0, dp(8), 0, 0)
            })
        }
        devices.forEach { device ->
            val id = device["id"].orEmpty()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            row.addView(TextView(this).apply {
                text = device["name"].orEmpty() + if (device["files"] == "true") " · 📁" else ""
                textSize = 16f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (device["files"] == "true") {
                row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    setText(R.string.local_target_revoke_files)
                    setOnClickListener { LocalLinkTarget.server?.revokeFiles(id); render() }
                })
            }
            row.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.local_target_forget)
                setOnClickListener { LocalLinkTarget.server?.forget(id); render() }
            })
            list.addView(row)
        }
    }

    private fun showPairingQr() {
        val server = LocalLinkTarget.server ?: return
        val pairing = server.pairingQr()
        if (pairing == null) {
            MaterialAlertDialogBuilder(this).setMessage(R.string.local_target_no_wifi)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val size = 560
        val matrix = QRCodeWriter().encode(pairing.payload, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { index ->
            if (matrix[index % size, index / size]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val image = ImageView(this).apply {
            setImageBitmap(Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888))
            adjustViewBounds = true
            maxHeight = minOf(size, (resources.displayMetrics.heightPixels * 0.45f).toInt())
        }
        val hostPort = TvLink.Pairing.parse(pairing.payload).let { "${it.host}:${it.port}" }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tv_pair_display_code, pairing.code))
            .setMessage(getString(R.string.local_target_display_address, hostPort))
            .setView(image)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        pairingDialog = dialog
        val expire = Runnable { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (pairingDialog === dialog) pairingDialog = null
            handler.removeCallbacks(expire)
            // Comme sur la TV : l'invitation reste valable au plus 60 s après fermeture.
            server.dismissPairing(pairing.sessionId)
        }
        handler.postDelayed(expire, 5 * 60 * 1000L)
    }

    /**
     * Gonzo 25/11 (« tablette injoignable ») : cet appareil se connecte à son propre serveur par
     * son adresse Wi-Fi, comme le ferait le téléphone. Si ça marche ici mais pas depuis le
     * téléphone, c'est le réseau qui bloque (VPN, réseau invité, isolation des appareils).
     */
    private fun selfTest() {
        val server = LocalLinkTarget.server ?: return
        test.isEnabled = false
        Thread {
            val invitation = server.pairingQr()
            val message = if (invitation == null) getString(R.string.local_target_no_wifi) else try {
                val address = TvLink.Pairing.parse(invitation.payload)
                val hello = TvLink.request(address.copy(secret = ""), org.json.JSONObject().put("type", "hello"))
                if (hello.optBoolean("ok")) getString(R.string.local_target_test_ok, "${address.host}:${address.port}")
                else getString(R.string.local_target_test_failed, hello.optString("error"))
            } catch (e: Exception) {
                getString(R.string.local_target_test_failed, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            } finally {
                invitation?.let { server.dismissPairing(it.sessionId) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                test.isEnabled = LocalLinkTarget.server != null
                MaterialAlertDialogBuilder(this).setTitle(R.string.local_target_test)
                    .setMessage(message).setPositiveButton(android.R.string.ok, null).show()
            }
        }.start()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

fun createLocalTargetActivityIntent(context: Context): Intent = Intent(context, LocalTargetActivity::class.java)
