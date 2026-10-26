import buildconventions.consumeProductJar

// A 车道桩插件（mc-testkit E2E）：根构建子模块（ADR-0026——不自带 settings.gradle.kts / wrapper）。
//
// 形态说明：
// - 协议胶水全部来自共享构件 harness-core（top.wcpe.mc:harness-core）：契约 env 读取、结果文件原子写出、
//   serve 空闲、桩基类（含 Paper/Folia 兼容调度）。本模块只保留 MPMT 的业务场景（smoke / smoke-folia）。
// - 编排扩展 mcTestkit { } 与桩同处本模块：拓扑 / 场景 / 依赖注入随桩一起演进，根侧只留入口别名任务。
// - paper-api 仅 compileOnly：桩在真实服务端里由 PaperMC 提供运行期类，打包不含它。
plugins {
    id("top.wcpe.mc-testkit")
    java
    // 质量门：桩是 E2E 用的辅助模块（无单测、非产品代码），故只保留风格检查口径——
    // 关掉覆盖率底线（无测试可覆盖，0.70 必然误杀）；覆盖率报告与其它工具仍按约定产出。
    id("build-conventions.quality")
}

// 覆盖率为报告 only：本模块不设 LINE 底线（E2E 桩没有可覆盖的单测）。
quality {
    coverageFloor.set(false)
}

repositories {
    // mavenCentral 放最前：质量工具链（ktlint 等）的依赖统一走它，避免被下方专用仓库的首个命中拦下。
    mavenCentral()
    // harness-core 发布地（mc-testkit 系共享胶水构件）
    maven("https://maven.wcpe.top/repository/maven-releases/") { name = "WCPE Releases" }
    // PaperMC 官方仓库：提供 paper-api（桩编译期所需的 Bukkit/Paper API）
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

dependencies {
    // 仅编译期依赖：运行期由真实 Paper 服务端提供，打入插件 jar 会冲突
    compileOnly("io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT")
    // 共享协议胶水（契约 env / 结果文件原子写出 / 桩基类）：打进桩 jar
    implementation("top.wcpe.mc:harness-core:0.1.1")
}

java {
    // 跟随 Paper 1.20.1 的 Java 基线（17）；桩只面对 paper-api（Java）与 harness-core（纯 Java），
    // 故不需要任何 Kotlin 插件或 stdlib，字节码即 Java 17。
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

// 运行期依赖（harness-core）在**配置期**解析成文件集合并包进 jar：真实 Paper 服务端不提供它，
// 不打进来会在 onEnable 抛 NoClassDefFoundError。paper-api 是 compileOnly、不在 runtimeClasspath。
// 用 Provider 惰性传递（而不是在内联 lambda 里读脚本作用域），否则本任务会捕获脚本对象而与
// configuration cache 不兼容。
val runtimeDependencyFiles = configurations.runtimeClasspath.map { files -> files.files }

tasks.jar {
    archiveBaseName.set("mc-testkit-e2e-harness")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(runtimeDependencyFiles.map { set -> set.map { if (it.isDirectory) it else zipTree(it) } })
}

// 质量门由 build-conventions.quality 承担（含 ktlint / checkstyle / PMD / SpotBugs 与覆盖率报告）；
// 本模块只关掉覆盖率**底线**（供 `quality { }` 块声明，见上），不重复装配任何工具。

// 桩 plugin.yml 的版本跟随工程版本（根目录 VERSION 单点来源），不再手写第二份版本号。
// CC 约束：展开表必须声明在**任务配置块内成为局部 val**——脚本顶层 val 在嵌套 lambda 里被引用时，
// Kotlin 生成的闭包仍会捕获整个脚本实例（script object），从而让本任务无法序列化。
tasks.processResources {
    val pluginYmlMetadata = mapOf("version" to project.version.toString())
    inputs.properties(pluginYmlMetadata)
    filesMatching("plugin.yml") { expand(pluginYmlMetadata) }
}

// ============================================================================
// A 车道接线（DSL 形态见 mc-testkit docs/API.md §3.1）
// 拓扑 = 版本矩阵：Paper 1.20.1 / 1.21.1 / 26.2 + Folia 1.20.1，四个后端各自独立场景，
// CI 侧由 .github/workflows/ci.yml 的 e2e-matrix 逐格并行跑、再由聚合 job 收报告。
// 被测插件 jar：CI 矩阵按格用 MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR 指向该版本的产品 jar（覆盖机制保留）；
// 本机缺省走 :platform:bukkit:1.20.1 暴露的产物依赖（outgoing variant "productJar"）——
// 产物名与「先构建 shadowJar」的任务顺序都由 Gradle 从上游产物推导，不再写死 build/libs 路径。
// 既有契约不可变：backend s1/folia1 与 scenario smoke/smoke-folia。
// ============================================================================
val pluginUnderTestEnv: String? = System.getenv("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR")
// 外部注入生效时完全不声明该产物依赖（CI 各格自行先构建对应版本产品 jar，不该被 1.20.1 的图牵连）
val pluginUnderTestArtifact =
    if (pluginUnderTestEnv == null) {
        consumeProductJar(":platform:bukkit:1.20.1", "e2ePluginUnderTest")
    } else {
        null
    }

// 桩 jar 用惰性 provider 表达：不在此刻实现 jar 任务，取值时才随任务图一起确定
val harnessJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }

mcTestkit {
    // 既有拓扑（名称不可变）
    backend("s1") {
        platform = paper
        version = "1.20.1"
        port = 25565
    }
    backend("folia1") {
        platform = folia
        version = "1.20.1"
        port = 25566
    }
    // 版本矩阵扩展：Paper 1.21.1 与 Paper 26.2（无 bot，仅校验桩 + 被测插件就绪）
    backend("paper1211") {
        platform = paper
        version = "1.21.1"
        port = 25567
    }
    backend("paper262") {
        platform = paper
        version = "26.2"
        port = 25568
    }
    scenario("smoke") {
        backend = "s1"
    }
    scenario("smoke-folia") {
        backend = "folia1"
    }
    scenario("smoke1211") {
        backend = "paper1211"
    }
    scenario("smoke262") {
        backend = "paper262"
    }
    dependencies {
        // 两种声明在 mc-testkit 里互斥（同一时刻只能有一种来源）：
        // · 有外部注入 → 按运行期解析的「路径 / 环境变量名」声明；
        // · 无外部注入 → 按「本构建产物来源」声明，mc-testkit 会沿产物依赖把上游打包任务一并
        //   挂到 prepareE2e* / e2e* 上，故无需再手写 dependsOn。
        val injected = pluginUnderTestEnv
        if (injected != null) {
            pluginUnderTest = injected
        } else {
            pluginUnderTest(pluginUnderTestArtifact!!.files)
        }
        plugin(harnessJar)
    }
}
