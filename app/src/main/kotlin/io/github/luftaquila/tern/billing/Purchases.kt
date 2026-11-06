// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.billing

import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import io.github.luftaquila.tern.data.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app products, set up with the same ids in Play Console (one-time products). */
object Products {
    /** Tern Pro: bought once, kept. */
    const val PRO = "tern_pro"

    /** Donations, smallest first, as offered on the Pro screen. */
    val DONATIONS = listOf("donate_small", "donate_medium", "donate_large")

    /**
     * Donations that include Pro. Like Pro they are kept (acknowledged, not consumed), since Play
     * only reports purchases that are still owned and Pro must survive a reinstall. A kept product
     * can be bought only once, so giving the same donation again buys its [REPEAT] product.
     */
    val DONATIONS_WITH_PRO = setOf("donate_medium", "donate_large")

    /** Same name and price as the donation, consumed so it can be given any number of times. */
    val REPEAT = mapOf("donate_medium" to "donate_medium_again", "donate_large" to "donate_large_again")

    /** Owning any of these means Pro. */
    val UNLOCK_PRO = DONATIONS_WITH_PRO + PRO

    val ALL = listOf(PRO) + DONATIONS + REPEAT.values

    /** The product to buy for [donation]: its repeat product once the kept one is owned. */
    fun donationToBuy(donation: String, owned: Set<String>): String =
        if (donation in owned) REPEAT[donation] ?: donation else donation
}

/**
 * Google Play purchases: Tern Pro and donations (the larger ones include Pro). Pro is remembered
 * in the settings so it works offline, and dropped when Play reports that nothing granting it is
 * owned any more (refund). Debug builds always have Pro, so its features can be developed
 * without Play.
 */
class Purchases(context: Context, private val settings: SettingsRepository) : PurchasesUpdatedListener {

    sealed interface Event {
        data object ProUnlocked : Event
        data object Thanks : Event
        data object ThanksWithPro : Event
        data object Failed : Event
    }

    private val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val _pro = MutableStateFlow(debug || settings.value.proUnlocked)
    val pro: StateFlow<Boolean> = _pro.asStateFlow()

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

    /** Product details from Play (names and localized prices), empty until Play answers. */
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _owned = MutableStateFlow<Set<String>>(emptySet())

    /** Kept products this account owns (Pro and the donations that include it). */
    val owned: StateFlow<Set<String>> = _owned.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** Connects to Play, then loads the products and restores purchases. Safe to call repeatedly. */
    fun start() {
        if (client.isReady) {
            refresh()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingResponseCode.OK) refresh()
            }

            override fun onBillingServiceDisconnected() = Unit // reconnects by itself
        })
    }

    private fun refresh() {
        val products = Products.ALL.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, details ->
            if (result.responseCode == BillingResponseCode.OK) _products.value = details.productDetailsList.associateBy { it.productId }
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode != BillingResponseCode.OK) return@queryPurchasesAsync
            val kept = purchases.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .flatMap { it.products }
                .filter { it in Products.UNLOCK_PRO }
                .toSet()
            _owned.value = kept
            setPro(kept.isNotEmpty())
            purchases.forEach { handle(it, fresh = false) }
        }
    }

    /** Opens Play's purchase sheet; false if the product is not available (Play unreachable). */
    fun launch(activity: Activity, productId: String): Boolean {
        val details = _products.value[productId] ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingResponseCode.OK -> purchases?.forEach { handle(it, fresh = true) }
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingResponseCode.USER_CANCELED -> Unit
            else -> _events.tryEmit(Event.Failed)
        }
    }

    /** Grants a purchase once Play reports it paid (pending ones wait for a later update). */
    private fun handle(purchase: Purchase, fresh: Boolean) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val kept = purchase.products.filter { it in Products.UNLOCK_PRO }
        when {
            kept.isNotEmpty() -> {
                setPro(true)
                _owned.value = _owned.value + kept
                if (!purchase.isAcknowledged) {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
                }
                if (fresh) _events.tryEmit(if (Products.PRO in kept) Event.ProUnlocked else Event.ThanksWithPro)
            }
            purchase.products.any { it in Products.ALL } -> {
                client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { r, _ ->
                    if (r.responseCode == BillingResponseCode.OK) _events.tryEmit(Event.Thanks)
                }
            }
        }
    }

    private fun setPro(owned: Boolean) {
        _pro.value = debug || owned
        if (settings.value.proUnlocked != owned) settings.update { it.copy(proUnlocked = owned) }
    }
}
