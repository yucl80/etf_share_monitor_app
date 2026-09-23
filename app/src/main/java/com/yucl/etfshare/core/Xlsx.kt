package com.yucl.etfshare.core

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * 原生实现的 xlsx 解析（替代 Python 版的 zipfile + ElementTree）。
 *
 * 只做本工程需要的事：把首个工作表解析为二维字符串表，
 * 支持 sharedStrings / inlineStr / 数值三种单元格。
 * 相比 Python 版增加了「按单元格引用定位列」的处理，
 * 避免稀疏行（空单元格被省略）导致列错位。
 */
object Xlsx {

    private const val SHARED = "xl/sharedStrings.xml"

    fun parseRows(raw: ByteArray): List<List<String>> {
        var sharedBytes: ByteArray? = null
        var sheetBytes: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
            var entry = zis.nextEntry
            val buf = ByteArray(32 * 1024)
            while (entry != null) {
                val name = entry.name
                val wantShared = name == SHARED
                val wantSheet = sheetBytes == null && name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")
                if (wantShared || wantSheet) {
                    val out = ByteArrayOutputStream(64 * 1024)
                    var n = zis.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        n = zis.read(buf)
                    }
                    val bytes = out.toByteArray()
                    if (wantShared) sharedBytes = bytes else sheetBytes = bytes
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sheet = sheetBytes ?: throw IllegalArgumentException("xlsx 中未找到工作表")
        val shared = sharedBytes?.let { parseSharedStrings(it) } ?: emptyList()
        return parseSheet(sheet, shared)
    }

    private fun newParser(bytes: ByteArray): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val out = ArrayList<String>()
        val parser = newParser(bytes)
        var text: StringBuilder? = null
        var inT = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "si" -> text = StringBuilder()
                    "t" -> inT = true
                }

                XmlPullParser.TEXT -> if (inT) text?.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inT = false
                    "si" -> {
                        out.add(text?.toString()?.trim() ?: "")
                        text = null
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val parser = newParser(bytes)

        var cells: MutableList<String>? = null
        var nextCol = 0
        var cellType: String? = null
        var cellCol = -1
        var buf = StringBuilder()
        var inV = false
        var inT = false
        var inInline = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "row" -> {
                        cells = ArrayList()
                        nextCol = 0
                    }

                    "c" -> {
                        cellType = parser.getAttributeValue(null, "t")
                        cellCol = colIndex(parser.getAttributeValue(null, "r"))
                        buf = StringBuilder()
                        inInline = false
                    }

                    "v" -> inV = true
                    "is" -> {
                        inInline = true
                        buf = StringBuilder()
                    }

                    "t" -> if (inInline) inT = true
                }

                XmlPullParser.TEXT -> if (inV || inT) buf.append(parser.text)

                XmlPullParser.END_TAG -> when (parser.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val rawText = buf.toString()
                        val value = when {
                            inInline -> rawText
                            cellType == "s" -> rawText.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            else -> rawText
                        }
                        val row = cells
                        if (row != null) {
                            val at = if (cellCol >= 0) cellCol else nextCol
                            while (row.size <= at) row.add("")
                            row[at] = value.trim()
                            nextCol = at + 1
                        }
                    }

                    "row" -> {
                        cells?.let { rows.add(it) }
                        cells = null
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    /** "AB12" -> 27（0 基列号）；无引用时返回 -1。 */
    private fun colIndex(ref: String?): Int {
        if (ref.isNullOrEmpty()) return -1
        var n = 0
        var seen = false
        for (ch in ref) {
            when {
                ch in 'A'..'Z' -> {
                    n = n * 26 + (ch - 'A' + 1)
                    seen = true
                }

                ch in 'a'..'z' -> {
                    n = n * 26 + (ch - 'a' + 1)
                    seen = true
                }

                else -> break
            }
        }
        return if (seen) n - 1 else -1
    }
}
