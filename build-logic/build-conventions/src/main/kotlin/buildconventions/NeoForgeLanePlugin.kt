package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * neoforge 车道公共层：车道脚本 `plugins { id("build-conventions.neoforge") }` 后只保留"参数与差异"。
 *
 * 集中承担加载器特有层：
 * - 编译工具链与单测平台（[configureNeoForgeJavaConventions]）；
 * - 依赖接线（[registerNeoForgeDependencies]）：minecraft / Mojang 映射 / NeoForge userdev 三段式坐标、
 *   shade 内部产物与 relocate 第三方、单测与验收源集依赖；
 * - dev run（[configureNeoForgeRuns]）与 mod 元数据占位展开（[expandNeoForgeModMetadata]）；
 * - 打包链路（[configureNeoForgePackagingChain]）、打包校验与打包出口
 *   （[registerNeoForgePackagingVerification] / [registerNeoForgePackageArtifacts]）；
 * - 验收接入层（[registerNeoForgeAcceptanceSourceSets] / [registerNeoForgeAcceptancePlumbing]）与
 *   realserver 真服门禁（[registerNeoForgeAcceptancePlumbing] 内的 `runRealServerAcceptance`）。
 *
 * 车道的"参数"经 [NeoForgeLaneExtension] 声明；不可变契约仍逐字留在车道脚本与插件里：
 * 任务名与任务路径、发布 jar 名与字节、报告路径、`-Pmpmt.acceptance.*` 属性名、`verifyPackaging` 断言清单与
 * 真服门禁判定文案。
 *
 * loom / shadow 的类型不在本插件工程编译类路径内，相关取值与配置经 [invokeGroovy] / [groovyValue] 动态表达。
 */
class NeoForgeLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create("neoforgeLane", NeoForgeLaneExtension::class.java)
        applyConventions(project, lane)
        // 工具链须在 apply 期接线：loom 的 afterEvaluate 会读它，车道参数只提供取值来源（Provider 惰性解析）。
        configureNeoForgeJavaConventions(project, lane)
        // 验收源集须在 apply 期创建：车道脚本随后的依赖声明与验收接线都要按名取到它。
        registerNeoForgeAcceptanceSourceSets(project)
        // 车道参数块（`neoforgeLane { … }`）晚于插件 apply，读取参数值的接线统一在 afterEvaluate 完成。
        project.afterEvaluate {
            val acceptance = neoforgeAcceptanceSourceSet(project)
            registerNeoForgeDependencies(project, lane, acceptance)
            configureNeoForgeRuns(project, lane)
            expandNeoForgeModMetadata(project)
            configureNeoForgePackagingChain(project)
            registerNeoForgePackagingVerification(project)
            registerNeoForgePackageArtifacts(project)
            registerNeoForgeAcceptancePlumbing(project, lane)
        }
    }

    /** 公共约定：车道不写即为这些值。 */
    private fun applyConventions(project: Project, lane: NeoForgeLaneExtension) {
        lane.acceptanceReport.convention(NEOFORGE_REALSERVER_REPORT_PATH)
        lane.simulatorReport.convention(project.layout.buildDirectory.file(NEOFORGE_SIM_REPORT_PATH))
    }

    /** 编译工具链（车道目标 Java 版本）与单测平台：与其它车道同构。 */
    private fun configureNeoForgeJavaConventions(project: Project, lane: NeoForgeLaneExtension) {
        project.pluginManager.withPlugin("java") {
            project.extensions.getByType(JavaPluginExtension::class.java).toolchain.languageVersion
                .set(lane.targetJavaVersion.map { JavaLanguageVersion.of(it) })
            project.tasks.named("test", Test::class.java).configure { useJUnitPlatform() }
        }
    }
}
