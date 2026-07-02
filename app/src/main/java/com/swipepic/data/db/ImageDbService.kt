package com.swipepic.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从本地 SQLite 数据库查询图片信息的服务。
 *
 * 数据库表结构：
 * - albums (id, title, created_at)
 * - images (id, url, album_id -> albums.id)
 */
class ImageDbService(private val dbManager: DatabaseManager) {

    /**
     * 从 images 表中随机返回一条图片 URL。
     * @return 图片 URL，无数据时返回 null
     */
    suspend fun getRandomImageUrl(): String? = withContext(Dispatchers.IO) {
        val db = dbManager.getDatabase()
        val cursor = db.rawQuery("SELECT url FROM images ORDER BY RANDOM() LIMIT 1", null)
        cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * 获取指定相册的随机图片 URL。
     * @param albumTitle 相册标题（对应 albums.title）
     */
    suspend fun getRandomImageUrlByAlbum(albumTitle: String): String? = withContext(Dispatchers.IO) {
        val db = dbManager.getDatabase()
        val cursor = db.rawQuery(
            """SELECT i.url FROM images i
               INNER JOIN albums a ON i.album_id = a.id
               WHERE a.title = ?
               ORDER BY RANDOM() LIMIT 1""",
            arrayOf(albumTitle)
        )
        cursor.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    /**
     * 获取所有相册标题。
     */
    suspend fun getAllAlbumTitles(): List<String> = withContext(Dispatchers.IO) {
        val db = dbManager.getDatabase()
        val cursor = db.rawQuery("SELECT title FROM albums ORDER BY title", null)
        cursor.use {
            val titles = mutableListOf<String>()
            while (it.moveToNext()) {
                titles.add(it.getString(0))
            }
            titles
        }
    }

    /**
     * 获取数据库中图片总数。
     */
    suspend fun getImageCount(): Int = withContext(Dispatchers.IO) {
        val db = dbManager.getDatabase()
        val cursor = db.rawQuery("SELECT COUNT(*) FROM images", null)
        cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}