package app.jingqi.guard.accessibility

import app.jingqi.guard.system.PrivilegedContract

internal data class SplashPixelProfile(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val whiteRange: ClosedFloatingPointRange<Float>,
    val darkRange: ClosedFloatingPointRange<Float> = 0f..1f
)

/** Closed, compiled-in profiles. Remote rules cannot change regions or thresholds. */
internal object SplashPixelProfileCatalog {
    fun find(profileId: Int): SplashPixelProfile? = when (profileId) {
        PrivilegedContract.SPLASH_CTRIP -> SplashPixelProfile(
            left = 0.735f,
            top = 0.068f,
            right = 0.970f,
            bottom = 0.110f,
            whiteRange = 0.025f..0.100f
        )
        PrivilegedContract.SPLASH_IQIYI -> SplashPixelProfile(
            left = 0.800f,
            top = 0.030f,
            right = 0.900f,
            bottom = 0.058f,
            whiteRange = 0.025f..0.200f
        )
        PrivilegedContract.SPLASH_MIUI_MUSIC -> SplashPixelProfile(
            left = 0.650f,
            top = 0.065f,
            right = 0.960f,
            bottom = 0.115f,
            whiteRange = 0.015f..0.150f,
            darkRange = 0.450f..1.000f
        )
        else -> null
    }

    fun matches(profileId: Int, whiteRatio: Float, darkRatio: Float): Boolean {
        val profile = find(profileId) ?: return false
        return whiteRatio in profile.whiteRange && darkRatio in profile.darkRange
    }
}
