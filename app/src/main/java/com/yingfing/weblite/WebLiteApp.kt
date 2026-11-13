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
