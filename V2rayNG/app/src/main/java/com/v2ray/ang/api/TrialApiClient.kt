package com.v2ray.ang.api

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class RegisterRequest(val device_id: String)
data class RegisterResponse(
    val status: String,
    val config: String?,
    val expire_date: String?
)

interface TrialApiService {
    @POST("api/v1/register")
    suspend fun registerTrial(@Body request: RegisterRequest): RegisterResponse
}

object TrialApiClient {
    private const val BASE_URL = "http://87.106.222.188:5938/"

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: TrialApiService = retrofit.create(TrialApiService::class.java)
}
