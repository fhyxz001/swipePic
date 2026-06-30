package com.swipepic

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swipepic.databinding.ActivityMainBinding
import com.swipepic.ui.CardCommand
import com.swipepic.ui.SwipeDirection
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ensureStoragePermission()

        binding.foregroundCard.onSwipeIntent = { direction ->
            viewModel.onSwipeIntent(direction)
        }

        binding.btnSave.setOnClickListener {
            viewModel.onSwipeIntent(SwipeDirection.RIGHT)
        }
        binding.btnSkip.setOnClickListener {
            viewModel.onSwipeIntent(SwipeDirection.LEFT)
        }
        binding.btnUndo.setOnClickListener { viewModel.undo() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnRefreshEmpty.setOnClickListener { viewModel.refreshFromEmpty() }

        observeViewModel()
    }

    private fun observeViewModel() {
        // 背景预览卡片（FR-07）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.nextImage.collect { image ->
                    binding.backgroundCard.bindImage(image?.bitmap)
                    binding.backgroundCard.visibility =
                        if (image != null) View.VISIBLE else View.GONE
                }
            }
        }

        // 撤销按钮可用性（FR-21）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.canUndo.collect { enabled ->
                    binding.btnUndo.isEnabled = enabled
                    binding.btnUndo.alpha = if (enabled) 1f else 0.4f
                }
            }
        }

        // 各类覆盖层显隐
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isInitialLoading.collect { loading ->
                    binding.loadingOverlay.visibility =
                        if (loading) View.VISIBLE else View.GONE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isEmpty.collect { empty ->
                    binding.emptyOverlay.visibility =
                        if (empty) View.VISIBLE else View.GONE
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadError.collect { error ->
                    binding.errorOverlay.visibility =
                        if (error != null && viewModel.currentImage.value == null) View.VISIBLE else View.GONE
                }
            }
        }

        // 卡片操作指令
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cardCommand.collect { command ->
                    when (command) {
                        is CardCommand.Show -> {
                            binding.foregroundCard.bindImage(command.bitmap)
                            binding.foregroundCard.translationX = 0f
                            binding.foregroundCard.translationY = 0f
                            binding.foregroundCard.rotation = 0f
                            binding.foregroundCard.alpha = 1f
                        }
                        is CardCommand.FlyAway -> {
                            binding.foregroundCard.animateFlyAway(command.direction) {
                                binding.foregroundCard.bindImage(command.nextBitmap)
                            }
                        }
                        CardCommand.SnapBack -> binding.foregroundCard.snapBack()
                    }
                }
            }
        }

        // Toast 反馈（FR-12 / FR-13 / FR-16）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.toast.collect { resId ->
                    Toast.makeText(this@MainActivity, resId, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** API 24-28 需运行时申请 WRITE_EXTERNAL_STORAGE 才能写入公共相册 */
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return
        val granted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.isNotEmpty() &&
            grantResults[0] != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.toast_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val REQ_STORAGE = 1001
    }
}
