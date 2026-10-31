package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.provider.Provider
import java.io.File

/** 验收全套跑完的截止时间（毫秒），仍小于全局硬超时。 */
private const val ACCEPTANCE_DEADLINE_MS = 1_260_000L

/** 固定 JVM 参数：容器内无需终端与 Jansi，统一 UTF-8 避免中文日志乱码。 */
private val BASE_JVM_ARGUMENTS: List<String> =
    listOf(
        "-Xmx1G",
        "-Xms512M",
        "-Dfile.encoding=UTF-8",
        "-Dorg.jline.terminal.dumb=true",
        "-Dorg.jline.terminal.jansi=false",
        "-Dorg.jline.terminal.jna=false",
    )

/**
 * 起服涉及的四个制品；客户端两个未单独配置时复用服务端同名制品。
 */
internal class PaperArtifacts(
    val product: File,
    val acceptance: File,
    clientProduct: File? = null,
    clientAcceptance: File? = null,
) {
    val clientProduct: File = clientProduct ?: product
    val clientAcceptance: File = clientAcceptance ?: acceptance
}

/**
 * Paper 启动命令拼装。
 *
 * <p>冻结与非冻结车道的差异已在 jar 解析阶段消化，这里只按
 * 「基础 JVM 参数 → 验收属性 → jar 入口」三段生成命令行；验收属性一律以 `-D` 透传，空值不下发。
 */
internal object PaperLaunchCommand {
    fun build(
        parameters: PaperHostService.Params,
        artifacts: PaperArtifacts,
        paperJar: File,
    ): List<String> =
        buildList {
            // argv[0] 必须是 Java 可执行文件，后面才是 JVM 参数与 jar 入口。
            add(parameters.javaExecutable.get())
            addAll(BASE_JVM_ARGUMENTS)
            if (parameters.acceptanceEnabled.getOrElse(false)) {
                addAll(acceptanceArguments(parameters, artifacts, paperJar))
            }
            add("-jar")
            add(paperJar.absolutePath)
            add("nogui")
        }

    private fun acceptanceArguments(
        parameters: PaperHostService.Params,
        artifacts: PaperArtifacts,
        paperJar: File,
    ): List<String> =
        buildList {
            add("-Dmpmt.acceptance=true")
            add("-Dmpmt.acceptance.report=${parameters.acceptanceReportFile.get().asFile.absolutePath}")
            addProperty("only", parameters.acceptanceOnly)
            addProperty("matrix", parameters.acceptanceMatrix)
            addProperty("runId", parameters.acceptanceRunId)
            addProperty("startEpochMs", parameters.acceptanceStartEpochMs)
            add("-Dmpmt.acceptance.javaExecutable=${parameters.javaExecutable.get()}")
            add("-Dmpmt.acceptance.artifact.server-runtime=${paperJar.absolutePath}")
            add("-Dmpmt.acceptance.artifact.server-product=${artifacts.product.absolutePath}")
            add("-Dmpmt.acceptance.artifact.server-acceptance=${artifacts.acceptance.absolutePath}")
            add("-Dmpmt.acceptance.artifact.client-product=${artifacts.clientProduct.absolutePath}")
            add("-Dmpmt.acceptance.artifact.client-acceptance=${artifacts.clientAcceptance.absolutePath}")
            // 默认轨权威报告元数据（缺任一项驱动会 ERROR framework/driver-error）
            addProperty("commit", parameters.acceptanceCommit)
            addProperty("version", parameters.acceptanceVersion)
            addProperty("mcVersion", parameters.acceptanceMcVersion)
            addProperty("serverVersion", parameters.acceptanceServerVersion)
            addProperty("productJarSha256", parameters.acceptanceProductJarSha256)
            // 全量 acceptance 放大 runner / 截止（仍小于看门狗）
            add("-Dmpmt.acceptance.deadlineMs=$ACCEPTANCE_DEADLINE_MS")
        }

    /** 空值不下发：驱动侧按「属性缺失」而不是「空串」判断未配置。 */
    private fun MutableList<String>.addProperty(
        name: String,
        value: Provider<String>,
    ) {
        value.getOrElse("").takeIf { it.isNotBlank() }?.let { add("-Dmpmt.acceptance.$name=$it") }
    }
}
