package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

/** fabric 验收接入层源集名：三条车道一致，源码在 `gametest/src/main/{java,resources}`。 */
const val GAMETEST_SOURCE_SET: String = "gametest"

/** 模拟服一键门禁任务名。 */
const val SIMULATOR_ACCEPTANCE_TASK: String = "runSimNetworkAcceptance"

/** acceptance v2 报告头（与 realserver harness 的报告格式契约一致）。 */
private const val ACCEPTANCE_REPORT_HEADER = "SERVER-GAMETEST-REPORT v2"

/** 模拟服报告的 platform 元数据标识。 */
private const val SIMULATOR_PLATFORM_ID = "sim-fabric"

/** 报告必需元数据（acceptance v2 契约）。 */
private val REQUIRED_REPORT_METADATA =
    listOf("commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios")

/**
 * 创建 gametest 验收接入层源集。
 *
 * 必须在插件 apply 期完成：车道脚本随后的 `dependencies { }` 块就要往 `gametestImplementation` 加依赖。
 * 源集对 main 的编译/运行类路径继承、`gametestImplementation` extends `implementation`、
 * 以及把 `gametestClasses` 纳入 `build`（只编译、不运行——运行需真实服）都保持原样。
 */
fun registerGametestSourceSet(project: Project) {
    project.pluginManager.withPlugin("java") {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val gametest = sourceSets.create(GAMETEST_SOURCE_SET)
        gametest.java.setSrcDirs(listOf("$GAMETEST_SOURCE_SET/src/main/java"))
        gametest.resources.setSrcDirs(listOf("$GAMETEST_SOURCE_SET/src/main/resources"))
        gametest.compileClasspath += main.compileClasspath + main.output
        gametest.runtimeClasspath += main.runtimeClasspath + main.output
        project.configurations.getByName("${GAMETEST_SOURCE_SET}Implementation")
            .extendsFrom(project.configurations.getByName("implementation"))
        project.tasks.named("build").configure { dependsOn("${GAMETEST_SOURCE_SET}Classes") }
    }
}

/** gametest 源集（[registerGametestSourceSet] 创建；Loom run 以它为 source）。 */
fun gametestSourceSet(project: Project): SourceSet =
    project.extensions.getByType(SourceSetContainer::class.java).getByName(GAMETEST_SOURCE_SET)

/**
 * `fabric.mod.json` 占位注入：产品资源与 gametest 测试 mod 资源用同一份元数据。
 *
 * 元数据全部由车道版本常量派生，故换版本只需改一处。
 */
fun expandModMetadata(project: Project, lane: FabricLaneExtension) {
    val metadata =
        mapOf(
            "version" to project.version,
            "minecraftVersion" to lane.mcVersion.get(),
            "javaVersion" to lane.targetJavaVersion.get(),
            "loaderDependency" to lane.loaderVersion.get(),
            "fabricApiDependency" to lane.fabricApiVersion.get(),
        )
    expandMetadata(project, "processResources", metadata)
    expandMetadata(project, "processGametestResources", metadata)
}

/** 单测的版本/验收系统属性：`mpmt.test.*` 一组，与产品元数据同口径。 */
fun configureGametestTestTask(project: Project, lane: FabricLaneExtension) {
    val mcVersion = lane.mcVersion.get()
    val acceptanceMetadata = project.layout.buildDirectory.file("resources/gametest/fabric.mod.json").get().asFile
    project.tasks.named("test", Test::class.java).configure {
        useJUnitPlatform()
        dependsOn("processGametestResources")
        systemProperty("mpmt.test.minecraftVersion", mcVersion)
        systemProperty("mpmt.test.javaVersion", lane.targetJavaVersion.get().toString())
        systemProperty("mpmt.test.archiveName", "mpmt-fabric-$mcVersion")
        systemProperty("mpmt.test.loaderDependency", lane.loaderVersion.get())
        systemProperty("mpmt.test.fabricApiDependency", lane.fabricApiVersion.get())
        systemProperty("mpmt.test.projectVersion", project.version.toString())
        // 验收元数据：processGametestResources 注入与产品同口径的 depends
        systemProperty("mpmt.test.acceptanceMetadata", acceptanceMetadata.absolutePath)
        systemProperty("mpmt.test.acceptanceArchiveName", "mpmt-fabric-acceptance-$mcVersion-${project.version}.jar")
    }
}

/**
 * 模拟服 GameTest 一键门禁：起 headless 服跑完整默认轨回环场景，
 * 并严格校验 acceptance v2 元数据与场景清单（清单由车道传入）。
 */
fun registerSimulatorAcceptanceGate(project: Project, lane: FabricLaneExtension) {
    val scenarios = lane.simScenarios.get()
    project.tasks.register(SIMULATOR_ACCEPTANCE_TASK) {
        group = "verification"
        description = "起 headless 服跑完整默认轨模拟服场景并严格校验 acceptance v2 报告"
        dependsOn(loomRunTaskName(SIMULATOR_RUN_NAME))
        doLast { verifySimulatorReport(lane.simulatorReport.get().asFile, scenarios) }
    }
}

private fun expandMetadata(project: Project, taskName: String, metadata: Map<String, Any>) {
    project.tasks.named(taskName, ProcessResources::class.java).configure {
        inputs.properties(metadata)
        filesMatching("fabric.mod.json") { expand(metadata) }
    }
}

/** 读报告并逐项校验：v2 头、必需元数据、platform、场景清单、结果行、逐项 PASS。 */
private fun Task.verifySimulatorReport(report: File, scenarios: List<String>) {
    if (!report.exists()) {
        throw GradleException("未找到模拟服报告（runSimNetworkTest 未写出）：${report.absolutePath}")
    }
    val text = report.readText()
    logger.lifecycle("[sim] 模拟服 GameTest 权威报告：\n$text")
    val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.firstOrNull() != ACCEPTANCE_REPORT_HEADER) {
        throw GradleException("[sim] 模拟服报告不是 acceptance v2")
    }
    verifySimulatorMetadata(lines, scenarios)
    verifySimulatorResults(lines, scenarios)
    logger.lifecycle("[sim] 模拟服 GameTest 通过：acceptance v2，${scenarios.size} 项默认轨场景全部 PASS")
}

/** 元数据校验：必需项齐全、platform 为 sim-fabric、产品 jar SHA 合法、场景声明与车道清单一致。 */
// 报告校验按契约逐项失败即抛：各项判据独立且失败文案不同，合并判定会丢失具体原因，故抑制该规则。
@Suppress("ThrowsCount")
private fun verifySimulatorMetadata(lines: List<String>, scenarios: List<String>) {
    val metadata =
        lines.filter { it.startsWith("META ") }.associate { line ->
            val entry = line.removePrefix("META ")
            val separator = entry.indexOf('=')
            if (separator <= 0) throw GradleException("[sim] 非法元数据行：$line")
            entry.substring(0, separator) to entry.substring(separator + 1)
        }
    if (REQUIRED_REPORT_METADATA.any { metadata[it].isNullOrBlank() }) {
        throw GradleException("[sim] 模拟服报告缺少 acceptance v2 必需元数据")
    }
    if (metadata["platform"] != SIMULATOR_PLATFORM_ID) {
        throw GradleException("[sim] platform 元数据必须为 $SIMULATOR_PLATFORM_ID：${metadata["platform"]}")
    }
    if (!metadata.getValue("productJarSha256").matches(Regex("[0-9a-fA-F]{64}"))) {
        throw GradleException("[sim] productJarSha256 元数据非法")
    }
    if (metadata["scenarios"] != scenarios.joinToString(",")) {
        throw GradleException("[sim] 报告场景声明不完整：${metadata["scenarios"]}")
    }
}

/** 结果校验：唯一末行 RESULT PASS，且场景集合与车道清单完全一致、逐项 PASS。 */
// 同 verifySimulatorMetadata：三项判据（唯一结果行 / 场景集合 / 逐项 PASS）各有独立失败文案，故抑制该规则。
@Suppress("ThrowsCount")
private fun verifySimulatorResults(lines: List<String>, scenarios: List<String>) {
    val resultLines = lines.filter { it.startsWith("RESULT ") }
    if (resultLines != listOf("RESULT PASS") || lines.last() != "RESULT PASS") {
        throw GradleException("[sim] 模拟服报告必须仅有一个末行 RESULT PASS")
    }
    val scenarioLines =
        lines.filter {
            it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("ERROR ") || it.startsWith("SKIP ")
        }
    val reported = scenarioLines.associateBy { it.split(' ', limit = 3)[1] }
    if (reported.size != scenarioLines.size || reported.keys != scenarios.toSet()) {
        throw GradleException("[sim] 实际场景与默认轨清单不一致：${reported.keys}")
    }
    if (scenarioLines.any { !it.startsWith("PASS ") }) {
        throw GradleException("[sim] 默认轨场景存在非 PASS 结果")
    }
}
