package com.sample.android.trivialdrivesample

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.LifecycleObserver
import com.sample.android.trivialdrivesample.billing.BillingDataSource
import com.sample.android.trivialdrivesample.db.GameStateModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * The repository uses data from the Billing data source and the game state model together to give
 * a unified version of the state of the game to the ViewModel. It works closely with the
 * BillingDataSource to implement consumable items, premium items, etc.
 */
class TrivialDriveRepository(private val application: Application) {
    val billingDataSource: BillingDataSource
    val gameStateModel: GameStateModel
    val gameMessages: MutableSharedFlow<String>

    /**
     * Sets up the event that we can use to send messages up to the UI to be used in Snackbars.
     * This collects new purchase events from the BillingDataSource, transforming the known SKU
     * strings into useful String messages, and emitting the messages into the game messages flow.
     */
    fun postMessagesFromBillingFlow() {
        Log.d(TAG, "postMessagesFromBillingFlow")
        CoroutineScope(Dispatchers.Main).launch {
            Log.d(TAG, "Collection Coroutine Scope Entered")
            try {
                billingDataSource.getNewPurchases().collect() { sku ->
                    Log.d(TAG, "Triggered with " + sku)
                    when (sku) {
                        SKU_GAS -> gameMessages.emit(application.getString(R.string.message_more_gas_acquired))
                        SKU_PREMIUM -> gameMessages.emit(application.getString(R.string.message_premium))
                        SKU_INFINITE_GAS_MONTHLY -> gameMessages.emit(application.getString(R.string.message_subscribed))
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "Collection complete")
            }
            Log.d(TAG, "Collection Coroutine Scope Exited")
        }
    }

    /**
     * Uses one unit of gas if we don't have a subscription.
     */
    fun drive() {
        CoroutineScope(Dispatchers.Main).launch {
            val gasTankLevel = gasTankLevel().first()
            when (gasTankLevel) {
                GAS_TANK_INFINITE -> sendMessage(application.getString(R.string.message_infinite_drive))
                GAS_TANK_MIN -> sendMessage(application.getString(R.string.message_out_of_gas))
                else -> {
                    val newGasLevel = gasTankLevel - gameStateModel.decrementGas(GAS_TANK_MIN)
                    Log.d(TAG, "Old Gas Level: " + gasTankLevel + " New Gas Level: " + newGasLevel)
                    if ( newGasLevel == GAS_TANK_MIN ) {
                        sendMessage(application.getString(R.string.message_out_of_gas))
                    } else {
                        sendMessage(application.getString(R.string.message_you_drove))
                    }
                }
            }
        }
    }

    /**
     * Automatic support for upgrading/downgrading subscription.
     * @param activity
     * @param sku
     */
    fun buySku(activity: Activity, sku: String): Boolean {
        var oldSku: String? = null
        when (sku) {
            SKU_INFINITE_GAS_MONTHLY -> oldSku = SKU_INFINITE_GAS_YEARLY
            SKU_INFINITE_GAS_YEARLY -> oldSku = SKU_INFINITE_GAS_MONTHLY
        }
        if ( oldSku == null ) {
            return billingDataSource.launchBillingFlow(activity, sku)
        } else {
            return billingDataSource.launchBillingFlow(activity, sku, oldSku)
        }
    }

    /**
     * Return Flow that indicates whether the sku is currently purchased.
     *
     * @param sku the SKU to get and observe the value for
     * @return Flow that returns true if the sku is purchased.
     */
    fun isPurchased(sku: String): Flow<Boolean> {
        return billingDataSource.isPurchased(sku)
    }

    /**
     * We can buy gas if:
     * 1) We can add at least one unit of gas
     * 2) The billing data source allows us to purchase, which means that the item isn't already
     *    purchased.
     * For other skus, we rely on just the data from the billing data source. For subscriptions,
     * only one can be held at a time, and purchasing one subscription will use the billing feature
     * to upgrade or downgrade the user from the other.
     *
     * @param sku the SKU to get and observe the value for
     * @return Flow<Boolean> that returns true if the sku can be purchased
     */
    fun canPurchase(sku: String): Flow<Boolean> {
        return when (sku) {
            SKU_GAS -> {
                billingDataSource.canPurchase(sku).combine(gasTankLevel()) { canPurchase, gasTankLevel ->
                    canPurchase && gasTankLevel < GAS_TANK_MAX
                }
            }
            else -> billingDataSource.canPurchase(sku)
        }
    }

    /**
     * Combine the results from our subscription flow with our gas tank level from the game state
     * database to get our displayed gas tank level, which will be infinite if a subscription is
     * active.
     *
     * @return Flow that represents the gasTankLevel by game logic.
     */
    fun gasTankLevel(): Flow<Int> {
        val gasTankLevelFlow = gameStateModel.gasTankLevel()
        val monthlySubPurchasedFlow = isPurchased(SKU_INFINITE_GAS_MONTHLY)
        val yearlySubPurchasedFlow = isPurchased(SKU_INFINITE_GAS_YEARLY)
        return combine(
                gasTankLevelFlow,
                monthlySubPurchasedFlow,
                yearlySubPurchasedFlow) { gasTankLevel, monthlySubPurchased, yearlySubPurchased ->
            when {
                monthlySubPurchased || yearlySubPurchased -> GAS_TANK_INFINITE
                else -> gasTankLevel
            }
        }
    }

    fun refreshPurchases() {
        billingDataSource.refreshPurchases()
    }

    val billingLifecycleObserver: LifecycleObserver
        get() = billingDataSource

    // There's lots of information in SkuDetails, but our app only needs a few things, since our
    // goods never go on sale, have introductory pricing, etc.
    fun getSkuTitle(sku: String): Flow<String> {
        return billingDataSource.getSkuTitle(sku)
    }

    fun getSkuPrice(sku: String): Flow<String> {
        return billingDataSource.getSkuPrice(sku)
    }

    fun getSkuDescription(sku: String): Flow<String> {
        return billingDataSource.getSkuDescription(sku)
    }

    val messages: Flow<String>
        get() = gameMessages

    fun sendMessage(s: String) {
        CoroutineScope(Dispatchers.Main).launch {
            gameMessages.emit(s)
        }
    }

    val billingFlowInProcess: Flow<Boolean>
        get() = billingDataSource.getBillingFlowInProcess()

    fun debugConsumePremium() {
        CoroutineScope(Dispatchers.Main).launch {
            billingDataSource.consumeInappPurchase(SKU_PREMIUM)
        }
    }

    companion object {
        // Source for all constants
        const val GAS_TANK_MIN = 0
        const val GAS_TANK_MAX = 4
        const val GAS_TANK_INFINITE = 5

        // The following SKU strings must match the ones we have in the Google Play developer console.
        // SKUs for non-subscription purchases
        const val SKU_PREMIUM = "premium"
        const val SKU_GAS = "gas"

        // SKU for subscription purchases (infinite gas)
        const val SKU_INFINITE_GAS_MONTHLY = "infinite_gas_monthly"
        const val SKU_INFINITE_GAS_YEARLY = "infinite_gas_yearly"
        val TAG = "TrivialDrive:" + TrivialDriveRepository::class.java.simpleName
        val INAPP_SKUS = arrayOf(SKU_PREMIUM, SKU_GAS)
        val SUBSCRIPTION_SKUS = arrayOf(SKU_INFINITE_GAS_MONTHLY,
                SKU_INFINITE_GAS_YEARLY)
        val AUTO_CONSUME_SKUS = arrayOf(SKU_GAS)

        @Volatile
        private var sInstance: TrivialDriveRepository? = null

        // Standard boilerplate double check locking pattern for thread-safe singletons.
        @JvmStatic
        fun getInstance(application: Application) =
            sInstance ?: synchronized(this) {
                sInstance ?: TrivialDriveRepository(application).
                    also { sInstance = it }
            }
    }

    init {
        billingDataSource = BillingDataSource.getInstance(application, INAPP_SKUS,
                SUBSCRIPTION_SKUS, AUTO_CONSUME_SKUS)
        gameStateModel = GameStateModel.getInstance(application)
        gameMessages = MutableSharedFlow<String>()
        postMessagesFromBillingFlow()

        // Since both are tied to application lifecycle, we can launch this scope to collect
        // consumed purchases from the billing data source while the app process is alive.
        CoroutineScope(Dispatchers.Main).launch {
            billingDataSource.getConsumedPurchases().collect {
                if ( it == SKU_GAS ) {
                    gameStateModel.incrementGas(GAS_TANK_MAX)
                }
            }
        }
    }
}