package com.yucl.etfshare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.data.LocalDataStatus
import com.yucl.etfshare.work.DailyUpdateScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 保证本地一定有可用数据：数据库缺失/损坏/为空时重新导入打包的初始快照，
        // 这样首屏直接就有数据可看，不必等联网、也不必手动点刷新。
        seedStatus = try {
            Db.ensureLocalData(this)
        } catch (t: Throwable) {
            // 连 OutOfMemoryError 这类错误也要兜住：宁可退回联网抓取，也不能一起动就崩
            Log.w(TAG, "本地数据准备失败", t)
            LocalDataStatus(false, false, "本地数据准备失败：${t.message}")
        }
        Db.get(this)
        IndexDict.get(this)
        createChannel()
        try {
            DailyUpdateScheduler.schedule(this)
        } catch (e: Exception) {
            // 后台任务注册失败不应影响前台使用
            Log.w(TAG, "每日自动更新注册失败", e)
        }
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
        private const val TAG = "App"

        /** 启动时本地数据准备的结果，界面用它给出「数据从哪来」的提示。 */
        @Volatile
        var seedStatus: LocalDataStatus = LocalDataStatus(true, false, "未初始化")
            private set
    }
}
