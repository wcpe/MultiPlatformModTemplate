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
    private const val RESULT_PREFIX = "RESULT "
    private const val RESULT_PASS = "RESULT PASS"

    fun verify(reportFile: File) {
        // 判定与抛错分开：所有拒绝理由先落成文案，出口只有一个，便于调用方统一收口。
        val problem = problemOf(reportFile)
        if (problem != null) throw GradleException(problem)
    }

    /** 返回拒绝理由；`null` 表示放行。 */
    private fun problemOf(reportFile: File): String? {
        if (!reportFile.isFile) {
            return "未找到验收报告：${reportFile.absolutePath}（先跑对应平台 runAcceptance* / 真服驱动）"
        }
        val text = reportFile.readText()
        val lines =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toList()
        val last = lines.lastOrNull()
        return when {
            last == null -> "验收报告为空：${reportFile.absolutePath}"
            !last.startsWith(RESULT_PREFIX) ->
                "验收报告末行不是 RESULT 行：${reportFile.absolutePath}\n$last"
            last != RESULT_PASS ->
                "验收未通过（$last）：${reportFile.absolutePath}\n—— 报告全文 ——\n$text"
            else -> null
        }
    }
}
