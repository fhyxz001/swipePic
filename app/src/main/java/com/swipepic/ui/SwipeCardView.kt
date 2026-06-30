package com.swipepic.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.swipepic.R
import kotlin.math.abs

/**
 * 可拖拽的图片卡片（对应 FR-05 ~ FR-09）。
 *
 * - 跟随手指位移并旋转（±15°，FR-06）
 * - 拖拽距离超过屏幕宽度 40% 松手 → 通知意图（FR-08），由外部决定飞走或弹回
 * - 未超过 40% 松手 → 自动弹回（FR-09）
 *
 * 卡片不自行决定飞走，因为右滑需先保存相册（FR-13：保存失败时卡片不飞走）。
 *
 * UX 优化：
 * - 越过 40% 阈值瞬间触发一次触感反馈，明确「松手即触发」的临界感
 * - animateFlyAway 调整复位顺序：先绑定新图再复位 alpha，消除旧图闪现一帧的问题
 */
class SwipeCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView: ImageView
    private var downRawX = 0f
    private var downRawY = 0f
    private var startTransX = 0f
    private var startTransY = 0f
    private var dragging = false
    private var thresholdCrossed = false

    var onSwipeIntent: ((SwipeDirection) -> Unit)? = null

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_card)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, CORNER_RADIUS)
            }
        }
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(imageView)
    }

    fun bindImage(bitmap: Bitmap?) {
        imageView.setImageBitmap(bitmap)
    }

    /** FR-08：卡片飞走动画，结束后回调（外部用于切换图片） */
    fun animateFlyAway(direction: SwipeDirection, onEnd: () -> Unit) {
        val sign = if (direction == SwipeDirection.RIGHT) 1f else -1f
        val endX = sign * width * 1.6f
        val endRot = sign * FLY_AWAY_ROTATION
        animate()
            .translationX(endX)
            .rotation(endRot)
            .alpha(0f)
            .setDuration(FLY_AWAY_DURATION)
            .withEndAction {
                // 先绑定新图（此时 alpha=0 不可见），再复位变换与 alpha，
                // 避免旧图以 alpha=1 闪现一帧造成 flicker
                onEnd()
                translationX = 0f
                translationY = 0f
                rotation = 0f
                alpha = 1f
            }
            .start()
    }

    /** FR-09：未超过阈值时弹回原位 */
    fun snapBack() {
        animate()
            .translationX(0f)
            .translationY(0f)
            .rotation(0f)
            .setDuration(SNAP_BACK_DURATION)
            .start()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startTransX = translationX
                startTransY = translationY
                dragging = true
                thresholdCrossed = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                translationX = startTransX + dx
                translationY = startTransY + dy
                val halfWidth = (swipeAreaWidth.coerceAtLeast(1)) / 2f
                val rotRatio = (translationX / halfWidth).coerceIn(-1f, 1f)
                rotation = rotRatio * MAX_ROTATION

                // 阈值跨越瞬间给一次触感反馈
                val threshold = swipeAreaWidth * SWIPE_THRESHOLD_RATIO
                val crossed = abs(translationX) > threshold
                if (crossed != thresholdCrossed) {
                    thresholdCrossed = crossed
                    if (crossed) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                dragging = false
                val threshold = swipeAreaWidth * SWIPE_THRESHOLD_RATIO
                if (abs(translationX) > threshold) {
                    val dir = if (translationX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                    // 卡片暂留于松手位置，由外部决定飞走或弹回（满足 FR-13）
                    onSwipeIntent?.invoke(dir)
                } else {
                    snapBack()
                }
            }
        }
        return true
    }

    private val swipeAreaWidth: Int
        get() = (parent as? View)?.width?.takeIf { it > 0 } ?: width

    companion object {
        private const val MAX_ROTATION = 15f          // FR-06：±15°
        private const val FLY_AWAY_ROTATION = 30f
        private const val SWIPE_THRESHOLD_RATIO = 0.4f // FR-08：40%
        private const val FLY_AWAY_DURATION = 300L
        private const val SNAP_BACK_DURATION = 200L
        private const val CORNER_RADIUS = 24f
    }
}
