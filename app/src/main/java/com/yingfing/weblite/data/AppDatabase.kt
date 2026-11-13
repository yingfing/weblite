package com.yingfing.weblite.data

import android.content.Context
import androidx.room.*

/** 历史记录 */
@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long = System.currentTimeMillis(),
)

/** 书签 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC LIMIT 500")
    fun recent(): kotlinx.coroutines.flow.Flow<List<HistoryEntity>>

    @Insert suspend fun add(h: HistoryEntity)

    @Query("DELETE FROM history") suspend fun clear()
    @Query("DELETE FROM history WHERE id = :id") suspend fun delete(id: Long)
}

@Dao interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun all(): kotlinx.coroutines.flow.Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE url = :url LIMIT 1")
    suspend fun find(url: String): BookmarkEntity?

    @Insert suspend fun add(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE url = :url") suspend fun remove(url: String)
}

@Database(entities = [HistoryEntity::class, BookmarkEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
}

/** 轻量设置：工具栏位置 / 搜索引擎 / 主页 / 特效开关 */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var toolbarAtTop: Boolean
        get() = p.getBoolean("toolbar_at_top", true)          // 默认置顶（遥控器更好按）
        set(v) = p.edit().putBoolean("toolbar_at_top", v).apply()

    var engineIndex: Int
        get() = p.getInt("engine", 0)
        set(v) = p.edit().putInt("engine", v).apply()

    var homepage: String
        get() = p.getString("homepage", "https://www.bing.com") ?: "https://www.bing.com"
        set(v) = p.edit().putString("homepage", v).apply()

    /** 特效总开关：TV 上默认关（保留给未来可能的模糊/动画） */
    var fancyEffects: Boolean
        get() = p.getBoolean("fancy", false)
        set(v) = p.edit().putBoolean("fancy", v).apply()
}
