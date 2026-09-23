package com.yucl.etfshare.domain

import com.yucl.etfshare.data.IndexRow
import com.yucl.etfshare.data.ShareRecord
import com.yucl.etfshare.data.WindowStat
import com.yucl.etfshare.data.Windows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.LocalDate

/**
 * 基期选取与排序规则的回归测试（对应桌面版 report.py 的口径）。
 *
 * 这里覆盖过的一个真实 bug：把「基期须早于当前快照 N/2 天」写成整数天截断，
 * 1 日窗口的 N/2=0.5 天被截成 0，导致当前快照自己被当成基期、1 日变化恒为 0。
 */
class ReportCalcTest {

    private val today = LocalDate.of(2026, 9, 23)
    private val curDate = LocalDate.of(2026, 9, 22)

    private fun recs(vararg pairs: Pair<String, Double>): List<ShareRecord> =
        pairs.map { ShareRecord(it.first, it.second) }

    private fun spec(key: String) = Windows.ALL.first { it.key == key }

    @Test
    fun oneDayWindowNeverUsesCurrentSnapshotAsBase() {
        val records = recs(
            "2026-06-30" to 100.0,
            "2026-09-21" to 80.0,
            "2026-09-22" to 90.0, // 当前快照本身，必须被排除
        )
        val picked = ReportCalc.pickBase(records, spec("1d"), today, curDate)
        assertNotNull(picked)
        assertEquals("2026-09-21", picked!!.first.date)
        assertEquals(10.0, 90.0 - picked.first.shares, 1e-9)
    }

    @Test
    fun picksCandidateNearestToTargetWithinTolerance() {
        // 1 月窗口目标日 = 2026-08-24，容限 20 天：08-20（偏差 4）与 08-25（偏差 1）都应被选到最近的 08-25
        val records = recs(
            "2026-06-30" to 100.0,
            "2026-08-20" to 70.0,
            "2026-08-25" to 75.0,
            "2026-09-22" to 90.0,
        )
        val picked = ReportCalc.pickBase(records, spec("1m"), today, curDate)
        assertNotNull(picked)
        assertEquals("2026-08-25", picked!!.first.date)
        assertEquals(1.0, picked.second, 1e-9)
    }

    @Test
    fun returnsNullWhenNothingWithinTolerance() {
        val records = recs("2026-01-05" to 10.0, "2026-09-22" to 90.0)
        assertNull(ReportCalc.pickBase(records, spec("1m"), today, curDate))
    }

    @Test
    fun returnsNullWhenRecordTooCloseToCurrentSnapshot() {
        // 只有当前快照与它前一天以外的数据都在容限外时，应返回 null（而不是退化成当前快照）
        val records = recs("2026-09-22" to 90.0)
        assertNull(ReportCalc.pickBase(records, spec("1d"), today, curDate))
    }

    private fun row(code: String, d1: Double?): IndexRow {
        val windows = HashMap<String, WindowStat?>()
        for (key in Windows.KEYS) windows[key] = null
        if (d1 != null) {
            windows["1d"] = WindowStat("1d", d1, 0.01, "2026-09-21", "2026-09-22", 1.0)
        }
        return IndexRow(code, code, 1, 0.0, emptyList(), windows)
    }

    @Test
    fun nullsAlwaysSortLastRegardlessOfDirection() {
        val rows = listOf(row("AAA", null), row("BBB", 5.0), row("CCC", -3.0))
        assertEquals(
            listOf("BBB", "CCC", "AAA"),
            rows.sortedWith(ReportCalc.comparator("1d", ascending = false)).map { it.indexCode },
        )
        assertEquals(
            listOf("CCC", "BBB", "AAA"),
            rows.sortedWith(ReportCalc.comparator("1d", ascending = true)).map { it.indexCode },
        )
    }

    @Test
    fun defaultSortedIsOneDayDescending() {
        val rows = listOf(row("AAA", 1.0), row("BBB", 9.0), row("CCC", null), row("DDD", -2.0))
        assertEquals(
            listOf("BBB", "AAA", "DDD", "CCC"),
            ReportCalc.defaultSorted(rows).map { it.indexCode },
        )
    }
}
