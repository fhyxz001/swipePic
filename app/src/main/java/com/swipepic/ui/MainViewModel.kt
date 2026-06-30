package com.swipepic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.swipepic.R
import com.swipepic.data.api.ImageApiService
import com.swipepic.data.model.CachedImage
import com.swipepic.data.model.PreloadState
import com.swipepic.data.model.SwipeAction
import com.swipepic.data.repository.ImageRepository
import com.swipepic.util.ImageSaver
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * UI 与数据层的协调者。
 *
 * - 接收卡片滑动意图（手势或按钮触发）
 * - 右滑先保存相册（FR-11），成功才让卡片飞走（FR-13）
 * - 左滑直接飞走（FR-14）
 * - 撤销时若上一步是保存，从相册删除（FR-19）
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val api = ImageApiService(httpClient)
    private val repository = ImageRepository(api, viewModelScope)

    val currentImage: StateFlow<CachedImage?> get() = repository.currentImage
    val nextImage: StateFlow<CachedImage?> get() = repository.nextImage
    val preloadState: StateFlow<PreloadState> get() = repository.preloadState
    val canUndo: StateFlow<Boolean> get() = repository.canUndo
    val isEmpty: StateFlow<Boolean> get() = repository.isEmpty
    val loadError: StateFlow<Throwable?> get() = repository.loadError
    val isInitialLoading: StateFlow<Boolean> get() = repository.isInitialLoading

    private val _toast = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val toast: SharedFlow<Int> = _toast.asSharedFlow()

    private val _cardCommand = MutableSharedFlow<CardCommand>(extraBufferCapacity = 4)
    val cardCommand: SharedFlow<CardCommand> = _cardCommand.asSharedFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            repository.initialize()
            _cardCommand.emit(CardCommand.Show(repository.currentImage.value?.bitmap))
        }
    }

    /** 卡片滑动意图入口（手势释放越过阈值，或按钮触发） */
    fun onSwipeIntent(direction: SwipeDirection) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                when (direction) {
                    SwipeDirection.RIGHT -> handleRightSwipe()
                    SwipeDirection.LEFT -> handleLeftSwipe()
                }
            } finally {
                _busy.value = false
            }
        }
    }

    /** 撤销（FR-17 ~ FR-20） */
    fun undo() {
        if (!repository.canUndo.value || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val entry = repository.undo() ?: return@launch
                if (entry.action == SwipeAction.SAVE && !entry.savedFilePath.isNullOrEmpty()) {
                    val deleted = ImageSaver.deleteFromGallery(getApplication(), entry.savedFilePath)
                    _toast.emit(if (deleted) R.string.toast_undo_save_removed else R.string.toast_undone)
                } else {
                    _toast.emit(R.string.toast_undone)
                }
                _cardCommand.emit(CardCommand.Show(repository.currentImage.value?.bitmap))
            } finally {
                _busy.value = false
            }
        }
    }

    /** FR-23：网络异常重试 */
    fun retry() {
        if (_busy.value) return
        viewModelScope.launch {
            repository.retryLoad()
            // 重试成功后需要把当前图片绑定到卡片
            _cardCommand.emit(CardCommand.Show(repository.currentImage.value?.bitmap))
        }
    }

    /** FR-22：空状态刷新 */
    fun refreshFromEmpty() {
        if (_busy.value) return
        viewModelScope.launch {
            repository.refreshFromEmpty()
            _cardCommand.emit(CardCommand.Show(repository.currentImage.value?.bitmap))
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()  // FR-29
    }

    private suspend fun handleLeftSwipe() {
        // FR-14 / FR-15：左滑跳过，直接飞走；FR-16：无提示
        val ok = repository.consumeAndAdvance(SwipeAction.SKIP, savedFilePath = null)
        if (ok) {
            _cardCommand.emit(CardCommand.FlyAway(SwipeDirection.LEFT, repository.currentImage.value?.bitmap))
        } else {
            _cardCommand.emit(CardCommand.SnapBack)
        }
    }

    private suspend fun handleRightSwipe() {
        val current = repository.currentImage.value ?: run {
            _cardCommand.emit(CardCommand.SnapBack)
            return
        }
        // FR-11：保存到相册
        val saveResult = ImageSaver.saveToGallery(getApplication(), current.bitmap, current.id)
        if (saveResult.isSuccess) {
            val savedPath = saveResult.getOrNull()
            val ok = repository.consumeAndAdvance(SwipeAction.SAVE, savedPath)
            if (ok) {
                // FR-12：保存成功，卡片飞走
                _cardCommand.emit(CardCommand.FlyAway(SwipeDirection.RIGHT, repository.currentImage.value?.bitmap))
                _toast.emit(R.string.toast_saved)
            } else {
                _cardCommand.emit(CardCommand.SnapBack)
                _toast.emit(R.string.toast_save_failed)
            }
        } else {
            // FR-13：保存失败，卡片不飞走
            _cardCommand.emit(CardCommand.SnapBack)
            _toast.emit(R.string.toast_save_failed)
        }
    }
}
