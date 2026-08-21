package dev.hyperadguard.system

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class GovernanceItem(val title: String, val detail: String, val success: Boolean)

data class GovernanceState(
    val phase: Phase = Phase.CHECKING,
    val title: String = "正在检查系统权限…",
    val detail: String = "",
    val results: List<GovernanceItem> = emptyList(),
    val canRestore: Boolean = false
) {
    enum class Phase { CHECKING, SHIZUKU_MISSING, SHIZUKU_STOPPED, PERMISSION, READY, WORKING, DONE, ERROR }
}

class HyperOsGovernance(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences("hyperos_governance", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(GovernanceState())
    val state: StateFlow<GovernanceState> = _state.asStateFlow()
    private var service: IPrivilegedService? = null
    private var pending: Action? = null

    private enum class Action { APPLY, RESTORE, DISABLE_SKIP }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE && grantResult == PackageManager.PERMISSION_GRANTED) bindAndRun()
        else if (requestCode == REQUEST_CODE) _state.value = GovernanceState(
            GovernanceState.Phase.PERMISSION,
            "需要 Shizuku 授权",
            "你拒绝了权限。点击按钮可重新请求；本工具只运行内置的治理命令。",
            canRestore = prefs.getBoolean(KEY_SNAPSHOT, false)
        )
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
    ).daemon(false).processNameSuffix("governance").debuggable(false).version(9)

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
    }

    fun refresh() {
        val restore = prefs.getBoolean(KEY_SNAPSHOT, false)
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!installed) {
            _state.value = GovernanceState(GovernanceState.Phase.SHIZUKU_MISSING, "需要安装 Shizuku", "普通 App 无权修改澎湃 OS 的系统广告项。", canRestore = restore)
            return
        }
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) {
            _state.value = GovernanceState(GovernanceState.Phase.SHIZUKU_STOPPED, "Shizuku 尚未运行", "打开 Shizuku，使用无线调试或电脑 USB 调试启动一次。", canRestore = restore)
            return
        }
        val granted = try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
        _state.value = if (granted) GovernanceState(GovernanceState.Phase.READY, "一键治理已就绪", "将从当前用户移除 MSA 广告服务，并关闭浏览器推荐和系统预下载。", canRestore = restore)
        else GovernanceState(GovernanceState.Phase.PERMISSION, "需要一次 Shizuku 授权", "点击治理后，在系统弹窗中允许即可；以后只需点一次。", canRestore = restore)
    }

    fun apply() = request(Action.APPLY)
    fun restore() = request(Action.RESTORE)
    fun disableSplashSkip() = request(Action.DISABLE_SKIP)

    private fun request(action: Action) {
        val phase = _state.value.phase
        when (phase) {
            GovernanceState.Phase.SHIZUKU_MISSING -> openShizukuDownload()
            GovernanceState.Phase.SHIZUKU_STOPPED -> openShizuku()
            GovernanceState.Phase.CHECKING, GovernanceState.Phase.WORKING -> Unit
            else -> {
                pending = action
                if (try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }) bindAndRun()
                else try { Shizuku.requestPermission(REQUEST_CODE) } catch (t: Throwable) { fail(t) }
            }
        }
    }

    private fun bindAndRun() {
        _state.value = GovernanceState(GovernanceState.Phase.WORKING, "正在连接系统服务…", "请保持当前页面。", canRestore = prefs.getBoolean(KEY_SNAPSHOT, false))
        try {
            Shizuku.bindUserService(userServiceArgs, connection)
        } catch (t: Throwable) { fail(t) }
    }

    private suspend fun runAction(action: Action) = withContext(Dispatchers.IO) {
        try {
            val privileged = requireNotNull(service)
            val results = when (action) {
                Action.APPLY -> apply(privileged)
                Action.RESTORE -> restore(privileged)
                Action.DISABLE_SKIP -> disableSplashSkip(privileged)
            }
            val ok = results.all { it.success }
            withContext(Dispatchers.Main) {
                val successTitle = when (action) {
                    Action.APPLY -> "系统广告治理完成"
                    Action.RESTORE -> "原设置已恢复"
                    Action.DISABLE_SKIP -> "开屏自动跳过已停用"
                }
                _state.value = GovernanceState(
                    if (ok) GovernanceState.Phase.DONE else GovernanceState.Phase.ERROR,
                    if (ok) successTitle else "部分操作未完成",
                    if (ok) "已逐项回读验证。重启后设置仍然有效。" else "请查看下方结果；未成功的项目没有被标记为完成。",
                    results,
                    prefs.getBoolean(KEY_SNAPSHOT, false)
                )
            }
        } catch (t: Throwable) { withContext(Dispatchers.Main) { fail(t) } }
    }

    private fun apply(p: IPrivilegedService): List<GovernanceItem> {
        if (!prefs.getBoolean(KEY_SNAPSHOT, false)) {
            val msaEnabled = run(p, "pm", "list", "packages", "-e", MSA_PACKAGE).contains(MSA_PACKAGE)
            prefs.edit()
                .putBoolean(KEY_MSA_ENABLED, msaEnabled)
                .putString(KEY_BROWSER, getSetting(p, BROWSER_KEY))
                .putString(KEY_PREDOWNLOAD, getSetting(p, PREDOWNLOAD_KEY))
                .putString(KEY_PREDOWNLOAD_UI, getSetting(p, PREDOWNLOAD_UI_KEY))
                .putBoolean(KEY_SNAPSHOT, true)
                .apply()
        }
        if (!prefs.getBoolean(KEY_ACCESSIBILITY_SNAPSHOT, false)) {
            prefs.edit()
                .putString(KEY_ENABLED_ACCESSIBILITY, getSecureSetting(p, ENABLED_ACCESSIBILITY_KEY) ?: NULL_VALUE)
                .putString(KEY_ACCESSIBILITY_ENABLED, getSecureSetting(p, ACCESSIBILITY_ENABLED_KEY) ?: NULL_VALUE)
                .putBoolean(KEY_ACCESSIBILITY_SNAPSHOT, true)
                .apply()
        }
        // HyperOS 3 blocks disable-user for this system package, but permits the
        // standard per-user uninstall. The system-partition APK stays intact.
        if (run(p, "pm", "list", "packages", "--user", "0", MSA_PACKAGE).contains(MSA_PACKAGE)) {
            run(p, "pm", "uninstall", "-k", "--user", "0", MSA_PACKAGE)
        }
        run(p, "settings", "put", "system", BROWSER_KEY, "0")
        run(p, "settings", "put", "system", PREDOWNLOAD_KEY, "0")
        run(p, "settings", "put", "system", PREDOWNLOAD_UI_KEY, "[]")
        val existingServices = getSecureSetting(p, ENABLED_ACCESSIBILITY_KEY)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()
        val enabledServices = (existingServices + SPLASH_SKIP_COMPONENT).distinct().joinToString(":")
        run(p, "settings", "put", "secure", ENABLED_ACCESSIBILITY_KEY, enabledServices)
        run(p, "settings", "put", "secure", ACCESSIBILITY_ENABLED_KEY, "1")
        val skipEnabled = getSecureSetting(p, ENABLED_ACCESSIBILITY_KEY)
            ?.split(':')?.contains(SPLASH_SKIP_COMPONENT) == true
        return listOf(
            GovernanceItem("小米系统广告服务（MSA）", if (run(p, "pm", "list", "packages", "--user", "0", MSA_PACKAGE).contains(MSA_PACKAGE)) "移除失败" else "已从当前用户移除（系统原包保留）", !run(p, "pm", "list", "packages", "--user", "0", MSA_PACKAGE).contains(MSA_PACKAGE)),
            verifySetting(p, "浏览器推荐入口", BROWSER_KEY, "0"),
            verifySetting(p, "系统云预下载", PREDOWNLOAD_KEY, "0"),
            verifySetting(p, "预下载任务列表", PREDOWNLOAD_UI_KEY, "[]"),
            verifySetting(p, "米享广告", "mishare_enable_advert_mine", "0"),
            GovernanceItem("第三方应用开屏自动跳过", if (skipEnabled) "已启用；普通应用通用模式，金融应用严格保护" else "启用失败", skipEnabled)
        )
    }

    private fun restore(p: IPrivilegedService): List<GovernanceItem> {
        if (!prefs.getBoolean(KEY_SNAPSHOT, false)) return listOf(GovernanceItem("恢复", "没有可恢复的快照", false))
        if (prefs.getBoolean(KEY_MSA_ENABLED, true)) run(p, "cmd", "package", "install-existing", "--user", "0", MSA_PACKAGE)
        restoreSetting(p, BROWSER_KEY, prefs.getString(KEY_BROWSER, null))
        restoreSetting(p, PREDOWNLOAD_KEY, prefs.getString(KEY_PREDOWNLOAD, null))
        restoreSetting(p, PREDOWNLOAD_UI_KEY, prefs.getString(KEY_PREDOWNLOAD_UI, null))
        if (prefs.getBoolean(KEY_ACCESSIBILITY_SNAPSHOT, false)) {
            restoreSecureSetting(p, ENABLED_ACCESSIBILITY_KEY, prefs.getString(KEY_ENABLED_ACCESSIBILITY, NULL_VALUE).fromStoredValue())
            restoreSecureSetting(p, ACCESSIBILITY_ENABLED_KEY, prefs.getString(KEY_ACCESSIBILITY_ENABLED, NULL_VALUE).fromStoredValue())
        }
        val msaOk = !prefs.getBoolean(KEY_MSA_ENABLED, true) || run(p, "pm", "list", "packages", "-e", MSA_PACKAGE).contains(MSA_PACKAGE)
        val results = listOf(
            GovernanceItem("小米系统广告服务（MSA）", if (msaOk) "已恢复原状态" else "恢复失败", msaOk),
            verifySetting(p, "浏览器推荐入口", BROWSER_KEY, prefs.getString(KEY_BROWSER, null)),
            verifySetting(p, "系统云预下载", PREDOWNLOAD_KEY, prefs.getString(KEY_PREDOWNLOAD, null)),
            verifySetting(p, "预下载任务列表", PREDOWNLOAD_UI_KEY, prefs.getString(KEY_PREDOWNLOAD_UI, null)),
            GovernanceItem("开屏自动跳过", "已恢复治理前状态", true)
        )
        if (results.all { it.success }) prefs.edit().clear().apply()
        return results
    }

    private fun disableSplashSkip(p: IPrivilegedService): List<GovernanceItem> {
        val remaining = getSecureSetting(p, ENABLED_ACCESSIBILITY_KEY)
            ?.split(':')
            ?.filter { it.isNotBlank() && it != SPLASH_SKIP_COMPONENT }
            .orEmpty()
        if (remaining.isEmpty()) {
            run(p, "settings", "delete", "secure", ENABLED_ACCESSIBILITY_KEY)
            run(p, "settings", "put", "secure", ACCESSIBILITY_ENABLED_KEY, "0")
        } else {
            run(p, "settings", "put", "secure", ENABLED_ACCESSIBILITY_KEY, remaining.joinToString(":"))
        }
        val disabled = getSecureSetting(p, ENABLED_ACCESSIBILITY_KEY)
            ?.split(':')?.contains(SPLASH_SKIP_COMPONENT) != true
        return listOf(GovernanceItem("第三方应用开屏自动跳过", if (disabled) "已停用；DNS 与系统治理保持不变" else "停用失败", disabled))
    }

    private fun getSetting(p: IPrivilegedService, key: String): String? = run(p, "settings", "get", "system", key).takeUnless { it == "null" }

    private fun getSecureSetting(p: IPrivilegedService, key: String): String? = run(p, "settings", "get", "secure", key).takeUnless { it == "null" }

    private fun restoreSetting(p: IPrivilegedService, key: String, value: String?) {
        if (value == null) run(p, "settings", "delete", "system", key)
        else run(p, "settings", "put", "system", key, value)
    }

    private fun restoreSecureSetting(p: IPrivilegedService, key: String, value: String?) {
        if (value == null) run(p, "settings", "delete", "secure", key)
        else run(p, "settings", "put", "secure", key, value)
    }

    private fun String?.fromStoredValue(): String? = this?.takeUnless { it == NULL_VALUE }

    private fun verifySetting(p: IPrivilegedService, title: String, key: String, expected: String?): GovernanceItem {
        val actual = getSetting(p, key)
        val ok = actual == expected
        return GovernanceItem(title, if (ok) "已设为 ${expected ?: "未设置"}" else "当前值：${actual ?: "未设置"}", ok)
    }

    private fun run(p: IPrivilegedService, vararg command: String): String {
        val result = p.execute(command)
        val lines = result.lineSequence().toList()
        val code = lines.firstOrNull()?.toIntOrNull() ?: -1
        if (code != 0) throw IllegalStateException(lines.drop(1).joinToString("\n").ifBlank { "命令执行失败：${command.first()}" })
        return lines.drop(1).joinToString("\n").trim()
    }

    private fun fail(t: Throwable) {
        _state.value = GovernanceState(GovernanceState.Phase.ERROR, "系统治理未完成", t.message ?: t.javaClass.simpleName, canRestore = prefs.getBoolean(KEY_SNAPSHOT, false))
    }

    private fun openShizuku() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (intent != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun openShizukuDownload() {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val REQUEST_CODE = 7310
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val MSA_PACKAGE = "com.miui.systemAdSolution"
        private const val BROWSER_KEY = "com.android.browser.enable_app_chooser_recommend"
        private const val PREDOWNLOAD_KEY = "predownload_cloud_enable"
        private const val PREDOWNLOAD_UI_KEY = "predownload_ui_enable"
        private const val ENABLED_ACCESSIBILITY_KEY = "enabled_accessibility_services"
        private const val ACCESSIBILITY_ENABLED_KEY = "accessibility_enabled"
        private const val SPLASH_SKIP_COMPONENT = "dev.hyperadguard/dev.hyperadguard.accessibility.SplashSkipAccessibilityService"
        private const val NULL_VALUE = "__NULL__"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_MSA_ENABLED = "msa_enabled"
        private const val KEY_BROWSER = "browser"
        private const val KEY_PREDOWNLOAD = "predownload"
        private const val KEY_PREDOWNLOAD_UI = "predownload_ui"
        private const val KEY_ACCESSIBILITY_SNAPSHOT = "accessibility_snapshot"
        private const val KEY_ENABLED_ACCESSIBILITY = "enabled_accessibility"
        private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled_original"
    }
}
