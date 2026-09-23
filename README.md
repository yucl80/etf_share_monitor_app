# ETF 份额监控 · Android 原生版

A股场内 ETF 份额变化监控 —— **用 Kotlin 原生重写的 Android 应用**（Jetpack Compose + 原生 SQLite），
不是把命令行脚本套一层壳：数据抓取、存储、汇总、界面全部由 Android 原生代码实现。

以 ETF 跟踪的**指数代码**为维度汇总份额变化，主表默认按「最近 1 日」变化降序排列，
点击任意指数行可下钻查看该指数的成分 ETF 明细。

> 相关工程：
> * `etf_share_monitor`（Python / 桌面 CLI 版，本应用的原型与口径基准）
> * `etf_share_monitor_android`（Termux 直跑版，手机上跑 Python）

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 指数维度汇总 | 把全部 ETF 按跟踪指数归并（如 300ETF、300增强ETF 合并统计） |
| 多窗口变化 | 最近 1日 / 1周 / 1月 / 3月 / 6月 份额变化，单位「亿份」，同时给出变化率与基期日期 |
| 点击表头排序 | 任意列升降序；**默认按最近 1 日降序**；无数据的行恒排末尾 |
| 点击行下钻 | 弹出该指数的成分 ETF 明细（代码 / 名称 / 份额总数 / 各窗口增减），明细内同样可排序，底部有合计行 |
| 本地增量存储 | 原生 SQLite 落库，逐日累积；只抓缺失部分 |
| 智能跳过重复抓取 | 当日已抓到数据 / 非工作日 / 未到份额日报发布时点 → 不再联网 |
| 定时自动更新 | WorkManager 每日约 19:05 自动抓取，完成后发通知（可关闭） |
| 首次免久等 | 首次启动导入打包在 assets 里的数据快照，秒级可用，只需增量抓取 |
| 深色模式 | 跟随系统 |

**颜色约定**：份额增加红色、减少绿色（A股惯例，与欧美相反）。

---

## 技术选型（为什么这样写）

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 | Android 官方推荐技术栈 |
| 本地存储 | 原生 `SQLiteOpenHelper` | 表结构与桌面版一致（`etf_meta` / `shares_daily` / `run_state`），无需 Room 的注解处理 |
| 网络 | `HttpURLConnection` 封装（`core/Http.kt`） | 数据源都是公开的简单 GET/POST，不引入 OkHttp/Retrofit |
| JSON | `org.json` | Android 平台自带 |
| xlsx 解析 | `java.util.zip` + `XmlPullParser`（`core/Xlsx.kt`） | 自行解析沪深交易所导出报表，不引入 POI |
| 并发 | Kotlin Coroutines + `Semaphore` | F10 补全 6 并发，其余串行 |
| 定时任务 | `WorkManager` | 系统托管，无需常驻后台，省电且不会被杀 |

**整个工程没有引入任何第三方网络 / JSON / 数据库 / 表格解析库**，运行时依赖仅 AndroidX 与 Kotlin 标准库。

---

## 构建

### 方式一：Android Studio

用 Android Studio 打开本目录，直接 Run。AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35 / minSdk 26（Android 8.0+）。

### 方式二：命令行

```bash
# 需要 JDK 17 与 Android SDK（platform-35 + build-tools;35.0.0）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug          # 或使用本机 gradle：gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

依赖仓库已在 `settings.gradle.kts` 中配置国内镜像（阿里云 google / central / gradle-plugin），
在 `dl.google.com` 不可达的网络下也能正常构建。

---

## 工程结构

```
app/src/main/java/com/yucl/etfshare/
├── App.kt                     # Application：数据快照解压、通知渠道、注册定时任务
├── core/
│   ├── Http.kt                # HttpURLConnection 封装（重试 / 超时 / gzip 容错）
│   ├── Xlsx.kt                # xlsx 解析（sharedStrings / inlineStr / 按列引用定位）
│   └── Text.kt                # 相似度（LCS）、数字与日期解析
├── data/
│   ├── Models.kt              # 数据模型 + 变化窗口定义
│   ├── Db.kt                  # SQLiteOpenHelper + DAO + 初始快照导入
│   ├── IndexDict.kt           # 中证/国证官网全量指数字典（约 4480 条）+ 名称归一化
│   └── Sources.kt             # 全部官网接口封装（11 个数据源）
├── domain/
│   ├── Updater.kt             # 更新流程：交易所官方份额 -> 兜底 -> F10 -> 映射纠错
│   ├── ReportCalc.kt          # 指数维度汇总 + 排序比较器
│   └── UpdatePolicy.kt        # 「今日是否还需要抓取」判定
├── ui/
│   ├── MainActivity.kt        # 入口（Compose）
│   ├── MainViewModel.kt       # 状态与流程编排
│   ├── ReportScreen.kt        # 主表格 + 下钻明细弹窗 + 日志
│   ├── Theme.kt / Fmt.kt      # 主题与格式化
└── work/
    └── DailyUpdateWorker.kt   # WorkManager 定时任务 + 调度器
```

---

## 数据源

| 数据 | 来源 | 关键约束 |
| --- | --- | --- |
| ETF 全量名单 | 天天基金 `fundcode_search.js` | 按场内代码段过滤（沪 5xxxxx / 深 15,16,18xxxx） |
| 沪市日频份额 | 上交所 `query.sse.com.cn`（`COMMON_SSE_ZQPZ_ETFZL_XXPL_ETFGM_SEARCH_L`） | 按 `STAT_DATE` 单日查询；原始单位「万份」；非交易日返回空 |
| 深市日频份额 | 深交所 `api/report/ShowReport`（`CATALOGID=scsj_fund_jjgm`） | 区间导出 xlsx，单次最长 6 个月 |
| 深市拟合指数 | 深交所 ETF 列表（`CATALOGID=1945`） | 「拟合指数」列形如 `399372 大盘成长`，可直接作为指数映射 |
| 跟踪指数名称 | 天天基金 F10 基金概况 | 正则提取「跟踪标的」 |
| 跟踪指数代码 | 官方指数字典 + 东财搜索 + 基金详情接口 `INDEXCODE` | 基金详情接口限流极严，已做全局限速 + 指数退避 |
| 官方指数字典 | 中证指数官网（**必须 POST**）+ 国证指数官网 | 缓存 30 天，落 `filesDir/index_dict.json` |
| 兜底份额 | 腾讯行情 `qt.gtimg.cn` | 份额 = 总市值 ÷ 最新价，仅交易所报表未覆盖时使用 |

### 跟踪指数解析优先级

`alias`（手工别名，最高）→ `dict`（官网全量字典）→ `search`（东财检索）→ `fund_api`（基金详情接口兜底）

「补全缺失」与「纠正错误」使用**不同严格度**：纠正只接受官方**全称**精确匹配，
避免把同名不同机构的指数改错（如「新能电池」同为国证 980032 与中证 931555）。

---

## 口径说明

* **份额单位**：内部统一为「份」，展示一律换算为「亿份」。
* **基期选取**：在 [今日-N-容限, 今日-N+容限] 内取距目标日最近的记录，
  且基期须早于当前快照 N/2 天以上。容限：1日 4 / 1周 6 / 1月 20 / 3月 45 / 6月 60（天）。
* **指数维度变化率**：以成分 ETF 的**基期份额之和**为分母（非当前份额之和）。
* **未识别跟踪指数**：`index_code` 为空则不参与指数维度汇总（界面上单独计数）。
* **无数据行**：某窗口缺少可用基期时显示 `—`，排序时恒排末尾。

---

## 数据来源与免责

全部数据取自公开官网与官方行情接口，仅供研究参考，不构成任何投资建议。
接口字段或地址变化可能导致抓取失败（界面「运行日志」会给出具体错误）。
