package app.jingqi.guard.data

/**
 * Applications whose core networking is more important than DNS-level filtering.
 *
 * DNS filtering cannot distinguish an advertisement request from a login, payment,
 * mini-program, or product-data request when an SDK shares infrastructure with the
 * host application. These packages therefore start in the VPN bypass list. The user
 * can still change the list explicitly from the rules screen.
 */
object CompatibilityPolicy {
    val defaultBypassPackages: Set<String> = setOf(
        "com.tencent.mm",              // WeChat and mini programs
        "com.jingdong.app.mall",      // JD product, account, and payment traffic
        "cmb.pb",                     // China Merchants Bank
        "com.yitong.mbank.psbc",      // Postal Savings Bank of China
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.mipay.wallet",
        "com.android.icredit"
    )

    fun effectiveBypassPackages(saved: Set<String>?): Set<String> =
        saved?.toSet() ?: defaultBypassPackages
}
