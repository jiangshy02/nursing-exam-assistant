package com.xiaoniu.nursing

import android.app.Application
import com.xiaoniu.nursing.database.AppDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 预初始化数据库
        AppDatabase.getInstance(this)
    }
}
