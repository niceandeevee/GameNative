package app.gamenative.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Google Play subscriptions are not part of this build (it is sideloaded, never installed from
 * Play), so the RevenueCat SDK is gone. This keeps the call shape so membership checks and the
 * upgrade dialog fall through to the non-Play path.
 */
object PlayBilling {
    data class Plan(val tier: String, val price: String)

    sealed class PurchaseOutcome {
        data object Success : PurchaseOutcome()
        data object Cancelled : PurchaseOutcome()
        data class Failed(val message: String) : PurchaseOutcome()
    }

    val hasActivePlan = mutableStateOf(false)
    val cachedPlans = mutableStateOf<List<Plan>>(emptyList())

    @Suppress("UNUSED_PARAMETER")
    fun enabled(context: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun init(app: Application) = Unit

    suspend fun refresh() = Unit

    suspend fun plans(): List<Plan> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    suspend fun purchase(activity: Activity, plan: Plan): PurchaseOutcome =
        PurchaseOutcome.Failed("Google Play billing is not available in this build")

    @Suppress("UNUSED_PARAMETER")
    suspend fun linkAccount(accountId: String) = Unit

    suspend fun unlinkAccount() = Unit
}
