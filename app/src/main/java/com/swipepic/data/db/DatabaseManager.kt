package com.swipepic.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

/**
 * 管理预置 SQLite 数据库的复制与打开。
 * 首次使用时将 assets/databases/ 下的 .db 文件复制到 app 内部数据库目录，
 * 此后直接打开内部文件。
 */
class DatabaseManager(private val context: Context) {

    private var database: SQLiteDatabase? = null

    /**
     * 获取数据库实例。首次调用时会触发复制。
     */
    fun getDatabase(): SQLiteDatabase {
        val db = database
        if (db != null && db.isOpen) return db

        val dbFile = getDbFile()
        if (!dbFile.exists()) {
            copyDatabase(dbFile)
        }

        return SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).also { database = it }
    }

    fun close() {
        database?.close()
        database = null
    }

    private fun getDbFile(): File {
        return context.getDatabasePath(DB_NAME)
    }

    private fun copyDatabase(destFile: File) {
        destFile.parentFile?.mkdirs()
        context.assets.open("databases/$DB_NAME").use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    companion object {
        private const val DB_NAME = "cosplay_images.db"
    }
}