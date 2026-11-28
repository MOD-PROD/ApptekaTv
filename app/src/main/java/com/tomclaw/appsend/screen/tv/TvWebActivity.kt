package com.tomclaw.appsend.screen.tv

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.google.gson.Gson
import com.tomclaw.appsend.R
import com.tomclaw.appsend.appComponent
import com.tomclaw.appsend.tvlink.TvLink
import com.tomclaw.appsend.target.TvRemoteState
import org.json.JSONObject
import com.tomclaw.appsend.tvlink.TvLinkSender
import com.tomclaw.appsend.util.isTvDevice
import com.tomclaw.appsend.util.isRealTvDevice
import com.tomclaw.appsend.util.isTvDisplayForced
import com.tomclaw.appsend.util.setTvDisplayForced
import com.tomclaw.appsend.categories.Category
import com.tomclaw.appsend.core.StoreApi
import com.tomclaw.appsend.download.ApkStorage
import com.tomclaw.appsend.download.COMPLETED
import com.tomclaw.appsend.download.DownloadLauncher
import com.tomclaw.appsend.download.DownloadManager
import com.tomclaw.appsend.download.DownloadParams
import com.tomclaw.appsend.dto.AppEntity
import com.tomclaw.appsend.install.AppInstaller
import com.tomclaw.appsend.install.InstallRequest
import com.tomclaw.appsend.install.InstallStatus
import com.tomclaw.appsend.screen.details.api.Details
import com.tomclaw.appsend.screen.details.createDetailsActivityIntent
import com.tomclaw.appsend.util.PackageObserver
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Point d'entrée Android TV : interface HTML (liste + fiche app) branchée
 * sur les vrais services natifs d'Appteka (API connectée, téléchargement,
 * installation, suivi des paquets installés).
 *
 * NOTE GROK: This is a STUB placeholder - full file must be restored from
 * reference branch eb5828e0 + openClassic routes for files/distro/installed.
 * See Drive: TvWebActivity-header-shortcuts.kt
 */
class TvWebActivity : AppCompatActivity() {
    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, TvWebActivity::class.java)
        }
    }
}
