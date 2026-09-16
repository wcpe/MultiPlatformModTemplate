package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService

/** run 任务 / 单测 / 契约测试共用的 JDK 启动器（与车道目标 Java 版本一致）。 */
fun forgeJavaLauncher(
    project: Project,
    javaVersion: Int,
): Provider<JavaLauncher> =
    project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }

/** 读取必需运行属性，缺失即失败（文案与迁移前各车道一致）。 */
fun requiredForgeRunProperty(
    project: Project,
    name: String,
): String {
    val value = project.findProperty(name)
    if (value == null || value.toString().isEmpty()) {
        throw GradleException("缺少运行属性 -P$name")
    }
    return value.toString()
}

/**
 * run 任务的验收元数据注入：矩阵 / runId / 时刻、Java 可执行文件与产品、验收制品路径。
 *
 * `serverRuntimeRequired`：真实专用服任务必须显式提供 `-Pmpmt.acceptance.artifact.server-runtime`；
 * dev 编排起的是 loom dev 服务端（其"运行期制品"即产品 jar），未提供时回落产品 jar。
 */
fun configureForgeReportProperties(
    project: Project,
    lane: ForgeLaneExtension,
    task: JavaExec,
    serverRuntimeRequired: Boolean,
) {
    val javaVersion = lane.targetJavaVersion.get()
    val product = project.tasks.named(lane.productTaskName.get(), AbstractArchiveTask::class.java).get().archiveFile.get().asFile
    val acceptanceJarFile =
        project.tasks.named(FORGE_ACCEPTANCE_JAR_TASK, AbstractArchiveTask::class.java).get().archiveFile.get().asFile
    val serverRuntimeProperty = project.findProperty("mpmt.acceptance.artifact.server-runtime")
    val serverRuntime =
        when {
            serverRuntimeProperty != null && serverRuntimeProperty.toString().isNotEmpty() ->
                project.file(serverRuntimeProperty.toString())
            serverRuntimeRequired -> throw GradleException("缺少运行属性 -Pmpmt.acceptance.artifact.server-runtime")
            else -> product
        }
    if (!serverRuntime.isFile) {
        throw GradleException("server-runtime 必须是调用方显式提供的实际文件：$serverRuntime")
    }
    task.systemProperty("mpmt.acceptance.runId", requiredForgeRunProperty(project, AcceptanceRound.RUN_ID_PROPERTY))
    task.systemProperty("mpmt.acceptance.matrix", requiredForgeRunProperty(project, AcceptanceRound.MATRIX_PROPERTY))
    task.systemProperty("mpmt.acceptance.startEpochMs", requiredForgeRunProperty(project, AcceptanceRound.START_EPOCH_PROPERTY))
    task.systemProperty("mpmt.acceptance.javaExecutable", forgeJavaLauncher(project, javaVersion).get().executablePath.asFile.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-runtime", serverRuntime.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-product", product.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-acceptance", acceptanceJarFile.absolutePath)
    task.systemProperty(
        "mpmt.acceptance.artifact.client-product",
        (project.findProperty("mpmt.acceptance.artifact.client-product") ?: product.absolutePath).toString(),
    )
    task.systemProperty(
        "mpmt.acceptance.artifact.client-acceptance",
        (project.findProperty("mpmt.acceptance.artifact.client-acceptance") ?: acceptanceJarFile.absolutePath).toString(),
    )
}

/**
 * realserver 报告门：`verifyAcceptanceReport` 校验权威报告末行 `RESULT PASS`，`runRealServerAcceptance` 为门禁入口。
 *
 * 判定顺序与失败文案与迁移前车道脚本逐条一致（仅车道标签与补跑提示由车道参数派生）。
 */
internal fun registerForgeAcceptanceReportGate(project: Project, lane: ForgeLaneExtension) {
    val laneLabel = lane.laneLabel.get()
    project.tasks.register("verifyAcceptanceReport") {
        group = "verification"
        description = "校验 $laneLabel 真服/验收报告末行 RESULT PASS"
        doLast {
            val report = project.acceptanceReportFrom(*lane.acceptanceReportCandidates.get().toTypedArray())
            if (!report.isFile) {
                throw GradleException("未找到 $laneLabel 验收报告：${report.absolutePath}\n" + lane.acceptanceReportHint.get())
            }
            val lines = report.readText(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                throw GradleException("验收报告为空：${report.absolutePath}")
            }
            val last = lines.last()
            if (!last.startsWith("RESULT ")) {
                throw GradleException("验收报告末行不是 RESULT 行：${report.absolutePath}\n$last")
            }
            if (last != "RESULT PASS") {
                throw GradleException("验收未通过（$last）：${report.absolutePath}\n—— 报告全文 ——\n${report.readText(Charsets.UTF_8)}")
            }
            logger.lifecycle("[realserver] $laneLabel 报告 PASS：${report.absolutePath}")
        }
    }
    project.tasks.register("runRealServerAcceptance") {
        group = "verification"
        description =
            "$laneLabel realserver 门禁：校验权威报告 RESULT PASS（须先起服 + ${lane.acceptanceGateClientHint.get()}）"
        dependsOn("verifyAcceptanceReport")
    }
}

/** 真服验收流程提示（独立 launcher，禁止嵌套根 gradlew）：文本与文档既有步骤一致。 */
internal fun registerForgeAcceptanceRecipe(project: Project, lane: ForgeLaneExtension) {
    val laneLabel = lane.laneLabel.get()
    project.tasks.register("printRealServerAcceptanceRecipe") {
        group = "help"
        description = "打印 $laneLabel 真服验收推荐步骤（独立 launcher，禁止嵌套根 gradlew）"
        doLast {
            logger.lifecycle(
                """
                |[$laneLabel realserver]
                |1) 本目录构建产物：
                |   ./gradlew --no-daemon packageArtifacts
                |2) 起服（二选一）：
                |   a) dev 服：./gradlew --no-daemon runAcceptanceServer
                |   b) 真实专用服：./gradlew --no-daemon runRealServerAcceptanceHost \
                |        -Pmpmt.acceptance.artifact.server-runtime=<forge-server.jar> \
                |        -Pmpmt.acceptance.runId=... -Pmpmt.acceptance.matrix=... \
                |        -Pmpmt.acceptance.startEpochMs=...
                |3) 另开终端客户端伴侣：
                |   ./gradlew --no-daemon runAcceptanceClient -Pmpmt.acceptance.server=127.0.0.1:<port>
                |4) 报告门：
                |   ./gradlew --no-daemon runRealServerAcceptance
                |   # 或 ./gradlew --no-daemon verifyAcceptanceReport
                """.trimMargin().trim(),
            )
        }
    }
}

/**
 * 契约测试：`contractTest` 源集运行车道契约主类，产品 / 验收 jar 路径与版本元数据经 `mpmt.test.*` 注入。
 *
 * 主类留空表示本车道不注册（1.20.1 走 `acceptanceContractTest` 的测试源集形态）。
 */
internal fun registerForgeContractTest(project: Project, lane: ForgeLaneExtension) {
    val contractTestMainClass = lane.contractTestMainClass.get()
    if (contractTestMainClass.isEmpty()) {
        return
    }
    val productTask = project.tasks.named(lane.contractTestProductTask.get(), AbstractArchiveTask::class.java)
    val acceptanceTask = project.tasks.named(lane.contractTestAcceptanceTask.get(), AbstractArchiveTask::class.java)
    val contractTestSourceSet = project.extensions.getByType(SourceSetContainer::class.java).getByName("contractTest")
    project.tasks.register("contractTest", JavaExec::class.java) {
        group = "verification"
        description = lane.contractTestDescription.get()
        dependsOn("contractTestClasses", productTask)
        lane.contractTestDependsOn.get().forEach { dependsOn(it) }
        classpath = contractTestSourceSet.runtimeClasspath
        mainClass.set(lane.contractTestMainClass.get())
        javaLauncher.set(forgeJavaLauncher(project, lane.targetJavaVersion.get()))
        systemProperty("mpmt.test.repositoryRoot", project.rootProject.projectDir.absolutePath)
        systemProperty("mpmt.test.productJar", productTask.flatMap { it.archiveFile }.get().asFile.absolutePath)
        systemProperty("mpmt.test.acceptanceJar", acceptanceTask.flatMap { it.archiveFile }.get().asFile.absolutePath)
        lane.contractTestProperties.get().forEach { (name, value) -> systemProperty(name, value) }
    }
}
