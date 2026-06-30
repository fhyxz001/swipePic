package com.swipepic.data.model

import android.graphics.Bitmap

/**
 * 接口获取结果（区分成功 / 空数据 / 失败，对应 FR-22 空状态与 FR-23 网络异常）。
 */
sealed class FetchResult {
    data class Success(val bitmap: Bitmap) : FetchResult()
    object Empty : FetchResult()
    data class Error(val throwable: Throwable) : FetchResult()
}
