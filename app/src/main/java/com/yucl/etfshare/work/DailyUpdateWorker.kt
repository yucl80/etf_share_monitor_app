package com.yucl.etfshare.work

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yucl.etfshare.App
import com.yucl.etfshare.R
import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.data.Sources
import com.yucl.etfshare.domain.ReportCalc
import com.yucl.etfshare.domain.UpdatePolicy
import com.yucl.etfshare.domain.Updater
import com.yucl.etfshare.ui.MainActivity
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** 每日自动更新：抓取最新份额 -> 重算汇总 -> 发通知。 */
class DailyUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = Db.get(applicationContext)
        val decision = UpdatePolicy.decide(db)
        if (decision.skip) {
            return Result.success(workDataOf("skipped" to true, "reason" to decision.reason))
        }
        val dict = IndexDict.get(applicationContext)
        val updater = Updater(db, Sources(dict), dict)
        val result = try {
            updater.run()
        } catch (e: Exception) {
            notify("自动更新失败", e.message ?: "网络异常")
            return Result.retry()
        }
        val rows = ReportCalc.aggregateByIndex(ReportCalc.computeEtfChanges(db))
        notify(
            "份额数据已更新",
            "最新快照 ${result.latestSnapshot ?: "无"} · 指数维度 ${rows.size} 个 · 深市 ${result.szseRows} 条 / 沪市 ${result.sseRows} 条",
        )
        return if (result.ok) Result.success() else Result.retry()
    }

    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val mgr = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        val intent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            mgr.notify(1001, notification)
        } catch (_: SecurityException) {
            // 权限被拒时静默忽略
        }
    }
}

/** 每日更新任务的注册 / 注销。 */
object DailyUpdateScheduler {

    private const val UNIQUE_NAME = "etf-daily-update"
    private const val PREFS = "etf_prefs"
    private const val KEY_ENABLED = "auto_daily"

    /** 默认运行时间：每日 19:05（份额日报通常 18:00 后发布）。 */
    private const val TARGET_HOUR = 19
    private const val TARGET_MINUTE = 5

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    fun schedule(context: Context) {
        if (!isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<DailyUpdateWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMinutes(), TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag(UNIQUE_NAME)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    private fun delayMinutes(): Long {
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(TARGET_HOUR, TARGET_MINUTE)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return ChronoUnit.MINUTES.between(now, target).coerceAtLeast(1L)
    }
}
