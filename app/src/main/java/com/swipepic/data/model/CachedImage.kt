package com.swipepic.data.model

import android.graphics.Bitmap

/**
 * 预加载缓存数据结构（对应 PRD §5.3）。
 *
 * @param sourceBytes 原始图片字节，用于无损保存到相册（FR-11）
 * @param mimeType 图片 MIME 类型（如 image/jpeg），决定保存扩展名
 */
data class CachedImage(
    val id: String,
    val url: String,
    val bitmap: Bitmap,
    val sourceBytes: ByteArray,
    val mimeType: String,
    val timestamp: Long
) {
    /**
     * 由 mimeType 推导文件扩展名（不含点）。
     * 未知类型回退为 jpg，保证 MediaStore 写入成功。
     */
    val fileExtension: String
        get() = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }
}
