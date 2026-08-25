package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

class P3R7ReportValidatorTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `完整当前报告通过`() {
        val expected = expectedArtifacts()
        val report = report(expected)

        assertDoesNotThrow { P3R7ReportValidator.verify(report, expectation(expected)) }
    }

    @Test
    fun `错误制品哈希和额外失败场景都会拒绝`() {
        val expected = expectedArtifacts()
        val report = report(expected)
        val badHash = report.readText().replace(expected.getValue("server-product").let(::sha256), "0".repeat(64))
        report.writeText(badHash, StandardCharsets.UTF_8)

        assertThrows(GradleException::class.java) {
            P3R7ReportValidator.verify(report, expectation(expected))
        }

        val failedScenario =
            report.readText().replace(
                "TOTAL 3 PASS 3 FAIL 0 ERROR 0 SKIP 0",
                "SCENARIO\toptional\tFAIL\t1\t失败\nTOTAL 4 PASS 3 FAIL 1 ERROR 0 SKIP 0",
            )
        report.writeText(failedScenario, StandardCharsets.UTF_8)
        assertThrows(GradleException::class.java) {
            P3R7ReportValidator.verify(report, expectation(expected))
        }
    }

    @Test
    fun `早于本轮开始时间的报告拒绝`() {
        val expected = expectedArtifacts()
        val report = report(expected)
        Files.setLastModifiedTime(report.toPath(), FileTime.fromMillis(999L))

        assertThrows(GradleException::class.java) {
            P3R7ReportValidator.verify(report, expectation(expected))
        }
    }

    private fun expectedArtifacts(): Map<String, File> =
        P3R7ReportValidator.ARTIFACT_ROLES.associateWith { role ->
            directory.resolve("$role.jar").also { it.writeText(role, StandardCharsets.UTF_8) }
        }

    private fun expectation(artifacts: Map<String, File>): P3R7ReportExpectation =
        P3R7ReportExpectation(
            lane = "测试",
            runId = "r7-test",
            startEpochMs = 1_000L,
            artifacts = artifacts,
        )

    private fun report(artifacts: Map<String, File>): File =
        directory.resolve("report.txt").also { report ->
            report.writeText(
                buildString {
                    appendLine("SERVER-GAMETEST-REPORT v2")
                    appendLine("RUN_ID\tr7-test")
                    appendLine("MATRIX\tR7")
                    appendLine("START_EPOCH_MS\t1000")
                    appendLine("SERVER_JAVA\t25\tC:/jdk/bin/java")
                    appendLine("CLIENT_JAVA\t25\tC:/jdk/bin/java")
                    artifacts.forEach { (role, file) -> appendLine("ARTIFACT\t$role\t${sha256(file)}") }
                    appendLine("SCENARIO\tproduct-handshake\tPASS\t1\t通过")
                    appendLine("SCENARIO\tproduct-roundtrip\tPASS\t1\t通过")
                    appendLine("SCENARIO\tclient-hud\tPASS\t1\t通过")
                    appendLine("TOTAL 3 PASS 3 FAIL 0 ERROR 0 SKIP 0")
                    appendLine("RESULT PASS")
                },
                StandardCharsets.UTF_8,
            )
            Files.setLastModifiedTime(report.toPath(), FileTime.fromMillis(1_000L))
        }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { byte -> "%02x".format(byte) }
}
