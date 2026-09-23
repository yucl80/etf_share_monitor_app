package com.yucl.etfshare.data

import android.content.Context

/**
 * 本地偏好设置（SharedPreferences）。
 *
 * 只放「开关类」配置；业务数据（份额快照、ETF 名单、运行状态）一律落 SQLite，
 * 保证离线也能看到上一次的数据。
 */
object Prefs {

    private const val NAME = "etf_prefs"
    private const val KEY_AUTO_DAILY = "auto_daily"
    private const val KEY_AUTO_ON_LAUNCH = "auto_on_launch"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 每日后台自动更新（WorkManager），默认开启。 */
    fun autoDaily(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_DAILY, true)

    fun setAutoDaily(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_DAILY, enabled).apply()
    }

    /** 启动（回到前台）时自动检查并补抓当天数据，默认开启。 */
    fun autoOnLaunch(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_ON_LAUNCH, true)

    fun setAutoOnLaunch(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_ON_LAUNCH, enabled).apply()
    }
}
