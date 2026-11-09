package com.mockroute.app

import android.app.Application
import com.mockroute.app.data.SettingsRepository
import com.mockroute.app.mock.StartupCleaner

class MockRouteApp : Application() {
    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        // A fresh process never has a running simulation (the service is START_NOT_STICKY), so any test
        // provider still registered is a leftover from a killed process: remove it so real GPS works again.
        StartupCleaner.clean(this, reason = "app start")
    }
}
