import buildconventions.FORGE_ARCHIVE_EXCLUDES
import buildconventions.ForgeLaneExtension
import buildconventions.ForgeModules
import buildconventions.LaneBundle
import buildconventions.declareLaneBundles
import buildconventions.mpmtDomains
import buildconventions.registerForgeAcceptanceJar
import dev.architectury.pack200.java.Pack200Adapter
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.toolchain.JavaToolchainService

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
    remapAcceptanceJarName.set("mpmt-forge-acceptance-1.12.2")
    remapAcceptanceJarVersioned.set(true)
    // contractTest 已于 2026-10-29 移除（硬编码断言精确版本号，非正式版必失败）。
}

// 插件公开 API 需要扩展实例（块外传参用），故在块后取回一次
val forge = extensions.getByType(buildconventions.ForgeLaneExtension::class.java)
// 共享模块（L0-L2）项目路径；acceptance 仅进验收伴侣，不进产品
val productSharedProjects = listOf(":core:kernel", ":core:runtime", ":core:client", ":core:protocol")
val acceptanceSharedProjects = productSharedProjects + listOf(":modules:acceptance")
// 功能域（L0 玩法逻辑）：动态发现，新增域无需改上面的字面量清单（ADR-0028）
val domainProjects = mpmtDomains().map { it.path }

declareLaneBundles(LaneBundle.PRODUCT, LaneBundle.ACCEPTANCE)

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
}

// forge 1.12.2 的 userdev 由 WCPE Loom 内建处理（ADR-0025 升级项）：loom 会按版本选择
// :userdev / :userdev3 分类器，并把 FG2.3 过渡形态归一为 legacy 链路的 FG2 形态
// （补 merged_at.cfg / sources.zip、重写 config.json、精简 libraries）。
// 此前这里曾有一整套"本地镜像（userdev shim）"：配置期下载 forge 制品、改写并放进
// build/ 下本地仓库再重排到仓库列表最前。改造上移到 loom 后该段已删除。

dependencies {
    // arch-loom 三段式声明（minecraft / mappings / runs 分开）：
    // 原版 MC 本体 + MCP snapshot 映射（1.12.2 无 Mojang 官方映射，沿用原 snapshot_20171003-1.12）
    // + Forge（arch-loom 自行解析 userdev / binpatches / SRG 数据）
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    // MCP snapshot 映射为 zip 产物（无 POM），@zip 指定扩展名，配合上方 artifact-only 仓库解析
    add("mappings", "de.oceanlabs.mcp:mcp_snapshot:$mappingsVersion@zip")
    add("forge", "net.minecraftforge:forge:$forgeVersion")

    // vecmath：Forge 1.12.2 userdev config.json 的 libraries 清单要求 java3d:vecmath:1.5.2，
    // 但 loom 未把它带进运行期类路径。Minecraft.<init> 用 javax.vecmath.Tuple4f 时缺类即崩，
    // 故在此显式补齐；该制品仅存在于 Forge 官方 maven（见下方 repositories 段）。
    add("minecraftLibraries", "java3d:vecmath:1.5.2")
    add("implementation", "java3d:vecmath:1.5.2")

    // 域与共享核心同一批非传递项目依赖：既有条目顺序不变，域追加在末尾（ADR-0028）。
    val allProductProjects = productSharedProjects + domainProjects
    allProductProjects.forEach {
        add("implementation", ForgeModules.moduleJar(project, it))
        add("productBundle", ForgeModules.moduleJar(project, it))
    }

    // 纯 JVM 单元测试：JUnit 5（BOM 统一版本，与其它车道同源）。
    // 被测类为 Java 8 字节码，测试同样以 Java 8 语法编译 / 运行（toolchain 已固定 8）。
    add("testImplementation", platform("org.junit:junit-bom:5.14.4"))
    add("testImplementation", "org.junit.jupiter:junit-jupiter")
    add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")

    acceptanceSharedProjects.forEach {
        add("acceptanceImplementation", ForgeModules.moduleJar(project, it))
    }
    add("acceptanceBundle", ForgeModules.moduleJar(project, ":modules:acceptance"))
}

// 运行期注入的 srg→named 映射表：取 loom 的 generateSrgNamedMappings 产物。
// 它同时满足「类名 unmap 恒等」与「成员名 srg→MCP 可翻译」两个约束，缺一即分别导致
// tweaker 阶段 defineClass(null) NPE 或 ForgeHooks.initTools 的 NoSuchFieldException。
// 该任务由 loom 延迟注册、类型也不在本次编译类路径内，故只在执行期按名解析并注入。
tasks.matching { it.name == "runClient" }.configureEach {
    dependsOn("generateSrgNamedMappings")
    // 显式给堆上限。不设时 Java 8 按物理内存自动推算：本机 251 GB 内存下实测拿到 27 GB
    // （崩溃报告里 JVM Flags 只有 -XX:StackShadowPages=32，没有 -Xmx，上限 27305 MB）。而一个
    // 1.12.2 客户端实际只用约 1 GB——允许它长到 27 GB 只会在系统内存吃紧时让它成为 OOM killer
    // 的首选目标。实测症状就是进程输出停止后被 SIGKILL（runClient 报退出码 137），Gradle 空等到
    // 超时才报 BUILD FAILED。设成固定值后，内存占用与机器规格无关。
    //
    // 注意：不能用 loom 的 RunConfigSettings.vmArgs(...)。本仓库所用的 loom 分支里
    // RunConfigSettings.getVmArgs() 没有任何消费方（全局 grep 零命中），写了也不会进真实命令行。
    // 这里按 JavaExec 判断后设置：configureEach 的接收者是 Task，不加判断就取不到 jvmArgs。
    if (this is JavaExec) {
        jvmArgs("-Xmx2G", "-Xms512M")
    }
    doFirst {
        val srgFile =
            tasks
                .named("generateSrgNamedMappings")
                .get()
                .property("srgFile")
                .let { p ->
                    @Suppress("UNCHECKED_CAST")
                    (p as Provider<RegularFile>).get().asFile
                }
        (this as JavaExec).systemProperty(
            "net.minecraftforge.gradle.GradleStart.srg.srg-mcp",
            srgFile.absolutePath,
        )
    }
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
            // 让 FMLDeobfuscatingRemapper 的 srg 映射表为空，使 unmap/remap 退化为恒等。
            // 前提已由 loom 侧满足：forge.jar 现在被重映射成 named 名（见 MinecraftLegacyPatchedProvider
            // 的 remapForgeToNamed），与 MC jar 的命名体系一致，因此运行期不需要任何名变换。
            // 若保留完整 notch→MCP 表，DeobfuscationTransformer.unmapClassName 会把 MCP 名反查成
            // notch 名（如 ICrashReportDetail -> "d"），jar 里没有该条目 → getClassBytes 返回
            // null → defineClass(null) 在 LaunchClassLoader:182 抛 NPE，该类随即被钉进
            // invalidClasses 永久不可加载（这是 tweaker 阶段崩溃的直接原因）。
            //
            // 但「空表」会走到另一个极端：Forge 的 ObfuscationReflectionHelper.remapFieldName 靠
            // FMLDeobfuscatingRemapper 的 unmap + mapFieldName 把 srg 名翻成 MCP 名，空表令其退化为
            // 恒等，于是 ForgeHooks.initTools 按 field_150915_c 找不到 EFFECTIVE_ON，抛
            // NoSuchFieldException。
            //
            // 正解是 srg→named 表（GenerateSrgNamedMappingsTask 的产物），它同时满足两侧：
            //   · CL 行源名与目标名逐一相同（实测 3321 条全恒等）→ unmapClassName 恒等，类加载不受影响；
            //   · FD/MD 行有真实的 srg→MCP 映射 → 反射路径能翻成 EFFECTIVE_ON。
            // 该属性的注入见本文件末尾的 tasks.matching { runClient } 块（执行期求值），
            // 因为产物任务由 loom 延迟注册，配置期按名取会失败。
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

val generatedBuildInfo =
    layout.buildDirectory
        .file("generated/sources/buildInfo/top/wcpe/mc/mpmt/platform/forge/ForgeBuildInfo.java")
        .get()
        .asFile
val generateBuildInfo =
    tasks.register("generateBuildInfo") {
        // 版本与目标文件在配置期取成局部值：动作只捕获这些值，不捕获 project / 脚本对象（配置缓存要求），
        // 生成内容与产物路径逐字不变。
        val buildInfoVersion = project.version.toString()
        val buildInfoFile = generatedBuildInfo
        inputs.property("version", buildInfoVersion)
        outputs.file(buildInfoFile)
        doLast {
            buildInfoFile.parentFile.mkdirs()
            buildInfoFile.writeText(
                "package top.wcpe.mc.mpmt.platform.forge;\n\n" +
                    "public final class ForgeBuildInfo {\n" +
                    "    public static final String VERSION = \"$buildInfoVersion\";\n" +
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
    // 解包形态与 build-conventions 的 ForgePackaging 一致：provider 内按名取配置再 zipTree，
    // 使 from() 不捕获脚本对象（配置缓存要求）；打入内容不变。
    from(project.provider { project.configurations.getByName("productBundle").map { project.zipTree(it) } })
    // provider 内解包会让 Gradle 看不到产物来源，须显式声明配置级任务依赖。
    dependsOn(project.configurations.named("productBundle"))
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

// reobf 兼容任务（reobfJar / reobfAcceptanceJar）由 build-conventions.forge 注册：
// 根 :collectReleaseArtifacts 硬引用 platform/forge/1.12.2/build/reobfJar/output.jar，
// 根 realserver 说明沿用 `gradlew reobfJar reobfAcceptanceJar` 命令与产物路径。

/**
 * 将 client-only 产品 + 验收伴侣拷到 build/client-companion/，供 CatServer（HYBRID 矩阵）手工/编排消费。
 * 禁止作为服务端 mods 装入 CatServer。
 */
val prepareClientCompanionArtifacts =
    tasks.register("prepareClientCompanionArtifacts") {
        group = "build"
        description = "拷贝 Forge 1.12.2 client-only 产品/验收 jar 到 build/client-companion/（供 CatServer（HYBRID 矩阵））"
        dependsOn("reobfJar", "reobfAcceptanceJar")
        doLast {
            val outDir =
                layout.buildDirectory
                    .dir("client-companion")
                    .get()
                    .asFile
            outDir.mkdirs()
            val product = file("${layout.buildDirectory.get().asFile}/reobfJar/output.jar")
            val acceptanceReobf = file("${layout.buildDirectory.get().asFile}/reobfAcceptanceJar/output.jar")
            if (!product.isFile) {
                throw GradleException("缺少 reobf 产品 jar：$product")
            }
            // 优先 reobfAcceptanceJar 兼容路径，否则 libs 中的验收 remap 产物
            // （remapAcceptanceJar 由 build-conventions.forge 注册，按名取归档任务，不直引 loom 类型）
            val acceptance =
                if (acceptanceReobf.isFile) {
                    acceptanceReobf
                } else {
                    tasks
                        .named("remapAcceptanceJar", AbstractArchiveTask::class.java)
                        .get()
                        .archiveFile
                        .get()
                        .asFile
                }
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
            runClientTask.systemProperty(
                "mpmt.acceptance.javaExecutable",
                java8Launcher
                    .get()
                    .executablePath.asFile.absolutePath,
            )
        }
    }
}

// contractTest 已移除（2026-10-29）
// build 须同时产出生产验收 jar 与根门禁/文档既有的 reobf* 兼容路径（产品 remapJar 由 assemble 链触发）
tasks.named("build") { dependsOn("remapAcceptanceJar", "reobfJar", "reobfAcceptanceJar") }

tasks.named<Delete>("clean") {
    // 待删目录在配置期取出：动作只捕获 File，不访问 project（配置缓存要求），删除目标不变。
    val runClientDirectory = project.file("run-client")
    doFirst {
        delete(runClientDirectory)
    }
}
