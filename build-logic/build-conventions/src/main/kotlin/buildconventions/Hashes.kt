package buildconventions

import java.io.File
import java.security.MessageDigest

/** SHA-256 工具：车道冻结校验共用，避免每份脚本各写一份实现。 */
object Hashes {
    /** 分块读取的缓冲区大小：流式摘要，避免把整份制品读进内存。 */
    private const val BUFFER_SIZE_BYTES: Int = 8192

    /** 单字节掩码：把有符号 `byte` 还原成无符号值，才能格式化出两位十六进制。 */
    private const val UNSIGNED_BYTE_MASK: Int = 0xff

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }
    }
}
