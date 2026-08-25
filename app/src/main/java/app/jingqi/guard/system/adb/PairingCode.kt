package app.jingqi.guard.system.adb

/** Pure validation helpers; pairing codes are never persisted or logged. */
internal object PairingCode {
    fun editableValue(raw: String): String = raw.filter(Char::isDigit).take(LENGTH)

    fun toCharsOrNull(raw: String): CharArray? {
        val candidate = raw.trim()
        if (candidate.length != LENGTH || !candidate.all(Char::isDigit)) return null
        return candidate.toCharArray()
    }

    fun isComplete(raw: String): Boolean = raw.length == LENGTH && raw.all(Char::isDigit)

    private const val LENGTH = 6
}
