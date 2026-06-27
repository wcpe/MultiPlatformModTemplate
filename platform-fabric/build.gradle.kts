import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import net.fabricmc.loom.task.RemapJarTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.language.jvm.tasks.ProcessResources
import java.util.zip.ZipFile

// platform-fabric（L3）：M0 阶段只验证构建骨架 + 打包链路，不含平台胶水逻辑。
// 关键链路（ADR-0012）：core 纯 Java 经 shadow shade 进产物（不被 remap），snakeyaml relocate；
// remapJar 消费 shadowJar 产物产出最终 remapped mod jar。映射用 Mojang 官方（ADR-0016）。

plugins {
    id("fabric-loom") version "1.7.4"
    id("com.gradleup.shadow") version "8.3.3"
    // 静态分析 / 质量工具链（严格门禁，static-analysis.md）：与根构建同一套，共享仓库根 config/ 规则集。
    // 核心 Gradle 插件经 apply(plugin=...) 接入（见下方装配块）；外部插件在此带版本直接 apply。
    id("com.github.spotbugs") version "6.0.26"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("org.jetbrains.kotlinx.kover") version "0.8.3"
}

// 坐标与版本：独立 includeBuild 需自行设定（版本唯一来源仍为根 VERSION 文件）
group = "top.wcpe.mc.mpmt"
version = file("../VERSION").readText().trim()

// 锚点版本与依赖坐标（集中声明，避免散落魔法值）
val mcVersion = "1.20.1"
val loaderVersion = "0.16.5"
// fabric-api：Fabric 平台 API（含网络 ServerPlayNetworking / ClientPlayNetworking，network spec §3.4），1.20.1 末版
val fabricApiVersion = "0.92.2+1.20.1"
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经 includeBuild 依赖替换消费
val spiCoordinate = "top.wcpe.mc.mpmt:platform-spi:$version"
// 依赖 core-server（服务端网络装配特性 ServerNetworkFeature；经 api 传递 protocol + core-runtime）
val serverCoordinate = "top.wcpe.mc.mpmt:core-server:$version"
// 依赖 core-client（客户端网络装配特性 ClientNetworkFeature + 弱标识提供者）
val clientCoordinate = "top.wcpe.mc.mpmt:core-client:$version"
// realserver 验收 harness 平台无关核心（仅 gametest 接入层用，不入产品 jar，ADR-0014）
val acceptanceCoordinate = "top.wcpe.mc.mpmt:acceptance:$version"

base {
    // 最终产物名带平台后缀，便于区分
    archivesName.set("mpmt-fabric")
}

java {
    // Fabric 1.20.1 强制 Java 17（ADR-0004：胶水随各 loader JDK）
    toolchain { languageVersion = JavaLanguageVersion.of(17) }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

// ============================================================================
// 静态分析 / 质量工具链装配（严格门禁，static-analysis.md）——本独立 includeBuild 单工程直接 apply。
// includeBuild 的 rootProject 即本目录，共享规则集在仓库根 config/，故引用 ../config/*；
// .editorconfig / lombok.config 在仓库根，ktlint / Lombok 自动向上查找，无需额外配置。
// 违规即失败构建（isIgnoreFailures=false），与根构建口径一致。
// ============================================================================
// 样式审查：Checkstyle（共享裁剪规则集）
apply(plugin = "checkstyle")
configure<CheckstyleExtension> {
    toolVersion = "10.17.0"
    configFile = rootProject.file("../config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}
// 代码异味 / 源码规则：PMD（共享裁剪规则集）
apply(plugin = "pmd")
configure<PmdExtension> {
    toolVersion = "7.0.0"
    isConsoleOutput = true
    ruleSetConfig = resources.text.fromFile(rootProject.file("../config/pmd/ruleset.xml"))
    ruleSets = emptyList()
    isIgnoreFailures = false
}
// 测试覆盖率：JaCoCo（仅报告，不设覆盖率底线门禁）。平台胶水单元测试少、靠 realserver 验收，
// 故只产出 xml/html 报告，不并入 check、不加 jacocoTestCoverageVerification。
apply(plugin = "jacoco")
tasks.withType(org.gradle.testing.jacoco.tasks.JacocoReport::class.java).configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
// 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
configure<SpotBugsExtension> {
    ignoreFailures.set(false)
    effort.set(Effort.MAX)
    // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
    reportLevel.set(Confidence.MEDIUM)
    excludeFilter.set(rootProject.file("../config/spotbugs/exclude.xml"))
}
dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
// 把 lombok.config 登记为编译输入：其改动须失效编译缓存（否则缓存会服旧的、缺 @Generated 的类，
// 导致 SpotBugs/JaCoCo 仍对 Lombok 生成代码误报）。lombok.config 在仓库根，故引用 ../lombok.config。
tasks.withType(JavaCompile::class.java).configureEach {
    inputs.file(rootProject.file("../lombok.config"))
        .withPropertyName("lombokConfig")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
// 分析任务固定 JDK 17 启动器：Checkstyle 10.x / PMD 7.x 需 JDK 11+；本工程 Loom 编译目标已是 JDK 17，
// 仍显式固定分析任务启动器与根构建口径一致。SpotBugs worker 用守护 JVM，无 javaLauncher 属性、不设。
val analysisToolchains = extensions.getByType(JavaToolchainService::class.java)
val analysisLauncher =
    analysisToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType(Checkstyle::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
tasks.withType(Pmd::class.java).configureEach {
    javaLauncher.set(analysisLauncher)
}
// 仅生产码（spotbugsMain）严格门禁；test / gametest 等非 main 源集宽松
// （测试与验收 harness 常含 mock/反射等 SpotBugs 噪声，安全/缺陷分析重在生产码）。
tasks.withType(SpotBugsTask::class.java).configureEach {
    if (name != "spotbugsMain") {
        ignoreFailures = true
    }
}

// 专用配置：需 shade 进产物并 relocate 的内容（core + 第三方运行期依赖），不参与 Loom remap
val shadowBundle: Configuration by configurations.creating

// realserver 验收 Fabric 接入层独立源集（src/gametest）：不入产品 jar、仅供 runRealServerAcceptance（后续）。
// 编译期继承 main 的类路径（含 Loom 提供的 remapped MC + fabric-api）+ main 产物，并叠加 acceptance 核心。
val gametest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].compileClasspath + sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].runtimeClasspath + sourceSets["main"].output
}
configurations["gametestImplementation"].extendsFrom(configurations["implementation"])

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    // Fabric 平台 API：提供网络收发（fabric-networking-api-v1）等；编译期依赖，运行期由宿主提供
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 共享核心（platform-spi + 传递的 core-runtime/core-domain）：纯 Java、非 mod 依赖、不参与 remap
    implementation(spiCoordinate)
    shadowBundle(spiCoordinate)

    // 服务端公共逻辑（core-server + 传递的 protocol）：同样纯 Java、shade 进产物、不参与 remap
    implementation(serverCoordinate)
    shadowBundle(serverCoordinate)

    // 客户端公共逻辑（core-client）：客户端网络装配 + 弱标识提供者，shade 进产物、不参与 remap
    implementation(clientCoordinate)
    shadowBundle(clientCoordinate)

    // 第三方运行期依赖：shade 进产物并 relocate 到 top.wcpe.mc.mpmt.libs.*（ADR-0012，防类冲突的统一约定）
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")
    shadowBundle("org.yaml:snakeyaml:$snakeyamlVersion")

    // gametest 接入层依赖 realserver 验收平台无关核心（控制协议 / 协调 / 报告 / GameTest 框架）
    "gametestImplementation"(acceptanceCoordinate)

    // 跨栈字节对齐 spike 的纯 JVM 测试
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// realserver 验收的 Loom 运行配置（dev 环境，加载 gametest 源集的 mpmt-acceptance 测试 mod）。
// 服务端可 headless 跑（runAcceptanceServer）；客户端需显示，由用户本机经 quickPlay 自连（runAcceptanceClient）。
val acceptanceReportFile = layout.buildDirectory.file("acceptance/server-report.txt")
val simReportFile = layout.buildDirectory.file("acceptance/sim-report.txt")
loom {
    runs {
        // 模拟服 GameTest 套件（FR-23①）：headless 起服跑 in-process 回环网络 GameTest，无外部客户端、可自动跑
        create("simNetworkTest") {
            server()
            configName = "Sim Network GameTest"
            source(gametest)
            property("mpmt.simtest", "true")
            property("mpmt.simtest.report", simReportFile.get().asFile.absolutePath)
        }
        create("acceptanceServer") {
            server()
            configName = "Acceptance Server"
            source(gametest)
            property("mpmt.acceptance", "true")
            property("mpmt.acceptance.report", acceptanceReportFile.get().asFile.absolutePath)
            // 看门狗绝对截止（无客户端连入时由场景 awaitClientReady 先失败，这里是上限兜底）
            property("mpmt.acceptance.deadlineMs", "300000")
        }
        create("acceptanceClient") {
            client()
            configName = "Acceptance Client"
            source(gametest)
            // 独立运行目录，避免与服务端 run/ 并发冲突
            runDir("run-client")
            // 经 --quickPlayMultiplayer <服务端地址> 自连
            programArgs("--quickPlayMultiplayer", "127.0.0.1")
        }
    }
}

// fabric.mod.json 中 ${version} 占位由构建注入
tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

// 打包链路：jar（仅本模块类）→ shadowJar（+core+snakeyaml，relocate）→ remapJar（remap MC 引用）
tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dev-shadow")
    // 仅打入 shadowBundle 指定内容，避免误打入 Minecraft / fabric-loader
    configurations = listOf(shadowBundle)
    // 第三方依赖 relocate，避免与宿主 / 其它插件冲突（ADR-0012）
    relocate("org.yaml.snakeyaml", "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml")
    // relocate 只改写类与字节码引用，不动 META-INF/maven 下的原始坐标元数据；剔除之，保持产物洁净
    exclude("META-INF/maven/**")
    // shadow 的 ShadowJar 不把 relocate/exclude 等配置纳入增量/缓存指纹（实测改配置后仍 UP-TO-DATE / FROM-CACHE，
    // 命中陈旧产物）。打包要求确定性反映当前配置，故每次重跑、不参与构建缓存。
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

// Loom 的 remapJar 改吃 shadowJar 产物，使 core / 第三方随之进入最终 remapped 产物
tasks.named<RemapJarTask>("remapJar") {
    dependsOn(tasks.named("shadowJar"))
    inputFile.set(tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile })
    archiveClassifier.set("")
}

// 打包 spike 自动化校验：检查最终 remapped jar 内类归属是否符合预期
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验打包 spike：core 原包名保留（未 remap）/ snakeyaml 已 relocate / fabric.mod.json 存在"
    dependsOn(tasks.named("remapJar"))
    doLast {
        val jar = tasks.named<RemapJarTask>("remapJar").get().archiveFile.get().asFile
        val entries = ZipFile(jar).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

        fun must(cond: Boolean, msg: String) {
            if (!cond) throw GradleException("打包 spike 校验失败：$msg")
        }
        must(entries.contains("top/wcpe/mc/mpmt/core/domain/Mpmt.class"), "core 类未以原包名出现在产物（应被 shade 且未被 remap）")
        must(entries.contains("top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class"), "platform-spi 未 shade 进产物")
        must(entries.any { it.startsWith("top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/") }, "snakeyaml 未 relocate 到 top.wcpe.mc.mpmt.libs.*")
        must(entries.none { it.startsWith("org/yaml/snakeyaml/") }, "snakeyaml 仍在原包名 org.yaml.snakeyaml（relocate 未生效）")
        must(entries.none { it.startsWith("META-INF/maven/org.yaml/") }, "snakeyaml 的 Maven 坐标元数据残留（应 exclude META-INF/maven/**）")
        must(entries.contains("fabric.mod.json"), "产物缺少 fabric.mod.json")
        must(entries.none { it.startsWith("net/minecraft/") }, "产物内不应直接包含 Minecraft 类（应由 loader 提供）")
        println("打包 spike 校验通过：")
        println("  产物 = ${jar.name}（条目数 ${entries.size}）")
        println("  core 类 top/wcpe/mc/mpmt/core/domain/Mpmt.class 以原包名存在（未被 remap）")
        println("  snakeyaml 已 relocate 到 top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/")
    }
}

tasks.named("build") {
    dependsOn(verifyPackaging)
}

tasks.test {
    useJUnitPlatform()
}

// 把 gametest 接入层纳入构建期编译校验（只编译、不运行——运行需真实服）
tasks.named("build") {
    dependsOn("gametestClasses")
}

// realserver 验收门禁：读服务端写出的单一权威报告，末行非 RESULT PASS 即构建失败（ADR-0014）。
// 实跑流程（须真实进程，客户端需显示）：① 一端跑 runAcceptanceServer（服务端 headless，等客户端连入）；
// ② 另一端跑 runAcceptanceClient（客户端经 --quickPlayMultiplayer 自连，须显示）；③ 服务端聚合写报告后本任务判定。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description = "读 realserver 验收权威报告，末行非 RESULT PASS 即失败"
    doLast {
        val report = acceptanceReportFile.get().asFile
        if (!report.exists()) {
            throw GradleException(
                "未找到验收报告（先跑 runAcceptanceServer + runAcceptanceClient 使服务端写出报告）：${report.absolutePath}",
            )
        }
        val text = report.readText()
        logger.lifecycle("[realserver] 服务端权威验收报告：\n$text")
        val resultLine = text.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("RESULT") }
        if (resultLine != "RESULT PASS") {
            throw GradleException("[realserver] 验收未通过（末行非 RESULT PASS）：$resultLine")
        }
        logger.lifecycle("[realserver] 验收通过 ✓ RESULT PASS")
    }
}

// 模拟服 GameTest 一键门禁（FR-23①）：起 headless 服跑 in-process 回环网络 GameTest → 读报告末行 RESULT PASS。
tasks.register("runSimNetworkAcceptance") {
    group = "verification"
    description = "起 headless 服跑模拟服回环网络 GameTest，读报告末行 RESULT PASS"
    dependsOn("runSimNetworkTest")
    doLast {
        val report = simReportFile.get().asFile
        if (!report.exists()) {
            throw GradleException("未找到模拟服报告（runSimNetworkTest 未写出）：${report.absolutePath}")
        }
        val text = report.readText()
        logger.lifecycle("[sim] 模拟服 GameTest 权威报告：\n$text")
        val resultLine = text.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("RESULT") }
        if (resultLine != "RESULT PASS") {
            throw GradleException("[sim] 模拟服未通过（末行非 RESULT PASS）：$resultLine")
        }
        logger.lifecycle("[sim] 模拟服 GameTest 通过 ✓ RESULT PASS")
    }
}

// gametest 测试 mod 的 fabric.mod.json 同样注入版本号占位
tasks.named<ProcessResources>("processGametestResources") {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
