// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.billing

import android.app.Activity
import android.content.Context
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app products, set up with the same ids in Play Console (one-time products). */
object Products {
    /** Donations, smallest first, as offered on the donation screen. */
    val DONATIONS = listOf("donate_small", "donate_medium", "donate_large")
}

/**
 * Google Play donations. Each one is consumed once paid, so the same donation can be given any
 * number of times. Donations unlock nothing; every feature is free.
 */
class Purchases(context: Context) : PurchasesUpdatedListener {

    sealed interface Event {
        data object Thanks : Event
        data object Failed : Event
    }

    private val _products = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())

    /** Product details from Play (names and localized prices), empty until Play answers. */
    val products: StateFlow<Map<String, ProductDetails>> = _products.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /**
     * Connects to Play, then loads the products and consumes donations paid since the last check
     * (pending payments completed while the app was closed). Safe to call repeatedly.
     */
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
        val products = Products.DONATIONS.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build()
        }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, details ->
            if (result.responseCode == BillingResponseCode.OK) _products.value = details.productDetailsList.associateBy { it.productId }
        }
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, purchases ->
            if (result.responseCode == BillingResponseCode.OK) purchases.forEach(::consume)
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
            BillingResponseCode.OK -> purchases?.forEach(::consume)
            BillingResponseCode.ITEM_ALREADY_OWNED -> refresh() // an earlier donation not consumed yet
            BillingResponseCode.USER_CANCELED -> Unit
            else -> _events.tryEmit(Event.Failed)
        }
    }

    /** Consumes a donation once Play reports it paid (pending ones wait for a later update). */
    private fun consume(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (purchase.products.none { it in Products.DONATIONS }) return
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { r, _ ->
            if (r.responseCode == BillingResponseCode.OK) _events.tryEmit(Event.Thanks)
        }
    }
}
