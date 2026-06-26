import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

// platform-forge（L3）：独立 includeBuild，仅应用 ForgeGradle（ADR-0007）。
// 打包链路（ADR-0012）：shade platform-spi + core + relocate snakeyaml 进 mod jar，再 reobf 到 SRG 供真实 Forge 运行。
// 映射用官方（ADR-0016）。core/snakeyaml 为纯 Java、无 MC 引用，reobf 不改写之。

plugins {
    java
    id("net.minecraftforge.gradle") version "6.0.54"
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

val forgeVersion = "1.20.1-47.4.2"
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 服务端公共网络特性（经 api 传递 protocol + core-runtime），各平台注入 TransportPort 后复用同一份装配
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"

base {
    archivesName.set("mpmt-forge")
}

java {
    // Forge 1.20.1 运行于 Java 17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

minecraft {
    // 官方映射（ADR-0016：1.20.1 有官方映射）
    mappings("official", "1.20.1")
}

repositories {
    mavenCentral()
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖），不参与 reobf
val shadowBundle: Configuration by configurations.creating

dependencies {
    minecraft("net.minecraftforge:forge:$forgeVersion")

    // 共享核心（platform-spi + 传递的 core-runtime/core-domain）：纯 Java、shade 进 mod jar
    implementation(spiCoordinate)
    shadowBundle(spiCoordinate)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进 mod jar（传递 protocol）
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

// 最终 mod jar = shadowJar（shade core/spi + relocate snakeyaml），随后 reobf 到 SRG
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 对 shadowJar 产物原地 reobf（named → SRG），生成 reobfShadowJar
reobf {
    create("shadowJar")
}

// 打包校验：mod jar 内核心 shade、snakeyaml relocate、mods.toml 与 services 在位、未误打入 Minecraft
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Forge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、未打入 Minecraft"
    dependsOn("reobfShadowJar")
    doLast {
        val jar = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }
        fun must(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("Forge 打包校验失败：$msg")
        }
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "核心类未 shade 进 mod jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进 mod jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class"), "缺少 Forge mod 主类")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate 到 libs.*")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 原包名残留")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml Maven 元数据残留")
        must(entries.contains("META-INF/mods.toml"), "缺少 META-INF/mods.toml")
        must(entries.contains("META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap"), "缺少 SPI services 声明")
        must(entries.none { it.startsWith("net/minecraft/") }, "误把 Minecraft 类打入 mod jar")
        println("Forge 打包校验通过：")
        println("  产物 = ${jar.name}（条目数 ${entries.size}）")
        println("  核心已 shade、snakeyaml 已 relocate、mods.toml/services 在位、已 reobf、未打入 Minecraft")
    }
}

tasks.named("build") {
    dependsOn("reobfShadowJar", verifyPackaging)
}

tasks.test {
    useJUnitPlatform()
}
