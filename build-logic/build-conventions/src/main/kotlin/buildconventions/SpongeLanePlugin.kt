package buildconventions

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * sponge 车道公共层：车道脚本 `plugins { id("build-conventions.sponge") }` 后只保留"参数与差异"。
 *
 * 集中承担加载器特有层：
 * - SpongeGradle 元数据装配（[configureSpongeMetadata]）：`sponge {}` 取值由车道参数派生，
 *   生成目录并入 `processResources`、显式接线 `writePluginMetadata`，并固定 spongeapi 编译制品版本；
 * - 打包链路与自包含校验（[configureSpongePackagingChain] / [registerSpongePackagingVerification]）；
 * - 验收接入层（[registerSpongeAcceptancePlumbing]）：验收源集、契约测试源集、验收驱动插件 jar、build/check 接线；
 * - realserver 真服门禁（[registerSpongeRealserverGate]）与编译工具链 / 单测（[configureToolchainAndTest]）。
 *
 * 车道的"参数"经 [SpongeLaneExtension] 声明；SpongeGradle 与 shadow 的类型不在本插件工程编译类路径内，
 * 相关接线经 [invokeGroovy] 等动态互操作等价表达。
 */
class SpongeLanePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val lane = project.extensions.create("spongeLane", SpongeLaneExtension::class.java)
        applyConventions(lane)
        configureTestDependencies(project)
        registerSpongeAcceptancePlumbing(project, lane)
        // 车道参数块（`spongeLane { … }`）晚于插件 apply，读取参数值的接线统一在 afterEvaluate 完成。
        project.afterEvaluate {
            configureSpongeMetadata(project, lane)
            configureToolchainAndTest(project, lane)
            configureSpongePackagingChain(project)
            registerSpongePackagingVerification(project)
            registerSpongeRealserverGate(project, lane)
        }
    }

    /** 公共约定：车道不写即为这些值。 */
    private fun applyConventions(lane: SpongeLaneExtension) {
        lane.license.convention("MIT")
        // 对应 SpongeGradle 的 PluginLoaders.JAVA_PLAIN：Sponge 原生插件入口
        lane.loaderName.convention("java_plain")
        lane.loaderVersion.convention("1.0")
        lane.acceptanceJarName.convention("mpmt-acceptance")
        lane.acceptanceReport.convention("run/acceptance-report.txt")
    }

    /** 单测依赖：JUnit 5 一套（与其它车道同构）。 */
    private fun configureTestDependencies(project: Project) {
        project.dependencies.add("testImplementation", project.dependencies.platform(JUNIT_BOM))
        project.dependencies.add("testImplementation", "org.junit.jupiter:junit-jupiter")
        project.dependencies.add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }

    /**
     * 编译工具链与单测：RC1365 使用 Java 17，编译工具链与目标服务端保持一致；单测走 JUnit 5。
     *
     * spongeapi 由 `sponge {}` apiVersion 以 compileOnly 接入，测试运行期需显式补同版本制品。
     */
    private fun configureToolchainAndTest(project: Project, lane: SpongeLaneExtension) {
        project.extensions.getByType(JavaPluginExtension::class.java)
            .toolchain.languageVersion
            .set(JavaLanguageVersion.of(lane.targetJavaVersion.get()))
        project.tasks.named("test", Test::class.java).configure { useJUnitPlatform() }
        project.dependencies.add(
            "testRuntimeOnly",
            "org.spongepowered:spongeapi:${lane.spongeApiMetadataVersion.get()}",
        )
    }

    private companion object {
        /** 单测 JUnit 版本（与其它车道同一套）。 */
        const val JUNIT_BOM = "org.junit:junit-bom:5.10.3"
    }
}
