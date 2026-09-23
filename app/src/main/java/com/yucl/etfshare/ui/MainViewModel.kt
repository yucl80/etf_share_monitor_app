package com.yucl.etfshare.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yucl.etfshare.App
import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.data.IndexRow
import com.yucl.etfshare.data.LocalDataStatus
import com.yucl.etfshare.data.Prefs
import com.yucl.etfshare.data.Sources
import com.yucl.etfshare.domain.ReportCalc
import com.yucl.etfshare.domain.UpdatePolicy
import com.yucl.etfshare.domain.Updater
import com.yucl.etfshare.work.DailyUpdateScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Db.get(app)
    private val dict = IndexDict.get(app)
    private val sources = Sources(dict)

    var rows by mutableStateOf<List<IndexRow>>(emptyList())
        private set

    var etfCount by mutableStateOf(0)
        private set

    var unknownCount by mutableStateOf(0)
        private set

    var snapshot by mutableStateOf("—")
        private set

    var shareRows by mutableStateOf(0L)
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("正在读取本地数据…")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var autoDaily by mutableStateOf(DailyUpdateScheduler.isEnabled(app))
        private set

    /** 启动时自动检查并补抓当天数据（可在菜单里关闭）。 */
    var autoOnLaunch by mutableStateOf(Prefs.autoOnLaunch(app))
        private set

    private val logFlow = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = logFlow

    /** 本进程内已经做过启动检查的日期，保证「每天最多自动检查一次」。 */
    private var autoCheckedDay: String? = null

    /** 首屏本地数据是否已渲染完成（用于 onForeground 与 init 的去重）。 */
    private var initialLoaded = false

    private data class Computed(
        val rows: List<IndexRow>,
        val etfCount: Int,
        val unknown: Int,
        val snapshot: String,
        val shareRows: Long,
        /** 本地数据摘要；在 IO 线程算好，避免在主线程再查三次库。 */
        val summary: String,
    )

    init {
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            // 1) 等后台的本地数据准备结束（正常路径它只做一次文件头体检，开销极小）
            val seed = awaitLocalData()
            val tPrepared = SystemClock.elapsedRealtime() - t0
            // 2) 读本地库 + 汇总，全部在 IO 线程完成，不联网
            val data = withContext(Dispatchers.IO) { compute() }
            val tReady = SystemClock.elapsedRealtime() - t0
            apply(data)
            initialLoaded = true
            if (seed.imported) emit("本地数据初始化：${seed.message}")
            if (data.shareRows == 0L) {
                status = "本地暂无数据，正在自动抓取…"
                emit("本地数据库为空 —— 已自动开始联网抓取（无需手动点击刷新）")
            } else {
                status = "本地数据 · 快照 ${data.snapshot}"
                emit("已从本地数据库读取：${data.summary}")
            }
            // 这段是回答「启动到底慢在哪一步」的关键日志：
            //   本地数据检查 = 是否有库 / 是否需要重新导入（App 后台线程做的事）
            //   读取+汇总   = 从 SQLite 读 10 万条份额并算 5 个窗口
            emit(
                "[启动耗时] 本地数据检查 ${seed.elapsedMs}ms（${seed.message}）｜ " +
                    "等待+读取+汇总 ${tReady - tPrepared}ms（含等待 ${tPrepared}ms）｜ " +
                    "首屏就绪 ${tReady}ms",
            )
            // 3) 再做「今日是否已刷新」的自动检查，未刷新就直接自动抓取
            autoCheck()
        }
    }

    /**
     * 等后台的本地数据准备结束。
     *
     * 正常路径（数据完好）几乎立刻返回；只有首次启动要导入快照时才会稍等。
     * 加超时是为了「宁可先读一次本地库看结果，也不能把首屏卡死」。
     */
    private suspend fun awaitLocalData(): LocalDataStatus = withContext(Dispatchers.IO) {
        try {
            withTimeoutOrNull(LOCAL_DATA_WAIT_MS) { App.localDataReady?.await() }
                ?: App.seedStatus
        } catch (e: Exception) {
            LocalDataStatus(false, false, "本地数据准备异常：${e.message}")
        }
    }

    /**
     * 回到前台时调用：同一天只自动检查一次，避免反复联网。
     * 由 MainActivity.onStart() 触发。
     */
    fun onForeground() {
        // 首屏加载流程里已经会做一次启动检查；只要它还没跑完就不重复触发
        if (!initialLoaded) return
        if (autoCheckedDay == LocalDate.now().toString()) return
        viewModelScope.launch { autoCheck() }
    }

    /**
     * 启动自动检查（核心逻辑）：
     *  - 今日已抓取且快照已含今日数据 -> 不联网，界面直接用本地数据显示；
     *  - 今日尚未抓取 -> 自动抓取，用户无需点任何按钮；
     *  - 网络失败 -> 保留本地数据展示，只在状态栏提示。
     */
    private fun autoCheck() {
        val today = LocalDate.now().toString()
        if (autoCheckedDay == today) return
        autoCheckedDay = today

        if (!autoOnLaunch) {
            emit("[启动检查] 已关闭「启动时自动更新」，仅展示本地数据")
            status = if (rows.isEmpty()) "本地暂无数据（启动自动更新已关闭，可点右上角刷新）" else "本地数据 · 快照 $snapshot"
            return
        }
        if (busy) return

        viewModelScope.launch {
            val decision = try {
                withContext(Dispatchers.IO) { UpdatePolicy.decideOnLaunch(db) }
            } catch (e: Exception) {
                UpdatePolicy.Decision(false, "读取运行状态失败：${e.message}")
            }
            // 把判定依据原样打出来：这样「今天到底有没有抓过、为什么又要抓」一目了然，
            // 不必靠猜（数据被清空 vs 判定逻辑不认为今天抓过，看这行就能分清）。
            val diag = withContext(Dispatchers.IO) {
                val st = UpdatePolicy.stateOf(db)
                "上次抓取 ${st.lastFetchDate ?: "从未"} ${st.lastFetchTime?.takeLast(8) ?: "--:--:--"}" +
                    "｜成功 ${if (st.lastFetchOk) "是" else "否"}" +
                    "｜标记快照 ${st.latestSnapshot ?: "无"}" +
                    "｜实际最新快照 ${db.latestShareDate() ?: "无"}" +
                    "｜库内份额 ${db.countShareRows()} 条"
            }
            emit("[本地状态] $diag")
            if (decision.skip) {
                emit("[启动检查] ${decision.reason} —— 直接展示本地数据，不联网")
                status = if (rows.isEmpty()) {
                    "本地暂无数据：${decision.reason}"
                } else {
                    "本地数据已是最新 · 快照 $snapshot（${decision.reason}）"
                }
                return@launch
            }
            emit("[启动检查] ${decision.reason} —— 自动开始更新，无需点刷新")
            runUpdate(force = false, auto = true)
        }
    }

    /** 只重算汇总（不联网）。 */
    fun reload() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val data = withContext(Dispatchers.IO) { compute() }
                apply(data)
                status = "已按本地数据重算 · 快照 ${data.snapshot}"
            } finally {
                busy = false
            }
        }
    }

    /**
     * 手动刷新（右上角按钮）：默认仍遵循「今日已抓取则跳过」，force=true 强制联网重抓。
     */
    fun refresh(force: Boolean = false) {
        if (busy) return
        viewModelScope.launch { runUpdate(force = force, auto = false) }
    }

    /** 抓取 + 落库 + 重算的唯一入口（自动与手动共用）。 */
    private suspend fun runUpdate(force: Boolean, auto: Boolean) {
        if (busy) return
        busy = true
        error = null
        if (!auto) logFlow.value = emptyList()
        try {
            val decision = if (force) {
                UpdatePolicy.Decision(false, "手动触发，忽略「今日已抓取」标记")
            } else {
                withContext(Dispatchers.IO) { UpdatePolicy.decide(db) }
            }
            emit(if (decision.skip) "[跳过数据抓取] ${decision.reason}" else "[抓取数据] ${decision.reason}")
            if (decision.skip) {
                status = decision.reason
            } else {
                status = "正在抓取数据（首次约 1~3 分钟）…"
                val updater = Updater(db, sources, dict) { line -> emit(line) }
                val result = updater.run(quick = false)
                emit("本次耗时 ${Fmt.seconds(result.elapsedMs)}")
                emit("数据已保存到本地：${withContext(Dispatchers.IO) { db.summary() }}")
                status = if (result.ok) "数据已更新并保存到本地" else "核心数据未完整获取，可稍后重试"
            }
            status = "正在汇总…"
            val data = withContext(Dispatchers.IO) { compute() }
            apply(data)
            val suffix = if (auto) "自动更新完成" else "完成"
            status = "$suffix · 快照 ${data.snapshot} · 指数维度 ${data.rows.size} 个 · 数据已存本地"
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            status = if (auto) "自动更新失败，已显示本地数据（可手动刷新重试）" else "失败"
            emit("! ${e.message ?: e.toString()}")
        } finally {
            busy = false
        }
    }

    fun updateAutoDaily(enabled: Boolean) {
        autoDaily = enabled
        DailyUpdateScheduler.setEnabled(getApplication(), enabled)
        emit(if (enabled) "已开启每日自动更新（约 19:05）" else "已关闭每日自动更新")
    }

    fun updateAutoOnLaunch(enabled: Boolean) {
        autoOnLaunch = enabled
        Prefs.setAutoOnLaunch(getApplication(), enabled)
        emit(if (enabled) "已开启「启动时自动更新」" else "已关闭「启动时自动更新」")
    }

    private fun compute(): Computed {
        val changes = ReportCalc.computeEtfChanges(db)
        val shareRows = db.countShareRows()
        val snapshot = db.latestShareDate() ?: "—"
        val aggregated = ReportCalc.aggregateByIndex(changes)
        return Computed(
            rows = ReportCalc.defaultSorted(aggregated),
            etfCount = changes.size,
            unknown = changes.values.count { it.indexCode.isNullOrEmpty() },
            snapshot = snapshot,
            shareRows = shareRows,
            summary = "ETF ${changes.size} 只 / 份额 $shareRows 条 / 快照 $snapshot",
        )
    }

    private fun apply(data: Computed) {
        rows = data.rows
        etfCount = data.etfCount
        unknownCount = data.unknown
        snapshot = data.snapshot
        shareRows = data.shareRows
    }

    private fun emit(line: String) {
        logFlow.update { it + line }
    }

    companion object {
        /**
         * 等本地数据准备完成的超时上限。
         * 正常路径（数据完好）只做一次文件头体检，几千赫兹就返回；
         * 只有首次启动要解压 8MB 快照时才会真正等待。超时也继续往下走，
         * 宁可先读一遍本地库看结果，也不能把首屏卡死。
         */
        private const val LOCAL_DATA_WAIT_MS = 30_000L
    }
}
