package com.yucl.etfshare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.work.DailyUpdateScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 首次启动导入初始数据快照，之后再首次读取数据库
        Db.ensureSeeded(this)
        Db.get(this)
        IndexDict.get(this)
        createChannel()
        DailyUpdateScheduler.schedule(this)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "数据更新",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "每日份额数据自动更新结果" }
        mgr.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "etf_update"
    }
}
