package com.yucl.etfshare.domain

import com.yucl.etfshare.data.Db
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「今日是否还需要联网抓取」的判定（与桌面版 main.py 的策略一致）。
 *
 * 满足其一即跳过：1) 今日已成功抓取且快照已含今日数据；2) 今日非工作日；
 * 3) 未到份额日报发布时点；4) 今日已在发布时点之后抓取过。
 */
object UpdatePolicy {

    /** 交易所 ETF 份额日报通常在该时点之后才发布。 */
    const val DATA_READY_HOUR = 18

    data class Decision(val skip: Boolean, val reason: String)

    fun decide(db: Db, now: LocalDateTime = LocalDateTime.now()): Decision {
        val st = db.getStates()
        val today = LocalDate.now().toString()

        if (st["last_fetch_date"] != today) return Decision(false, "今日尚未抓取")
        if (st["last_fetch_ok"] != "1") return Decision(false, "上次抓取核心数据未成功，重试")

        val latest = db.latestShareDate() ?: ""
        if (latest >= today) return Decision(true, "今日份额快照已在本地（$latest）")

        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return Decision(true, "今日为非工作日，无新增份额数据")
        }
        if (now.hour < DATA_READY_HOUR) {
            return Decision(true, "当日份额日报通常 $DATA_READY_HOUR:00 后才发布，暂无需重抓")
        }
        val last = st["last_fetch_time"].orEmpty()
        if (last.length >= 13) {
            val hh = last.substring(11, 13).toIntOrNull()
            if (hh != null && hh >= DATA_READY_HOUR) return Decision(true, "今日已在份额日报发布后抓取过")
        }
        return Decision(false, "上次抓取早于份额日报发布时点，补抓一次")
    }
}
