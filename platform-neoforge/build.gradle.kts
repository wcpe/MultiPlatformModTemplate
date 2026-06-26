import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// platform-neoforge（L3）：独立 includeBuild，应用 NeoGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.2（NeoForge 无 1.20.1；PRD §7）。NeoForge 运行期用官方 Mojmap、无 SRG/reobf（区别于 Forge）；
// Mixin 内置（mods.toml [[mixins]] 声明、无 MixinGradle、无 refmap）。打包：shade 共享核心 + relocate snakeyaml（ADR-0012）。
// dev run classpath 墙同 FG：includeBuild/shaded 库默认不在 dev 运行期类路径，经 additionalRuntimeClasspath 暴露。

plugins {
    `java-library`
    id("net.neoforged.gradle.userdev") version "7.0.116"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

val neoforgeVersion = "20.2.93"
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"

base {
    archivesName.set("mpmt-neoforge")
}

java {
    // NeoForge 1.20.2 运行于 Java 17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

dependencies {
    // NeoForge userdev：单一依赖传递性引入 patched MC + loader（NeoGradle 7，非 minecraft(...)）
    implementation("net.neoforged:neoforge:$neoforgeVersion")

    // 共享核心（platform-spi + 传递 core-runtime/core-domain）：纯 Java、shade 进 mod jar
    implementation(spiCoordinate)
    shadowBundle(spiCoordinate)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进 mod jar（传递 protocol）
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // 注：dev run 运行期类路径（NeoGradle 同 FG 的 dev classpath 墙——shaded/includeBuild 库默认不在 dev 运行期可见）
    // 待 realserver dev run 里程碑按需解决（per-run runtime 依赖或把 shaded jar 入 run-*/mods）；编译 + 纯 JVM 测试不需要。

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// NeoGradle 运行配置：client/server dev run（NeoGradle 自动装好 MC 客户端 + 资源）。
// Kotlin DSL 下 runs 为 NamedDomainObjectContainer，用 create("...")（Groovy 的 client{} 简写不可用）。
runs {
    configureEach {
        // 告诉运行加载本 mod 的 main 源集
        modSource(project.sourceSets["main"])
        systemProperty("forge.logging.console.level", "info")
    }
    create("client") {
        workingDirectory(project.file("run-client"))
    }
    create("server") {
        workingDirectory(project.file("run-server"))
        programArgument("--nogui")
    }
}

// mods.toml 的 ${version} 占位由构建注入
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("dev")
}

// 最终 mod jar = shadowJar（shade core/spi + relocate snakeyaml）。NeoForge 运行期 Mojmap、无 reobf。
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.test {
    useJUnitPlatform()
}
