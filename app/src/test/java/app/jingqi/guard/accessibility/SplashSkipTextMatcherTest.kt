package app.jingqi.guard.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashSkipTextMatcherTest {
    @Test
    fun generalLabelsAcceptObservedCountdownLayouts() {
        listOf(
            "跳过",
            "跳过广告",
            "跳过 2",
            "跳过广告 3秒",
            "3 跳过",
            "5秒跳过",
            "开VIP免广告 | 5 跳过",
            "开 VIP 免广告｜2 跳过"
        ).forEach { label -> assertTrue(label, SplashSkipTextMatcher.isGeneral(label)) }
    }

    @Test
    fun generalLabelsRejectBusinessActionsAndLooseSubstrings() {
        listOf(
            "点击跳过领取红包",
            "跳过后立即购买",
            "开VIP免广告",
            "关闭广告",
            "广告 3 跳过",
            "跳过 123"
        ).forEach { label -> assertFalse(label, SplashSkipTextMatcher.isGeneral(label)) }
    }

    @Test
    fun financialStrictModeDoesNotAcceptCountdownBeforeSkip() {
        assertTrue(SplashSkipTextMatcher.isStrict("跳过 2秒"))
        assertFalse(SplashSkipTextMatcher.isStrict("2 跳过"))
        assertFalse(SplashSkipTextMatcher.isStrict("开VIP免广告 | 2 跳过"))
    }

    @Test
    fun verifiedNodeWithRegisteredIdsRequiresAnExactIdMatch() {
        assertFalse(
            SplashSkipTextMatcher.matchesVerifiedNode(
                skipText = true,
                skipId = true,
                verifiedViewId = false,
                requiresVerifiedViewId = true
            )
        )
        assertTrue(
            SplashSkipTextMatcher.matchesVerifiedNode(
                skipText = false,
                skipId = false,
                verifiedViewId = true,
                requiresVerifiedViewId = true
            )
        )
    }

    @Test
    fun verifiedNodeWithoutRegisteredIdsStillAcceptsCompiledSkipLabels() {
        assertTrue(
            SplashSkipTextMatcher.matchesVerifiedNode(
                skipText = true,
                skipId = false,
                verifiedViewId = false,
                requiresVerifiedViewId = false
            )
        )
    }
}
