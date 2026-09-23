package com.yucl.etfshare.data

import android.content.Context
import com.yucl.etfshare.core.Http
import com.yucl.etfshare.core.Xlsx
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 官方指数全量字典（中证指数官网 + 国证指数官网）。
 *
 * 公开检索库对中证/国证「细分主题指数」覆盖不全（例如「中证电池主题指数」
 * 「上证科创板综合指数」检索不到），因此直接抓取两家官网的全量清单，
 * 在本地做名称匹配。缓存落在 filesDir/index_dict.json，30 天内不重复下载。
 */
class IndexDict private constructor(private val context: Context) {

    class Snapshot(
        val updated: String,
        val full: Map<String, String>,
        val short: Map<String, String>,
        val normFull: Map<String, String>,
        val normShort: Map<String, String>,
        val errors: List<String>,
    )

    private val cacheFile = File(context.filesDir, CACHE_NAME)
    private var snapshotCache: Snapshot? = null
    private var aliasCache: Map<String, String>? = null

    @Synchronized
    fun snapshot(force: Boolean = false): Snapshot {
        snapshotCache?.let { if (!force) return it }
        var data = readCache()
        var needRefresh = data == null || force
        if (data != null && !needRefresh) {
            needRefresh = runCatching {
                val age = ChronoUnit.DAYS.between(LocalDate.parse(data.updated), LocalDate.now())
                age > REFRESH_DAYS
            }.getOrDefault(false)
        }
        if (needRefresh) {
            try {
                data = build()
                cacheFile.writeText(data.toJson().toString())
            } catch (e: Exception) {
                if (data == null) throw e
                // 刷新失败则沿用本地缓存
            }
        }
        val d = data ?: throw IllegalStateException("指数字典不可用")
        val normFull = LinkedHashMap<String, String>()
        val normShort = LinkedHashMap<String, String>()
        for ((k, v) in d.full) normFull.putIfAbsent(normalize(k), v)
        for ((k, v) in d.short) normShort.putIfAbsent(normalize(k), v)
        val snap = Snapshot(d.updated, d.full, d.short, normFull, normShort, d.errors)
        snapshotCache = snap
        return snap
    }

    fun stats(): Triple<String, Int, Int> {
        val s = snapshot()
        return Triple(s.updated, s.full.size, s.short.size)
    }

    /** 手工别名映射（assets/index_alias.json）：跟踪标的名称 -> 指数代码，优先级最高。 */
    @Synchronized
    fun alias(): Map<String, String> {
        aliasCache?.let { return it }
        val out = LinkedHashMap<String, String>()
        try {
            val text = context.assets.open("index_alias.json").use { String(it.readBytes(), Charsets.UTF_8) }
            val obj = JSONObject(text)
            for (key in obj.keys()) {
                if (key.startsWith("_")) continue
                val v = obj.optString(key, "")
                if (key.isNotBlank() && v.isNotBlank()) out[key] = v
            }
        } catch (_: Exception) {
            // assets 缺失时按空映射处理
        }
        aliasCache = out
        return out
    }

    /**
     * 跟踪标的名称 -> 指数代码。
     * 匹配顺序：精确全称 -> 精确简称 -> 归一化全称 -> 归一化简称。
     * fullNameOnly=true 时只用全称（用于纠正已有关联，避免同名不同机构指数被改错）。
     */
    fun lookupOfficial(name: String?, fullNameOnly: Boolean = false): Pair<String?, String?> {
        if (name.isNullOrEmpty()) return null to null
        val d = snapshot()
        d.full[name]?.let { return it to name }
        if (!fullNameOnly) d.short[name]?.let { return it to name }
        val key = normalize(name)
        if (key != name) {
            d.normFull[key]?.let { return it to key }
            if (!fullNameOnly) d.normShort[key]?.let { return it to key }
        }
        return null to null
    }

    /**
     * 名称归一化：去括号/空白、去币种标记、去尾部修饰词。
     * 例：中证港股通高股息投资港元指数 -> 中证港股通高股息投资；创业板指数(价格) -> 创业板
     */
    fun normalize(name: String?): String {
        var s = BRACKET.replace(name ?: "", "")
        s = SPACES.replace(s, "")
        for (cur in CURRENCY) s = s.replace(cur, "")
        for (tok in TAIL_TOKENS) {
            if (s.endsWith(tok) && s.length > tok.length) {
                s = s.dropLast(tok.length)
                break
            }
        }
        return s
    }

    // ---------------------------------------------------------------- 内部

    private class Raw(
        val updated: String,
        val full: Map<String, String>,
        val short: Map<String, String>,
        val errors: List<String>,
    ) {
        fun toJson(): JSONObject {
            val o = JSONObject()
            o.put("updated", updated)
            val fo = JSONObject()
            for ((k, v) in full) fo.put(k, v)
            o.put("full", fo)
            val so = JSONObject()
            for ((k, v) in short) so.put(k, v)
            o.put("short", so)
            o.put("errors", JSONArray(errors))
            return o
        }
    }

    private fun readCache(): Raw? {
        if (!cacheFile.exists()) return null
        return try {
            val o = JSONObject(cacheFile.readText())
            val full = o.optJSONObject("full") ?: return null
            if (full.length() == 0) return null
            val short = o.optJSONObject("short") ?: JSONObject()
            Raw(
                updated = o.optString("updated", LocalDate.now().toString()),
                full = full.toStringMap(),
                short = short.toStringMap(),
                errors = o.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun build(): Raw {
        val full = LinkedHashMap<String, String>()
        val short = LinkedHashMap<String, String>()
        val errors = ArrayList<String>()
        try {
            val (f, s) = fetchCsindex()
            f.forEach { (k, v) -> full.putIfAbsent(k, v) }
            s.forEach { (k, v) -> short.putIfAbsent(k, v) }
        } catch (e: Exception) {
            errors.add("中证指数官网: ${e.message}")
        }
        try {
            val (f, s) = fetchCnindex()
            f.forEach { (k, v) -> full.putIfAbsent(k, v) }
            s.forEach { (k, v) -> short.putIfAbsent(k, v) }
        } catch (e: Exception) {
            errors.add("国证指数官网: ${e.message}")
        }
        if (full.isEmpty()) throw IllegalStateException("指数字典构建失败 -> ${errors.joinToString("; ")}")
        return Raw(LocalDate.now().toString(), full, short, errors)
    }

    /** 中证指数官网全量清单（注意：该接口必须用 POST，GET 会返回空）。 */
    private fun fetchCsindex(): Pair<Map<String, String>, Map<String, String>> {
        val raw = Http.postJson(CS_EXPORT, CS_PAYLOAD, csHeaders, 90_000)
        if (!(raw.size > 1 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte())) {
            throw IllegalStateException("中证指数官网返回的不是 xlsx")
        }
        val rows = Xlsx.parseRows(raw)
        if (rows.isEmpty()) throw IllegalStateException("中证指数官网 xlsx 解析为空")
        val header = rows[0]
        val ci = header.indexOf("指数代码")
        val sn = header.indexOf("指数简称")
        val fn = header.indexOf("指数全称")
        if (ci < 0 || sn < 0 || fn < 0) throw IllegalStateException("中证指数官网 xlsx 表头变化: $header")
        val full = LinkedHashMap<String, String>()
        val short = LinkedHashMap<String, String>()
        for (r in rows.drop(1)) {
            if (r.size <= fn || r[ci].isBlank()) continue
            val code = r[ci].trim().padStart(6, '0')
            if (r[fn].isNotBlank()) full.putIfAbsent(r[fn].trim(), code)
            if (r[sn].isNotBlank()) short.putIfAbsent(r[sn].trim(), code)
        }
        return full to short
    }

    /** 国证指数官网全量清单。 */
    private fun fetchCnindex(): Pair<Map<String, String>, Map<String, String>> {
        val data = JSONObject(Http.getText(CN_URL, cnHeaders))
        val rows = data.optJSONObject("data")?.optJSONArray("rows") ?: throw IllegalStateException("国证指数官网返回为空")
        if (rows.length() == 0) throw IllegalStateException("国证指数官网返回为空")
        val full = LinkedHashMap<String, String>()
        val short = LinkedHashMap<String, String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("indexcode").trim()
            if (code.isEmpty()) continue
            val fn = row.optString("indexfullcname").trim()
            val sn = row.optString("indexname").trim()
            if (fn.isNotEmpty()) full.putIfAbsent(fn, code)
            if (sn.isNotEmpty()) short.putIfAbsent(sn, code)
        }
        return full to short
    }

    companion object {
        private const val CACHE_NAME = "index_dict.json"
        private const val REFRESH_DAYS = 30L

        private const val CS_EXPORT = "https://www.csindex.com.cn/csindex-home/exportExcel/indexAll/CH"
        private const val CN_URL = "https://www.cnindex.com.cn/index/indexList?channelCode=-1&rows=5000&pageNum=1"

        private const val CS_PAYLOAD =
            """{"sorter":{"sortField":"null","sortOrder":null},"pager":{"pageNum":1,"pageSize":10},""" +
                """"indexFilter":{"ifCustomized":null,"ifTracked":null,"ifWeightCapped":null,""" +
                """"indexCompliance":null,"hotSpot":null,"indexClassify":null,"currency":null,""" +
                """"region":null,"indexSeries":null,"undefined":null}}"""

        private val csHeaders = Http.defaultHeaders() + mapOf(
            "Content-Type" to "application/json;charset=UTF-8",
            "Referer" to "https://www.csindex.com.cn/",
        )
        private val cnHeaders = Http.defaultHeaders() + mapOf("Referer" to "https://www.cnindex.com.cn/")

        private val BRACKET = Regex("[（(][^）)]*[）)]")
        private val SPACES = Regex("\\s+")
        private val CURRENCY = listOf("离岸人民币", "港元", "港币", "人民币", "美元")
        private val TAIL_TOKENS = listOf("全收益指数", "净收益指数", "全收益", "收益率", "指数", "价格", "净值")

        @Volatile
        private var instance: IndexDict? = null

        fun get(context: Context): IndexDict = instance ?: synchronized(this) {
            instance ?: IndexDict(context.applicationContext).also { instance = it }
        }
    }
}

internal fun JSONObject.toStringMap(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val it = keys()
    while (it.hasNext()) {
        val k = it.next()
        out[k] = optString(k, "")
    }
    return out
}
