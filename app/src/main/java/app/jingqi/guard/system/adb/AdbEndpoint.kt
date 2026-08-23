package app.jingqi.guard.system.adb

internal data class AdbEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int
)
