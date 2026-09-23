package com.yucl.etfshare.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    @Test
    fun similarityIsSymmetricAndBounded() {
        assertEquals(1.0, Text.similarity("沪深300指数", "沪深300指数"), 1e-9)
        val partial = Text.similarity("沪深300指数", "沪深300")
        assertTrue("partial=$partial", partial in 0.6..0.99)
        val unrelated = Text.similarity("沪深300指数", "中证500")
        assertTrue("unrelated=$unrelated", unrelated < 0.5)
    }

    @Test
    fun similarityHandlesEmptyInput() {
        assertEquals(0.0, Text.similarity("", "沪深300"), 1e-9)
        assertEquals(0.0, Text.similarity("沪深300", ""), 1e-9)
    }

    @Test
    fun toDoubleHandlesThousandsSeparator() {
        assertEquals(1234567.89, Text.toDoubleOrNull("1,234,567.89")!!, 1e-9)
        assertEquals(42.0, Text.toDoubleOrNull(" 42 ")!!, 1e-9)
        assertNull(Text.toDoubleOrNull(""))
        assertNull(Text.toDoubleOrNull("--"))
    }

    @Test
    fun normalizeDateAcceptsSlashAndDot() {
        assertEquals("2026-09-22", Text.normalizeDate("2026-09-22"))
        assertEquals("2026-09-22", Text.normalizeDate("2026/9/22"))
        assertEquals("2026-09-01", Text.normalizeDate("2026.9.1"))
        assertNull(Text.normalizeDate("20260922"))
    }

    @Test
    fun clipKeepsShortNamesUntouched() {
        assertEquals("沪深300", Text.clip("沪深300"))
        assertEquals("中证全指证券公司指数", Text.clip("中证全指证券公司指数")) // 恰好 10 字
        assertEquals("", Text.clip(""))
        assertEquals("", Text.clip("   "))
    }

    @Test
    fun clipIncludesEllipsisWithinLimit() {
        // 含省略号的显示长度也必须 <= 10，否则按「10 个全角字」预留的列宽会溢出。
        // 注意：是「前 9 字 + …」，不是「前 10 字 + …」。
        val long = "中证AAA科技创新公司债指数" // 14 字
        val out = Text.clip(long, 10)
        assertEquals("中证AAA科技创新…", out)
        assertEquals(10, out.length)
        assertTrue(Text.clip(long, 6) == "中证AAA…")
        assertEquals(1, Text.clip(long, 1).length)
        assertEquals("", Text.clip(long, 0))
    }

    @Test
    fun clipNeverExceedsMaxCodePoints() {
        val names = listOf(
            "中证AAA科技创新公司债指数",
            "沪深300",
            "国证创业板成长指数",
            "标普500ETF联接",
            "MSCI中国A50互联互通指数",
        )
        for (n in names) {
            for (m in 1..12) {
                val out = Text.clip(n, m)
                assertTrue("clip($n, $m) = $out 超长", out.codePointCount(0, out.length) <= m)
                assertTrue("clip($n, $m) = $out 不是前缀", n.startsWith(out.removeSuffix("…")))
            }
        }
    }
}
