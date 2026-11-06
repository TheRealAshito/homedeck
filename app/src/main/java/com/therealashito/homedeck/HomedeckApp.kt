package com.therealashito.homedeck

import android.app.Application
import com.therealashito.homedeck.data.SettingsStore
import com.therealashito.homedeck.debug.AppLog
import com.therealashito.homedeck.debug.CrashHandler

/** Boots the local logging and crash capture before any activity starts. */
class HomedeckApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashHandler.install(this)
        val debug = SettingsStore(this).debugMode
        AppLog.i("App", "Homedeck started (debug=$debug)")
    }
}
