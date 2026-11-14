package com.tomclaw.appsend.screen.installed.api

import com.google.gson.annotations.SerializedName
import com.tomclaw.appsend.dto.DeviceInfo
import com.tomclaw.appsend.util.GsonModel

@GsonModel
data class CheckUpdatesRequest(
    @SerializedName(value = "locale")
    private val locale: String,
    @SerializedName(value = "apps")
    private val apps: Map<String, Long>,
    @SerializedName(value = "certs")
    private val certs: Map<String, String>,
    @SerializedName(value = "device")
    // ATV : null en mode 📺 (applis de la TV) : on n'envoie pas l'appareil du téléphone.
    private val device: DeviceInfo?,
)