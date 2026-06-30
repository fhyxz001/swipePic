package com.swipepic.data.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.swipepic.data.model.FetchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 图片接口服务（对应 PRD §6.1）。
 * 接口直接返回图片二进制流，非 JSON。
 */
class ImageApiService(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    suspend fun fetchRandomImage(): FetchResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(baseUrl).get().build()
            val response = client.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext FetchResult.Error(IOException("HTTP ${resp.code}"))
                }
                val body = resp.body
                    ?: return@withContext FetchResult.Error(IOException("Empty response body"))
                val bytes = body.bytes()
                if (bytes.isEmpty()) {
                    return@withContext FetchResult.Empty
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@withContext FetchResult.Error(IOException("Bitmap decode failed"))
                FetchResult.Success(bitmap)
            }
        } catch (e: Exception) {
            FetchResult.Error(e)
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://boudoir.ortlinde.com/random"
    }
}
