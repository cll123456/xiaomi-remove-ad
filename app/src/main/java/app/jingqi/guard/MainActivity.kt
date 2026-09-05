package app.jingqi.guard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.jingqi.guard.accessibility.SplashHealth
import app.jingqi.guard.accessibility.SplashRuntime
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
import kotlinx.coroutines.delay
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
        accessibilityEnabled = SplashRuntime.permissionEnabled(this),
        notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
    )

    fun openAccessibilitySettings() = openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openBackgroundSettings() = openSettings(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )

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
    val splashRuntime by SplashRuntime.state.collectAsState()
    LaunchedEffect(activity) {
        activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                activity.refreshPermissionState()
                delay(2_000L)
            }
        }
    }
    var tab by remember { mutableIntStateOf(0) }
    var oneTapResult by remember { mutableStateOf<String?>(null) }
    val permissions = remember(permissionRevision, running) { activity.permissionSnapshot() }
    val splashHealth = remember(permissionRevision, permissions, splashRuntime) {
        splashRuntime.health(permissions.accessibilityEnabled, SystemClock.elapsedRealtime())
    }

    fun runAuthorizedProtection() {
        activity.startGuard()
        oneTapResult = buildString {
            append("已请求启动本地过滤，请以下方实际运行状态为准。")
            if (splashHealth != SplashHealth.RUNNING) append("请点“恢复开屏守护”完成检查。")
            else append("开屏守护正在运行，可以直接使用其他应用。")
            append("日常保护不需要无线调试，也不需要停留在设置页。")
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) runAuthorizedProtection()
        else oneTapResult = "没有获得 VPN 连接许可，本地广告过滤未启动。"
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity.refreshPermissionState()
    }

    fun requestPairing() {
        tab = 1
        oneTapResult = "请保持系统六位配对码窗口在前台，净启会在本次配对中自动提交；不要切换回来。"
        activity.governance.startPairing()
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
                        splashHealth = splashHealth,
                        oneTapResult = oneTapResult,
                        onOneTap = { requestProtection(true) },
                        onToggleVpn = ::requestProtection,
                        onOpenPermissions = { tab = 2 },
                        onAccessibility = activity::openAccessibilitySettings,
                        onOpenExpert = { tab = 1 }
                    )
                    1 -> SystemGovernance(
                        entitlement = entitlement,
                        governance = activity.governance,
                        accessibilityEnabled = permissions.accessibilityEnabled,
                        splashHealth = splashHealth,
                        onPairing = ::requestPairing,
                        onAccessibility = activity::openAccessibilitySettings
                    )
                    2 -> PermissionCenter(
                        running = running,
                        entitlement = entitlement,
                        governanceState = governanceState,
                        permissions = permissions,
                        splashHealth = splashHealth,
                        onToggleVpn = ::requestProtection,
                        onAccessibility = activity::openAccessibilitySettings,
                        onBackground = activity::openBackgroundSettings,
                        onNotifications = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                    activity,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else activity.openNotificationSettings()
                        },
                        onPairing = ::requestPairing,
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
    splashHealth: SplashHealth,
    oneTapResult: String?,
    onOneTap: () -> Unit,
    onToggleVpn: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onAccessibility: () -> Unit,
    onOpenExpert: () -> Unit
) {
    val count by AppState.blockedCount.collectAsState()
    val hits by AppState.recentHits.collectAsState()
    val splashRuntime by SplashRuntime.state.collectAsState()
    val readyCount = listOf(
        running,
        splashHealth == SplashHealth.RUNNING
    ).count { it }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("一键净化", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("日常保护无需无线调试，也不需要一直连接电脑或停在系统设置。", color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOneTap, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Outlined.HealthAndSafety, null)
                        Spacer(Modifier.size(10.dp))
                        Text(if (running) "重新检查并净化" else "开始一键净化", fontWeight = FontWeight.Bold)
                    }
                    if (splashHealth != SplashHealth.RUNNING) {
                        OutlinedButton(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) {
                            Text("恢复开屏守护")
                        }
                        Text("Android 要求你亲自开启一次无障碍；已授权但未连接时，关闭再开启该服务。净启不能代替你确认。", style = MaterialTheme.typography.bodySmall)
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
                detail = splashHealth.description,
                ready = splashHealth == SplashHealth.RUNNING,
                actionLabel = if (splashHealth == SplashHealth.RUNNING) null else "恢复",
                onAction = onAccessibility
            )
        }
        item {
            StatusCard(
                title = "可选：专家系统治理",
                detail = when {
                    !entitlement.isExpert -> "免费版不执行系统级修改"
                    governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE) -> "专家权限已连接"
                    else -> "未连接不影响日常开屏守护；执行系统治理时再连接"
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
                    Text("$readyCount / 2 项日常保护正在运行", fontWeight = FontWeight.Bold)
                    Text(
                        "所有权限均可撤销；净启不会静默开启无障碍、开发者模式或无线调试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    TextButton(onClick = onOpenPermissions) { Text("后台运行与权限检查") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("开屏跳过操作：${splashRuntime.submittedActions} 次", fontWeight = FontWeight.Bold)
                    if (splashRuntime.lastActionAt > 0L) {
                        Text("最近：${BuiltInRuleCatalog.find(splashRuntime.lastActionPackage)?.displayName ?: "其他应用"} · ${DateFormat.getDateTimeInstance().format(Date(splashRuntime.lastActionAt))}")
                    }
                    Text("记录的是跳过操作已提交，不代表每次广告都已关闭；不处理首页信息流或视频贴片。", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
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
private fun SystemGovernance(
    entitlement: EntitlementState,
    governance: HyperOsGovernance,
    accessibilityEnabled: Boolean,
    splashHealth: SplashHealth,
    onPairing: () -> Unit,
    onAccessibility: () -> Unit
) {
    val state by governance.state.collectAsState()
    val busy = state.phase in setOf(
        GovernanceState.Phase.WORKING,
        GovernanceState.Phase.CHECKING,
        GovernanceState.Phase.PAIRING
    )
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
                    containerColor = if (splashHealth == SplashHealth.RUNNING) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("第三方应用开屏广告", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${splashHealth.description}。京东、酷狗、淘宝等开屏跳过不依赖专家连接；专家配对不能代替无障碍授权。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray
                    )
                    if (splashHealth != SplashHealth.RUNNING) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onAccessibility, modifier = Modifier.fillMaxWidth()) {
                            Text("去系统设置开启开屏守护")
                        }
                    }
                }
            }
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
                    if (state.awaitingPairingCode) {
                        Button(
                            onClick = if (accessibilityEnabled) onPairing else onAccessibility,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (accessibilityEnabled) "重新打开系统配对页面" else "先开启开屏守护")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = governance::cancelPairing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("取消本次配对")
                        }
                    } else {
                        Button(
                            onClick = {
                                when (state.phase) {
                                    GovernanceState.Phase.NOT_PAIRED -> {
                                        if (accessibilityEnabled) onPairing() else onAccessibility()
                                    }
                                    GovernanceState.Phase.DISCONNECTED -> governance.openWirelessDebugging()
                                    else -> governance.apply()
                                }
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
                                    GovernanceState.Phase.NOT_PAIRED -> {
                                        if (accessibilityEnabled) "开始一次本机配对" else "先开启开屏守护"
                                    }
                                    GovernanceState.Phase.PAIRING -> "配对进行中…"
                                    GovernanceState.Phase.DISCONNECTED -> "打开无线调试"
                                    GovernanceState.Phase.WORKING -> "正在治理…"
                                    GovernanceState.Phase.CHECKING -> "正在检查连接…"
                                    else -> "一键关闭系统广告"
                                }
                            )
                        }
                        if (state.phase == GovernanceState.Phase.PAIRING) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = governance::cancelPairing, modifier = Modifier.fillMaxWidth()) {
                                Text("取消配对")
                            }
                        }
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
                    if (state.phase == GovernanceState.Phase.DISCONNECTED) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = governance::refresh, modifier = Modifier.fillMaxWidth()) {
                            Text("检查已有配对连接")
                        }
                        TextButton(onClick = onPairing, modifier = Modifier.fillMaxWidth()) {
                            Text("系统忘记了净启？重新配对")
                        }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))) {
                Text(
                    "首次配对：先由你在系统无障碍页开启“净启·开屏守护”，再点上方按钮 → 开启“无线调试” → 点“使用配对码配对设备”并保持窗口不动。净启只在这次配对期间从系统设置读取六位码并立即本机提交，不需要切换界面，也不需要安装 Shizuku。等待超过五分钟会自动取消。",
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
                    Text("• 配对端口必须属于本机网络接口，不会连接附近其他设备。")
                    Text("• 配对私钥由 Android Keystore 加密并排除云备份。")
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
    splashHealth: SplashHealth,
    onToggleVpn: (Boolean) -> Unit,
    onAccessibility: () -> Unit,
    onBackground: () -> Unit,
    onNotifications: () -> Unit,
    onPairing: () -> Unit,
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
                status = splashHealth.description,
                explanation = "仅在应用启动窗口识别明确跳过控件；你主动发起专家配对时，还会在系统设置配对窗口一次性读取六位码。金融应用严格限制或完全排除。",
                ready = splashHealth == SplashHealth.RUNNING,
                action = "打开系统设置",
                onAction = onAccessibility
            )
        }
        item {
            PermissionCard(
                icon = Icons.Outlined.Settings,
                title = "后台运行（首次检查）",
                status = "系统后台策略不能由净启完整检测，请自行确认",
                explanation = "在净启的应用信息中允许自启动，将省电策略设为无限制（不同澎湃版本入口可能不同）；可在最近任务中锁定净启。完成后退出设置正常使用即可。强行停止、撤销权限或系统回收后仍可能需要手动恢复。",
                ready = false,
                action = "应用信息",
                onAction = onBackground
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
                    else -> "可选，日常守护不需要连接"
                },
                explanation = "用于固定的系统广告治理；不允许规则、服务器或界面输入任意命令。",
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
                title = "内置无线调试配对",
                status = when {
                    governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE) -> "已配对并连接"
                    governanceState.phase == GovernanceState.Phase.PAIRING -> "正在配对"
                    else -> "需要一次用户确认"
                },
                explanation = "首次由你开启无线调试并保持系统六位码窗口在前台，净启自动本机提交；配对后日常治理可以一键执行，不需要 Shizuku。",
                ready = governanceState.phase in setOf(GovernanceState.Phase.READY, GovernanceState.Phase.DONE),
                action = "开始/重新配对",
                onAction = onPairing
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
                Text(
                    "选中的应用会完全绕过净启 VPN。微信、京东和金融支付应用默认受兼容保护，避免小程序、商品、登录或支付异常。",
                    style = MaterialTheme.typography.bodySmall
                )
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
