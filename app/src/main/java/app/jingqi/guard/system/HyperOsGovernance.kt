package app.jingqi.guard.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.jingqi.guard.system.adb.AdbPairingService
import app.jingqi.guard.system.adb.AdbPrivilegedGateway
import app.jingqi.guard.system.adb.EmbeddedAdbRuntime
import app.jingqi.guard.system.adb.PairingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

data class GovernanceItem(val title: String, val detail: String, val success: Boolean)

data class GovernanceState(
    val phase: Phase = Phase.CHECKING,
    val title: String = "正在检查专家权限…",
    val detail: String = "",
    val results: List<GovernanceItem> = emptyList(),
    val canRestore: Boolean = false,
    val awaitingPairingCode: Boolean = false
) {
    enum class Phase {
        CHECKING,
        NOT_PAIRED,
        PAIRING,
        DISCONNECTED,
        READY,
        WORKING,
        DONE,
        ERROR
    }
}

/** Coordinates the embedded, locally paired ADB bridge and its closed command surface. */
class HyperOsGovernance(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gateway: PrivilegedGateway = AdbPrivilegedGateway()
    private val _state = MutableStateFlow(GovernanceState())
    val state: StateFlow<GovernanceState> = _state.asStateFlow()
    private var refreshJob: Job? = null

    private enum class Action { APPLY, RESTORE }

    private data class SettingPlan(
        val id: Int,
        val title: String,
        val targetValue: String,
        val snapshotKey: String
    )

    private val settingPlans = listOf(
        SettingPlan(
            PrivilegedContract.SETTING_BROWSER_RECOMMEND,
            "浏览器推荐入口",
            "0",
            "setting_browser_recommend"
        ),
        SettingPlan(
            PrivilegedContract.SETTING_PREDOWNLOAD_CLOUD,
            "系统云预下载",
            "0",
            "setting_predownload_cloud"
        ),
        SettingPlan(
            PrivilegedContract.SETTING_PREDOWNLOAD_TASKS,
            "预下载任务列表",
            "[]",
            "setting_predownload_tasks"
        ),
        SettingPlan(
            PrivilegedContract.SETTING_MISHARE_ADS,
            "米享广告",
            "0",
            "setting_mishare_ads"
        )
    )

    fun attach() {
        EmbeddedAdbRuntime.initialize(context)
        _state.value = offlineState()
        scope.launch { EmbeddedAdbRuntime.status.collect(::onPairingStatus) }
    }

    /** Opening the daily dashboard must never discover/connect ADB or open Settings. */
    private fun offlineState() = GovernanceState(
        phase = if (EmbeddedAdbRuntime.hasPairedIdentity()) GovernanceState.Phase.DISCONNECTED
            else GovernanceState.Phase.NOT_PAIRED,
        title = "系统治理是可选操作，日常守护不需要连接",
        detail = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L).takeIf { it > 0 }?.let {
            "上次完整验证：${DateFormat.getDateTimeInstance().format(Date(it))}。当前未联网复查，系统更新可能恢复设置。"
        } ?: "第三方应用开屏由开屏守护处理。只有执行或恢复系统治理时，才需要开启无线调试。",
        canRestore = hasSnapshot()
    )

    fun detach() {
        refreshJob?.cancel()
        scope.cancel()
    }

    fun refresh() {
        EmbeddedAdbRuntime.activePairingStatus()?.let {
            onPairingStatus(it)
            return
        }
        if (!EmbeddedAdbRuntime.hasPairedIdentity()) {
            _state.value = GovernanceState(
                phase = GovernanceState.Phase.NOT_PAIRED,
                title = "需要一次本机无线调试配对",
                detail = "配对由 Android 系统显示六位码并由你确认；不需要安装第二个应用。",
                canRestore = hasSnapshot()
            )
            return
        }
        if (_state.value.phase == GovernanceState.Phase.WORKING) return
        _state.value = GovernanceState(
            phase = GovernanceState.Phase.CHECKING,
            title = "正在连接净启内置专家权限…",
            detail = "请确认开发者选项中的无线调试已经开启。",
            canRestore = hasSnapshot()
        )
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val ready = EmbeddedAdbRuntime.refreshConnection(REFRESH_TIMEOUT_MS)
            if (_state.value.phase == GovernanceState.Phase.WORKING) return@launch
            _state.value = if (ready) readyState() else GovernanceState(
                phase = GovernanceState.Phase.DISCONNECTED,
                title = "无线调试当前未连接",
                detail = EmbeddedAdbRuntime.status.value.detail.ifBlank {
                    "需要系统治理时才开启无线调试，然后点“检查已有配对连接”；日常开屏守护不受影响。"
                },
                canRestore = hasSnapshot()
            )
        }
    }

    fun startPairing() {
        AdbPairingService.start(context)
        openWirelessDebugging()
    }

    fun cancelPairing() = AdbPairingService.cancel(context)

    fun openWirelessDebugging() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun apply() = request(Action.APPLY)

    fun restore() = request(Action.RESTORE)

    private fun request(action: Action) {
        if (_state.value.phase == GovernanceState.Phase.WORKING) return
        if (_state.value.phase !in setOf(
                GovernanceState.Phase.READY,
                GovernanceState.Phase.DONE,
                GovernanceState.Phase.ERROR
            )
        ) {
            refresh()
            return
        }
        _state.value = GovernanceState(
            phase = GovernanceState.Phase.WORKING,
            title = if (action == Action.APPLY) "正在执行系统广告治理…" else "正在恢复治理前状态…",
            detail = "只执行代码内登记的固定操作，请保持无线调试开启。",
            canRestore = hasSnapshot()
        )
        scope.launch { runAction(action) }
    }

    private suspend fun runAction(action: Action) = withContext(Dispatchers.IO) {
        try {
            val results = when (action) {
                Action.APPLY -> apply(gateway)
                Action.RESTORE -> restore(gateway)
            }
            val successful = results.all { it.success }
            withContext(Dispatchers.Main) {
                _state.value = GovernanceState(
                    phase = if (successful) GovernanceState.Phase.DONE else GovernanceState.Phase.ERROR,
                    title = when {
                        !successful -> "部分操作未完成"
                        action == Action.APPLY -> "系统广告治理完成"
                        else -> "治理前设置已恢复"
                    },
                    detail = if (successful) {
                        "所有项目已逐项回读验证。无线调试现在可以关闭。"
                    } else {
                        "未成功的项目不会被标记为完成，请查看逐项结果。"
                    },
                    results = results,
                    canRestore = hasSnapshot()
                )
            }
        } catch (error: Throwable) {
            withContext(Dispatchers.Main) { fail(error) }
        }
    }

    private suspend fun apply(privileged: PrivilegedGateway): List<GovernanceItem> {
        prefs.edit().remove(KEY_LAST_VERIFIED_AT).apply()
        if (!hasSnapshot()) createSnapshot(privileged)
        val msaRemoved = privileged.removeKnownPackageForCurrentUser(PrivilegedContract.PACKAGE_MSA)
        val results = mutableListOf(
            GovernanceItem(
                title = "小米系统广告服务（MSA）",
                detail = if (msaRemoved) "已从当前用户移除，系统分区原包保留" else "移除失败",
                success = msaRemoved
            )
        )
        settingPlans.forEach { plan ->
            val written = privileged.writeKnownSetting(plan.id, plan.targetValue, false)
            val actual = privileged.readKnownSetting(plan.id)
            val verified = written && actual == plan.targetValue
            results += GovernanceItem(
                title = plan.title,
                detail = if (verified) "已设为 ${plan.targetValue}" else "当前值：${actual ?: "未设置"}",
                success = verified
            )
        }
        if (results.all { it.success }) {
            prefs.edit().putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis()).apply()
        }
        return results
    }

    @SuppressLint("ApplySharedPref")
    private suspend fun createSnapshot(privileged: PrivilegedGateway) {
        val editor = prefs.edit()
            .putInt(KEY_SNAPSHOT_SCHEMA, SNAPSHOT_SCHEMA)
            .putLong(KEY_SNAPSHOT_CREATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_MSA_INSTALLED, privileged.isKnownPackageInstalled(PrivilegedContract.PACKAGE_MSA))
        settingPlans.forEach { plan ->
            editor.putString(plan.snapshotKey, privileged.readKnownSetting(plan.id) ?: NULL_SNAPSHOT)
        }
        check(editor.putBoolean(KEY_SNAPSHOT, true).commit()) {
            "无法持久化治理快照，未执行系统修改"
        }
    }

    private suspend fun restore(privileged: PrivilegedGateway): List<GovernanceItem> {
        if (!hasSnapshot()) return listOf(GovernanceItem("恢复", "没有可恢复的治理快照", false))
        prefs.edit().remove(KEY_LAST_VERIFIED_AT).apply()
        val msaShouldExist = prefs.getBoolean(KEY_MSA_INSTALLED, true)
        val msaSuccess = if (msaShouldExist) {
            privileged.restoreKnownPackageForCurrentUser(PrivilegedContract.PACKAGE_MSA)
        } else {
            privileged.removeKnownPackageForCurrentUser(PrivilegedContract.PACKAGE_MSA)
        }
        val results = mutableListOf(
            GovernanceItem(
                title = "小米系统广告服务（MSA）",
                detail = if (msaSuccess) "已恢复治理前状态" else "恢复失败",
                success = msaSuccess
            )
        )
        settingPlans.forEach { plan ->
            val original = prefs.getString(plan.snapshotKey, NULL_SNAPSHOT).fromSnapshot()
            val restored = privileged.writeKnownSetting(plan.id, original, original == null)
            val actual = privileged.readKnownSetting(plan.id)
            val verified = restored && actual == original
            results += GovernanceItem(
                title = plan.title,
                detail = if (verified) "已恢复为 ${original ?: "未设置"}" else "当前值：${actual ?: "未设置"}",
                success = verified
            )
        }
        if (results.all { it.success }) prefs.edit().clear().apply()
        return results
    }

    private fun onPairingStatus(status: PairingStatus) {
        if (_state.value.phase == GovernanceState.Phase.WORKING) return
        when (status.phase) {
            PairingStatus.Phase.IDLE -> Unit
            PairingStatus.Phase.WAITING_FOR_CODE,
            PairingStatus.Phase.DISCOVERING,
            PairingStatus.Phase.PAIRING,
            PairingStatus.Phase.CONNECTING -> {
                _state.value = GovernanceState(
                    phase = GovernanceState.Phase.PAIRING,
                    title = when (status.phase) {
                        PairingStatus.Phase.WAITING_FOR_CODE -> "等待系统显示六位配对码"
                        PairingStatus.Phase.DISCOVERING -> "正在确认本机配对端口"
                        PairingStatus.Phase.PAIRING -> "正在进行加密配对"
                        else -> "正在连接专家权限"
                    },
                    detail = status.detail,
                    canRestore = hasSnapshot(),
                    awaitingPairingCode = status.phase == PairingStatus.Phase.WAITING_FOR_CODE
                )
            }
            PairingStatus.Phase.READY -> _state.value = readyState()
            PairingStatus.Phase.FAILED -> {
                _state.value = GovernanceState(
                    phase = if (EmbeddedAdbRuntime.hasPairedIdentity()) {
                        GovernanceState.Phase.DISCONNECTED
                    } else GovernanceState.Phase.NOT_PAIRED,
                    title = "专家权限尚未连接",
                    detail = status.detail,
                    canRestore = hasSnapshot()
                )
            }
        }
    }

    private fun readyState() = GovernanceState(
        phase = GovernanceState.Phase.READY,
        title = "净启内置专家权限已就绪",
        detail = "不依赖 Shizuku；将治理 MSA 和四项固定澎湃 OS 推荐设置。",
        canRestore = hasSnapshot()
    )

    private fun String?.fromSnapshot(): String? = this?.takeUnless { it == NULL_SNAPSHOT }

    private fun hasSnapshot(): Boolean =
        prefs.getBoolean(KEY_SNAPSHOT, false) && prefs.getInt(KEY_SNAPSHOT_SCHEMA, 0) == SNAPSHOT_SCHEMA

    private fun fail(error: Throwable) {
        _state.value = GovernanceState(
            phase = GovernanceState.Phase.ERROR,
            title = "系统治理未完成",
            detail = error.message ?: error.javaClass.simpleName,
            canRestore = hasSnapshot()
        )
    }

    private companion object {
        const val SNAPSHOT_SCHEMA = 2
        const val PREFS_NAME = "hyperos_governance"
        const val NULL_SNAPSHOT = "__JINGQI_SNAPSHOT_NULL__"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_SNAPSHOT_SCHEMA = "snapshot_schema"
        const val KEY_SNAPSHOT_CREATED_AT = "snapshot_created_at"
        // Old versions wrote last_applied_at even for partial failures; do not migrate it as success.
        const val KEY_LAST_VERIFIED_AT = "last_fully_verified_at"
        const val KEY_MSA_INSTALLED = "msa_installed"
        const val REFRESH_TIMEOUT_MS = 30_000L
    }
}
