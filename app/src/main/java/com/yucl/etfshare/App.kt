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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 本地数据准备（可能要解压 8MB 快照、打开数据库做校验）**放到后台线程**。
        // 之前是在这里同步执行的，Application.onCreate 挡住主线程就等于挡住第一帧，
        // 表现就是「打开 App 先白屏一会儿才出界面」。界面在真正读库之前 await 它。
        localDataReady = appScope.async(Dispatchers.IO) {
            try {
                Db.ensureLocalData(this@App)
            } catch (t: Throwable) {
                // 连 OutOfMemoryError 这类错误也要兜住：宁可退回联网抓取，也不能一起动就崩
                Log.w(TAG, "本地数据准备失败", t)
                LocalDataStatus(false, false, "本地数据准备失败：${t.message}")
            }
        }
        // 这两个只是构造对象（不打开数据库），开销可忽略
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

        /** 后台专用作用域：只跑本地数据准备这类一次性任务。 */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** 本地数据准备任务；界面在读数之前 await 它，避免与导入过程抢文件。 */
        @Volatile
        var localDataReady: Deferred<LocalDataStatus>? = null
            private set

        /** 本地数据准备结果；尚未完成时给出「进行中」状态。 */
        val seedStatus: LocalDataStatus
            get() {
                val d = localDataReady ?: return PENDING
                if (!d.isCompleted) return PENDING
                return runCatching { d.getCompleted() }
                    .getOrElse { LocalDataStatus(false, false, "本地数据准备失败：${it.message}") }
            }

        private val PENDING = LocalDataStatus(true, false, "正在准备本地数据…")
    }
}
