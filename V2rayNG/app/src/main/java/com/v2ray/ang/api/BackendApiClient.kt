package com.v2ray.ang.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

data class SubscribeRequest(val purchaseToken: String)
data class SubscribeResponse(
    val status: String,
    val username: String?,
    val subscription_url: String?,
    val expire_date: String?
)

data class VerifyResponse(
    val status: String, // "active" or "expired"
    val expire_date: String?
)

interface BackendApiService {
    @POST("api/v1/subscribe")
    suspend fun subscribe(@Body request: SubscribeRequest): SubscribeResponse

    @GET("api/v1/verify/{purchaseToken}")
    suspend fun verify(@Path("purchaseToken") purchaseToken: String): VerifyResponse
}

object BackendApiClient {
    // We will use the domain provided by the user for HTTPS traffic via Cloudflare
    private const val BASE_URL = "https://api.myminiapps.win/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: BackendApiService = retrofit.create(BackendApiService::class.java)
}
