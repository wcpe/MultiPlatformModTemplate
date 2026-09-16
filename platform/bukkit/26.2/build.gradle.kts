import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.SpotBugsExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import buildconventions.packagingVerification

// Bukkit 26.2 独立产品工程：common + modern + v26_2 → mpmt-bukkit-26.2-*.jar

plugins {
    id("build-conventions.quality")
    id("build-conventions.platform")
    java
    id("com.gradleup.shadow") version "8.3.11"
    id("top.wcpe.mc.mpmt.realserver-acceptance")
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

val minecraftVersion = "26.2"
val paperRuntimeBuild = 71
val paperRuntimeSizeBytes = 61_744_713L
val paperRuntimeSha256 = "36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e"
val apiCoordinate = "io.papermc.paper:paper-api:26.2.build.72-beta"
val apiSha256 = "ff4dd8b88beb95e990a900f587da3644d44345ce2bc6e8a11b851f6dfb98742b"
val compilerJavaVersion = 25 // 读 paper-api major 69
val targetJavaVersion = 25 // paper-api 元数据要求 JVM 25，产物目标随之为 25
val apiVersion = "26.2"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v26_2.V26_2BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v26_2/V26_2BukkitVersionAdapter.class"

base {
    archivesName.set("mpmt-bukkit-$minecraftVersion")
}

java {
    toolchain {
        // 编译器用 25 才能解析 paper-api（class major 69）
        languageVersion = JavaLanguageVersion.of(compilerJavaVersion)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

val acceptance: SourceSet = sourceSets.create("acceptance")
val mainSourceSet = sourceSets.getByName("main")
// 验收控制通道常量由 build-conventions.platform 生成（生成内容与原内联实现逐字节一致）
platformLane {
    mcVersion.set(minecraftVersion)
    channelName.set(acceptanceChannel)
    channelPackage.set("top.wcpe.mc.mpmt.platform.bukkit.acceptance")
    channelClass.set("BukkitAcceptanceControlChannelId")
}

// paper-api 26.2 的 Gradle 元数据要求 JVM 25：产物目标同为 25（见 targetJavaVersion），
// 故可直接按普通坐标解析。冻结语义由 apiVerification 配置 + SHA-256 校验任务承担，
// 与 bukkit 1.12.2 / 1.20.1 / 1.21.1 三车道保持一致，不再需要本地 libs jar。
val apiVerification =
    configurations.create("apiVerification") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

acceptance.compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
acceptance.runtimeClasspath += mainSourceSet.output + mainSourceSet.runtimeClasspath

dependencies {
    implementation(project(":platform:bukkit:common"))
    implementation(project(":platform:bukkit:modern"))
    add(apiVerification.name, apiCoordinate)
    // 显式钉住编译期 adventure / guava / gson 版本（Paper 元数据亦会传递，这里避免解析漂移；均不入产物）
    compileOnly(platform("net.kyori:adventure-bom:5.2.0"))
    compileOnly(apiCoordinate)
    compileOnly("net.kyori:adventure-api")
    compileOnly("net.kyori:adventure-key")
    compileOnly("net.kyori:adventure-text-minimessage")
    compileOnly("net.kyori:adventure-text-serializer-gson")
    compileOnly("net.kyori:adventure-text-serializer-legacy")
    compileOnly("net.kyori:adventure-text-serializer-plain")
    compileOnly("net.kyori:adventure-text-logger-slf4j")
    compileOnly("com.google.guava:guava:33.6.0-jre")
    compileOnly("com.google.code.gson:gson:2.14.0")
    compileOnly("org.jetbrains:annotations:26.0.2")
    testImplementation(platform("net.kyori:adventure-bom:5.2.0"))
    testImplementation(apiCoordinate)
    testImplementation("net.kyori:adventure-api")
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(acceptance.compileOnlyConfigurationName, platform("net.kyori:adventure-bom:5.2.0"))
    add(acceptance.compileOnlyConfigurationName, apiCoordinate)
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-api")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-key")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-minimessage")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-gson")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-legacy")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-serializer-plain")
    add(acceptance.compileOnlyConfigurationName, "net.kyori:adventure-text-logger-slf4j")
    add(acceptance.compileOnlyConfigurationName, "com.google.guava:guava:33.6.0-jre")
    add(acceptance.compileOnlyConfigurationName, "org.jetbrains:annotations:26.0.2")
    // 产品入口仅 compileOnly：运行期由已加载的产品插件提供，禁止 shade 进验收 jar
    add(acceptance.compileOnlyConfigurationName, project(":platform:bukkit:common"))
    add(acceptance.compileOnlyConfigurationName, project(":platform:bukkit:modern"))
    add(acceptance.implementationConfigurationName, project(":modules:acceptance"))
    add(acceptance.implementationConfigurationName, project(":core:protocol"))
    add(acceptance.implementationConfigurationName, project(":core:server"))
    add(acceptance.implementationConfigurationName, project(":core:client"))
}

val toolchains = extensions.getByType(JavaToolchainService::class.java)
tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        toolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(compilerJavaVersion))
        },
    )
    options.encoding = "UTF-8"
    // 产物与 paper-api 26.2 的运行时要求一致：Java 25 字节码
    options.release.set(targetJavaVersion)
}

val metadataProperties =
    mapOf(
        "version" to project.version,
        "apiVersionMetadata" to "api-version: '$apiVersion'",
        "foliaMetadata" to "folia-supported: true",
    )

tasks.named<ProcessResources>(mainSourceSet.processResourcesTaskName) {
    inputs.properties(metadataProperties)
    filesMatching("plugin.yml") {
        expand(metadataProperties)
    }
}
tasks.named<ProcessResources>(acceptance.processResourcesTaskName) {
    inputs.properties(metadataProperties)
    filesMatching("plugin.yml") {
        expand(metadataProperties)
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    configurations = listOf(project.configurations.runtimeClasspath.get())
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    dependencies {
        exclude(dependency("io.papermc.paper:paper-api"))
        exclude(dependency("org.spigotmc:spigot-api"))
    }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 MC $minecraftVersion 的 Bukkit realserver 验收插件"
    archiveBaseName.set("mpmt-bukkit-acceptance-$minecraftVersion")
    archiveClassifier.set("")
    from(acceptance.output)
    from(mainSourceSet.output) {
        include("top/wcpe/mc/mpmt/platform/bukkit/version/**")
        include("META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter")
    }
    configurations = listOf(project.configurations[acceptance.runtimeClasspathConfigurationName])
    dependencies {
        exclude(dependency("io.papermc.paper:paper-api"))
        exclude(dependency("org.spigotmc:spigot-api"))
    }
    exclude("META-INF/maven/**")
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(acceptance.processResourcesTaskName)
    javaLauncher.set(
        toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        },
    )
    systemProperty("mpmt.test.minecraftVersion", minecraftVersion)
    systemProperty("mpmt.test.javaVersion", targetJavaVersion.toString())
    systemProperty("mpmt.test.archiveName", "mpmt-bukkit-$minecraftVersion")
    systemProperty("mpmt.test.productChannel", productChannel)
    systemProperty("mpmt.test.acceptanceChannel", acceptanceChannel)
    systemProperty("mpmt.test.regionSchedulerClass", regionSchedulerClass)
    systemProperty("mpmt.test.apiVersion", apiVersion)
    systemProperty("mpmt.test.foliaMetadata", true)
    systemProperty(
        "mpmt.test.acceptanceMetadata",
        layout.buildDirectory.file("resources/acceptance/plugin.yml").get().asFile.absolutePath,
    )
}

configure<SpotBugsExtension> {
    toolVersion.set("4.9.8")
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

val verifyApiSnapshotFreeze by tasks.registering {
    group = "verification"
    description = "验证 Bukkit $minecraftVersion API JAR 与冻结 SHA-256 一致"
    doLast {
        val artifact = apiVerification.singleFile
        val actual = sha256(artifact)
        if (actual != apiSha256) {
            throw GradleException(
                "Bukkit $minecraftVersion API 校验失败：expected=$apiSha256, actual=$actual",
            )
        }
        logger.lifecycle("Bukkit $minecraftVersion API 校验通过：${artifact.name} $actual")
    }
}
tasks.named("compileJava") { dependsOn(verifyApiSnapshotFreeze) }
tasks.named(acceptance.compileJavaTaskName) { dependsOn(verifyApiSnapshotFreeze) }

val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Bukkit $minecraftVersion 产品/验收产物"
    dependsOn(tasks.named("shadowJar"), acceptanceJar)
    packagingVerification(
        laneLabel = "Bukkit",
        mcVersion = minecraftVersion,
        product = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
        acceptance = acceptanceJar.flatMap { it.archiveFile },
    ) { product, acceptance ->
        val acceptanceFile = acceptance ?: error("缺少验收产物输入")
        val adapterService =
        "META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter"
        must(product.file.name.contains(minecraftVersion), "产品产物名未包含 MC 版本")
        must(acceptanceFile.file.name.contains(minecraftVersion), "验收产物名未包含 MC 版本")
        mustContain(product, adapterClassPath, "产品缺少选中的 L4 适配器")
        must(product.entries.count { it.endsWith("BukkitVersionAdapter.class") && !it.endsWith("ModernBukkitVersionAdapter.class") && it != "top/wcpe/mc/mpmt/platform/bukkit/version/BukkitVersionAdapter.class" } == 1, "产品包含零个或多个 L4 适配器")
        mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class", "现代产品缺少 Folia 调度类")
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
        mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
        mustMetadataContains(product, "plugin.yml", "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin", "产品 metadata 入口错误")
        mustMetadataContains(product, "plugin.yml", "folia-supported: true", "现代产品缺少 folia 字段")
        mustMetadataContains(product, "plugin.yml", "api-version: '$apiVersion'", "产品 api-version 错误")
        mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
        mustContain(product, "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap", "缺少 PlatformBootstrap services")
        log("Bukkit $minecraftVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
    }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"), acceptance.classesTaskName, verifyPackaging, verifyApiSnapshotFreeze)
}

// 26.2 车道没有"默认轨"：其唯一有效矩阵即 REALSERVER262（见 PlatformLane.BUKKIT_262.defaultMatrix）。
// 因此在本轮上下文（带 -Pmpmt.acceptance.runId）下若未显式声明矩阵，就按 REALSERVER262 解析报告——
// 使跨 lane 聚合门（只有一个全局矩阵值，无法逐 lane 区分）也能正确定位本车道的报告；
// 不带 runId 时保持原有"默认轨"行为，不改变独立调用语义。
val acceptanceMatrix =
    providers
        .gradleProperty("mpmt.acceptance.matrix")
        .orElse(
            providers.gradleProperty("mpmt.acceptance.runId").flatMap { runId ->
                if (runId.isBlank()) providers.provider { "" } else providers.provider { "REALSERVER262" }
            },
        )
val bukkitReportFile =
    acceptanceMatrix.flatMap { matrix ->
        val reportName = if (matrix.isBlank()) "server-report.txt" else "server-report-${matrix.lowercase()}.txt"
        layout.buildDirectory.file("acceptance/$reportName")
    }
val fabric262Product =
    rootProject.layout.projectDirectory.file(
        "platform/fabric/26.2/build/libs/mpmt-fabric-26.2-${rootProject.version}.jar",
    )
val autoHost =
    providers.gradleProperty("mpmt.realserver.autoHost").map { it == "true" }.orElse(false)

mpmtRealServerAcceptance {
    reportFile.set(bukkitReportFile)
    laneId.set("Bukkit")
    matrix.set(acceptanceMatrix)
    autoStartPaperHost.set(autoHost)
    paperVersion.set(minecraftVersion)
    paperBuild.set(paperRuntimeBuild)
    paperJarSizeBytes.set(paperRuntimeSizeBytes)
    paperJarSha256.set(paperRuntimeSha256)
    paperJavaVersion.set(compilerJavaVersion)
    paperPort.set(
        providers.gradleProperty("mpmt.realserver.port").map { it.toInt() }.orElse(25599),
    )
    pluginJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    acceptanceDriverJar.set(acceptanceJar.flatMap { it.archiveFile })
    acceptanceClientProductJar.set(fabric262Product)
    acceptanceClientAcceptanceJar.set(fabric262Product)
    clientTaskName.set("runAcceptanceClient")
    extraDependsOn.set(listOf("shadowJar", "acceptanceJar"))
    acceptanceRunId.set(providers.gradleProperty("mpmt.acceptance.runId").orElse(""))
    acceptanceStartEpochMs.set(providers.gradleProperty("mpmt.acceptance.startEpochMs").orElse(""))
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description = "Bukkit $minecraftVersion realserver 门禁"
    dependsOn(tasks.named("shadowJar"), acceptanceJar, "verifyMpmtAcceptanceReport")
}
