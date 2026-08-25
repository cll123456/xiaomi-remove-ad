package app.jingqi.guard.system.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun limitsEditableValueToSixDigits() {
        assertEquals("123456", PairingCode.editableValue("12a34 5678"))
    }

    @Test
    fun acceptsOnlyAnExactSixDigitSubmission() {
        assertArrayEquals("123456".toCharArray(), PairingCode.toCharsOrNull(" 123456 "))
        assertNull(PairingCode.toCharsOrNull("12345"))
        assertNull(PairingCode.toCharsOrNull("abc123456"))
        assertTrue(PairingCode.isComplete("654321"))
        assertFalse(PairingCode.isComplete("65432"))
    }
}
