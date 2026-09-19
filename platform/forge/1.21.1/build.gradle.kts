import buildconventions.ForgeLaneExtension
import buildconventions.ForgeModules
import buildconventions.configureForgeModernProductJar
import buildconventions.configureForgeReportProperties
import buildconventions.forgeJavaLauncher
import buildconventions.registerForgeAcceptanceJar
import buildconventions.registerForgeModernSourceSets
import buildconventions.requiredForgeRunProperty
import buildconventions.verifyForgeDevSecureJarOutputs
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import java.io.File

// Forge 1.21.1 车道（根构建子模块）：common + server + client 分目录 → mpmt-forge-1.21.1-<version>.jar。
// 不可变契约：产物名与路径、SRG 重映射链路与 mods.toml/services 断言、dev SecureJar 嵌入链、
// 验收报告门与判定强度（ADR-0014）；打包链路与 dev 链路集中在 build-conventions.forge（ADR-0027）。
// 守护 JVM 须 ≥ 21（unpick 运行期要求）；目标 Java 21 由下方 toolchain 承担。

plugins {
    id("build-conventions.quality")
    java
    id("top.wcpe.loom")
    id("build-conventions.forge")
}

val minecraftVersion = "1.21.1"
val forgeVersion = "1.21.1-52.1.0"
val loomVersion = "1.17.2"

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

// 车道编译目标（版本目标即车道事实；UTF-8 / -Xlint 由 build-conventions.forge 统一接线）
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

// 打包配置：产品 / 验收各自打入的共享模块（依赖接线由 build-conventions.forge 按配置名承担）
configurations.create("productBundle")
configurations.create("acceptanceBundle")

// forge 车道参数：版本、产物名与本车道接线差异在此声明；
// 源集、dev SecureJar 嵌入、验收 jar 与报告门、打包校验、契约测试由插件承担。
val laneAliasForgeVersion = forgeVersion
val laneAliasLoomVersion = loomVersion
val laneAliasMinecraftVersion = minecraftVersion
forgeLane {
    mcVersion.set(laneAliasMinecraftVersion)
    forgeVersion.set(laneAliasForgeVersion)
    loomVersion.set(laneAliasLoomVersion)
    targetJavaVersion.set(21)
    laneLabel.set("Forge 1.21.1")
    // 产品 jar 由 arch-loom remapJar 以官方映射命名空间输出（Forge 52 生产运行时即 Mojang 命名，重映射为恒等）
    productTaskName.set("remapJar")
    archiveExcludes.add("module-info.class")
    productModClass.set("top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
    acceptanceModClass.set("top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge121AcceptanceMod.class")
    acceptanceJarName.set("mpmt-forge-acceptance-1.21.1")
    acceptanceJarTitle.set("MPMT Forge 1.21.1 验收伴侣")
    acceptanceExcludes.set(
        listOf(
            "top/wcpe/mc/mpmt/core/**",
            "top/wcpe/mc/mpmt/protocol/**",
            "top/wcpe/mc/mpmt/platform/spi/**",
            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class",
            "top/wcpe/mc/mpmt/platform/forge/modern/client/**",
            "top/wcpe/mc/mpmt/platform/forge/modern/net/**",
        ),
    )
    devModOutputs.set(true)
    unitTestMetadata.set(true)
    acceptanceReportGate.set(true)
    acceptanceReportCandidates.set(
        listOf("run-realserver/acceptance-report.txt", "run-acceptance-server/acceptance-report.txt"),
    )
    acceptanceReportHint.set(
        "先跑 runAcceptanceServer + runAcceptanceClient，或提供 server-runtime 后跑 runRealServerAcceptanceHost + 客户端伴侣。",
    )
    modernPackagingVerification.set(true)
    contractTestMainClass.set("top.wcpe.mc.mpmt.platform.forge.modern.contract.Forge121ContractMain")
    contractTestDependsOn.set(listOf("packageArtifacts"))
    contractTestProductTask.set("remapJar")
    contractTestAcceptanceTask.set("acceptanceJar")
    contractTestProperties.put("mpmt.test.projectDir", projectDir.absolutePath)
    contractTestProperties.put("mpmt.test.minecraftVersion", minecraftVersion)
    contractTestProperties.put("mpmt.test.forgeVersion", forgeVersion)
    contractTestProperties.put("mpmt.test.loomVersion", loomVersion)
    contractTestProperties.put("mpmt.test.gradleVersion", gradle.gradleVersion)
}

// 插件公开 API 需要扩展实例（块外传参用），故在块后取回一次
val forge = extensions.getByType(buildconventions.ForgeLaneExtension::class.java)
// 源集形状（common/server/client 分目录 + acceptance/contractTest）与打包内容装配：
// resources 输出并入 classes 目录，使 dev MOD_CLASSES 每 mod 只剩一条 SecureJar 路径
registerForgeModernSourceSets(project)
configureForgeModernProductJar(project, forge)
registerForgeAcceptanceJar(project, forge)
// loom run 的 source / mods 声明直接引用源集对象
val acceptance = sourceSets["acceptance"]

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
    add("compileOnly", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("productBundle", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))

    "acceptanceCompileOnly"(files(acceptanceSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("acceptanceBundle", files(ForgeModules.moduleJar(project, ":modules:acceptance")))

    testImplementation(files(acceptanceSharedProjects.map { ForgeModules.moduleJar(project, it) }))

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

loom {
    runs {
        // 配置名决定任务名：acceptanceServer -> runAcceptanceServer（主类由 forge 运行模板注入）。
        // source 指定任务 classpath 基底（acceptance.runtimeClasspath）；mods 声明经
        // ForgeModClassesService 写入 MOD_CLASSES（mods { source } 的 SecureJar 路径）。
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

val java21Launcher = forgeJavaLauncher(project, 21)

tasks.matching { it.name == "runAcceptanceServer" }.configureEach {
    // packageArtifacts 供报告属性取 jar 路径；prepareDevModOutputs 供 SecureJar 实际加载
    dependsOn("packageArtifacts", "prepareDevModOutputs")
    val host: Task = this
    if (host is JavaExec) {
        host.javaLauncher.set(java21Launcher)
        host.doFirst {
            configureForgeReportProperties(project, forge, host, true)
            verifyForgeDevSecureJarOutputs(project, forge)
        }
    }
}

tasks.matching { it.name == "runAcceptanceClient" }.configureEach {
    dependsOn("packageArtifacts", "prepareDevModOutputs")
    val host: Task = this
    if (host is JavaExec) {
        host.javaLauncher.set(java21Launcher)
        host.doFirst {
            host.systemProperty(
                "mpmt.acceptance.javaExecutable",
                java21Launcher
                    .get()
                    .executablePath.asFile.absolutePath,
            )
            // 伴侣自动连服地址（含端口）；缺省与 quickPlay 一致
            host.systemProperty("mpmt.acceptance.server", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1").toString())
        }
    }
}

/** 仅起真实专用服（须 -Pmpmt.acceptance.artifact.server-runtime=…）；客户端请另开终端 runAcceptanceClient。 */
val runRealServerAcceptanceHost =
    tasks.register<Exec>("runRealServerAcceptanceHost") {
        group = "verification"
        description = "启动 Forge 1.21.1 真实专用服（不含客户端伴侣；不含报告门）"
        dependsOn("packageArtifacts")
        workingDir(project.file("run-realserver"))
        doFirst {
            val serverRuntime = file(requiredForgeRunProperty(project, "mpmt.acceptance.artifact.server-runtime"))
            if (!serverRuntime.isFile()) {
                throw GradleException("server-runtime 必须是调用方显式提供的实际文件：$serverRuntime")
            }
            val product =
                tasks
                    .named("remapJar", Jar::class.java)
                    .get()
                    .archiveFile
                    .get()
                    .asFile
            val acceptanceJarFile =
                tasks
                    .named("acceptanceJar", Jar::class.java)
                    .get()
                    .archiveFile
                    .get()
                    .asFile
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
                "-Dmpmt.acceptance.runId=${requiredForgeRunProperty(project, "mpmt.acceptance.runId")}",
                "-Dmpmt.acceptance.matrix=${requiredForgeRunProperty(project, "mpmt.acceptance.matrix")}",
                "-Dmpmt.acceptance.startEpochMs=${requiredForgeRunProperty(project, "mpmt.acceptance.startEpochMs")}",
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

tasks.named("check") { dependsOn(tasks.named("test"), "contractTest", "verifyPackaging") }
tasks.named("build") { dependsOn("packageArtifacts") }
