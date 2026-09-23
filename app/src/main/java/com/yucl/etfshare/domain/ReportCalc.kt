package com.yucl.etfshare.domain

import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.EtfChange
import com.yucl.etfshare.data.IndexRow
import com.yucl.etfshare.data.ShareRecord
import com.yucl.etfshare.data.WindowSpec
import com.yucl.etfshare.data.WindowStat
import com.yucl.etfshare.data.Windows
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 汇总计算：与桌面版 report.py 的 compute_etf_changes / aggregate_by_index 完全同口径。
 *
 * 基期选取规则：在 [今日-N-容限, 今日-N+容限] 内取距目标日最近的份额记录，
 * 且基期须早于当前快照 N/2 天以上（避免基期与当前过于接近）。
 */
object ReportCalc {

    /**
     * 已解析日期的份额记录。
     *
     * 首屏汇总时的热点是「为每只 ETF 的每个窗口挑选基期」，如果每次都重新
     * `LocalDate.parse`，16 万+ 条快照 × 5 个窗口会产生百万次解析，在中低端
     * 手机上会让首屏明显发卡。因此这里一次性把日期解析成 epochDay 缓存起来。
     */
    internal class Dated(val date: String, val shares: Double, val epochDay: Long)

    fun computeEtfChanges(db: Db): Map<String, EtfChange> {
        val all = db.getAllShares()
        val meta = db.getMeta()
        val today = LocalDate.now()
        val out = LinkedHashMap<String, EtfChange>()

        for ((code, records) in all) {
            if (records.isEmpty()) continue
            val dated = records
                .sortedBy { it.date }
                .map { Dated(it.date, it.shares, LocalDate.parse(it.date).toEpochDay()) }
            val cur = dated.last()
            val curDate = LocalDate.ofEpochDay(cur.epochDay)
            val windows = HashMap<String, WindowStat?>()

            for (spec in Windows.ALL) {
                val picked = pickBaseDated(dated, spec, today, curDate)
                windows[spec.key] = picked?.let { (b, gap) ->
                    val delta = cur.shares - b.shares
                    WindowStat(
                        key = spec.key,
                        delta = delta,
                        pct = if (b.shares != 0.0) delta / b.shares else null,
                        baseDate = b.date,
                        curDate = cur.date,
                        gap = Math.round(gap * 10.0) / 10.0,
                    )
                }
            }

            val m = meta[code]
            out[code] = EtfChange(
                code = code,
                name = m?.name ?: code,
                indexName = m?.indexName,
                indexCode = m?.indexCode,
                shares = cur.shares,
                curDate = cur.date,
                windows = windows,
            )
        }
        return out
    }

    /**
     * 为某个窗口挑选基期记录（返回 基期记录 与 距目标日的天数）。
     *
     * 规则（与桌面版逐字对齐）：
     *  1. 基期须**明显早于**当前快照 —— 早于 curDate - N/2 天。
     *     注意这里用「半天」精度，不能用整数天截断：N=1 时 N/2=0.5 天，
     *     若写成 `minusDays(0)` 会把当前快照自己当成基期，导致 1 日变化恒为 0。
     *  2. 距目标日（今日 - N 天）的偏差不得超过容限 tol，取偏差最小者。
     */
    internal fun pickBase(
        sorted: List<ShareRecord>,
        spec: WindowSpec,
        today: LocalDate,
        curDate: LocalDate,
    ): Pair<ShareRecord, Double>? =
        pickBaseDated(
            sorted.map { Dated(it.date, it.shares, LocalDate.parse(it.date).toEpochDay()) },
            spec,
            today,
            curDate,
        )?.let { (d, gap) -> ShareRecord(d.date, d.shares) to gap }

    internal fun pickBaseDated(
        dated: List<Dated>,
        spec: WindowSpec,
        today: LocalDate,
        curDate: LocalDate,
    ): Pair<Dated, Double>? {
        val curEpoch = curDate.toEpochDay() * 86_400L
        val newestAllowed = curEpoch - spec.days * 43_200L // days * 0.5 天
        // 目标日 = 今日 - N 天；天数差直接用 epochDay 相减（天粒度），
        // 与 ChronoUnit.DAYS.between 完全等价，但省掉热循环里的对象创建。
        val targetEpoch = today.toEpochDay() - spec.days
        var best: Dated? = null
        var bestGap = Long.MAX_VALUE
        for (r in dated) {
            if (r.epochDay * 86_400L > newestAllowed) continue
            val gap = abs(r.epochDay - targetEpoch)
            if (gap > spec.tol) continue
            if (gap < bestGap) {
                bestGap = gap
                best = r
            }
        }
        val b = best ?: return null
        return b to bestGap.toDouble()
    }

    /** 按指数代码汇总：份额总数 + 各窗口变化求和，并保留成分 ETF 明细。 */
    fun aggregateByIndex(changes: Map<String, EtfChange>): List<IndexRow> {
        class Acc {
            var indexName: String = ""
            var etfCount = 0
            var shares = 0.0
            val members = ArrayList<EtfChange>()
            val delta = HashMap<String, Double>()
            val pctDen = HashMap<String, Double>()
            val hit = HashMap<String, Int>()
            val bases = HashMap<String, HashMap<String, Int>>()
            val curDate = HashMap<String, String>()
        }

        val groups = LinkedHashMap<String, Acc>()
        for ((_, info) in changes) {
            val idx = info.indexCode ?: continue
            val g = groups.getOrPut(idx) {
                val a = Acc()
                a.indexName = info.indexName ?: idx
                for (k in Windows.KEYS) {
                    a.delta[k] = 0.0
                    a.pctDen[k] = 0.0
                    a.hit[k] = 0
                    a.bases[k] = HashMap()
                    a.curDate[k] = ""
                }
                a
            }
            g.etfCount++
            g.shares += info.shares
            g.members.add(info)
            for (k in Windows.KEYS) {
                val w = info.windows[k] ?: continue
                g.delta[k] = (g.delta[k] ?: 0.0) + w.delta
                // 基期份额 = 当前份额 - 变化量
                g.pctDen[k] = (g.pctDen[k] ?: 0.0) + (info.shares - w.delta)
                g.hit[k] = (g.hit[k] ?: 0) + 1
                val bases = g.bases[k]!!
                bases[w.baseDate] = (bases[w.baseDate] ?: 0) + 1
                val prev = g.curDate[k].orEmpty()
                if (w.curDate > prev) g.curDate[k] = w.curDate
            }
        }

        val now = LocalDate.now()
        val rows = ArrayList<IndexRow>(groups.size)
        for ((idx, g) in groups) {
            val windows = HashMap<String, WindowStat?>()
            val variants = HashMap<String, Int>()
            for (spec in Windows.ALL) {
                val hit = g.hit[spec.key] ?: 0
                val den = g.pctDen[spec.key] ?: 0.0
                if (hit > 0 && den != 0.0) {
                    val bases = g.bases[spec.key]!!
                    val base = bases.maxByOrNull { it.value }!!.key
                    val delta = g.delta[spec.key] ?: 0.0
                    val gap = abs(ChronoUnit.DAYS.between(LocalDate.parse(base), now) - spec.days).toDouble()
                    windows[spec.key] = WindowStat(
                        key = spec.key,
                        delta = delta,
                        pct = delta / den,
                        baseDate = base,
                        curDate = g.curDate[spec.key].orEmpty(),
                        gap = gap,
                    )
                    variants[spec.key] = bases.size
                } else {
                    windows[spec.key] = null
                }
            }
            rows.add(
                IndexRow(
                    indexCode = idx,
                    indexName = g.indexName,
                    etfCount = g.etfCount,
                    shares = g.shares,
                    members = g.members.sortedByDescending { it.shares },
                    windows = windows,
                    baseVariants = variants,
                )
            )
        }
        return rows
    }

    /** 默认排序：最近 1 日变化降序，无数据行恒排末尾（与报告默认一致）。 */
    fun defaultSorted(rows: List<IndexRow>): List<IndexRow> =
        rows.sortedWith(comparator("1d", ascending = false))

    /** 生成排序比较器；null 值无论升降序都排在最后。 */
    fun comparator(key: String, ascending: Boolean): Comparator<IndexRow> = when (key) {
        "code" -> compareBy { it.indexCode }
        "name" -> compareBy { it.indexName }
        "count" -> compareBy { it.etfCount }
        "shares" -> compareBy { it.shares }
        else -> Comparator { a, b ->
            val va = a.windows[key]?.delta
            val vb = b.windows[key]?.delta
            when {
                va == null && vb == null -> 0
                va == null -> 1
                vb == null -> -1
                else -> if (ascending) va.compareTo(vb) else vb.compareTo(va)
            }
        }
    }

    /** 明细按份额降序（成分 ETF 默认顺序）。 */
    fun memberComparator(key: String, ascending: Boolean): Comparator<EtfChange> = when (key) {
        "code" -> compareBy { it.code }
        "name" -> compareBy { it.name }
        "shares" -> compareBy { it.shares }
        else -> Comparator { a, b ->
            val va = a.windows[key]?.delta
            val vb = b.windows[key]?.delta
            when {
                va == null && vb == null -> 0
                va == null -> 1
                vb == null -> -1
                else -> if (ascending) va.compareTo(vb) else vb.compareTo(va)
            }
        }
    }
}
