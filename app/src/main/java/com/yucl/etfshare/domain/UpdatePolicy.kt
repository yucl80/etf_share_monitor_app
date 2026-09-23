package com.yucl.etfshare.domain

import com.yucl.etfshare.data.Db
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 「今日是否还需要联网抓取」的判定（与桌面版 main.py 的策略一致）。
 *
 * 满足其一即跳过：1) 今日已成功抓取且快照已含今日数据；2) 今日非工作日；
 * 3) 未到份额日报发布时点；4) 今日已在发布时点之后抓取过。
 *
 * 判定逻辑被抽成纯函数（[State] + 快照日期 + 当前时间），便于在 JVM 单测里
 * 覆盖各种时间点，不必依赖 Android 运行环境。
 */
object UpdatePolicy {

    /** 交易所 ETF 份额日报通常在该时点之后才发布。 */
    const val DATA_READY_HOUR = 18

    /**
     * 启动自动补抓的冷却时间（分钟）。
     *
     * 抓取可能因网络抖动而「核心数据未完整获取」，此时 last_fetch_ok=0，
     * 若不加冷却就会每次启动都重新全量抓取（很费流量也很慢）。
     * 手动点刷新不受此限制。
     */
    const val LAUNCH_COOLDOWN_MINUTES = 15L

    data class Decision(val skip: Boolean, val reason: String)

    /** 判定所需的运行状态（对应 run_state 表的关键字段）。 */
    data class State(
        val lastFetchDate: String?,
        val lastFetchTime: String?,
        val lastFetchOk: Boolean,
        val latestSnapshot: String?,
    )

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun stateOf(db: Db): State {
        val s = db.getStates()
        return State(
            lastFetchDate = s["last_fetch_date"],
            lastFetchTime = s["last_fetch_time"],
            lastFetchOk = s["last_fetch_ok"] == "1",
            latestSnapshot = s["latest_snapshot"],
        )
    }

    fun decide(db: Db, now: LocalDateTime = LocalDateTime.now()): Decision =
        decide(stateOf(db), db.latestShareDate(), now)

    fun decide(st: State, latestShareDate: String?, now: LocalDateTime = LocalDateTime.now()): Decision {
        val today = now.toLocalDate().toString()

        if (st.lastFetchDate != today) return Decision(false, "今日尚未抓取")
        if (!st.lastFetchOk) return Decision(false, "上次抓取核心数据未成功，重试")

        val latest = latestShareDate ?: ""
        if (latest >= today) return Decision(true, "今日份额快照已在本地（$latest）")

        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) {
            return Decision(true, "今日为非工作日，无新增份额数据")
        }
        if (now.hour < DATA_READY_HOUR) {
            return Decision(true, "当日份额日报通常 $DATA_READY_HOUR:00 后才发布，暂无需重抓")
        }
        val hh = st.lastFetchTime?.takeIf { it.length >= 13 }?.substring(11, 13)?.toIntOrNull()
        if (hh != null && hh >= DATA_READY_HOUR) return Decision(true, "今日已在份额日报发布后抓取过")
        return Decision(false, "上次抓取早于份额日报发布时点，补抓一次")
    }

    /**
     * 启动（或回到前台）时是否应该自动联网补抓。
     *
     * 在 [decide] 之上再加一道冷却：距上次抓取不足 [LAUNCH_COOLDOWN_MINUTES] 分钟，
     * 本次启动就不再自动重试，避免反复失败时形成「每次启动都全量抓取」的抖动。
     */
    fun decideOnLaunch(db: Db, now: LocalDateTime = LocalDateTime.now()): Decision =
        decideOnLaunch(stateOf(db), db.latestShareDate(), now)

    fun decideOnLaunch(
        st: State,
        latestShareDate: String?,
        now: LocalDateTime = LocalDateTime.now(),
    ): Decision {
        val d = decide(st, latestShareDate, now)
        if (d.skip) return d
        if (withinCooldown(st.lastFetchTime, now)) {
            return Decision(true, "距上次抓取不足 $LAUNCH_COOLDOWN_MINUTES 分钟，本次启动暂不自动重试")
        }
        return d
    }

    /** 最近一次抓取是否发生在冷却窗口内。 */
    fun withinCooldown(lastFetchTime: String?, now: LocalDateTime): Boolean {
        val t = parseStamp(lastFetchTime) ?: return false
        val minutes = ChronoUnit.MINUTES.between(t, now)
        return minutes in 0 until LAUNCH_COOLDOWN_MINUTES
    }

    /** 严格解析 "yyyy-MM-dd HH:mm:ss"，失败返回 null。 */
    internal fun parseStamp(value: String?): LocalDateTime? {
        if (value == null || value.length < 19) return null
        return try {
            LocalDateTime.parse(value.substring(0, 19), STAMP)
        } catch (_: Exception) {
            null
        }
    }

    /** 供界面展示：今天是不是「已经是最新」。 */
    fun isFreshToday(latestShareDate: String?, now: LocalDate = LocalDate.now()): Boolean =
        latestShareDate != null && latestShareDate >= now.toString()
}
