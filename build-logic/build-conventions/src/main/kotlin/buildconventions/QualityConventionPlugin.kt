package buildconventions

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

/**
 * 静态分析与质量门禁（严格）——工程显式 `plugins { id("build-conventions.quality") }` 接入。
 *
 * 覆盖 Checkstyle / PMD / SpotBugs(+FindSecBugs) / ktlint / detekt / kover / JaCoCo，
 * 违规即失败（`isIgnoreFailures = false`），覆盖率下限 LINE 0.70；规则集仍在仓库根 `config/`。
 */
class QualityConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val quality = project.extensions.create("quality", QualityExtension::class.java)
        quality.checkstyleToolVersion.convention("10.17.0")
        quality.pmdToolVersion.convention("7.0.0")
        quality.analysisJavaVersion.convention(DEFAULT_ANALYSIS_JAVA_VERSION)
        quality.coverageFloor.convention(true)
        applyStyleAndBugs(project, quality)
        applyCoverage(project, quality)
        applyKotlinToolchain(project)
        wireCompileInputs(project)
        pinAnalysisLaunchers(project, quality)
    }

    /** 样式审查：Checkstyle（导入卫生/命名/结构）与 PMD（未用/空块/吞异常/线程等真实坏味道）。 */
    private fun applyStyleAndBugs(project: Project, quality: QualityExtension) {
        project.pluginManager.apply("checkstyle")
        project.pluginManager.apply("pmd")
        project.pluginManager.apply("com.github.spotbugs")
        // 工具版本与排除过滤器在 afterEvaluate 读取：车道 `quality { … }` 块晚于插件 apply，
        // 在 apply 期 get() 只会拿到约定值，覆盖项会被丢掉。
        project.afterEvaluate {
            configure<CheckstyleExtension> {
                toolVersion = quality.checkstyleToolVersion.get()
                configFile = project.rootProject.file("config/checkstyle/checkstyle.xml")
                isIgnoreFailures = false
                maxWarnings = 0
            }

            configure<PmdExtension> {
                toolVersion = quality.pmdToolVersion.get()
                isConsoleOutput = true
                ruleSetConfig = project.resources.text.fromFile(project.rootProject.file("config/pmd/ruleset.xml"))
                ruleSets = emptyList()
                isIgnoreFailures = false
            }

            // 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
            configure<SpotBugsExtension> {
                quality.spotbugsToolVersion.orNull?.let { version -> toolVersion.set(version) }
                ignoreFailures.set(false)
                effort.set(Effort.MAX)
                // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
                reportLevel.set(Confidence.MEDIUM)
                excludeFilter.set(project.rootProject.file("config/spotbugs/exclude.xml"))
            }
            dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
        }
    }

    /**
     * 覆盖率：JaCoCo 报告 + LINE 0.70 底线，并接入 `check`。
     *
     * 平台车道会把 core/protocol/spi 等共享模块**嵌入**自己的 main classes（Forge SecureJar 需要），
     * 若一并计入会稀释车道覆盖率，而它们已由各自模块的测试覆盖，故仅对平台车道排除这些共享包。
     * 注：必须作用在 class *文件树* 上——JaCoCo 自行遍历目录，只过滤目录集合无效。
     */
    private fun applyCoverage(project: Project, quality: QualityExtension) {
        project.pluginManager.apply("jacoco")

        val isPlatformLane = project.path.startsWith(":platform:") && project.path != ":platform"
        if (isPlatformLane) {
            val laneCodeExclusions = SHARED_PACKAGES_EXCLUDED_FROM_PLATFORM_LANES
            // 必须在 afterEvaluate 应用：java 插件在 afterEvaluate 才填充 classDirectories，
            // 若在 configureEach 里 setFrom，会被随后的默认值覆盖掉。
            project.afterEvaluate {
                // classDirectories 被重建成 fileTree 后，Gradle 不再知道这些目录由哪个任务产出，
                // 任务的直接依赖会变成空集——正常路径靠 test 的传递依赖兜住，一旦 `-x test`
                // 就暴露为 "uses this output ... without declaring a dependency"。故显式声明
                // 产物生产者：classes / processResources，以及车道往 build/resources/main 嵌入
                // 内容的自定义拷贝任务（如 forge 26.2 的 embedProductSharedIntoMain）。
                val classDirProducers =
                    tasks.matching {
                        it.name == "classes" || it.name == "processResources" || it.name.endsWith("IntoMain")
                    }
                tasks.withType(JacocoReport::class.java).configureEach {
                    classDirectories.setFrom(
                        files(
                            classDirectories.files.map { dir ->
                                fileTree(dir) { exclude(laneCodeExclusions) }
                            },
                        ),
                    )
                    dependsOn(classDirProducers)
                }
                tasks.withType(JacocoCoverageVerification::class.java).configureEach {
                    classDirectories.setFrom(
                        files(
                            classDirectories.files.map { dir ->
                                fileTree(dir) { exclude(laneCodeExclusions) }
                            },
                        ),
                    )
                    dependsOn(classDirProducers)
                }
            }
        }

        project.tasks.withType(JacocoReport::class.java).configureEach {
            reports.xml.required.set(true)
            reports.html.required.set(true)
        }
        // 覆盖率底线按车道开关：无业务测试的辅助模块（E2E 桩）关掉它，避免必然误杀；
        // 报告仍产出，只是不设阈值、不入 `check`。
        val coverageFloorEnabled = project.providers.provider { quality.coverageFloor.get() }
        project.tasks.withType(JacocoCoverageVerification::class.java).configureEach {
            onlyIf { coverageFloorEnabled.get() }
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.70".toBigDecimal()
                    }
                }
            }
        }
        project.tasks.withType(Test::class.java).configureEach {
            finalizedBy(project.tasks.matching { it.name == "jacocoTestReport" })
        }
        project.tasks.matching { task -> task.name == "check" }.configureEach {
            if (coverageFloorEnabled.get()) {
                dependsOn(project.tasks.matching { task -> task.name == "jacocoTestCoverageVerification" })
            }
        }
    }

    /** Kotlin 工具链：ktlint 检 `.gradle.kts`（含插件工程脚本）、detekt 扫 Kotlin 源、kover 备 Kotlin 覆盖率。 */
    private fun applyKotlinToolchain(project: Project) {
        project.pluginManager.apply("org.jlleitschuh.gradle.ktlint")
        project.pluginManager.apply("io.gitlab.arturbosch.detekt")
        project.pluginManager.apply("org.jetbrains.kotlinx.kover")
    }

    /**
     * 把 `lombok.config` 登记为编译输入：其改动须失效编译缓存，
     * 否则构建缓存会服旧的、缺 `@Generated` 的类，导致 SpotBugs/JaCoCo 仍对 Lombok 生成代码误报。
     */
    private fun wireCompileInputs(project: Project) {
        project.tasks.withType(JavaCompile::class.java).configureEach {
            inputs.file(project.rootProject.file("lombok.config"))
                .withPropertyName("lombokConfig")
                .withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    /**
     * 分析任务固定 JDK 17 启动：Checkstyle 10.x 需 JDK 11+，而 L0–L2 的编译工具链是 JDK 8
     * （分析工具运行 JVM 与被测模块目标字节码无关）。
     *
     * 在 afterEvaluate 配置：JavaToolchainService 由模块自身 java 插件注册、晚于插件应用；
     * 无业务源码的聚合壳可能没有 java 插件，此时跳过。
     */
    private fun pinAnalysisLaunchers(project: Project, quality: QualityExtension) {
        project.afterEvaluate {
            val toolchains = extensions.findByType(JavaToolchainService::class.java) ?: return@afterEvaluate
            val analysisLauncher =
                toolchains.launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(quality.analysisJavaVersion.get()))
                }
            tasks.withType(Checkstyle::class.java).configureEach { javaLauncher.set(analysisLauncher) }
            tasks.withType(Pmd::class.java).configureEach { javaLauncher.set(analysisLauncher) }
            // SpotBugs worker 默认用守护 JVM，无需固定 launcher。
            // 仅生产码（spotbugsMain）严格门禁；test / acceptance / gametest 等非 main 源集宽松
            // （测试与验收 harness 常含 mock/反射等噪声，安全与缺陷分析重在生产码）。
            tasks.withType(SpotBugsTask::class.java).configureEach {
                if (name != "spotbugsMain") {
                    ignoreFailures = true
                }
                // 报告必须落盘：非门禁源集（test / acceptance / gametest）不因检出而失败，
                // 若不产出报告，检出结果在任何地方都不可见，既无法复核也无法据此判断该不该抑制。
                // 插件 6.x 的 reports 容器默认为空，须显式创建（默认不建任何报告）。
                reports.create("html").required.set(true)
                // Lombok 在各模块是 compileOnly，不随编译类路径传递；但被分析类的字节码带有
                // @lombok.Generated / @lombok.NonNull。缺这两个类会让 SpotBugs 分析不完整并
                // 以退出码 3 结束（harness 全绿也掩盖不了"其实没分析干净"）。补进辅助类路径。
                auxClassPaths.from(
                    project.configurations.detachedConfiguration(
                        project.dependencies.create("org.projectlombok:lombok:${LOMBOK_AUX_VERSION}"),
                    ),
                )
            }
        }
    }

    private companion object {
        /** 分析任务启动 JDK：Checkstyle 10.x 需 JDK 11+，独立于被测模块的编译工具链（可能是 JDK 8）。 */
        const val DEFAULT_ANALYSIS_JAVA_VERSION: Int = 17

        /** SpotBugs 辅助类路径补齐用的 Lombok 版本，与各模块 compileOnly 声明保持一致。 */
        const val LOMBOK_AUX_VERSION: String = "1.18.48"

        /** 平台车道嵌入的共享模块包：计入车道覆盖率会稀释口径（由各模块自身测试覆盖）。 */
        val SHARED_PACKAGES_EXCLUDED_FROM_PLATFORM_LANES =
            listOf(
                "top/wcpe/mc/mpmt/core/**",
                "top/wcpe/mc/mpmt/domain/**",
                "top/wcpe/mc/mpmt/protocol/**",
                "top/wcpe/mc/mpmt/paths/**",
                "top/wcpe/mc/mpmt/config/**",
                "top/wcpe/mc/mpmt/acceptance/**",
                "top/wcpe/mc/mpmt/platform/spi/**",
            )
    }
}
