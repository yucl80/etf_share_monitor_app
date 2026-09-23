package com.yucl.etfshare.core

/** 简易文本工具：相似度、数字解析等。 */
object Text {

    /**
     * 名称相似度（0~1），用于指数检索结果的置信度判断。
     *
     * 采用最长公共子序列（LCS）计算 2*LCS/(lenA+lenB)，
     * 与 Python difflib.SequenceMatcher.ratio 的语义一致但更宽松（偏保守地接受候选）。
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1)
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            for (j in 1..m) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev
            prev = cur
            cur = t
            cur.fill(0)
        }
        return 2.0 * prev[m] / (n + m)
    }

    /** 去掉千分位与空白后转 Double，失败返回 null。 */
    fun toDoubleOrNull(s: String?): Double? {
        if (s == null) return null
        val t = s.replace(",", "").replace("\u0000", " ").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()
    }

    /** 日期规范化为 yyyy-MM-dd，失败返回 null。 */
    fun normalizeDate(s: String?): String? {
        if (s == null) return null
        val t = s.trim()
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t)) return t
        val m = Regex("^(\\d{4})[/.](\\d{1,2})[/.](\\d{1,2})$").find(t) ?: return null
        return "%s-%02d-%02d".format(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
}
