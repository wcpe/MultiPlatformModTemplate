package buildconventions

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/**
 * 平台车道公共配置：车道脚本只声明"是什么"，流程由 `build-conventions.platform` 插件承担。
 *
 * 约定优于配置的部分（源集名、生成目录、类名）都有默认值，车道只在偏离默认时才写。
 */
abstract class PlatformLaneExtension {
    /** MC 版本，用于生成物文案与档案名。 */
    abstract val mcVersion: Property<String>

    /** 验收控制通道名（产品端与验收端必须一致）。 */
    abstract val channelName: Property<String>

    /** 验收控制通道常量：生成类的包名。 */
    abstract val channelPackage: Property<String>

    /** 验收控制通道常量：生成类的类名。 */
    abstract val channelClass: Property<String>

    /** 接收生成源码的源集名（默认 `acceptance`）。 */
    abstract val acceptanceSourceSetName: Property<String>

    /** 生成目录（默认 `build/generated/sources/acceptance/java`）。 */
    abstract val generatedSourcesDir: DirectoryProperty
}
