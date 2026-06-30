package com.swipepic.data.model

import android.graphics.Bitmap

/**
 * 预加载缓存数据结构（对应 PRD §5.3）。
 */
data class CachedImage(
    val id: String,
    val url: String,
    val bitmap: Bitmap,
    val timestamp: Long
)
