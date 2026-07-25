import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipFile

// Bukkit 1.20.1 独立产品工程：common + modern + v1_20 → mpmt-bukkit-1.20.1-*.jar

plugins {
    java
    id("com.gradleup.shadow") version "8.3.3"
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
val generatedAcceptanceSources = layout.buildDirectory.dir("generated/sources/acceptance/java")
acceptance.java.srcDir(generatedAcceptanceSources)

val generateAcceptanceChannelId by tasks.registering {
    group = "build"
    description = "生成验收控制通道常量（MC $minecraftVersion）"
    inputs.property("acceptanceChannel", acceptanceChannel)
    outputs.dir(generatedAcceptanceSources)
    doLast {
        val packageDir =
            generatedAcceptanceSources
                .get()
                .asFile
                .resolve("top/wcpe/mc/mpmt/platform/bukkit/acceptance")
        packageDir.mkdirs()
        packageDir.resolve("BukkitAcceptanceControlChannelId.java").writeText(
            """
            package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

            /** 构建期生成的验收控制通道（MC $minecraftVersion）。 */
            public final class BukkitAcceptanceControlChannelId {

                /** 当前目标版本的验收控制通道。 */
                public static final String CHANNEL = "$acceptanceChannel";

                private BukkitAcceptanceControlChannelId() {
                }
            }
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
        )
    }
}
tasks.named<JavaCompile>(acceptance.compileJavaTaskName) {
    dependsOn(generateAcceptanceChannelId)
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
    description = "运行 Bukkit acceptance v2 与完整 P1 场景契约测试"
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
    doLast {
        val product = tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile
        val acceptanceFile = acceptanceJar.get().archiveFile.get().asFile
        val adapterService =
            "META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter"

        fun entries(file: File): Set<String> =
            ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

        fun entryText(file: File, name: String): String =
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(name) ?: throw GradleException("${file.name} 缺少 $name")
                zip.getInputStream(entry).use { input ->
                    String(input.readBytes(), StandardCharsets.UTF_8)
                }
            }

        fun must(condition: Boolean, message: String) {
            if (!condition) throw GradleException("Bukkit 打包校验失败：$message")
        }

        val productEntries = entries(product)
        val acceptanceEntries = entries(acceptanceFile)
        val productMetadata = entryText(product, "plugin.yml")
        val acceptanceMetadata = entryText(acceptanceFile, "plugin.yml")
        val l4Prefix = "top/wcpe/mc/mpmt/platform/bukkit/version/v1_"

        must(product.name.contains(minecraftVersion), "产品产物名未包含 MC 版本")
        must(acceptanceFile.name.contains(minecraftVersion), "验收产物名未包含 MC 版本")
        must(productEntries.contains(adapterClassPath), "产品缺少选中的 L4 适配器")
        must(
            productEntries.count {
                it.startsWith(l4Prefix) && it.endsWith("BukkitVersionAdapter.class")
            } == 1,
            "产品包含零个或多个 L4 适配器",
        )
        must(
            entryText(product, adapterService).trim() == adapterClass,
            "产品 adapter services 与目标不符",
        )
        must(productEntries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "产品未 shade 核心")
        must(productEntries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "产品未 shade SPI")
        must(productEntries.contains("top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class"), "产品缺少入口")
        must(
            productEntries.contains(
                "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class",
            ),
            "现代产品缺少 Folia 调度类",
        )
        must(
            productEntries.none { it.startsWith("top/wcpe/mc/mpmt/platform/bukkit/acceptance/") },
            "产品混入 acceptance",
        )
        must(
            acceptanceEntries.contains(
                "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class",
            ),
            "验收缺少入口",
        )
        must(
            !acceptanceEntries.contains("top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class"),
            "验收混入产品入口",
        )
        must(
            productEntries.none { it.startsWith("org/bukkit/") || it.startsWith("io/papermc/") },
            "产品误打入 Bukkit/Paper API",
        )
        must(
            productEntries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") },
            "产品 snakeyaml 未 relocate",
        )
        must(productMetadata.contains("main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin"), "产品 metadata 入口错误")
        must(productMetadata.contains("folia-supported: true"), "现代产品缺少 folia 字段")
        must(productMetadata.contains("api-version: '$apiVersion'"), "产品 api-version 错误")
        must(acceptanceMetadata.contains("MpmtBukkitAcceptancePlugin"), "验收 metadata 入口错误")
        must(
            productEntries.contains(
                "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap",
            ),
            "缺少 PlatformBootstrap services",
        )
        logger.lifecycle(
            "Bukkit $minecraftVersion 打包校验通过：产品=${product.name}，验收=${acceptanceFile.name}",
        )
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
    description =
        "Bukkit $minecraftVersion realserver 门禁" +
            "（-Pmpmt.realserver.autoHost=true 时接线 PaperHostService）"
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
