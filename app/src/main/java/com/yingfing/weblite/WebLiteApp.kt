package com.yingfing.weblite

import android.app.Application
import androidx.room.Room
import com.yingfing.weblite.data.AppDatabase
import com.yingfing.weblite.data.Settings

class WebLiteApp : Application() {
    lateinit var db: AppDb
        private set
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDb(
            Room.databaseBuilder(this, AppDatabase::class.java, "weblite.db")
                .fallbackToDestructiveMigration()
                .build()
        )
        settings = Settings(this)
        com.yingfing.weblite.util.DebugLog.init(this, settings.debugLog)
        // 未捕获异常也落盘，电视上崩溃后能拉到现场
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            com.yingfing.weblite.util.DebugLog.e("未捕获崩溃 @${t.name}", e)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    companion object {
        lateinit var instance: WebLiteApp
            private set
        val db: AppDb get() = instance.db
        val settings: Settings get() = instance.settings
    }
}

class AppDb(val raw: AppDatabase) {
    val history by lazy { raw.historyDao() }
    val bookmarks by lazy { raw.bookmarkDao() }
}
