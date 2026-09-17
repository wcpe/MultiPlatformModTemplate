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
// 被测插件与桩 jar 均按**绝对路径**注入（0.9.3 取值语义：先当环境变量名查、查不到就把该值当路径），
// 消除 `env ?: 路径` 双轨样板；生产者任务由下方 dependsOn 显式接上——框架不会替外部工程接图。
// 拓扑与场景名是不可变契约：backend s1/folia1 + scenario smoke/smoke-folia。
// ============================================================================
val pluginUnderTestJar: String =
    rootProject.layout.projectDirectory
        .file("platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-$version.jar")
        .asFile.absolutePath
val harnessJar: String = tasks.named<Jar>("jar").get().archiveFile.get().asFile.absolutePath

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
        pluginUnderTest = pluginUnderTestJar
        plugin(harnessJar)
    }
}

// prepareE2e* / e2e* 都会在启动前预检并注入 jar：显式接上两个生产者
// （被测插件取 :platform:bukkit:1.20.1 车道的 shadowJar 产物，桩取本模块 jar）。
tasks.matching { it.name.startsWith("prepareE2e") || it.name.startsWith("e2e") }.configureEach {
    dependsOn(":platform:bukkit:1.20.1:shadowJar")
    dependsOn(tasks.jar)
}
