package com.yucl.etfshare.data

import com.yucl.etfshare.core.Http
import com.yucl.etfshare.core.Text
import com.yucl.etfshare.core.Xlsx
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 数据源封装（全部为公开官网 / 官方行情接口）：
 *
 * 1. ETF 全量名单        : 天天基金 fundcode_search.js（按场内代码规则过滤）
 * 2. 【沪市】官方日频份额 : 上交所 query.sse.com.cn，ETFGM 报表按 STAT_DATE 单日查询
 * 3. 【深市】官方日频份额 : 深交所 scsj_fund_jjgm 报表，按日期区间导出 xlsx（单次最长 6 个月）
 * 4. 【深市】拟合指数映射 : 深交所 ETF 列表（CATALOGID=1945）含「拟合指数」= 指数代码 + 名称
 * 5. 兜底份额快照        : 腾讯财经行情（总市值 / 最新价 -> 份额）
 * 6. 历史份额(季度)      : 天天基金 F10「规模变动」期末总份额
 * 7. 跟踪指数名称        : 天天基金 F10「基金概况」跟踪标的
 * 8. 指数名称->代码      : 官方指数字典（IndexDict）+ 东方财富搜索适配接口
 * 9. 跟踪指数代码(权威)  : 东方财富基金详情接口 INDEXCODE（覆盖境外/债券/主题指数）
 */
class Sources(private val dict: IndexDict) {

    data class Quote(val date: String, val shares: Double, val price: Double)

    data class SzseScaleRow(val date: String, val code: String, val name: String, val shares: Double)

    data class SzseEtf(val name: String, val indexCode: String?, val indexName: String?, val shares: Double?)

    private val sseDayCache = ConcurrentHashMap<String, Map<String, Double>>()
    private val resolveCache = ConcurrentHashMap<String, Pair<String?, String?>>()

    private val fundLock = Any()
    private var fundLastAt = 0L

    // ------------------------------------------------------------ 请求头

    private val sseHeaders = Http.defaultHeaders() + mapOf("Referer" to "https://www.sse.com.cn/")
    private val szseScaleHeaders =
        Http.defaultHeaders() + mapOf("Referer" to "https://www.szse.cn/market/fund/volume/etf/index.html")
    private val szseListHeaders =
        Http.defaultHeaders() + mapOf("Referer" to "https://www.szse.cn/market/fund/etf/index.html")
    private val quoteHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        "Referer" to "https://gu.qq.com/",
    )
    private val fundIndexHeaders = Http.defaultHeaders() + mapOf("Referer" to "https://fund.eastmoney.com/")

    // ------------------------------------------------------------ ETF 名单

    /** 全市场场内 ETF 名单（沪 5xxxxx / 深 15,16,18xxxx，排除联接与货币型）。 */
    fun fetchEtfList(): List<Pair<String, String>> {
        val text = Http.getText("https://fund.eastmoney.com/js/fundcode_search.js")
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) throw IOException("ETF 名单接口返回异常")
        val arr = JSONArray(text.substring(start, end + 1))
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val code = row.optString(0)
            val name = row.optString(2)
            val type = row.optString(3)
            if (code.length != 6) continue
            if (!(code.startsWith("5") || code.startsWith("15") || code.startsWith("16") || code.startsWith("18"))) continue
            if (!name.contains("ETF")) continue
            if (name.contains("联接") || name.contains("FOF")) continue
            if (type.contains("货币")) continue
            out[code] = name
        }
        return out.map { it.key to it.value }
    }

    // ------------------------------------------------------------ 行情快照

    /** 份额 = 总市值(元) / 最新价(元)。停牌 / 无价证券跳过。 */
    fun fetchQuotes(codes: List<String>, batchSize: Int = 50): Map<String, Quote> {
        val out = HashMap<String, Quote>()
        var i = 0
        while (i < codes.size) {
            val batch = codes.subList(i, minOf(i + batchSize, codes.size))
            val q = batch.joinToString(",") { (if (it.startsWith("5")) "sh" else "sz") + it }
            try {
                val text = Http.getText("https://qt.gtimg.cn/q=$q", quoteHeaders)
                for (m in QUOTE.findAll(text)) {
                    val code = m.groupValues[1]
                    val parts = m.groupValues[2].split("~")
                    if (parts.size <= 45) continue
                    val price = Text.toDoubleOrNull(parts[3]) ?: continue
                    val mktcapYi = Text.toDoubleOrNull(parts[45]) ?: continue
                    val ts = parts[30]
                    if (price <= 0.0 || mktcapYi <= 0.0 || ts.length < 8) continue
                    val date = "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)}"
                    out[code] = Quote(date, mktcapYi * 1e8 / price, price)
                }
            } catch (_: Exception) {
                // 单批失败不影响整体兜底
            }
            i += batchSize
            Http.sleep(200)
        }
        return out
    }

    // ------------------------------------------------------------ F10

    /** 基金概况页 -> 跟踪标的名称。 */
    fun fetchIndexName(code: String): String? {
        val html = Http.getText("https://fundf10.eastmoney.com/jbgk_$code.html")
        val name = TRACK_TARGET.find(html)?.groupValues?.get(1)?.trim()
        if (name.isNullOrEmpty() || name == "无") return null
        return name
    }

    /** F10 规模变动 -> [(季度末日期, 期末总份额(份))]，日期升序。 */
    fun fetchGmbdShares(code: String): List<Pair<String, Double>> {
        val text = Http.getText(
            "https://fundf10.eastmoney.com/FundArchivesDatas.aspx?type=gmbd&code=$code&rt=${System.currentTimeMillis()}"
        )
        val rows = ArrayList<Pair<String, Double>>()
        for (m in GMBD.findAll(text)) {
            val date = m.groupValues[1].trim()
            val sharesYi = Text.toDoubleOrNull(m.groupValues[4]) ?: continue
            if (sharesYi <= 0.0) continue
            rows.add(date to sharesYi * 1e8)
        }
        rows.sortBy { it.first }
        return rows
    }

    /**
     * 东方财富基金详情接口 -> (跟踪指数代码, 跟踪指数名称)。
     * 最权威的兜底来源：接口直接给出 INDEXCODE / INDEXNAME。
     * 该接口限流较严（ErrCode=61136403），故内置全局限速 + 指数退避。
     */
    fun fetchFundIndex(code: String): Pair<String?, String?> {
        val url = "https://fundmobapi.eastmoney.com/FundMNewApi/FundMNBasicInformation" +
            "?FCODE=$code&deviceid=1&plat=Iphone&product=EFund&version=1"
        var lastErr: Exception? = null
        for (attempt in 0 until FUND_RETRIES) {
            synchronized(fundLock) {
                val gap = FUND_MIN_INTERVAL_MS - (System.currentTimeMillis() - fundLastAt)
                if (gap > 0) Http.sleep(gap)
                fundLastAt = System.currentTimeMillis()
            }
            try {
                val data = JSONObject(Http.getText(url, fundIndexHeaders))
                val errCode = data.optString("ErrCode", "0")
                val success = data.optBoolean("Success", true)
                if (!success && errCode.isNotEmpty() && errCode != "0") {
                    lastErr = IOException("ErrCode=$errCode")
                    Http.sleep(2000L * (attempt + 1))
                    continue
                }
                val info = data.optJSONObject("Datas")
                val ic = info?.optString("INDEXCODE").orEmpty().trim()
                val iname = info?.optString("INDEXNAME").orEmpty().trim()
                return ic.ifEmpty { null } to iname.ifEmpty { null }
            } catch (e: Exception) {
                lastErr = e
                Http.sleep(1500L * (attempt + 1))
            }
        }
        throw lastErr ?: IOException("基金详情接口无响应")
    }

    // ------------------------------------------------------------ 指数名称 -> 代码

    /**
     * 跟踪标的名称 -> (指数代码, 来源)。
     * 优先级：alias（手工别名，最高）-> dict（官网全量字典）-> search（东财检索）。
     */
    fun resolveIndexCodeWithSource(indexName: String?): Pair<String?, String?> {
        if (indexName.isNullOrEmpty()) return null to null
        resolveCache[indexName]?.let { return it }
        dict.alias()[indexName]?.let {
            val r = it to "alias"
            resolveCache[indexName] = r
            return r
        }
        var code: String? = null
        var source: String? = null
        try {
            val (c, _) = dict.lookupOfficial(indexName)
            if (c != null) {
                code = c
                source = "dict"
            }
        } catch (_: Exception) {
            // 字典不可用时退回检索
        }
        if (code == null) {
            code = searchIndexCode(indexName)
            source = if (code != null) "search" else null
        }
        val r = code to source
        resolveCache[indexName] = r
        return r
    }

    /** 东财搜索适配接口解析（多级关键词回退 + 名称相似度）。 */
    fun searchIndexCode(indexName: String): String? {
        var bestRatio = 0.0
        var bestPriority = Int.MIN_VALUE
        var bestCode: String? = null
        for (q in indexCandidates(indexName)) {
            if (q.length < 2) continue
            val items = try {
                searchItems(q)
            } catch (_: Exception) {
                continue
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val cls = item.optString("Classify")
                val priority = ACCEPT_CLASS[cls] ?: continue
                val code = item.optString("Code")
                if (code.isEmpty()) continue
                val name = BRACKET.replace(item.optString("Name"), "").trim()
                val ratio = Text.similarity(q, name)
                if (ratio < MIN_RATIO) continue
                val key = -priority
                if (ratio > bestRatio + 1e-9 || (kotlin.math.abs(ratio - bestRatio) < 1e-9 && key > bestPriority)) {
                    bestRatio = ratio
                    bestPriority = key
                    bestCode = code
                }
            }
            if (bestRatio >= 0.95) break
        }
        return bestCode
    }

    private fun searchItems(keyword: String): JSONArray {
        val url = "https://searchadapter.eastmoney.com/api/suggest/get?input=" +
            URLEncoder.encode(keyword, "UTF-8") + "&type=14&count=15"
        val data = JSONObject(Http.getText(url, quoteHeaders))
        return data.optJSONObject("QuotationCodeTable")?.optJSONArray("Data") ?: JSONArray()
    }

    /** 生成由精确到宽松的检索关键词序列。 */
    private fun indexCandidates(indexName: String): List<String> {
        val cands = LinkedHashSet<String>()
        fun add(s: String) {
            val t = SPACES.replace(s, "")
            if (t.isNotEmpty()) cands.add(t)
        }

        val base = BRACKET.replace(indexName, "").trim()
        add(indexName)
        add(base)
        for (tok in STRIP_TOKENS) {
            var b = base
            while (b.endsWith(tok) && b.length > tok.length) {
                b = b.dropLast(tok.length)
                add(b)
            }
        }
        for (b in cands.toList()) {
            for (p in STRIP_PREFIX) {
                if (b.startsWith(p) && b.length > p.length + 1) add(b.substring(p.length))
            }
        }
        for (b in cands.toList()) {
            add(b.replace("科创板", "科创").replace("创业板", "创业"))
        }
        return cands.toList()
    }

    // ------------------------------------------------------------ 沪市官方日频份额

    /**
     * 上交所官网 ETF 基金份额（单日，沪市全量）。非交易日返回空。
     * 原始单位为「万份」，此处已换算为「份」。
     */
    fun fetchSseShares(date: String): Map<String, Double> {
        sseDayCache[date]?.let { return it }
        val url = "https://query.sse.com.cn/commonQuery.do" +
            "?isPagination=true&pageHelp.pageSize=10000&pageHelp.pageNo=1&pageHelp.beginPage=1" +
            "&pageHelp.cacheSize=1&pageHelp.endPage=1" +
            "&sqlId=COMMON_SSE_ZQPZ_ETFZL_XXPL_ETFGM_SEARCH_L&STAT_DATE=$date"
        var out: Map<String, Double> = emptyMap()
        try {
            val data = JSONObject(Http.getText(url, sseHeaders))
            val result = data.optJSONArray("result")
            if (result != null) {
                val m = LinkedHashMap<String, Double>()
                for (i in 0 until result.length()) {
                    val row = result.optJSONObject(i) ?: continue
                    val code = row.optString("SEC_CODE").trim()
                    val shares = Text.toDoubleOrNull(row.optString("TOT_VOL"))?.times(1e4)
                    if (code.isNotEmpty() && shares != null && shares > 0) m[code] = shares
                }
                out = m
            }
        } catch (_: Exception) {
            out = emptyMap()
        }
        sseDayCache[date] = out
        return out
    }

    /** 从目标日期向前回溯，返回最近一个有数据的交易日及其份额。 */
    fun fetchSseNearest(targetDate: String, maxBack: Int = 12): Pair<String?, Map<String, Double>> {
        val day = LocalDate.parse(targetDate)
        for (i in 0..maxBack) {
            val d = day.minusDays(i.toLong()).toString()
            val data = fetchSseShares(d)
            if (data.isNotEmpty()) return d to data
        }
        return null to emptyMap()
    }

    // ------------------------------------------------------------ 深市官方日频份额

    /** 深交所基金规模日频数据（区间查询，单次最长 6 个月）。 */
    fun fetchSzseScale(startDate: String, endDate: String): List<SzseScaleRow> {
        val url = "https://www.szse.cn/api/report/ShowReport" +
            "?SHOWTYPE=xlsx&CATALOGID=scsj_fund_jjgm&TABKEY=tab1" +
            "&txtStart=$startDate&txtEnd=$endDate&jjlb=ETF&random=${Math.random()}"
        val raw = Http.get(url, szseScaleHeaders, 120_000)
        val rows = try {
            Xlsx.parseRows(raw)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<SzseScaleRow>()
        for (row in rows.drop(1)) {
            if (row.size < 4) continue
            val date = Text.normalizeDate(row[0])
            val code = row[1].trim()
            val name = row[2].trim()
            val shares = Text.toDoubleOrNull(row[3])
            if (date == null || code.isEmpty() || shares == null || shares <= 0) continue
            out.add(SzseScaleRow(date, code, name, shares))
        }
        return out
    }

    /**
     * 深交所 ETF 列表：代码 -> {名称, 拟合指数代码, 拟合指数名称, 当前规模}。
     * 导出列中的「拟合指数」形如 "399372 大盘成长"，可直接作为跟踪指数映射。
     */
    fun fetchSzseEtfList(): Map<String, SzseEtf> {
        val url = "https://www.szse.cn/api/report/ShowReport" +
            "?SHOWTYPE=xlsx&CATALOGID=1945&TABKEY=tab1&random=${Math.random()}"
        val raw = Http.get(url, szseListHeaders, 90_000)
        val rows = try {
            Xlsx.parseRows(raw)
        } catch (_: Exception) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, SzseEtf>()
        for (row in rows.drop(1)) {
            if (row.size < 4) continue
            val code = row[0].trim()
            if (!Regex("^\\d{6}$").matches(code)) continue
            val name = row[1].trim()
            val fit = row[2].trim()
            val shares = Text.toDoubleOrNull(row[3])
            val m = FIT_INDEX.find(fit)
            out[code] = SzseEtf(
                name = name,
                indexCode = m?.groupValues?.get(1),
                indexName = m?.groupValues?.get(2)?.trim()?.ifEmpty { null },
                shares = shares,
            )
        }
        return out
    }

    /** 深交所日频份额查询的日期窗口（不超过 6 个月）。 */
    fun szseHistoryWindow(days: Int = 180): Pair<String, String> {
        val end = LocalDate.now()
        return end.minusDays(days.toLong()).toString() to end.toString()
    }

    companion object {
        private const val FUND_RETRIES = 4
        private const val FUND_MIN_INTERVAL_MS = 400L
        private const val MIN_RATIO = 0.6

        private val ACCEPT_CLASS = mapOf(
            "Index" to 0, "24" to 0, "NDI" to 1, "HK" to 2, "UniversalIndex" to 3, "SGE" to 4,
        )

        private val QUOTE = Regex("v_(?:sh|sz)(\\d{6})=\"([^\"]*)\"")
        private val TRACK_TARGET = Regex("跟踪标的</th><td>([^<]+)</td>")
        private val BRACKET = Regex("[（(].*?[)）]")
        private val SPACES = Regex("\\s+")
        private val FIT_INDEX = Regex("^([A-Za-z0-9]{4,8})\\s*(.*)$")
        private val GMBD = Regex(
            "<tr><td>(\\d{4}-\\d{2}-\\d{2})</td>\\s*" +
                "<td class='tor'>([^<]*)</td>\\s*" +
                "<td class='tor'>([^<]*)</td>\\s*" +
                "<td class='tor'>([^<]*)</td>\\s*" +
                "<td class='tor'>([^<]*)</td>\\s*" +
                "<td class='tor'>([^<]*)</td></tr>"
        )

        private val STRIP_TOKENS = listOf(
            "成份指数", "成份", "指数", "全收益", "净收益", "收益率", "(价格)", "（价格）",
            "(人民币)", "（人民币）", "价格",
        )
        private val STRIP_PREFIX = listOf("中证全指", "中证", "上证", "深证", "国证", "标普", "全指")
    }
}
