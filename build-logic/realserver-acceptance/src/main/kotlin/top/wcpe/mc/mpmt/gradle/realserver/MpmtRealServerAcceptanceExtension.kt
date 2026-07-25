package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * 真服验收编排扩展。
 *
 * <p>B 完整：全服务端 lane 由 [PlatformLaneCatalog] 定义；各平台自有 gametest 客户端进服。
 * B 增强：可选 [autoStartPaperHost] + jar 路径，由 [PaperHostService] 在客户端任务 doFirst 起 Paper。
 */
abstract class MpmtRealServerAcceptanceExtension {
    /** 服务端权威验收报告文件。 */
    abstract val reportFile: RegularFileProperty

    /**
     * 本平台 gametest/acceptance **客户端** run 任务名
     * （如 runAcceptanceClient；由各 loader 自写伴侣进服）。
     */
    abstract val clientTaskName: Property<String>

    /**
     * 本平台 gametest/acceptance **服务端** run 任务名
     * （如 runAcceptanceServer；mod 专用服用；Paper 宿主用 BuildService 时可不设）。
     */
    abstract val serverTaskName: Property<String>

    /** 验收矩阵 id（R1–R6 或空=P1 默认轨）。 */
    abstract val matrix: Property<String>

    /**
     * 本构建对应的 [PlatformLane.id]（Fabric/Forge/…），
     * 用于文档与日志；可选。
     */
    abstract val laneId: Property<String>

    /**
     * 额外要在本平台门禁前依赖的任务路径（同构建内任务名）。
     */
    abstract val extraDependsOn: ListProperty<String>

    // ── B 增强：Paper 宿主 BuildService ──

    /**
     * 是否在客户端任务 doFirst 经 [PaperHostService] 自动起 Paper。
     * 默认 false：仅读报告门禁（兼容既有「外部/脚本已起服」流程）。
     */
    abstract val autoStartPaperHost: Property<Boolean>

    /** 产品插件 jar（autoStart 时必填）。 */
    abstract val pluginJar: RegularFileProperty

    /** 验收驱动插件 jar（autoStart 时必填）。 */
    abstract val acceptanceDriverJar: RegularFileProperty

    /** Paper MC 版本，默认 1.20.1。 */
    abstract val paperVersion: Property<String>

    /** Paper 端口，默认 25599。 */
    abstract val paperPort: Property<Int>

    /** 就绪超时分钟，默认 8。 */
    abstract val readyTimeoutMinutes: Property<Int>

    /** 全局硬超时分钟，默认 24。 */
    abstract val globalTimeoutMinutes: Property<Int>

    /** 场景白名单（逗号分隔），透传 -Dmpmt.acceptance.only。 */
    abstract val acceptanceOnly: Property<String>
}
