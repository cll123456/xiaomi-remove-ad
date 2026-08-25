package app.jingqi.guard.accessibility

import app.jingqi.guard.system.PrivilegedContract
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashPixelProfileCatalogTest {
    @Test
    fun iqiyiAcceptsObservedDarkAndLightAdsOnly() {
        val profileId = PrivilegedContract.SPLASH_IQIYI

        assertTrue(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.10f, darkRatio = 0.77f))
        assertTrue(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.12f, darkRatio = 0.00f))
        assertFalse(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.00f, darkRatio = 1.00f))
        assertFalse(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.89f, darkRatio = 0.02f))
    }

    @Test
    fun miuiMusicRejectsObservedHomeHeader() {
        val profileId = PrivilegedContract.SPLASH_MIUI_MUSIC

        assertTrue(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.04f, darkRatio = 0.94f))
        assertFalse(SplashPixelProfileCatalog.matches(profileId, whiteRatio = 0.94f, darkRatio = 0.00f))
    }
}
