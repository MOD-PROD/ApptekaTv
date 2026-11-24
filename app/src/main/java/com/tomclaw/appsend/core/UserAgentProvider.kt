package com.tomclaw.appsend.core

import android.os.Build
import java.util.Locale

interface UserAgentProvider {

    fun getUserAgent(): String

}

class UserAgentProviderImpl(
    private val appInfoProvider: AppInfoProvider,
    private val locale: Locale
) : UserAgentProvider {

    override fun getUserAgent(): String {
        return String.format(
            Locale.ENGLISH,
            "Appteka/%s.%d (%s; %s; sdk:%d; %s; %s-%s)",
            appInfoProvider.getVersionName(),
            STORE_CLIENT_VERSION_CODE, // ATV: see Config
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.SDK_INT,
            appInfoProvider.getPackageName(),
            locale.language,
            locale.country
        )
    }

}
