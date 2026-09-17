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
}

repositories {
    // harness-core 发布地（mc-testkit 系共享胶水构件）
    maven("https://maven.wcpe.top/repository/maven-releases/") { name = "WCPE Releases" }
    // PaperMC 官方仓库：提供 paper-api（桩编译期所需的 Bukkit/Paper API）
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
    mavenCentral()
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

tasks.jar {
    archiveBaseName.set("mc-testkit-e2e-harness")
    // 把运行期依赖（harness-core）打进插件 jar：真实 Paper 服务端不提供它，不打进来会在 onEnable
    // 抛 NoClassDefFoundError。paper-api 是 compileOnly、不在 runtimeClasspath，故不会被打入。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

// 桩 plugin.yml 的版本跟随工程版本（根目录 VERSION 单点来源），不再手写第二份版本号。
tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// ============================================================================
// A 车道接线（DSL 形态见 mc-testkit docs/API.md §3.1）
// 拓扑 = 版本矩阵：Paper 1.20.1 / 1.21.1 / 26.2 + Folia 1.20.1，四个后端各自独立场景，
// CI 侧由 .github/workflows/ci.yml 的 e2e-matrix 逐格并行跑、再由聚合 job 收报告。
// 被测插件 jar：CI 矩阵按格用 MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR 指向该版本的产品 jar；
// 本机缺省取 :platform:bukkit:1.20.1 的 shadowJar 产物（绝对路径，避免依赖进程工作目录）。
// 既有契约不可变：backend s1/folia1 与 scenario smoke/smoke-folia。
// ============================================================================
val productVersion: String = rootProject.file("VERSION").readText().trim()
val pluginUnderTestEnv: String? = System.getenv("MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR")
val pluginUnderTestJar: String =
    pluginUnderTestEnv
        ?: rootProject.layout.projectDirectory
            .file("platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-$productVersion.jar")
            .asFile.absolutePath
val harnessJar: String = tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath

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
        pluginUnderTest = pluginUnderTestJar
        plugin(harnessJar)
    }
}

// prepareE2e* / e2e* 启动前会预检并注入 jar：桩（本模块 jar）恒接；
// 被测插件默认取 :platform:bukkit:1.20.1 的 shadowJar 产物——CI 矩阵各格自行先构建对应版本产品 jar，
// 故在外部注入生效时不再接 1.20.1 的图。
tasks.matching { it.name.startsWith("prepareE2e") || it.name.startsWith("e2e") }.configureEach {
    dependsOn(tasks.jar)
    if (pluginUnderTestEnv == null) {
        dependsOn(":platform:bukkit:1.20.1:shadowJar")
    }
}
