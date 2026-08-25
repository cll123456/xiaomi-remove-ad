package app.jingqi.guard.system.adb

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.shell.AdbShellResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PairingStatus(
    val phase: Phase = Phase.IDLE,
    val detail: String = ""
) {
    enum class Phase { IDLE, WAITING_FOR_CODE, DISCOVERING, PAIRING, CONNECTING, READY, FAILED }
}

/**
 * Single in-process ADB host. Its only command entry point accepts a closed set
 * of typed operations, never text received from UI, rules, feedback or network.
 */
internal object EmbeddedAdbRuntime {
    private lateinit var preferences: SharedPreferences
    private lateinit var identityStore: AdbIdentityStore
    private lateinit var discovery: AdbMdnsDiscovery
    private lateinit var connectivityManager: ConnectivityManager
    private val connectionMutex = Mutex()
    private var client: Kadb? = null
    private val _status = MutableStateFlow(PairingStatus())
    val status: StateFlow<PairingStatus> = _status.asStateFlow()

    fun initialize(context: Context) {
        if (::preferences.isInitialized) return
        val applicationContext = context.applicationContext
        preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        identityStore = AdbIdentityStore(applicationContext)
        discovery = AdbMdnsDiscovery(applicationContext)
        connectivityManager = applicationContext.getSystemService(ConnectivityManager::class.java)
    }

    fun hasPairedIdentity(): Boolean = prefs().getBoolean(KEY_PAIRED, false)

    fun markWaitingForCode() {
        _status.value = PairingStatus(
            PairingStatus.Phase.WAITING_FOR_CODE,
            "请在无线调试中打开“使用配对码配对设备”，并保持六位码窗口在前台"
        )
    }

    fun activePairingStatus(): PairingStatus? = status.value.takeIf {
        it.phase in setOf(
            PairingStatus.Phase.WAITING_FOR_CODE,
            PairingStatus.Phase.DISCOVERING,
            PairingStatus.Phase.PAIRING,
            PairingStatus.Phase.CONNECTING
        )
    }

    fun cancelPairing(detail: String = "本次无线调试配对已取消") {
        _status.value = PairingStatus(PairingStatus.Phase.FAILED, detail)
    }

    suspend fun pairWithThisDevice(code: String): Boolean = withContext(Dispatchers.IO) {
        require(code.matches(Regex("^[0-9]{6}$"))) { "配对码必须是 6 位数字" }
        connectionMutex.withLock {
            identityStore.ensureLoaded()
            _status.value = PairingStatus(PairingStatus.Phase.DISCOVERING, "正在确认本机配对端口…")
            val endpoint = discovery.pairingEndpoint(PAIR_DISCOVERY_TIMEOUT_MS)
            _status.value = PairingStatus(PairingStatus.Phase.PAIRING, "正在进行本机加密配对…")
            withEndpointNetwork(endpoint.network) {
                Kadb.pair(endpoint.host, endpoint.port, code, HOST_NAME)
            }
            prefs().edit()
                .putBoolean(KEY_PAIRED, true)
                .putString(KEY_PAIR_SERVICE, endpoint.serviceName)
                .apply()
            closeClient()
            _status.value = PairingStatus(PairingStatus.Phase.CONNECTING, "配对成功，正在连接专家权限…")
            connectLocked(CONNECT_DISCOVERY_TIMEOUT_MS)
        }
    }

    suspend fun refreshConnection(timeoutMillis: Long = CONNECT_DISCOVERY_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            if (!hasPairedIdentity()) return@withContext false
            connectionMutex.withLock {
                identityStore.ensureLoaded()
                connectLocked(timeoutMillis)
            }
        }

    suspend fun executeKnown(operation: KnownAdbOperation): AdbShellResponse = withContext(Dispatchers.IO) {
        connectionMutex.withLock {
            identityStore.ensureLoaded()
            val active = client?.takeIf { it.connectionCheck() }
                ?: run {
                    check(connectLocked(CONNECT_DISCOVERY_TIMEOUT_MS)) {
                        "无线调试未连接，请在系统设置中开启后重试"
                    }
                    requireNotNull(client)
                }
            val command = AdbCommandCatalog.commandFor(operation)
            runCatching { active.shell(command) }
                .onFailure { closeClient() }
                .getOrThrow()
        }
    }

    fun reportFailure(error: Throwable) {
        _status.value = PairingStatus(
            PairingStatus.Phase.FAILED,
            error.message ?: error.javaClass.simpleName
        )
    }

    private suspend fun connectLocked(timeoutMillis: Long): Boolean {
        client?.let { current ->
            val stillReady = runCatching {
                current.shell(PROBE_COMMAND).exitCode == 0
            }.getOrDefault(false)
            if (stillReady) {
                _status.value = PairingStatus(PairingStatus.Phase.READY, "净启内置专家权限已连接")
                return true
            }
            closeClient()
        }

        val attemptedSocketAddresses = linkedSetOf<String>()
        var lastConnectionError: Throwable? = null
        var lastProbeExitCode: Int? = null
        for (attemptIndex in 0 until MAX_CONNECT_ENDPOINTS) {
            _status.value = PairingStatus(
                PairingStatus.Phase.CONNECTING,
                if (attemptIndex == 0) {
                    "正在发现本机无线调试端口…"
                } else {
                    "旧端口不可用，正在尝试下一个本机无线调试端口…"
                }
            )
            val discoveryTimeout = if (attemptIndex == 0) {
                timeoutMillis
            } else {
                minOf(timeoutMillis, RETRY_DISCOVERY_TIMEOUT_MS)
            }
            val endpoint = try {
                discovery.connectEndpoint(discoveryTimeout, attemptedSocketAddresses)
            } catch (error: Throwable) {
                if (lastConnectionError == null && lastProbeExitCode == null) reportFailure(error)
                break
            }
            attemptedSocketAddresses += endpoint.socketAddress

            val connectionAttempt = runCatching {
                withEndpointNetwork(endpoint.network) {
                    val candidate = Kadb.create(
                        host = endpoint.host,
                        port = endpoint.port,
                        connectTimeout = SOCKET_CONNECT_TIMEOUT_MS,
                        socketTimeout = SOCKET_READ_TIMEOUT_MS
                    )
                    try {
                        candidate to candidate.shell(PROBE_COMMAND)
                    } catch (error: Throwable) {
                        candidate.close()
                        throw error
                    }
                }
            }
            if (connectionAttempt.isFailure) {
                lastConnectionError = connectionAttempt.exceptionOrNull()
                Log.w(
                    TAG,
                    "Embedded ADB candidate ${attemptIndex + 1} failed",
                    lastConnectionError
                )
                continue
            }

            val (candidate, probe) = connectionAttempt.getOrThrow()
            if (probe.exitCode != 0) {
                lastProbeExitCode = probe.exitCode
                candidate.close()
                continue
            }
            client = candidate
            prefs().edit()
                .putString(KEY_CONNECT_SERVICE, endpoint.serviceName)
                .putString(KEY_LAST_HOST, endpoint.host)
                .putInt(KEY_LAST_PORT, endpoint.port)
                .apply()
            _status.value = PairingStatus(PairingStatus.Phase.READY, "净启内置专家权限已连接")
            return true
        }

        _status.value = when {
            lastConnectionError != null -> PairingStatus(
                PairingStatus.Phase.FAILED,
                "本机无线调试候选端口均不可用：${connectionFailureDetail(lastConnectionError)}"
            )
            lastProbeExitCode != null -> PairingStatus(
                PairingStatus.Phase.FAILED,
                "无线调试已连接，但权限探测被系统拒绝（退出码 $lastProbeExitCode）"
            )
            else -> _status.value
        }
        return false
    }

    private fun connectionFailureDetail(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val type = root.javaClass.simpleName.ifBlank { "未知异常" }
        val message = root.message
            ?.replace(Regex("[0-9]{6}"), "******")
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(120)
            .orEmpty()
        return if (message.isBlank()) type else "$type：$message"
    }

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    private fun prefs() = preferences

    /** Kadb owns its sockets, so bind the process only while each socket is created. */
    private suspend fun <T> withEndpointNetwork(network: Network?, block: suspend () -> T): T {
        if (network == null) return block()
        val previous = connectivityManager.boundNetworkForProcess
        if (previous == network) return block()
        check(connectivityManager.bindProcessToNetwork(network)) {
            "无法将本机专家连接绑定到物理 Wi-Fi"
        }
        return try {
            block()
        } finally {
            connectivityManager.bindProcessToNetwork(previous)
        }
    }

    private const val PREFS_NAME = "embedded_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_PAIR_SERVICE = "pair_service"
    private const val KEY_CONNECT_SERVICE = "connect_service"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_LAST_PORT = "last_port"
    private const val HOST_NAME = "JingQi"
    private const val PROBE_COMMAND = "echo JINGQI_READY"
    private const val PAIR_DISCOVERY_TIMEOUT_MS = 25_000L
    private const val CONNECT_DISCOVERY_TIMEOUT_MS = 30_000L
    private const val RETRY_DISCOVERY_TIMEOUT_MS = 8_000L
    private const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
    private const val SOCKET_READ_TIMEOUT_MS = 8_000
    private const val MAX_CONNECT_ENDPOINTS = 4
    private const val TAG = "JingQiEmbeddedAdb"
}
