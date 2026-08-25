package app.jingqi.guard.system.adb

import java.util.Locale

internal data class WirelessPairingText(
    val value: String,
    val viewId: String = ""
)

/**
 * Pure, deliberately narrow recognizer for Android's wireless-debugging pairing dialog.
 * A six-digit value is never accepted without pairing-specific Settings context.
 */
internal object WirelessPairingTextDetector {
    fun findCode(nodes: List<WirelessPairingText>): String? {
        if (nodes.isEmpty()) return null
        val labels = nodes.map { it.value.trim().lowercase(Locale.ROOT) }.filter(String::isNotEmpty)
        val resourceIds = nodes.map { it.viewId.lowercase(Locale.ROOT) }.filter(String::isNotEmpty)

        val hasStrongTitle = labels.any { label ->
            STRONG_CONTEXT.any(label::contains)
        }
        val hasPairingCodeTerm = labels.any { label ->
            PAIRING_CODE_TERMS.any(label::contains)
        }
        val hasDeviceOrDebugTerm = labels.any { label ->
            DEVICE_OR_DEBUG_TERMS.any(label::contains)
        }
        val hasPairingResource = resourceIds.any { id ->
            id.contains("pairing_code") || id.contains("pairingcode") || id.contains("pair_code")
        }
        if (!hasStrongTitle && !(hasPairingCodeTerm && hasDeviceOrDebugTerm) && !hasPairingResource) {
            return null
        }

        return nodes.asSequence()
            .mapNotNull { exactSixDigits(it.value) }
            .distinct()
            .singleOrNull()
    }

    private fun exactSixDigits(raw: String): String? {
        val compact = raw.filterNot { it.isWhitespace() || it == '\u00a0' }
        if (compact.length != 6 || compact.any { it !in '0'..'9' }) return null
        return compact
    }

    private val STRONG_CONTEXT = listOf(
        "使用配对码配对设备",
        "通过配对码配对设备",
        "pair device with pairing code",
        "pair a device with pairing code",
        "wi-fi pairing code",
        "wifi pairing code"
    )
    private val PAIRING_CODE_TERMS = listOf("配对码", "pairing code", "pair code")
    private val DEVICE_OR_DEBUG_TERMS = listOf(
        "配对设备",
        "无线调试",
        "pair device",
        "wireless debugging"
    )
}
