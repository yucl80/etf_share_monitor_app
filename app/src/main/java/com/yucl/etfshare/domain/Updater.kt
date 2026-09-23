package com.yucl.etfshare.domain

import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.data.ShareRow
import com.yucl.etfshare.data.Sources
import com.yucl.etfshare.data.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 数据更新：本地优先，缺失数据自动到官网补全（对应桌面版 updater.py）。
 *
 * 步骤：交易所官方日频份额（深市区间 + 沪市逐日/锚点）
 *      -> 深市拟合指数映射 -> 行情兜底 -> F10 补全（跟踪指数 + 季度末历史）-> 映射纠错。
 */
class Updater(
    private val db: Db,
    private val src: Sources,
    private val dict: IndexDict,
    private val log: (String) -> Unit = {},
) {

    private val errors = ArrayList<String>()

    suspend fun run(quick: Boolean = false): UpdateResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        errors.clear()
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val etfs = src.fetchEtfList()
        log("获取到全市场场内 ETF ${etfs.size} 只")
        // 先把名单落库，保证份额汇总的 JOIN 不会漏掉尚未完成指数解析的 ETF
        for ((code, name) in etfs) db.upsertMeta(code, name)
        preloadDict()

        val official = HashMap<String, Pair<String, Double>>()
        val sseRows = ArrayList<ShareRow>()
        val seenDates = HashSet<String>()

        // ---- [1/4] 深市：一次区间请求拿到近半年日频份额 ----
        var szseCount = 0
        val window = src.szseHistoryWindow()
        try {
            val rows = src.fetchSzseScale(window.first, window.second)
            szseCount = rows.size
            if (rows.isNotEmpty()) {
                db.upsertSharesMany(rows.map { ShareRow(it.code, it.date, it.shares, "szse") })
                for (r in rows) {
                    val cur = official[r.code]
                    if (cur == null || r.date > cur.first) official[r.code] = r.date to r.shares
                }
            }
            log("[1/4] 深交所官方日频份额: $szseCount 条（区间 ${window.first} ~ ${window.second}）")
        } catch (e: Exception) {
            err("深交所规模接口失败: ${e.message}")
        }

        // ---- [1/4] 深市 ETF 列表：拟合指数 -> 指数代码 ----
        try {
            val szMeta = src.fetchSzseEtfList()
            var withIndex = 0
            for ((code, info) in szMeta) {
                val idx = info.indexCode
                if (idx != null) {
                    withIndex++
                    db.upsertMeta(code, info.name, info.indexName, idx, indexSource = "exchange")
                }
            }
            log("      深市ETF列表: ${szMeta.size} 只，其中带拟合指数 $withIndex 只")
        } catch (e: Exception) {
            err("深交所ETF列表失败: ${e.message}")
        }

        // ---- [2/4] 沪市：近两周逐日 + 月/季锚点 ----
        val today = LocalDate.now()
        val recent = (0 until 15).map { today.minusDays(it.toLong()).toString() }
        val anchors = listOf(30, 91, 182).map { today.minusDays(it.toLong()).toString() }
        val anchorHit = LinkedHashMap<String, String>()

        fun collect(target: String, date: String, data: Map<String, Double>) {
            if (seenDates.add(date)) {
                for ((code, shares) in data) sseRows.add(ShareRow(code, date, shares, "sse"))
            }
            for ((code, shares) in data) {
                val cur = official[code]
                if (cur == null || date > cur.first) official[code] = date to shares
            }
        }

        for (target in recent) {
            try {
                val data = src.fetchSseShares(target)
                if (data.isNotEmpty()) collect(target, target, data)
            } catch (e: Exception) {
                err("上交所份额接口失败($target): ${e.message}")
            }
        }
        for (target in anchors) {
            try {
                val (date, data) = src.fetchSseNearest(target)
                if (date != null) {
                    anchorHit[target] = date
                    collect(target, date, data)
                }
            } catch (e: Exception) {
                err("上交所份额接口失败($target): ${e.message}")
            }
        }
        if (sseRows.isNotEmpty()) db.upsertSharesMany(sseRows)
        log("[2/4] 上交所官方日频份额: ${sseRows.size} 条，覆盖交易日 ${seenDates.size} 个")

        // ---- [3/4] 行情兜底 ----
        val missing = etfs.map { it.first }.filter { !official.containsKey(it) }
        if (missing.isEmpty()) {
            log("[3/4] 全部 ETF 均有交易所官方数据，无需行情兜底")
        } else {
            val quotes = src.fetchQuotes(missing)
            if (quotes.isNotEmpty()) {
                db.upsertSharesMany(quotes.map { (c, q) -> ShareRow(c, q.date, q.shares, "quote") })
            }
            log("[3/4] 行情兜底: ${quotes.size} / ${missing.size} 只（交易所报表未覆盖）")
        }

        // ---- [4/4] F10 补全 ----
        if (quick) {
            log("[4/4] 快速模式：跳过 F10 全量补全（官方接口已覆盖近 6 个月）")
        } else {
            updateF10(etfs)
        }
        correctIndexMappings()

        val latest = db.latestShareDate()
        val ok = szseCount > 0 && sseRows.size > 0
        val now = LocalDateTime.now()
        db.setStates(
            mapOf(
                "last_fetch_date" to now.toLocalDate().toString(),
                "last_fetch_time" to now.format(fmt),
                "last_fetch_ok" to if (ok) "1" else "0",
                "latest_snapshot" to (latest ?: ""),
                "last_fetch_errors" to errors.size.toString(),
            )
        )
        log(
            "[数据抓取完成] 深市 $szseCount 条 / 沪市 ${sseRows.size} 条 | 最新快照 ${latest ?: "无"} | " +
                "核心数据${if (ok) "成功" else "失败"} | 失败 ${errors.size} 项"
        )
        if (!ok) log("      ⚠ 核心数据未完整获取，本次不记为「今日已抓取」，下次会重试")

        UpdateResult(
            ok = ok,
            szseRows = szseCount,
            sseRows = sseRows.size,
            latestSnapshot = latest,
            errors = errors.toList(),
            elapsedMs = System.currentTimeMillis() - t0,
        )
    }

    /** 步骤 [4/4]：F10 补全（跟踪指数映射 + 更早的季度末历史）。 */
    private suspend fun updateF10(etfs: List<Pair<String, String>>) = coroutineScope {
        val meta = db.getMeta()
        val today = LocalDate.now()
        val lastQe = lastQuarterEnd(today)
        val names = etfs.toMap()

        val need = etfs.map { it.first }.filter { code ->
            val m = meta[code]
            val fresh = m?.f10Updated?.let {
                runCatching { ChronoUnit.DAYS.between(LocalDate.parse(it), today) < META_REFRESH_DAYS }
                    .getOrDefault(false)
            } ?: false
            !fresh || !db.getShareDates(code).contains(lastQe) || m?.indexCode.isNullOrEmpty()
        }
        log("[4/4] 需补全 F10 的基金: ${need.size} 只")
        if (need.isEmpty()) return@coroutineScope

        val total = need.size
        var done = 0
        val counter = Mutex()
        val sem = Semaphore(F10_WORKERS)

        need.map { code ->
            async(Dispatchers.IO) {
                sem.withPermit { fetchF10AndStore(code, names[code], meta[code]?.indexCode) }
                counter.withLock {
                    done++
                    if (done % 100 == 0 || done == total) log("      F10 进度: $done/$total")
                }
            }
        }.awaitAll()
        log("      F10 补全完成: $done/$total")
    }

    private fun fetchF10AndStore(code: String, name: String?, knownIndexCode: String?) {
        var indexName: String? = null
        var indexCode: String? = knownIndexCode
        var source: String? = null
        try {
            indexName = src.fetchIndexName(code)
        } catch (_: Exception) {
            // 概况页偶发失败不影响其它步骤
        }
        if (indexCode == null && indexName != null) {
            try {
                val (c, s) = src.resolveIndexCodeWithSource(indexName)
                indexCode = c
                source = s
            } catch (_: Exception) {
            }
        }
        if (indexCode == null) {
            try {
                val (c, n) = src.fetchFundIndex(code)
                indexCode = c
                if (c != null) source = "fund_api"
                if (indexName == null) indexName = n
            } catch (_: Exception) {
            }
        }
        var gmbd: List<Pair<String, Double>> = emptyList()
        try {
            gmbd = src.fetchGmbdShares(code)
        } catch (_: Exception) {
        }
        db.upsertMeta(
            code = code,
            name = name,
            indexName = indexName,
            indexCode = indexCode,
            f10Updated = Db.today(),
            indexSource = if (knownIndexCode == null) source else null,
        )
        val have = db.getShareDates(code)
        val rows = gmbd.filter { it.first !in have }.map { ShareRow(code, it.first, it.second, "gmbd") }
        if (rows.isNotEmpty()) db.upsertSharesMany(rows)
    }

    /** 步骤 [5/5]：纠正历史遗留的错误映射（只接受官方全称精确匹配）。 */
    private fun correctIndexMappings() {
        val meta = db.getMeta()
        var checked = 0
        var changed = 0
        for ((code, m) in meta) {
            val idxName = m.indexName ?: continue
            val old = m.indexCode ?: continue
            if (m.indexSource == "exchange" || m.indexSource == "alias") continue
            checked++
            val hit = try {
                dict.lookupOfficial(idxName, fullNameOnly = true).first
            } catch (_: Exception) {
                null
            }
            if (hit != null && hit != old) {
                db.upsertMeta(code, m.name, idxName, hit, indexSource = "dict")
                changed++
            }
        }
        log("[5/5] 映射纠错: 检查 $checked 只，修正 $changed 只")
    }

    private fun preloadDict() {
        try {
            val (updated, full, short) = dict.stats()
            log("官方指数字典: 全称 $full 条 / 简称 $short 条（更新于 $updated）")
        } catch (e: Exception) {
            log("  ! 官方指数字典加载失败（退回东财检索 + 基金详情接口）: ${e.message}")
        }
    }

    private fun lastQuarterEnd(today: LocalDate): String {
        val qMonth = ((today.monthValue - 1) / 3) * 3 + 1
        return LocalDate.of(today.year, qMonth, 1).minusDays(1).toString()
    }

    private fun err(msg: String) {
        errors.add(msg)
        log("  ! $msg")
    }

    companion object {
        private const val F10_WORKERS = 6
        private const val META_REFRESH_DAYS = 30L
    }
}
