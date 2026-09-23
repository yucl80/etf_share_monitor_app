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

    /**
     * 把名称裁剪到最多 [maxChars] 个字符（按码点计数，即「字数」）。
     *
     * 超出时保留前 `maxChars - 1` 个字符并以 `…` 结尾，
     * 让**含省略号的显示长度也恰好不超过 maxChars**。
     * 这一点很关键：若写成「截 maxChars 个再补省略号」，实际显示会多出 1 个字，
     * 按「N 个全角字」预留的列宽就又会溢出。
     *
     * 按码点截断可避免把代理对（emoji、生僻扩展字）从中间劈开。
     */
    fun clip(s: String, maxChars: Int = 10): String {
        if (maxChars <= 0) return ""
        val t = s.trim()
        if (t.isEmpty()) return t
        if (t.codePointCount(0, t.length) <= maxChars) return t
        val end = t.offsetByCodePoints(0, maxChars - 1)
        return t.substring(0, end) + "…"
    }
}
