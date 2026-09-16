package buildconventions

import org.gradle.api.provider.Property

/**
 * sponge 车道配置：车道脚本只声明"本车道是什么"，重复的 loader 接入流程由 `build-conventions.sponge` 承担。
 *
 * 默认值取自 SpongeGradle 与本车道的公共约定（loader `java_plain` / `1.0`、许可 MIT、
 * 验收驱动插件 jar 名、验收报告在 `run/` 下），车道只在偏离约定时才写对应属性。
 */
abstract class SpongeLaneExtension {
    /** Sponge 插件元数据声明的 API 版本（须与 RC1365 清单一致）。 */
    abstract val spongeApiMetadataVersion: Property<String>

    /** 编译类路径固定的同源 API 制品版本（时间戳版本只用于固定解析，不写进元数据）。 */
    abstract val spongeApiCompileVersion: Property<String>

    /** 车道目标 Java 版本：编译工具链用它。 */
    abstract val targetJavaVersion: Property<Int>

    /** 插件元数据 id。 */
    abstract val pluginId: Property<String>

    /** 插件元数据展示名。 */
    abstract val displayName: Property<String>

    /** 插件元数据主类（entrypoint）。 */
    abstract val entrypoint: Property<String>

    /** 插件元数据描述。 */
    abstract val description: Property<String>

    /** 插件元数据许可证（默认 `MIT`）。 */
    abstract val license: Property<String>

    /** 元数据 loader 名（对应 SpongeGradle `PluginLoaders.JAVA_PLAIN`）。 */
    abstract val loaderName: Property<String>

    /** 元数据 loader 版本（默认 `1.0`）。 */
    abstract val loaderVersion: Property<String>

    /** 验收驱动插件 jar 的基名（默认 `mpmt-acceptance`）。 */
    abstract val acceptanceJarName: Property<String>

    /** 真服验收报告路径（相对工程目录；`-Pmpmt.acceptance.report` 覆盖优先）。 */
    abstract val acceptanceReport: Property<String>
}
