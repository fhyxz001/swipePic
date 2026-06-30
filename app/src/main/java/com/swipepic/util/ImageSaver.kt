package com.swipepic.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * 将图片保存到系统相册 Pictures/SwipePic（FR-11）。
 * 撤销时按保存返回的 token 删除文件（FR-19）。
 *
 * token 规则：
 * - API >= 29：返回 content Uri 字符串（MediaStore）
 * - API <  29：返回绝对文件路径
 *
 * 优化：
 * - 优先直接写入原始字节 [saveBytesToGallery]，避免二次 JPEG 压缩导致的画质损失与 CPU 开销
 * - 保留 [saveToGallery]（Bitmap 重编码）作为兜底，供无原始字节的场景使用
 * - 扩展名随 mimeType 自适应，保证相册扫描正确
 */
object ImageSaver {

    private const val DIR_NAME = "SwipePic"
    private const val JPEG_QUALITY = 95

    /** 直接写入原始字节，无损且最快 */
    suspend fun saveBytesToGallery(
        context: Context,
        bytes: ByteArray,
        name: String,
        mimeType: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val ext = extensionFor(mimeType)
                val displayName = "$name.$ext"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveBytesViaMediaStore(context, bytes, displayName, mimeType)
                } else {
                    saveBytesViaLegacyFile(bytes, displayName)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Bitmap 重编码保存（兜底，无原始字节时使用） */
    suspend fun saveToGallery(context: Context, bitmap: Bitmap, name: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val displayName = "$name.jpg"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveBitmapViaMediaStore(context, bitmap, displayName)
                } else {
                    saveBitmapViaLegacyFile(bitmap, displayName)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    fun deleteFromGallery(context: Context, token: String): Boolean {
        return try {
            if (token.startsWith("content://")) {
                val uri = Uri.parse(token)
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                val file = File(token)
                if (file.exists()) file.delete() else false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> "jpg"
    }

    // ---------- 字节写入 ----------

    private fun saveBytesViaMediaStore(
        context: Context,
        bytes: ByteArray,
        displayName: String,
        mimeType: String
    ): Result<String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DIR_NAME")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return Result.failure(IllegalStateException("MediaStore insert failed"))
        try {
            val stream: OutputStream = resolver.openOutputStream(uri)
                ?: return Result.failure(IllegalStateException("Open output stream failed"))
            stream.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            return Result.success(uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return Result.failure(e)
        }
    }

    private fun saveBytesViaLegacyFile(bytes: ByteArray, displayName: String): Result<String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            DIR_NAME
        )
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("Create dir failed"))
        }
        val file = File(dir, displayName)
        file.outputStream().use { it.write(bytes) }
        return Result.success(file.absolutePath)
    }

    // ---------- Bitmap 重编码写入（兜底） ----------

    private fun saveBitmapViaMediaStore(
        context: Context,
        bitmap: Bitmap,
        displayName: String
    ): Result<String> {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$DIR_NAME")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: return Result.failure(IllegalStateException("MediaStore insert failed"))
        try {
            val stream: OutputStream = resolver.openOutputStream(uri)
                ?: return Result.failure(IllegalStateException("Open output stream failed"))
            stream.use {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    throw IllegalStateException("Bitmap compress failed")
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Result.success(uri.toString())
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return Result.failure(e)
        }
    }

    private fun saveBitmapViaLegacyFile(bitmap: Bitmap, displayName: String): Result<String> {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            DIR_NAME
        )
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.failure(IllegalStateException("Create dir failed"))
        }
        val file = File(dir, displayName)
        file.outputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IllegalStateException("Bitmap compress failed")
            }
        }
        return Result.success(file.absolutePath)
    }
}
