package app.jingqi.guard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityPolicyTest {
    @Test
    fun protectsCriticalAppsByDefault() {
        val defaults = CompatibilityPolicy.effectiveBypassPackages(null)

        assertTrue("com.tencent.mm" in defaults)
        assertTrue("com.jingdong.app.mall" in defaults)
        assertTrue("cmb.pb" in defaults)
        assertTrue("com.eg.android.AlipayGphone" in defaults)
    }

    @Test
    fun preservesAnExplicitUserSelectionIncludingEmpty() {
        assertEquals(setOf("example.app"), CompatibilityPolicy.effectiveBypassPackages(setOf("example.app")))
        assertEquals(emptySet<String>(), CompatibilityPolicy.effectiveBypassPackages(emptySet()))
    }
}
