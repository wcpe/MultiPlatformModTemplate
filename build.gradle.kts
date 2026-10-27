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
    id("dev.detekt") version "2.0.0-alpha.6" apply false
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
    // 架构守卫（ADR-0028 决策 7 / ADR-0036）：mc-shared 形态 + 顶层语义 + 依赖方向。
    // 分层引用不再扫源码文本，改由依赖声明与 ArchUnit（LayeringArchTest / DomainArchTest）承担。
    id("build-conventions.architecture")
}

// 工程坐标（group / version）与 SNAPSHOT 缓存策略已上移到 settings.gradle.kts 的
// `gradle.lifecycle.beforeProject { }`：Isolated Projects 禁止根工程在配置期改子工程的
// 可变模型（原 allprojects / subprojects 会被记为跨工程访问）。每个工程只配置自己。
// 版本号唯一来源仍是根目录 VERSION，settings 读取一次后统一下发。

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
