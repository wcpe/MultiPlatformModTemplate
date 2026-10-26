package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File

/** acceptance v2 报告头（与 realserver harness 的报告格式契约一致）。 */
private const val DEFAULT_TRACK_REPORT_HEADER = "SERVER-GAMETEST-REPORT v2"

/** 报告必需元数据（acceptance v2 契约）。 */
private val DEFAULT_TRACK_REQUIRED_METADATA =
    listOf("commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios")

/**
 * 「默认轨」真服报告的完整校验（车道内的 `runRealServerAcceptance` 共用一份实现）。
 *
 * 与 `verifyAcceptanceRoundReport`（按矩阵轮次校验）互补：本函数处理**矩阵轨上下文之外的默认轨**——
 * 即报告不带轮次、按固定场景清单逐项判定。校验顺序、失败文案与逐项强度与迁移前的车道内实现一致：
 * 报告存在性 → v2 报告头 → 元数据齐全 → platform 标识 → 产品 jar SHA → 场景声明 → 唯一末行 RESULT PASS
 * → 场景集合与清单一致 → 逐项 PASS。
 *
 * 配置缓存约束：本函数**接收 `Logger` 而不是 `Project`**。任务动作里若捕获 `Project`，
 * 配置缓存会在存储阶段报 "cannot serialize Gradle script object references" 并令构建失败
 *（本仓默认开启 configuration-cache）。调用方在 `doLast` 里传任务自身的 `logger`——
 * 它是 `Task` 的成员，随任务一同被序列化，不产生额外捕获。
 *
 * @param logger 任务自身的 logger（`doLast` 里直接写 `logger`）。
 * @param report 报告文件（各车道自行解析路径：默认轨取车道声明值，矩阵轨用 `acceptanceReportFile`）。
 * @param matrixId 非空时改走矩阵校验：此时 [matrixRunId] 必须给出，本函数只做前置的头部检查后交给矩阵校验。
 * @param matrixRunId 矩阵轨的本轮 runId（配置期从 `-Pmpmt.acceptance.runId` 解析并固化）。
 * @param platformId 报告的 `platform` 元数据取值（如 `fabric` / `forge` / `neoforge`）。
 * @param requiredScenarios 本车道的默认轨场景清单（顺序敏感，用于比对报告声明）。
 */
fun verifyDefaultTrackReport(
    logger: Logger,
    report: File,
    matrixId: String,
    matrixRunId: String?,
    platformId: String,
    requiredScenarios: List<String>,
) {
    if (!report.exists()) {
        throw GradleException(
            "未找到验收报告（先跑 runAcceptanceServer + runAcceptanceClient）：${report.absolutePath}",
        )
    }
    val text = report.readText()
    logger.lifecycle("[realserver] 服务端权威验收报告：\n$text")
    val lines =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    if (lines.firstOrNull() != DEFAULT_TRACK_REPORT_HEADER) {
        throw GradleException("[realserver] 报告不是 acceptance v2")
    }
    if (matrixId.isNotEmpty()) {
        val runId =
            matrixRunId
                ?: throw GradleException("矩阵 $matrixId 缺少 -P${AcceptanceRound.RUN_ID_PROPERTY}")
        verifyAcceptanceRoundReport(report = report, matrixId = matrixId, runId = runId, lines = lines)
        logger.lifecycle("[realserver] 矩阵 $matrixId 报告 PASS：${report.absolutePath}")
        return
    }
    verifyDefaultTrackMetadata(lines, platformId, requiredScenarios)
    verifyDefaultTrackResults(lines, requiredScenarios)
    logger.lifecycle(
        "[realserver] 验收通过 ✓ acceptance v2，${requiredScenarios.size} 项 REAL_REQUIRED 全部 PASS",
    )
}

/**
 * 元数据校验：必需项齐全、platform 标识正确、产品 jar SHA 合法、场景声明与车道清单一致。
 *
 * 抑制 ThrowsCount：本函数是"报告照契约逐项判定"的对照表，判定顺序与失败文案本身即契约
 * （与 `verifyAcceptanceRoundReport` 同款处理）；拆成多个小函数会让该契约更难逐行比对。
 */
@Suppress("ThrowsCount")
private fun verifyDefaultTrackMetadata(lines: List<String>, platformId: String, requiredScenarios: List<String>) {
    val metadata =
        lines.filter { it.startsWith("META ") }.associate { line ->
            val entry = line.removePrefix("META ")
            val separator = entry.indexOf('=')
            if (separator <= 0) throw GradleException("[realserver] 非法元数据行：$line")
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
    if (DEFAULT_TRACK_REQUIRED_METADATA.any { metadata[it].isNullOrBlank() }) {
        throw GradleException("[realserver] 报告缺少 acceptance v2 必需元数据")
    }
    if (metadata["platform"] != platformId) {
        throw GradleException("[realserver] platform 元数据必须为 $platformId：${metadata["platform"]}")
    }
    if (!metadata.getValue("productJarSha256").matches(Regex("[0-9a-fA-F]{64}"))) {
        throw GradleException("[realserver] productJarSha256 元数据非法")
    }
    if (metadata["scenarios"] != requiredScenarios.joinToString(",")) {
        throw GradleException("[realserver] 报告场景声明不完整：${metadata["scenarios"]}")
    }
}

/**
 * 结果校验：唯一末行 RESULT PASS、场景集合与清单完全一致、逐项 PASS。
 *
 * 抑制理由同上：判定顺序与失败文案是契约，不拆函数以便与报告格式逐行对照。
 */
@Suppress("ThrowsCount")
private fun verifyDefaultTrackResults(lines: List<String>, requiredScenarios: List<String>) {
    val resultLines = lines.filter { it.startsWith("RESULT ") }
    if (resultLines != listOf("RESULT PASS") || lines.last() != "RESULT PASS") {
        throw GradleException("[realserver] 报告必须仅有一个末行 RESULT PASS")
    }
    val scenarioLines =
        lines.filter {
            it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("ERROR ") || it.startsWith("SKIP ")
        }
    val scenarios = scenarioLines.associateBy { it.split(' ', limit = 3)[1] }
    if (scenarios.size != scenarioLines.size || scenarios.keys != requiredScenarios.toSet()) {
        throw GradleException("[realserver] 实际场景与默认轨 REAL_REQUIRED 不一致：${scenarios.keys}")
    }
    if (scenarioLines.any { !it.startsWith("PASS ") }) {
        throw GradleException("[realserver] 默认轨场景存在非 PASS 结果")
    }
}
