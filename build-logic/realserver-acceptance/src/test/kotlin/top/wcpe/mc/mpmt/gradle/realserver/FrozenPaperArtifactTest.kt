package top.wcpe.mc.mpmt.gradle.realserver

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets

class FrozenPaperArtifactTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `冻结 Paper 制品校验大小和哈希`() {
        val artifact = directory.resolve("paper-26.2-71.jar")
        artifact.writeText("冻结 Paper", StandardCharsets.UTF_8)
        val expected =
            FrozenPaperArtifact(
                mcVersion = "26.2",
                build = 71,
                sizeBytes = artifact.length(),
                sha256 = FrozenPaperArtifact.sha256(artifact),
            )

        assertDoesNotThrow { expected.verify(artifact) }
        artifact.appendText("已损坏", StandardCharsets.UTF_8)
        assertThrows(IllegalStateException::class.java) { expected.verify(artifact) }
    }

    @Test
    fun `只允许官方 HTTPS 下载地址`() {
        assertTrue(FrozenPaperArtifact.isTrustedDownloadUrl("https://fill-data.papermc.io/v1/paper.jar"))
        assertFalse(FrozenPaperArtifact.isTrustedDownloadUrl("http://fill-data.papermc.io/v1/paper.jar"))
        assertFalse(FrozenPaperArtifact.isTrustedDownloadUrl("https://mirror.example/paper.jar"))
    }
}
