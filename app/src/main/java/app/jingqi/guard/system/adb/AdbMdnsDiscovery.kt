package app.jingqi.guard.system.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.ArrayDeque
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
    private val applicationContext = context.applicationContext
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    private val mainExecutor = ContextCompat.getMainExecutor(applicationContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun pairingEndpoint(timeoutMillis: Long = DEFAULT_TIMEOUT_MS): AdbEndpoint =
        discoverFirst(TLS_PAIRING, timeoutMillis)

    suspend fun connectEndpoint(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
        excludedSocketAddresses: Set<String> = emptySet()
    ): AdbEndpoint = discoverFirst(TLS_CONNECT, timeoutMillis, excludedSocketAddresses)

    private suspend fun discoverFirst(
        serviceType: String,
        timeoutMillis: Long,
        excludedSocketAddresses: Set<String> = emptySet()
    ): AdbEndpoint =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val discoveryNetwork = physicalWifiNetwork()
                var discoveryStarted = false
                var servicesFound = 0
                var resolveFailures = 0
                var rejectedEndpoints = 0
                var skippedEndpoints = 0
                var resolutionInFlight = false
                val pendingServices = ArrayDeque<NsdServiceInfo>()
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
                            IllegalStateException(
                                timeoutDetail(
                                    serviceType = serviceType,
                                    hasWifiNetwork = discoveryNetwork != null,
                                    servicesFound = servicesFound,
                                    resolveFailures = resolveFailures,
                                    rejectedEndpoints = rejectedEndpoints,
                                    skippedEndpoints = skippedEndpoints
                                )
                            )
                        )
                    }
                }

                fun resolveNext() {
                    if (finished.get() || resolutionInFlight || pendingServices.isEmpty()) return
                    val serviceInfo = pendingServices.removeFirst()
                    resolutionInFlight = true
                    val resolveListener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            resolveFailures++
                            resolutionInFlight = false
                            resolveNext()
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host
                            if (host == null ||
                                !host.isAddressOfThisDevice(discoveryNetwork) ||
                                info.port !in 1..65535
                            ) {
                                rejectedEndpoints++
                                resolutionInFlight = false
                                resolveNext()
                                return
                            }
                            val hostAddress = host.hostAddress ?: run {
                                resolutionInFlight = false
                                resolveNext()
                                return
                            }
                            if (socketAddress(hostAddress, info.port) in excludedSocketAddresses) {
                                skippedEndpoints++
                                resolutionInFlight = false
                                resolveNext()
                                return
                            }
                            finish(
                                AdbEndpoint(
                                    serviceName = info.serviceName.orEmpty(),
                                    host = hostAddress,
                                    port = info.port,
                                    network = discoveryNetwork
                                )
                            )
                        }
                    }
                    runCatching {
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            nsdManager.resolveService(serviceInfo, mainExecutor, resolveListener)
                        } else {
                            nsdManager.resolveService(serviceInfo, resolveListener)
                        }
                    }.onFailure {
                        resolveFailures++
                        resolutionInFlight = false
                        resolveNext()
                    }
                }

                val timeout = Runnable { finish() }
                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) {
                        discoveryStarted = true
                    }

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (finished.get()) return
                        servicesFound++
                        val duplicatePending = pendingServices.any {
                            it.serviceName == serviceInfo.serviceName
                        }
                        if (!duplicatePending) pendingServices.addLast(serviceInfo)
                        resolveNext()
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && discoveryNetwork != null) {
                        nsdManager.discoverServices(
                            serviceType,
                            NsdManager.PROTOCOL_DNS_SD,
                            discoveryNetwork,
                            mainExecutor,
                            listener
                        )
                    } else {
                        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    }
                }.onFailure { finish(error = it) }
            }
        }

    private fun physicalWifiNetwork(): Network? = connectivityManager.allNetworks.firstOrNull { network ->
        connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun InetAddress.isAddressOfThisDevice(network: Network?): Boolean = runCatching {
        val networkAddresses = network?.let(connectivityManager::getLinkProperties)
            ?.linkAddresses
            ?.map { it.address }
            .orEmpty()
        if (networkAddresses.isNotEmpty()) return@runCatching this in networkAddresses
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .any { it == this }
    }.getOrDefault(false)

    private fun timeoutDetail(
        serviceType: String,
        hasWifiNetwork: Boolean,
        servicesFound: Int,
        resolveFailures: Int,
        rejectedEndpoints: Int,
        skippedEndpoints: Int
    ): String = when {
        !hasWifiNetwork -> "没有可用的物理 Wi-Fi 网络，请先连接 Wi-Fi 并开启无线调试"
        servicesFound == 0 && serviceType == TLS_PAIRING ->
            "未发现本机配对广播，请保持六位配对码窗口打开后重试"
        servicesFound == 0 -> "尚未收到本机无线调试连接广播，请保持无线调试开启后重试"
        resolveFailures > 0 -> "发现了无线调试广播，但系统地址解析失败，请关闭再开启无线调试后重试"
        rejectedEndpoints > 0 -> "发现的无线调试地址不属于当前物理 Wi-Fi，已为安全起见拒绝连接"
        skippedEndpoints > 0 -> "当前只发现了已经尝试过的无线调试端口"
        else -> "无线调试服务尚未就绪，请保持无线调试开启后重试"
    }

    private companion object {
        fun socketAddress(host: String, port: Int): String = "$host:$port"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val DEFAULT_TIMEOUT_MS = 12_000L
    }
}
