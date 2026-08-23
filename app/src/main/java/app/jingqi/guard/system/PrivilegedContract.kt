package app.jingqi.guard.system

/**
 * The complete surface exposed to the shell-identity process.
 *
 * IDs are intentionally closed and versioned. The app, downloaded rule packs,
 * and feedback payloads can never supply an executable command.
 */
object PrivilegedContract {
    const val NULL_VALUE = "__JINGQI_NULL__"

    const val PACKAGE_MSA = 1

    const val SETTING_BROWSER_RECOMMEND = 1
    const val SETTING_PREDOWNLOAD_CLOUD = 2
    const val SETTING_PREDOWNLOAD_TASKS = 3
    const val SETTING_MISHARE_ADS = 4

    const val SPLASH_CTRIP = 1
    const val SPLASH_IQIYI = 2

    data class KnownSetting(
        val namespace: String,
        val key: String,
        val allowedValues: Set<String>,
        val allowsJsonSnapshot: Boolean = false
    )

    fun packageName(packageId: Int): String? = when (packageId) {
        PACKAGE_MSA -> "com.miui.systemAdSolution"
        else -> null
    }

    fun setting(settingId: Int): KnownSetting? = when (settingId) {
        SETTING_BROWSER_RECOMMEND -> KnownSetting(
            namespace = "system",
            key = "com.android.browser.enable_app_chooser_recommend",
            allowedValues = setOf("0", "1")
        )
        SETTING_PREDOWNLOAD_CLOUD -> KnownSetting(
            namespace = "system",
            key = "predownload_cloud_enable",
            allowedValues = setOf("0", "1")
        )
        SETTING_PREDOWNLOAD_TASKS -> KnownSetting(
            namespace = "system",
            key = "predownload_ui_enable",
            allowedValues = setOf("[]"),
            allowsJsonSnapshot = true
        )
        SETTING_MISHARE_ADS -> KnownSetting(
            namespace = "system",
            key = "mishare_enable_advert_mine",
            allowedValues = setOf("0", "1")
        )
        else -> null
    }

    fun isAllowedSettingValue(settingId: Int, value: String): Boolean {
        val setting = setting(settingId) ?: return false
        if (value in setting.allowedValues) return true
        return setting.allowsJsonSnapshot &&
            value.length <= 4_096 &&
            value.startsWith('[') &&
            value.endsWith(']') &&
            value.none { it == '\u0000' || it == '\n' || it == '\r' }
    }
}
