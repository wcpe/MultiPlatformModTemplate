package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.language.jvm.tasks.ProcessResources

/** 验收驱动接入层源集名。 */
private const val SPONGE_ACCEPTANCE_SOURCE_SET: String = "acceptance"

/** 验收契约测试源集名。 */
private const val SPONGE_ACCEPTANCE_TEST_SOURCE_SET: String = "acceptanceTest"

/** 验收驱动插件 jar 任务名（仅验收运行期用，不入产品 jar）。 */
private const val SPONGE_ACCEPTANCE_JAR_TASK: String = "acceptanceJar"

/** 契约测试任务名。 */
private const val SPONGE_ACCEPTANCE_CONTRACT_TEST_TASK: String = "acceptanceContractTest"

/** realserver 真服门禁任务名。 */
private const val SPONGE_REALSERVER_GATE_TASK: String = "runRealServerAcceptance"

/** 需 shade 进验收驱动插件 jar 的专用配置名（车道按此名声明并加依赖）。 */
private const val SPONGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION: String = "acceptanceShadowBundle"

/** shadow 插件 id：验收驱动插件 jar 也是自包含 shadowJar，须等它应用后再注册。 */
private const val SPONGE_SHADOW_PLUGIN_ID: String = "com.gradleup.shadow"

/**
 * 验收接入层：验收源集、契约测试源集、验收驱动插件 jar 与 build/check 接线（ADR-0014）。
 *
 * Sponge 无 GameTest，realserver 是其唯一实机验收形态；验收驱动代码不入产品插件 jar，仅在验收运行期
 * 放入服务端。编译期继承 main 类路径（含 spongeapi + spi/server）+ main 产物，叠加 acceptance 核心 + protocol；
 * 客户端复用我方 Fabric 验收伴侣（异构互通）。
 *
 * 源集必须在插件 apply 期建好：车道脚本随后的 `dependencies {}` 要往这些配置里加依赖。
 */
internal fun registerSpongeAcceptancePlumbing(project: Project, lane: SpongeLaneExtension) {
    project.pluginManager.withPlugin("java") {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val acceptance = sourceSets.create(SPONGE_ACCEPTANCE_SOURCE_SET)
        acceptance.compileClasspath += main.compileClasspath + main.output
        acceptance.runtimeClasspath += main.runtimeClasspath + main.output
        val configurations = project.configurations
        configurations.getByName("${SPONGE_ACCEPTANCE_SOURCE_SET}Implementation")
            .extendsFrom(configurations.getByName("implementation"))

        // 纯 JVM 验收契约源集：验证默认轨清单与 acceptance v2 严格报告
        val acceptanceTest = sourceSets.create(SPONGE_ACCEPTANCE_TEST_SOURCE_SET)
        acceptanceTest.compileClasspath += acceptance.output + acceptance.compileClasspath + main.output
        acceptanceTest.runtimeClasspath +=
            acceptanceTest.output + acceptance.runtimeClasspath + acceptanceTest.compileClasspath
        configurations.getByName("${SPONGE_ACCEPTANCE_TEST_SOURCE_SET}Implementation")
            .extendsFrom(configurations.getByName("testImplementation"))
        configurations.getByName("${SPONGE_ACCEPTANCE_TEST_SOURCE_SET}RuntimeOnly")
            .extendsFrom(configurations.getByName("testRuntimeOnly"))

        // 验收插件 sponge_plugins.json 的 ${version} 占位由构建注入
        project.tasks.named("processAcceptanceResources", ProcessResources::class.java).configure {
            inputs.property("version", project.version)
            filesMatching("META-INF/sponge_plugins.json") { expand(mapOf("version" to project.version)) }
        }

        val acceptanceContractTest = registerSpongeAcceptanceContractTest(project, acceptanceTest)
        project.pluginManager.withPlugin(SPONGE_SHADOW_PLUGIN_ID) {
            registerSpongeAcceptanceJar(project, lane, acceptance)
        }
        // 把验收源集与契约测试纳入常规 build/check
        project.tasks.named("build").configure {
            dependsOn(project.tasks.named(acceptance.classesTaskName), acceptanceContractTest)
        }
        project.tasks.named("check").configure { dependsOn(acceptanceContractTest) }
    }
}

/** 契约测试任务：跑 acceptance v2 与完整默认轨场景契约。 */
private fun registerSpongeAcceptanceContractTest(
    project: Project,
    acceptanceTest: SourceSet,
): TaskProvider<Test> =
    project.tasks.register(SPONGE_ACCEPTANCE_CONTRACT_TEST_TASK, Test::class.java) {
        group = "verification"
        description = "运行 Sponge acceptance v2 与完整默认轨场景契约测试"
        testClassesDirs = acceptanceTest.output.classesDirs
        classpath = acceptanceTest.runtimeClasspath
        useJUnitPlatform()
    }

/**
 * 验收驱动插件 jar：shade acceptance 核心 + protocol + core-domain
 * （均第一方、无第三方运行期依赖，无需 relocate）。
 */
private fun registerSpongeAcceptanceJar(project: Project, lane: SpongeLaneExtension, acceptance: SourceSet) {
    // shadowJar 类型不在插件工程编译类路径内，按全名反射取；注册后向下转型到 Jar 配置通用打包属性。
    val shadowType = externalTaskType(project.extensions.getByName(SPONGE_SHADOW_EXTENSION), SPONGE_SHADOW_JAR_CLASS)
    project.tasks.register(SPONGE_ACCEPTANCE_JAR_TASK, shadowType) {
        val archive = this as Jar
        archive.group = "build"
        archive.description =
            "构建 realserver 验收驱动插件 ${lane.acceptanceJarName.get()}（仅验收运行期用，不入产品 jar）"
        archive.archiveBaseName.set(lane.acceptanceJarName.get())
        archive.archiveClassifier.set("")
        archive.from(acceptance.output)
        archive.invokeGroovy(
            "setConfigurations",
            listOf(project.configurations.getByName(SPONGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION)),
        )
        archive.exclude("META-INF/maven/**")
        // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
        archive.outputs.upToDateWhen { false }
        archive.outputs.cacheIf { false }
    }
}

/**
 * realserver 真服门禁：Sponge 服 + Fabric gametest 客户端进服写报告后校验权威报告。
 *
 * 判定逻辑与文案与迁移前车道脚本逐字一致（报告路径由车道参数给出，`-Pmpmt.acceptance.report` 覆盖优先）。
 */
internal fun registerSpongeRealserverGate(project: Project, lane: SpongeLaneExtension) {
    val spongeRealserverReport = project.provider { project.acceptanceReportFrom(lane.acceptanceReport.get()) }
    project.tasks.register(SPONGE_REALSERVER_GATE_TASK) {
        group = "verification"
        description = "Sponge realserver 门禁：校验权威报告 RESULT PASS"
        doLast {
            val report = spongeRealserverReport.get()
            if (!report.isFile) {
                throw GradleException(
                    "未找到 Sponge 验收报告：${report.absolutePath}（先起 Sponge + Fabric gametest 客户端）",
                )
            }
            val lines =
                report.readText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            if (lines.lastOrNull() != "RESULT PASS") {
                throw GradleException(
                    "Sponge realserver 未通过：${report.absolutePath}\n${report.readText()}",
                )
            }
            logger.lifecycle("[realserver] Sponge 报告 PASS：${report.absolutePath}")
        }
    }
}
