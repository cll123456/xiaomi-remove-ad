package dev.hyperadguard

import android.app.Application
import dev.hyperadguard.data.AppState
import dev.hyperadguard.work.RecheckScheduler

class HyperAdGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.initialize(this)
        RecheckScheduler.schedule(this)
    }
}
