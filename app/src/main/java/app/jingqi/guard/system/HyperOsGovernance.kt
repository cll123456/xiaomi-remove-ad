package app.jingqi.guard.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class GovernanceItem(val title: String, val detail: String, val success: Boolean)

data class GovernanceState(
    val phase: Phase = Phase.CHECKING,
    val title: String = "正在检查专家权限…",
    val detail: String = "",
    val results: List<GovernanceItem> = emptyList(),
    val canRestore: Boolean = false
) {
    enum class Phase {
        CHECKING,
        SHIZUKU_MISSING,
        SHIZUKU_STOPPED,
        PERMISSION,
        READY,
        WORKING,
        DONE,
        ERROR
    }
}

/**
 * Coordinates the currently supported expert bridge and a deliberately closed
 * privileged API. Shizuku is a temporary 0.8.x bootstrap while the in-app ADB
 * pairing engine is being built; no downloaded rule can invoke this API.
 */
class HyperOsGovernance(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(GovernanceState())
    val state: StateFlow<GovernanceState> = _state.asStateFlow()
    private var service: IPrivilegedService? = null
    private var pending: Action? = null

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

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
            bindAndRun()
        } else if (requestCode == REQUEST_CODE) {
            _state.value = GovernanceState(
                phase = GovernanceState.Phase.PERMISSION,
                title = "需要专家权限授权",
                detail = "你拒绝了权限。净启只会执行界面中列出的固定治理操作。",
                canRestore = hasSnapshot()
            )
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IPrivilegedService.Stub.asInterface(binder)
            val action = pending ?: return
            pending = null
            scope.launch { runAction(action) }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }
    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, PrivilegedService::class.java.name)
    ).daemon(false).processNameSuffix("governance").debuggable(false).version(USER_SERVICE_VERSION)

    fun attach() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    fun detach() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) }
        service = null
        scope.cancel()
    }

    fun refresh() {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            _state.value = GovernanceState(
                phase = GovernanceState.Phase.SHIZUKU_MISSING,
                title = "专家桥接尚未配置",
                detail = "0.8 内测版暂时兼容 Shizuku；后续版本会改为净启内置无线调试配对。",
                canRestore = hasSnapshot()
            )
            return
        }
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            _state.value = GovernanceState(
                phase = GovernanceState.Phase.SHIZUKU_STOPPED,
                title = "专家桥接尚未运行",
                detail = "当前内测版请先启动 Shizuku。净启不会在后台偷偷开启调试权限。",
                canRestore = hasSnapshot()
            )
            return
        }
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        _state.value = if (granted) {
            GovernanceState(
                phase = GovernanceState.Phase.READY,
                title = "专家治理已就绪",
                detail = "将治理 MSA 和四项经过白名单限定的澎湃 OS 推荐设置。",
                canRestore = hasSnapshot()
            )
        } else {
            GovernanceState(
                phase = GovernanceState.Phase.PERMISSION,
                title = "需要一次专家权限授权",
                detail = "授权后只允许执行净启内置的固定治理操作，不接受远程命令。",
                canRestore = hasSnapshot()
            )
        }
    }

    fun apply() = request(Action.APPLY)

    fun restore() = request(Action.RESTORE)

    fun openExpertBridge() {
        when (_state.value.phase) {
            GovernanceState.Phase.SHIZUKU_MISSING -> openShizukuDownload()
            GovernanceState.Phase.SHIZUKU_STOPPED -> openShizuku()
            else -> Unit
        }
    }

    private fun request(action: Action) {
        when (_state.value.phase) {
            GovernanceState.Phase.SHIZUKU_MISSING,
            GovernanceState.Phase.SHIZUKU_STOPPED -> {
                pending = null
                return
            }
            GovernanceState.Phase.CHECKING,
            GovernanceState.Phase.WORKING -> return
            else -> Unit
        }
        pending = action
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        if (granted) bindAndRun()
        else try {
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun bindAndRun() {
        _state.value = GovernanceState(
            phase = GovernanceState.Phase.WORKING,
            title = "正在连接受限系统服务…",
            detail = "请保持当前页面。",
            canRestore = hasSnapshot()
        )
        val connected = service
        if (connected != null) {
            val action = pending ?: return
            pending = null
            scope.launch { runAction(action) }
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private suspend fun runAction(action: Action) = withContext(Dispatchers.IO) {
        try {
            val privileged = requireNotNull(service)
            val results = when (action) {
                Action.APPLY -> apply(privileged)
                Action.RESTORE -> restore(privileged)
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
                        "所有项目已逐项回读验证。"
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

    private fun apply(privileged: IPrivilegedService): List<GovernanceItem> {
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
            val actual = readSetting(privileged, plan.id)
            val verified = written && actual == plan.targetValue
            results += GovernanceItem(
                title = plan.title,
                detail = if (verified) "已设为 ${plan.targetValue}" else "当前值：${actual ?: "未设置"}",
                success = verified
            )
        }
        prefs.edit().putLong(KEY_LAST_APPLIED_AT, System.currentTimeMillis()).apply()
        return results
    }

    @SuppressLint("ApplySharedPref")
    private fun createSnapshot(privileged: IPrivilegedService) {
        val editor = prefs.edit()
            .putInt(KEY_SNAPSHOT_SCHEMA, SNAPSHOT_SCHEMA)
            .putLong(KEY_SNAPSHOT_CREATED_AT, System.currentTimeMillis())
            .putBoolean(
                KEY_MSA_INSTALLED,
                privileged.isKnownPackageInstalled(PrivilegedContract.PACKAGE_MSA)
            )
        settingPlans.forEach { plan ->
            editor.putString(plan.snapshotKey, readSetting(privileged, plan.id) ?: NULL_SNAPSHOT)
        }
        check(editor.putBoolean(KEY_SNAPSHOT, true).commit()) {
            "无法持久化治理快照，未执行系统修改"
        }
    }

    private fun restore(privileged: IPrivilegedService): List<GovernanceItem> {
        if (!hasSnapshot()) {
            return listOf(GovernanceItem("恢复", "没有可恢复的治理快照", false))
        }

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
            val restored = writeSetting(privileged, plan.id, original)
            val actual = readSetting(privileged, plan.id)
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

    private fun readSetting(privileged: IPrivilegedService, settingId: Int): String? =
        privileged.readKnownSetting(settingId).takeUnless { it == PrivilegedContract.NULL_VALUE }

    private fun writeSetting(privileged: IPrivilegedService, settingId: Int, value: String?): Boolean =
        privileged.writeKnownSetting(settingId, value.orEmpty(), value == null)

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

    private fun openShizuku() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openShizukuDownload() {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_DOWNLOAD_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private companion object {
        const val REQUEST_CODE = 7310
        const val USER_SERVICE_VERSION = 10
        const val SNAPSHOT_SCHEMA = 2
        const val PREFS_NAME = "hyperos_governance"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        const val NULL_SNAPSHOT = "__JINGQI_SNAPSHOT_NULL__"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_SNAPSHOT_SCHEMA = "snapshot_schema"
        const val KEY_SNAPSHOT_CREATED_AT = "snapshot_created_at"
        const val KEY_LAST_APPLIED_AT = "last_applied_at"
        const val KEY_MSA_INSTALLED = "msa_installed"
    }
}
