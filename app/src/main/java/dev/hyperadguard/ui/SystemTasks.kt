package dev.hyperadguard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings

data class SystemTask(
    val id: String,
    val title: String,
    val description: String,
    val actionLabel: String = "打开设置",
    val open: (Context) -> Unit
)

private fun launchPackage(context: Context, packageName: String, fallback: Intent) {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: fallback
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun launchSetting(context: Context, primary: Intent) {
    val intent = if (primary.resolveActivity(context.packageManager) != null) primary else Intent(Settings.ACTION_SETTINGS)
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

val hyperOsTasks = listOf(
    SystemTask(
        "system_ads", "关闭个性化广告推荐",
        "在“广告服务”中关闭个性化推荐。注意：这不会自动移除全部广告。"
    ) { launchSetting(it, Intent(Settings.ACTION_PRIVACY_SETTINGS)) },
    SystemTask(
        "security", "安全中心：关闭推荐内容",
        "打开安全中心设置，检查“推荐内容/接收推荐”以及垃圾清理后的推荐。"
    ) { launchPackage(it, "com.miui.securitycenter", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "app_store", "应用商店：关闭推荐与通知",
        "在我的/设置中关闭相关推荐、活动通知和自动播放。"
    ) { launchPackage(it, "com.xiaomi.market", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "browser", "浏览器：关闭广告与信息流",
        "在隐私与安全、主页设置中关闭广告、个性化推荐和内容推荐。"
    ) { launchPackage(it, "com.android.browser", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "file_manager", "文件管理：关闭热门推荐",
        "在关于或隐私设置中关闭推荐内容。"
    ) { launchPackage(it, "com.android.fileexplorer", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "downloads", "下载管理：关闭资源推荐",
        "进入下载管理设置，关闭资源推荐或热门内容。"
    ) { launchPackage(it, "com.android.providers.downloads.ui", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "themes", "主题壁纸：关闭个性化推荐",
        "在主题壁纸的隐私或设置页面中关闭个性化推荐。"
    ) { launchPackage(it, "com.android.thememanager", Intent(Settings.ACTION_SETTINGS)) },
    SystemTask(
        "notifications", "清理推广通知",
        "查看最近发送通知的应用，关闭商店、浏览器和系统工具的营销通知。"
    ) { launchSetting(it, Intent("android.settings.NOTIFICATION_SETTINGS")) },
    SystemTask(
        "autostart", "允许净广工具自启动",
        "澎湃 OS 可能限制后台恢复。请允许本工具自启动并将省电策略设为无限制。"
    ) { launchPackage(it, "com.miui.securitycenter", Intent(Settings.ACTION_APPLICATION_SETTINGS)) },
    SystemTask(
        "always_on_vpn", "启用始终开启的 VPN",
        "可在网络设置中设为始终开启，使过滤在服务被系统回收后更可靠地恢复。"
    ) { launchSetting(it, Intent(Settings.ACTION_VPN_SETTINGS)) }
)
