import buildconventions.frozenApiSnapshot
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources

// Bukkit 1.12.2 独立产品工程：common + v1_12 适配器 → mpmt-bukkit-1.12.2-*.jar

plugins {
    id("build-conventions.quality")
    id("build-conventions.platform")
    java
    id("com.gradleup.shadow") version "8.3.11"
    id("top.wcpe.mc.mpmt.realserver-acceptance")
}

group = "top.wcpe.mc.mpmt"
version = rootProject.file("VERSION").readText().trim()

val minecraftVersion = "1.12.2"
val apiCoordinate = "org.spigotmc:spigot-api:1.12.2-R0.1-SNAPSHOT"
val apiSha256 = "22ca0ff290aa2d3066348d623e9c8998e58a49f2fee91bc06e3de96b2544e909"
val targetJavaVersion = 8
val apiVersion = ""
val foliaMetadata = false
val productChannel = "MPMT"
val acceptanceChannel = "MPMTTEST"
val regionSchedulerClass = "top.wcpe.mc.mpmt.platform.bukkit.capability.BukkitSchedulerPort"
val adapterClass = "top.wcpe.mc.mpmt.platform.bukkit.version.v1_12.V1_12BukkitVersionAdapter"
val adapterClassPath = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_12/V1_12BukkitVersionAdapter.class"
val snakeyamlVersion = "2.2"

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
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "Spigot"
    }
    maven("https://oss.sonatype.org/content/repositories/snapshots/") {
        name = "SonatypeSnapshots"
    }
    maven("https://repo.md-5.net/content/repositories/snapshots/") {
        name = "md5Snapshots"
    }
}

val acceptance: SourceSet = sourceSets.create("acceptance")
val mainSourceSet = sourceSets.getByName("main")
val testSourceSet = sourceSets.getByName("test")
// 验收控制通道常量由 build-conventions.platform 生成（生成内容与原内联实现逐字节一致）
platformLane {
    mcVersion.set(minecraftVersion)
    channelName.set(acceptanceChannel)
    channelPackage.set("top.wcpe.mc.mpmt.platform.bukkit.acceptance")
    channelClass.set("BukkitAcceptanceControlChannelId")
}

acceptance.compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
acceptance.runtimeClasspath += mainSourceSet.output + mainSourceSet.runtimeClasspath

// 冻结 paper-api：插件负责解析配置与 SHA-256 校验，并把校验挂到编译任务之前
frozenApiSnapshot(
    laneLabel = "Bukkit",
    coordinate = apiCoordinate,
    expectedSha256 = apiSha256,
    mcVersion = minecraftVersion,
)

dependencies {
    implementation(project(":platform:bukkit:common"))
    compileOnly(apiCoordinate) {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    compileOnly(files(rootProject.file("platform/bukkit/third-party/bungeecord-chat-1.12-SNAPSHOT.jar")))
    testImplementation(apiCoordinate) {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    testImplementation(files(rootProject.file("platform/bukkit/third-party/bungeecord-chat-1.12-SNAPSHOT.jar")))
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    add(acceptance.compileOnlyConfigurationName, apiCoordinate) {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    add(
        acceptance.compileOnlyConfigurationName,
        files(rootProject.file("platform/bukkit/third-party/bungeecord-chat-1.12-SNAPSHOT.jar")),
    )
    // 产品入口仅 compileOnly：运行期由已加载的产品插件提供，禁止 shade 进验收 jar
    add(acceptance.compileOnlyConfigurationName, project(":platform:bukkit:common"))
    add(acceptance.implementationConfigurationName, project(":modules:acceptance"))
    add(acceptance.implementationConfigurationName, project(":core:protocol"))
    add(acceptance.implementationConfigurationName, project(":core:server"))
    add(acceptance.implementationConfigurationName, project(":core:client"))
}

val toolchains = extensions.getByType(JavaToolchainService::class.java)
tasks.withType<JavaCompile>().configureEach {
    javaCompiler.set(
        toolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
        },
    )
    options.encoding = "UTF-8"
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

val metadataProperties =
    mapOf(
        "version" to project.version,
        "apiVersionMetadata" to "",
        "foliaMetadata" to "",
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
    // 并入 common 的类与资源
    configurations = listOf(project.configurations.runtimeClasspath.get())
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    exclude("META-INF/maven/**")
    dependencies {
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
    systemProperty("mpmt.test.javaVersion", "1.8")
    systemProperty("mpmt.test.archiveName", "mpmt-bukkit-$minecraftVersion")
    systemProperty("mpmt.test.productChannel", productChannel)
    systemProperty("mpmt.test.acceptanceChannel", acceptanceChannel)
    systemProperty("mpmt.test.regionSchedulerClass", regionSchedulerClass)
    systemProperty("mpmt.test.apiVersion", apiVersion)
    systemProperty("mpmt.test.foliaMetadata", foliaMetadata)
    systemProperty(
        "mpmt.test.acceptanceMetadata",
        layout.buildDirectory.file("resources/acceptance/plugin.yml").get().asFile.absolutePath,
    )
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
        mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
        mustContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class", "验收缺少入口")
        mustNotContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "验收混入产品入口")
        mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
        mustMetadataContains(product, "plugin.yml", "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin", "产品 metadata 入口错误")
        must(!product.text("plugin.yml").contains("MpmtBukkitAcceptancePlugin"), "产品 metadata 混入验收入口")
        mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
        must(product.entries.none { it.endsWith("FoliaSchedulerPort.class") }, "1.12 产品混入 Folia 类")
        must(!product.text("plugin.yml").contains("folia-supported:"), "1.12 产品不得含 folia 字段")
        must(!product.text("plugin.yml").contains("api-version:"), "1.12 产品不得含 api-version")
        mustContain(product, "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap", "缺少 PlatformBootstrap services")
        log("Bukkit $minecraftVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
    }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}
tasks.named("build") {
    dependsOn(tasks.named("shadowJar"), acceptance.classesTaskName, verifyPackaging, "verifyApiSnapshotFreeze")
}

// realserver 门禁：1.12 车道默认不接 PaperHost
val bukkitReportFile = layout.buildDirectory.file("acceptance/server-report.txt")
val autoHost =
    providers.gradleProperty("mpmt.realserver.autoHost").map { it == "true" }.orElse(false)

mpmtRealServerAcceptance {
    reportFile.set(bukkitReportFile)
    laneId.set("Bukkit")
    matrix.set(providers.gradleProperty("mpmt.acceptance.matrix").orElse(""))
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
    description = "Bukkit $minecraftVersion realserver 门禁"
    dependsOn(tasks.named("shadowJar"), acceptanceJar, "verifyMpmtAcceptanceReport")
}
