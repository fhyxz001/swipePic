package com.swipepic.data.repository

import com.swipepic.data.api.ImageApiService
import com.swipepic.data.model.CachedImage
import com.swipepic.data.model.FetchResult
import com.swipepic.data.model.HistoryEntry
import com.swipepic.data.model.PreloadState
import com.swipepic.data.model.SwipeAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import android.graphics.Bitmap

/**
 * 图片仓库 + 预加载状态机（对应 PRD §5）。
 *
 * - currentImage：当前展示的卡片
 * - nextImage：缓存池容量固定 1 张（FR-27），同时作为下层预览（FR-07）
 * - history：仅保留最近一次操作，用于撤销（FR-18）
 *
 * 缓存健壮性优化：
 * - 预加载失败后自动延迟重试一次，避免弱网下用户滑到空白
 * - discardNext 统一负责 bitmap 回收，杜绝双重 recycle
 * - undo / advance / release 路径均显式取消后台预加载任务，防止回收竞态
 */
class ImageRepository(
    private val api: ImageApiService,
    private val scope: CoroutineScope
) {

    private val _currentImage = MutableStateFlow<CachedImage?>(null)
    val currentImage: StateFlow<CachedImage?> = _currentImage.asStateFlow()

    private val _nextImage = MutableStateFlow<CachedImage?>(null)
    val nextImage: StateFlow<CachedImage?> = _nextImage.asStateFlow()

    private val _preloadState = MutableStateFlow(PreloadState.IDLE)
    val preloadState: StateFlow<PreloadState> = _preloadState.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _isEmpty = MutableStateFlow(false)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _loadError = MutableStateFlow<Throwable?>(null)
    val loadError: StateFlow<Throwable?> = _loadError.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(false)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val history = ArrayDeque<HistoryEntry>(2)
    private val mutex = Mutex()
    private var preloadingJob: Job? = null
    private var preloadRetryJob: Job? = null
    private var initialized = false

    /** FR-25：首屏加载 + 同时预加载下一张（5秒超时提醒） */
    suspend fun initialize() {
        if (initialized) return
        initialized = true
        _isInitialLoading.value = true
        _loadError.value = null
        try {
            withTimeout(5000L) {
                when (val r = api.fetchRandomImage()) {
                    is FetchResult.Success -> {
                        _currentImage.value = makeImage(r)
                        _isInitialLoading.value = false
                        triggerPreload()
                    }
                    is FetchResult.Empty -> {
                        _isEmpty.value = true
                        _preloadState.value = PreloadState.EMPTY
                        _isInitialLoading.value = false
                    }
                    is FetchResult.Error -> {
                        _loadError.value = r.throwable
                        _preloadState.value = PreloadState.IDLE
                        _isInitialLoading.value = false
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            _loadError.value = IOException("网络连接超时")
            _preloadState.value = PreloadState.IDLE
            _isInitialLoading.value = false
        }
    }

    /**
     * FR-26：滑动后立即将缓存切为当前卡片，同时异步预加载新的下一张。
     * 返回 true 表示前进已发生（卡片应飞走）；仅当无当前卡片时返回 false。
     * 即便后续拉取失败，也会进入加载/错误态，避免「保存了但卡片未飞走」的不一致。
     */
    suspend fun consumeAndAdvance(action: SwipeAction, savedFilePath: String?): Boolean {
        return mutex.withLock {
            val current = _currentImage.value ?: return@withLock false

            // 丢弃更早的历史 bitmap（FR-18 仅保留一步）
            history.forEach { it.image.bitmap.takeIf { b -> b !== current.bitmap }?.let(::recycleSafe) }
            history.clear()
            history.addLast(HistoryEntry(current, action, savedFilePath))
            _canUndo.value = true

            preloadRetryJob?.cancel()
            val next = _nextImage.value
            _nextImage.value = null
            if (next != null) {
                _currentImage.value = next
                _preloadState.value = PreloadState.IDLE
                _isEmpty.value = false
                _loadError.value = null
                triggerPreload()
                true
            } else {
                // 缓存尚未就绪：同步拉一张作为兜底；失败则进入错误态（UI 显示重试）
                _loadError.value = null
                _isInitialLoading.value = true
                when (val r = api.fetchRandomImage()) {
                    is FetchResult.Success -> {
                        _currentImage.value = makeImage(r)
                        _isInitialLoading.value = false
                        triggerPreload()
                        true
                    }
                    is FetchResult.Empty -> {
                        _currentImage.value = null
                        _isEmpty.value = true
                        _preloadState.value = PreloadState.EMPTY
                        _isInitialLoading.value = false
                        true
                    }
                    is FetchResult.Error -> {
                        _currentImage.value = null
                        _loadError.value = r.throwable
                        _preloadState.value = PreloadState.IDLE
                        _isInitialLoading.value = false
                        true
                    }
                }
            }
        }
    }

    /**
     * FR-17 ~ FR-20：撤销上一步。
     * 返回被撤销的记录（若 action 为 SAVE，由调用方删除相册文件，对应 FR-19）；
     * 无历史时返回 null。
     * FR-28：撤销返回的图片直接展示，不重新请求；撤销后重置预加载队列。
     */
    suspend fun undo(): HistoryEntry? {
        return mutex.withLock {
            val entry = history.lastOrNull() ?: return@withLock null
            history.removeLast()
            _canUndo.value = false

            // 丢弃当前卡片 bitmap 与预加载缓存（重置预加载队列）
            val currentBitmap = _currentImage.value?.bitmap
            preloadingJob?.cancel()
            preloadRetryJob?.cancel()
            discardNext()
            currentBitmap?.let(::recycleSafe)

            _currentImage.value = entry.image
            _isEmpty.value = false
            _loadError.value = null
            _preloadState.value = PreloadState.IDLE
            triggerPreload()
            entry
        }
    }

    /** FR-23：网络异常 / 加载失败时的重试入口（重新初始化或拉取一张） */
    suspend fun retryLoad() {
        if (_currentImage.value == null) {
            initialized = false
            initialize()
        } else if (_nextImage.value == null && _preloadState.value != PreloadState.LOADING) {
            _loadError.value = null
            _isEmpty.value = false
            triggerPreload()
        }
    }

    /** FR-22：空状态下用户点击刷新 */
    suspend fun refreshFromEmpty() {
        mutex.withLock {
            _isEmpty.value = false
            _loadError.value = null
            _preloadState.value = PreloadState.IDLE
            preloadingJob?.cancel()
            preloadRetryJob?.cancel()
            discardNext()
        }
        if (_currentImage.value == null) {
            _isInitialLoading.value = true
            when (val r = api.fetchRandomImage()) {
                is FetchResult.Success -> {
                    _currentImage.value = makeImage(r)
                    _isInitialLoading.value = false
                    triggerPreload()
                }
                is FetchResult.Empty -> {
                    _isEmpty.value = true
                    _preloadState.value = PreloadState.EMPTY
                    _isInitialLoading.value = false
                }
                is FetchResult.Error -> {
                    _loadError.value = r.throwable
                    _isInitialLoading.value = false
                }
            }
        } else {
            triggerPreload()
        }
    }

    /** FR-29：页面销毁时释放所有预加载缓存 */
    fun release() {
        preloadingJob?.cancel()
        preloadRetryJob?.cancel()
        discardNext()
        _currentImage.value = null
        history.forEach { recycleSafe(it.image.bitmap) }
        history.clear()
        _preloadState.value = PreloadState.IDLE
    }

    private fun triggerPreload() {
        if (_nextImage.value != null) return
        if (_preloadState.value == PreloadState.LOADING) return
        if (_preloadState.value == PreloadState.EMPTY) return
        preloadRetryJob?.cancel()
        preloadingJob?.cancel()
        _preloadState.value = PreloadState.LOADING
        preloadingJob = scope.launch {
            when (val r = api.fetchRandomImage()) {
                is FetchResult.Success -> {
                    _nextImage.value = makeImage(r)
                    _preloadState.value = PreloadState.CACHED
                }
                is FetchResult.Empty -> _preloadState.value = PreloadState.EMPTY
                is FetchResult.Error -> {
                    // 失败后回到 IDLE，并安排一次延迟重试，提升弱网下的缓存命中率
                    _preloadState.value = PreloadState.IDLE
                    schedulePreloadRetry()
                }
            }
        }
    }

    /** 弱网下对失败的预加载做一次延迟重试；仅当仍缺缓存且未在加载时生效 */
    private fun schedulePreloadRetry() {
        preloadRetryJob?.cancel()
        preloadRetryJob = scope.launch {
            delay(PRELOAD_RETRY_DELAY_MS)
            if (_nextImage.value == null &&
                _preloadState.value == PreloadState.IDLE &&
                _currentImage.value != null &&
                !_isEmpty.value
            ) {
                triggerPreload()
            }
        }
    }

    /** 先置空 StateFlow 再回收 bitmap，避免 UI 拿到已回收的 Bitmap */
    private fun discardNext() {
        val cached = _nextImage.value
        _nextImage.value = null
        cached?.bitmap?.let(::recycleSafe)
    }

    private fun makeImage(r: FetchResult.Success): CachedImage {
        return CachedImage(
            id = UUID.randomUUID().toString(),
            url = ImageApiService.DEFAULT_BASE_URL,
            bitmap = r.bitmap,
            sourceBytes = r.sourceBytes,
            mimeType = r.mimeType,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun recycleSafe(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    companion object {
        // 预加载失败后的延迟重试间隔
        private const val PRELOAD_RETRY_DELAY_MS = 2000L
    }
}
