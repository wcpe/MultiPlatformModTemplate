package top.wcpe.mc.mpmt.gradle.realserver

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** 由版本车道声明的冻结 Paper 运行时制品。 */
data class FrozenPaperArtifact(
    val mcVersion: String,
    val build: Int,
    val sizeBytes: Long,
    val sha256: String,
) {
    init {
        require(mcVersion.isNotBlank()) { "Paper 目标版本不能为空" }
        require(build > 0) { "Paper build 必须为正数" }
        require(sizeBytes > 0) { "Paper 制品大小必须为正数" }
        require(SHA256.matches(sha256)) { "Paper SHA-256 格式非法" }
    }

    fun jarName(): String = "paper-$mcVersion-$build.jar"

    fun verify(file: File) {
        check(file.isFile) { "冻结 Paper 制品不存在：${file.absolutePath}" }
        check(file.length() == sizeBytes) {
            "冻结 Paper 制品大小不匹配：expected=$sizeBytes, actual=${file.length()}"
        }
        check(sha256(file) == sha256) { "冻结 Paper 制品 SHA-256 不匹配：${file.absolutePath}" }
    }

    companion object {
        private val SHA256 = Regex("[0-9a-f]{64}")
        private const val DOWNLOAD_HOST = "fill-data.papermc.io"

        fun isTrustedDownloadUrl(url: String): Boolean =
            runCatching {
                val uri = URI(url)
                uri.scheme == "https" && uri.host == DOWNLOAD_HOST && uri.userInfo == null
            }.getOrDefault(false)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
