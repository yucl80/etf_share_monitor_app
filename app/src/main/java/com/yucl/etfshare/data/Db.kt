package com.yucl.etfshare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.LocalDate
import java.util.zip.GZIPInputStream

/**
 * 本地存储（SQLite）：ETF 元数据 + 每日份额快照 + 运行状态。
 *
 * 首次启动时若检测到数据库不存在，会把打包在 assets 里的初始数据快照
 * （seed_db.sqlite.gz，约 2 MB）解压落盘 —— 这样首次运行只需增量抓取。
 */
class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "etf_shares.db"
        private const val DB_VERSION = 1
        private const val TAG = "Db"
        const val SEED_DB = "seed_db.sqlite"
        const val SEED_DB_GZ = "seed_db.sqlite.gz"
        const val SEED_DICT = "seed_index_dict.json"
        const val SEED_DICT_GZ = "seed_index_dict.json.gz"

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context).also { instance = it }
        }

        fun today(): String = LocalDate.now().toString()

        fun nowStamp(): String =
            java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        /**
         * 打开打包在 assets 里的初始数据流（自动识别并解压 gzip）。
         *
         * 注意两点：
         *  1. AAPT/AGP 会把 `.gz` 资源**自动解压并去掉扩展名**，
         *     即 `seed_db.sqlite.gz` 在 APK 里会变成 `seed_db.sqlite`；
         *  2. 未压缩的资产可以拿到 `AssetFileDescriptor`（流式读取，几乎不占堆内存），
         *     被压缩的资产拿不到 fd，只能退回普通流。
         * 因此这里先试候选名，再试 fd / 普通流，并按 gzip 魔数决定是否解压，各种形态都能兼容。
         */
        private fun openSeed(context: Context, vararg names: String): InputStream? {
            for (name in names) {
                // 优先走 fd：初始库有 8MB，流式拷贝可以避免一次性占用大块堆内存
                try {
                    val fd = context.assets.openFd(name)
                    return gzipAware(fd.createInputStream())
                } catch (_: Exception) {
                    // 资产被压缩时 openFd 会失败，继续试普通流
                }
                try {
                    return gzipAware(context.assets.open(name))
                } catch (_: Exception) {
                    // 该候选名不存在，试下一个
                }
            }
            return null
        }

        /** 前两字节是 gzip 魔数就套一层 GZIPInputStream，否则原样返回。 */
        private fun gzipAware(input: InputStream): InputStream {
            val head = PushbackInputStream(input, 2)
            val b0 = head.read()
            val b1 = head.read()
            if (b0 < 0) return head
            if (b1 >= 0) head.unread(b1)
            head.unread(b0)
            val isGzip = b0 == 0x1f && b1 == 0x8b
            return if (isGzip) GZIPInputStream(head) else head
        }

        /** 把初始数据流按块写入目标文件（16KB 缓冲），返回写入字节数；失败返回 -1。 */
        private fun copySeedTo(context: Context, dest: File, vararg names: String): Long {
            val input = openSeed(context, *names) ?: return -1
            return try {
                dest.parentFile?.mkdirs()
                input.use { src ->
                    dest.outputStream().use { out -> src.copyTo(out, 16 * 1024) }
                }
                dest.length()
            } catch (e: Exception) {
                Log.w(TAG, "初始数据写入失败: ${dest.name}", e)
                runCatching { dest.delete() }
                -1
            }
        }

        /**
         * 启动时准备本地数据（幂等，可重复调用）。
         *
         * 与旧版只在「文件不存在」时导入不同，这里会**校验**已有数据：
         * 若数据库缺失、损坏或份额表为空（例如上次导入被系统中断），
         * 就重新从 assets 导入快照。这样「首屏一定有数据可看」，
         * 不需要用户手动点刷新。
         */
        fun ensureLocalData(context: Context): LocalDataStatus {
            val dbFile = context.getDatabasePath(DB_NAME)
            var imported = false
            var message: String

            if (!dbFile.exists()) {
                imported = importSeedDb(context, dbFile)
                message = if (imported) "已导入初始数据快照" else "初始数据导入失败，将自动联网抓取"
            } else if (!usable(dbFile)) {
                Log.w(TAG, "本地数据库校验未通过（缺失/损坏/为空），重新导入初始数据")
                imported = importSeedDb(context, dbFile)
                message = if (imported) "本地数据异常，已重新导入初始数据快照" else "本地数据异常且导入失败，将自动联网抓取"
            } else {
                message = "沿用本地已有数据"
            }

            importSeedDict(context)
            val ok = dbFile.exists() && usable(dbFile)
            Log.i(TAG, "本地数据准备: ok=$ok imported=$imported ($message)")
            return LocalDataStatus(ok, imported, message)
        }

        /** 指数字典缓存（缺失时才写入）。 */
        private fun importSeedDict(context: Context) {
            val dict = File(context.filesDir, "index_dict.json")
            if (dict.exists() && dict.length() > 0) return
            if (copySeedTo(context, dict, SEED_DICT, SEED_DICT_GZ) <= 0) {
                Log.w(TAG, "指数字典缓存导入失败（将联网重建）")
            }
        }

        /** 通过「能打开 + 两类核心表都有数据」判断数据库是否可用。 */
        fun usable(dbFile: File): Boolean = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
                val shares = d.rawQuery("SELECT COUNT(*) FROM shares_daily", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
                val meta = d.rawQuery("SELECT COUNT(*) FROM etf_meta", null).use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
                shares > 0 && meta > 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "数据库不可用: ${e.message}")
            false
        }

        /** 把 assets 里的初始快照解压落盘，并做完整校验。 */
        private fun importSeedDb(context: Context, dbFile: File): Boolean = try {
            dbFile.parentFile?.mkdirs()
            if (dbFile.exists() && !dbFile.delete()) {
                throw IllegalStateException("无法覆盖旧数据库: ${dbFile.path}")
            }
            val written = copySeedTo(context, dbFile, SEED_DB, SEED_DB_GZ)
            if (written <= 0) {
                throw IllegalStateException("assets 中未找到初始数据快照（$SEED_DB / $SEED_DB_GZ）")
            }
            if (!usable(dbFile)) throw IllegalStateException("导入后的数据库校验未通过")
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                // 清空运行状态，避免手机端误判「今日已抓取」而跳过更新
                d.execSQL("DELETE FROM run_state")
                d.execSQL(
                    "INSERT OR REPLACE INTO run_state(key,value) VALUES('seed_imported_at',?)",
                    arrayOf(nowStamp()),
                )
                // 显式对齐版本号：让 SQLiteOpenHelper 把这份数据认作「当前版本」，
                // 而不是 version=0 的「从未建过库」。
                d.version = DB_VERSION
            }
            Log.i(TAG, "初始数据快照导入完成: ${dbFile.length()} bytes")
            true
        } catch (e: Exception) {
            Log.w(TAG, "初始数据快照导入失败，将改为联网抓取", e)
            runCatching { dbFile.delete() }
            false
        }
    }

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS etf_meta (
                code         TEXT PRIMARY KEY,
                name         TEXT,
                index_name   TEXT,
                index_code   TEXT,
                f10_updated  TEXT,
                index_source TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shares_daily (
                code   TEXT NOT NULL,
                date   TEXT NOT NULL,
                shares REAL NOT NULL,
                source TEXT NOT NULL,
                PRIMARY KEY (code, date)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shares_date ON shares_daily(date)")
        db.execSQL("CREATE TABLE IF NOT EXISTS run_state (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 表结构向后兼容，暂无升级动作
    }

    // ------------------------------------------------------------ etf_meta

    private data class MetaRow(
        val name: String?,
        val indexName: String?,
        val indexCode: String?,
        val f10Updated: String?,
        val indexSource: String?,
    )

    /** 合并写入（null 表示保留原值），index_code 非空时才更新来源标记。 */
    fun upsertMeta(
        code: String,
        name: String?,
        indexName: String? = null,
        indexCode: String? = null,
        f10Updated: String? = null,
        indexSource: String? = null,
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val old = db.rawQuery(
                "SELECT name,index_name,index_code,f10_updated,index_source FROM etf_meta WHERE code=?",
                arrayOf(code),
            ).use { c ->
                if (c.moveToFirst()) MetaRow(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4)) else null
            }
            val cv = ContentValues().apply {
                put("code", code)
                put("name", name ?: old?.name)
                put("index_name", indexName ?: old?.indexName)
                put("index_code", indexCode ?: old?.indexCode)
                put("index_source", if (indexCode != null) (indexSource ?: old?.indexSource) else old?.indexSource)
                put("f10_updated", f10Updated ?: old?.f10Updated)
            }
            db.insertWithOnConflict("etf_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getMeta(): Map<String, EtfMeta> {
        val out = LinkedHashMap<String, EtfMeta>()
        readableDatabase.rawQuery(
            "SELECT code,name,index_name,index_code,f10_updated,index_source FROM etf_meta", null,
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = EtfMeta(
                    code = c.getString(0),
                    name = c.getString(1),
                    indexName = c.getString(2),
                    indexCode = c.getString(3),
                    f10Updated = c.getString(4),
                    indexSource = c.getString(5),
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------ shares

    fun upsertSharesMany(rows: List<ShareRow>) {
        if (rows.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT INTO shares_daily(code,date,shares,source) VALUES(?,?,?,?) " +
                    "ON CONFLICT(code,date) DO UPDATE SET shares=excluded.shares, source=excluded.source"
            )
            for (r in rows) {
                st.clearBindings()
                st.bindString(1, r.code)
                st.bindString(2, r.date)
                st.bindDouble(3, r.shares)
                st.bindString(4, r.source)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getShareDates(code: String): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT date FROM shares_daily WHERE code=?", arrayOf(code)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    /**
     * 全部份额数据，按 code 分组、日期升序。
     *
     * 通过 JOIN etf_meta 过滤：交易所份额报表里还包含货币型等未纳入 ETF 名单的品种，
     * 避免它们污染指数维度汇总。
     */
    fun getAllShares(): Map<String, List<ShareRecord>> {
        val out = LinkedHashMap<String, MutableList<ShareRecord>>()
        readableDatabase.rawQuery(
            "SELECT s.code, s.date, s.shares FROM shares_daily s " +
                "JOIN etf_meta m ON m.code = s.code ORDER BY s.code, s.date", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getString(0)) { ArrayList() }.add(ShareRecord(c.getString(1), c.getDouble(2)))
            }
        }
        return out
    }

    fun latestShareDate(): String? =
        readableDatabase.rawQuery("SELECT MAX(date) FROM shares_daily", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    fun countShareRows(): Long =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM shares_daily", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /** 本地是否已有可用数据（ETF 名单 + 至少一条份额快照）。 */
    fun hasLocalData(): Boolean = try {
        countShareRows() > 0L && getMeta().isNotEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "本地数据校验失败", e)
        false
    }

    /** 数据摘要，写入日志与界面提示。 */
    fun summary(): String = try {
        "ETF ${getMeta().size} 只 / 份额 ${countShareRows()} 条 / 快照 ${latestShareDate() ?: "—"}"
    } catch (e: Exception) {
        "本地数据库不可用（${e.message}）"
    }

    fun clearRunState() {
        writableDatabase.execSQL("DELETE FROM run_state")
    }

    // ------------------------------------------------------------ run_state

    fun getStates(): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            readableDatabase.rawQuery("SELECT key,value FROM run_state", null).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1) ?: ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "run_state 读取失败", e)
        }
        return out
    }

    fun setStates(values: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement(
                "INSERT INTO run_state(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value"
            )
            for ((k, v) in values) {
                st.clearBindings()
                st.bindString(1, k)
                st.bindString(2, v)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setState(key: String, value: String) = setStates(mapOf(key to value))
}

/** 启动时本地数据准备的结果（供界面提示与日志排查）。 */
data class LocalDataStatus(
    val ok: Boolean,
    val imported: Boolean,
    val message: String,
)
