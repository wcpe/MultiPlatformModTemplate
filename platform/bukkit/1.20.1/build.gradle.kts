import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile
import buildconventions.packagingVerification

// Bukkit 1.20.1 独立产品工程：common + modern + v1_20 → mpmt-bukkit-1.20.1-*.jar

plugins {
    id("build-conventions.quality")
    id("build-conventions.platform")
    java
    id("com.gradleup.shadow") version "8.3.11"
    id("top.wcpe.mc.mpmt.realserver-acceptance")
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

val minecraftVersion = "1.20.1"
val apiCoordinate = "io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT"
val apiSha256 = "161ecf24e6ffb325a79eb7eb04904419c2b80f602672e4758785e9389e9038d1"
val targetJavaVersion = 17
val apiVersion = "1.20"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_20/V1_20BukkitVersionAdapter.class"

base {
    archivesName.set("mpmt-bukkit-$minecraftVersion")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
}

val acceptance: SourceSet = sourceSets.create("acceptance")
val acceptanceTest: SourceSet =
    sourceSets.create("acceptanceTest") {
        compileClasspath += acceptance.output + acceptance.compileClasspath + sourceSets["main"].output
        runtimeClasspath += output + acceptance.runtimeClasspath + compileClasspath
    }
val mainSourceSet = sourceSets.getByName("main")
// 验收控制通道常量由 build-conventions.platform 生成（生成内容与原内联实现逐字节一致）
platformLane {
    mcVersion.set(minecraftVersion)
    channelName.set(acceptanceChannel)
    channelPackage.set("top.wcpe.mc.mpmt.platform.bukkit.acceptance")
    channelClass.set("BukkitAcceptanceControlChannelId")
}

val apiVerification =
    configurations.create("apiVerification") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }

acceptance.compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
acceptance.runtimeClasspath += mainSourceSet.output + mainSourceSet.runtimeClasspath

configurations["acceptanceTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["acceptanceTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    add(apiVerification.name, apiCoordinate)
    implementation(project(":platform:bukkit:common"))
    implementation(project(":platform:bukkit:modern"))
    compileOnly(apiCoordinate)
    testImplementation(apiCoordinate)
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.github.seeseemelk:MockBukkit-v1.20:3.88.1")

    add(acceptance.compileOnlyConfigurationName, apiCoordinate)
    // 产品入口仅 compileOnly：运行期由已加载的产品插件提供，禁止 shade 进验收 jar
    add(acceptance.compileOnlyConfigurationName, project(":platform:bukkit:common"))
    add(acceptance.compileOnlyConfigurationName, project(":platform:bukkit:modern"))
    add(acceptance.implementationConfigurationName, project(":modules:acceptance"))
    add(acceptance.implementationConfigurationName, project(":core:protocol"))
    add(acceptance.implementationConfigurationName, project(":core:server"))
    add(acceptance.implementationConfigurationName, project(":core:client"))

    "acceptanceTestImplementation"(project(":modules:acceptance"))
    "acceptanceTestImplementation"(project(":core:protocol"))
    "acceptanceTestImplementation"(project(":core:server"))
    "acceptanceTestImplementation"(project(":core:client"))
    "acceptanceTestImplementation"(platform("org.junit:junit-bom:5.10.3"))
    "acceptanceTestImplementation"("org.junit.jupiter:junit-jupiter")
    "acceptanceTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

val toolchains = extensions.getByType(JavaToolchainService::class.java)
tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        toolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        },
    )
    options.encoding = "UTF-8"
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

val acceptanceContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "运行 Bukkit acceptance v2 与完整默认轨场景契约测试"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    useJUnitPlatform()
    javaLauncher.set(
        toolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        },
    )
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
        val l4Prefix = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_"
        must(product.file.name.contains(minecraftVersion), "产品产物名未包含 MC 版本")
        must(acceptanceFile.file.name.contains(minecraftVersion), "验收产物名未包含 MC 版本")
        mustContain(product, adapterClassPath, "产品缺少选中的 L4 适配器")
        must(product.entries.count { it.startsWith(l4Prefix) && it.endsWith("BukkitVersionAdapter.class") } == 1, "产品包含零个或多个 L4 适配器")
        mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
        mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class", "现代产品缺少 Folia 调度类")
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
        mustNotContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "验收混入产品入口")
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
    dependsOn(
        tasks.named("shadowJar"),
        acceptance.classesTaskName,
        acceptanceContractTest,
        verifyPackaging,
        verifyApiSnapshotFreeze,
    )
}
tasks.named("check") {
    dependsOn(acceptanceContractTest)
}

val bukkitReportFile = layout.buildDirectory.file("acceptance/server-report.txt")
// SCHEDULER 矩阵报告默认旁路文件；-Pmpmt.acceptance.matrix=SCHEDULER 时门禁读此路径
val schedulerReportFile = layout.buildDirectory.file("acceptance/server-report-scheduler.txt")
val acceptanceMatrix =
    providers.gradleProperty("mpmt.acceptance.matrix").orElse("")
val autoHost =
    providers.gradleProperty("mpmt.realserver.autoHost").map { it == "true" }.orElse(false)

mpmtRealServerAcceptance {
    // Folia（SCHEDULER）与 Paper 默认报告分离，避免默认轨全量报告冒充矩阵报告
    reportFile.set(
        acceptanceMatrix.map { matrixId ->
            if (matrixId.equals("SCHEDULER", ignoreCase = true)) {
                schedulerReportFile.get()
            } else {
                bukkitReportFile.get()
            }
        },
    )
    laneId.set("Bukkit")
    matrix.set(acceptanceMatrix)
    autoStartPaperHost.set(autoHost)
    paperVersion.set(minecraftVersion)
    paperPort.set(
        providers.gradleProperty("mpmt.realserver.port").map { it.toInt() }.orElse(25599),
    )
    pluginJar.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    acceptanceDriverJar.set(acceptanceJar.flatMap { it.archiveFile })
    clientTaskName.set("runAcceptanceClient")
    extraDependsOn.set(listOf("shadowJar", "acceptanceJar"))
}

tasks.named("runRealServerAcceptance") {
    group = "verification"
    description =
        "Bukkit $minecraftVersion realserver 门禁" +
        "（-Pmpmt.realserver.autoHost=true 时接线 PaperHostService；" +
        "-Pmpmt.acceptance.matrix=SCHEDULER 时读 server-report-scheduler.txt）"
    dependsOn(tasks.named("shadowJar"), acceptanceJar, "verifyMpmtAcceptanceReport")
}

tasks.register("ensurePaperRealServerHost") {
    group = "verification"
    description =
        "PaperHostService.ensureStarted（须 -Pmpmt.realserver.autoHost=true）"
    dependsOn(tasks.named("shadowJar"), acceptanceJar)
    outputs.upToDateWhen { false }
    doLast {
        if (!autoHost.get()) {
            throw GradleException("请加 -Pmpmt.realserver.autoHost=true 启用 BuildService 起宿主")
        }
        val reg =
            gradle.sharedServices.registrations.findByName("mpmtPaperHostService")
                ?: throw GradleException("未注册 mpmtPaperHostService")

        @Suppress("UNCHECKED_CAST")
        val service =
            reg.service.get() as top.wcpe.mc.mpmt.gradle.realserver.PaperHostService
        service.ensureStarted()
        val port = providers.gradleProperty("mpmt.realserver.port").orElse("25599").get()
        logger.lifecycle(
            "[realserver] Paper 已就绪 port=$port；" +
                "请另终端跑 Fabric 验收客户端连入",
        )
    }
}
