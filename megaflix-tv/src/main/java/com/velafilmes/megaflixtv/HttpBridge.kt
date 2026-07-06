package com.velafilmes.megaflixtv

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object HttpBridge {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetch(url: String, headersJson: String?): String {
        val request = Request.Builder()
            .url(url)
            .apply {
                val headers = HeaderStore.parse(headersJson).ifEmpty { HeaderStore.headers }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()

        return client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    }
}
