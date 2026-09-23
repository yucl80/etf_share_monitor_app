package com.yucl.etfshare.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * xlsx 解析器测试。
 *
 * 这是本次原生重写里风险最高的新代码（交易所报表全靠它），
 * 因此用内存构造的 xlsx 覆盖三类单元格与稀疏行定位。
 */
class XlsxTest {

    private val ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"

    private fun xlsx(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun parsesSharedInlineAndNumericCells() {
        val raw = xlsx(
            // 故意把 sheet 放在 sharedStrings 之前：实现是先把两者缓冲再解析，顺序无关
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="$ns"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
                  <row r="2"><c r="A2" t="inlineStr"><is><t>2026-09-22</t></is></c><c r="B2"><v>159915</v></c><c r="C2" t="inlineStr"><is><t>创业板ETF</t></is></c><c r="D2"><v>1234567.89</v></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
            "xl/sharedStrings.xml" to
                """<sst xmlns="$ns"><si><t>指数代码</t></si><si><t>指数简称</t></si><si><t>399372 大盘成长</t></si></sst>""",
        )
        val rows = Xlsx.parseRows(raw)
        assertEquals(2, rows.size)
        assertEquals(listOf("指数代码", "指数简称"), rows[0])
        assertEquals(listOf("2026-09-22", "159915", "创业板ETF", "1234567.89"), rows[1])
    }

    @Test
    fun keepsColumnPositionWhenCellsAreSparse() {
        // 第 2 行缺少 B 列（Excel 会省略空单元格），必须按 r 引用补位，不能左移
        val raw = xlsx(
            "xl/worksheets/sheet1.xml" to """
                <worksheet xmlns="$ns"><sheetData>
                  <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                  <row r="2"><c r="A2" t="s"><v>0</v></c><c r="C2" t="inlineStr"><is><t>稀疏行</t></is></c></row>
                </sheetData></worksheet>
            """.trimIndent(),
            "xl/sharedStrings.xml" to """<sst xmlns="$ns"><si><t>399372 大盘成长</t></si></sst>""",
        )
        val rows = Xlsx.parseRows(raw)
        assertEquals(2, rows.size)
        assertEquals(3, rows[1].size)
        assertEquals("399372 大盘成长", rows[1][0])
        assertEquals("", rows[1][1])
        assertEquals("稀疏行", rows[1][2])
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsWhenNoWorksheet() {
        Xlsx.parseRows(xlsx("xl/workbook.xml" to "<workbook/>"))
    }
}
