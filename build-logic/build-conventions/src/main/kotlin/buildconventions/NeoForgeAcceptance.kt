package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

/** 验收驱动接入层源集名。 */
internal const val NEOFORGE_ACCEPTANCE_SOURCE_SET: String = "acceptance"

/** 验收契约测试源集名。 */
internal const val NEOFORGE_ACCEPTANCE_TEST_SOURCE_SET: String = "acceptanceTest"

/** 验收驱动 mod jar 任务名（仅验收运行期用，不入产品 jar）。 */
internal const val NEOFORGE_ACCEPTANCE_JAR_TASK: String = "acceptanceJar"

/** 契约测试任务名。 */
private const val NEOFORGE_ACCEPTANCE_CONTRACT_TEST_TASK: String = "acceptanceContractTest"

/** 模拟服验收任务名。 */
private const val NEOFORGE_SIM_ACCEPTANCE_TASK: String = "runSimNetworkAcceptance"

/** 权威报告校验任务名。 */
private const val NEOFORGE_VERIFY_ACCEPTANCE_TASK: String = "verifyAcceptanceReport"

/** realserver 真服门禁任务名。 */
private const val NEOFORGE_REALSERVER_GATE_TASK: String = "runRealServerAcceptance"

/** 需 shade 进验收 mod jar 的专用配置名（车道按此名声明并加依赖）。 */
internal const val NEOFORGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION: String = "acceptanceShadowBundle"

/** 模拟服默认轨报告文件（`build/` 下）。 */
internal const val NEOFORGE_SIM_REPORT_PATH: String = "acceptance/sim-report-v2.txt"

/** 产品 jar SHA-256 属性（驱动据此核对被测产物）。 */
private const val MPMT_ACCEPTANCE_PRODUCT_SHA_PROPERTY: String = "mpmt.acceptance.productJarSha256"

/**
 * 验收接入层源集：验收驱动源集与验收契约测试源集（ADR-0014）。
 *
 * NeoForge 与 Forge 同走 realserver：真实 NeoForge 专用服 + 独立 acceptance mod jar。验收驱动代码不入产品
 * mod jar：单独打 mpmt-acceptance-neoforge mod，仅在验收运行期放入服务端 mods/。编译期继承 main 的类路径
 * （含 arch-loom 提供的 patched MC + NeoForge API）+ main 产物，并叠加 acceptance 核心 + protocol。
 *
 * 须在插件 apply 期建好：车道脚本随后的 `dependencies {}` 与验收接线要按名取到这些源集与配置。
 */
internal fun registerNeoForgeAcceptanceSourceSets(project: Project) {
    project.pluginManager.withPlugin("java") {
        val sourceSets = project.extensions.getByType(SourceSetContainer::class.java)
        val main = sourceSets.getByName("main")
        val acceptance = sourceSets.create(NEOFORGE_ACCEPTANCE_SOURCE_SET)
        acceptance.compileClasspath += main.compileClasspath + main.output
        acceptance.runtimeClasspath += main.runtimeClasspath + main.output
        val configurations = project.configurations
        configurations.getByName(acceptance.implementationConfigurationName)
            .extendsFrom(configurations.getByName("implementation"))

        val acceptanceTest = sourceSets.create(NEOFORGE_ACCEPTANCE_TEST_SOURCE_SET)
        acceptanceTest.compileClasspath += acceptance.output + main.output
        acceptanceTest.runtimeClasspath += acceptanceTest.output + acceptanceTest.compileClasspath
        configurations.getByName(acceptanceTest.implementationConfigurationName)
            .extendsFrom(configurations.getByName("testImplementation"))
        configurations.getByName(acceptanceTest.runtimeOnlyConfigurationName)
            .extendsFrom(configurations.getByName("testRuntimeOnly"))
    }
}

/**
 * 验收接入层接线：验收驱动 mod jar、契约测试、模拟服默认轨与权威报告门禁，并把验收源集纳入
 * build / check（打包由验收编排按需触发）。
 */
internal fun registerNeoForgeAcceptancePlumbing(project: Project, lane: NeoForgeLaneExtension) {
    val acceptance = neoforgeAcceptanceSourceSet(project)
    expandNeoForgeAcceptanceModMetadata(project)
    registerNeoForgeAcceptanceJar(project, lane, acceptance)
    val contractTest =
        registerNeoForgeAcceptanceContractTest(project, acceptance, neoforgeAcceptanceTestSourceSet(project))
    registerNeoForgeSimulationAcceptance(project, lane, acceptance)
    registerNeoForgeAcceptanceReportGate(project, lane, acceptance)
    // 把验收源集与契约测试纳入常规 build/check，验收驱动仍不进入产品 jar。
    project.tasks.named("build").configure {
        dependsOn(project.tasks.named(acceptance.classesTaskName), contractTest)
    }
    project.tasks.named("check").configure { dependsOn(contractTest) }
}

/** 验收驱动 mod jar：仅 shade acceptance 核心（第一方、无第三方运行期依赖，无需 relocate）。NeoForge 运行期 Mojmap、无 reobf。 */
private fun registerNeoForgeAcceptanceJar(project: Project, lane: NeoForgeLaneExtension, acceptance: SourceSet) {
    // shadowJar 类型不在插件工程编译类路径内，按全名反射取；注册后向下转型到 Jar 配置通用打包属性。
    val shadowType =
        externalTaskType(project.extensions.getByName(NEOFORGE_SHADOW_EXTENSION), NEOFORGE_SHADOW_JAR_CLASS)
    project.tasks.register(NEOFORGE_ACCEPTANCE_JAR_TASK, shadowType) {
        val archive = this as Jar
        archive.group = "build"
        archive.description = "构建 realserver 验收驱动 mod ${lane.acceptanceJarName.get()}（仅验收运行期用，不入产品 jar）"
        archive.archiveBaseName.set(lane.acceptanceJarName.get())
        archive.archiveClassifier.set("")
        archive.from(acceptance.output)
        archive.dependsOn(project.tasks.named(acceptance.classesTaskName))
        archive.invokeGroovy(
            "setConfigurations",
            listOf(project.configurations.getByName(NEOFORGE_ACCEPTANCE_SHADOW_BUNDLE_CONFIGURATION)),
        )
        archive.exclude(NEOFORGE_MAVEN_METADATA_EXCLUDE)
        // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
        archive.outputs.upToDateWhen { false }
        archive.outputs.cacheIf { false }
    }
}

/** 契约测试：跑 acceptance v2 与完整默认轨场景契约（只编译验收源集，不打包）。 */
private fun registerNeoForgeAcceptanceContractTest(
    project: Project,
    acceptance: SourceSet,
    acceptanceTest: SourceSet,
): TaskProvider<Test> =
    project.tasks.register(NEOFORGE_ACCEPTANCE_CONTRACT_TEST_TASK, Test::class.java) {
        group = "verification"
        description = "运行 NeoForge acceptance v2 与完整默认轨场景契约测试"
        testClassesDirs = acceptanceTest.output.classesDirs
        classpath = acceptanceTest.runtimeClasspath
        useJUnitPlatform()
        dependsOn(project.tasks.named(acceptance.classesTaskName))
    }

/** 模拟服默认轨：headless 跑完整场景套件并生成 acceptance v2 报告。 */
private fun registerNeoForgeSimulationAcceptance(
    project: Project,
    lane: NeoForgeLaneExtension,
    acceptance: SourceSet,
) {
    project.tasks.register(NEOFORGE_SIM_ACCEPTANCE_TASK, JavaExec::class.java) {
        group = "verification"
        description = "运行 NeoForge 1.20.2 完整默认轨模拟服套件并生成 acceptance v2 报告"
        classpath = acceptance.runtimeClasspath
        mainClass.set(lane.acceptanceMainClass.get())
        dependsOn(project.tasks.named(acceptance.classesTaskName), project.tasks.named(NEOFORGE_REMAP_JAR_TASK))
        systemProperty(MPMT_ACCEPTANCE_REPORT_PROPERTY, lane.simulatorReport.get().asFile.absolutePath)
        systemProperty(MPMT_ACCEPTANCE_VERSION_PROPERTY, project.version.toString())
        systemProperty(MPMT_ACCEPTANCE_PLATFORM_PROPERTY, NEOFORGE_PLATFORM_ID)
        systemProperty(MPMT_ACCEPTANCE_MC_VERSION_PROPERTY, lane.mcVersion.get())
        systemProperty(MPMT_ACCEPTANCE_SERVER_VERSION_PROPERTY, lane.neoForgeVersion.get())
        doFirst {
            systemProperty(MPMT_ACCEPTANCE_PRODUCT_SHA_PROPERTY, Hashes.sha256(neoforgeProductJarFile(project)))
            systemProperty(MPMT_ACCEPTANCE_COMMIT_PROPERTY, gitHeadCommit(project))
        }
    }
}

/**
 * realserver 真服门禁：校验权威报告（须先起 NeoForge 专用服 + NeoForge acceptance 客户端 gametest）。
 *
 * 判定逻辑与文案与迁移前车道脚本逐字一致：报告路径由车道参数给出（`-Pmpmt.acceptance.report` 覆盖优先），
 * 缺失即失败，其余校验交给驱动 `verify` 子命令。
 */
private fun registerNeoForgeAcceptanceReportGate(
    project: Project,
    lane: NeoForgeLaneExtension,
    acceptance: SourceSet,
) {
    val realAcceptanceReport =
        project.provider { project.acceptanceReportFrom(listOf(lane.acceptanceReport.get())) }
    project.tasks.register(NEOFORGE_VERIFY_ACCEPTANCE_TASK, JavaExec::class.java) {
        group = "verification"
        description = "严格校验 NeoForge acceptance v2 报告，缺元数据、场景或 PASS 均失败"
        classpath = acceptance.runtimeClasspath
        mainClass.set(lane.acceptanceMainClass.get())
        dependsOn(project.tasks.named(acceptance.classesTaskName))
        doFirst {
            val report = realAcceptanceReport.get()
            if (!report.isFile) {
                throw GradleException("未找到 NeoForge 验收报告：${report.absolutePath}")
            }
            args("verify", report.absolutePath)
        }
    }
    // B 车道：NeoForge 专用服 + 自有 acceptance 客户端伴侣进服后读报告。
    project.tasks.register(NEOFORGE_REALSERVER_GATE_TASK) {
        group = "verification"
        description = "NeoForge realserver 门禁：校验权威报告（须先专用服 + NeoForge acceptance 客户端 gametest）"
        dependsOn(NEOFORGE_VERIFY_ACCEPTANCE_TASK)
    }
}

/** 验收驱动源集（由 [registerNeoForgeAcceptanceSourceSets] 在 apply 期创建）。 */
internal fun neoforgeAcceptanceSourceSet(project: Project): SourceSet =
    project.extensions.getByType(SourceSetContainer::class.java).getByName(NEOFORGE_ACCEPTANCE_SOURCE_SET)

/** 验收契约测试源集。 */
internal fun neoforgeAcceptanceTestSourceSet(project: Project): SourceSet =
    project.extensions.getByType(SourceSetContainer::class.java).getByName(NEOFORGE_ACCEPTANCE_TEST_SOURCE_SET)
