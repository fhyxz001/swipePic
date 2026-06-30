package com.swipepic.data.model

import android.graphics.Bitmap

/**
 * 接口获取结果（区分成功 / 空数据 / 失败，对应 FR-22 空状态与 FR-23 网络异常）。
 *
 * Success 同时返回原始字节与解码后的（已降采样的）Bitmap：
 * - sourceBytes 用于无损保存到相册（避免二次 JPEG 压缩损失）
 * - bitmap 用于卡片展示（按显示尺寸降采样，控制内存）
 * - mimeType 由 BitmapFactory 检测，决定保存时的扩展名
 */
sealed class FetchResult {
    data class Success(
        val bitmap: Bitmap,
        val sourceBytes: ByteArray,
        val mimeType: String
    ) : FetchResult()

    object Empty : FetchResult()
    data class Error(val throwable: Throwable) : FetchResult()
}
