package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AcceptanceReportGateTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `RESULT PASS 放行`() {
        val report = dir.resolve("ok.txt")
        report.writeText("SERVER-GAMETEST-REPORT v2\nMETA platform=fabric\nRESULT PASS\n")
        assertDoesNotThrow { AcceptanceReportGate.verify(report) }
    }

    @Test
    fun `RESULT FAIL 拒绝`() {
        val report = dir.resolve("fail.txt")
        report.writeText("TOTAL 1 PASS 0\nRESULT FAIL\n")
        assertThrows(GradleException::class.java) { AcceptanceReportGate.verify(report) }
    }

    @Test
    fun `文件不存在拒绝`() {
        assertThrows(GradleException::class.java) {
            AcceptanceReportGate.verify(dir.resolve("missing.txt"))
        }
    }
}
