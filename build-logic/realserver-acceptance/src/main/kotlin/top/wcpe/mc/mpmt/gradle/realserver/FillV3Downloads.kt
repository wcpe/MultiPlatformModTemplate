package top.wcpe.mc.mpmt.gradle.realserver

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * PaperMC Fill v3 下载工具（真服验收 fixture 用）。
 *
 * <p>旧 `api.papermc.io/v2` 已 sunset；迁至 Fill v3（`fill.papermc.io/v3`）。
 * 请求须带 User-Agent。不引第三方 JSON 库，用正则解析少数字段。
 */
internal object FillV3Downloads {
    const val ENDPOINT: String = "https://fill.papermc.io/v3/"
    private const val USER_AGENT: String = "MPMT-realserver-acceptance/1.0"
    const val KEY_PRIMARY: String = "server:default"
    private const val CONNECT_TIMEOUT_MS: Int = 30_000
    private const val READ_TIMEOUT_MS: Int = 60_000

    fun fetchText(url: String): String {
        val conn = open(url)
        try {
            conn.inputStream.use { return it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val conn = open(url)
        try {
            conn.instanceFollowRedirects = false
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("下载响应状态异常：${conn.responseCode}")
            }
            conn.inputStream.use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    fun parseLatestBuildId(json: String): Int =
        Regex(""""id"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toInt()
            ?: throw IllegalStateException("未从 Fill v3 构建响应解析到 id 字段。")

    fun parseDownloadUrl(json: String, key: String): String =
        Regex(
            """"${Regex.escape(key)}"\s*:\s*\{.*?"url"\s*:\s*"([^"]+)"""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(json)?.groupValues?.get(1)
            ?: throw IllegalStateException("未从 Fill v3 构建响应解析到下载键 \"$key\" 的 url。")
}
