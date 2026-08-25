package app.jingqi.guard.rules

import app.jingqi.guard.system.PrivilegedContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInRuleCatalogTest {
    @Test
    fun sensitivePaymentAppsAreBlocked() {
        val alipay = BuiltInRuleCatalog.find("com.eg.android.AlipayGphone")
        assertEquals(NodePolicy.BLOCKED, alipay?.nodePolicy)
        assertTrue(alipay?.sensitive == true)
    }

    @Test
    fun canvasProfilesResolveToClosedPrivilegedIds() {
        assertEquals(
            PrivilegedContract.SPLASH_CTRIP,
            BuiltInRuleCatalog.find("ctrip.android.view")?.visualSplash?.profileId
        )
        assertEquals(
            PrivilegedContract.SPLASH_IQIYI,
            BuiltInRuleCatalog.find("com.qiyi.video")?.visualSplash?.profileId
        )
        assertEquals(
            PrivilegedContract.SPLASH_MIUI_MUSIC,
            BuiltInRuleCatalog.find("com.miui.player")?.visualSplash?.profileId
        )
        assertEquals(10, BuiltInRuleCatalog.find("com.qiyi.video")?.visualSplash?.maxAttempts)
    }

    @Test
    fun observedNodeRulesAreExactAndVerified() {
        val shenzhenAir = BuiltInRuleCatalog.find("com.air.sz")
        assertEquals(NodePolicy.VERIFIED, shenzhenAir?.nodePolicy)
        assertEquals(setOf("com.air.sz:id/count_down"), shenzhenAir?.verifiedSkipViewIds)

        val miuiMusic = BuiltInRuleCatalog.find("com.miui.player")
        assertEquals(NodePolicy.VERIFIED, miuiMusic?.nodePolicy)
        assertEquals(11, miuiMusic?.visualSplash?.maxAttempts)
    }

    @Test
    fun unknownApplicationHasNoRemotePrivilegeRule() {
        assertNull(BuiltInRuleCatalog.find("example.unknown.application"))
    }

    @Test
    fun privilegedSettingsRejectUnexpectedValues() {
        assertTrue(PrivilegedContract.isAllowedSettingValue(PrivilegedContract.SETTING_BROWSER_RECOMMEND, "0"))
        assertFalse(PrivilegedContract.isAllowedSettingValue(PrivilegedContract.SETTING_BROWSER_RECOMMEND, "anything"))
        assertFalse(PrivilegedContract.isAllowedSettingValue(999, "0"))
    }
}
