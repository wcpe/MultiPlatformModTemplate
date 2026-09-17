import buildconventions.FORGE_ARCHIVE_EXCLUDES
import buildconventions.ForgeLaneExtension
import buildconventions.ForgeModules
import buildconventions.registerForgeAcceptanceJar
import dev.architectury.pack200.java.Pack200Adapter
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.jvm.toolchain.JavaToolchainService
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Forge 1.12.2 车道（根构建子模块）：client-only，legacy 链路经 top.wcpe.loom（ADR-0025）→
// mpmt-forge-1.12.2-<version>.jar 与验收伴侣 mpmt-forge-acceptance-1.12.2-<version>.jar。
// 不可变契约：产物名与路径（含 reobfJar / reobfAcceptanceJar 兼容路径）、MCP snapshot 映射与 SRG 重映射链路、
// mcmod.info 元数据、验收伴侣判定（ADR-0014）；质量门禁真源在 build-conventions.quality（ADR-0027）。
// 守护 JVM 须 ≥ 21（loom 运行期要求）；目标 Java 8 由下方 toolchain 承担。

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // legacy forge 的 binpatches.pack.lzma 解包需要 Pack200 实现（JDK 14+ 已移除 java.util.jar.Pack200）；
        // 必须进 buildscript classpath，供下方 loom.forge.pack200Provider 实例化
        classpath("dev.architectury:architectury-pack200:0.1.3")
    }
}

plugins {
    id("build-conventions.quality")
    java
    id("top.wcpe.loom")
    id("build-conventions.forge")
}

base {
    archivesName.set("mpmt-forge-1.12.2")
}

java {
    toolchain {
        // 1.12.2 产品、共享 JAR 与 Forge 全链均为 Java 8 字节码；编译与契约测试运行都由该 toolchain 供给
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}

val minecraftVersion = "1.12.2"
val forgeVersion = "1.12.2-14.23.5.2860"
val mappingsVersion = "20171003-1.12"

// forge 车道参数：本车道为 client-only + reobf 兼容任务映射（无 dev SecureJar 嵌入、无报告门）
val laneAliasMinecraftVersion = minecraftVersion
forgeLane {
    mcVersion.set(laneAliasMinecraftVersion)
    targetJavaVersion.set(8)
    laneLabel.set("Forge 1.12.2")
    modMetadataResource.set("mcmod.info")
    deduplicateProcessedResources.set(true)
    acceptanceJarVersionedDevLibs.set(true)
    acceptanceJarName.set("mpmt-forge-acceptance-1.12.2")
    acceptanceJarTitle.set("MPMT Forge 1.12.2 客户端验收伴侣")
    acceptanceExcludes.set(
        listOf(
            "top/wcpe/mc/mpmt/core/**",
            "top/wcpe/mc/mpmt/protocol/**",
            "top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class",
            "top/wcpe/mc/mpmt/platform/forge/ForgeBuildInfo.class",
            "top/wcpe/mc/mpmt/platform/forge/client/**",
            "top/wcpe/mc/mpmt/platform/forge/hud/**",
            "top/wcpe/mc/mpmt/platform/forge/net/**",
        ),
    )
    reobfCopyTasks.put("reobfJar", "remapJar")
    reobfCopyTasks.put("reobfAcceptanceJar", "remapAcceptanceJar")
    contractTestMainClass.set("top.wcpe.mc.mpmt.platform.forge.contract.Forge112ContractTest")
    contractTestDescription.set("运行 1.12.2 构建、握手、wire 与双 JAR 隔离契约测试")
    contractTestProductTask.set("remapJar")
    contractTestAcceptanceTask.set("remapAcceptanceJar")
    contractTestDependsOn.set(listOf("remapAcceptanceJar"))
    contractTestProperties.put("mpmt.test.version", project.version.toString())
}

// 插件公开 API 需要扩展实例（块外传参用），故在块后取回一次
val forge = extensions.getByType(buildconventions.ForgeLaneExtension::class.java)
// 共享模块（L0-L2）项目路径；acceptance 仅进验收伴侣，不进产品
val productSharedProjects = listOf(":core:domain", ":core:runtime", ":core:client", ":core:protocol")
val acceptanceSharedProjects = productSharedProjects + listOf(":modules:acceptance")

val productBundle: Configuration = configurations.create("productBundle")
val acceptanceBundle: Configuration = configurations.create("acceptanceBundle")

sourceSets {
    named("main") {
        // 1.12 产品以 client 为主；common + client 分目录
        java.setSrcDirs(listOf("common/src/main/java", "client/src/main/java"))
        resources.setSrcDirs(listOf("common/src/main/resources", "src/main/resources"))
    }
    // 纯 JVM 单元测试源集（JaCoCo 覆盖率门禁的 test 任务输入）。
    // 编译期继承 main 类路径（含 loom 的 MCP 命名 Minecraft/Forge），故测试源码用 MCP 名。
    named("test") {
        java.srcDir("src/test/java")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += output + compileClasspath + sourceSets["main"].output
    }
    create("acceptance") {
        java.srcDir("src/acceptance/java")
        resources.srcDir("src/acceptance/resources")
        compileClasspath += sourceSets["main"].output + configurations["compileClasspath"]
        runtimeClasspath += output + compileClasspath
    }
    create("contractTest") {
        java.srcDir("src/contractTest/java")
        resources.srcDir("src/contractTest/resources")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath + sourceSets["acceptance"].output + sourceSets["acceptance"].compileClasspath
        runtimeClasspath += output + compileClasspath + sourceSets["main"].runtimeClasspath + sourceSets["acceptance"].runtimeClasspath
    }
}

// ── forge 本地镜像（userdev shim）────────────────────────────────────────────
// arch-loom 的 ForgeProvider 硬编码按 :userdev classifier 请求 forge userdev 包（dep+":userdev"），
// 而 Forge 1.12.2-14.23.5.x 只发布 userdev3（FG2.3 过渡格式，仅早期 14.23.0.x 有 userdev）。
// 且 Gradle 对 POM 命中的仓库做 artifact 绑定（其余仓库/artifactUrls 均不参与，已实测），
// 因此这里在配置期把 loom 需要的 forge 产物完整镜像进 build/ 下的本地仓库（clean 即清）。
// 另有两处必须干预：
// 1) :userdev ← userdev3 内容；
// 2) loom 以 userdev config.json 是否含 "mcp" 键判定 legacy/modern（ForgeUserdevProvider），
//    userdev3 带 mcp_config 键会被误判 modern（modern mappings 管线请求不存在的 fabric intermediary），
//    故镜像时移除该键，强制走 legacy 管线（与 14.23.0.x 的 FG2 userdev 同为 legacy 语义）。
// 所有下载文件做 sha256 校验防半截/漂移。
// TODO(契约 7 不适用)：本段"配置期下载 + 写盘"经复核**无法**改为任务或惰性 Provider。arch-loom
// （top.wcpe.loom / CompileConfiguration#run → GradleUtils.afterSuccessfulEvaluation → setupMinecraft
// → DependencyProviders#handleDependencies → ForgeUserdevProvider#provide → DependencyInfo#resolveFile）
// 在**配置期**（afterEvaluate）就解析 forge 依赖；任务级 dependsOn 一律晚于该时点，冷缓存下配置阶段必失败。
// 故保留配置期镜像；下载已按文件存在性短路（命中即不重下）。
val forgeMirrorSpecs: List<List<Any>> =
    listOf(
        // [本地文件名, 远程文件名, sha256, 是否重写为 FG2 形态]
        // userdev 必须最后处理：其重写过程要复用已镜像的 sources jar
        listOf(
            "forge-1.12.2-14.23.5.2860-universal.jar", "forge-1.12.2-14.23.5.2860-universal.jar",
            "cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64", false,
        ),
        listOf(
            "forge-1.12.2-14.23.5.2860-installer.jar", "forge-1.12.2-14.23.5.2860-installer.jar",
            "ea7c33ba95e3993a98d0e9e38168c0759ec323a18675a71d938e1f3f70e6e8e7", false,
        ),
        listOf(
            "forge-1.12.2-14.23.5.2860-sources.jar", "forge-1.12.2-14.23.5.2860-sources.jar",
            "52c1f22b56a936d62108d6c0d752c8ccd9cea90bdd190f3e344d6b0c698a4f57", false,
        ),
        listOf(
            "forge-1.12.2-14.23.5.2860-userdev.jar", "forge-1.12.2-14.23.5.2860-userdev3.jar",
            "9040f8f95d7296eb2f4ae9dd638021b6af43cedfa1ca4d30a2beae5491e9f3e2", true,
        ),
    )
val userdevShimRoot = file("${layout.buildDirectory.get().asFile}/forge-userdev-shim")
val userdevShimDir = file("$userdevShimRoot/net/minecraftforge/forge/$forgeVersion")
forgeMirrorSpecs.forEach { spec ->
    val localName = spec[0] as String
    val remoteName = spec[1] as String
    val expectedSha = spec[2] as String
    val stripMcpKey = spec[3] as Boolean
    val target = File(userdevShimDir, localName)
    if (!target.isFile || target.length() == 0L) {
        userdevShimDir.mkdirs()
        logger.lifecycle("[forge-mirror] 下载 $remoteName → $localName")
        val source = URI("https://maven.minecraftforge.net/net/minecraftforge/forge/$forgeVersion/$remoteName").toURL()
        val downloaded = File(userdevShimDir, localName + ".downloading")
        source.openStream().use { input ->
            downloaded.outputStream().use { output -> input.copyTo(output) }
        }
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(downloaded.readBytes())
                .joinToString("") { "%02x".format(it) }
        if (digest != expectedSha) {
            downloaded.delete()
            throw GradleException("forge 镜像下载 sha256 校验失败（$localName，实际 $digest）")
        }
        if (stripMcpKey) {
            // 重写 userdev jar 为 FG2 形态（loom legacy 分支的期待，结构对照 14.23.0.x 的 FG2 userdev）：
            // 1) config.json 整体替换为 FG2 结构（inheritsFrom 供 legacy 分支拼 de.oceanlabs.mcp:mcp:<v>:srg@zip；
            //    libraries 为 {name,url,children} 对象数组供 createLegacyLibs 读取；绝不能含 "mcp" 键——
            //    loom 以 config.json 是否含该键判定 legacy/modern，userdev3 原样会被误判 modern）
            // 2) jar 根补 merged_at.cfg（createLegacyAts 硬编码，内容取自 ats/forge_at.cfg）
            // FG2 结构的 config.json 内容：
            // - inheritsFrom 供 legacy 分支拼 de.oceanlabs.mcp:mcp:<v>:srg@zip
            // - libraries 用 {name} 对象数组供 createLegacyLibs 读取
            // - libraries 仅保留 mavenCentral 可解析的构建/验证核心库：1.12.2 客户端完整运行库
            //   （scala/akka 等，mavenCentral 已清理下架）只有 runClient 需要，本车道不实跑客户端
            //   （CatServer 生产自带），严禁在 JSON 里写注释（Gson 严格模式）
            val legacyDevJson = """
{
  "id": "@minecraft_version@-@project@@version@",
  "type": "release",
  "inheritsFrom": "1.12.2",
  "minecraftArguments": "--version FML_DEV --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker",
  "libraries": [
    {"name": "net.minecraft:launchwrapper:1.12"},
    {"name": "org.ow2.asm:asm-debug-all:5.2"},
    {"name": "lzma:lzma:0.0.1"},
    {"name": "net.sf.jopt-simple:jopt-simple:5.0.3"},
    {"name": "org.apache.maven:maven-artifact:3.5.3"},
    {"name": "org.apache.logging.log4j:log4j-api:2.15.0"},
    {"name": "org.apache.logging.log4j:log4j-core:2.15.0"}
  ]
}
"""
            val zout = ZipOutputStream(FileOutputStream(target))
            val zin = ZipInputStream(FileInputStream(downloaded))
            var atBytes: ByteArray? = null
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                if (entry.name == "config.json") {
                    // spec2 config.json → FG2 结构
                    zout.putNextEntry(ZipEntry("config.json"))
                    zout.write(legacyDevJson.toByteArray(Charsets.UTF_8))
                    zout.closeEntry()
                } else {
                    zout.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        val bytes = zin.readAllBytes()
                        if (entry.name == "ats/forge_at.cfg") {
                            atBytes = bytes
                        }
                        zout.write(bytes)
                    }
                    zout.closeEntry()
                }
                entry = zin.nextEntry
            }
            if (atBytes != null) {
                zout.putNextEntry(ZipEntry("merged_at.cfg"))
                zout.write(atBytes)
                zout.closeEntry()
            }
            // FG2 形态还要求 jar 内有 sources.zip（createLegacySources 会把它拷进 source-repo 本地 maven 仓库）；
            // userdev3 的 sources 是远程 sources classifier，直接复用已镜像的 sources jar 内容
            val sourcesFile = File(userdevShimDir, "forge-$forgeVersion-sources.jar")
            if (sourcesFile.isFile) {
                zout.putNextEntry(ZipEntry("sources.zip"))
                sourcesFile.inputStream().use { zout.write(it.readBytes()) }
                zout.closeEntry()
            }
            zin.close()
            zout.close()
            downloaded.delete()
        } else {
            if (!downloaded.renameTo(target)) {
                throw GradleException("forge 镜像文件就位失败：$downloaded → $target")
            }
        }
    }
}
// 最小 POM：让 shim 成为该 module 的元数据源——Gradle 把 artifact 查找绑定到 POM 所在仓库，
// 仅在远程仓库前置镜像文件是无法参与解析的（已实测）。
val userdevShimPom = File(userdevShimDir, "forge-$forgeVersion.pom")
if (!userdevShimPom.isFile) {
    userdevShimPom.writeText(
        """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>net.minecraftforge</groupId>
  <artifactId>forge</artifactId>
  <version>$forgeVersion</version>
  <packaging>jar</packaging>
</project>
""",
    )
}

repositories {
    // 本地 shim：承载 forge module 的 POM 与全部 loom 所需产物（见上方说明）。
    // 注意：loom 在 apply 时已注入自己的 "Forge" 远程仓库（content 过滤 FORGE_GROUPS），
    // POM 在其命中即绑定该仓库、其余仓库不再参与；故必须把 shim 重排到仓库列表最前（见下）。
    maven {
        name = "ForgeUserdevShim"
        url = uri(userdevShimRoot.toURI())
        content { includeModule("net.minecraftforge", "forge") }
    }
    mavenCentral()
    // 无 POM 的 zip/jar 产物（MCP 映射数据 de.oceanlabs.mcp、Forge 托管的 scala-continuations _mc 版本）
    // 在 Forge maven 上只有 artifact 没有 POM，loom 注入的 Forge 仓库只查 mavenPom 会 miss；
    // 这里以 artifact-only 方式解析这些 group。
    maven {
        name = "MinecraftForgeArtifactOnly"
        url = uri("https://maven.minecraftforge.net/")
        metadataSources { artifact() }
        content {
            includeGroup("de.oceanlabs.mcp")
            includeGroup("org.scala-lang.plugins")
        }
    }
}
// 把 shim 重排到仓库列表最前（loom 的 Forge 仓库在 apply 阶段已插入列表头部，
// 后声明的 shim 若不重排则永远轮不到）
val forgeUserdevShim = repositories.getByName("ForgeUserdevShim")
repositories.remove(forgeUserdevShim)
repositories.add(0, forgeUserdevShim)

dependencies {
    // arch-loom 三段式声明（minecraft / mappings / runs 分开）：
    // 原版 MC 本体 + MCP snapshot 映射（1.12.2 无 Mojang 官方映射，沿用原 snapshot_20171003-1.12）
    // + Forge（arch-loom 自行解析 userdev / binpatches / SRG 数据）
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    // MCP snapshot 映射为 zip 产物（无 POM），@zip 指定扩展名，配合上方 artifact-only 仓库解析
    add("mappings", "de.oceanlabs.mcp:mcp_snapshot:$mappingsVersion@zip")
    add("forge", "net.minecraftforge:forge:$forgeVersion")

    add("implementation", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("productBundle", files(productSharedProjects.map { ForgeModules.moduleJar(project, it) }))

    // 纯 JVM 单元测试：JUnit 5（BOM 统一版本，与其它车道同源）。
    // 被测类为 Java 8 字节码，测试同样以 Java 8 语法编译 / 运行（toolchain 已固定 8）。
    add("testImplementation", platform("org.junit:junit-bom:5.10.3"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

    add("acceptanceImplementation", files(acceptanceSharedProjects.map { ForgeModules.moduleJar(project, it) }))
    add("acceptanceBundle", files(ForgeModules.moduleJar(project, ":modules:acceptance")))
}

loom {
    forge {
        // legacy forge（1.12.2）binpatches 为 pack200+lzma 打包，需要外部 Pack200 实现
        pack200Provider.set(Pack200Adapter())
    }
    runs {
        // runs.client 的取值：工作目录 / 系统属性 / mod 源集；
        // legacy forge（LaunchWrapper tweakClass）的启动装配由 arch-loom 运行模板自动完成
        maybeCreate("client").apply {
            runDirectory.set(project.file("run-client"))
            systemProperties.put("forge.logging.console.level", "info")
            // HYBRID CatServer 自连地址；可 -Dmpmt.acceptance.server=host:port 覆盖
            systemProperties.put("mpmt.acceptance.server", (project.findProperty("mpmt.acceptance.server") ?: "127.0.0.1:25568").toString())
            // 验收伴侣上报 ClientReady 用的 Java 可执行文件（runClient 执行期覆盖为 Java 8 launcher）
            systemProperties.put(
                "mpmt.acceptance.javaExecutable",
                System.getProperty("java.home") + File.separator + "bin" + File.separator +
                    (if (System.getProperty("os.name", "").lowercase().contains("win")) "java.exe" else "java"),
            )
            mods {
                maybeCreate("mpmt").apply { sourceSet(sourceSets["main"]) }
                maybeCreate("mpmt_acceptance").apply { sourceSet(sourceSets["acceptance"]) }
            }
        }
    }
}

val generatedBuildInfo = layout.buildDirectory.file("generated/sources/buildInfo/top/wcpe/mc/mpmt/platform/forge/ForgeBuildInfo.java").get().asFile
val generateBuildInfo by tasks.registering {
    inputs.property("version", project.version)
    outputs.file(generatedBuildInfo)
    doLast {
        generatedBuildInfo.parentFile.mkdirs()
        generatedBuildInfo.writeText(
            "package top.wcpe.mc.mpmt.platform.forge;\n\n" +
                "public final class ForgeBuildInfo {\n" +
                "    public static final String VERSION = \"${project.version}\";\n" +
                "    private ForgeBuildInfo() { }\n" +
                "}\n",
            Charsets.UTF_8,
        )
    }
}
sourceSets["main"].java.srcDir(file("${layout.buildDirectory.get().asFile}/generated/sources/buildInfo"))
tasks.named("compileJava") { dependsOn(generateBuildInfo) }

tasks.named<Jar>("jar") {
    // loom 约定：jar 产物改落 build/devlibs 且带 -dev 分类器；
    // build/libs 下的正式产品名（无分类器）由 remapJar 以生产命名输出（见 reobfJar 兼容层）
    from(productBundle.elements.map { elements -> elements.map { zipTree(it.asFile) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    FORGE_ARCHIVE_EXCLUDES.forEach { exclude(it) }
    exclude("top/wcpe/mc/mpmt/acceptance/**")
    exclude("top/wcpe/mc/mpmt/platform/forge/acceptance/**")
    manifest {
        attributes(
            "Implementation-Title" to "MPMT Forge 1.12.2 客户端产品",
            "Implementation-Version" to project.version,
            "FMLAT" to "",
        )
    }
}

// 验收伴侣 dev 命名中间产物（落 devlibs），剔除清单与产物名由车道参数给出
val acceptanceJar = registerForgeAcceptanceJar(project, forge)

// 验收伴侣生产产物：named(MCP) → srg 重映射，
// 输出保持 build/libs/mpmt-forge-acceptance-1.12.2-<version>.jar（根文档/契约既有预期）
val remapAcceptanceJar by tasks.registering(RemapJarTask::class) {
    group = "build"
    description = "将验收伴侣重映射到生产命名（srg），等价原 FG reobfAcceptanceJar"
    dependsOn(acceptanceJar)
    inputFile.set(acceptanceJar.flatMap { it.archiveFile })
    classpath.setFrom(sourceSets["acceptance"].runtimeClasspath)
    archiveBaseName.set("mpmt-forge-acceptance-1.12.2")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

// reobf 兼容任务（reobfJar / reobfAcceptanceJar）由 build-conventions.forge 注册：
// 根 :collectReleaseArtifacts 硬引用 platform/forge/1.12.2/build/reobfJar/output.jar，
// 根 realserver 说明沿用 `gradlew reobfJar reobfAcceptanceJar` 命令与产物路径。

/**
 * 将 client-only 产品 + 验收伴侣拷到 build/client-companion/，供 CatServer（HYBRID 矩阵）手工/编排消费。
 * 禁止作为服务端 mods 装入 CatServer。
 */
val prepareClientCompanionArtifacts by tasks.registering {
    group = "build"
    description = "拷贝 Forge 1.12.2 client-only 产品/验收 jar 到 build/client-companion/（供 CatServer（HYBRID 矩阵））"
    dependsOn("reobfJar", "reobfAcceptanceJar")
    doLast {
        val outDir = layout.buildDirectory.dir("client-companion").get().asFile
        outDir.mkdirs()
        val product = file("${layout.buildDirectory.get().asFile}/reobfJar/output.jar")
        val acceptanceReobf = file("${layout.buildDirectory.get().asFile}/reobfAcceptanceJar/output.jar")
        if (!product.isFile) {
            throw GradleException("缺少 reobf 产品 jar：$product")
        }
        // 优先 reobfAcceptanceJar 兼容路径，否则 libs 中的验收 remap 产物
        val acceptance = if (acceptanceReobf.isFile) acceptanceReobf else remapAcceptanceJar.get().archiveFile.get().asFile
        if (!acceptance.isFile) {
            throw GradleException("缺少验收伴侣 jar：$acceptance")
        }
        copy {
            from(product)
            into(outDir)
            rename { "mpmt-forge-1.12.2-${project.version}.jar" }
        }
        copy {
            from(acceptance)
            into(outDir)
            rename { "mpmt-forge-acceptance-1.12.2-${project.version}.jar" }
        }
        logger.lifecycle("[client-companion] ${outDir.absolutePath}")
        logger.lifecycle("[client-companion] 注意：client-only，禁止装入 CatServer mods/；HYBRID 客户端伴侣用")
    }
}

tasks.named("compileAcceptanceJava") { dependsOn(tasks.named("classes")) }
tasks.named("compileContractTestJava") { dependsOn(tasks.named("acceptanceClasses")) }

val java8Launcher =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        // 1.12.2 客户端/契约测试须 Java 8 运行；守护 JVM（≥21）与之解耦
        languageVersion.set(JavaLanguageVersion.of(8))
    }

// runClient 的取值：除 runDir / 系统属性外，1.12.2 客户端本体只能在 Java 8 上启动
tasks.matching { it.name == "runClient" }.configureEach {
    val runClientTask = this
    if (runClientTask is JavaExec) {
        runClientTask.javaLauncher.set(java8Launcher)
        runClientTask.doFirst {
            runClientTask.systemProperty("mpmt.acceptance.javaExecutable", java8Launcher.get().executablePath.asFile.absolutePath)
        }
    }
}

tasks.named("check") { dependsOn("contractTest") }
// build 须同时产出生产验收 jar 与根门禁/文档既有的 reobf* 兼容路径（产品 remapJar 由 assemble 链触发）
tasks.named("build") { dependsOn("remapAcceptanceJar", "reobfJar", "reobfAcceptanceJar") }

tasks.named<Delete>("clean") {
    doFirst {
        delete(project.file("run-client"))
    }
}
