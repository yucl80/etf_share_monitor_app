package com.yucl.etfshare.data

/** 变化窗口定义：天数为目标回溯天数，容限为允许的基期偏差（天）。 */
data class WindowSpec(val key: String, val label: String, val days: Int, val tol: Int)

object Windows {
    val ALL = listOf(
        WindowSpec("1d", "最近1日", 1, 4),
        WindowSpec("1w", "最近1周", 7, 6),
        WindowSpec("1m", "最近1月", 30, 20),
        WindowSpec("3m", "最近3月", 91, 45),
        WindowSpec("6m", "最近6月", 182, 60),
    )
    val KEYS: List<String> = ALL.map { it.key }
    fun label(key: String): String = ALL.firstOrNull { it.key == key }?.label ?: key
    fun short(key: String): String = when (key) {
        "1d" -> "1日"
        "1w" -> "1周"
        "1m" -> "1月"
        "3m" -> "3月"
        "6m" -> "6月"
        else -> key
    }
}

const val YI = 1e8 // 份 -> 亿份

data class EtfMeta(
    val code: String,
    val name: String?,
    val indexName: String?,
    val indexCode: String?,
    val f10Updated: String?,
    val indexSource: String?,
)

data class ShareRecord(val date: String, val shares: Double)

data class ShareRow(val code: String, val date: String, val shares: Double, val source: String)

/** 单个窗口的份额变化。 */
data class WindowStat(
    val key: String,
    val delta: Double,
    val pct: Double?,
    val baseDate: String,
    val curDate: String,
    val gap: Double,
)

/** 单只 ETF 的当前份额 + 各窗口变化。 */
data class EtfChange(
    val code: String,
    val name: String,
    val indexName: String?,
    val indexCode: String?,
    val shares: Double,
    val curDate: String,
    val windows: Map<String, WindowStat?>,
)

/** 指数维度汇总行。 */
data class IndexRow(
    val indexCode: String,
    val indexName: String,
    val etfCount: Int,
    val shares: Double,
    val members: List<EtfChange>,
    val windows: Map<String, WindowStat?>,
    val baseVariants: Map<String, Int> = emptyMap(),
)

/** 一次数据更新的结果摘要。 */
data class UpdateResult(
    val ok: Boolean,
    val szseRows: Int,
    val sseRows: Int,
    val latestSnapshot: String?,
    val errors: List<String>,
    val elapsedMs: Long,
)
