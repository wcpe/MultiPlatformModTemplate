import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.spongepowered.gradle.plugin.config.PluginLoaders
import org.spongepowered.plugin.metadata.model.PluginDependency

// platform-sponge（L3）：独立 includeBuild，应用 SpongeGradle（ADR-0007，隔离加载器专属插件）。
// 锚点 MC 1.20.1 / SpongeAPI 11.0.0（SpongeVanilla）。Sponge 为纯服务端平台（无客户端插件 API）：
// FR-27 跨端 HUD 由 Sponge 服下发、客户端复用我方 Fabric 伴侣渲染（异构互通，同 Bukkit 模式）。
// spongeapi 由 sponge{} apiVersion 接入（compileOnly，运行期服务端提供，不 shade）；core shade + relocate snakeyaml（ADR-0012）。
// 无 reobf（Sponge 不 remap，同 NeoForge）。sponge{} DSL 生成插件元数据，不手写 sponge_plugins.json。

plugins {
    `java-library`
    id("org.spongepowered.gradle.plugin") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"

base {
    archivesName.set("mpmt-sponge")
}

java {
    // SpongeAPI 11 最新制品与 SpongeVanilla 1.20.1 最新 RC 已要求 Java 21（按各 loader 最低 JDK 编译，ADR-0004）；
    // shade 进来的 core 为 Java 8 字节码、与 21 运行期兼容。
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/") { name = "Sponge" }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖）
val shadowBundle: Configuration by configurations.creating

dependencies {
    // 共享核心（platform-spi + 传递 core-runtime/core-domain）：纯 Java、shade 进插件 jar
    implementation(spiCoordinate)
    shadowBundle(spiCoordinate)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进插件 jar（传递 protocol）
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")
    // 注：spongeapi 由 sponge{} apiVersion 自动接入（compileOnly），不在此手动声明、不 shade

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 插件元数据由 SpongeGradle 生成（不手写 META-INF/sponge_plugins.json）
sponge {
    // 1.20.1 对应 SpongeAPI 11 的 Java 17 制品为 11.0.0-SNAPSHOT（release 11.0.0 已是 Java 21）
    apiVersion("11.0.0-SNAPSHOT")
    license("MIT")
    loader {
        name(PluginLoaders.JAVA_PLAIN)
        version("1.0")
    }
    plugin("mpmt") {
        displayName("MultiPlatformModTemplate")
        entrypoint("top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin")
        description("多平台 mod 玩法脚手架 —— Sponge 平台胶水")
        dependency("spongeapi") {
            loadOrder(PluginDependency.LoadOrder.AFTER)
            optional(false)
        }
    }
}

// 最终插件 jar = shadowJar（shade core/spi + relocate snakeyaml）。Sponge 运行期不 remap、无 reobf。
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
