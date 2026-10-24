package com.example.util.simpletimetracker

import android.app.Application
import android.os.StrictMode
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import timber.log.Timber.DebugTree

@HiltAndroidApp
class TimeTrackerApp : Application() {

    @javax.inject.Inject lateinit var cloudBridge: com.example.util.simpletimetracker.cloud.NativeCloudBridge

    override fun onCreate() {
        super.onCreate()
        initLog()
        initLibraries()
        initStrictMode()
        cloudBridge.start()
        com.example.util.simpletimetracker.cloud.PhoneCloudRetryJobService.ensurePeriodic(this)
        com.example.util.simpletimetracker.lan.LanReceiverService.restoreIfEnabled(this)
    }

    private fun initLog() {
        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }
    }

    private fun initLibraries() {
        val config = BundledEmojiCompatConfig(applicationContext)
            .setReplaceAll(true)
        EmojiCompat.init(config)
    }

    private fun initStrictMode() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
    }
}