package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import java.io.File

/**
 * 读服务端权威验收报告并判定是否放行（对齐 platform-fabric 既有门禁语义）。
 *
 * <p>支持：
 * <ul>
 *   <li>v1：末行 {@code RESULT PASS|FAIL}</li>
 *   <li>v2：首行 {@code SERVER-GAMETEST-REPORT v2} 且末行 {@code RESULT PASS}</li>
 * </ul>
 * 不做 sh 编排；仅供 Gradle 任务调用。
 */
object AcceptanceReportGate {
    fun verify(reportFile: File) {
        if (!reportFile.isFile) {
            throw GradleException(
                "未找到验收报告：${reportFile.absolutePath}（先跑对应平台 runAcceptance* / 真服驱动）",
            )
        }
        val text = reportFile.readText()
        val lines =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        if (lines.isEmpty()) {
            throw GradleException("验收报告为空：${reportFile.absolutePath}")
        }
        val last = lines.last()
        if (!last.startsWith("RESULT ")) {
            throw GradleException(
                "验收报告末行不是 RESULT 行：${reportFile.absolutePath}\n$last",
            )
        }
        if (last != "RESULT PASS") {
            throw GradleException(
                "验收未通过（$last）：${reportFile.absolutePath}\n—— 报告全文 ——\n$text",
            )
        }
    }
}
