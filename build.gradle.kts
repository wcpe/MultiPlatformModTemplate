// 根构建脚本：只做配置——插件声明、根级坐标与版本、A 车道 mc-testkit 接线。
// 发布聚合与真服/版本矩阵门禁的编排实现集中在 build-conventions.release（ADR-0027）。
//
// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3：VERSION 是版本号唯一来源）。

// 外部分析插件挂 buildscript classpath（apply false），供 subprojects 统一 apply。
plugins {
    id("com.github.spotbugs") version "6.0.26" apply false
    // Kotlin 工具链（现接、前瞻就绪；当前仅 .gradle.kts 为 Kotlin，第二期引入 Kotlin 源即生效）
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jetbrains.kotlinx.kover") version "0.8.3" apply false
    // WCPE Loom（ADR-0025）：必须在根以 apply false 声明，让插件类路径在全构建只加载一次。
    // 若只由各车道在自己的 plugins {} 里请求，Gradle 会按子树各建一份插件类加载器，
    // loom 跨工程迭代（MixinAPMappingService → GradleUtils.allLoomProjects →
    // LoomGradleExtension.get(otherProject)）就会因跨 classloader 而转换失败。
    id("top.wcpe.loom") version "1.17.1" apply false
    id("top.wcpe.loom-no-remap") version "1.17.1" apply false
    // A 车道：mc-testkit（Bukkit/Folia bot e2e；与 B 真 mod 客户端分 lane）
    // 仅经 maven.wcpe.top 解析插件坐标；禁止 sibling includeBuild 联调
    id("top.wcpe.mc-testkit") version "0.5.1"
    id("top.wcpe.mc.mpmt.realserver-report-gate")
    // 根侧编排（发布聚合 + 真服/版本矩阵门禁注册）。须声明在上述报告门插件之后：
    // 该插件注册 verifyRealServerReportsStrict，编排插件为它接线 mustRunAfter。
    id("build-conventions.release")
}

val mpmtVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    group = "top.wcpe.mc.mpmt"
    version = mpmtVersion
}

// ============================================================================
// A 车道：mc-testkit（Bukkit/Folia + mineflayer bot smoke；非 B 主 lane 的 mod 客户端）
// 桩：e2e/harness；bot：e2e/bot；被测 jar / 桩 jar 经 env 或路径注入。
// 接线须留在根：扩展由根 plugins {} 中的外部插件注册，编排插件无从 apply。
// ============================================================================
mcTestkit {
    backend("s1") {
        platform = paper
        version = "1.20.1"
        port = 25565
    }
    // 无 bot：仅校验桩 + 被测插件就绪（smoke 桩内断言 MultiPlatformModTemplate 已启用）
    scenario("smoke") {
        backend = "s1"
    }
    // Folia 后端可选矩阵（同 smoke 场景，换平台声明）
    backend("folia1") {
        platform = folia
        version = "1.20.1"
        port = 25566
    }
    scenario("smoke-folia") {
        backend = "folia1"
    }
    dependencies {
        // 环境变量名或路径；运行前导出或传 -D
        pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        plugin("HARNESS_JAR")
    }
}

/** A 车道聚合：先提示构建 jar，再跑 e2eSmoke（须 env 指向产物）。 */
tasks.register("runMcTestkitSmoke") {
    group = "verification"
    description =
        "A 车道：mc-testkit Paper smoke（须已构建产品/桩 jar 并设置 " +
        "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR 与 HARNESS_JAR；-PmcTestkit.botDir=e2e/bot）"
    dependsOn("e2eSmoke")
}

tasks.register("runMcTestkitFoliaSmoke") {
    group = "verification"
    description = "A 车道：mc-testkit Folia smoke（场景 smoke-folia；依赖同上）"
    // 任务名由 scenario key 生成：smoke-folia → SmokeFolia
    dependsOn("e2eSmokeFolia")
}
