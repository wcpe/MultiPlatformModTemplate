package buildconventions

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer

/** loom 为 forge 系预建的 run 名（loom 已套用 `client()` / `server()` 模板，这里只做等价移植）。 */
internal const val NEOFORGE_CLIENT_RUN_NAME: String = "client"
internal const val NEOFORGE_SERVER_RUN_NAME: String = "server"

/** run 展示名。 */
private const val NEOFORGE_CLIENT_CONFIG_NAME: String = "NeoForge Client"
private const val NEOFORGE_SERVER_CONFIG_NAME: String = "NeoForge Server"

/** run 目录（服客分离）。 */
private const val NEOFORGE_CLIENT_RUN_DIR: String = "run-client"
private const val NEOFORGE_SERVER_RUN_DIR: String = "run-server"

/** 真服权威报告落点（相对工程目录）：dev run 与真服门共用同一路径契约。 */
internal const val NEOFORGE_REALSERVER_REPORT_PATH: String = "run-server/acceptance-report.txt"

/** NeoForge 沿用 forge 的日志属性名与级别。 */
private const val NEOFORGE_CONSOLE_LEVEL_PROPERTY: String = "forge.logging.console.level"
private const val NEOFORGE_CONSOLE_LEVEL: String = "info"

/** 验收看门狗绝对截止：须覆盖客户端冷启动 + 首场景 awaitClientReady（常 >3min）。 */
private const val NEOFORGE_ACCEPTANCE_DEADLINE_MS: String = "660000"

/** `-Pmpmt.acceptance.server` 未指定时验收客户端连的默认地址。 */
private const val NEOFORGE_DEFAULT_ACCEPTANCE_SERVER: String = "127.0.0.1"

/** 平台标识（`mpmt.acceptance.platform`）。 */
internal const val NEOFORGE_PLATFORM_ID: String = "neoforge"

/** dev run / 验收驱动注入的 `mpmt.acceptance.*` 属性名（对外契约，不可变）。 */
internal const val MPMT_ACCEPTANCE_PROPERTY: String = "mpmt.acceptance"
internal const val MPMT_ACCEPTANCE_REPORT_PROPERTY: String = "mpmt.acceptance.report"
internal const val MPMT_ACCEPTANCE_SERVER_PROPERTY: String = "mpmt.acceptance.server"
internal const val MPMT_ACCEPTANCE_DEADLINE_PROPERTY: String = "mpmt.acceptance.deadlineMs"
internal const val MPMT_ACCEPTANCE_COMMIT_PROPERTY: String = "mpmt.acceptance.commit"
internal const val MPMT_ACCEPTANCE_VERSION_PROPERTY: String = "mpmt.acceptance.version"
internal const val MPMT_ACCEPTANCE_PLATFORM_PROPERTY: String = "mpmt.acceptance.platform"
internal const val MPMT_ACCEPTANCE_MC_VERSION_PROPERTY: String = "mpmt.acceptance.mcVersion"
internal const val MPMT_ACCEPTANCE_SERVER_VERSION_PROPERTY: String = "mpmt.acceptance.serverVersion"
internal const val MPMT_ACCEPTANCE_PRODUCT_JAR_PROPERTY: String = "mpmt.acceptance.productJar"

/** 报告标识本轮提交：commit 配置期一次取定（与迁移前车道脚本同一口径）。 */
internal fun gitHeadCommit(project: Project): String =
    project.providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()

/**
 * dev run 配置：loom 已为 forge 系预建默认 client / server 运行配置（`client()` / `server()` 模板已应用），
 * 这里只做等价移植。
 *
 * `source(main)` 等价 NeoGradle modSource：MOD_CLASSES 指向 main 源集输出，提供产品 main 类；验收驱动
 * acceptanceJar 放 `mods/`（自带 mods.toml，由 realserver 编排落位）。
 *
 * 已放弃的通路：core 库的 FML 模块层不向 mod 暴露（NoClassDefFoundError），曾用带 FMLModType:GAMELIBRARY
 * 的 coreLibJar 放进各 run 目录的 `mods/`，让 FML 当 game library 暴露；因 dev↔dev 全验收受 NeoGradle 同项目
 * 并发限制、交付走真服路径，该任务无调用方，已随 ADR-0027 的收敛删除（需要时按同一手法临时补回即可）。
 *
 * loom 类型不在插件工程编译类路径内，run 设定按对象真实方法名动态配置；调用顺序与原先车道内
 * `getByName("client") { … }` 逐项一致。
 */
internal fun configureNeoForgeRuns(project: Project, lane: NeoForgeLaneExtension) {
    val runs = neoforgeLoomRuns(project)
    val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
    configureClientRun(project, runs, main)
    configureServerRun(project, runs, main, lane)
}

private fun configureClientRun(project: Project, runs: NamedDomainObjectContainer<*>, main: SourceSet) {
    val run = neoforgeRunConfig(runs, NEOFORGE_CLIENT_RUN_NAME)
    run.invokeGroovy("setConfigName", NEOFORGE_CLIENT_CONFIG_NAME)
    run.invokeGroovy("source", main)
    run.invokeGroovy("runDir", NEOFORGE_CLIENT_RUN_DIR)
    run.invokeGroovy("property", NEOFORGE_CONSOLE_LEVEL_PROPERTY, NEOFORGE_CONSOLE_LEVEL)
    run.invokeGroovy("property", MPMT_ACCEPTANCE_PROPERTY, "true")
    run.invokeGroovy(
        "property",
        MPMT_ACCEPTANCE_SERVER_PROPERTY,
        (project.findProperty(MPMT_ACCEPTANCE_SERVER_PROPERTY) as String?) ?: NEOFORGE_DEFAULT_ACCEPTANCE_SERVER,
    )
}

/** loom server 模板已自带 nogui 程序参数，无需（不可）重复声明。 */
private fun configureServerRun(
    project: Project,
    runs: NamedDomainObjectContainer<*>,
    main: SourceSet,
    lane: NeoForgeLaneExtension,
) {
    val run = neoforgeRunConfig(runs, NEOFORGE_SERVER_RUN_NAME)
    run.invokeGroovy("setConfigName", NEOFORGE_SERVER_CONFIG_NAME)
    run.invokeGroovy("source", main)
    run.invokeGroovy("runDir", NEOFORGE_SERVER_RUN_DIR)
    run.invokeGroovy("property", NEOFORGE_CONSOLE_LEVEL_PROPERTY, NEOFORGE_CONSOLE_LEVEL)
    run.invokeGroovy("property", MPMT_ACCEPTANCE_PROPERTY, "true")
    val report = project.file(NEOFORGE_REALSERVER_REPORT_PATH).absolutePath
    run.invokeGroovy("property", MPMT_ACCEPTANCE_REPORT_PROPERTY, report)
    run.invokeGroovy("property", MPMT_ACCEPTANCE_DEADLINE_PROPERTY, NEOFORGE_ACCEPTANCE_DEADLINE_MS)
    // v2 元数据：commit 配置期取 git；productJar 供驱动算 SHA（对齐 Forge realserver）
    run.invokeGroovy("property", MPMT_ACCEPTANCE_COMMIT_PROPERTY, gitHeadCommit(project))
    run.invokeGroovy("property", MPMT_ACCEPTANCE_VERSION_PROPERTY, project.version.toString())
    run.invokeGroovy("property", MPMT_ACCEPTANCE_PLATFORM_PROPERTY, NEOFORGE_PLATFORM_ID)
    run.invokeGroovy("property", MPMT_ACCEPTANCE_MC_VERSION_PROPERTY, lane.mcVersion.get())
    run.invokeGroovy("property", MPMT_ACCEPTANCE_SERVER_VERSION_PROPERTY, lane.neoForgeVersion.get())
    run.invokeGroovy("property", MPMT_ACCEPTANCE_PRODUCT_JAR_PROPERTY, neoforgeProductJarFile(project).absolutePath)
}
