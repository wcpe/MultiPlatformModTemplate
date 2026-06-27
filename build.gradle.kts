// 根构建脚本：仅定义全局坐标与版本，不承载任何平台插件。
// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3：VERSION 是版本号唯一来源）。

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService

// 外部分析插件挂 buildscript classpath（apply false），供 subprojects 统一 apply。
plugins {
    id("com.github.spotbugs") version "6.0.26" apply false
}

val mpmtVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    group = "top.wcpe.mc.mpmt"
    version = mpmtVersion
}

// ============================================================================
// 静态分析 / 质量工具链（严格门禁，static-analysis.md）——根构建各 Java 模块统一接入。
// 核心 Gradle 插件（checkstyle 等）经 subprojects 统一配置，共享 config/ 规则集；
// 违规即失败构建（isIgnoreFailures=false）。各独立 includeBuild 平台各自接同一套（共享 config/）。
// ============================================================================
subprojects {
    // 样式审查：Checkstyle（裁剪规则集，聚焦导入卫生/命名/结构）
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.17.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }
    // 代码异味 / 源码规则：PMD（裁剪规则集，聚焦未用/空块/吞异常/线程等真实坏味道）
    apply(plugin = "pmd")
    configure<PmdExtension> {
        toolVersion = "7.0.0"
        isConsoleOutput = true
        ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
        ruleSets = emptyList()
        isIgnoreFailures = false
    }
    // 测试覆盖率：JaCoCo（报告 + 覆盖率底线门禁）。底线 LINE 0.70 取各模块当前覆盖率（最低 77%）下方整数、防回退。
    apply(plugin = "jacoco")
    tasks.withType(org.gradle.testing.jacoco.tasks.JacocoReport::class.java).configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    tasks.withType(org.gradle.testing.jacoco.tasks.JacocoCoverageVerification::class.java)
        .configureEach {
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.70".toBigDecimal()
                    }
                }
            }
        }
    tasks.withType(Test::class.java).configureEach {
        finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
    }
    tasks.matching { it.name == "check" }
        .configureEach { dependsOn(tasks.matching { it.name == "jacocoTestCoverageVerification" }) }
    // 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
    apply(plugin = "com.github.spotbugs")
    configure<SpotBugsExtension> {
        ignoreFailures.set(false)
        effort.set(Effort.MAX)
        // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
        reportLevel.set(Confidence.MEDIUM)
        excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
    }
    dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
    // 把 lombok.config 登记为编译输入：其改动须失效编译缓存（否则构建缓存会服旧的、缺 @Generated 的
    // 类，导致 SpotBugs/JaCoCo 仍对 Lombok 生成代码误报）。
    tasks.withType(JavaCompile::class.java).configureEach {
        inputs.file(rootProject.file("lombok.config"))
            .withPropertyName("lombokConfig")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // 分析工具运行 JVM 与被测模块目标字节码无关：L0–L2 编译工具链为 JDK 8，但 Checkstyle 10.x 需 JDK 11+，
    // 故把分析任务固定到 JDK 17 启动器运行（不影响模块自身的 Java 8 编译目标）。
    // 在 afterEvaluate 配置：JavaToolchainService 由模块自身的 java 插件注册、晚于本 subprojects 块。
    afterEvaluate {
        val toolchains = extensions.getByType(JavaToolchainService::class.java)
        val analysisLauncher =
            toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
        tasks.withType(Checkstyle::class.java).configureEach {
            javaLauncher.set(analysisLauncher)
        }
        tasks.withType(Pmd::class.java).configureEach {
            javaLauncher.set(analysisLauncher)
        }
        // SpotBugs worker 默认用守护 JVM（JDK 17），无需固定 launcher。
        // 仅生产码（spotbugsMain）严格门禁；test / acceptance / gametest 等非 main 源集宽松
        // （测试与验收 harness 常含 mock/反射等 SpotBugs 噪声，安全/缺陷分析重在生产码）。
        tasks.withType(SpotBugsTask::class.java).configureEach {
            if (name != "spotbugsMain") {
                ignoreFailures = true
            }
        }
    }
}

// 一键全量构建：复合构建的 includeBuild 默认不并入根 build 生命周期，这里聚合
// 根构建各子模块 + 各独立 includeBuild 平台的 build，便于本地 / CI 全量验证。
tasks.register("buildAll") {
    group = "build"
    description = "构建根构建所有模块 + 各 includeBuild 平台产物"
    dependsOn(subprojects.map { "${it.path}:build" })
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}

