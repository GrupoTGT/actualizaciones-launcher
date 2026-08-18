package com.grupotgt.launcherkioscotgt.mdm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object MdmHttpClientFactory {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
}
