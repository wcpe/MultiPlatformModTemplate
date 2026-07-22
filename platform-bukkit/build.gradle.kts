import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.zip.ZipFile

// L3 platform-bukkit（Bukkit/Spigot/Paper/Folia 单一构建，ADR-0007）：普通 Java + shadow。
// 编译基线 paper-api（compileOnly，ADR-0019：spigot 超集，为接 Folia 区域调度 API），运行期仍 Bukkit 家族；
// Paper/Folia 差异运行期经 FeatureGate 适配（paper-only API 调用一律经 FeatureGate 门控）。
// 打包：shade 共享核心 + relocate snakeyaml 进插件 jar（ADR-0012，关闭 Bukkit 共享类加载空间的冲突）。

plugins {
    java
    id("com.gradleup.shadow") version "8.3.3"
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

// paper-api 版本沿用 1.20.1-R0.1-SNAPSHOT（与 spigot 同版号方案，PaperMC 仓库提供）
val paperApiVersion = "1.20.1-R0.1-SNAPSHOT"
val snakeyamlVersion = "2.2"

java {
    // Bukkit 1.20.1 运行于 Java 17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") { name = "Spigot" }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") { name = "SonatypeSnapshots" }
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

dependencies {
    // Bukkit 编译基线 paper-api（spigot 超集，含 Folia 区域调度 API）：编译可见、运行期由服务端提供（ADR-0007/0019）
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    // 共享核心：shade 进插件 jar（platform-spi 经 api 传递 core-runtime + core-domain）
    implementation(project(":platform-spi"))
    // 服务端公共网络特性（FR-19）：各平台注入 TransportPort 后复用同一份握手 / 协商 / 收发装配
    implementation(project(":core-server"))
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    // Bukkit 无 GameTest：用 MockBukkit 在无真实服下验证插件装配链路（FR-23）
    // 用 3.88.1（MockBukkit-v1.20 最后一个 Java 17 基线版本；之后 bump 到 Java 21，与本模块 1.20.1/JDK17 不符）
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.88.1")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// plugin.yml 的 ${version} 占位由构建注入
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

// 最终插件 jar = shadowJar（无 classifier）：shade 核心 + relocate snakeyaml
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    // relocate 不动 META-INF/maven 元数据，剔除保持洁净
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与 platform-fabric 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 打包校验：插件 jar 内核心未改包名、snakeyaml relocate、plugin.yml 与 services 在位、未误打入 Bukkit API
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Bukkit 插件 jar：核心 shade、snakeyaml relocate、plugin.yml/services 在位、未打入 Bukkit API"
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val plain = tasks.named<Jar>("jar").get()
        val jar = shadow.archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

        fun must(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("Bukkit 打包校验失败：$msg")
        }
        must(plain.archiveFile.get().asFile != jar, "普通 jar 与最终 shadowJar 输出路径冲突")
        must(!shadow.isPreserveFileTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
        must(shadow.isReproducibleFileOrder, "最终产品未启用可复现文件顺序")
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "核心类未 shade 进插件 jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进插件 jar")
        must(entries.contains("top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class"), "缺少插件主类")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate 到 libs.*")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 原包名残留")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml Maven 元数据残留")
        must(entries.contains("plugin.yml"), "缺少 plugin.yml")
        must(entries.contains("META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap"), "缺少 SPI services 声明")
        must(entries.none { it.startsWith("org/bukkit/") }, "误把 Bukkit API 打入插件 jar（应 compileOnly）")
        println("Bukkit 打包校验通过：")
        println("  产物 = ${jar.name}（条目数 ${entries.size}）")
        println("  核心已 shade、snakeyaml 已 relocate、plugin.yml/services 在位、未打入 Bukkit API")
    }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"), verifyPackaging)
}

// ============================================================================
// realserver 验收驱动（独立 acceptance 源集 + 独立插件 jar，ADR-0014）
// Bukkit 无 GameTest，故 realserver 是其唯一实机验收形态。验收驱动代码不入产品插件 jar：
// 单独编译为 mpmt-acceptance 插件，仅在验收运行期放入服务端 plugins/。
// ============================================================================
val acceptance: SourceSet by sourceSets.creating

// 纯 JVM 验收契约源集：验证 P1 清单与 acceptance v2 严格报告，不依赖真实服下载
val acceptanceTest: SourceSet by sourceSets.creating {
    compileClasspath += acceptance.output + acceptance.compileClasspath + sourceSets["main"].output
    runtimeClasspath += output + acceptance.runtimeClasspath + compileClasspath
}
configurations["acceptanceTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["acceptanceTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    // Bukkit 编译基线 paper-api（spigot 超集）编译可见、运行期由服务端提供（ADR-0019）
    "acceptanceCompileOnly"("io.papermc.paper:paper-api:$paperApiVersion")
    // 平台无关验收核心 + 协议 + 进程内回环所需 core（shade 进验收 jar；插件类加载器隔离，与产品 jar 分装可共存）
    "acceptanceImplementation"(project(":acceptance"))
    "acceptanceImplementation"(project(":protocol"))
    "acceptanceImplementation"(project(":core-server"))
    "acceptanceImplementation"(project(":core-client"))
    // 纯 JVM P1 契约：复用 acceptance 源集输出（含 BukkitP1Simulation）
    "acceptanceTestImplementation"(project(":acceptance"))
    "acceptanceTestImplementation"(project(":protocol"))
    "acceptanceTestImplementation"(project(":core-server"))
    "acceptanceTestImplementation"(project(":core-client"))
    "acceptanceTestImplementation"(platform("org.junit:junit-bom:5.10.3"))
    "acceptanceTestImplementation"("org.junit.jupiter:junit-jupiter")
    "acceptanceTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

// 验收插件 plugin.yml 的 ${version} 占位由构建注入
tasks.named<ProcessResources>("processAcceptanceResources") {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// 验收驱动插件 jar：shade acceptance/protocol/core-domain（均第一方、无第三方运行期依赖，无需 relocate）
val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 realserver 验收驱动插件 mpmt-acceptance（仅验收运行期用，不入产品 jar）"
    archiveBaseName.set("mpmt-acceptance")
    archiveClassifier.set("")
    from(acceptance.output)
    configurations = listOf(project.configurations["acceptanceRuntimeClasspath"])
    // 防御：paper-api 为 compileOnly，不应进产物
    dependencies { exclude(dependency("io.papermc.paper:paper-api")) }
    exclude("META-INF/maven/**")
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val acceptanceContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "运行 Bukkit acceptance v2 与完整 P1 场景契约测试"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    useJUnitPlatform()
}

// 把验收源集与契约测试纳入常规 build/check（只编译/单测，真实服运行另触发）
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"), acceptanceContractTest)
}
tasks.named("check") {
    dependsOn(acceptanceContractTest)
}
