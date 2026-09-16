package buildconventions

import java.io.File
import java.security.MessageDigest

/** SHA-256 工具：车道冻结校验共用，避免每份脚本各写一份实现。 */
object Hashes {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
