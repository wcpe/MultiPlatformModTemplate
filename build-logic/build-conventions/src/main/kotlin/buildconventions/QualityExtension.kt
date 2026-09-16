package buildconventions

import org.gradle.api.provider.Property

/**
 * 质量工具链的车道级覆盖项：默认值即历史行为（PMD 7.0.0 / Checkstyle 10.17.0 / 分析 JVM 17 /
 * SpotBugs 用插件自带默认版本）。车道只声明"与本车道不同"的那几项，其余交给
 * [QualityConventionPlugin] 统一装配。
 */
abstract class QualityExtension {
    /** Checkstyle 工具版本（默认 10.17.0）。 */
    abstract val checkstyleToolVersion: Property<String>

    /** PMD 工具版本（默认 7.0.0；部分车道因规则集兼容性需要更高版本）。 */
    abstract val pmdToolVersion: Property<String>

    /** SpotBugs 工具版本（默认留空 = 用 SpotBugs 插件自带版本；部分车道需更高版本以解析新 classfile）。 */
    abstract val spotbugsToolVersion: Property<String>

    /** 分析任务（Checkstyle / PMD）启动 JVM 的版本（默认 17；低版本字节码车道可调到与本车道一致）。 */
    abstract val analysisJavaVersion: Property<Int>
}
