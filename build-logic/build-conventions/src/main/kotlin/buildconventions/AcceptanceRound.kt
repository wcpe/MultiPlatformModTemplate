package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File

/**
 * 验收轮次上下文（矩阵 / runId / startEpochMs）与权威报告文件的解析规则。
 *
 * 各车道此前的实现逐字相同，集中到此以免多处漂移；通过/失败文案保持原样，
 * 这样日志与真服门的输出不因抽取而改变。
 */
object AcceptanceRound {
    const val MATRIX_PROPERTY: String = "mpmt.acceptance.matrix"
    const val RUN_ID_PROPERTY: String = "mpmt.acceptance.runId"
    const val START_EPOCH_PROPERTY: String = "mpmt.acceptance.startEpochMs"
    const val REPORT_PROPERTY: String = "mpmt.acceptance.report"

    /** 公共场景：REALSERVER262 与各矩阵轨共用。 */
    val PUBLIC_SCENARIOS: List<String> = listOf("product-handshake", "product-roundtrip", "client-hud")
}

/** 当前矩阵值；未指定时为空串（车道据此决定是否走矩阵轨）。 */
fun Project.acceptanceMatrix(): String =
    (findProperty(AcceptanceRound.MATRIX_PROPERTY) as String?)?.trim().orEmpty()

/** 读取必需属性，缺失即失败（文案与各车道原实现一致）。 */
fun Project.requiredAcceptanceProperty(matrixId: String, name: String): String {
    val value = (findProperty(name) as String?)?.trim().orEmpty()
    if (value.isEmpty()) {
        throw GradleException("矩阵 $matrixId 缺少 -P$name")
    }
    return value
}

/** 报告文件：`-Pmpmt.acceptance.report` 覆盖优先，否则取 `build/acceptance/server-report-<矩阵>.txt`。 */
fun Project.acceptanceReportFile(matrixId: String): File {
    val custom = (findProperty(AcceptanceRound.REPORT_PROPERTY) as String?)?.trim().orEmpty()
    return if (custom.isNotEmpty()) {
        file(custom)
    } else {
        layout.buildDirectory.file("acceptance/server-report-${matrixId.lowercase()}.txt").get().asFile
    }
}

/**
 * 报告文件（候选回溯式）：`-Pmpmt.acceptance.report` 覆盖优先；
 * 否则取第一个已存在的候选；都存在不了时取**最后一个**候选（失败文案将指向它，
 * 与各车道"dev 编排目录优先、真实专用服目录兜底"的既有语义一致）。
 *
 * 用于真实专用服 / dev 编排两套运行目录共存的场景（forge / neoforge / sponge）。
 */
fun Project.acceptanceReportFrom(vararg candidates: String): File {
    val custom = (findProperty(AcceptanceRound.REPORT_PROPERTY) as String?)?.trim().orEmpty()
    if (custom.isNotEmpty()) {
        return file(custom)
    }
    val resolved = candidates.map { file(it) }
    return resolved.firstOrNull { it.isFile } ?: resolved.last()
}

/**
 * 校验权威报告：v2 头、MATRIX 命中、RUN_ID 属于本轮、末行 RESULT PASS、公共场景各一次 PASS。
 * 判定顺序与失败文案与各车道原实现一致。
 */
fun Project.verifyAcceptanceRoundReport(report: File, matrixId: String) {
    val lines = report.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") {
        throw GradleException("[realserver] 报告不是 acceptance v2")
    }
    val matrixLine = lines.firstOrNull { it.startsWith("MATRIX\t") || it.startsWith("MATRIX ") }
    if (matrixLine == null || !matrixLine.contains(matrixId)) {
        throw GradleException("[realserver] 矩阵报告缺少 MATRIX $matrixId：$matrixLine")
    }
    val runId = requiredAcceptanceProperty(matrixId, AcceptanceRound.RUN_ID_PROPERTY)
    if (lines.none { it == "RUN_ID\t$runId" || it == "RUN_ID $runId" }) {
        throw GradleException("[realserver] 矩阵 $matrixId 报告不属于当前运行：$runId")
    }
    if (lines.lastOrNull() != "RESULT PASS") {
        throw GradleException("[realserver] 矩阵 $matrixId 未通过：${report.absolutePath}")
    }
    for (id in AcceptanceRound.PUBLIC_SCENARIOS) {
        val passed =
            lines.any {
                it.startsWith("SCENARIO\t$id\tPASS") || it.startsWith("PASS $id") || it.contains("\t$id\tPASS")
            }
        if (!passed) {
            throw GradleException("[realserver] 矩阵 $matrixId 缺少公共场景 PASS：$id")
        }
    }
}
