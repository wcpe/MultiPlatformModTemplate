package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import java.io.File

/** P3 R7 根门使用的严格报告校验器。 */
data class P3R7ReportExpectation(
    val lane: String,
    val runId: String,
    val startEpochMs: Long,
    val artifacts: Map<String, File>,
)

object P3R7ReportValidator {
    val ARTIFACT_ROLES: List<String> =
        listOf(
            "server-runtime",
            "server-product",
            "server-acceptance",
            "client-product",
            "client-acceptance",
        )
    private val REQUIRED_SCENARIOS: List<String> =
        listOf("product-handshake", "product-roundtrip", "client-hud")

    fun verify(report: File, expected: P3R7ReportExpectation) {
        val parsed = parse(report, expected.lane)
        verifyIdentity(parsed, report, expected)
        verifyArtifacts(parsed, expected)
        verifyScenarios(parsed, expected.lane)
        verifyTotals(parsed, expected.lane)
    }

    private fun parse(report: File, lane: String): ParsedReport {
        if (!report.isFile) fail(lane, "缺少当前权威报告：${report.absolutePath}")
        val lines = report.readLines()
        if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") fail(lane, "报告不是 acceptance v2")
        if (lines.lastOrNull() != "RESULT PASS") fail(lane, "报告末行不是 RESULT PASS")
        return ParsedReport(lane).also { parsed ->
            lines.drop(1).forEach(parsed::accept)
            parsed.verifyRequiredRecords()
        }
    }

    private fun verifyIdentity(parsed: ParsedReport, report: File, expected: P3R7ReportExpectation) {
        if (parsed.runId != expected.runId) fail(expected.lane, "报告不属于本轮 RUN_ID")
        if (parsed.matrix != "R7") fail(expected.lane, "报告 MATRIX 不是 R7")
        if (parsed.startEpochMs != expected.startEpochMs) fail(expected.lane, "报告 START_EPOCH_MS 不属于本轮")
        if (report.lastModified() < expected.startEpochMs) fail(expected.lane, "报告文件早于本轮开始时间")
    }

    private fun verifyArtifacts(parsed: ParsedReport, expected: P3R7ReportExpectation) {
        if (expected.artifacts.keys != ARTIFACT_ROLES.toSet()) fail(expected.lane, "本轮预期制品 role 不完整")
        val actual = parsed.artifacts
        if (actual.keys != expected.artifacts.keys) fail(expected.lane, "报告制品 role 不完整或多余")
        expected.artifacts.forEach { (role, artifact) ->
            if (!artifact.isFile) fail(expected.lane, "本轮制品不存在：$role -> ${artifact.absolutePath}")
            val expectedHash = FrozenPaperArtifact.sha256(artifact)
            if (actual[role] != expectedHash) fail(expected.lane, "报告制品哈希不匹配：$role")
        }
    }

    private fun verifyScenarios(parsed: ParsedReport, lane: String) {
        if (parsed.scenarios.values.any { it == "FAIL" || it == "ERROR" }) fail(lane, "报告包含 FAIL 或 ERROR 场景")
        REQUIRED_SCENARIOS.forEach { scenario ->
            if (parsed.scenarios[scenario] != "PASS") fail(lane, "required 场景未恰好一次 PASS：$scenario")
        }
    }

    private fun verifyTotals(parsed: ParsedReport, lane: String) {
        val totals = parsed.totals ?: fail(lane, "报告缺少 TOTAL")
        val actual = parsed.scenarios.values.groupingBy { it }.eachCount()
        val expected =
            ReportTotals(
                total = parsed.scenarios.size,
                pass = actual["PASS"] ?: 0,
                fail = actual["FAIL"] ?: 0,
                error = actual["ERROR"] ?: 0,
                skip = actual["SKIP"] ?: 0,
            )
        if (totals != expected) fail(lane, "TOTAL 与场景记录不一致")
        if (expected.fail != 0 || expected.error != 0) fail(lane, "RESULT PASS 含失败场景")
    }

    private fun fail(lane: String, message: String): Nothing = throw GradleException("$lane 26.2 R7 $message")

    private data class ReportTotals(
        val total: Int,
        val pass: Int,
        val fail: Int,
        val error: Int,
        val skip: Int,
    )

    private class ParsedReport(private val lane: String) {
        var runId: String? = null
        var matrix: String? = null
        var startEpochMs: Long? = null
        val artifacts: MutableMap<String, String> = linkedMapOf()
        val scenarios: MutableMap<String, String> = linkedMapOf()
        var totals: ReportTotals? = null
        private var serverJavaSeen = false
        private var clientJavaSeen = false
        private var resultCount = 0

        fun accept(line: String) {
            when {
                line.startsWith("RUN_ID\t") -> runId = unique("RUN_ID", runId, fields(line, 2)[1])
                line.startsWith("MATRIX\t") -> matrix = unique("MATRIX", matrix, fields(line, 2)[1])
                line.startsWith("START_EPOCH_MS\t") -> startEpochMs = unique("START_EPOCH_MS", startEpochMs, fields(line, 2)[1].toLong())
                line.startsWith("SERVER_JAVA\t") -> {
                    if (serverJavaSeen) fail(lane, "报告 SERVER_JAVA 重复")
                    javaInfo(line, "SERVER_JAVA")
                    serverJavaSeen = true
                }
                line.startsWith("CLIENT_JAVA\t") -> {
                    if (clientJavaSeen) fail(lane, "报告 CLIENT_JAVA 重复")
                    javaInfo(line, "CLIENT_JAVA")
                    clientJavaSeen = true
                }
                line.startsWith("ARTIFACT\t") -> artifact(line)
                line.startsWith("SCENARIO\t") -> scenario(line)
                line.startsWith("TOTAL ") -> totals = unique("TOTAL", totals, totals(line))
                line == "RESULT PASS" -> resultCount += 1
                else -> fail(lane, "报告包含未知或非法行：$line")
            }
        }

        private fun javaInfo(line: String, key: String) {
            val fields = fields(line, 3)
            if (fields[1].toIntOrNull()?.let { it > 0 } != true || fields[2].isBlank()) {
                fail(lane, "$key 行非法")
            }
        }

        fun verifyRequiredRecords() {
            if (!serverJavaSeen || !clientJavaSeen) fail(lane, "报告缺少 Java 记录")
            if (resultCount != 1) fail(lane, "报告 RESULT PASS 必须恰好一次")
        }

        private fun artifact(line: String) {
            val fields = fields(line, 3)
            if (artifacts.put(fields[1], fields[2]) != null) fail(lane, "报告制品 role 重复：${fields[1]}")
        }

        private fun scenario(line: String) {
            val fields = fields(line, 5)
            if (fields[2] !in setOf("PASS", "FAIL", "ERROR", "SKIP")) fail(lane, "场景状态非法")
            if (fields[3].toLongOrNull()?.let { it >= 0 } != true) fail(lane, "场景耗时非法")
            if (scenarios.put(fields[1], fields[2]) != null) fail(lane, "报告场景重复：${fields[1]}")
        }

        private fun totals(line: String): ReportTotals {
            val fields = line.split(" ")
            if (fields.size != 10 ||
                fields.filterIndexed { index, value -> index % 2 == 0 }.let {
                    it != listOf("TOTAL", "PASS", "FAIL", "ERROR", "SKIP")
                }
            ) {
                fail(lane, "TOTAL 行格式非法")
            }
            return ReportTotals(
                fields[1].toIntOrNull() ?: fail(lane, "TOTAL 数值非法"),
                fields[3].toIntOrNull() ?: fail(lane, "TOTAL 数值非法"),
                fields[5].toIntOrNull() ?: fail(lane, "TOTAL 数值非法"),
                fields[7].toIntOrNull() ?: fail(lane, "TOTAL 数值非法"),
                fields[9].toIntOrNull() ?: fail(lane, "TOTAL 数值非法"),
            )
        }

        private fun fields(line: String, expectedCount: Int): List<String> =
            line.split("\t").also { fields ->
                if (fields.size != expectedCount) fail(lane, "报告字段数非法：$line")
            }

        private fun <T> unique(name: String, current: T?, next: T): T {
            if (current != null) fail(lane, "报告 $name 重复")
            return next
        }
    }
}
