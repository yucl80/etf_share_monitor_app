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
}
