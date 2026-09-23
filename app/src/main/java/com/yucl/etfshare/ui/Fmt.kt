package com.yucl.etfshare.ui

import java.util.Locale

/** 数值与日期格式化（与桌面版报告口径一致：份额一律「亿份」）。 */
object Fmt {

    fun yi(value: Double): String = String.format(Locale.CHINA, "%,.2f", value)

    fun signedYi(value: Double): String = (if (value > 0) "+" else "") + yi(value)

    fun pct(p: Double?): String =
        if (p == null) "" else (if (p > 0) "+" else "") + String.format(Locale.CHINA, "%.2f%%", p * 100)

    /** "2026-08-21" -> "08-21" */
    fun monthDay(date: String?): String =
        if (date != null && date.length >= 10) date.substring(5) else (date ?: "—")

    fun seconds(ms: Long): String = String.format(Locale.CHINA, "%.1f 秒", ms / 1000.0)
}
