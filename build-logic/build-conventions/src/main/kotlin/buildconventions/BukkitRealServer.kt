package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.withGroovyBuilder

/** realserver 验收扩展名（由 `top.wcpe.mc.mpmt.realserver-acceptance` 插件提供）。 */
private const val BUKKIT_REAL_SERVER_EXTENSION = "mpmtRealServerAcceptance"

/** 本平台自有验收客户端 run 任务名（各平台伴侣自行进服）。 */
private const val BUKKIT_ACCEPTANCE_CLIENT_TASK = "runAcceptanceClient"

/** 托管 Paper 宿主门户：BuildService 名前缀，实际注册名带「_<工程路径>」后缀（见 [bukkitEnsurePaperHost]）。 */
private const val BUKKIT_PAPER_HOST_SERVICE = "mpmtPaperHostService"

/** 托管 Paper 宿主自检任务名（仅声明了 `managedPaperHost` 的车道注册）。 */
internal const val BUKKIT_ENSURE_PAPER_HOST_TASK = "ensurePaperRealServerHost"

/** realserver 车道标识：四条 bukkit 车道统一。 */
private const val BUKKIT_LANE_ID = "Bukkit"

/** 默认验收报告路径；1.20.1（SCHEDULER 旁路）与 26.2（矩阵命名）在车道脚本内覆盖。 */
private const val BUKKIT_DEFAULT_REPORT_PATH = "acceptance/server-report.txt"

/** 默认 Paper 端口与 `-P` 覆盖属性名。 */
private const val BUKKIT_DEFAULT_PAPER_PORT = 25599
private const val BUKKIT_PAPER_PORT_PROPERTY = "mpmt.realserver.port"
private const val BUKKIT_AUTO_HOST_PROPERTY = "mpmt.realserver.autoHost"

/** 验收矩阵属性名（26.2 的 runId 回退逻辑保留在车道脚本）。 */
private const val BUKKIT_MATRIX_PROPERTY = "mpmt.acceptance.matrix"

/**
 * realserver 门禁的公共接线：车道标识、宿主起服开关、端口、产品/验收 jar 与额外依赖。
 *
 * 报告路径与矩阵用 `convention` 声明默认值，车道脚本的 `mpmtRealServerAcceptance { }` 块可覆盖
 * （1.20.1 的 SCHEDULER 旁路与 26.2 的矩阵命名因此保持原样）。
 */
internal fun configureBukkitRealServerAcceptance(project: Project, lane: BukkitLaneExtension) {
    val extension = project.extensions.getByName(BUKKIT_REAL_SERVER_EXTENSION)
    extension.stringProperty("laneId").set(BUKKIT_LANE_ID)
    extension.booleanProperty("autoStartPaperHost").set(bukkitAutoStartPaperHost(project))
    extension.stringProperty("paperVersion").set(lane.mcVersion)
    extension.intProperty("paperPort").set(bukkitPaperPort(project))
    extension.stringProperty("clientTaskName").set(BUKKIT_ACCEPTANCE_CLIENT_TASK)
    extension.listProperty("extraDependsOn").set(listOf(BUKKIT_PRODUCT_JAR_TASK, BUKKIT_ACCEPTANCE_JAR_TASK))
    extension.fileProperty("pluginJar").set(bukkitProductJarFile(project))
    extension.fileProperty("acceptanceDriverJar").set(bukkitAcceptanceJarFile(project))
    extension.fileProperty("reportFile")
        .convention(project.layout.buildDirectory.file(BUKKIT_DEFAULT_REPORT_PATH))
    extension.stringProperty("matrix").convention(project.providers.gradleProperty(BUKKIT_MATRIX_PROPERTY).orElse(""))
}

/**
 * 托管 Paper 宿主自检任务（仅声明了 `managedPaperHost` 的车道注册）。
 *
 * 与原 1.20.1 车道内联实现逐字一致：门禁文案、未注册 BuildService 的报错、
 * `PaperHostService.ensureStarted()` 调用与端口回显都保持不变
 * （服务实例按名动态调用，插件工程不编译依赖它）。
 */
internal fun registerBukkitPaperHostEnsureTask(project: Project) {
    val autoHost = bukkitAutoStartPaperHost(project)
    project.tasks.register(BUKKIT_ENSURE_PAPER_HOST_TASK) {
        group = "verification"
        description = "PaperHostService.ensureStarted（须 -P$BUKKIT_AUTO_HOST_PROPERTY=true）"
        dependsOn(project.tasks.named(BUKKIT_PRODUCT_JAR_TASK), project.tasks.named(BUKKIT_ACCEPTANCE_JAR_TASK))
        outputs.upToDateWhen { false }
        doLast {
            if (!autoHost.get()) {
                throw GradleException("请加 -P$BUKKIT_AUTO_HOST_PROPERTY=true 启用 BuildService 起宿主")
            }
            // 宿主服务由 realserver-acceptance 插件按「mpmtPaperHostService_<工程路径>」注册（避免多车道同名冲突），
            // 故此处按本工程路径拼出真实注册名查找；旧的裸名查找永远匹配不到（迁移前即有的缺陷）。
            val serviceName = BUKKIT_PAPER_HOST_SERVICE + project.path.replace(':', '_')
            val registration =
                project.gradle.sharedServices.registrations.findByName(serviceName)
                    ?: throw GradleException("未注册 $serviceName（本车道未声明 managedPaperHost？）")
            registration.service.get().withGroovyBuilder { "ensureStarted"() }
            val port =
                project.providers.gradleProperty(BUKKIT_PAPER_PORT_PROPERTY)
                    .orElse(BUKKIT_DEFAULT_PAPER_PORT.toString()).get()
            project.logger.lifecycle("[realserver] Paper 已就绪 port=$port；请另终端跑 Fabric 验收客户端连入")
        }
    }
}

/** `-Pmpmt.realserver.autoHost=true` 开关（默认 false：仅读报告门禁）。 */
private fun bukkitAutoStartPaperHost(project: Project): Provider<Boolean> =
    project.providers.gradleProperty(BUKKIT_AUTO_HOST_PROPERTY).map { it == "true" }.orElse(false)

/** Paper 端口：`-Pmpmt.realserver.port` 优先，缺省 25599（与原车道表达式一致）。 */
private fun bukkitPaperPort(project: Project): Provider<Int> =
    project.providers.gradleProperty(BUKKIT_PAPER_PORT_PROPERTY).map { it.toInt() }.orElse(BUKKIT_DEFAULT_PAPER_PORT)

/** 产品 jar：最终 ShadowJar 产物（realserver 托管宿主加载用）。 */
private fun bukkitProductJarFile(project: Project): Provider<RegularFile> =
    project.tasks.named(BUKKIT_PRODUCT_JAR_TASK, Jar::class.java).flatMap { it.archiveFile }

/** 验收驱动 jar：托管宿主加载的验收插件产物。 */
private fun bukkitAcceptanceJarFile(project: Project): Provider<RegularFile> =
    project.tasks.named(BUKKIT_ACCEPTANCE_JAR_TASK, Jar::class.java).flatMap { it.archiveFile }
