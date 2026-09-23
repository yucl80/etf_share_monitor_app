package com.yucl.etfshare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yucl.etfshare.data.IndexRow
import com.yucl.etfshare.data.WindowStat
import com.yucl.etfshare.data.Windows
import com.yucl.etfshare.data.YI
import com.yucl.etfshare.domain.ReportCalc

private val W_CODE = 76.dp

/**
 * 指数名称 / ETF 名称列宽。
 *
 * 主表已去掉「ETF数」列（成分数量改为在名称下方以「N 只成分」小字呈现），
 * 腾出的 50dp 直接并入名称列，使长指数名显示更完整；
 * 这样整表总宽与旧版完全一致，右侧份额与各窗口数字列的位置不会发生跳动。
 */
private val W_NAME = 196.dp
private val W_SHARES = 98.dp
private val W_WIN = 98.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(vm: MainViewModel) {
    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf("1d") }
    var ascending by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<IndexRow?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val hScroll = rememberScrollState()

    val visibleRows = remember(vm.rows, query, sortKey, ascending) {
        val filtered = if (query.isBlank()) {
            vm.rows
        } else {
            vm.rows.filter {
                it.indexCode.contains(query, ignoreCase = true) ||
                    it.indexName.contains(query, ignoreCase = true)
            }
        }
        if (sortKey == "1d" && !ascending) {
            ReportCalc.defaultSorted(filtered)
        } else {
            filtered.sortedWith(ReportCalc.comparator(sortKey, ascending))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ETF 份额监控", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "快照 ${vm.snapshot} · ${vm.etfCount} 只 ETF · ${vm.rows.size} 个指数维度",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (vm.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(20.dp).height(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    IconButton(onClick = { vm.refresh(force = false) }, enabled = !vm.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "更新数据")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("强制重新抓取") },
                                onClick = { menuOpen = false; vm.refresh(force = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("仅按本地数据重算") },
                                onClick = { menuOpen = false; vm.reload() },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (vm.autoDaily) "关闭每日自动更新" else "开启每日自动更新")
                                },
                                onClick = { menuOpen = false; vm.updateAutoDaily(!vm.autoDaily) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (vm.autoOnLaunch) "关闭启动时自动更新" else "开启启动时自动更新")
                                },
                                onClick = { menuOpen = false; vm.updateAutoOnLaunch(!vm.autoOnLaunch) },
                            )
                            DropdownMenuItem(
                                text = { Text("运行日志") },
                                onClick = { menuOpen = false; showLog = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    StatusLine(vm)
                    if (vm.busy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        placeholder = { Text("按指数代码或名称筛选", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "清除")
                                }
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    )
                }
            }

            TableHeader(
                hScroll = hScroll,
                sortKey = sortKey,
                ascending = ascending,
                onSort = { key ->
                    if (sortKey == key) {
                        ascending = !ascending
                    } else {
                        sortKey = key
                        ascending = key == "code" || key == "name"
                    }
                },
            )
            HorizontalDivider()

            if (visibleRows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            vm.busy -> "正在抓取数据（首次约 1~3 分钟）…"
                            vm.shareRows == 0L -> "本地暂无数据 · 已自动开始抓取，请稍候"
                            else -> "没有匹配的指数"
                        },
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visibleRows, key = { it.indexCode }) { row ->
                        IndexTableRow(row, hScroll) { detail = row }
                    }
                }
            }
        }
    }

    detail?.let { row ->
        DetailSheet(row) { detail = null }
    }

    if (showLog) {
        val logs by vm.logs.collectAsState()
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("运行日志", fontSize = 16.sp) },
            text = {
                Column(
                    Modifier
                        .fillMaxHeight(0.7f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (logs.isEmpty()) Text("暂无日志", fontSize = 12.sp)
                    logs.takeLast(400).forEach {
                        Text(
                            it,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLog = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun StatusLine(vm: MainViewModel) {
    val err = vm.error
    if (err != null) {
        Text(
            "错误：$err",
            fontSize = 12.sp,
            color = Palette.Up,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    } else {
        Text(
            vm.status,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TableHeader(
    hScroll: androidx.compose.foundation.ScrollState,
    sortKey: String,
    ascending: Boolean,
    onSort: (String) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .horizontalScroll(hScroll)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("指数代码", W_CODE, "code", sortKey, ascending, false, onSort)
            HeaderCell("指数名称", W_NAME, "name", sortKey, ascending, false, onSort)
            HeaderCell("份额总数", W_SHARES, "shares", sortKey, ascending, true, onSort)
            for (spec in Windows.ALL) {
                HeaderCell(spec.label, W_WIN, spec.key, sortKey, ascending, true, onSort)
            }
        }
    }
}

@Composable
private fun HeaderCell(
    text: String,
    width: Dp,
    key: String,
    sortKey: String,
    ascending: Boolean,
    alignEnd: Boolean,
    onSort: (String) -> Unit,
) {
    val active = sortKey == key
    Row(
        Modifier
            .width(width)
            .clickable { onSort(key) }
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            maxLines = 1,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (active) (if (ascending) "↑" else "↓") else "",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun IndexTableRow(
    row: IndexRow,
    hScroll: androidx.compose.foundation.ScrollState,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.horizontalScroll(hScroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.indexCode,
                modifier = Modifier.width(W_CODE).padding(horizontal = 6.dp),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Column(Modifier.width(W_NAME).padding(horizontal = 6.dp)) {
                Text(row.indexName, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${row.etfCount} 只成分",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                Fmt.yi(row.shares / YI),
                modifier = Modifier.width(W_SHARES).padding(horizontal = 6.dp),
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            for (spec in Windows.ALL) {
                WindowCell(row.windows[spec.key], W_WIN)
            }
            Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun WindowCell(stat: WindowStat?, width: Dp) {
    Column(
        Modifier.width(width).padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (stat == null) {
            Text("—", fontSize = 12.sp, color = Palette.Flat)
        } else {
            val deltaYi = stat.delta / YI
            Text(
                Fmt.signedYi(deltaYi),
                fontSize = 12.sp,
                color = Palette.delta(stat.delta),
                maxLines = 1,
            )
            Row {
                Text(Fmt.pct(stat.pct), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "基期 ${Fmt.monthDay(stat.baseDate)}",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(row: IndexRow, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sortKey by remember { mutableStateOf("shares") }
    var ascending by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()

    val members = remember(row, sortKey, ascending) {
        row.members.sortedWith(ReportCalc.memberComparator(sortKey, ascending))
    }
    val toggle: (String) -> Unit = { key ->
        if (sortKey == key) {
            ascending = !ascending
        } else {
            sortKey = key
            ascending = key == "code" || key == "name"
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxHeight(0.88f)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "${row.indexCode}  ${row.indexName}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "共 ${row.etfCount} 只成分 ETF · 份额合计 ${Fmt.yi(row.shares / YI)} 亿份",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
            }
            HorizontalDivider()

            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    Modifier.horizontalScroll(hScroll).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeaderCell("ETF代码", W_CODE, "code", sortKey, ascending, false, toggle)
                    HeaderCell("ETF名称", W_NAME, "name", sortKey, ascending, false, toggle)
                    HeaderCell("份额总数", W_SHARES, "shares", sortKey, ascending, true, toggle)
                    for (spec in Windows.ALL) {
                        HeaderCell(spec.label, W_WIN, spec.key, sortKey, ascending, true, toggle)
                    }
                }
            }
            HorizontalDivider()

            LazyColumn(Modifier.weight(1f)) {
                items(members, key = { it.code }) { m ->
                    Row(
                        Modifier.horizontalScroll(hScroll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            m.code,
                            modifier = Modifier.width(W_CODE).padding(horizontal = 6.dp, vertical = 5.dp),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Text(
                            m.name,
                            modifier = Modifier.width(W_NAME).padding(horizontal = 6.dp),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            Fmt.yi(m.shares / YI),
                            modifier = Modifier.width(W_SHARES).padding(horizontal = 6.dp),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                        )
                        for (spec in Windows.ALL) {
                            WindowCell(m.windows[spec.key], W_WIN)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
                item {
                    TotalsRow(row, hScroll)
                }
            }
        }
    }
}

/** 明细弹窗中「成分 ETF」单元格宽度与主表保持一致。 */
@Suppress("unused")
private val MEMBER_CODE_WIDTH: Dp = W_CODE

@Composable
private fun TotalsRow(row: IndexRow, hScroll: androidx.compose.foundation.ScrollState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.horizontalScroll(hScroll).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "合计",
                modifier = Modifier.width(W_CODE).padding(horizontal = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${row.etfCount} 只",
                modifier = Modifier.width(W_NAME).padding(horizontal = 6.dp),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                Fmt.yi(row.shares / YI),
                modifier = Modifier.width(W_SHARES).padding(horizontal = 6.dp),
                fontSize = 12.sp,
                textAlign = TextAlign.End,
                fontWeight = FontWeight.Bold,
            )
            for (spec in Windows.ALL) {
                val w = row.windows[spec.key]
                Column(
                    Modifier.width(W_WIN).padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    if (w == null) {
                        Text("—", fontSize = 12.sp, color = Palette.Flat)
                    } else {
                        Text(
                            Fmt.signedYi(w.delta / YI),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Palette.delta(w.delta),
                        )
                        Text(
                            Fmt.pct(w.pct),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
    }
}
