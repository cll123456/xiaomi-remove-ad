package app.jingqi.guard.accessibility

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SplashRuntime {
    private val mutableState = MutableStateFlow(SplashRuntimeState())
    val state = mutableState.asStateFlow()

    fun permissionEnabled(context: Context): Boolean =
        context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == context.packageName &&
                    it.resolveInfo.serviceInfo.name == SplashSkipAccessibilityService::class.java.name
            }

    fun health(context: Context): SplashHealth =
        state.value.health(permissionEnabled(context), SystemClock.elapsedRealtime())

    fun connected(context: Context) {
        val prefs = context.getSharedPreferences("splash_skip", Context.MODE_PRIVATE)
        mutableState.value = SplashRuntimeState(
            connected = true,
            heartbeatAt = SystemClock.elapsedRealtime(),
            submittedActions = prefs.getLong("total", 0L),
            lastActionPackage = prefs.getString("last_package", "").orEmpty(),
            lastActionAt = prefs.getLong("last_time", 0L)
        )
    }

    fun heartbeat() {
        val now = SystemClock.elapsedRealtime()
        if (now - state.value.heartbeatAt >= 2_000L) {
            mutableState.update { it.copy(heartbeatAt = now) }
        }
    }

    fun disconnected() = mutableState.update { it.copy(connected = false) }

    fun recordAction(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences("splash_skip", Context.MODE_PRIVATE)
        val total = prefs.getLong("total", 0L) + 1L
        val now = System.currentTimeMillis()
        prefs.edit().putString("last_package", packageName).putLong("last_time", now)
            .putLong("total", total).apply()
        mutableState.update {
            it.copy(submittedActions = total, lastActionPackage = packageName, lastActionAt = now)
        }
    }
}
