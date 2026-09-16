package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import java.io.File

/** 矩阵轨 java 可执行文件缺失环境变量时的提示前缀：服务端与客户端各一份文案。 */
private const val SERVER_PURPOSE = "矩阵轨"
private const val CLIENT_PURPOSE = "矩阵轨客户端"

/** 模拟服报告绑定当前提交、版本与实际产品 jar；故障注入类只来自 gametest 源集，不进入该产品 jar。 */
fun JavaExec.injectSimulatorMetadata(project: Project, lane: FabricLaneExtension) {
    val productJar = project.productJarFile(lane)
    val mcVersion = lane.mcVersion.get()
    systemProperty("mpmt.simtest.commit", project.gitHeadCommit())
    systemProperty("mpmt.simtest.version", project.version.toString())
    systemProperty("mpmt.simtest.mcVersion", mcVersion)
    systemProperty("mpmt.simtest.serverVersion", "Fabric headless $mcVersion")
    systemProperty("mpmt.simtest.productJarSha256", Hashes.sha256(productJar))
}

/**
 * realserver v2 报告元数据：与模拟服同一套绑定（commit / 版本 / 产品 jar SHA）。
 *
 * 矩阵轨（`-Pmpmt.acceptance.matrix` 声明矩阵值）追加 runId / startEpoch / javaExecutable
 * 与五类制品路径（缺省回退产品 jar）与报告路径，供 `MatrixAcceptanceReportV2` 使用。
 * 26.2 的单进程真服编排任务复用本函数，故公共元数据与矩阵元数据的注入顺序保持原样。
 */
fun JavaExec.injectAcceptanceServerMetadata(lane: FabricLaneExtension) {
    val productJar = project.productJarFile(lane)
    val mcVersion = lane.mcVersion.get()
    systemProperty("mpmt.acceptance.commit", project.gitHeadCommit())
    systemProperty("mpmt.acceptance.version", project.version.toString())
    systemProperty("mpmt.acceptance.mcVersion", mcVersion)
    systemProperty("mpmt.acceptance.serverVersion", "Fabric realserver $mcVersion")
    systemProperty("mpmt.acceptance.productJarSha256", Hashes.sha256(productJar))

    val javaHomeEnvironment = lane.matrixJavaHomeEnvironment.orNull
    val matrixId = project.acceptanceMatrix()
    if (javaHomeEnvironment.isNullOrEmpty() || matrixId.isEmpty()) {
        return
    }
    injectMatrixMetadata(project, matrixId, javaHomeEnvironment, productJar)
}

/** 矩阵客户端：注入 javaExecutable（供 ClientReady v2 上报）与矩阵值。 */
fun JavaExec.injectAcceptanceClientMetadata(lane: FabricLaneExtension) {
    val javaHomeEnvironment = lane.matrixJavaHomeEnvironment.orNull
    val matrixId = project.acceptanceMatrix()
    if (javaHomeEnvironment.isNullOrEmpty() || matrixId.isEmpty()) {
        return
    }
    val javaExecutable = project.matrixJavaExecutable(javaHomeEnvironment, CLIENT_PURPOSE)
    systemProperty("mpmt.acceptance.javaExecutable", javaExecutable.absolutePath)
    systemProperty(AcceptanceRound.MATRIX_PROPERTY, matrixId)
}

/**
 * 矩阵轨 java 可执行文件：优先车道声明的 JDK 环境变量（如 `MPMT_JAVA21_HOME`），
 * 退回当前 `java.home`；文件不存在即失败。
 *
 * @param purpose 提示前缀（服务端「矩阵轨」/客户端「矩阵轨客户端」）
 */
fun Project.matrixJavaExecutable(environmentVariable: String, purpose: String): File {
    val javaHome =
        System.getenv(environmentVariable)
            ?: System.getProperty("java.home")
            ?: throw GradleException("$purpose 需要 $environmentVariable 或当前 java.home")
    val suffix = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
    val executable = file("$javaHome/bin/java$suffix")
    if (!executable.isFile) {
        throw GradleException("找不到 Java 可执行文件：${executable.absolutePath}")
    }
    return executable
}

/** 产品 jar 文件：车道以任务名声明产品任务（`remapJar`；26.2 无 remap 时为 `shadowJar`）。 */
internal fun Project.productJarFile(lane: FabricLaneExtension): File =
    tasks.named(lane.productTaskName.get(), AbstractArchiveTask::class.java).get().archiveFile.get().asFile

/** 当前提交（报告元数据绑定）。 */
private fun Project.gitHeadCommit(): String =
    providers.exec {
        commandLine("git", "rev-parse", "HEAD")
    }.standardOutput.asText.get().trim()

/** 矩阵五类制品：`-P` 显式指定则用之，否则以产品 jar 占位（Loom dev 无独立运行期 jar）。 */
private fun Project.artifactOrProduct(property: String, productJar: File): File {
    val raw = (findProperty(property) as String?)?.trim().orEmpty()
    return if (raw.isEmpty()) productJar else file(raw)
}

/** 矩阵元数据：runId / startEpoch / javaExecutable / 五类制品（缺省回退产品 jar）/ 报告路径。 */
private fun JavaExec.injectMatrixMetadata(
    project: Project,
    matrixId: String,
    javaHomeEnvironment: String,
    productJar: File,
) {
    val javaExecutable = project.matrixJavaExecutable(javaHomeEnvironment, SERVER_PURPOSE)
    val report = project.acceptanceReportFile(matrixId)
    val runId = project.requiredAcceptanceProperty(matrixId, AcceptanceRound.RUN_ID_PROPERTY)
    val startEpochMs = project.requiredAcceptanceProperty(matrixId, AcceptanceRound.START_EPOCH_PROPERTY)
    val serverRuntime = project.artifactOrProduct("mpmt.acceptance.artifact.server-runtime", productJar)
    val serverAcceptance = project.artifactOrProduct("mpmt.acceptance.artifact.server-acceptance", productJar)
    val clientProduct = project.artifactOrProduct("mpmt.acceptance.artifact.client-product", productJar)
    val clientAcceptance = project.artifactOrProduct("mpmt.acceptance.artifact.client-acceptance", productJar)

    systemProperty(AcceptanceRound.MATRIX_PROPERTY, matrixId)
    systemProperty(AcceptanceRound.RUN_ID_PROPERTY, runId)
    systemProperty(AcceptanceRound.START_EPOCH_PROPERTY, startEpochMs)
    systemProperty("mpmt.acceptance.javaExecutable", javaExecutable.absolutePath)
    systemProperty("mpmt.acceptance.artifact.server-runtime", serverRuntime.absolutePath)
    systemProperty("mpmt.acceptance.artifact.server-product", productJar.absolutePath)
    systemProperty("mpmt.acceptance.artifact.server-acceptance", serverAcceptance.absolutePath)
    systemProperty("mpmt.acceptance.artifact.client-product", clientProduct.absolutePath)
    systemProperty("mpmt.acceptance.artifact.client-acceptance", clientAcceptance.absolutePath)
    systemProperty(AcceptanceRound.REPORT_PROPERTY, report.absolutePath)
    logger.lifecycle(
        "[realserver] 矩阵 $matrixId 已注入：runId=$runId report=${report.absolutePath}",
    )
}
