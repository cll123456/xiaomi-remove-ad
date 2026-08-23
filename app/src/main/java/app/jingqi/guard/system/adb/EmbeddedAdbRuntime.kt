package app.jingqi.guard.system.adb

import android.content.Context
import android.content.SharedPreferences
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
    }

    fun hasPairedIdentity(): Boolean = prefs().getBoolean(KEY_PAIRED, false)

    fun markWaitingForCode() {
        _status.value = PairingStatus(
            PairingStatus.Phase.WAITING_FOR_CODE,
            "请在无线调试的配对码窗口中下拉通知栏，并从净启通知输入六位配对码"
        )
    }

    suspend fun pairWithThisDevice(code: String): Boolean = withContext(Dispatchers.IO) {
        require(code.matches(Regex("^[0-9]{6}$"))) { "配对码必须是 6 位数字" }
        connectionMutex.withLock {
            identityStore.ensureLoaded()
            _status.value = PairingStatus(PairingStatus.Phase.DISCOVERING, "正在确认本机配对端口…")
            val endpoint = discovery.pairingEndpoint(PAIR_DISCOVERY_TIMEOUT_MS)
            _status.value = PairingStatus(PairingStatus.Phase.PAIRING, "正在进行本机加密配对…")
            Kadb.pair(endpoint.host, endpoint.port, code, HOST_NAME)
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

        _status.value = PairingStatus(PairingStatus.Phase.CONNECTING, "正在发现本机无线调试端口…")
        val endpoint = runCatching { discovery.connectEndpoint(timeoutMillis) }
            .onFailure(::reportFailure)
            .getOrNull() ?: return false
        val candidate = Kadb.create(
            host = endpoint.host,
            port = endpoint.port,
            connectTimeout = SOCKET_CONNECT_TIMEOUT_MS,
            socketTimeout = SOCKET_READ_TIMEOUT_MS
        )
        val ready = runCatching { candidate.shell(PROBE_COMMAND).exitCode == 0 }
            .getOrDefault(false)
        if (!ready) {
            candidate.close()
            _status.value = PairingStatus(
                PairingStatus.Phase.FAILED,
                "发现了无线调试端口，但本机尚未信任当前净启密钥"
            )
            return false
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

    private fun closeClient() {
        runCatching { client?.close() }
        client = null
    }

    private fun prefs() = preferences

    private const val PREFS_NAME = "embedded_adb"
    private const val KEY_PAIRED = "paired"
    private const val KEY_PAIR_SERVICE = "pair_service"
    private const val KEY_CONNECT_SERVICE = "connect_service"
    private const val KEY_LAST_HOST = "last_host"
    private const val KEY_LAST_PORT = "last_port"
    private const val HOST_NAME = "JingQi"
    private const val PROBE_COMMAND = "echo JINGQI_READY"
    private const val PAIR_DISCOVERY_TIMEOUT_MS = 20_000L
    private const val CONNECT_DISCOVERY_TIMEOUT_MS = 12_000L
    private const val SOCKET_CONNECT_TIMEOUT_MS = 4_000
    private const val SOCKET_READ_TIMEOUT_MS = 8_000
}
