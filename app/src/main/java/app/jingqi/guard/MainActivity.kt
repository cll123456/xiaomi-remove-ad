package app.jingqi.guard

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.jingqi.guard.data.AppState
import app.jingqi.guard.data.EntitlementState
import app.jingqi.guard.data.Entitlements
import app.jingqi.guard.rules.AppRule
import app.jingqi.guard.rules.BuiltInRuleCatalog
import app.jingqi.guard.rules.NodePolicy
import app.jingqi.guard.system.GovernanceState
import app.jingqi.guard.system.HyperOsGovernance
import app.jingqi.guard.vpn.DnsVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

private data class LaunchableApp(val label: String, val packageName: String)

internal data class PermissionSnapshot(
    val vpnConsentGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationsEnabled: Boolean
)

class MainActivity : ComponentActivity() {
    val governance by lazy { HyperOsGovernance(applicationContext) }
    private val _permissionRevision = MutableStateFlow(0L)
    val permissionRevision = _permissionRevision.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        governance.attach()
        setContent { JingQiRoot() }
        if (AppState.wasEnabled() && VpnService.prepare(this) == null) startGuard()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        governance.refresh()
        _permissionRevision.value += 1
    }

    override fun onDestroy() {
        governance.detach()
        super.onDestroy()
    }

    fun startGuard() {
        ContextCompat.startForegroundService(this, Intent(this, DnsVpnService::class.java))
    }

    fun stopGuard() {
        startService(Intent(this, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP))
    }

    internal fun permissionSnapshot(): PermissionSnapshot = PermissionSnapshot(
        vpnConsentGranted = VpnService.prepare(this) == null,
        accessibilityEnabled = isSplashAccessibilityEnabled(),
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    )

    fun openAccessibilitySettings() = openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openDeveloperSettings() = openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

    fun openNotificationSettings() = openSettings(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    )

    fun openFeedback() {
        val title = "[漏网广告] 请填写应用名称"
        val body = buildString {
            appendLine("请描述广告出现的位置和复现步骤：")
            appendLine()
            appendLine("应用名称：")
            appendLine("广告类型：开屏 / 弹窗 / 信息流 / 通知 / 其他")
            appendLine("是否每次出现：")
            appendLine()
            appendLine("--- 自动生成的非敏感环境信息 ---")
            appendLine("净启版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        }
        val uri = Uri.parse(FEEDBACK_URL).buildUpon()
            .appendQueryParameter("title", title)
            .appendQueryParameter("body", body)
            .build()
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun openSettings(intent: Intent) {
        val resolved = if (intent.resolveActivity(packageManager) != null) intent else Intent(Settings.ACTION_SETTINGS)
        startActivity(resolved)
    }

    private fun isSplashAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                service.resolveInfo.serviceInfo.packageName == packageName &&
                    service.resolveInfo.serviceInfo.name ==
                    "app.jingqi.guard.accessibility.SplashSkipAccessibilityService"
            }
    }

    private companion object {
        const val FEEDBACK_URL = "https://github.com/cll123456/xiaomi-remove-ad/issues/new"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JingQiRoot() {
    val activity = requireNotNull(LocalActivity.current as? MainActivity) {
        "净启界面必须运行在 MainActivity 中"
    }
    val running by AppState.running.collectAsState()
    val entitlement by Entitlements.state.collectAsState()
    val governanceState by activity.governance.state.collectAsState()
    val permissionRevision by activity.permissionRevision.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var oneTapResult by remember { mutableStateOf<String?>(null) }
    val permissions = remember(permissionRevision, running) { activity.permissionSnapshot() }

    fun runAuthorizedProtection() {
        activity.startGuard()
        val expertStarted = if (entitlement.isExpert && governanceState.phase in setOf(
                GovernanceState.Phase.READY,
                GovernanceState.Phase.PERMISSION,
                GovernanceState.Phase.DONE,
                GovernanceState.Phase.ERROR
            )
        ) {
            activity.governance.apply()
            true
        } else {
            false
        }
        oneTapResult = buildString {
            append("本地广告过滤已启动。")
            if (!permissions.accessibilityEnabled) append("开屏守护还需在“权限”中由你手动开启。")
            if (entitlement.isExpert) {
                append(if (expertStarted) "专家系统治理正在执行。" else "专家桥接尚未连接，可在“专家”页继续。")
            }
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) runAuthorizedProtection()
        else oneTapResult = "没有获得 VPN 连接许可，本地广告过滤未启动。"
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity.refreshPermissionState()
    }

    fun requestProtection(enable: Boolean) {
        if (!enable) {
            activity.stopGuard()
            oneTapResult = "本地广告过滤已停止，其他已授权能力保持原状态。"
            return
        }
        val prepareIntent = VpnService.prepare(activity)
        if (prepareIntent == null) runAuthorizedProtection() else vpnPermission.launch(prepareIntent)
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("净启", fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    },
                    actions = {
                        Text(
                            entitlement.label,
                            modifier = Modifier.padding(end = 16.dp),
                            color = if (entitlement.isExpert) Color(0xFF6A1B9A) else Color.DarkGray,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Outlined.Home, null) }, label = { Text("首页") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Outlined.AdminPanelSettings, null) }, label = { Text("专家") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Outlined.VpnKey, null) }, label = { Text("权限") })
                    NavigationBarItem(tab == 3, { tab = 3 }, icon = { Icon(Icons.AutoMirrored.Outlined.List, null) }, label = { Text("规则") })
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = Color(0xFFF7F8FC)) {
                when (tab) {
                    0 -> Dashboard(
                        running = running,
                        entitlement = entitlement,
                        governanceState = governanceState,
                        permissions = permissions,
                        oneTapResult = oneTapResult,
                        onOneTap = { requestProtection(true) },
                        onToggleVpn = ::requestProtection,
                        onOpenPermissions = { tab = 2 },
                        onOpenExpert = { tab = 1 }
                    )
                    1 -> SystemGovernance(entitlement, activity.governance)
                    2 -> PermissionCenter(
                        running = running,
                        entitlement = entitlement,
                        governanceState = governanceState,
                        permissions = permissions,
                        onToggleVpn = ::requestProtection,
                        onAccessibility = activity::openAccessibilitySettings,
                        onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    activity,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else activity.openNotificationSettings()
                        },
                        onDeveloperSettings = activity::openDeveloperSettings,
                        onOpenExpert = { tab = 1 }
                    )
                    else -> RulesScreen(
                        running = running,
                        stop = activity::stopGuard,
                        openFeedback = activity::openFeedback
                    )
                }
            }
        }
    }
}

@Composable
private fun Dashboard(
    running: Boolean,
    entitlement: EntitlementState,
    governanceState: GovernanceState,
    permissions: PermissionSnapshot,
    oneTapResult: String?,
    onOneTap: () -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenExpert: () -> Unit
) {
    val count by AppState.blockedCount.collectAsState()
    val hits by AppState.recentHits.collectAsState()
    val readyCount = listOf(
        running,
        permissions.accessibilityEnabled,
        entitlement.isExpert && governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE)
    ).count { it }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("一键净化", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("启动本地过滤，并执行当前已经获得授权的保护能力。", color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOneTap, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.HealthAndSafety, null)
                        Spacer(Modifier.size(10.dp))
                        Text(if (running) "重新检查并净化" else "开始一键净化", fontWeight = FontWeight.Bold)
                    }
                    oneTapResult?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF283593))
                    }
                }
            }
        }
        item {
            Text("保护状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        item {
            StatusCard(
                title = "本地广告过滤",
                detail = if (running) "本地 DNS/VPN 正在运行" else "尚未启动",
                ready = running,
                trailing = { Switch(checked = running, onCheckedChange = onToggleVpn) }
            )
        }
        item {
            StatusCard(
                title = "开屏守护",
                detail = if (permissions.accessibilityEnabled) "已由用户明确授权" else "需要在系统无障碍设置中开启",
                ready = permissions.accessibilityEnabled,
                actionLabel = if (permissions.accessibilityEnabled) null else "去开启",
                onAction = onOpenPermissions
            )
        }
        item {
            StatusCard(
                title = "专家系统治理",
                detail = when {
                    !entitlement.isExpert -> "免费版不执行系统级修改"
                    governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE) -> "专家权限已连接"
                    else -> "需要完成专家桥接"
                },
                ready = entitlement.isExpert && governanceState.phase in setOf(
                    GovernanceState.Phase.READY,
                    GovernanceState.Phase.DONE
                ),
                actionLabel = "查看",
                onAction = onOpenExpert
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("$readyCount / 3 项保护已就绪", fontWeight = FontWeight.Bold)
                    Text(
                        "所有权限均可撤销；净启不会静默开启无障碍、开发者模式或无线调试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("累计拦截", color = Color.Gray)
                    Text(
                        "$count",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF536DFE)
                    )
                    Text("次已知广告或追踪域名请求")
                }
            }
        }
        item { Text("最近命中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (hits.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text("使用其他应用后，命中的广告域名会显示在这里。", Modifier.padding(20.dp), color = Color.Gray)
            }
        }
        items(hits) { hit ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Block, null, tint = Color(0xFFE53935))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(hit.domain, fontWeight = FontWeight.Medium)
                    Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(hit.timeMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
        item {
            Text(
                "只记录被拦截的域名，记录不会上传。停止本地过滤即可恢复原网络行为。",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    detail: String,
    ready: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
                null,
                tint = if (ready) Color(0xFF2E7D32) else Color(0xFF757575)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            }
            if (trailing != null) trailing()
            else if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun SystemGovernance(entitlement: EntitlementState, governance: HyperOsGovernance) {
    val state by governance.state.collectAsState()
    val busy = state.phase == GovernanceState.Phase.WORKING || state.phase == GovernanceState.Phase.CHECKING
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!entitlement.isExpert) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))) {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text("净启专家版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text("专家版买断后解锁澎湃 OS 系统治理、视觉开屏规则和定期复查。")
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {}, enabled = false) { Text("公开测试后开放买断") }
                    }
                }
            }
            return@LazyColumn
        }
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.phase == GovernanceState.Phase.DONE) Color(0xFFE8F5E9) else Color(0xFFE8EAF6)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("澎湃 OS 一键治理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.title, fontWeight = FontWeight.Bold)
                    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            if (state.phase in setOf(
                                    GovernanceState.Phase.SHIZUKU_MISSING,
                                    GovernanceState.Phase.SHIZUKU_STOPPED
                                )
                            ) governance.openExpertBridge() else governance.apply()
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(
                            when (state.phase) {
                                GovernanceState.Phase.SHIZUKU_MISSING -> "安装内测桥接组件"
                                GovernanceState.Phase.SHIZUKU_STOPPED -> "打开内测桥接组件"
                                GovernanceState.Phase.PERMISSION -> "授权并开始治理"
                                GovernanceState.Phase.WORKING, GovernanceState.Phase.CHECKING -> "正在治理…"
                                else -> "一键关闭系统广告"
                            }
                        )
                    }
                    if (state.canRestore) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = governance::restore,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Restore, null)
                            Spacer(Modifier.size(8.dp))
                            Text("恢复治理前的系统设置")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Text(
                    "0.8 内测版暂时使用 Shizuku 启动受限服务；正式专家版将换成净启内置无线调试配对，不再要求安装第二个应用。",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        if (state.results.isNotEmpty()) item {
            Text("执行结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(state.results) { result ->
            StatusCard(result.title, result.detail, result.success)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("治理范围与安全边界", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("• MSA 仅从当前用户移除，系统分区原包保留。")
                    Text("• 只修改四个预先登记的澎湃 OS 推荐设置。")
                    Text("• 不再通过特权命令偷偷开启无障碍服务。")
                    Text("• 不接受服务器、规则或用户输入的 Shell 命令。")
                    Text("• 执行前保存原值，所有成功项目均回读验证。")
                    Text("• 可以恢复到第一次治理前的状态。")
                }
            }
        }
    }
}

@Composable
private fun PermissionCenter(
    running: Boolean,
    entitlement: EntitlementState,
    governanceState: GovernanceState,
    permissions: PermissionSnapshot,
    onToggleVpn: (Boolean) -> Unit,
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit,
    onDeveloperSettings: () -> Unit,
    onOpenExpert: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("权限中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("每项能力独立授权、随时可撤销。拒绝可选权限不影响免费基础功能。", color = Color.DarkGray)
        }
        item {
            PermissionCard(
                icon = Icons.Outlined.VpnKey,
                title = "本地 VPN",
                status = when {
                    running -> "正在运行"
                    permissions.vpnConsentGranted -> "已授权，当前未运行"
                    else -> "未授权"
                },
                explanation = "只在本机过滤 DNS 广告域名，不安装证书，不解密 HTTPS。",
                ready = running,
                action = if (running) "停止" else "开启",
                onAction = { onToggleVpn(!running) }
            )
        }
        item {
            PermissionCard(
                icon = Icons.Outlined.HealthAndSafety,
                title = "开屏守护（无障碍）",
                status = if (permissions.accessibilityEnabled) "已由用户开启" else "未开启",
                explanation = "仅在应用启动窗口识别明确跳过控件；金融应用严格限制或完全排除。",
                ready = permissions.accessibilityEnabled,
                action = "打开系统设置",
                onAction = onAccessibility
            )
        }
        item {
            PermissionCard(
                icon = Icons.Outlined.Notifications,
                title = "通知",
                status = if (permissions.notificationsEnabled) "已允许" else "未允许",
                explanation = "用于显示 VPN 运行状态和每周复查提醒，不用于营销推送。",
                ready = permissions.notificationsEnabled,
                action = "管理",
                onAction = onNotifications
            )
        }
        item {
            PermissionCard(
                icon = Icons.Outlined.AdminPanelSettings,
                title = "专家系统权限",
                status = when {
                    !entitlement.isExpert -> "免费版不需要"
                    governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE) -> "已连接"
                    else -> "尚未连接"
                },
                explanation = "用于固定的系统广告治理和局部视觉匹配；不允许任意命令。",
                ready = !entitlement.isExpert || governanceState.phase in setOf(
                    GovernanceState.Phase.READY,
                    GovernanceState.Phase.DONE
                ),
                action = "查看专家页",
                onAction = onOpenExpert
            )
        }
        if (entitlement.isExpert) item {
            PermissionCard(
                icon = Icons.Outlined.Settings,
                title = "无线调试（正式版路径）",
                status = "内置配对开发中",
                explanation = "首次必须由用户开启并输入六位配对码；配对后日常治理可以一键执行。",
                ready = false,
                action = "查看开发者选项",
                onAction = onDeveloperSettings
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(16.dp)) {
                    Text("撤销方式", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("VPN 可在此停止；无障碍和无线调试在系统设置中关闭；专家治理可在专家页恢复原设置。")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    status: String,
    explanation: String,
    ready: Boolean,
    action: String,
    onAction: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (ready) Color(0xFF2E7D32) else Color(0xFF5C6BC0))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.bodySmall, color = if (ready) Color(0xFF2E7D32) else Color.DarkGray)
                }
                TextButton(onClick = onAction) { Text(action) }
            }
            Text(explanation, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun RulesScreen(running: Boolean, stop: () -> Unit, openFeedback: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    var showBypass by remember { mutableStateOf(false) }
    var rulesText by remember { mutableStateOf(AppState.customRules().sorted().joinToString("\n")) }
    val supportedRules = BuiltInRuleCatalog.supportedApplications()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.List, null)
                        Text("规则中心", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("内置规则版本 ${BuiltInRuleCatalog.REVISION}，当前登记 ${supportedRules.size} 个专用应用规则。")
                    Text("远程规则只能描述固定匹配方式，不能包含脚本或系统命令。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showEditor = true }) { Text("编辑自定义域名规则") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showBypass = true }) { Text("应用 VPN 白名单") }
                }
            }
        }
        item { Text("专用应用规则", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(supportedRules, key = AppRule::id) { rule ->
            RuleCard(rule)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.BugReport, null, tint = Color(0xFF2E7D32))
                        Text("发现漏网广告？", Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("当前内测阶段通过 GitHub 收集应用名称、广告类型和复现步骤，不会自动上传安装列表或截图。")
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = openFeedback) { Text("提交漏网广告") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("使用提醒", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("• 规则过宽可能导致登录、支付或图片加载失败。")
                    Text("• 修改 DNS 规则后需要重新开启本地过滤。")
                    Text("• 净启不安装证书，也不读取 HTTPS 内容。")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { AppState.resetStatistics() }) { Text("清空拦截统计") }
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("自定义域名规则") },
            text = {
                OutlinedTextField(
                    value = rulesText,
                    onValueChange = { rulesText = it },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    placeholder = { Text("ads.example.com\n*.tracker.example") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rules = rulesText.lineSequence()
                        .map(String::trim)
                        .filter { it.isNotBlank() && !it.startsWith("#") }
                        .toSet()
                    AppState.saveCustomRules(rules)
                    if (running) stop()
                    showEditor = false
                }) { Text(if (running) "保存并停止过滤" else "保存") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("取消") } }
        )
    }
    if (showBypass) {
        BypassDialog(
            context = context,
            running = running,
            onDismiss = { showBypass = false },
            onSaved = {
                if (running) stop()
                showBypass = false
            }
        )
    }
}

@Composable
private fun RuleCard(rule: AppRule) {
    val mode = when {
        rule.visualSplash != null -> "局部视觉规则 · 专家"
        rule.nodePolicy == NodePolicy.FINANCIAL_EXACT -> "金融严格文字规则"
        rule.nodePolicy == NodePolicy.VERIFIED -> "已验证控件规则"
        else -> "通用安全规则"
    }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF2E7D32))
            Column(Modifier.padding(start = 12.dp)) {
                Text(rule.displayName, fontWeight = FontWeight.Bold)
                Text(mode, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                Text(rule.packageName, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun BypassDialog(context: Context, running: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy(LaunchableApp::packageName)
            .sortedBy { it.label.lowercase() }
    }
    var selected by remember { mutableStateOf(AppState.bypassPackages()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用 VPN 白名单") },
        text = {
            Column {
                Text("选中的应用会完全绕过净启 VPN。适合登录、支付或联网异常的应用。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(apps, key = LaunchableApp::packageName) { app ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + app.packageName else selected - app.packageName
                                }
                            )
                            Column {
                                Text(app.label)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { AppState.saveBypassPackages(selected); onSaved() }) {
                Text(if (running) "保存并停止过滤" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
