package app.jingqi.guard.system.adb

import android.net.Network

internal data class AdbEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
    val network: Network? = null
) {
    val socketAddress: String
        get() = "$host:$port"
}
