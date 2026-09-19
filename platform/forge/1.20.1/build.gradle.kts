import buildconventions.ForgeLaneExtension
import buildconventions.configureForgeShadowProductChain
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import java.security.MessageDigest

// Forge 1.20.1 车道（根构建子模块）：common + server + client 分目录 → mpmt-forge-1.20.1-<version>.jar。
// 不可变契约：产物名与路径（remapJar 无 classifier 输出即最终产品 jar）、SRG remap 链路与 Mixin 配置
// （mpmt.mixins.json / mpmt.refmap.json，ADR-0018）、mods.toml 与 services 断言、打包链路（ADR-0012）、
// realserver 报告路径与判定强度（ADR-0014）。质量门禁真源在 build-conventions.quality（ADR-0027）。

plugins {
    id("build-conventions.quality")
    java
    id("top.wcpe.loom")
    // 8.3.11：修复 RelocatorRemapper.mapValue 与 loom 依赖树上新 ASM（visitLdcInsn 传 Type）的不兼容
    id("com.gradleup.shadow") version "8.3.11"
    // 静态分析插件（checkstyle / pmd / spotbugs+findsecbugs / jacoco / ktlint / detekt / kover）一律由
    // build-conventions.quality 应用：版本与类路径由根 plugins{} 单点 pin，车道内重复声明会分裂插件类加载器
    // 并破坏 loom 的清单服务。
    id("build-conventions.forge")
}

val forgeVersion = "1.20.1-47.4.2"

// forge 车道参数：本车道走 shadow 打包链路（无 dev SecureJar 嵌入），只声明 lane 标签与 reobf 兼容任务映射
forgeLane {
    mcVersion.set("1.20.1")
    targetJavaVersion.set(17)
    laneLabel.set("Forge 1.20.1")
    reobfCopyTasks.put("reobfShadowJar", "remapJar")
    reobfCopyTasks.put("reobfAcceptanceJar", "remapAcceptanceJar")
    remapAcceptanceJarName.set("mpmt-acceptance-forge")
}

// 插件公开 API 需要扩展实例（块外传参用），故在块后取回一次
val forge = extensions.getByType(buildconventions.ForgeLaneExtension::class.java)
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经项目依赖消费
val platformApiProject = project(":platform:forge:forge-api")
val spiProject = project(":core:spi")
// 服务端公共网络特性（经 api 传递 protocol + core-runtime），各平台注入 TransportPort 后复用同一份装配
val serverProject = project(":core:server")
// 客户端公共网络特性（握手、心跳与重同步），仅复用仓库现有第一方模块
val clientProject = project(":core:client")

base {
    archivesName.set("mpmt-forge-1.20.1")
}

java {
    // Forge 1.20.1 运行于 Java 17
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// 单版本构建内：common / server / client 分目录（服客分离）；loom 仅挂本根
sourceSets.named("main") {
    java.setSrcDirs(
        listOf(
            "common/src/main/java",
            "server/src/main/java",
            "client/src/main/java",
        ),
    )
    resources.setSrcDirs(listOf("common/src/main/resources"))
}
sourceSets.named("test") {
    java.setSrcDirs(listOf("common/src/test/java"))
}

// ============================================================================
// loom 配置：Mixin AP + Forge mixin 配置 + dev run
// ============================================================================
loom {
    // Mixin（ADR-0018）：显式启用 legacy Mixin AP（arch-loom 1.13 默认关闭，而 Forge 生产期需要 compile 期
    // refmap：Mojmap→SRG，dev↔dev 运行期再经 disableRefMap 直解 Mojmap 名——静态 remap 会把注解值写成 SRG，
    // 破坏 dev run）；注册 main 源集并固定 refmap 名。
    mixin {
        useLegacyMixinAp.set(true)
        add(sourceSets["main"], "mpmt.refmap.json")
    }
    // dev run 需要知道本 mod 的 mixin 配置；同时 loom 会把配置名写进 jar 清单 MixinConfigs 属性。
    forge {
        mixinConfigs("mpmt.mixins.json")
    }
    runs {
        // loom 已为 forge 预建默认 client/server 运行配置（client()/server() 模板已应用），这里只补齐本车道取值。
        // realserver 验收用客户端运行配置：loom 负责 dev MC 客户端 + 原生 + 资源（headless 可渲染）。
        // 不声明 loom.mods dev 源（MOD_CLASSES 留空、FML 不注入 dev 源集；dev 源会撞 FML 不暴露 core 的
        // classpath 墙）；改把 remap 后的 shaded jar（core 在 jar 内）放进 run-client/mods/，
        // 让 FML 当真实 jar mod 加载，绕过 dev classpath 墙。
        getByName("client") {
            configName = "Forge Client"
            runDir("run-client")
            property("forge.logging.console.level", "info")
            // 激活验收客户端伴侣（Dist.CLIENT）：伴侣到主菜单后程序化连入本机服务端
            // （Forge dev 客户端不可靠处理 --quickPlayMultiplayer，故由伴侣自连）。
            // 默认 host:port 对齐 run-server/server.properties 的 25566；异构可 -Pmpmt.acceptance.server=host:port 覆盖。
            property("mpmt.acceptance", "true")
            property(
                "mpmt.acceptance.server",
                (project.findProperty("mpmt.acceptance.server") as String?) ?: "127.0.0.1:25566",
            )
            // dev↔dev 为 Mojmap 运行期，而 mods/ 内 jar 带的是生产 refmap（Mojmap→SRG）；jar 经 mods/ 加载、未走
            // dev 源集的 refmap 回映射注入，故 dev 关闭 refmap、直接按注解里的 Mojmap 名解析（ADR-0018）。
            // 生产（真实 Forge 服，SRG 运行期）不经本 run 配置、照常用 refmap，不受影响。
            property("mixin.env.disableRefMap", "true")
        }
        // realserver 验收用服务端运行配置（dev 服）：与客户端同为 dev / Mojmap，使 FML 握手 dev↔dev 兼容
        // （dev 客户端连真实生产服会握手不通）。同样从 run-server/mods 加载 shaded jar，绕过 dev classpath 墙。
        // loom 默认附加 --nogui 程序参数（headless，验收不受影响）。
        getByName("server") {
            configName = "Forge Server"
            runDir("run-server")
            property("forge.logging.console.level", "info")
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", project.file("run-server/acceptance-report.txt").absolutePath)
            property("mpmt.acceptance.deadlineMs", "660000")
            // v2 元数据：commit 配置期取 git；productJar 供驱动算 SHA（remapJar 无 classifier 产物，即最终产品 jar）
            property(
                "mpmt.acceptance.commit",
                providers
                    .exec { commandLine("git", "rev-parse", "HEAD") }
                    .standardOutput.asText
                    .get()
                    .trim(),
            )
            property("mpmt.acceptance.version", project.version.toString())
            property("mpmt.acceptance.platform", "forge")
            property("mpmt.acceptance.mcVersion", "1.20.1")
            property("mpmt.acceptance.serverVersion", forgeVersion)
            property(
                "mpmt.acceptance.productJar",
                // 与 archivesName=mpmt-forge-1.20.1 对齐
                layout.buildDirectory
                    .file("libs/mpmt-forge-1.20.1-${project.version}.jar")
                    .get()
                    .asFile
                    .absolutePath,
            )
            // 同 client：dev↔dev Mojmap 运行期关闭 refmap，按 Mojmap 名解析（生产 SRG 不受影响，ADR-0018）
            property("mixin.env.disableRefMap", "true")
        }
    }
}

repositories {
    mavenCentral()
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖），不参与 remap
val shadowBundle: Configuration by configurations.creating

dependencies {
    // userdev 单坐标拆分（arch-loom）：原版 MC + 官方 Mojang 映射（ADR-0016）+ Forge（arch-loom 的 forge
    // 配置，由其解析 userdev 并产出 patched MC dev jar）
    minecraft("com.mojang:minecraft:1.20.1")
    mappings(loom.officialMojangMappings())
    "forge"("net.minecraftforge:forge:$forgeVersion")

    // 共享核心（platform-spi + 传递的 core-runtime/core-domain）：纯 Java、shade 进 mod jar
    implementation(platformApiProject)
    implementation(spiProject)
    shadowBundle(platformApiProject)
    shadowBundle(spiProject)
    // 服务端公共网络特性（FR-19）：纯 Java、shade 进 mod jar（传递 protocol）
    implementation(serverProject)
    shadowBundle(serverProject)
    // 客户端公共网络特性（FR-22/FR-28）：纯 Java、shade 进同一产品 jar
    implementation(clientProject)
    shadowBundle(clientProject)
    // 第三方运行期依赖：shade 并 relocate（ADR-0012）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")
    // Mixin 注解处理器由 arch-loom 内建提供（loom.mixin.useLegacyMixinAp），无需再声明 processor 依赖

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":modules:acceptance"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 打包链路：shadowJar（shade core/spi + relocate snakeyaml，产物名 -dev-shadow）→ remapJar（named → SRG，
// 产出无 classifier 的最终产品 jar）由 build-conventions.forge 接线
configureForgeShadowProductChain(project, "shadowBundle", "mpmt.mixins.json")

// 打包校验：mod jar 内核心 shade、snakeyaml relocate、mods.toml 与 services 在位、未误打入 Minecraft
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Forge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、SRG remap、未打入 Minecraft"
    dependsOn(tasks.named("remapJar"))
    // 输出冲突与可复现性断言所需取值在配置期取出：动作内不得再访问 project / Task（配置缓存要求），
    // 断言取值与判定顺序、失败文案与迁移前逐字一致。
    val shadowJarTask = tasks.named<ShadowJar>("shadowJar").get()
    val plainArchive =
        tasks
            .named<Jar>("jar")
            .get()
            .archiveFile
            .get()
            .asFile
    val shadowPreservesTimestamps = shadowJarTask.isPreserveFileTimestamps
    val shadowReproducibleOrder = shadowJarTask.isReproducibleFileOrder
    packagingVerification(
        laneLabel = "Forge",
        product = tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
        acceptance = null,
    ) { product, _ ->
        must(plainArchive != product.file, "普通 jar 与最终 remapJar 输出路径冲突")
        must(!shadowPreservesTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
        must(shadowReproducibleOrder, "最终产品未启用可复现文件顺序")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "核心类未 shade 进 mod jar")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "platform-spi 未 shade 进 mod jar")
        mustContain(product, "top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class", "缺少 Forge mod 主类")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "snakeyaml 未 relocate 到 libs.*")
        mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
        mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
        mustContain(product, "META-INF/mods.toml", "缺少 META-INF/mods.toml")
        mustContain(product, "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap", "缺少 SPI services 声明")
        // Mixin（ADR-0018）：配置 + refmap 须在产物内，否则生产期 mixin apply 失败（target method not found）
        mustContain(product, "mpmt.mixins.json", "缺少 Mixin 配置 mpmt.mixins.json")
        mustContain(product, "mpmt.refmap.json", "缺少 Mixin refmap（AP 未生成或未打包，生产期会 apply 失败）")
        mustNotBundle(product, listOf("net/minecraft/"), "误把 Minecraft 类打入 mod jar")
        println("Forge 打包校验通过：")
        println(" 产物 = ${product.file.name}（条目数 ${product.entries.size}）")
        println(" 核心已 shade、snakeyaml 已 relocate、mods.toml/services 在位、已 remap 到 SRG、未打入 Minecraft")
    }
}

tasks.named("assemble") {
    dependsOn(verifyPackaging)
}

tasks.named("build") {
    dependsOn("reobfShadowJar", verifyPackaging)
}

// ============================================================================
// realserver 验收驱动（独立 acceptance 源集 + 独立 shaded+remap mod jar，ADR-0014）
// Forge dev run 因 FML 模块层不向 mod 暴露库依赖而不可用（已证），故 Forge 与 Bukkit 同走 realserver：
// 真实 Forge 专用服 + 独立 acceptance mod jar。验收驱动代码不入产品 mod jar：单独打 mpmt-acceptance mod，
// 仅在验收运行期放入服务端 mods/。
// 编译期继承 main 的类路径（含 arch-loom 提供的 patched MC + Forge API）+ main 产物，并叠加 acceptance 核心 + protocol。
// ============================================================================
val acceptanceProject = project(":modules:acceptance")
val protocolProject = project(":core:protocol")

val acceptance: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["acceptanceImplementation"].extendsFrom(configurations["implementation"])

val acceptanceTest: SourceSet by sourceSets.creating {
    compileClasspath += acceptance.output + sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}
configurations["acceptanceTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["acceptanceTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// 专用配置：需 shade 进验收 mod jar 的内容——**只含 acceptance 核心**（验收 jar 独有、不在产品 jar 里）。
// protocol/core-domain 等已在产品 mod jar 内：Forge 的 FML 模块层禁止两个 mod 导出同名包（split package，
// 否则 ResolutionException 启动失败），故验收 jar 不能再打 protocol/core-domain；运行期由产品 mod 提供
// （FML mod 为自动模块，验收 mod 可读取产品 mod 的包）。protocol 仅作编译期依赖（compileOnly），不入产物。
val acceptanceShadowBundle: Configuration by configurations.creating

dependencies {
    // 平台无关验收核心（控制协议 / 协调 / GameTest 框架 / 报告）：验收 jar 独有，shade 进去
    "acceptanceImplementation"(acceptanceProject)
    acceptanceShadowBundle(acceptanceProject)
    // protocol（编 HUD 包用）：仅编译期可见，运行期由产品 mod 提供——绝不打进验收 jar（防 split package）
    "acceptanceCompileOnly"(protocolProject)
}

// 验收驱动 mod jar：shade acceptance/protocol/core-domain（均第一方、无第三方运行期依赖，无需 relocate），
// 产物名 -dev-shadow，作为 remapAcceptanceJar 的输入
val acceptanceJar by tasks.registering(ShadowJar::class) {
    group = "build"
    description = "构建 realserver 验收驱动 mod mpmt-acceptance（仅验收运行期用，不入产品 jar）"
    archiveBaseName.set("mpmt-acceptance-forge")
    archiveClassifier.set("dev-shadow")
    from(acceptance.output)
    configurations = listOf(acceptanceShadowBundle)
    exclude("META-INF/maven/**")
    // MixinConfigs 清单属性指向产品 jar 内的 mpmt.mixins.json（验收 mod 与产品 mod 共用同一 Mixin 配置）
    manifest {
        attributes("MixinConfigs" to "mpmt.mixins.json")
    }
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 把验收源集纳入常规 build 的编译校验（只编译，不打包——打包由验收编排按需触发）
val acceptanceContractTest by tasks.registering(Test::class) {
    group = "verification"
    description = "运行 Forge acceptance v2 与完整默认轨场景契约测试"
    testClassesDirs = acceptanceTest.output.classesDirs
    classpath = acceptanceTest.runtimeClasspath
    useJUnitPlatform()
    dependsOn(tasks.named("acceptanceClasses"))
}

val simAcceptanceReport = layout.buildDirectory.file("acceptance/sim-report-v2.txt")
val realAcceptanceReport =
    providers
        .gradleProperty("mpmt.acceptance.report")
        .map { file(it) }
        .orElse(provider { file("run-server/acceptance-report.txt") })

val runSimNetworkAcceptance by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "运行 Forge 1.20.1 完整默认轨模拟服套件并生成 acceptance v2 报告"
    classpath = acceptance.runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.forge.acceptance.sim.ForgeDefaultSimulation")
    dependsOn("acceptanceClasses", "reobfShadowJar")
    systemProperty("mpmt.acceptance.report", simAcceptanceReport.get().asFile.absolutePath)
    systemProperty("mpmt.acceptance.version", project.version.toString())
    systemProperty("mpmt.acceptance.platform", "forge")
    systemProperty("mpmt.acceptance.mcVersion", "1.20.1")
    systemProperty("mpmt.acceptance.serverVersion", forgeVersion)
    doFirst {
        val product =
            tasks
                .named<RemapJarTask>("remapJar")
                .get()
                .archiveFile
                .get()
                .asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(product.readBytes())
        systemProperty("mpmt.acceptance.productJarSha256", digest.joinToString("") { byte -> "%02x".format(byte) })
        val commit =
            providers
                .exec { commandLine("git", "rev-parse", "HEAD") }
                .standardOutput.asText
                .get()
                .trim()
        systemProperty("mpmt.acceptance.commit", commit)
    }
}

val verifyAcceptanceReport by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "严格校验 Forge acceptance v2 报告，缺元数据、场景或 PASS 均失败"
    classpath = acceptance.runtimeClasspath
    mainClass.set("top.wcpe.mc.mpmt.platform.forge.acceptance.sim.ForgeDefaultSimulation")
    dependsOn(tasks.named("acceptanceClasses"))
    doFirst {
        val report = realAcceptanceReport.get()
        if (!report.isFile) {
            throw GradleException("未找到 Forge 验收报告：${report.absolutePath}")
        }
        args("verify", report.absolutePath)
    }
}

// B 车道：Forge 专用服 + 自有 acceptance 客户端伴侣进服后读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "Forge realserver 门禁：校验权威报告（须先 runServer/专用服 + Forge acceptance 客户端 gametest）"
    dependsOn(verifyAcceptanceReport)
}

// 把验收源集与契约测试纳入常规 build/check，验收驱动仍不进入产品 jar。
tasks.named("build") {
    dependsOn(tasks.named("acceptanceClasses"), acceptanceContractTest)
}
tasks.named("check") {
    dependsOn(acceptanceContractTest)
}
