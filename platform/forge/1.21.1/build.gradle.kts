import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.DataInputStream
import java.util.zip.ZipFile

plugins {
    id("build-conventions.quality")
    java
    id("top.wcpe.loom")
}

// Gradle 版本统一到根 9.6.1（wrapper 已对齐）；守护 JVM 须 ≥21（unpick 运行期要求），
// 目标 Java 21 由下方 toolchain 承担，不再做配置期版本守卫。

// 本工程为根构建的普通子模块（platform/forge/1.21.1）；group / version 由根 allprojects 统一提供。

val minecraftVersion = "1.21.1"
val forgeVersion = "1.21.1-52.1.0"
val loomVersion = "1.17.1"
val productChannel = "mpmt:main"
val acceptanceChannel = "mpmt-test:acceptance"

// 共享模块已为根构建子模块：按工程路径取各模块 jar 任务产物（FileCollection，自带任务依赖）
val moduleJar: (String) -> FileCollection =
    { projectPath ->
        files(project(projectPath).tasks.withType(Jar::class.java).matching { it.name == "jar" })
    }

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
    archivesName.set("mpmt-forge-1.21.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
// 源集名即契约（决定 compileAcceptanceJava / processAcceptanceResources 等任务名），故显式 create(name)
val acceptance =
    sourceSets.create("acceptance") {
        java.setSrcDirs(listOf("src/acceptance/java"))
        resources.setSrcDirs(listOf("src/acceptance/resources"))
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        // loom RunGameTask 以本源集 runtimeClasspath 作为运行 classpath（同 FG6 塞进模块层的口径）。
        // 若此处再拼 compileClasspath，compileOnly 共享 jar（如 acceptance 的共享 JAR）
        // 会以自动模块名与 SecureJar 内嵌同名包冲突（split package）。
        // 运行期只靠：本源集输出（含 embed）+ main 输出（含产品共享 embed）+ main 运行时。
        runtimeClasspath = output + sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
val contractTestSources =
    sourceSets.create("contractTest") {
        java.setSrcDirs(listOf("src/contractTest/java"))
        resources.setSrcDirs(listOf("src/contractTest/resources"))
        compileClasspath += sourceSets["main"].output + sourceSets["acceptance"].output + sourceSets["test"].compileClasspath
        runtimeClasspath += output + compileClasspath
    }

// FG6 mapModClassesToGradle（现等价机制为 loom 的 MOD_CLASSES）会把每个 sourceSet 的
// resourcesDir 与 classesDirs 拼成两条 SecureJar 路径；1.21 FML 对“仅有 mods.toml、无 @Mod 类”的路径直接 FATAL
// （constructed 0 mods）。
// 将 resources 输出合并进 classes 目录，使 MOD_CLASSES 每 mod 只剩一条路径。
// 同时：共享 JAR 只在 packaging 时 shade，dev classpath 又不被 FML 模块层暴露给 mod，
// 故把 product/acceptance 共享内容同步进对应 classes 目录，让 SecureJar 内自洽。
listOf(sourceSets["main"], sourceSets["acceptance"]).forEach { ss ->
    val classesDir = ss.java.destinationDirectory.get().asFile
    ss.output.setResourcesDir(classesDir)
}

// acceptance 编译需要 main 的 compileOnly 共享类；不 extends implementation（已无 runtime 共享 jar）
configurations["acceptanceCompileOnly"].extendsFrom(configurations["compileOnly"])
configurations["contractTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["contractTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

repositories {
    maven {
        name = "MinecraftForge"
        setUrl("https://maven.minecraftforge.net/")
    }
    mavenCentral()
}

dependencies {
    // arch-loom 三段式声明：原版 MC 本体 + Mojang 官方映射 + Forge（arch-loom 自行解析 userdev）
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    "forge"("net.minecraftforge:forge:$forgeVersion")

    // 共享 JAR 仅 compileOnly：1.21 FML 模块层会把 runtime 上的 jar 当自动模块，
    // 与 SecureJar 内嵌同名包冲突（split package，如 acceptance vs mpmt_acceptance）。
    // 运行期由 embed* / jar shade 把类放进 mod 目录，不进 module path。
    compileOnly(files(productSharedProjects.map { moduleJar(it) }))
    productBundle(files(productSharedProjects.map { moduleJar(it) }))

    "acceptanceCompileOnly"(files(acceptanceSharedProjects.map { moduleJar(it) }))
    acceptanceBundle(files(moduleJar(":modules:acceptance")))

    testImplementation(files(acceptanceSharedProjects.map { moduleJar(it) }))
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    // loom 约定：jar 产物改落 build/devlibs 且带 -dev 分类器；
    // build/libs 下的正式产品名（无分类器）由 remapJar 以官方映射命名空间输出（见 verifyPackaging）。
    from(configurations["productBundle"].map { zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveExcludes.forEach { exclude(it) }
    exclude("top/wcpe/mc/mpmt/acceptance/**")
    manifest {
        attributes(
            "Implementation-Title" to "MPMT Forge 1.21.1 产品",
            "Implementation-Version" to project.version,
        )
    }
}

val acceptanceJar =
    tasks.register<Jar>("acceptanceJar") {
        group = "build"
        description = "构建独立 Forge 1.21.1 验收 mod JAR"
        archiveBaseName.set("mpmt-forge-acceptance-1.21.1")
        archiveClassifier.set("")
        from(acceptance.output)
        from(configurations["acceptanceBundle"].map { zipTree(it) })
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/core/**")
        exclude("top/wcpe/mc/mpmt/protocol/**")
        exclude("top/wcpe/mc/mpmt/platform/spi/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/client/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/net/**")
        manifest {
            attributes(
                "Implementation-Title" to "MPMT Forge 1.21.1 验收伴侣",
                "Implementation-Version" to project.version,
            )
        }
    }

loom {
    runs {
        // 配置名决定任务名：acceptanceServer -> runAcceptanceServer（主类由 forge 运行模板注入）。
        // source 指定任务 classpath 基底（acceptance.runtimeClasspath）；mods 声明经
        // ForgeModClassesService 写入 MOD_CLASSES（等价于 FG6 的 mods { source } SecureJar 路径）。
        create("acceptanceServer") {
            server()
            source(acceptance)
            runDir("run-acceptance-server")
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", project.file("run-acceptance-server/acceptance-report.txt").absolutePath)
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

tasks.withType(JavaCompile::class.java).configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.add("-Xlint:all")
}

// 把共享模块解压进 dev SecureJar 路径（与 jar/acceptanceJar 的 shade 内容对齐）。
// 必须在任何消费 main.output / acceptance.output 的编译任务之前完成，
// 否则 Gradle 会报 implicit dependency（embed 写目录、compileAcceptance 读目录）。
val embedProductSharedIntoMain =
    tasks.register<Copy>("embedProductSharedIntoMain") {
        group = "build"
        description = "将产品共享 JAR 嵌入 main classes，供 dev MOD_CLASSES 对应 SecureJar 发现 @Mod 与依赖类"
        dependsOn(tasks.named("classes"), tasks.named("processResources"))
        from(configurations["productBundle"].map { zipTree(it) })
        into(sourceSets["main"].java.destinationDirectory)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/acceptance/**")
    }

val embedAcceptanceSharedIntoAcceptance =
    tasks.register<Copy>("embedAcceptanceSharedIntoAcceptance") {
        group = "build"
        description = "将验收共享 JAR 嵌入 acceptance classes，供 dev MOD_CLASSES 对应 SecureJar 加载验收伴侣"
        dependsOn(tasks.named("acceptanceClasses"), tasks.named("processAcceptanceResources"))
        from(configurations["acceptanceBundle"].map { zipTree(it) })
        into(sourceSets["acceptance"].java.destinationDirectory)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        archiveExcludes.forEach { exclude(it) }
        exclude("top/wcpe/mc/mpmt/core/**")
        exclude("top/wcpe/mc/mpmt/protocol/**")
        exclude("top/wcpe/mc/mpmt/platform/spi/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/client/**")
        exclude("top/wcpe/mc/mpmt/platform/forge/modern/net/**")
    }

val prepareDevModOutputs =
    tasks.register("prepareDevModOutputs") {
        group = "build"
        description = "准备 loom runAcceptance* 所需的合并 SecureJar 目录"
        dependsOn(embedProductSharedIntoMain, embedAcceptanceSharedIntoAcceptance)
    }

// 凡读取 main.output / acceptance.output 的任务都必须显式依赖 embed，
// 否则 Gradle 8 隐式依赖校验会在 build 全量门禁失败（compileTestJava 等）。
tasks.named("compileAcceptanceJava") { dependsOn(embedProductSharedIntoMain) }
tasks.named("compileTestJava") { dependsOn(embedProductSharedIntoMain) }
tasks.named("compileContractTestJava") { dependsOn(prepareDevModOutputs) }
tasks.named("jar") { dependsOn(embedProductSharedIntoMain) }
acceptanceJar.configure { dependsOn(embedAcceptanceSharedIntoAcceptance) }
// 静态分析任务读取 classes 目录（embed 会写入），必须显式声明依赖，
// 否则 Gradle 隐式依赖校验在全量 build 门禁失败（与 forge/26.2 同口径）。
tasks.named("checkstyleMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("pmdMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("spotbugsMain") { dependsOn(embedProductSharedIntoMain) }
tasks.named("checkstyleAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("pmdAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("spotbugsAcceptance") { dependsOn(embedAcceptanceSharedIntoAcceptance) }
tasks.named("test") { dependsOn(prepareDevModOutputs) }

val classMajor: (ZipFile, String) -> Int =
    { zip, name ->
        val input = DataInputStream(zip.getInputStream(zip.getEntry(name)))
        try {
            if (input.readInt() != 0xCAFEBABE.toInt()) {
                throw GradleException("类文件魔数错误：$name")
            }
            input.readUnsignedShort()
            input.readUnsignedShort()
        } finally {
            input.close()
        }
    }

val verifyPackaging =
    tasks.register("verifyPackaging") {
        group = "verification"
        description = "校验产品与验收 JAR 隔离、入口、共享核心和 Java 21 平台字节码"
        // 产品 jar 由 arch-loom remapJar 输出（Forge 52 生产运行时即 Mojang 命名，重映射为恒等），
        // 承担 FG6 reobf=false 的等价语义；remapJar 自身依赖 jar -> embed 链。
        dependsOn(tasks.named<RemapJarTask>("remapJar"), acceptanceJar)
        doLast {
            val product = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
            val acceptanceJarFile = acceptanceJar.get().archiveFile.get().asFile
            val productZip = ZipFile(product)
            val acceptanceZip = ZipFile(acceptanceJarFile)
            try {
                fun must(condition: Boolean, message: String) {
                    if (!condition) {
                        throw GradleException("Forge 1.21.1 打包校验失败：$message")
                    }
                }
                must(productZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class") != null, "产品缺少 mod 入口")
                must(productZip.getEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class") != null, "产品未 shade core-server")
                must(productZip.getEntry("top/wcpe/mc/mpmt/core/client/ClientNetworkFeature.class") != null, "产品未 shade core-client")
                must(productZip.getEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class") != null, "产品未 shade protocol")
                must(
                    productZip.entries().asSequence().filter { it.name.startsWith("top/wcpe/mc/mpmt/acceptance/") }.toList().isEmpty(),
                    "产品误包含 acceptance 核心",
                )
                must(
                    acceptanceZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge121AcceptanceMod.class") != null,
                    "验收 JAR 缺少入口",
                )
                must(
                    acceptanceZip.getEntry("top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class") != null,
                    "验收 JAR 未包含 acceptance 核心",
                )
                must(
                    acceptanceZip.entries().asSequence().filter { it.name.startsWith("top/wcpe/mc/mpmt/core/") }.toList().isEmpty(),
                    "验收 JAR 重复包含 core",
                )
                must(
                    acceptanceZip.entries().asSequence().filter { it.name.startsWith("top/wcpe/mc/mpmt/protocol/") }.toList().isEmpty(),
                    "验收 JAR 重复包含 protocol",
                )
                must(
                    acceptanceZip.getEntry("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class") == null,
                    "验收 JAR 重复包含产品入口",
                )
                productZip
                    .entries()
                    .asSequence()
                    .filter { it.name.startsWith("top/wcpe/mc/mpmt/platform/forge/modern/") && it.name.endsWith(".class") }
                    .forEach { entry ->
                        must(classMajor(productZip, entry.name) == 65, "产品平台类不是 Java 21：${entry.name}")
                    }
                acceptanceZip
                    .entries()
                    .asSequence()
                    .filter {
                        it.name.startsWith("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/") &&
                            it.name.endsWith(".class")
                    }.forEach { entry ->
                        must(classMajor(acceptanceZip, entry.name) == 65, "验收平台类不是 Java 21：${entry.name}")
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
        description = "打包并校验 Forge 1.21.1 产品与验收 JAR"
        dependsOn(verifyPackaging)
    }

val java21Launcher =
    javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }

tasks.withType(Test::class.java).configureEach {
    useJUnitPlatform()
    javaLauncher.set(java21Launcher)
}

tasks.test {
    systemProperty("mpmt.test.repositoryRoot", rootProject.projectDir.absolutePath)
    systemProperty("mpmt.test.projectDir", projectDir.absolutePath)
    systemProperty("mpmt.test.minecraftVersion", minecraftVersion)
    systemProperty("mpmt.test.forgeVersion", forgeVersion)
    systemProperty("mpmt.test.loomVersion", loomVersion)
    systemProperty("mpmt.test.gradleVersion", gradle.gradleVersion)
}

val contractTest =
    tasks.register<JavaExec>("contractTest") {
        group = "verification"
        description = "校验 Forge 1.21.1 冻结矩阵、双 JAR 隔离与 class major"
        dependsOn(tasks.named("contractTestClasses"), packageArtifacts)
        classpath = contractTestSources.runtimeClasspath
        mainClass.set("top.wcpe.mc.mpmt.platform.forge.modern.contract.Forge121ContractMain")
        javaLauncher.set(java21Launcher)
        systemProperty("mpmt.test.repositoryRoot", rootProject.projectDir.absolutePath)
        systemProperty("mpmt.test.projectDir", projectDir.absolutePath)
        systemProperty("mpmt.test.minecraftVersion", minecraftVersion)
        systemProperty("mpmt.test.forgeVersion", forgeVersion)
        systemProperty("mpmt.test.loomVersion", loomVersion)
        systemProperty("mpmt.test.gradleVersion", gradle.gradleVersion)
        systemProperty("mpmt.test.productJar", tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile }.get().asFile.absolutePath)
        systemProperty("mpmt.test.acceptanceJar", acceptanceJar.flatMap { it.archiveFile }.get().asFile.absolutePath)
    }

val requiredRunProperty: (String) -> String =
    { name ->
        val value = project.findProperty(name)
        if (value == null || value.toString().isEmpty()) {
            throw GradleException("缺少运行属性 -P$name")
        }
        value.toString()
    }

val configureReportProperties: (JavaExec) -> Unit =
    { task ->
        val serverRuntime = file(requiredRunProperty("mpmt.acceptance.artifact.server-runtime"))
        if (!serverRuntime.isFile()) {
            throw GradleException("server-runtime 必须是调用方显式提供的实际文件：$serverRuntime")
        }
        val product = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
        val acceptanceJarFile = acceptanceJar.get().archiveFile.get().asFile
        task.systemProperty("mpmt.acceptance.runId", requiredRunProperty("mpmt.acceptance.runId"))
        task.systemProperty("mpmt.acceptance.matrix", requiredRunProperty("mpmt.acceptance.matrix"))
        task.systemProperty("mpmt.acceptance.startEpochMs", requiredRunProperty("mpmt.acceptance.startEpochMs"))
        task.systemProperty("mpmt.acceptance.javaExecutable", java21Launcher.get().executablePath.asFile.absolutePath)
        task.systemProperty("mpmt.acceptance.artifact.server-runtime", serverRuntime.absolutePath)
        task.systemProperty("mpmt.acceptance.artifact.server-product", product.absolutePath)
        task.systemProperty("mpmt.acceptance.artifact.server-acceptance", acceptanceJarFile.absolutePath)
        task.systemProperty(
            "mpmt.acceptance.artifact.client-product",
            (project.findProperty("mpmt.acceptance.artifact.client-product") ?: product.absolutePath).toString(),
        )
        task.systemProperty(
            "mpmt.acceptance.artifact.client-acceptance",
            (project.findProperty("mpmt.acceptance.artifact.client-acceptance") ?: acceptanceJarFile.absolutePath).toString(),
        )
    }

tasks.matching { it.name == "runAcceptanceServer" }.configureEach {
    // packageArtifacts 供报告属性取 jar 路径；prepareDevModOutputs 供 SecureJar 实际加载
    dependsOn(packageArtifacts, prepareDevModOutputs)
    val host: Task = this
    if (host is JavaExec) {
        host.javaLauncher.set(java21Launcher)
        host.doFirst {
            configureReportProperties(host)
            val mainDir = sourceSets["main"].java.destinationDirectory.get().asFile
            val acceptanceDir = sourceSets["acceptance"].java.destinationDirectory.get().asFile
            val mainToml = File(mainDir, "META-INF/mods.toml")
            val acceptanceToml = File(acceptanceDir, "META-INF/mods.toml")
            val mainModClass = File(mainDir, "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
            val acceptanceModClass =
                File(acceptanceDir, "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge121AcceptanceMod.class")
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
    val host: Task = this
    if (host is JavaExec) {
        host.javaLauncher.set(java21Launcher)
        host.doFirst {
            host.systemProperty("mpmt.acceptance.javaExecutable", java21Launcher.get().executablePath.asFile.absolutePath)
            // 伴侣自动连服地址（含端口）；缺省与 quickPlay 一致
            host.systemProperty("mpmt.acceptance.server", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
        }
    }
}

val forge121ReportFile: () -> File =
    {
        val custom = project.findProperty("mpmt.acceptance.report")
        if (custom != null && custom.toString().trim().isNotEmpty()) {
            file(custom.toString())
        } else {
            val real = project.file("run-realserver/acceptance-report.txt")
            if (real.isFile) {
                real
            } else {
                project.file("run-acceptance-server/acceptance-report.txt")
            }
        }
    }

tasks.register("verifyAcceptanceReport") {
    group = "verification"
    description = "校验 Forge 1.21.1 真服/验收报告末行 RESULT PASS"
    doLast {
        val report = forge121ReportFile()
        if (!report.isFile) {
            throw GradleException(
                "未找到 Forge 1.21.1 验收报告：${report.absolutePath}\n" +
                    "先跑 runAcceptanceServer + runAcceptanceClient，或提供 server-runtime 后跑 runRealServerAcceptanceHost + 客户端伴侣。",
            )
        }
        val lines = report.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
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
        logger.lifecycle("[realserver] Forge 1.21.1 报告 PASS：${report.absolutePath}")
    }
}

/** 仅起真实专用服（须 -Pmpmt.acceptance.artifact.server-runtime=…）；客户端请另开终端 runAcceptanceClient。 */
val runRealServerAcceptanceHost =
    tasks.register<Exec>("runRealServerAcceptanceHost") {
        group = "verification"
        description = "启动 Forge 1.21.1 真实专用服（不含客户端伴侣；不含报告门）"
        dependsOn(packageArtifacts)
        workingDir(project.file("run-realserver"))
        doFirst {
            val serverRuntime = file(requiredRunProperty("mpmt.acceptance.artifact.server-runtime"))
            if (!serverRuntime.isFile()) {
                throw GradleException("server-runtime 必须是调用方显式提供的实际文件：$serverRuntime")
            }
            val product = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
            val acceptanceJarFile = acceptanceJar.get().archiveFile.get().asFile
            val runDir = project.file("run-realserver")
            val modsDir = File(runDir, "mods")
            modsDir.mkdirs()
            File(runDir, "eula.txt").writeText("eula=true\n", Charsets.UTF_8)
            copy {
                from(product, acceptanceJarFile)
                into(modsDir)
            }
            workingDir(runDir)
            val report = File(runDir, "acceptance-report.txt")
            setExecutable(java21Launcher.get().executablePath.asFile)
            args(
                "-Dmpmt.acceptance=true",
                "-Dmpmt.acceptance.report=${report.absolutePath}",
                "-Dmpmt.acceptance.deadlineMs=300000",
                "-Dmpmt.acceptance.runId=${requiredRunProperty("mpmt.acceptance.runId")}",
                "-Dmpmt.acceptance.matrix=${requiredRunProperty("mpmt.acceptance.matrix")}",
                "-Dmpmt.acceptance.startEpochMs=${requiredRunProperty("mpmt.acceptance.startEpochMs")}",
                "-Dmpmt.acceptance.javaExecutable=${java21Launcher.get().executablePath.asFile.absolutePath}",
                "-Dmpmt.acceptance.artifact.server-runtime=${serverRuntime.absolutePath}",
                "-Dmpmt.acceptance.artifact.server-product=${product.absolutePath}",
                "-Dmpmt.acceptance.artifact.server-acceptance=${acceptanceJarFile.absolutePath}",
                "-Dmpmt.acceptance.artifact.client-product=${project.findProperty("mpmt.acceptance.artifact.client-product") ?: product.absolutePath}",
                "-Dmpmt.acceptance.artifact.client-acceptance=${project.findProperty("mpmt.acceptance.artifact.client-acceptance") ?: acceptanceJarFile.absolutePath}",
                "-jar",
                serverRuntime.absolutePath,
                "nogui",
            )
        }
    }

/** 报告门：须先完成「服 + 客户端伴侣」并落 RESULT PASS 报告。 */
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "Forge 1.21.1 realserver 门禁：校验权威报告 RESULT PASS（须先起服 + runAcceptanceClient）"
    dependsOn(tasks.named("verifyAcceptanceReport"))
}

tasks.register("printRealServerAcceptanceRecipe") {
    group = "help"
    description = "打印 Forge 1.21.1 真服验收推荐步骤（独立 launcher，禁止嵌套根 gradlew）"
    doLast {
        logger.lifecycle(
            """
            |[Forge 1.21.1 realserver]
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

tasks.named("check") { dependsOn(tasks.named("test"), contractTest, verifyPackaging) }
tasks.named("build") { dependsOn(packageArtifacts) }
