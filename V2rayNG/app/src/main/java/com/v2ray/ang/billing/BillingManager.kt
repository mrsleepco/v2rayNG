package com.v2ray.ang.billing

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(private val activity: Activity) {

    private var billingClient: BillingClient
    private var isConnected = false

    init {
        billingClient = BillingClient.newBuilder(activity)
            .setListener { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                    Log.i("BillingManager", "User canceled the purchase flow.")
                } else {
                    Log.e("BillingManager", "Billing error: ${billingResult.responseCode}")
                }
            }
            .enablePendingPurchases()
            .build()

        connectToPlayBillingService()
    }

    private fun connectToPlayBillingService() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    Log.i("BillingManager", "Billing client connected.")
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
                Log.w("BillingManager", "Billing client disconnected. Trying to reconnect...")
            }
        })
    }

    fun initiatePurchaseFlow(productId: String) {
        if (!isConnected) {
            Log.e("BillingManager", "Billing client not connected.")
            return
        }

        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            )
            .build()

        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                val productDetails = productDetailsList[0]
                val productDetailsParamsList = listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: "")
                        .build()
                )

                val billingFlowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(productDetailsParamsList)
                    .build()

                billingClient.launchBillingFlow(activity, billingFlowParams)
            } else {
                Log.e("BillingManager", "Failed to query product details. Response code: ${billingResult.responseCode}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        Log.i("BillingManager", "Purchase successful! Token: ${purchase.purchaseToken}")
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.i("BillingManager", "Purchase acknowledged.")
                    }
                }
            }
        }
    }
}
