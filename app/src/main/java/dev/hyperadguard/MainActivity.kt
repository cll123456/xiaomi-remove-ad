package dev.hyperadguard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.hyperadguard.data.AppState
import dev.hyperadguard.system.GovernanceState
import dev.hyperadguard.system.HyperOsGovernance
import dev.hyperadguard.vpn.DnsVpnService
import java.text.DateFormat
import java.util.Date

private data class LaunchableApp(val label: String, val packageName: String)

class MainActivity : ComponentActivity() {
    val governance by lazy { HyperOsGovernance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        governance.attach()
        setContent { GuardApp() }
        if (AppState.wasEnabled() && VpnService.prepare(this) == null) startGuard()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuardApp() {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val running by AppState.running.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) activity.startGuard()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("澎湃净广", fontWeight = FontWeight.Bold) }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(tab == 0, { tab = 0 }, icon = { Icon(Icons.Outlined.HealthAndSafety, null) }, label = { Text("防护") })
                    NavigationBarItem(tab == 1, { tab = 1 }, icon = { Icon(Icons.Outlined.CheckCircle, null) }, label = { Text("系统治理") })
                    NavigationBarItem(tab == 2, { tab = 2 }, icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("规则") })
                }
            }
        ) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding), color = Color(0xFFF7F8FC)) {
                when (tab) {
                    0 -> Dashboard(running) { enable ->
                        if (enable) {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            val intent = VpnService.prepare(activity)
                            if (intent == null) activity.startGuard() else vpnPermission.launch(intent)
                        } else activity.stopGuard()
                    }
                    1 -> SystemGovernance(activity.governance)
                    else -> RulesScreen(running) { activity.stopGuard() }
                }
            }
        }
    }
}

@Composable
private fun Dashboard(running: Boolean, toggle: (Boolean) -> Unit) {
    val count by AppState.blockedCount.collectAsState()
    val hits by AppState.recentHits.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (running) Color(0xFFE8F5E9) else Color.White),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.HealthAndSafety, null, tint = if (running) Color(0xFF2E7D32) else Color.Gray)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(if (running) "广告过滤已开启" else "广告过滤未开启", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(if (running) "本地 DNS 规则正在保护设备" else "开启后会请求 Android VPN 授权")
                    }
                    Switch(checked = running, onCheckedChange = toggle)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("累计拦截", color = Color.Gray)
                    Text("$count", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = Color(0xFF536DFE))
                    Text("次已知广告或追踪域名请求")
                }
            }
        }
        item { Text("最近命中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (hits.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Text("开启防护并使用应用后，命中的域名会显示在这里。", Modifier.padding(20.dp), color = Color.Gray)
            }
        }
        items(hits) { hit ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Block, null, tint = Color(0xFFE53935))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(hit.domain, fontWeight = FontWeight.Medium)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(hit.timeMillis)), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
        item { Text("只记录被拦截的域名，记录不会上传。停止防护即可恢复原网络行为。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SystemGovernance(governance: HyperOsGovernance) {
    val state by governance.state.collectAsState()
    val busy = state.phase == GovernanceState.Phase.WORKING || state.phase == GovernanceState.Phase.CHECKING
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (state.phase == GovernanceState.Phase.DONE) Color(0xFFE8F5E9) else Color(0xFFE8EAF6)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("一键关闭澎湃系统广告", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.title, fontWeight = FontWeight.Bold)
                    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { governance.apply() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                        }
                        Text(
                            when (state.phase) {
                                GovernanceState.Phase.SHIZUKU_MISSING -> "安装 Shizuku"
                                GovernanceState.Phase.SHIZUKU_STOPPED -> "打开 Shizuku 启动服务"
                                GovernanceState.Phase.PERMISSION -> "授权并一键治理"
                                GovernanceState.Phase.WORKING, GovernanceState.Phase.CHECKING -> "正在治理…"
                                else -> "一键关闭系统广告"
                            }
                        )
                    }
                    if (state.canRestore) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { governance.restore() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Text("恢复治理前的系统设置")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { governance.disableSplashSkip() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("仅停用开屏自动跳过")
                    }
                }
            }
        }
        if (state.results.isNotEmpty()) item {
            Text("执行结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        items(state.results) { result ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (result.success) Icons.Outlined.CheckCircle else Icons.Outlined.Block,
                        null,
                        tint = if (result.success) Color(0xFF2E7D32) else Color(0xFFE53935)
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(result.title, fontWeight = FontWeight.Bold)
                        Text(result.detail, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("治理范围与安全说明", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("• 自动从当前用户移除 MSA；系统原包保留，可恢复。")
                    Text("• 自动关闭浏览器推荐入口和系统预下载。")
                    Text("• 普通第三方应用启动后自动识别“跳过”。")
                    Text("• 招商/邮储使用金融精确模式；支付与钱包默认排除。")
                    Text("• 不禁用应用商店、安全中心、浏览器或文件管理。")
                    Text("• 执行前保存原值，可随时一键恢复。")
                    Spacer(Modifier.height(8.dp))
                    Text("第三方应用自己的会员推广或服务内容不属于澎湃系统广告，仍由 DNS 防护规则处理。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(running: Boolean, stop: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    var showBypass by remember { mutableStateOf(false) }
    var rulesText by remember { mutableStateOf(AppState.customRules().sorted().joinToString("\n")) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.List, null)
                        Text("过滤规则", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("内置一组保守规则。自定义规则支持 example.com 和 *.example.com，每行一条。")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showEditor = true }) { Text("编辑自定义规则") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { showBypass = true }) { Text("应用白名单") }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Column(Modifier.padding(18.dp)) {
                    Text("使用提醒", fontWeight = FontWeight.Bold)
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text("• 规则过宽可能导致登录、支付或图片加载失败。")
                    Text("• 修改规则后需要重新开启防护才会生效。")
                    Text("• 本工具不安装证书，也不读取 HTTPS 内容。")
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
                    val rules = rulesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
                    AppState.saveCustomRules(rules)
                    if (running) stop()
                    showEditor = false
                }) { Text(if (running) "保存并停止防护" else "保存") }
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
private fun BypassDialog(context: Context, running: Boolean, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val apps = remember {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
    var selected by remember { mutableStateOf(AppState.bypassPackages()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用白名单") },
        text = {
            Column {
                Text("选中的应用会完全绕过净广 VPN。适合登录、支付或联网异常的应用。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp)) {
                    items(apps, key = { it.packageName }) { app ->
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
                Text(if (running) "保存并停止防护" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
