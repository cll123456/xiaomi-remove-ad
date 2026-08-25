package app.jingqi.guard.accessibility

/** Exact, locally compiled labels that are safe to treat as splash skip controls. */
internal object SplashSkipTextMatcher {
    private const val COUNTDOWN = "[0-9]{1,2}\\s*(?:秒|s)?"
    private val generalPattern = Regex(
        "^(?:" +
            "跳过(?:广告)?(?:\\s*$COUNTDOWN)?" +
            "|$COUNTDOWN\\s*跳过(?:广告)?" +
            "|开\\s*VIP\\s*免广告\\s*[|｜]\\s*$COUNTDOWN\\s*跳过(?:广告)?" +
            ")$",
        RegexOption.IGNORE_CASE
    )
    private val strictPattern = Regex(
        "^跳过(?:广告)?(?:\\s*$COUNTDOWN)?$",
        RegexOption.IGNORE_CASE
    )

    fun isGeneral(text: CharSequence?): Boolean =
        text?.toString()?.normalizedLabel()?.matches(generalPattern) == true

    fun isStrict(text: CharSequence?): Boolean =
        text?.toString()?.normalizedLabel()?.matches(strictPattern) == true

    fun matchesVerifiedNode(
        skipText: Boolean,
        skipId: Boolean,
        verifiedViewId: Boolean,
        requiresVerifiedViewId: Boolean
    ): Boolean = if (requiresVerifiedViewId) {
        verifiedViewId
    } else {
        skipText || skipId
    }

    private fun String.normalizedLabel(): String =
        replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
