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
 *
 * 优化点：
 * 1. 按 显示尺寸降采样解码 Bitmap，避免大图直接解码导致 OOM
 * 2. 同时保留原始字节 sourceBytes，供相册无损保存
 * 3. 对瞬时网络异常（IOException）自动重试一次，提升弱网体验
 * 4. 通过 BitmapFactory.Options.outMimeType 检测真实图片类型，决定保存扩展名
 */
class ImageApiService(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {

    /**
     * @param reqWidth  期望解码宽度（px），用于计算 inSampleSize
     * @param reqHeight 期望解码高度（px）
     */
    suspend fun fetchRandomImage(
        reqWidth: Int = DEFAULT_REQ_WIDTH,
        reqHeight: Int = DEFAULT_REQ_HEIGHT
    ): FetchResult = fetchImage(baseUrl, reqWidth, reqHeight)

    /**
     * 从指定 URL 拉取图片（由本地 DB 获取 URL 后调用）。
     */
    suspend fun fetchImage(
        url: String,
        reqWidth: Int = DEFAULT_REQ_WIDTH,
        reqHeight: Int = DEFAULT_REQ_HEIGHT
    ): FetchResult = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(MAX_ATTEMPTS) { _ ->
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        // HTTP 错误码不重试，直接返回
                        return@withContext FetchResult.Error(IOException("HTTP ${resp.code}"))
                    }
                    val body = resp.body
                        ?: return@withContext FetchResult.Error(IOException("Empty response body"))
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) return@withContext FetchResult.Empty

                    // 单次 bounds 解码：同时用于 MIME 检测与降采样计算
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        return@withContext FetchResult.Error(IOException("Bitmap decode failed"))
                    }

                    val mimeType = bounds.outMimeType
                        ?.takeIf { it.isNotEmpty() && it != "image/*" }
                        ?: sniffMimeTypeByMagic(bytes)

                    val sampleSize = calculateInSampleSize(
                        bounds.outWidth, bounds.outHeight, reqWidth, reqHeight
                    )
                    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@withContext FetchResult.Error(IOException("Bitmap decode failed"))

                    return@withContext FetchResult.Success(bitmap, bytes, mimeType)
                }
            } catch (e: IOException) {
                // 网络层异常：记录后重试（若仍有次数）
                lastError = e
            } catch (e: Exception) {
                // 非网络异常不重试
                return@withContext FetchResult.Error(e)
            }
        }
        FetchResult.Error(lastError ?: IOException("Unknown error"))
    }

    /**
     * 计算 inSampleSize：使解码后尺寸仍略大于目标尺寸（2 倍以内），
     * 既能控制内存，又保证显示清晰度。
     */
    private fun calculateInSampleSize(
        outWidth: Int, outHeight: Int, reqWidth: Int, reqHeight: Int
    ): Int {
        var sampleSize = 1
        while (outWidth / sampleSize > reqWidth * 2 ||
            outHeight / sampleSize > reqHeight * 2
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    /** 由文件头魔数嗅探 MIME，作为 BitmapFactory 检测失败时的兜底 */
    private fun sniffMimeTypeByMagic(bytes: ByteArray): String {
        if (bytes.size < 12) return "image/jpeg"
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() -> "image/gif"
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() -> "image/webp"
            else -> "image/jpeg"
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://xrw.christin3.com/api/random-photo"

        // 卡片显示区域约 340x450dp，按 xhdpi(2x) 估算约 680x900px
        private const val DEFAULT_REQ_WIDTH = 680
        private const val DEFAULT_REQ_HEIGHT = 900

        // 弱网下对 IOException 自动重试一次
        private const val MAX_ATTEMPTS = 2
    }
}
