package app.jingqi.guard.system.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Discovers only ADB endpoints advertised by this Android device.
 *
 * Matching the resolved address against a local network interface prevents a
 * nearby phone on the same Wi-Fi from accidentally becoming the pairing target.
 */
internal class AdbMdnsDiscovery(context: Context) {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun pairingEndpoint(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): AdbEndpoint =
        discoverFirst(TLS_PAIRING, timeoutMillis)

    suspend fun connectEndpoint(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): AdbEndpoint =
        discoverFirst(TLS_CONNECT, timeoutMillis)

    private suspend fun discoverFirst(serviceType: String, timeoutMillis: Long): AdbEndpoint =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                var discoveryStarted = false
                lateinit var listener: NsdManager.DiscoveryListener

                fun stopDiscovery() {
                    if (discoveryStarted) runCatching { nsdManager.stopServiceDiscovery(listener) }
                }

                fun finish(endpoint: AdbEndpoint? = null, error: Throwable? = null) {
                    if (!finished.compareAndSet(false, true)) return
                    mainHandler.removeCallbacksAndMessages(continuation)
                    stopDiscovery()
                    when {
                        error != null -> continuation.resumeWithException(error)
                        endpoint != null -> continuation.resume(endpoint)
                        else -> continuation.resumeWithException(
                            IllegalStateException("未发现本机无线调试服务，请确认无线调试和配对码窗口仍保持开启")
                        )
                    }
                }

                val timeout = Runnable { finish() }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        discoveryStarted = true
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (finished.get()) return
                        runCatching {
                            @Suppress("DEPRECATION")
                            nsdManager.resolveService(
                                serviceInfo,
                                object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                                    override fun onServiceResolved(info: NsdServiceInfo) {
                                        val host = info.host ?: return
                                        if (!host.isAddressOfThisDevice() || info.port !in 1..65535) return
                                        finish(
                                            AdbEndpoint(
                                                serviceName = info.serviceName.orEmpty(),
                                                host = host.hostAddress ?: return,
                                                port = info.port
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        discoveryStarted = false
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(error = IllegalStateException("无线调试发现启动失败：$errorCode"))
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        discoveryStarted = false
                    }
                }

                continuation.invokeOnCancellation {
                    mainHandler.post {
                        if (finished.compareAndSet(false, true)) stopDiscovery()
                    }
                }
                mainHandler.postAtTime(timeout, continuation, android.os.SystemClock.uptimeMillis() + timeoutMillis)
                runCatching {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure { finish(error = it) }
            }
        }

    private fun InetAddress.isAddressOfThisDevice(): Boolean = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .any { it == this }
    }.getOrDefault(false)

    private companion object {
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val DEFAULT_TIMEOUT_MS = 12_000L
    }
}
