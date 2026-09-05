package app.jingqi.guard.accessibility

enum class SplashHealth(val description: String) {
    DISABLED("未开启：需要你在无障碍页确认一次"),
    NOT_CONNECTED("已授权，但服务未连接：请重新开启开屏守护"),
    UNRESPONSIVE("服务暂未响应：请检查后台限制或重新开启守护"),
    RUNNING("服务正在运行，无需连接无线调试")
}

/** Permission and a live service are different; never persist a 'running' flag. */
data class SplashRuntimeState(
    val connected: Boolean = false,
    val heartbeatAt: Long = 0L,
    val submittedActions: Long = 0L,
    val lastActionPackage: String = "",
    val lastActionAt: Long = 0L
) {
    fun health(permissionEnabled: Boolean, now: Long): SplashHealth = when {
        !permissionEnabled -> SplashHealth.DISABLED
        !connected -> SplashHealth.NOT_CONNECTED
        now < heartbeatAt || now - heartbeatAt > 8_000L -> SplashHealth.UNRESPONSIVE
        else -> SplashHealth.RUNNING
    }
}
