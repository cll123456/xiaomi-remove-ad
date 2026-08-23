package app.jingqi.guard

import android.app.Application
import app.jingqi.guard.data.AppState
import app.jingqi.guard.system.adb.EmbeddedAdbRuntime
import app.jingqi.guard.work.RecheckScheduler

class JingQiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.initialize(this)
        EmbeddedAdbRuntime.initialize(this)
        RecheckScheduler.schedule(this)
    }
}
