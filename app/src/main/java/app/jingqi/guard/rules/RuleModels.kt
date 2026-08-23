package app.jingqi.guard.rules

/** A data-only rule model. Rule packs cannot contain executable code or shell commands. */
data class RulePackMetadata(
    val schemaVersion: Int,
    val revision: Long,
    val generatedAtEpochMillis: Long,
    val minimumAppVersionCode: Int,
    val signatureAlgorithm: String = "Ed25519"
)

enum class NodePolicy {
    VERIFIED,
    GENERAL,
    FINANCIAL_EXACT,
    BLOCKED
}

data class VisualSplashRule(
    val profileId: Int,
    val tapX: Float,
    val tapY: Float,
    val startupDelayMillis: Long = 1_050L,
    val activeWindowMillis: Long = 3_000L
)

data class AppRule(
    val id: String,
    val packageName: String,
    val displayName: String,
    val nodePolicy: NodePolicy,
    val minimumVersionCode: Long? = null,
    val maximumVersionCode: Long? = null,
    val visualSplash: VisualSplashRule? = null,
    val sensitive: Boolean = false
) {
    init {
        require(id.matches(Regex("[a-z0-9._-]{3,80}")))
        require(packageName.matches(Regex("[A-Za-z0-9_.]{3,200}")))
        require(minimumVersionCode == null || maximumVersionCode == null || minimumVersionCode <= maximumVersionCode)
        visualSplash?.let {
            require(it.tapX in 0f..1f && it.tapY in 0f..1f)
            require(it.startupDelayMillis in 0L..10_000L)
            require(it.activeWindowMillis in 250L..20_000L)
        }
    }

    fun supports(versionCode: Long?): Boolean {
        if (versionCode == null) return true
        if (minimumVersionCode != null && versionCode < minimumVersionCode) return false
        if (maximumVersionCode != null && versionCode > maximumVersionCode) return false
        return true
    }
}
