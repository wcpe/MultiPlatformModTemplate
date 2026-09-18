package buildconventions

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * forge 车道配置：车道脚本只声明"本车道是什么"，重复的 loader 接入流程由 `build-conventions.forge` 承担。
 *
 * 默认值取自四条车道的公共形状（验收 jar 命名与剔除清单、报告候选路径、契约测试接线、打包剔除清单）；
 * 车道只在偏离约定时才写对应属性。
 */
abstract class ForgeLaneExtension {
    /** MC 版本：`mpmt.test.*` 元数据、验收元数据、产品入口类名与失败文案都用它。 */
    abstract val mcVersion: Property<String>

    /** Forge 版本（1.20.1 起形如 `<mc>-<forge>`）：契约测试属性用。 */
    abstract val forgeVersion: Property<String>

    /** loom 版本：契约测试的 `mpmt.test.loomVersion` 用。 */
    abstract val loomVersion: Property<String>

    /** 车道目标 Java 版本：单测 / 契约测试 launcher、run 任务执行 JVM 与平台字节码断言都由它派生。 */
    abstract val targetJavaVersion: Property<Int>

    /** mod 元数据资源名：现代车道 `META-INF/mods.toml`，1.12.2 为 `mcmod.info`。 */
    abstract val modMetadataResource: Property<String>

    /** 是否对资源处理去重（1.12.2 的 common/src/main/resources 与 src/main/resources 存在同名条目）。 */
    abstract val deduplicateProcessedResources: Property<Boolean>

    /** 产品/验收 jar 剔除的归档噪声；现代车道追加 `module-info.class`。 */
    abstract val archiveExcludes: ListProperty<String>

    /** 车道文案标签（如 `Forge 26.2`）：门禁日志与失败文案用。 */
    abstract val laneLabel: Property<String>

    /** 产品 jar 的生产任务名：remap 车道为 `remapJar`，26.2（无混淆）为 `jar`。 */
    abstract val productTaskName: Property<String>

    /** 产品 mod 入口类（相对 jar 根的路径）：打包校验与 dev SecureJar 完整性检查都用它。 */
    abstract val productModClass: Property<String>

    /** 验收 mod 入口类（相对 jar 根的路径）。 */
    abstract val acceptanceModClass: Property<String>

    /** 验收 jar 名（发布与门禁契约不可变）。 */
    abstract val acceptanceJarName: Property<String>

    /** 验收 jar 的 manifest Implementation-Title。 */
    abstract val acceptanceJarTitle: Property<String>

    /** 验收 jar 相对产品额外剔除的条目（含产品入口类）。 */
    abstract val acceptanceExcludes: ListProperty<String>

    /** 验收 jar 是否带版本号并落 `build/devlibs`（1.12.2 的 FG 时代布局）。 */
    abstract val acceptanceJarVersionedDevLibs: Property<Boolean>

    /** 是否启用 dev SecureJar 嵌入链与 MOD_CLASSES 相关接线（1.21.1 / 26.2）。 */
    abstract val devModOutputs: Property<Boolean>

    /** FG 时代 reobf 兼容 Copy 任务：任务名 → 源任务名（根门禁 / 文档既有产物路径不可断）。 */
    abstract val reobfCopyTasks: MapProperty<String, String>

    /** 验收报告候选路径（按序回溯；均不存在时取最后一项作失败文案指向）。 */
    abstract val acceptanceReportCandidates: ListProperty<String>

    /** 报告缺失时提示的补跑步骤。 */
    abstract val acceptanceReportHint: Property<String>

    /** 报告门描述里点名的客户端伴侣 run 任务（1.21.1 为 `runAcceptanceClient`，26.2 为 `runClient`）。 */
    abstract val acceptanceGateClientHint: Property<String>

    /** 是否注册验收报告门（`verifyAcceptanceReport` + `runRealServerAcceptance` + 流程提示任务）。 */
    abstract val acceptanceReportGate: Property<Boolean>

    /** 是否注册现代车道的打包校验与 `packageArtifacts`（1.21.1 / 26.2）。 */
    abstract val modernPackagingVerification: Property<Boolean>

    /** `packageArtifacts` 的额外依赖（26.2 的静态质量门清单）。 */
    abstract val packageArtifactsDependsOn: ListProperty<String>

    /** 单测是否注入 `mpmt.test.*` 版本元数据（现代车道；1.12.2 / 1.20.1 原样不注入）。 */
    abstract val unitTestMetadata: Property<Boolean>

    /** 契约测试主类；留空表示本车道不注册 JavaExec 契约测试。 */
    abstract val contractTestMainClass: Property<String>

    /** 契约测试任务描述。 */
    abstract val contractTestDescription: Property<String>

    /** 契约测试附加依赖任务（`contractTestClasses` 与产品 jar 任务由插件补齐）。 */
    abstract val contractTestDependsOn: ListProperty<String>

    /** 契约测试取产品 jar / 验收 jar 的任务名。 */
    abstract val contractTestProductTask: Property<String>
    abstract val contractTestAcceptanceTask: Property<String>

    /** 契约测试的补充 `mpmt.test.*` 版本属性（仓库根、产品与验收 jar 路径由插件补齐）。 */
    abstract val contractTestProperties: MapProperty<String, String>

    /**
     * 验收伴侣 remap 任务的产物归档名（如 `mpmt-acceptance-forge`）；留空表示本车道无此任务。
     *
     * 1.12.2（FG 时代 `mpmt-forge-acceptance-1.12.2`，恒带版本并拼 acceptance 源集运行期 classpath）
     * 与 1.20.1（shadow 链路 `mpmt-acceptance-forge`，不带版本不拼 classpath）
     * 保留 remap（named → SRG）以产出生产命名验收 jar；1.21.1 / 26.2 走无 remap 链路，不设置。
     */
    abstract val remapAcceptanceJarName: Property<String>

    /** remap 验收 jar 是否带项目版本号（仅 FG 时代的 1.12.2）。 */
    abstract val remapAcceptanceJarVersioned: Property<Boolean>
}
