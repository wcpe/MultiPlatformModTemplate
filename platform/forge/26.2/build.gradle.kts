import buildconventions.acceptanceReportFrom
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.DataInputStream
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    id("build-conventions.quality")
    id("java")
    // EGT 迁移（ADR-0025 后续）： Forge 26.2 改用本地 wcpe loom fork（architectury-loom 系，
    // 为无混淆 Forge 补齐 mcp merge 预补丁管线）；版本由 settings pluginManagement 钉在 mavenLocal。
    id("top.wcpe.loom-no-remap")
}

// MC 26.2 的 loom 配置期要求守护 JVM 25（loom 对 MC 26.2 有版本硬校验）
if (JavaVersion.current().majorVersion.toInt() < 25) {
    throw GradleException("Forge 26.2 配置期必须使用 Java 25 及以上，当前为 ${System.getProperty("java.version")}")
}

// 本工程为根构建的普通子模块（platform/forge/26.2）；group / version 由根 allprojects 统一提供。

val minecraftVersion = "26.2"
val forgeVersion = "26.2-65.0.9"
val loomVersion = "1.17.1"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"

// 共享模块已为根构建子模块：按工程路径取各模块 jar 任务产物（FileCollection，自带任务依赖）
fun moduleJar(projectPath: String): FileCollection =
    files(project(projectPath).tasks.withType<Jar>().matching { it.name == "jar" })

// 共享模块（L0-L2）项目路径；platform-spi 项目名为 core:spi；acceptance 仅进验收伴侣，不进产品
val productSharedProjects =
    listOf(
        ":core:domain",
        ":core:runtime",
        ":core:client",
        ":core:server",
        ":core:protocol",
        ":core:spi",
    )
val acceptanceSharedProjects = productSharedProjects + listOf(":modules:acceptance")

base {
    archivesName.set("mpmt-forge-26.2")
}

java {
    toolchain {
        // MC 26.2 运行时要求 Java 25
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

val productBundle: Configuration by configurations.creating
val acceptanceBundle: Configuration by configurations.creating

sourceSets.named("main") {
    // 单版本内 common / server / client 分目录（服客分离）
    java.setSrcDirs(
        listOf(
            "common/src/main/java",
            "server/src/main/java",
            "client/src/main/java",
        ),
    )
    resources.setSrcDirs(listOf("common/src/main/resources"))
}

val acceptance: SourceSet by sourceSets.creating {
    java.setSrcDirs(listOf("src/acceptance/java"))
    resources.setSrcDirs(listOf("src/acceptance/resources"))
    compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
    // FG6 MinecraftRunTask 会把各 sourceSet.runtimeClasspath 整表塞进模块层。
    // 若此处再拼 compileClasspath，compileOnly 共享 jar（如 acceptance-0.1.0.jar）
    // 会以自动模块名与 SecureJar 内嵌同名包冲突（split package）。
    // 运行期只靠：本源集输出（含 embed）+ main 输出（含产品共享 embed）+ main 运行时。
    runtimeClasspath = output + sourceSets["main"].output + sourceSets["main"].runtimeClasspath
}

val contractTest: SourceSet by sourceSets.creating {
    java.setSrcDirs(listOf("src/contractTest/java"))
    resources.setSrcDirs(listOf("src/contractTest/resources"))
    compileClasspath += sourceSets["main"].output + acceptance.output + sourceSets["test"].compileClasspath
    runtimeClasspath += output + compileClasspath
}

// FG6 mapModClassesToGradle 会把每个 sourceSet 的 resourcesDir 与 classesDirs
// 拼成两条 SecureJar 路径；1.21 FML 对“仅有 mods.toml、无 @Mod 类”的路径直接 FATAL
// （constructed 0 mods）。ModConfig 也只有 source/sources，没有 file DSL——
// 之前 file(archiveFile) 被 Groovy 静默忽略，仍落到分目录输出。
// 将 resources 输出合并进 classes 目录，使 source_roots 每 mod 只剩一条路径。
// 同时：共享 JAR 只在 packaging 时 shade，dev classpath 又不被 FML 模块层暴露给 mod，
// 故把 product/acceptance 共享内容同步进对应 classes 目录，让 SecureJar 内自洽。
listOf(sourceSets["main"], acceptance).forEach { ss ->
    val classesDir: File = ss.java.destinationDirectory.get().asFile
    ss.output.setResourcesDir(classesDir)
}

// acceptance 编译需要 main 的 compileOnly 共享类；不 extends implementation（已无 runtime 共享 jar）
configurations["acceptanceCompileOnly"].extendsFrom(configurations["compileOnly"])
configurations["contractTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["contractTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

repositories {
    // loom：Mojang libraries 由 loom 自动注入；这里补 Forge 制品（universal/userdev/binarypatcher 等）与常规仓库
    mavenCentral()
    maven { url = uri("https://maven.minecraftforge.net/") }
}

dependencies {
    // arch-loom 三段式声明：原版 MC 本体（26.2 无混淆、无官方 mappings，走 disableObfuscation 管线）
    // + Forge（arch-loom 自行解析 userdev）；共享 JAR 消费与 FG7 时代保持一致
    minecraft("com.mojang:minecraft:$minecraftVersion")
    "forge"("net.minecraftforge:forge:$forgeVersion")

    // 共享 JAR 仅 compileOnly：FML 模块层会把 runtime 上的 jar 当自动模块，
    // 与 SecureJar 内嵌同名包冲突（split package，如 acceptance vs mpmt_acceptance）。
    // 运行期由 embed* / jar shade 把类放进 mod 目录，不进 module path。
    add("compileOnly", files(productSharedProjects.map { moduleJar(it) }))
    add("productBundle", files(productSharedProjects.map { moduleJar(it) }))

    add("acceptanceCompileOnly", files(acceptanceSharedProjects.map { moduleJar(it) }))
    add("acceptanceBundle", files(moduleJar(":modules:acceptance")))

    add("testImplementation", files(acceptanceSharedProjects.map { moduleJar(it) }))
    add("testImplementation", platform("org.junit:junit-bom:5.10.3"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

tasks.named<ProcessResources>("processAcceptanceResources") {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand("version" to project.version)
    }
}

val archiveExcludes =
    listOf(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "META-INF/MANIFEST.MF",
        "META-INF/maven/**",
        "module-info.class",
    )

tasks.named<Jar>("jar") {
    // disableObfuscation 下 loom 不注册 remapJar（NonRemappedJarTaskConfiguration）：
    // jar 任务保持默认输出 build/libs/<name>-<v>.jar，自身即权威产品 jar（loom 仅追加清单服务）。
    from(provider { configurations["productBundle"].map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveExcludes.forEach { exclude(it) }
    exclude("top/wcpe/mc/mpmt/acceptance/**")
    manifest {
        attributes(
            "Implementation-Title" to "MPMT Forge 26.2 产品",
            "Implementation-Version" to project.version,
        )
    }
}

val acceptanceJar =
    tasks.register<Jar>("acceptanceJar") {
        group = "build"
        description = "构建独立 Forge 26.2 验收 mod JAR"
        archiveBaseName.set("mpmt-forge-acceptance-26.2")
        archiveClassifier.set("")
        from(acceptance.output)
        from(provider { configurations["acceptanceBundle"].map { zipTree(it) } })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/core/**")
        exclude("top/wcpe/mc/mpmt/protocol/**")
        exclude("top/wcpe/mc/mpmt/platform/spi/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/client/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/net/**")
        manifest {
            attributes(
                "Implementation-Title" to "MPMT Forge 26.2 验收伴侣",
                "Implementation-Version" to project.version,
            )
        }
    }

val realServerHostRequested = gradle.startParameter.taskNames.any { it.endsWith("runRealServerAcceptanceHost") }

// 独立车道复用仓库统一的静态质量规则；不依赖或回调根 Gradle。
apply(plugin = "checkstyle")
configure<CheckstyleExtension> {
    toolVersion = "10.17.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
apply(plugin = "pmd")
configure<PmdExtension> {
    toolVersion = "7.16.0"
    isConsoleOutput = true
    ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
    ruleSets = emptyList()
    isIgnoreFailures = false
}
apply(plugin = "jacoco")
tasks.withType<JacocoReport>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
configure<SpotBugsExtension> {
    toolVersion.set("4.9.8")
    ignoreFailures.set(false)
    effort.set(Effort.valueOf("MAX"))
    reportLevel.set(Confidence.valueOf("MEDIUM"))
    excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}
dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
// lombok.config 由根构建 subprojects{} 统一登记为编译输入（ADR-0026），此处不再重复。
val analysisLauncher =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
tasks.withType<Checkstyle>().configureEach {
    javaLauncher.set(analysisLauncher)
}
tasks.withType<Pmd>().configureEach {
    javaLauncher.set(analysisLauncher)
}
tasks.withType<SpotBugsTask>().configureEach {
    if (name != "spotbugsMain") {
        ignoreFailures = true
    }
}
val staticQualityTasks =
    listOf(
        "checkstyleMain",
        "pmdMain",
        "spotbugsMain",
        "ktlintCheck",
        "detekt",
    )
val acceptanceServerRunDirectory = if (realServerHostRequested) "run-realserver" else "run-acceptance-server"

loom {
    runs {
        // 配置名决定任务名：acceptanceServer -> runAcceptanceServer（主类由 forge 运行模板注入，
        // userdev config runs.main = net.minecraftforge.bootstrap.ForgeBootstrap）。
        // source 指定任务 classpath 基底；mods 声明经 ForgeModClassesService 写入 MOD_CLASSES
        // （等价 FG7 时代 dev SecureJar 的 source_roots 语义）。
        create("acceptanceServer") {
            server()
            configName = "Acceptance Server"
            source(acceptance)
            runDir(acceptanceServerRunDirectory)
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", project.file("$acceptanceServerRunDirectory/acceptance-report.txt").absolutePath)
            // 允许 -Pmpmt.acceptance.deadlineMs 覆盖；默认 600s 覆盖慢机冷启动
            property("mpmt.acceptance.deadlineMs", (project.findProperty("mpmt.acceptance.deadlineMs") ?: "600000").toString())
            // loom 的 forge server 模板已自带 nogui 程序参数，无需（不可）重复声明
            mods {
                create("mpmt") {
                    sourceSet(sourceSets["main"])
                }
                create("mpmt_acceptance") {
                    sourceSet(acceptance)
                }
            }
        }
        create("acceptanceClient") {
            client()
            configName = "Acceptance Client"
            source(acceptance)
            runDir("run-acceptance-client")
            property("forge.logging.console.level", "info")
            // quickPlay 作辅；主路径由伴侣 tryAutoConnect 读系统属性 mpmt.acceptance.server
            programArgs("--quickPlayMultiplayer", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
            mods {
                create("mpmt") {
                    sourceSet(sourceSets["main"])
                }
                create("mpmt_acceptance") {
                    sourceSet(acceptance)
                }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:all")
}

// 把共享模块解压进 dev SecureJar 路径（与 jar/acceptanceJar 的 shade 内容对齐）。
// 必须在任何消费 main.output / acceptance.output 的编译任务之前完成，
// 否则 Gradle 会报 implicit dependency（embed 写目录、compileAcceptance 读目录）。
val embedProductSharedIntoMain =
    tasks.register<Copy>("embedProductSharedIntoMain") {
        group = "build"
        description = "将产品共享 JAR 嵌入 main classes，供 FG6 SecureJar 发现 @Mod 与依赖类"
        dependsOn(tasks.named("classes"), tasks.named("processResources"))
        from(provider { configurations["productBundle"].map { zipTree(it) } })
        into(sourceSets["main"].java.destinationDirectory)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/acceptance/**")
    }

val embedAcceptanceSharedIntoAcceptance =
    tasks.register<Copy>("embedAcceptanceSharedIntoAcceptance") {
        group = "build"
        description = "将验收共享 JAR 嵌入 acceptance classes，供 FG6 SecureJar 加载验收伴侣"
        dependsOn(tasks.named("acceptanceClasses"), tasks.named("processAcceptanceResources"))
        from(provider { configurations["acceptanceBundle"].map { zipTree(it) } })
        into(acceptance.java.destinationDirectory)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/core/**")
        exclude("top/wcpe/mc/mpmt/protocol/**")
        exclude("top/wcpe/mc/mpmt/platform/spi/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/client/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/net/**")
    }

val prepareDevModOutputs =
    tasks.register("prepareDevModOutputs") {
        group = "build"
        description = "准备 FG7 runServer/runClient 所需的合并 SecureJar 目录"
        dependsOn(embedProductSharedIntoMain, embedAcceptanceSharedIntoAcceptance)
    }

// 凡读取 main.output / acceptance.output 的任务都必须显式依赖 embed，
// 否则 Gradle 8 隐式依赖校验会在 build 全量门禁失败（compileTestJava 等）。
tasks.named("compileAcceptanceJava") { dependsOn(embedProductSharedIntoMain) }
tasks.named("compileTestJava") { dependsOn(embedProductSharedIntoMain) }
tasks.named("compileContractTestJava") { dependsOn(prepareDevModOutputs) }
tasks.named("jar") { dependsOn(embedProductSharedIntoMain) }
acceptanceJar.configure { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("test") { dependsOn(prepareDevModOutputs) }
tasks.named("checkstyleMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("pmdMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("spotbugsMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("checkstyleAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("pmdAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("spotbugsAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }

fun classMajor(zip: ZipFile, name: String): Int {
    val input = DataInputStream(zip.getInputStream(zip.getEntry(name)))
    try {
        if (input.readInt() != 0xCAFEBABE.toInt()) {
            throw GradleException("类文件魔数错误：$name")
        }
        input.readUnsignedShort()
        return input.readUnsignedShort()
    } finally {
        input.close()
    }
}

fun zipEntries(zip: ZipFile): List<ZipEntry> = zip.entries().iterator().asSequence().toList()

val verifyPackaging =
    tasks.register("verifyPackaging") {
        group = "verification"
        description = "校验产品与验收 JAR 隔离、入口、共享核心和 Java 25 平台字节码"
        // 产品 jar 即 loom jar 任务输出（26.2 无混淆无 remapJar，见 NonRemappedJarTaskConfiguration），
        // jar 依赖 embed 链；disableObfuscation 下 loom 不接管 jar 产物路径。
        dependsOn(tasks.named("jar"), acceptanceJar)
        doLast {
            val product = tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val acceptanceJarFile = acceptanceJar.get().archiveFile.get().asFile
            val productZip = ZipFile(product)
            val acceptanceZip = ZipFile(acceptanceJarFile)

            fun must(condition: Boolean, message: String) {
                if (!condition) {
                    throw GradleException("Forge 26.2 打包校验失败：$message")
                }
            }
            try {
                must(productZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class") != null, "产品缺少 mod 入口")
                must(productZip.getEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class") != null, "产品未 shade core-server")
                must(productZip.getEntry("top/wcpe/mc/mpmt/core/client/ClientNetworkFeature.class") != null, "产品未 shade core-client")
                must(productZip.getEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class") != null, "产品未 shade protocol")
                must(zipEntries(productZip).none { it.name.startsWith("top/wcpe/mc/mpmt/acceptance/") }, "产品误包含 acceptance 核心")
                must(acceptanceZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge262AcceptanceMod.class") != null, "验收 JAR 缺少入口")
                must(acceptanceZip.getEntry("top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class") != null, "验收 JAR 未包含 acceptance 核心")
                must(zipEntries(acceptanceZip).none { it.name.startsWith("top/wcpe/mc/mpmt/core/") }, "验收 JAR 重复包含 core")
                must(zipEntries(acceptanceZip).none { it.name.startsWith("top/wcpe/mc/mpmt/protocol/") }, "验收 JAR 重复包含 protocol")
                must(acceptanceZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class") == null, "验收 JAR 重复包含产品入口")
                zipEntries(productZip)
                    .filter {
                        it.name.startsWith("top/wcpe/mc/mpmt/platform/forge/modern/") && it.name.endsWith(".class")
                    }.forEach { entry ->
                        must(classMajor(productZip, entry.name) == 69, "产品平台类不是 Java 25：${entry.name}")
                    }
                zipEntries(acceptanceZip)
                    .filter {
                        it.name.startsWith("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/") && it.name.endsWith(".class")
                    }.forEach { entry ->
                        must(classMajor(acceptanceZip, entry.name) == 69, "验收平台类不是 Java 25：${entry.name}")
                    }
            } finally {
                productZip.close()
                acceptanceZip.close()
            }
        }
    }

val packageArtifacts =
    tasks.register("packageArtifacts") {
        group = "build"
        description = "打包并校验 Forge 26.2 产品与验收 JAR"
        dependsOn(verifyPackaging, staticQualityTasks)
    }

val java25Launcher =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher.set(java25Launcher)
}

tasks.named<Test>("test") {
    systemProperty("mpmt.test.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("mpmt.test.projectDir", projectDir.absolutePath)
    systemProperty("mpmt.test.minecraftVersion", minecraftVersion)
    systemProperty("mpmt.test.forgeVersion", forgeVersion)
    systemProperty("mpmt.test.loomVersion", loomVersion)
    systemProperty("mpmt.test.gradleVersion", gradle.gradleVersion)
}

tasks.register<JavaExec>("contractTest") {
    group = "verification"
    description = "校验 Forge 26.2 冻结矩阵、双 JAR 隔离与 class major"
    dependsOn(tasks.named("contractTestClasses"), packageArtifacts)
    classpath = sourceSets["contractTest"].runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.forge.modern.contract.Forge262ContractMain")
    javaLauncher.set(java25Launcher)
    systemProperty("mpmt.test.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("mpmt.test.projectDir", projectDir.absolutePath)
    systemProperty("mpmt.test.minecraftVersion", minecraftVersion)
    systemProperty("mpmt.test.forgeVersion", forgeVersion)
    systemProperty("mpmt.test.loomVersion", loomVersion)
    systemProperty("mpmt.test.gradleVersion", gradle.gradleVersion)
    systemProperty("mpmt.test.productJar", tasks.named<Jar>("jar").flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("mpmt.test.acceptanceJar", acceptanceJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
}

fun requiredRunProperty(name: String): String {
    val value = project.findProperty(name)
    if (value == null || value.toString().isEmpty()) {
        throw GradleException("缺少运行属性 -P$name")
    }
    return value.toString()
}

fun configureReportProperties(task: JavaExec) {
    val product = tasks.named<Jar>("jar").get().archiveFile.get().asFile
    val acceptanceJarFile = acceptanceJar.get().archiveFile.get().asFile
    // server-runtime 与其余角色同口径（镜像 fabric 26.2 的 artifactOrProduct）：
    // 显式传入时必须是真实文件（真实专用服路径），未传入则回落产品 jar——dev 编排起的是 loom dev 服务端，
    // 其"运行期制品"即产品 jar；否则 dev 编排会被迫依赖一个不存在的外部服务端 jar。
    val serverRuntimeProperty = project.findProperty("mpmt.acceptance.artifact.server-runtime")
    val serverRuntime =
        if (serverRuntimeProperty == null || serverRuntimeProperty.toString().isEmpty()) {
            product
        } else {
            file(serverRuntimeProperty.toString())
        }
    if (!serverRuntime.isFile) {
        throw GradleException("server-runtime 必须是调用方显式提供的实际文件：$serverRuntime")
    }
    task.systemProperty("mpmt.acceptance.runId", requiredRunProperty("mpmt.acceptance.runId"))
    task.systemProperty("mpmt.acceptance.matrix", requiredRunProperty("mpmt.acceptance.matrix"))
    task.systemProperty("mpmt.acceptance.startEpochMs", requiredRunProperty("mpmt.acceptance.startEpochMs"))
    task.systemProperty("mpmt.acceptance.javaExecutable", java25Launcher.get().executablePath.asFile.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-runtime", serverRuntime.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-product", product.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.server-acceptance", acceptanceJarFile.absolutePath)
    task.systemProperty("mpmt.acceptance.artifact.client-product", (project.findProperty("mpmt.acceptance.artifact.client-product") ?: product.absolutePath).toString())
    task.systemProperty("mpmt.acceptance.artifact.client-acceptance", (project.findProperty("mpmt.acceptance.artifact.client-acceptance") ?: acceptanceJarFile.absolutePath).toString())
}

fun installDevAcceptanceMod(runDir: File) {
    val modsDir = File(runDir, "mods")
    fileTree(modsDir) {
        include("mpmt-*.jar")
    }.files.forEach { candidate ->
        if (!candidate.delete()) {
            throw GradleException("无法删除旧版 MPMT 验收 JAR：$candidate")
        }
    }
    copy {
        from(acceptanceJar.flatMap { it.archiveFile })
        into(modsDir)
    }
}

fun prepareAcceptanceServerProperties(runDir: File) {
    val propertiesFile = File(runDir, "server.properties")
    val offlineProperties = mapOf("online-mode" to "false", "enforce-secure-profile" to "false")
    val lines: List<String> = if (propertiesFile.isFile) propertiesFile.readLines(Charsets.UTF_8) else emptyList()
    val updatedKeys = mutableSetOf<String>()
    val updatedLines: MutableList<String> =
        lines
            .map { line ->
                val separator = line.indexOf('=')
                val key = if (separator > 0) line.substring(0, separator) else ""
                val offlineValue = offlineProperties[key]
                if (offlineValue != null) {
                    updatedKeys.add(key)
                    "$key=$offlineValue"
                } else {
                    line
                }
            }.toMutableList()
    offlineProperties.forEach { (key, value) ->
        if (!updatedKeys.contains(key)) {
            updatedLines.add("$key=$value")
        }
    }
    runDir.mkdirs()
    propertiesFile.writeText(updatedLines.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
}

tasks.matching { it.name == "runAcceptanceServer" }.configureEach {
    // packageArtifacts 供报告属性取 jar 路径；prepareDevModOutputs 供 MOD_CLASSES 实际加载
    dependsOn(packageArtifacts, prepareDevModOutputs)
    if (this is JavaExec) {
        val javaExec = this
        javaLauncher.set(java25Launcher)
        doFirst {
            val runDir = project.file(acceptanceServerRunDirectory)
            if (realServerHostRequested) {
                File(runDir, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
            }
            prepareAcceptanceServerProperties(runDir)
            installDevAcceptanceMod(runDir)
            configureReportProperties(javaExec)
            val mainDir = sourceSets["main"].java.destinationDirectory.get().asFile
            val acceptanceDir = acceptance.java.destinationDirectory.get().asFile
            val mainToml = File(mainDir, "META-INF/mods.toml")
            val acceptanceToml = File(acceptanceDir, "META-INF/mods.toml")
            val mainModClass = File(mainDir, "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
            val acceptanceModClass =
                File(acceptanceDir, "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge262AcceptanceMod.class")
            val coreClass = File(mainDir, "top/wcpe/mc/mpmt/core/runtime/MpmtRuntime.class")
            if (!mainToml.isFile || !mainModClass.isFile || !coreClass.isFile) {
                throw GradleException("dev 产品 SecureJar 目录不完整：$mainDir")
            }
            if (!acceptanceToml.isFile || !acceptanceModClass.isFile) {
                throw GradleException("dev 验收 SecureJar 目录不完整：$acceptanceDir")
            }
        }
    }
}

tasks.matching { it.name == "runAcceptanceClient" }.configureEach {
    dependsOn(packageArtifacts, prepareDevModOutputs)
    if (this is JavaExec) {
        val javaExec = this
        javaLauncher.set(java25Launcher)
        doFirst {
            installDevAcceptanceMod(project.file("run-acceptance-client"))
            javaExec.systemProperty("mpmt.acceptance.javaExecutable", java25Launcher.get().executablePath.asFile.absolutePath)
            // 伴侣自动连服地址（含端口）；缺省与 quickPlay 一致
            javaExec.systemProperty("mpmt.acceptance.server", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
        }
    }
}

fun forge262ReportFile(): File =
    acceptanceReportFrom(
        "run-realserver/acceptance-report.txt",
        "run-acceptance-server/acceptance-report.txt",
    )

val verifyAcceptanceReport =
    tasks.register("verifyAcceptanceReport") {
        group = "verification"
        description = "校验 Forge 26.2 真服/验收报告末行 RESULT PASS"
        doLast {
            val report = forge262ReportFile()
            if (!report.isFile) {
                throw GradleException(
                    "未找到 Forge 26.2 验收报告：${report.absolutePath}\n" +
                        "先跑 runServer + runClient，或提供 server-runtime 后跑 runRealServerAcceptanceHost + 客户端伴侣。",
                )
            }
            val lines = report.readText(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) {
                throw GradleException("验收报告为空：${report.absolutePath}")
            }
            val last = lines.last()
            if (!last.startsWith("RESULT ")) {
                throw GradleException("验收报告末行不是 RESULT 行：${report.absolutePath}\n$last")
            }
            if (last != "RESULT PASS") {
                throw GradleException(
                    "验收未通过（$last）：${report.absolutePath}\n—— 报告全文 ——\n${report.readText(Charsets.UTF_8)}",
                )
            }
            logger.lifecycle("[realserver] Forge 26.2 报告 PASS：${report.absolutePath}")
        }
    }

// 进程命令行复刻 gradle JavaExec 启动（镜像 fabric launchAcceptanceProcess）：
// jvmArguments = @argfile（classpath）+ internal vmArgs（-Dfabric.dli.* 与全部 -D 属性）；
// 本轮系统属性去重后重注，保证编排注入值覆盖 loom 配置期默认值。
fun launchAcceptanceProcess(task: JavaExec, logFile: File, workDir: File): Process {
    val command: MutableList<Any?> = mutableListOf(task.javaLauncher.get().executablePath.asFile.absolutePath)
    val systemPropertyPrefixes = task.systemProperties.keys.map { "-D$it=" }
    // 必须用 allJvmArgs（镜像 fabric lane）：Windows 下长类路径由 Gradle 写成 argfile，
    // 该 @argfile 只出现在 allJvmArgs 里、不在 jvmArguments 中；漏掉它会导致 java 拿不到类路径，
    // 报 "找不到或无法加载主类 net.fabricmc.devlaunchinjector.Main"。
    command.addAll(task.allJvmArgs.filter { arg -> arg != null && systemPropertyPrefixes.none { arg.startsWith(it) } })
    command.addAll(task.systemProperties.map { (key, value) -> "-D$key=$value" })
    command.addAll(listOf(task.mainClass.get()))
    command.addAll(task.args)
    task.argumentProviders.forEach { provider -> command.addAll(provider.asArguments()) }
    // ProcessBuilder(List<String>) 会做一次底层 arraycopy；执行期由 argumentProviders 惰性产出的
    // 参数可能不是 String（如 File / Path），此时抛 ArrayStoreException：
    // "arraycopy: element type mismatch ... to java.lang.String"。
    // 命令行元素统一按其字符串形式归一，并打印非 String 元素类型以便定位。
    val nonStringTypes = command.filterNotNull().filter { it !is String }.map { it.javaClass.name }.distinct()
    if (nonStringTypes.isNotEmpty()) {
        logger.lifecycle("[realserver] ${task.name} 命令行含非 String 参数，已按字符串归一：$nonStringTypes")
    }
    val normalizedCommand = command.filterNotNull().map { it.toString() }
    logger.lifecycle("[realserver] 启动 ${task.name}…")
    logFile.parentFile.mkdirs()
    return ProcessBuilder(normalizedCommand)
        .directory(workDir)
        .redirectErrorStream(true)
        .redirectOutput(logFile)
        .start()
}

/** 仅起真实 Forge 专用服（须 -Pmpmt.acceptance.artifact.server-runtime=…）；客户端请另开终端 runAcceptanceClient。 */
/** 单 Gradle 编排：起服 → 等端口 → 起客户端伴侣 → 等同轮报告（镜像 fabric lane 的 launchAcceptanceProcess 模式）。 */
tasks.register("runForgeRealServer262Acceptance") {
    group = "verification"
    description = "单 Gradle 编排 Forge 26.2 REALSERVER262 服务端与客户端验收"
    // 同 fabric 26.2：本任务用 ProcessBuilder 直接拉起 run 任务命令行、绕过任务图，
    // 必须显式依赖 loom 的 generateDLIConfig（写出 dev-launch-injector 的 launch.cfg），
    // 否则 dev 启动不注入任何 mod、验收场景不执行 → 超时无报告。
    dependsOn(packageArtifacts, prepareDevModOutputs, "generateDLIConfig")
    doLast {
        val runId = requiredRunProperty("mpmt.acceptance.runId")
        requiredRunProperty("mpmt.acceptance.matrix")
        requiredRunProperty("mpmt.acceptance.startEpochMs")
        val report = file("run-acceptance-server/acceptance-report.txt")
        val logDir = File(layout.buildDirectory.get().asFile, "acceptance-logs")
        logDir.mkdirs()

        // 编排模式下 run 任务的 doFirst 不会执行，这里等价执行其准备工作
        val serverTask = tasks.named<JavaExec>("runAcceptanceServer").get()
        val clientTask = tasks.named<JavaExec>("runAcceptanceClient").get()
        installDevAcceptanceMod(file("run-acceptance-server"))
        prepareAcceptanceServerProperties(file("run-acceptance-server"))
        configureReportProperties(serverTask)
        installDevAcceptanceMod(file("run-acceptance-client"))
        clientTask.systemProperty("mpmt.acceptance.javaExecutable", java25Launcher.get().executablePath.asFile.absolutePath)
        clientTask.systemProperty(
            "mpmt.acceptance.server",
            (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1:25566").toString(),
        )

        var server: Process? = null
        var client: Process? = null
        try {
            // ① 起 dev 服务端
            val serverProcess =
                launchAcceptanceProcess(serverTask, File(logDir, "realserver262-server.log"), file(acceptanceServerRunDirectory))
            server = serverProcess

            // ② 等服务端监听 25566（Java 25 冷启动窗口）
            val portDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
            var portOpen = false
            while (System.nanoTime() < portDeadline) {
                if (!serverProcess.isAlive) break
                try {
                    Socket("127.0.0.1", 25566).close()
                    portOpen = true
                    break
                } catch (_: Exception) {
                    Thread.sleep(500)
                }
            }
            if (!portOpen) {
                val tail = serverLogFile0(logDir)
                throw GradleException("[realserver] 服务端未监听 25566：" + System.lineSeparator() + tail)
            }

            // ③ 起客户端伴侣（自连 127.0.0.1:25566；GUI 窗口在用户桌面，场景全自动）
            client = launchAcceptanceProcess(clientTask, File(logDir, "realserver262-client.log"), file("run-acceptance-client"))

            // ④ 等同轮报告（驱动看门狗 600s；此处留 660s 余量）
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(660_000)
            val reportLine = "RUN_ID" + "\t" + runId
            while (System.nanoTime() < deadline) {
                if (report.isFile && report.readText(Charsets.UTF_8).contains(reportLine)) break
                if (!serverProcess.isAlive && !(report.isFile && report.readText(Charsets.UTF_8).contains(reportLine))) break
                Thread.sleep(500)
            }
            if (!report.isFile || !report.readText(Charsets.UTF_8).contains(reportLine)) {
                val serverLog = File(logDir, "realserver262-server.log")
                val tail =
                    if (serverLog.isFile) {
                        serverLog.readLines().takeLast(30).joinToString(System.lineSeparator())
                    } else {
                        ""
                    }
                throw GradleException("[realserver] REALSERVER262 未在截止前生成当前运行报告（$runId）：${report.absolutePath}" + System.lineSeparator() + tail)
            }
            val lines = report.readText(Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
            val last = lines.last()
            if (last != "RESULT PASS") {
                throw GradleException("[realserver] 验收未通过（$last）：${report.absolutePath}")
            }
            logger.lifecycle("[realserver] Forge 26.2 REALSERVER262 报告 PASS：${report.absolutePath}")
        } finally {
            listOf(client, server).forEach { proc ->
                if (proc != null && proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
                }
            }
        }
    }
}

fun serverLogFile0(logDir: File): String {
    val f = File(logDir, "realserver262-server.log")
    return if (f.isFile) f.readLines().takeLast(30).joinToString(System.lineSeparator()) else "无服务端日志"
}

tasks.register("runRealServerAcceptanceHost") {
    group = "verification"
    description = "通过 Forge runAcceptanceServer 启动真实专用服（不含客户端伴侣；不含报告门）"
    dependsOn(tasks.named("runAcceptanceServer"))
}

/** 报告门：须先完成「服 + 客户端伴侣」并落 RESULT PASS 报告。 */
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "Forge 26.2 realserver 门禁：校验权威报告 RESULT PASS（须先起服 + runClient）"
    dependsOn(verifyAcceptanceReport)
}

tasks.register("printRealServerAcceptanceRecipe") {
    group = "help"
    description = "打印 Forge 26.2 真服验收推荐步骤（独立 launcher，禁止嵌套根 gradlew）"
    doLast {
        logger.lifecycle(
            """
            |[Forge 26.2 realserver]
            |1) 本目录构建产物：
            |   ./gradlew --no-daemon packageArtifacts
            |2) 起服（二选一）：
            |   a) dev 服：./gradlew --no-daemon runAcceptanceServer
            |   b) 真实专用服：./gradlew --no-daemon runRealServerAcceptanceHost \
            |        -Pmpmt.acceptance.artifact.server-runtime=<forge-server.jar> \
            |        -Pmpmt.acceptance.runId=... -Pmpmt.acceptance.matrix=... \
            |        -Pmpmt.acceptance.startEpochMs=...
            |3) 另开终端客户端伴侣：
            |   ./gradlew --no-daemon runAcceptanceClient -Pmpmt.acceptance.server=127.0.0.1:<port>
            |4) 报告门：
            |   ./gradlew --no-daemon runRealServerAcceptance
            |   # 或 ./gradlew --no-daemon verifyAcceptanceReport
            """.trimMargin().trim(),
        )
    }
}

tasks.named("check") {
    dependsOn(tasks.named("test"), tasks.named("contractTest"), verifyPackaging, staticQualityTasks, "koverXmlReport")
}
tasks.named("build") {
    dependsOn(packageArtifacts)
}
