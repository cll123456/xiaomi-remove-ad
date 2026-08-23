package app.jingqi.guard.data

import app.jingqi.guard.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ProductTier { FREE, EXPERT }

data class EntitlementState(
    val tier: ProductTier,
    val label: String,
    val source: Source
) {
    enum class Source { FREE, INTERNAL_PREVIEW, SIGNED_LICENSE }
    val isExpert: Boolean get() = tier == ProductTier.EXPERT
}

/**
 * Release builds remain free until the signed entitlement service is added.
 * Debug builds expose expert features so the open-source project can test them
 * without embedding a secret switch or a fake production licence.
 */
object Entitlements {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<EntitlementState> = _state.asStateFlow()

    private fun initialState(): EntitlementState = if (BuildConfig.DEBUG) {
        EntitlementState(
            tier = ProductTier.EXPERT,
            label = "专家内测",
            source = EntitlementState.Source.INTERNAL_PREVIEW
        )
    } else {
        EntitlementState(
            tier = ProductTier.FREE,
            label = "免费版",
            source = EntitlementState.Source.FREE
        )
    }
}
