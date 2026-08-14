package com.example.meshsocial

import android.app.Application
import android.util.Log
import timber.log.Timber

class NearFeedApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        container = AppContainer(this)
    }

    /** Logs to logcat always; on release, info/debug dropped, warnings/errors kept. */
    private class ReleaseTree : Timber.DebugTree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean =
            priority >= Log.WARN
    }
}
