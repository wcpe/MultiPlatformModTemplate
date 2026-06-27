// 根构建脚本：仅定义全局坐标与版本，不承载任何平台插件。
// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3：VERSION 是版本号唯一来源）。

import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.jvm.toolchain.JavaToolchainService

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

