package com.yucl.etfshare.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 极简同步 HTTP 客户端（HttpURLConnection 实现）。
 *
 * 刻意不引入 OkHttp / Retrofit：本工程的数据源都是公开的简单 GET/POST，
 * 平台自带能力足够，保持「零第三方网络库」这一设计约束。
 * 调用方负责切换到 IO 线程。
 */
object Http {

    const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private const val RETRIES = 2
    private const val DEFAULT_TIMEOUT = 15_000

    fun defaultHeaders(): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Referer" to "https://fundf10.eastmoney.com/",
    )

    fun get(
        url: String,
        headers: Map<String, String> = defaultHeaders(),
        timeoutMs: Int = DEFAULT_TIMEOUT,
    ): ByteArray = request("GET", url, headers, null, timeoutMs)

    fun getText(
        url: String,
        headers: Map<String, String> = defaultHeaders(),
        timeoutMs: Int = DEFAULT_TIMEOUT,
    ): String = String(get(url, headers, timeoutMs), Charsets.UTF_8)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = defaultHeaders(),
        timeoutMs: Int = 60_000,
    ): ByteArray = request("POST", url, headers, json.toByteArray(Charsets.UTF_8), timeoutMs)

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        timeoutMs: Int,
    ): ByteArray {
        var last: Exception? = null
        for (attempt in 0..RETRIES) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    useCaches = false
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                }
                if (body != null) {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val raw = conn.inputStream.use { readAll(it) }
                val enc = conn.contentEncoding ?: ""
                return if (enc.contains("gzip", ignoreCase = true)) gunzipOrRaw(raw) else raw
            } catch (e: Exception) {
                last = e
                if (attempt < RETRIES) sleep(800L * (attempt + 1))
            } finally {
                conn?.disconnect()
            }
        }
        throw last ?: IOException("请求失败: $url")
    }

    /** 某些网络栈会自动解压却又保留 Content-Encoding 头，此处做一次容错。 */
    private fun gunzipOrRaw(raw: ByteArray): ByteArray = try {
        GZIPInputStream(raw.inputStream()).use { readAll(it) }
    } catch (_: Exception) {
        raw
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
