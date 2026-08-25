package app.jingqi.guard.system.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WirelessPairingTextDetectorTest {
    @Test
    fun recognizesChineseAndroidPairingDialog() {
        assertEquals(
            "123456",
            WirelessPairingTextDetector.findCode(
                listOf(
                    WirelessPairingText("使用配对码配对设备"),
                    WirelessPairingText("配对码"),
                    WirelessPairingText("123 456")
                )
            )
        )
    }

    @Test
    fun recognizesEnglishAndroidPairingDialog() {
        assertEquals(
            "654321",
            WirelessPairingTextDetector.findCode(
                listOf(
                    WirelessPairingText("Pair device with pairing code"),
                    WirelessPairingText("654321", "com.android.settings:id/pairing_code")
                )
            )
        )
    }

    @Test
    fun rejectsSixDigitsWithoutPairingContext() {
        assertNull(
            WirelessPairingTextDetector.findCode(
                listOf(
                    WirelessPairingText("短信验证码"),
                    WirelessPairingText("123456")
                )
            )
        )
        assertNull(
            WirelessPairingTextDetector.findCode(
                listOf(
                    WirelessPairingText("屏幕锁定 PIN"),
                    WirelessPairingText("123456")
                )
            )
        )
    }

    @Test
    fun rejectsAmbiguousMultipleCodes() {
        assertNull(
            WirelessPairingTextDetector.findCode(
                listOf(
                    WirelessPairingText("使用配对码配对设备"),
                    WirelessPairingText("123456"),
                    WirelessPairingText("654321")
                )
            )
        )
    }
}
