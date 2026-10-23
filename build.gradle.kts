import java.util.concurrent.TimeUnit

// 根构建脚本：只做配置——插件声明、根级坐标与版本、A 车道根侧入口别名。
// 不可变契约：根目录 VERSION 是版本号唯一来源（testing-and-quality §3）、根侧入口任务名
// （runMcTestkitSmoke / runMcTestkitFoliaSmoke）、发布聚合与真服/版本矩阵门禁的编排入口。
// 流程实现集中在 build-conventions.release 与各 loader 约定插件，见 docs/adr/0027-build-convention-plugins.md。

// 外部分析插件以 apply false 声明：由 build-conventions.quality 在运行期按 id 应用，
// 在根声明可保证全构建只有一份插件类路径（避免按子树分裂类加载器）。
plugins {
    id("com.github.spotbugs") version "6.0.26" apply false
    // Kotlin 工具链（现接、前瞻就绪；当前仅 .gradle.kts 为 Kotlin，第二期引入 Kotlin 源即生效）
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9" apply false
    // WCPE Loom（ADR-0025）：必须在根以 apply false 声明，让插件类路径在全构建只加载一次。
    // 若只由各车道在自己的 plugins {} 里请求，Gradle 会按子树各建一份插件类加载器，
    // loom 跨工程迭代（MixinAPMappingService → GradleUtils.allLoomProjects →
    // LoomGradleExtension.get(otherProject)）就会因跨 classloader 而转换失败。
    // 版本由 settings.gradle.kts 的 pluginManagement.plugins 统一定义（真源：gradle.properties
    // 的 mpmt.loomVersion）；这里只写 id，避免同一版本在两处各写一遍、升级时漏改。
    id("top.wcpe.loom") apply false
    id("top.wcpe.loom-no-remap") apply false
    // A 车道：mc-testkit（Bukkit/Folia smoke；与 B 真 mod 客户端分 lane）
    // 仅经 repo.wcpe.top 解析插件坐标；禁止 sibling includeBuild 联调。
    // 只应用在 :e2e:harness（拓扑 / 场景 / 依赖注入随桩同处该子模块），根侧只留入口别名任务。
    id("top.wcpe.mc-testkit") version "0.16.0" apply false
    id("top.wcpe.mc.mpmt.realserver-report-gate")
    // 根侧编排（发布聚合 + 真服/版本矩阵门禁注册）。须声明在上述报告门插件之后：
    // 该插件注册 verifyRealServerReportsStrict，编排插件为它接线 mustRunAfter。
    id("build-conventions.release")
    // 架构守卫（ADR-0028 决策 7）：三档扫描 + mc-shared 形态 + 顶层语义 + 孤儿检查。
    id("build-conventions.architecture")
}

val mpmtVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    group = "top.wcpe.mc.mpmt"
    version = mpmtVersion
    // 域模块的末段名是短名（client / server / core 等，ADR-0030），会与 core:client / core:server
    // 等分层模块重名。Gradle 的项目依赖以 group:name:version 标识模块，重名即被视为同一模块
    // 而被替换（实测 :core:server 被 :core:domains:capability:server 顶掉，运行期
    // ClassNotFoundException: core.server.HandshakeServerService）。故域模块给独立的 group 消除歧义。
    // 域模块不对外发布，改 group 不影响 13 个发布产物。
    val path = project.path
    if (path.contains(":domains:") || (path.startsWith(":examples:") && path.count { it == ':' } >= 2)) {
        val domain = path.split(":").filter { it.isNotEmpty() }.let {
            // core:domains:<域>:<模块> 或 examples:<域>:<模块>
            if (it.contains("domains")) it.getOrElse(it.indexOf("domains") + 1) { "unknown" } else it.getOrElse(1) { "unknown" }
        }
        group = "top.wcpe.mc.mpmt.domains.$domain"
    }
}

// SNAPSHOT（changing module）默认仅缓存 24 小时，每次 CI 都要回源校验 metadata；
// 上游（如 repo.papermc.io）抖动即让构建失败（实测见过 502 Bad Gateway）。
// 本仓库依赖的旧版本线 SNAPSHOT 事实上已冻结——paper-api 1.20.1 停在 build 178（2023-09）、
// 1.20.4 停在 build 176（2024-10），不再重发——故把 TTL 放宽，避免无谓回源。
// 需要主动取上游新快照时，用 --refresh-dependencies 显式刷新即可。
subprojects {
    configurations.all {
        resolutionStrategy {
            cacheChangingModulesFor(365, TimeUnit.DAYS)
        }
    }
}

// ============================================================================
// A 车道：mc-testkit（Bukkit/Folia smoke；非 B 主 lane 的 mod 客户端）
// 拓扑、场景与依赖注入声明在 :e2e:harness（桩子模块，见 e2e/harness/build.gradle.kts）；
// 此处只保留根侧入口别名任务，任务名与分组不变（硬约束）。
// ============================================================================
tasks.register("runMcTestkitSmoke") {
    group = "verification"
    description = "A 车道：mc-testkit Paper smoke（在仓库根执行、被测插件自动取 :platform:bukkit:1.20.1:shadowJar 产物）"
    dependsOn(":e2e:harness:e2eSmoke")
}

tasks.register("runMcTestkitFoliaSmoke") {
    group = "verification"
    description = "A 车道：mc-testkit Folia smoke（场景 smoke-folia；被测插件自动取 :platform:bukkit:1.20.1:shadowJar 产物）"
    dependsOn(":e2e:harness:e2eSmokeFolia")
}
