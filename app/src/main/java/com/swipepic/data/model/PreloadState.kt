package com.swipepic.data.model

/**
 * 预加载状态机（对应 PRD §5.2）。
 */
enum class PreloadState {
    IDLE,
    LOADING,
    CACHED,
    EMPTY
}
