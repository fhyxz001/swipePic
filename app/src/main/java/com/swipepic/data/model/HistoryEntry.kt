package com.swipepic.data.model

/**
 * 一次滑动操作的历史记录（用于撤销，FR-17 ~ FR-20）。
 * 仅保留最近一条（FR-18）。
 */
data class HistoryEntry(
    val image: CachedImage,
    val action: SwipeAction,
    val savedFilePath: String?
)
