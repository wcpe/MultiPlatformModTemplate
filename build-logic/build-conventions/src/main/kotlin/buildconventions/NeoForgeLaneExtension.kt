package buildconventions

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * neoforge 车道配置：车道脚本只声明"本车道是什么"，重复的 loader 接入流程由 `build-conventions.neoforge` 承担。
 *
 * 参数口径与原车道脚本内联实现逐项对应（minecraft / userdev 坐标、relocate 目标、dev run 验收取值、
 * 报告路径都不变）；默认值取自本车道的公共约定，车道只在偏离约定时才写对应属性。
 */
abstract class NeoForgeLaneExtension {
    /** MC 版本：minecraft 坐标与 dev run 的 `mpmt.acceptance.mcVersion` 同源（锚点 1.20.2）。 */
    abstract val mcVersion: Property<String>

    /** NeoForge 版本：userdev 单坐标与 dev run 的 `mpmt.acceptance.serverVersion` 同源。 */
    abstract val neoForgeVersion: Property<String>

    /** 需 shade 进产物并 relocate 的第三方运行期依赖版本（ADR-0012）。 */
    abstract val snakeyamlVersion: Property<String>

    /** 车道目标 Java 版本：编译工具链用它（NeoForge 1.20.2 运行于 Java 17）。 */
    abstract val targetJavaVersion: Property<Int>

    /** 验收驱动 mod jar 基名（仅验收运行期用，不入产品 jar）。 */
    abstract val acceptanceJarName: Property<String>

    /** 验收驱动主类：模拟服 JavaExec 与权威报告校验共用。 */
    abstract val acceptanceMainClass: Property<String>

    /** 真服验收报告路径（相对工程目录；`-Pmpmt.acceptance.report` 覆盖优先）。 */
    abstract val acceptanceReport: Property<String>

    /** 模拟服默认轨报告文件（默认 `build/acceptance/sim-report-v2.txt`）。 */
    abstract val simulatorReport: RegularFileProperty
}
