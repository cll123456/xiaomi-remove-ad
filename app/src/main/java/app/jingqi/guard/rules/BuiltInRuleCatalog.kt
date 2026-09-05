package app.jingqi.guard.rules

import app.jingqi.guard.system.PrivilegedContract

object BuiltInRuleCatalog {
    const val SCHEMA_VERSION = 1
    const val REVISION = 3L

    val rules: List<AppRule> = listOf(
        // These use the conservative generic text matcher, not blind coordinates.
        AppRule("jd.splash.text.v1", "com.jingdong.app.mall", "京东", nodePolicy = NodePolicy.GENERAL),
        AppRule("kugou.splash.text.v1", "com.kugou.android", "酷狗音乐", nodePolicy = NodePolicy.GENERAL),
        AppRule("taobao.splash.text.v1", "com.taobao.taobao", "淘宝", nodePolicy = NodePolicy.GENERAL),
        AppRule(
            id = "ctrip.splash.visual.v1",
            packageName = "ctrip.android.view",
            displayName = "携程旅行",
            nodePolicy = NodePolicy.GENERAL,
            visualSplash = VisualSplashRule(
                profileId = PrivilegedContract.SPLASH_CTRIP,
                tapX = 0.848f,
                tapY = 0.088f
            )
        ),
        AppRule(
            id = "iqiyi.splash.visual.v1",
            packageName = "com.qiyi.video",
            displayName = "爱奇艺",
            nodePolicy = NodePolicy.GENERAL,
            visualSplash = VisualSplashRule(
                profileId = PrivilegedContract.SPLASH_IQIYI,
                tapX = 0.850f,
                tapY = 0.035f,
                startupDelayMillis = 600L,
                activeWindowMillis = 3_750L,
                retryIntervalMillis = 350L,
                maxAttempts = 10
            )
        ),
        AppRule(
            id = "shenzhen-air.splash.verified.v1",
            packageName = "com.air.sz",
            displayName = "深圳航空",
            nodePolicy = NodePolicy.VERIFIED,
            verifiedSkipViewIds = setOf("com.air.sz:id/count_down")
        ),
        AppRule(
            id = "miui-music.splash.visual.v1",
            packageName = "com.miui.player",
            displayName = "小米音乐",
            nodePolicy = NodePolicy.VERIFIED,
            visualSplash = VisualSplashRule(
                profileId = PrivilegedContract.SPLASH_MIUI_MUSIC,
                tapX = 0.855f,
                tapY = 0.091f,
                startupDelayMillis = 250L,
                activeWindowMillis = 3_750L,
                retryIntervalMillis = 350L,
                maxAttempts = 11
            )
        ),
        AppRule(
            id = "seeyou.splash.verified.v1",
            packageName = "com.lingan.seeyou",
            displayName = "美柚",
            nodePolicy = NodePolicy.VERIFIED
        ),
        AppRule(
            id = "railway12306.splash.verified.v1",
            packageName = "com.MobileTicket",
            displayName = "铁路12306",
            nodePolicy = NodePolicy.VERIFIED
        ),
        AppRule(
            id = "cmb.splash.financial.v1",
            packageName = "cmb.pb",
            displayName = "招商银行",
            nodePolicy = NodePolicy.FINANCIAL_EXACT,
            sensitive = true
        ),
        AppRule(
            id = "psbc.splash.financial.v1",
            packageName = "com.yitong.mbank.psbc",
            displayName = "邮储银行",
            nodePolicy = NodePolicy.FINANCIAL_EXACT,
            sensitive = true
        ),
        blocked("alipay.blocked", "com.eg.android.AlipayGphone", "支付宝"),
        blocked("unionpay.blocked", "com.unionpay", "云闪付"),
        blocked("mipay.blocked", "com.mipay.wallet", "小米钱包"),
        blocked("credit.blocked", "com.android.icredit", "金融账户组件")
    )

    private val byPackage = rules.associateBy(AppRule::packageName)

    fun find(packageName: String, versionCode: Long? = null): AppRule? =
        byPackage[packageName]?.takeIf { it.supports(versionCode) }

    fun supportedApplications(): List<AppRule> = rules.filter { it.nodePolicy != NodePolicy.BLOCKED }

    private fun blocked(id: String, packageName: String, displayName: String) = AppRule(
        id = id,
        packageName = packageName,
        displayName = displayName,
        nodePolicy = NodePolicy.BLOCKED,
        sensitive = true
    )
}
