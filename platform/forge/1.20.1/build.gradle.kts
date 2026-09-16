import buildconventions.ForgeLaneExtension
import buildconventions.configureForgeShadowProductChain
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.jvm.toolchain.JavaToolchainService
import java.security.MessageDigest

// platform-forge（L3）：根构建普通子模块，仅应用 arch-loom（top.wcpe.loom，ADR-0007）。
// 打包链路（ADR-0012）：shade platform-spi + core + relocate snakeyaml 进 mod jar，再经 remapJar remap 到
// SRG 供真实 Forge 运行。映射用官方（ADR-0016）。core/snakeyaml 为纯 Java、无 MC 引用，remap 不改写之。
// Mixin（ADR-0018）：Forge 端裸 CustomPayload 收包经 Mixin 拦截原版 handleCustomPayload 路由到我方 receiver，
// 打通 Forge↔Forge 与 Forge↔Bukkit。Mixin AP 由 arch-loom 内建提供（useLegacyMixinAp + add 注册 main 源集），
// 取代旧 MixinGradle（buildscript classpath + apply）。

plugins {
    id("build-conventions.quality")
    java
    id("top.wcpe.loom")
    // 8.3.11：修复 RelocatorRemapper.mapValue 与 loom 依赖树上新 ASM（visitLdcInsn 传 Type）的不兼容
    id("com.gradleup.shadow") version "8.3.11"
    // 静态分析 / 质量工具链由根构建 subprojects{} 统一提供（含 spotbugs/ktlint/detekt/kover，见 ADR-0026）。
    // 车道内重复声明会分裂插件类加载器并破坏 loom 清单服务，故此处不再声明。
    // 历史说明：静态分析 / 质量工具链（严格门禁，static-analysis.md）——与根构建同一套，共享 ../config 规则集。
    // 核心 Gradle 插件（checkstyle/pmd/jacoco）直接 apply；外部分析插件经 plugins{} 声明。
    checkstyle
    pmd
    jacoco
    id("build-conventions.forge")
}

val forgeVersion = "1.20.1-47.4.2"

// forge 车道参数：本车道走 shadow 打包链路（无 dev SecureJar 嵌入），只声明 lane 标签与 FG 时代 reobf 兼容路径
val forge = extensions.getByType(ForgeLaneExtension::class.java)
forge.mcVersion.set("1.20.1")
forge.targetJavaVersion.set(17)
forge.laneLabel.set("Forge 1.20.1")
forge.reobfCopyTasks.put("reobfShadowJar", "remapJar")
forge.reobfCopyTasks.put("reobfAcceptanceJar", "remapAcceptanceJar")
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经根构建项目依赖消费
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
// loom 配置：Mixin AP + Forge mixin 配置 + dev run（替代 FG minecraft{} 与 MixinGradle）
// ============================================================================
loom {
    // Mixin（ADR-0018）：显式启用 legacy Mixin AP（arch-loom 1.13 默认关闭，而 Forge 生产期需要 compile 期
    // refmap：Mojmap→SRG，dev↔dev 运行期再经 disableRefMap 直解 Mojmap 名——静态 remap 会把注解值写成 SRG，
    // 破坏 dev run）；注册 main 源集并固定 refmap 名，等价旧 MixinGradle 的 add(...)。
    mixin {
        useLegacyMixinAp.set(true)
        add(sourceSets["main"], "mpmt.refmap.json")
    }
    // dev run 需要知道本 mod 的 mixin 配置；同时 loom 会把配置名写进 jar 清单 MixinConfigs 属性，
    // 等价旧 MixinGradle 的 config(...)。
    forge {
        mixinConfigs("mpmt.mixins.json")
    }
    runs {
        // loom 已为 forge 预建默认 client/server 运行配置（client()/server() 模板已应用），这里只做等价移植。
        // realserver 验收用客户端运行配置：loom 负责 dev MC 客户端 + 原生 + 资源（headless 可渲染，同 FG）。
        // 不声明 loom.mods dev 源（MOD_CLASSES 留空、FML 不注入 dev 源集，与 FG 不声明 mods{} 等价；
        // dev 源会撞 FML 不暴露 core 的 classpath 墙）；改把 remap 后的 shaded jar（core 在 jar 内）
        // 放进 run-client/mods/，让 FML 当真实 jar mod 加载，绕过 dev classpath 墙。
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
                providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim(),
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

// EGT defaults 同款：loom 声明的 Forge maven 仓库默认元数据源仅 pom，补 artifact() 兜底，
// 避免 net.minecraftforge:forge 的 userdev 分类器产物解析异常
repositories.find { it.name == "Forge" }?.let { repo ->
    (repo as? MavenArtifactRepository)?.metadataSources {
        mavenPom()
        artifact()
        ignoreGradleMetadataRedirection()
    }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core/spi + 第三方运行期依赖），不参与 remap
val shadowBundle: Configuration by configurations.creating

dependencies {
    // FG 单坐标拆分（arch-loom）：原版 MC + 官方 Mojang 映射（ADR-0016）+ Forge（arch-loom forge 配置，
    // 由其解析 userdev 并产出 patched MC dev jar）
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
    // Mixin 注解处理器由 arch-loom 内建提供（loom.mixin.useLegacyMixinAp），无需手工挂 0.8.5:processor

    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(project(":modules:acceptance"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// 打包链路：shadowJar（shade core/spi + relocate snakeyaml，产物名 -dev-shadow）→ remapJar（named → SRG，
// arch-loom 承担 FG reobf 的 forge 生产命名，产出无 classifier 的最终产品 jar）由 build-conventions.forge 接线
configureForgeShadowProductChain(project, "shadowBundle", "mpmt.mixins.json")

// 打包校验：mod jar 内核心 shade、snakeyaml relocate、mods.toml 与 services 在位、未误打入 Minecraft
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Forge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、SRG remap、未打入 Minecraft"
    dependsOn(tasks.named("remapJar"))
    packagingVerification(
        laneLabel = "Forge",
        product = tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
        acceptance = null,
    ) { product, _ ->
        val shadow = tasks.named<ShadowJar>("shadowJar").get()
        val plain = tasks.named<Jar>("jar").get()
        must(plain.archiveFile.get().asFile != product.file, "普通 jar 与最终 remapJar 输出路径冲突")
        must(!shadow.isPreserveFileTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
        must(shadow.isReproducibleFileOrder, "最终产品未启用可复现文件顺序")
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
// 静态分析 / 质量工具链配置（严格门禁，static-analysis.md）——照根构建 subprojects 同一套，
// 共享根 config 规则集（经 rootProject.file("config/...") 引用）；
// 违规即失败构建（isIgnoreFailures=false）。本工程为单模块工程，故直接 apply、不用 subprojects 块。
// ============================================================================
// 样式审查：Checkstyle（共享裁剪规则集）
configure<CheckstyleExtension> {
    toolVersion = "10.17.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
// 代码异味 / 源码规则：PMD（共享裁剪规则集）
configure<PmdExtension> {
    toolVersion = "7.0.0"
    isConsoleOutput = true
    ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
    ruleSets = emptyList()
    isIgnoreFailures = false
}
// 测试覆盖率：JaCoCo（报告 only——平台胶水覆盖率由 realserver 验收门保障，此处不设覆盖率底线、不并入 check）
tasks.withType(org.gradle.testing.jacoco.tasks.JacocoReport::class.java).configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
tasks.withType(Test::class.java).configureEach {
    finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
}
// 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
configure<SpotBugsExtension> {
    ignoreFailures.set(false)
    effort.set(Effort.MAX)
    // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
}
dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
// lombok.config 由根构建 subprojects{} 统一登记为编译输入（ADR-0026），此处不再重复。
// 分析工具运行 JVM 与被测模块目标字节码无关：Checkstyle 10.x 需 JDK 11+，故把 Checkstyle/Pmd 分析任务
// 固定到 JDK 17 启动器运行。afterEvaluate：JavaToolchainService 由 java 插件注册、晚于本配置块。
afterEvaluate {
    val toolchains = extensions.getByType(JavaToolchainService::class.java)
    val analysisLauncher =
        toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    tasks.withType(Checkstyle::class.java).configureEach {
        javaLauncher.set(analysisLauncher)
    }
    tasks.withType(Pmd::class.java).configureEach {
        javaLauncher.set(analysisLauncher)
    }
    // SpotBugs worker 默认用守护 JVM（JDK 17），无需固定 launcher。
    // 仅生产码（spotbugsMain）严格门禁；test / acceptance 等非 main 源集宽松（含 mock/反射等 SpotBugs 噪声）。
    tasks.withType(SpotBugsTask::class.java).configureEach {
        if (name != "spotbugsMain") {
            ignoreFailures = true
        }
    }
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
    // 与旧 MixinGradle 产物一致的 MixinConfigs 清单属性（值指向产品 jar 内的 mpmt.mixins.json，逐项等价）
    manifest {
        attributes("MixinConfigs" to "mpmt.mixins.json")
    }
    // shadow 改配置不刷新缓存指纹，令其确定性重跑（与产品 shadowJar 一致）
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// 对验收 mod jar remap（named → SRG），令其能在真实 Forge 服运行；产物 build/libs/mpmt-acceptance-forge-*.jar
val remapAcceptanceJar by tasks.registering(RemapJarTask::class) {
    group = "build"
    description = "把验收 mod jar remap 到 SRG（arch-loom 承担 FG reobf）"
    dependsOn(acceptanceJar)
    inputFile.set(acceptanceJar.flatMap { it.archiveFile })
    archiveBaseName.set("mpmt-acceptance-forge")
    archiveClassifier.set("")
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
    providers.gradleProperty("mpmt.acceptance.report")
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
        val product = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(product.readBytes())
        systemProperty("mpmt.acceptance.productJarSha256", digest.joinToString("") { byte -> "%02x".format(byte) })
        val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
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
