package com.yucl.etfshare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
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

        /** SQLite 文件头魔数（16 字节，含结尾的 \0）。 */
        private const val SQLITE_MAGIC = "SQLite format 3\u0000"

        /**
         * 「体积明显偏小即视为写坏」的下限。
         * 正常本地库内置快照就有 8MB 以上，低于此值说明写入被中断或库是空的。
         */
        private const val MIN_DB_BYTES = 128L * 1024

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
         * 判定顺序从廉价到昂贵，尽量少碰磁盘：
         *   1. 文件不存在                            -> 导入快照
         *   2. 文件头不是 SQLite / 体积明显偏小（写入被中断）-> 导入快照
         *   3. 深度校验（能否打开 + 核心表是否有数据）失败  -> 导入快照
         *   4. 以上都通过 -> **沿用已有数据，不重新导入**
         *
         * 第 4 条是「当天已抓取就跳过联网」能否成立的前提：一旦重新导入，
         * run_state 里的「今日已抓取」标记会被清掉，重启就会再抓一遍。
         * 所以这里只在**确实坏掉**时才替换数据，并保留旧库备份。
         *
         * 注意：本函数会打开数据库、必要时还会解压 8MB 快照落盘，
         * 必须在后台线程调用（见 App.onCreate），否则会挡住首帧。
         */
        fun ensureLocalData(context: Context): LocalDataStatus {
            val t0 = SystemClock.elapsedRealtime()
            val dbFile = context.getDatabasePath(DB_NAME)
            var imported = false
            var verified: Boolean
            val message: String

            if (!dbFile.exists()) {
                imported = importSeedDb(context, dbFile)
                verified = imported
                message = if (imported) "已导入初始数据快照" else "初始数据导入失败，将自动联网抓取"
            } else if (!looksIntact(dbFile)) {
                Log.w(TAG, "本地数据库文件异常（文件头/体积不对），重新导入初始数据")
                imported = importSeedDb(context, dbFile)
                verified = imported
                message = if (imported) "本地数据异常，已重新导入初始数据快照" else "本地数据异常且导入失败，将自动联网抓取"
            } else if (!usable(dbFile)) {
                Log.w(TAG, "本地数据库深度校验未通过（打不开或核心表为空），重新导入初始数据")
                imported = importSeedDb(context, dbFile)
                verified = imported
                message = if (imported) "本地数据异常，已重新导入初始数据快照" else "本地数据异常且导入失败，将自动联网抓取"
            } else {
                // 关键路径：数据好好的，什么都不做。既不动 run_state，也不解压快照。
                verified = true
                message = "沿用本地已有数据（未重新导入）"
            }

            importSeedDict(context)
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "本地数据准备: ok=$verified imported=$imported ${ms}ms ($message)")
            return LocalDataStatus(verified, imported, message, ms)
        }

        /**
         * 廉价体检：只读文件头 16 字节，不打开数据库、不做任何查询。
         *
         * 用来挡住「库文件缺失 / 被写坏 / 系统在写入途中杀进程」这类明显异常，
         * 又能避免每次启动都做一次昂贵的深度校验（全表 COUNT）。
         */
        private fun looksIntact(dbFile: File): Boolean {
            if (!dbFile.isFile) return false
            if (dbFile.length() < MIN_DB_BYTES) return false
            return try {
                FileInputStream(dbFile).use { input ->
                    val head = ByteArray(16)
                    input.read(head) == 16 && String(head, Charsets.US_ASCII) == SQLITE_MAGIC
                }
            } catch (e: Exception) {
                Log.w(TAG, "数据库文件头读取失败: ${e.message}")
                false
            }
        }

        /** 指数字典缓存（缺失时才写入）。 */
        private fun importSeedDict(context: Context) {
            val dict = File(context.filesDir, "index_dict.json")
            if (dict.exists() && dict.length() > 0) return
            if (copySeedTo(context, dict, SEED_DICT, SEED_DICT_GZ) <= 0) {
                Log.w(TAG, "指数字典缓存导入失败（将联网重建）")
            }
        }

        /**
         * 深度校验：能否打开 + 两张核心表是否都有数据。
         *
         * 这里刻意用 OPEN_READWRITE 而不是 OPEN_READONLY。
         * 本地库由本应用独占，而且跑在 WAL 模式下；WAL 库用**只读**方式打开时
         * 需要能创建/写入 `-shm` 文件，部分系统与 SQLite 版本会直接抛
         * `SQLiteException: unable to open database file`。
         * 一旦被误判成「库坏了」，就会把好端端的数据删掉重新导入
         * （连带清空「今日已抓取」标记），表现正是
         * 「每次启动都重新导入 + 又联网抓一遍 + 启动很慢」。用读写方式打开没有这个坑。
         */
        fun usable(dbFile: File): Boolean = try {
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
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

        /**
         * 删除数据库及其全部旁挂文件。
         *
         * 只删 `.db` 是不够的：WAL 模式下还有 `-wal` / `-shm`（以及旧式回滚日志 `-journal`），
         * 它们属于**旧**数据库。若只换掉 `.db` 而留下旧的 `-wal`，
         * 下次打开时 SQLite 会尝试把旧 WAL 恢复进新文件，轻则报错、重则数据错乱，
         * 于是又触发一次「重新导入」，形成「每次启动都重导」的死循环。
         */
        private fun wipeDbFiles(dbFile: File) {
            val siblings = listOf(
                dbFile,
                File(dbFile.path + "-wal"),
                File(dbFile.path + "-shm"),
                File(dbFile.path + "-journal"),
            )
            for (f in siblings) {
                if (f.exists() && !f.delete()) Log.w(TAG, "无法删除 ${f.name}")
            }
        }

        /** 把 assets 里的初始快照解压落盘，并做完整校验。 */
        private fun importSeedDb(context: Context, dbFile: File): Boolean = try {
            dbFile.parentFile?.mkdirs()
            // 先把旧库改名备份而不是直接删除：万一这次替换是误判，用户攒下的历史还在
            if (dbFile.exists()) {
                val bak = File(dbFile.path + ".broken")
                runCatching { if (bak.exists()) bak.delete() }
                if (!dbFile.renameTo(bak)) Log.w(TAG, "旧数据库备份失败，将直接覆盖")
            }
            wipeDbFiles(dbFile)
            val written = copySeedTo(context, dbFile, SEED_DB, SEED_DB_GZ)
            if (written <= 0) {
                throw IllegalStateException("assets 中未找到初始数据快照（$SEED_DB / $SEED_DB_GZ）")
            }
            if (!usable(dbFile)) throw IllegalStateException("导入后的数据库校验未通过")
            SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                // 数据已被换成快照，旧的运行状态不再可信，必须清空，
                // 否则会误判「今日已抓取」而跳过本该进行的抓取。
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
    /** 本次准备占用的毫秒数，用于回答「启动慢在哪一步」。 */
    val elapsedMs: Long = 0L,
)
