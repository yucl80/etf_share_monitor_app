package com.yucl.etfshare.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yucl.etfshare.data.Db
import com.yucl.etfshare.data.IndexDict
import com.yucl.etfshare.data.IndexRow
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

    var status by mutableStateOf("就绪")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var autoDaily by mutableStateOf(DailyUpdateScheduler.isEnabled(app))
        private set

    private val logFlow = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = logFlow

    private data class Computed(
        val rows: List<IndexRow>,
        val etfCount: Int,
        val unknown: Int,
        val snapshot: String,
        val shareRows: Long,
    )

    init {
        // 首屏直接用本地数据渲染，避免等待网络
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) { compute() }
            apply(data)
            if (data.shareRows == 0L) {
                status = "本地暂无数据，请点右上角刷新抓取"
                emit("本地数据库为空 —— 点按右上角刷新按钮即可从官网抓取全量数据")
            } else {
                status = "本地数据 · 快照 ${data.snapshot}"
            }
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
     * 智能刷新：默认遵循「今日已抓取则跳过」策略；force=true 时强制联网抓取。
     */
    fun refresh(force: Boolean = false) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            error = null
            logFlow.value = emptyList()
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
                    status = if (result.ok) "数据已更新" else "核心数据未完整获取，可稍后重试"
                }
                status = "正在汇总…"
                val data = withContext(Dispatchers.IO) { compute() }
                apply(data)
                status = "完成 · 快照 ${data.snapshot} · 指数维度 ${data.rows.size} 个"
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                status = "失败"
                emit("! ${e.message ?: e.toString()}")
            } finally {
                busy = false
            }
        }
    }

    fun updateAutoDaily(enabled: Boolean) {
        autoDaily = enabled
        DailyUpdateScheduler.setEnabled(getApplication(), enabled)
        emit(if (enabled) "已开启每日自动更新（约 19:05）" else "已关闭每日自动更新")
    }

    private fun compute(): Computed {
        val changes = ReportCalc.computeEtfChanges(db)
        val aggregated = ReportCalc.aggregateByIndex(changes)
        return Computed(
            rows = ReportCalc.defaultSorted(aggregated),
            etfCount = changes.size,
            unknown = changes.values.count { it.indexCode.isNullOrEmpty() },
            snapshot = db.latestShareDate() ?: "—",
            shareRows = db.countShareRows(),
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
}
