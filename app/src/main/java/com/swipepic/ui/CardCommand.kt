package com.swipepic.ui

import android.graphics.Bitmap

/**
 * ViewModel 向卡片视图下发的指令。
 */
sealed class CardCommand {
    /** 直接展示指定图片（首屏、撤销、重试后） */
    data class Show(val bitmap: Bitmap?) : CardCommand()

    /** 卡片飞走动画，结束后绑定 nextBitmap 并复位（FR-08 / FR-12 / FR-14） */
    data class FlyAway(val direction: SwipeDirection, val nextBitmap: Bitmap?) : CardCommand()

    /** 弹回原位（FR-09 / FR-13） */
    object SnapBack : CardCommand()
}
