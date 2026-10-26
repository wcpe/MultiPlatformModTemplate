// 根构建（docs/adr/0026-single-build-subproject-unification.md）：单一根树下 include 全部平台车道子模块。
// 不可变契约：工程路径与目录映射、插件版本单点 pin（车道脚本只写 id 不带版本）、两个约定插件工程的 includeBuild。
// 物理布局即工程路径：core/* · platform/<loader>/* · modules/*；模块坐标 = group + project.name（path 末段）。

import java.io.File

pluginManagement {
    repositories {
        // 上游 marker 临时不可用时，仅从本机 Maven 仓库解析允许的坐标组。
        // 允许清单来自 gradle.properties 的 mpmt.localMavenGroups（单一真源，ADR-0029 / ADR-0032）：
        // Gradle 的仓库内容过滤器只写不可读，提到属性后测试才能按结构化数据核对。
        val localMavenGroups =
            providers.gradleProperty("mpmt.localMavenGroups").orNull
                ?.split(",")
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?: emptyList()
        // 清单为空时必须直接失败：不带 content 过滤的 mavenLocal 会放行本机 m2 的一切制品，
        // 比原本的作用域严格更危险，不能静默退化成"全开"。
        check(localMavenGroups.isNotEmpty()) {
            "gradle.properties 缺少 mpmt.localMavenGroups：空清单会让 mavenLocal 无作用域放行，" +
                "请显式声明本机 Maven 兜底允许的坐标组（ADR-0029）"
        }
        mavenLocal {
            content {
                localMavenGroups.forEach(::includeGroup)
            }
        }
        // 显式指向本机 m2 的 file 仓库：`mavenLocal()` 会受 settings.xml / M2_HOME 影响，
        // 且其内部带有对「本地已安装」的额外判定，发布后的新版本在部分情况下不被看见
        // （实测：publishToMavenLocal 后声明新版本仍解析到旧版本）。
        // 这里直连 ~/.m2/repository，与 publishToMavenLocal 的落盘位置逐字一致。
        maven {
            name = "MavenLocalExplicit"
            url = uri(File(System.getProperty("user.home"), ".m2/repository"))
            content {
                localMavenGroups.forEach(::includeGroup)
            }
        }
        // 阿里云公共镜像：本机网络对 Maven Central / Plugin Portal 的直连不稳定，
        // 而阿里云镜像覆盖了绝大多数 Maven Central 与 Gradle 插件坐标（实测含 shadow
        // 插件及其 BOM 依赖）。放在靠前位置作为兜底来源，避免因单个上游不可达中断解析。
        maven("https://maven.aliyun.com/repository/public") { name = "AliyunPublic" }
        // WCPE Loom（top.wcpe.loom）发布地：dev.architectury:architectury-loom
        maven("https://repo.wcpe.top/repository/maven-releases") { name = "WCPE Releases" }
        // 通用插件门户必须排在加载器仓库之前：ktlint / detekt / shadow 等只发布在此，
        // 加载器仓库并不持有这些坐标（实测 NeoForged 仓库对 ktlint-gradle 返回 404）。
        // 排在后面时，一次无关仓库的 5xx 就会直接中断整个构建——CI 上曾因 maven.neoforged.net
        // 返回 502 拉取 ktlint-gradle 而失败。
        gradlePluginPortal()
        mavenCentral()
        // 各加载器插件与运行期依赖仓库（根单点声明，车道脚本不再各自声明）
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.minecraftforge.net/") { name = "MinecraftForge" }
        // Architectury 必须排在 NeoForged 之前：dev.architectury:* （loom 自身的依赖，
        // 如 mercury / tiny-remapper）同时存在于两者，而 neoforged 当前不可达时
        // Gradle 会直接中断解析、不再继续尝试后续仓库（实测 2026-10-19）。
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
    }
    // 插件版本单点 pin（ADR-0025 / ADR-0026）：车道脚本只写 id，不带版本。
    // loom 版本取自 gradle.properties 的 mpmt.loomVersion（全仓单一真源），这里不再写版本字面量；
    // 这是插件解析的唯一版本来源——根 build.gradle.kts 的 plugins { apply false } 只写 id。
    val loomVersion =
        providers.gradleProperty("mpmt.loomVersion").orNull
            ?: error("gradle.properties 缺少 mpmt.loomVersion：loom 版本必须有单一真源")
    plugins {
        id("top.wcpe.loom") version loomVersion
        // 无混淆变体：loom 的 MixinAPMappingService 遍历全构建 loom 工程，
        // 仅跳过 LoomNoRemapGradlePlugin.isApplied() 的工程；仅靠 fabric.loom.disableObfuscation=true
        // 会让该服务对无 mappings 的工程调用 getMappingConfiguration() 而抛错。
        id("top.wcpe.loom-no-remap") version loomVersion
        id("org.spongepowered.gradle.plugin") version "2.3.0"
    }
    // 真服验收编排约定插件（Gradle 插件工程，必须经 includeBuild 引入；ADR-0026 决策 2）
    includeBuild("build-logic/realserver-acceptance")
    // 构建约定插件（公共构建流程：质量门禁、车道基座、打包断言等；ADR-0027）
    includeBuild("build-logic/build-conventions")
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

// ============================================================================
// 工程坐标与跨工程策略的单点下发（Isolated Projects 友好）
//
// 原先这些规则写在根 build.gradle.kts 的 allprojects { } / subprojects { } 里。
// Isolated Projects（Gradle 9.x）禁止一个工程在配置期触碰其它工程的可变模型，
// 根工程改子工程的 group / version / configurations 正属此列，会被记为
// "cannot access 'Project.group' functionality on subprojects via 'allprojects'"。
//
// 官方替代是 `gradle.lifecycle.beforeProject { }`：回调里 `this` 是**即将配置的那个工程自身**，
// 因此每个工程只配置自己，不构成跨工程访问。顺序语义与原先一致（配置该工程之前执行）。
// ============================================================================

// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3）。
// 在 settings 层经 beforeProject 内的 providers.fileContents 读一次并缓存，
// 这样各子工程不必用 rootProject.file(...) 反向访问根工程（IP 记为跨工程访问）。

// `buildAll` 需要"哪些工程真的有 build 任务"这份清单。Isolated Projects 禁止聚合侧遍历
// subprojects 读它们的任务容器，故由每个工程在**自身** afterEvaluate 里登记（自己看自己的
// 任务容器不构成跨工程访问）。聚合壳目录（`:core` / `:platform:bukkit` 等只有一行注释、
// 未应用 base/java 插件）因此自然被排除。该集合是普通数据，根侧用 provider 延迟到配置
// 末尾求值，读取它不构成跨工程模型访问。
// 必须放在 gradle.extensions 里而非脚本局部 val：闭包若引用脚本成员，Kotlin 会生成
// `this$0` 指向脚本实例，配置缓存会拒绝序列化。
gradle.extensions.extraProperties.set("projectsWithBuildTask", mutableSetOf<String>())

// 架构守卫（ADR-0028 决策 7 / ADR-0030 决策 6）需要「域模块 → 直接依赖」这张表。
//
// 不能由根工程在配置期直接读 `subprojects[i].configurations`：Gradle 广度优先求值，
// 根工程先于子工程，那一刻子工程的 java 插件还没 apply、configurations 容器是空的，
// 于是检查永远拿到空表、静默通过（实测：连 api(project(...)) 都看不到）。
// 也不能用 evaluationDependsOn 去强行提前子工程求值——那是官方明确的反模式。
//
// 正确做法：让每个域工程在**自身求值完成后**上报自己的直接依赖（afterEvaluate 里 `this`
// 就是该工程自身，不构成跨工程访问，Isolated Projects 允许）。上报结果是普通数据，
// 存在 gradle.ext 里供根侧读取——读普通数据不算跨工程模型访问。
gradle.extensions.extraProperties.set("domainModuleDependencies", mutableMapOf<String, Set<String>>())

// 域模块（ADR-0030 三段布局）使用独立 group：末段名与 core:client / core:server 等
// 分层模块重名，Gradle 以 group:name:version 标识模块，重名会被视为同一模块替换
// （实测 :core:server 被 :core:domains:capability:server 顶掉）。
//
// 配置缓存硬约束：`gradle.lifecycle.beforeProject { }` 的 action 不得捕获 settings 脚本对象。
// Kotlin DSL 里脚本顶层的 val / fun 都是**脚本类的成员**，闭包里引用它们会生成 `this$0`
// 指向脚本实例，实测报 "cannot serialize Gradle script object references"。因此：
//   · 闭包内只使用字面量与 Project 自身成员（`path` / `providers` / `layout` / `gradle`）；
//   · 版本号在现场经 `providers.fileContents` 读取并缓存进 extraProperties，不回读脚本 val。
gradle.lifecycle.beforeProject {
    val projectPath = path

    // 版本号唯一来源 = 根目录 VERSION（testing-and-quality §3）。
    // 只在首个工程上读一次并缓存：beforeProject 会为每个工程各跑一遍。
    // 注意 ExtraPropertiesExtension.get() 在键不存在时会抛 MissingPropertyException，
    // 不能用 `as? String` 兜底，必须先 has() 判断。
    val sharedProperties = gradle.extensions.extraProperties
    val resolvedVersion =
        if (sharedProperties.has("mpmtVersion")) {
            sharedProperties.get("mpmtVersion") as String
        } else {
            providers
                .fileContents(layout.settingsDirectory.file("VERSION"))
                .asText
                .get()
                .trim()
                .also { sharedProperties.set("mpmtVersion", it) }
        }

    // 域模块给独立 group（判定逻辑内联，不引用脚本函数）。
    group =
        if (projectPath.contains(":domains:") ||
            (projectPath.startsWith(":examples:") && projectPath.count { it == ':' } >= 2)
        ) {
            val segments = projectPath.split(":").filter { it.isNotEmpty() }
            val domain =
                if (segments.contains("domains")) {
                    segments.getOrElse(segments.indexOf("domains") + 1) { "unknown" }
                } else {
                    segments.getOrElse(1) { "unknown" }
                }
            "top.wcpe.mc.mpmt.domains.$domain"
        } else {
            "top.wcpe.mc.mpmt"
        }
    version = resolvedVersion

    // SNAPSHOT（changing module）默认仅缓存 24 小时，每次 CI 都要回源校验 metadata；
    // 上游（如 repo.papermc.io）抖动即让构建失败（实测见过 502 Bad Gateway）。
    // 本仓库依赖的旧版本线 SNAPSHOT 事实上已冻结——paper-api 1.20.1 停在 build 178（2023-09）、
    // 1.20.4 停在 build 176（2024-10）——故把 TTL 放宽，避免无谓回源。
    // 需要主动取上游新快照时，用 --refresh-dependencies 显式刷新即可。
    configurations.all {
        resolutionStrategy {
            cacheChangingModulesFor(365, java.util.concurrent.TimeUnit.DAYS)
        }
    }
    // 登记"确实有 build 任务"的工程供根侧 buildAll 使用（见 projectsWithBuildTask 的说明）。
    afterEvaluate {
        if (tasks.names.contains("build")) {
            @Suppress("UNCHECKED_CAST")
            (gradle.extensions.extraProperties
                .get("projectsWithBuildTask") as MutableSet<String>).add(projectPath)
        }
    }

    // 域模块在自身求值完成后上报直接项目依赖（见 domainModuleDependencies 的说明）。
    // 只处理 :core:domains: 前缀且确实带构建脚本的叶子模块——隐式父工程
    //（如 :core:domains:capability）没有变体，不参与方向检查。
    if (projectPath.startsWith(":core:domains:") && buildFile.isFile) {
        afterEvaluate {
            @Suppress("UNCHECKED_CAST")
            val registry =
                gradle.extensions.extraProperties
                    .get("domainModuleDependencies") as MutableMap<String, Set<String>>
            registry[projectPath] =
                configurations
                    .flatMap { configuration ->
                        configuration.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java)
                    }.map { dependency -> dependency.path }
                    .toSet()
        }
    }
}

// 车道目录映射的唯一真源：settings 决定 include 与 projectDir 的对应关系，
// 产物聚合任务（build-conventions.release 的 collectReleaseArtifacts）据此拼产物路径。
// 通过 gradle.ext 以不可变值下发给构建脚本——这样聚合侧不必读别的工程的
// layout.buildDirectory（IP 禁止），也不会因缺一份映射而算错目录。
val laneDirectories: Map<String, String> =
    mapOf(
        "platform:fabric:fabric-1.20.1" to "platform/fabric/1.20.1",
        "platform:fabric:fabric-1.21.1" to "platform/fabric/1.21.1",
        "platform:fabric:fabric-26.2" to "platform/fabric/26.2",
        "platform:forge:forge-1.12.2" to "platform/forge/1.12.2",
        "platform:forge:forge-1.20.1" to "platform/forge/1.20.1",
        "platform:forge:forge-1.21.1" to "platform/forge/1.21.1",
        "platform:forge:forge-26.2" to "platform/forge/26.2",
        "platform:neoforge:neoforge-1.20.2" to "platform/neoforge/1.20.2",
        "platform:sponge:sponge-1.20.1" to "platform/sponge/1.20.1",
        // Bukkit 各版本目录与路径末段逐字一致（无加载器前缀），但仍显式登记：
        // 聚合侧只查这一张表，缺项即失败，避免"路径推导"在将来重新引入隐式假设。
        "platform:bukkit:1.12.2" to "platform/bukkit/1.12.2",
        "platform:bukkit:1.20.1" to "platform/bukkit/1.20.1",
        "platform:bukkit:1.21.1" to "platform/bukkit/1.21.1",
        "platform:bukkit:26.2" to "platform/bukkit/26.2",
    )
gradle.extensions.extraProperties.set("laneDirectories", laneDirectories)

rootProject.name = "mpmt"

include(
    "core:kernel",
    // 功能域按 ADR-0030 三段布局切分；模块名短化（不再带域前缀），路径即 <域>:<模块>。
    // 只登记当前有真实内容的模块（不预建空壳）。
    "core:domains:capability:common-api",
    "core:domains:capability:server-api",
    "core:domains:capability:server-internal",
    "core:domains:capability:core",
    "core:domains:capability:server",
    "core:runtime",
    "core:server",
    "core:client",
    "core:protocol",
    "core:spi",
)

include(
    "modules:smoke",
    "modules:acceptance",
)

// FR-18 上手示例域（非产品玩法，不进发布产物）
// FR-18 上手示例域（非产品玩法，不进发布产物）。
// 八模块范本（ADR-0030）：演示域内 API / internal / 实现三段分层，新建域照此抄写。
// 按「不预建空壳」口径，只登记当前有真实内容的模块。
include(
    "examples:counter:common-api",
    "examples:counter:client-api",
    "examples:counter:server-api",
    "examples:counter:core",
    "examples:counter:client-internal",
    "examples:counter:server-internal",
    "examples:counter:client",
    "examples:counter:server",
)

// A 车道（mc-testkit Bukkit/Folia smoke）：桩插件是根构建子模块（ADR-0026——无自有 settings / wrapper），
// 编排扩展 mcTestkit { } 与桩同处该模块，根侧只留 runMcTestkitSmoke / runMcTestkitFoliaSmoke 入口别名。
include(":e2e:harness")

include(
    "platform:bukkit",
    "platform:bukkit:bukkit-api",
    "platform:bukkit:common",
    "platform:bukkit:modern",
    "platform:bukkit:1.12.2",
    "platform:bukkit:1.20.1",
    "platform:bukkit:1.21.1",
    "platform:bukkit:26.2",
)

include(
    "platform:fabric:fabric-api",
    "platform:forge:forge-api",
    "platform:sponge:sponge-api",
)

// 平台车道：全部作为根构建子模块（ADR-0026）。
// 根构建硬要求守护 JVM ≥ 25（26.2 两条车道的 loom 与车道脚本在配置期硬校验，ADR-0026 决策 4）。
//
// 命名：loom 的共享服务键为 "LoomJarManifestService:" + project.name（JarManifestService.get）。
// Gradle 的 project.name 恒等于工程路径末段，而跨加载器存在同名版本段
// （fabric 与 forge 都有 1.20.1 / 1.21.1 / 26.2）。同前缀会让两条车道撞键，运行期报
// `JarManifestService$Inject_ cannot be cast to JarManifestService`（jar / remapJar 均受影响）。
// 因此车道路径末段带上加载器前缀（沿用 ADR-0026 背景记录的复合构建名），
// 目录仍为 `platform/<loader>/<版本>`，构建产物路径与验收报告路径均不变。
// 车道引入与目录映射二合一：路径字符串逐字保留（契约测试按子串断言），目录显式列表不做推导。
listOf(
    "platform:fabric:fabric-1.20.1" to "platform/fabric/1.20.1",
    "platform:fabric:fabric-1.21.1" to "platform/fabric/1.21.1",
    "platform:fabric:fabric-26.2" to "platform/fabric/26.2",
    "platform:forge:forge-1.12.2" to "platform/forge/1.12.2",
    "platform:forge:forge-1.20.1" to "platform/forge/1.20.1",
    "platform:forge:forge-1.21.1" to "platform/forge/1.21.1",
    "platform:forge:forge-26.2" to "platform/forge/26.2",
    "platform:neoforge:neoforge-1.20.2" to "platform/neoforge/1.20.2",
    "platform:sponge:sponge-1.20.1" to "platform/sponge/1.20.1",
).forEach { (path, dir) ->
    include(path)
    project(":$path").projectDir = file(dir)
}
