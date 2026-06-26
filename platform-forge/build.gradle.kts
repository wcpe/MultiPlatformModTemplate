import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile
import org.gradle.language.jvm.tasks.ProcessResources

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

    // realserver 验收用客户端运行配置：FG 负责装好 MC 客户端 + 原生 + 资源（headless 可渲染，同 Loom）。
    // 不声明 mods{} dev 源（dev 源会撞 FG6/FML 不暴露 core 的 classpath 墙）；改把 reobf 后的 shaded jar
    // （core 在 jar 内）放进 run-client/mods/，让 FML 当真实 jar mod 加载，绕过 dev classpath 墙。
    runs {
        create("client") {
            workingDirectory(project.file("run-client"))
            property("forge.logging.console.level", "info")
            // 激活验收客户端伴侣（Dist.CLIENT）：伴侣到主菜单后程序化连入本机服务端
            // （Forge dev 客户端不可靠处理 --quickPlayMultiplayer，故由伴侣自连，地址默认 127.0.0.1）。
            property("mpmt.acceptance", "true")
        }
    }
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

// ============================================================================
// realserver 验收驱动（独立 acceptance 源集 + 独立 shaded+reobf mod jar，ADR-0014）
// Forge dev run 因 FG6/FML 模块层不向 mod 暴露库依赖而不可用（已证），故 Forge 与 Bukkit 同走 realserver：
// 真实 Forge 专用服 + 独立 acceptance mod jar。验收驱动代码不入产品 mod jar：单独打 mpmt-acceptance mod，
// 仅在验收运行期放入服务端 mods/。
// 编译期继承 main 的类路径（含 ForgeGradle 提供的 patched MC + Forge API）+ main 产物，并叠加 acceptance 核心 + protocol。
// ============================================================================
val acceptanceCoordinate = "top.wcpe.mc.mpmt:acceptance:$version"
val protocolCoordinate = "top.wcpe.mc.mpmt:protocol:$version"

val acceptance: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["acceptanceImplementation"].extendsFrom(configurations["implementation"])

// 专用配置：需 shade 进验收 mod jar 的内容——**只含 acceptance 核心**（验收 jar 独有、不在产品 jar 里）。
// protocol/core-domain 等已在产品 mod jar 内：Forge 的 FML 模块层禁止两个 mod 导出同名包（split package，
// 否则 ResolutionException 启动失败），故验收 jar 不能再打 protocol/core-domain；运行期由产品 mod 提供
// （FML mod 为自动模块，验收 mod 可读取产品 mod 的包）。protocol 仅作编译期依赖（compileOnly），不入产物。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    // 平台无关验收核心（控制协议 / 协调 / GameTest 框架 / 报告）：验收 jar 独有，shade 进去
    "acceptanceImplementation"(acceptanceCoordinate)
    acceptanceShadowBundle(acceptanceCoordinate)
    // protocol（编 HUD 包用）：仅编译期可见，运行期由产品 mod 提供——绝不打进验收 jar（防 split package）
    "acceptanceCompileOnly"(protocolCoordinate)
}

// 验收 mod mods.toml 的 ${version} 占位由构建注入
tasks.named<ProcessResources>("processAcceptanceResources") {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

// 验收驱动 mod jar：shade acceptance/protocol/core-domain（均第一方、无第三方运行期依赖，无需 relocate），随后 reobf
val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 realserver 验收驱动 mod mpmt-acceptance（仅验收运行期用，不入产品 jar）"
    archiveBaseName.set("mpmt-acceptance-forge")
    archiveClassifier.set("")
    from(acceptance.output)
    configurations = listOf(acceptanceShadowBundle)
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 对验收 mod jar 同样 reobf（named → SRG），令其能在真实 Forge 服运行；生成 reobfAcceptanceJar
reobf {
    create("acceptanceJar")
}

// 把验收源集纳入常规 build 的编译校验（只编译，不打包——打包由验收编排按需触发）
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"))
}
