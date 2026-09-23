package com.yucl.etfshare.domain

import com.yucl.etfshare.data.EtfMeta
import com.yucl.etfshare.data.ShareRecord
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首屏计算量实测（在 JVM 上跑）。
 *
 * 目的是回答「重启后要等一会儿才出数据」里，**读取 + 汇总**到底占多少：
 * 这里喂进去的规模与真实内置快照一致（约 1700 只 ETF × 60 个交易日 ≈ 10.2 万条），
 * 实测 computeEtfChanges + aggregateByIndex + 默认排序 的总耗时。
 *
 * 注意：JVM（桌面 CPU + JIT）明显快于手机上的 ART，所以这里得到的是**乐观下界**，
 * 真机通常是它的 2~5 倍。但如果这里只有几十毫秒，那么
 * 「处理数据慢」就不可能是启动等很久的主因，问题只能在别处（联网抓取 / 数据被重新导入）。
 */
class ReportCalcPerfTest {

    private val etfCount = 1700
    private val tradeDays = 60
    private val indexCount = 500

    private fun dataset(): Pair<Map<String, List<ShareRecord>>, Map<String, EtfMeta>> {
        val today = LocalDate.now()
        val all = LinkedHashMap<String, List<ShareRecord>>(etfCount * 2)
        val meta = HashMap<String, EtfMeta>(etfCount * 2)
        for (i in 0 until etfCount) {
            val code = "%06d".format(100000 + i)
            val records = ArrayList<ShareRecord>(tradeDays)
            for (d in tradeDays downTo 1) {
                val date = today.minusDays(d * 2L).toString()
                records.add(ShareRecord(date, 1.0e8 + i * 1.0e5 + d * 1.0e4))
            }
            all[code] = records
            meta[code] = EtfMeta(
                code = code,
                name = "测试ETF$i",
                indexName = "测试指数${i % indexCount}",
                indexCode = "%06d".format(300000 + (i % indexCount)),
                f10Updated = today.toString(),
                indexSource = "exchange",
            )
        }
        return all to meta
    }

    private fun runOnce(
        all: Map<String, List<ShareRecord>>,
        meta: Map<String, EtfMeta>,
    ): Long {
        val t = System.nanoTime()
        val changes = ReportCalc.computeEtfChanges(all, meta)
        val rows = ReportCalc.defaultSorted(ReportCalc.aggregateByIndex(changes))
        val ms = (System.nanoTime() - t) / 1_000_000
        assertEquals(etfCount, changes.size)
        assertEquals(indexCount, rows.size)
        return ms
    }

    @Test
    fun pipelineOnRealScaleDatasetIsFast() {
        val (all, meta) = dataset()
        val totalRows = all.values.sumOf { it.size }
        println("[perf] 规模: ${all.size} 只 ETF / $totalRows 条快照 / $tradeDays 个交易日")
        // 预热一次，把类加载与 JIT 的首次开销排除在外
        runOnce(all, meta)
        val times = (1..3).map { runOnce(all, meta) }
        val best = times.min()
        println("[perf] 读取+汇总+排序 耗时(ms): $times，最快 $best ms")
        println("[perf] 折算每万条快照: ${"%.2f".format(best / (totalRows / 10000.0))} ms")
        assertTrue("计算耗时异常（$times ms），可能出现了数量级退化", best < 3_000)
    }
}
