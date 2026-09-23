package com.yucl.etfshare.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
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
         * 读取打包在 assets 里的初始数据。
         *
         * 注意：AAPT/AGP 会把 `.gz` 资源**自动解压并去掉扩展名**，
         * 即 `seed_db.sqlite.gz` 在 APK 里会变成 `seed_db.sqlite`。
         * 因此这里逐个候选名尝试，并按 gzip 魔数决定是否解压，两种形态都能兼容。
         */
        fun readSeed(context: Context, vararg names: String): ByteArray? {
            for (name in names) {
                try {
                    val raw = context.assets.open(name).use { it.readBytes() }
                    if (raw.size < 2) return raw
                    val gzipped = raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()
                    return if (gzipped) {
                        GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
                    } else {
                        raw
                    }
                } catch (_: Exception) {
                    // 试下一个候选名
                }
            }
            return null
        }

        /** 首次启动：导入初始数据快照与指数字典缓存。 */
        fun ensureSeeded(context: Context) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) {
                try {
                    val bytes = readSeed(context, SEED_DB, SEED_DB_GZ)
                        ?: throw IllegalStateException("assets 中未找到初始数据快照")
                    dbFile.parentFile?.mkdirs()
                    dbFile.outputStream().use { it.write(bytes) }
                    // 清空运行状态，避免手机端误判「今日已抓取」而跳过更新
                    SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
                        d.execSQL("DELETE FROM run_state")
                    }
                    Log.i(TAG, "初始数据快照导入完成: ${dbFile.length()} bytes")
                } catch (e: Exception) {
                    Log.w(TAG, "初始数据快照导入失败，将改为全量联网抓取", e)
                    dbFile.delete()
                }
            }
            val dict = File(context.filesDir, "index_dict.json")
            if (!dict.exists()) {
                try {
                    val bytes = readSeed(context, SEED_DICT, SEED_DICT_GZ)
                    if (bytes != null) dict.writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "指数字典缓存导入失败（将联网重建）", e)
                }
            }
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
