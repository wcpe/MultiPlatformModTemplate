package buildconventions

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.kotlin.dsl.withGroovyBuilder

/** loom run 名（loom 会以 `run<首字母大写>` 注册对应的 JavaExec 任务）。 */
internal const val SIMULATOR_RUN_NAME = "simNetworkTest"
internal const val ACCEPTANCE_SERVER_RUN_NAME = "acceptanceServer"
internal const val ACCEPTANCE_CLIENT_RUN_NAME = "acceptanceClient"

/** 验收客户端独立运行目录：避免与服务端 `run/` 并发读写冲突。 */
private const val ACCEPTANCE_CLIENT_RUN_DIR = "run-client"

/** 验收看门狗绝对截止：须覆盖客户端冷启动 + 首场景 awaitClientReady（常 >3min）。 */
private const val ACCEPTANCE_DEADLINE_MS = "660000"

/** loom 由 run 名生成的 JavaExec 任务名（`acceptanceServer` → `runAcceptanceServer`）。 */
internal fun loomRunTaskName(runName: String): String = "run" + runName.replaceFirstChar { it.uppercase() }

/**
 * 声明三条 loom run：模拟服 GameTest、验收服务端、验收客户端（quickPlay 自连）。
 *
 * 插件工程不编译依赖 loom（loom 由消费车道自带），故 run 设定按 Groovy 方法名动态配置：
 * 调用顺序与原先车道内 `runs { create(…) { … } }` 逐项一致（server/client、configName、source、
 * runDir、property、programArgs）。
 */
internal fun registerLoomRuns(project: Project, lane: FabricLaneExtension, gametest: SourceSet) {
    val runs = loomRuns(project)
    configureSimulatorRun(runs, lane, gametest)
    configureAcceptanceServerRun(project, runs, lane, gametest)
    configureAcceptanceClientRun(runs, lane, gametest)
}

/**
 * 接线 run 任务的验收元数据注入（提交 / 版本 / 产品 jar SHA / 矩阵五类制品 / 报告路径）。
 *
 * `runAcceptanceServer` 对 `gametestClasses` 的显式依赖按车道声明保留（1.20.1 原样不加）。
 */
internal fun configureAcceptanceRunTasks(project: Project, lane: FabricLaneExtension) {
    val productTaskName = lane.productTaskName.get()
    project.tasks.named(loomRunTaskName(SIMULATOR_RUN_NAME), JavaExec::class.java).configure {
        dependsOn(project.tasks.named(productTaskName))
        doFirst { injectSimulatorMetadata(project, lane) }
    }
    project.tasks.named(loomRunTaskName(ACCEPTANCE_SERVER_RUN_NAME), JavaExec::class.java).configure {
        dependsOn(project.tasks.named(productTaskName))
        if (lane.acceptanceServerCompilesGametest.get()) {
            dependsOn("${GAMETEST_SOURCE_SET}Classes")
        }
        doFirst { injectAcceptanceServerMetadata(lane) }
    }
    project.tasks.named(loomRunTaskName(ACCEPTANCE_CLIENT_RUN_NAME), JavaExec::class.java).configure {
        if (hasMatrixRuns(lane)) {
            doFirst { injectAcceptanceClientMetadata(lane) }
        }
    }
}

/** 车道是否声明了矩阵轨（未声明则 run 任务不注入矩阵元数据，也不多挂空动作）。 */
private fun hasMatrixRuns(lane: FabricLaneExtension): Boolean = !lane.matrixJavaHomeEnvironment.orNull.isNullOrEmpty()

/** loom 的 `runs` 容器（`loom.runs`）。 */
private fun loomRuns(project: Project): NamedDomainObjectContainer<*> {
    val runs = project.extensions.getByName("loom").withGroovyBuilder { getProperty("runs") }
    return runs as? NamedDomainObjectContainer<*> ?: error("loom 扩展未提供 runs 容器")
}

/** 模拟服 GameTest 套件：headless 起服跑 in-process 回环网络 GameTest，无外部客户端、可自动跑。 */
private fun configureSimulatorRun(
    runs: NamedDomainObjectContainer<*>,
    lane: FabricLaneExtension,
    gametest: SourceSet,
) {
    createRun(runs, SIMULATOR_RUN_NAME).withGroovyBuilder {
        "server"()
        "configName"("Sim Network GameTest")
        "source"(gametest)
        "property"("mpmt.simtest", "true")
        "property"("mpmt.simtest.report", lane.simulatorReport.get().asFile.absolutePath)
    }
}

/**
 * 验收服务端：headless 可自动跑；报告路径默认 `build/acceptance/server-report.txt`。
 * 26.2 的单进程编排会用 `-Pmpmt.acceptance.report` 覆盖报告，故该车道的 run 设定同步采用覆盖值。
 */
private fun configureAcceptanceServerRun(
    project: Project,
    runs: NamedDomainObjectContainer<*>,
    lane: FabricLaneExtension,
    gametest: SourceSet,
) {
    val report = acceptanceServerReport(project, lane)
    createRun(runs, ACCEPTANCE_SERVER_RUN_NAME).withGroovyBuilder {
        "server"()
        "configName"("Acceptance Server")
        "source"(gametest)
        "property"("mpmt.acceptance", "true")
        "property"("mpmt.acceptance.report", report)
        "property"("mpmt.acceptance.deadlineMs", ACCEPTANCE_DEADLINE_MS)
    }
}

/** 验收客户端：需显示，由用户本机经 quickPlay 自连；默认对齐 `run/server.properties` 的 server-port=25571。 */
private fun configureAcceptanceClientRun(
    runs: NamedDomainObjectContainer<*>,
    lane: FabricLaneExtension,
    gametest: SourceSet,
) {
    val address = lane.acceptanceServerAddress.get()
    createRun(runs, ACCEPTANCE_CLIENT_RUN_NAME).withGroovyBuilder {
        "client"()
        "configName"("Acceptance Client")
        "source"(gametest)
        "runDir"(ACCEPTANCE_CLIENT_RUN_DIR)
        "programArgs"("--quickPlayMultiplayer", address)
        if (lane.acceptanceClientExposesServerProperty.get()) {
            // 伴侣自连读系统属性；与 programArgs 同源，避免只改 -P 时伴侣仍连默认口
            "property"("mpmt.acceptance.server", address)
        }
    }
}

/** acceptanceServer 的默认报告路径：按车道声明决定是否采用 `-Pmpmt.acceptance.report` 覆盖值。 */
private fun acceptanceServerReport(project: Project, lane: FabricLaneExtension): String {
    val configured = lane.acceptanceReport.get().asFile.absolutePath
    if (!lane.acceptanceServerUsesOverriddenReport.get()) {
        return configured
    }
    val override = (project.findProperty(AcceptanceRound.REPORT_PROPERTY) as String?)?.trim().orEmpty()
    return override.ifEmpty { configured }
}

private fun createRun(runs: NamedDomainObjectContainer<*>, name: String): Any =
    runs.create(name) ?: error("loom run 未创建：$name")
