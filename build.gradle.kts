// 根构建脚本：仅定义全局坐标与版本，不承载任何平台插件。
// 版本号唯一来源 = 根目录 VERSION 文件（testing-and-quality §3：VERSION 是版本号唯一来源）。

import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsExtension
import com.github.spotbugs.snom.SpotBugsTask
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.plugins.quality.Pmd
import org.gradle.api.plugins.quality.PmdExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaToolchainService

// 外部分析插件挂 buildscript classpath（apply false），供 subprojects 统一 apply。
plugins {
    id("com.github.spotbugs") version "6.0.26" apply false
    // Kotlin 工具链（现接、前瞻就绪；当前仅 .gradle.kts 为 Kotlin，第二期引入 Kotlin 源即生效）
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jetbrains.kotlinx.kover") version "0.8.3" apply false
    // WCPE Loom（ADR-0025）：必须在根以 apply false 声明，让插件类路径在全构建只加载一次。
    // 若只由各车道在自己的 plugins {} 里请求，Gradle 会按子树各建一份插件类加载器，
    // loom 跨工程迭代（MixinAPMappingService → GradleUtils.allLoomProjects →
    // LoomGradleExtension.get(otherProject)）就会因跨 classloader 而转换失败。
    id("top.wcpe.loom") version "1.17.1" apply false
    id("top.wcpe.loom-no-remap") version "1.17.1" apply false
    // A 车道：mc-testkit（Bukkit/Folia bot e2e；与 B 真 mod 客户端分 lane）
    // 仅经 maven.wcpe.top 解析插件坐标；禁止 sibling includeBuild 联调
    id("top.wcpe.mc-testkit") version "0.5.1"
    id("top.wcpe.mc.mpmt.realserver-report-gate")
}

val mpmtVersion: String = rootProject.file("VERSION").readText().trim()

allprojects {
    group = "top.wcpe.mc.mpmt"
    version = mpmtVersion
}

// 脚手架换名：纯 kts（gradle/scaffold-rename.gradle.kts），无 python 依赖
apply(from = "gradle/scaffold-rename.gradle.kts")

// ============================================================================
// 静态分析 / 质量工具链（严格门禁，static-analysis.md）——根构建各 Java 模块统一接入。
// 核心 Gradle 插件（checkstyle 等）经 subprojects 统一配置，共享 config/ 规则集；
// 违规即失败构建（isIgnoreFailures=false）。各独立 includeBuild 平台各自接同一套（共享 config/）。
// ============================================================================
subprojects {
    // 样式审查：Checkstyle（裁剪规则集，聚焦导入卫生/命名/结构）
    apply(plugin = "checkstyle")
    configure<CheckstyleExtension> {
        toolVersion = "10.17.0"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        isIgnoreFailures = false
        maxWarnings = 0
    }
    // 代码异味 / 源码规则：PMD（裁剪规则集，聚焦未用/空块/吞异常/线程等真实坏味道）
    apply(plugin = "pmd")
    configure<PmdExtension> {
        toolVersion = "7.0.0"
        isConsoleOutput = true
        ruleSetConfig = resources.text.fromFile(rootProject.file("config/pmd/ruleset.xml"))
        ruleSets = emptyList()
        isIgnoreFailures = false
    }
    // 测试覆盖率：JaCoCo（报告 + 覆盖率底线门禁）。底线 LINE 0.70。
    apply(plugin = "jacoco")
    // 平台车道会把 core/protocol/spi 等共享模块**嵌入**自己的 main classes（Forge SecureJar 需要），
    // 若一并计入，车道覆盖率会被这些"外来类"稀释，而它们已由各自模块的测试覆盖。
    // 因此仅对 platform:* 车道排除这些共享包（根模块与共享模块照常全量统计）。
    // 注意：必须作用在 class *文件树* 上——JaCoCo 自行遍历目录，只过滤目录集合无效。
    val sharedPackagesExcludedFromPlatformLanes =
        listOf(
            "top/wcpe/mc/mpmt/core/**",
            "top/wcpe/mc/mpmt/domain/**",
            "top/wcpe/mc/mpmt/protocol/**",
            "top/wcpe/mc/mpmt/paths/**",
            "top/wcpe/mc/mpmt/config/**",
            "top/wcpe/mc/mpmt/acceptance/**",
            "top/wcpe/mc/mpmt/platform/spi/**",
        )
    val isPlatformLane = path.startsWith(":platform:") && path != ":platform"
    val laneCodeExclusions = sharedPackagesExcludedFromPlatformLanes.toTypedArray()
    // 必须在 afterEvaluate 应用：java 插件在 afterEvaluate 才填充 classDirectories，
    // 若在 configureEach 里 setFrom，会被随后的默认值覆盖掉。
    if (isPlatformLane) {
        afterEvaluate {
            tasks.withType(JacocoReport::class.java).configureEach {
                // JaCoCo 自行遍历目录，必须在 FileTree 层面排除（对目录集合或文件级过滤都无效）。
                classDirectories.setFrom(files(classDirectories.files.map { dir -> fileTree(dir) { exclude(*laneCodeExclusions) } }))
            }
            tasks.withType(JacocoCoverageVerification::class.java).configureEach {
                classDirectories.setFrom(files(classDirectories.files.map { dir -> fileTree(dir) { exclude(*laneCodeExclusions) } }))
            }
        }
    }
    tasks.withType(JacocoReport::class.java).configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    tasks.withType(JacocoCoverageVerification::class.java)
        .configureEach {
            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        minimum = "0.70".toBigDecimal()
                    }
                }
            }
        }
    tasks.withType(Test::class.java).configureEach {
        finalizedBy(tasks.matching { it.name == "jacocoTestReport" })
    }
    tasks.matching { it.name == "check" }
        .configureEach { dependsOn(tasks.matching { it.name == "jacocoTestCoverageVerification" }) }

    // Kotlin 工具链（全装、前瞻就绪）：ktlint 检 .gradle.kts 构建脚本（.editorconfig 已放宽长注释场景，
    // 严格门禁）；detekt 扫 Kotlin 源（现无源、近空扫）；Kover 备 Kotlin 覆盖率（现 Java 码由 JaCoCo 覆盖、
    // Kover 待第二期 Kotlin 源生效）。三者均 Kotlin 源出现即自动生效。
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    // 缺陷检测（字节码）+ 安全审查：SpotBugs + FindSecBugs（挂在 SpotBugs 上）
    apply(plugin = "com.github.spotbugs")
    configure<SpotBugsExtension> {
        ignoreFailures.set(false)
        effort.set(Effort.MAX)
        // 报告 MEDIUM 及以上置信度，避免 LOW 置信度噪声拖垮严格门禁
        reportLevel.set(Confidence.MEDIUM)
        excludeFilter.set(rootProject.file("config/spotbugs/exclude.xml"))
    }
    dependencies.add("spotbugsPlugins", "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0")
    // 把 lombok.config 登记为编译输入：其改动须失效编译缓存（否则构建缓存会服旧的、缺 @Generated 的
    // 类，导致 SpotBugs/JaCoCo 仍对 Lombok 生成代码误报）。
    tasks.withType(JavaCompile::class.java).configureEach {
        inputs.file(rootProject.file("lombok.config"))
            .withPropertyName("lombokConfig")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    // 分析工具运行 JVM 与被测模块目标字节码无关：L0–L2 编译工具链为 JDK 8，但 Checkstyle 10.x 需 JDK 11+，
    // 故把分析任务固定到 JDK 17 启动器运行（不影响模块自身的 Java 8 编译目标）。
    // 在 afterEvaluate 配置：JavaToolchainService 由模块自身的 java 插件注册、晚于本 subprojects 块。
    // 聚合壳（如 platform-bukkit 无业务源码）也可能挂 java；无 JavaToolchainService 则跳过。
    afterEvaluate {
        val toolchains = extensions.findByType(JavaToolchainService::class.java) ?: return@afterEvaluate
        val analysisLauncher =
            toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
        tasks.withType(Checkstyle::class.java).configureEach {
            javaLauncher.set(analysisLauncher)
        }
        tasks.withType(Pmd::class.java).configureEach {
            javaLauncher.set(analysisLauncher)
        }
        // SpotBugs worker 默认用守护 JVM（JDK 17），无需固定 launcher。
        // 仅生产码（spotbugsMain）严格门禁；test / acceptance / gametest 等非 main 源集宽松
        // （测试与验收 harness 常含 mock/反射等 SpotBugs 噪声，安全/缺陷分析重在生产码）。
        tasks.withType(SpotBugsTask::class.java).configureEach {
            if (name != "spotbugsMain") {
                ignoreFailures = true
            }
        }
    }
}

// 发布产物结构门：全部平台车道均为根构建子模块（ADR-0026），直接依赖其任务；
// 任务不存在即配置期失败——不再有"includeBuild 未加载 → 静默跳过"的降级路径。

val verifyReleasePackaging by tasks.registering {
    group = "verification"
    description = "校验五平台最终自包含发布产物（全部平台车道为根构建子模块）"
    // Bukkit 已拆为每版本子工程；聚合任务在 platform-bukkit 壳上
    dependsOn(":platform:bukkit:verifyPackaging")
    dependsOn(
        ":platform:fabric:fabric-1.20.1:verifyPackaging",
        ":platform:fabric:fabric-1.21.1:verifyPackaging",
        ":platform:fabric:fabric-26.2:verifyPackaging",
        ":platform:forge:forge-1.20.1:verifyPackaging",
        ":platform:forge:forge-1.21.1:verifyPackaging",
        ":platform:forge:forge-26.2:verifyPackaging",
        ":platform:neoforge:neoforge-1.20.2:verifyPackaging",
        ":platform:sponge:sponge-1.20.1:verifyPackaging",
    )
}

// Fabric 26.2 / NeoForge 1.20.2 的"受控内部 JAR"随子模块化取消（ADR-0026）：
// 车道现在直接用 project(...) 依赖核心模块，无需根预构建输入；
// 相应 verifyNeoForge1202* 文件存在性门一并删除——产物由子项目任务保证。

val buildFabric262 by tasks.registering {
    group = "build"
    description = "构建 Fabric 26.2 车道（根子模块）"
    dependsOn(":platform:fabric:fabric-26.2:build")
}

verifyReleasePackaging.configure {
    dependsOn(buildFabric262)
}

/**
 * 聚合各平台权威可发布 jar 到 build/dist/{bukkit,fabric,forge,neoforge,sponge}/。
 *
 * <p>不复制 acceptance / plain / dev-shadow / corelib。全部平台车道为根构建子模块（ADR-0026），
 * 产物来源统一经 project(...) 的 buildDirectory 解析，并由任务依赖保证已构建。
 */
val collectReleaseArtifacts by tasks.registering {
    group = "build"
    description =
        "聚合权威可发布 jar 到 build/dist/{bukkit,fabric,forge,neoforge,sponge}/"
    dependsOn(verifyReleasePackaging)
    dependsOn(
        ":platform:bukkit:1.12.2:shadowJar",
        ":platform:bukkit:1.20.1:shadowJar",
        ":platform:bukkit:1.21.1:shadowJar",
        ":platform:bukkit:26.2:shadowJar",
        ":platform:fabric:fabric-1.20.1:remapJar",
        ":platform:fabric:fabric-1.21.1:remapJar",
        ":platform:fabric:fabric-26.2:shadowJar",
        ":platform:forge:forge-1.12.2:reobfJar",
        ":platform:forge:forge-1.20.1:reobfShadowJar",
        ":platform:forge:forge-1.21.1:packageArtifacts",
        ":platform:forge:forge-26.2:packageArtifacts",
        ":platform:neoforge:neoforge-1.20.2:packageArtifacts",
        ":platform:sponge:sponge-1.20.1:shadowJar",
    )

    val distRoot = layout.buildDirectory.dir("dist")
    outputs.dir(distRoot)

    doLast {
        data class ReleaseArtifact(val source: File, val loader: String, val targetName: String)

        fun laneArtifact(
            projectPath: String,
            buildRelativePath: String,
            targetName: String,
            loader: String,
        ): ReleaseArtifact =
            ReleaseArtifact(
                project(projectPath).layout.buildDirectory.file(buildRelativePath).get().asFile,
                loader,
                targetName,
            )

        val version = mpmtVersion
        val requiredArtifacts =
            listOf(
                laneArtifact(":platform:bukkit:1.12.2", "libs/mpmt-bukkit-1.12.2-$version.jar", "mpmt-bukkit-1.12.2-$version.jar", "bukkit"),
                laneArtifact(":platform:bukkit:1.20.1", "libs/mpmt-bukkit-1.20.1-$version.jar", "mpmt-bukkit-1.20.1-$version.jar", "bukkit"),
                laneArtifact(":platform:bukkit:1.21.1", "libs/mpmt-bukkit-1.21.1-$version.jar", "mpmt-bukkit-1.21.1-$version.jar", "bukkit"),
                laneArtifact(":platform:bukkit:26.2", "libs/mpmt-bukkit-26.2-$version.jar", "mpmt-bukkit-26.2-$version.jar", "bukkit"),
                laneArtifact(":platform:fabric:fabric-1.20.1", "libs/mpmt-fabric-1.20.1-$version.jar", "mpmt-fabric-1.20.1-$version.jar", "fabric"),
                laneArtifact(":platform:fabric:fabric-1.21.1", "libs/mpmt-fabric-1.21.1-$version.jar", "mpmt-fabric-1.21.1-$version.jar", "fabric"),
                laneArtifact(":platform:fabric:fabric-26.2", "libs/mpmt-fabric-26.2-$version.jar", "mpmt-fabric-26.2-$version.jar", "fabric"),
                laneArtifact(":platform:forge:forge-1.20.1", "reobfShadowJar/output.jar", "mpmt-forge-1.20.1-$version.jar", "forge"),
                laneArtifact(":platform:forge:forge-1.21.1", "libs/mpmt-forge-1.21.1-$version.jar", "mpmt-forge-1.21.1-$version.jar", "forge"),
                laneArtifact(":platform:forge:forge-1.12.2", "reobfJar/output.jar", "mpmt-forge-1.12.2-$version.jar", "forge"),
                laneArtifact(":platform:forge:forge-26.2", "libs/mpmt-forge-26.2-$version.jar", "mpmt-forge-26.2-$version.jar", "forge"),
                laneArtifact(":platform:neoforge:neoforge-1.20.2", "libs/mpmt-neoforge-1.20.2-$version.jar", "mpmt-neoforge-1.20.2-$version.jar", "neoforge"),
                laneArtifact(":platform:sponge:sponge-1.20.1", "libs/mpmt-sponge-1.20.1-$version.jar", "mpmt-sponge-1.20.1-$version.jar", "sponge"),
            )

        val dist = distRoot.get().asFile
        val missing = requiredArtifacts.filterNot { it.source.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "[dist] 发布制品缺失：\n" +
                    missing.joinToString("\n") { " - ${it.loader}/${it.targetName} ← ${it.source.absolutePath}" } +
                    "\n请先运行 :verifyReleasePackaging（全部平台车道已是根子模块，产物由任务依赖保证）。",
            )
        }
        if (dist.exists()) {
            dist.deleteRecursively()
        }
        listOf("bukkit", "fabric", "forge", "neoforge", "sponge").forEach { name ->
            File(dist, name).mkdirs()
        }
        requiredArtifacts.forEach { artifact ->
            val destination = File(File(dist, artifact.loader), artifact.targetName)
            artifact.source.copyTo(destination, overwrite = true)
            logger.lifecycle("[dist] ${artifact.loader}/${artifact.targetName}  (${artifact.source.length()} bytes)")
        }

        logger.lifecycle("[dist] 完成：${dist.absolutePath}")
    }
}

// 一键全量构建：全部平台车道已是根构建子模块（ADR-0026），直接聚合 subprojects 的 build，
// 并显式执行最终发布产物结构门 + dist 聚合。
tasks.register("buildAll") {
    group = "build"
    description = "构建全部模块、校验发布产物并聚合到 build/dist/"
    dependsOn(
        subprojects
            .filterNot { it.path == ":platform:bukkit" }
            .map { project -> project.tasks.matching { it.name == "build" } },
    )
    dependsOn(verifyReleasePackaging)
    dependsOn(collectReleaseArtifacts)
}

// ---------------------------------------------------------------------------
// 真服 / 版本矩阵 验收入口（Gradle only，禁止 scripts/*.sh 编排）
// B 完整：全部服务端 lane；客户端 = 各 loader 自有 gametest/acceptance 伴侣进服。
// 对齐 AllinCore：根薄包装 + includeBuild；禁止嵌套 gradlew。
// ---------------------------------------------------------------------------

/** 打印 B 车道覆盖（与 build-logic PlatformLaneCatalog 一致，根侧可离线查看）。 */
tasks.register("listRealServerLanes") {
    group = "help"
    description = "列出 B 车道：全服务端 + 自有 gametest 客户端进服方式"
    doLast {
        logger.lifecycle(
            """
            |[mpmt-realserver] B 车道覆盖（全部平台车道为根构建子模块，ADR-0026）
            |  Fabric 1.20.1   :platform:fabric:fabric-1.20.1    客户端=Fabric gametest
            |  Fabric 1.21.1   :platform:fabric:fabric-1.21.1    客户端=Fabric gametest
            |  Fabric 26.2     :platform:fabric:fabric-26.2      客户端=Fabric gametest（须 JDK 25）
            |  Forge 1.20.1    :platform:forge:forge-1.20.1     客户端=Forge acceptance
            |  Forge 1.21.1    :platform:forge:forge-1.21.1     客户端=Forge acceptance
            |  Forge 1.12.2    :platform:forge:forge-1.12.2     client-only（真服走 CatServer/HYBRID）
            |  Forge 26.2      :platform:forge:forge-26.2       客户端=Forge acceptance（须 JDK 25）
            |  NeoForge 1.20.2 :platform:neoforge:neoforge-1.20.2  客户端=NeoForge acceptance
            |  Bukkit/Paper    :platform:bukkit:1.20.1    客户端=Fabric gametest
            |  Folia           同上 1.20.1；矩阵默认 SCHEDULER
            |  CatServer（HYBRID 矩阵）    :platform:bukkit:1.12.2 + Forge 1.12 client-only 伴侣（禁止 Forge 服务端 mod）
            |  Sponge          :platform:sponge:sponge-1.20.1
            |入口（请用绝对路径 :task，避免匹配子工程同名任务）：
            |  ./gradlew :runRealServerAcceptance
            |  ./gradlew :runRealServerAcceptanceFabric
            |  ./gradlew :verifyVersionMatrixBuild
            |  ./gradlew :runVersionMatrixGate
            |  ./gradlew :collectReleaseArtifacts   # → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
            |  ./gradlew :buildAll
            |B 增强：
            |  ./gradlew :platform:bukkit:1.20.1:ensurePaperRealServerHost -Pmpmt.realserver.autoHost=true
            |A 辅车道：./gradlew :runMcTestkitSmoke -PmcTestkit.botDir=e2e/bot
            |注：根构建须以 JDK 25 运行（26.2 两条车道的配置期硬校验，ADR-0026）。
            """.trimMargin(),
        )
    }
}

fun registerLaneGate(
    taskName: String,
    descriptionText: String,
    configure: org.gradle.api.Task.() -> Unit,
) {
    tasks.register(taskName) {
        group = "verification"
        description = descriptionText
        configure()
    }
}

// --- 各服务端 lane：委托平台内 runRealServerAcceptance（读权威报告）---

registerLaneGate(
    "runRealServerAcceptanceFabric",
    "Fabric 1.20.1 专用服门禁：须先 runAcceptanceServer + Fabric gametest 客户端进服",
) {
    dependsOn(":platform:fabric:fabric-1.20.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceFabric121",
    "Fabric 1.21.1 专用服门禁",
) {
    dependsOn(":platform:fabric:fabric-1.21.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceFabric262",
    "Fabric 26.2 专用服门禁：须先完成服务端与 Fabric gametest 客户端实跑",
) {
    dependsOn(":platform:fabric:fabric-26.2:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceForge",
    "Forge 1.20.1 专用服门禁：须先实跑 Forge 服 + Forge acceptance 客户端伴侣",
) {
    dependsOn(":platform:forge:forge-1.20.1:runRealServerAcceptance")
}

/** Forge 1.21.1：根子模块车道（ADR-0026），直接委托其报告门。 */
tasks.register("runRealServerAcceptanceForge121") {
    group = "verification"
    description = "Forge 1.21.1 专用服门禁：委托车道 runRealServerAcceptance（须先完成起服 + 客户端伴侣实跑）"
    dependsOn(":platform:forge:forge-1.21.1:runRealServerAcceptance")
}

/**
 * Forge 26.2：根子模块车道，直接委托其报告门。
 * 轮次 / 制品哈希 / 三场景的严格校验由 verifyRealServerReportsStrict（build-logic）承担。
 */
tasks.register("runRealServerAcceptanceForge262") {
    group = "verification"
    description = "Forge 26.2 专用服门禁：委托车道 runRealServerAcceptance"
    dependsOn(":platform:forge:forge-26.2:runRealServerAcceptance")
}

/** Forge 1.12.2 client-only：真服走 CatServer（HYBRID 矩阵），禁止 Forge 专用服。 */
tasks.register("runRealServerAcceptanceForge112") {
    group = "verification"
    description =
        "Forge 1.12.2 伴侣构建门：构建 client-only 伴侣；真服请用 :runRealServerAcceptanceCatServer（禁止 Forge 服务端 mod）"
    dependsOn(":platform:forge:forge-1.12.2:prepareClientCompanionArtifacts")
    doLast {
        logger.lifecycle(
            """
            |[runRealServerAcceptanceForge112]
            |ADR-0021：1.12.2 Forge 只产 client-only 客户端，不得装入 CatServer 作服务端 mod。
            |真服矩阵 HYBRID：
            |  ./gradlew :runRealServerAcceptanceCatServer
            |  # 即 :platform:bukkit:1.12.2:runRealServerAcceptance（报告 RESULT PASS）
            |客户端伴侣产物（本次已构建）：
            |  platform/forge/1.12.2/build/reobfJar/output.jar
            |  platform/forge/1.12.2/build/reobfAcceptanceJar/output.jar
            """.trimMargin(),
        )
    }
}

/** NeoForge 1.20.2：根子模块车道（ADR-0026），直接委托其报告门。 */
tasks.register("runRealServerAcceptanceNeoForge") {
    group = "verification"
    description = "NeoForge 专用服门禁：委托车道 runRealServerAcceptance（须先完成起服 + 客户端伴侣实跑）"
    dependsOn(":platform:neoforge:neoforge-1.20.2:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceBukkit",
    "Paper/Bukkit 宿主门禁（默认 1.20.1）：产品+验收插件部署后，Fabric gametest 客户端进服写报告",
) {
    dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceBukkit262",
    "Paper 26.2 真服宿主门禁：须先部署产品与验收插件并完成客户端进服",
) {
    dependsOn(":platform:bukkit:26.2:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceFolia",
    "Folia 宿主门禁（矩阵 SCHEDULER）：1.20.1 产物，Folia 实跑 + Fabric gametest 客户端",
) {
    dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceCatServer",
    "CatServer 融合服门禁（矩阵 HYBRID）：Bukkit 1.12.2 活跃 + Forge 1.12.2 optional 客户端",
) {
    dependsOn(":platform:bukkit:1.12.2:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceSponge",
    "Sponge 宿主门禁：Sponge 服 + Fabric gametest 客户端进服",
) {
    dependsOn(":platform:sponge:sponge-1.20.1:runRealServerAcceptance")
}

/** 默认：全服务端 lane 串行门禁（各 lane 须已自行完成「服 + 自有 gametest 客户端」并落报告）。 */
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "B 完整：全服务端 realserver 报告门禁（含 Fabric121 / NeoForge / Sponge；不含 Forge 1.21/1.12 自有 launcher）"
    dependsOn(
        "runRealServerAcceptanceFabric",
        "runRealServerAcceptanceFabric121",
        "runRealServerAcceptanceFabric262",
        "runRealServerAcceptanceForge",
        "runRealServerAcceptanceForge262",
        "runRealServerAcceptanceNeoForge",
        "runRealServerAcceptanceBukkit",
        "runRealServerAcceptanceBukkit262",
        "runRealServerAcceptanceFolia",
        "runRealServerAcceptanceCatServer",
        "runRealServerAcceptanceSponge",
    )
}

/**
 * 版本矩阵核心真服门禁（FR-12）：仅矩阵相关车道，不阻断 NeoForge / Sponge。
 *
 * <p>各 lane 仍须先自行完成「服 + 自有 gametest 客户端」并落 RESULT PASS 报告；
 * 本任务只读权威报告，不嵌套 gradlew、不调用 buildAll。
 */
tasks.register("runVersionMatrixRealServerAcceptance") {
    group = "verification"
    description =
        "版本矩阵核心 realserver 门禁：Fabric 1.20/1.21 + Forge 1.20 + Bukkit/Folia + CatServer（不含 NeoForge/Sponge）"
    dependsOn(
        "runRealServerAcceptanceFabric",
        "runRealServerAcceptanceFabric121",
        "runRealServerAcceptanceForge",
        "runRealServerAcceptanceBukkit",
        "runRealServerAcceptanceFolia",
        "runRealServerAcceptanceCatServer",
    )
}

/**
 * 版本矩阵构建（无真服）：对齐每版本独立工程路径，废除 -Pmpmt.minecraftVersion。
 * Forge 1.21.1 / 1.12.2 须用各自目录自有 launcher，本任务只打印命令不嵌套 gradlew。
 */
tasks.register("verifyVersionMatrixBuild") {
    group = "verification"
    description =
        "版本矩阵构建：Bukkit 三版本 + Fabric 两版本 + Forge 1.20.1 打包校验；打印 1.21/1.12 Forge 独立 launcher 命令"
    dependsOn(
        ":platform:bukkit:1.12.2:verifyPackaging",
        ":platform:bukkit:1.20.1:verifyPackaging",
        ":platform:bukkit:1.21.1:verifyPackaging",
    )
    dependsOn(":platform:fabric:fabric-1.20.1:verifyPackaging")
    dependsOn(":platform:fabric:fabric-1.21.1:verifyPackaging")
    dependsOn(":platform:forge:forge-1.20.1:verifyPackaging")
    doLast {
        val java8 = System.getenv("MPMT_JAVA8_HOME")
        val java17 = System.getenv("MPMT_JAVA17_HOME")
        val java21 = System.getenv("MPMT_JAVA21_HOME")
        if (java8.isNullOrBlank() || java17.isNullOrBlank() || java21.isNullOrBlank()) {
            logger.warn(
                "[verifyVersionMatrixBuild] 建议设置 MPMT_JAVA8_HOME / MPMT_JAVA17_HOME / " +
                    "MPMT_JAVA21_HOME（跨代车道显式 JDK；当前未齐）",
            )
        } else {
            logger.lifecycle(
                "[verifyVersionMatrixBuild] JDK 环境：8=$java8 17=$java17 21=$java21",
            )
        }
        logger.lifecycle(
            """
            |[verifyVersionMatrixBuild] 矩阵构建门已通过（全部平台车道为根子模块，ADR-0026）。
            |版本矩阵真服子门：./gradlew :runVersionMatrixRealServerAcceptance（须先矩阵车道落 RESULT PASS）
            |全 lane（含 NeoForge/Sponge）：./gradlew :runRealServerAcceptance
            """.trimMargin(),
        )
    }
}

tasks.register("buildRealServerArtifacts262") {
    group = "verification"
    description = "26.2 三车道构建门：Paper 26.2、Fabric 26.2 与 Forge 26.2 产物"
    dependsOn(
        ":platform:bukkit:26.2:verifyPackaging",
        buildFabric262,
        ":platform:forge:forge-26.2:packageArtifacts",
    )
    doLast {
        val version = mpmtVersion
        val forgeProduct =
            project(":platform:forge:forge-26.2").layout.buildDirectory.file("libs/mpmt-forge-26.2-$version.jar").get().asFile
        val forgeAcceptance =
            project(":platform:forge:forge-26.2").layout.buildDirectory
                .file("libs/mpmt-forge-acceptance-26.2-$version.jar").get().asFile
        if (!forgeProduct.isFile || !forgeAcceptance.isFile) {
            throw GradleException(
                "缺少 Forge 26.2 产品或验收产物（已于本次依赖 :platform:forge:forge-26.2:packageArtifacts，仍缺失说明该任务未产出预期命名）：" +
                    "${forgeProduct.absolutePath} / ${forgeAcceptance.absolutePath}",
            )
        }
    }
}

tasks.named("verifyRealServerReportsStrict") {
    mustRunAfter(
        "runRealServerAcceptanceBukkit262",
        "runRealServerAcceptanceFabric262",
        "runRealServerAcceptanceForge262",
    )
}

tasks.register("runRealServerAcceptance262") {
    group = "verification"
    description = "26.2 真服门：Paper、Fabric、Forge 26.2 三车道当前报告全通过"
    dependsOn(
        "runRealServerAcceptanceBukkit262",
        "runRealServerAcceptanceFabric262",
        "runRealServerAcceptanceForge262",
        "verifyRealServerReportsStrict",
    )
}

tasks.register("runRealServerGate262") {
    group = "verification"
    description = "26.2 交付门：构建三车道并校验当前真服报告"
    dependsOn("buildRealServerArtifacts262", "runRealServerAcceptance262")
}

/**
 * 版本矩阵聚合门（FR-12 / ADR-0021）：
 * 构建矩阵 + 版本矩阵核心 realserver 报告门；不调用 buildAll，不阻断 NeoForge/Sponge。
 */
tasks.register("runVersionMatrixGate") {
    group = "verification"
    description =
        "版本矩阵门禁：verifyVersionMatrixBuild + runVersionMatrixRealServerAcceptance（不含 NeoForge/Sponge）"
    dependsOn("verifyVersionMatrixBuild", "runVersionMatrixRealServerAcceptance")
}

// ============================================================================
// A 车道：mc-testkit（Bukkit/Folia + mineflayer bot smoke；非 B 主 lane 的 mod 客户端）
// 桩：e2e/harness；bot：e2e/bot；被测 jar / 桩 jar 经 env 或路径注入。
// ============================================================================
mcTestkit {
    backend("s1") {
        platform = paper
        version = "1.20.1"
        port = 25565
    }
    // 无 bot：仅校验桩 + 被测插件就绪（smoke 桩内断言 MultiPlatformModTemplate 已启用）
    scenario("smoke") {
        backend = "s1"
    }
    // Folia 后端可选矩阵（同 smoke 场景，换平台声明）
    backend("folia1") {
        platform = folia
        version = "1.20.1"
        port = 25566
    }
    scenario("smoke-folia") {
        backend = "folia1"
    }
    dependencies {
        // 环境变量名或路径；运行前导出或传 -D
        pluginUnderTest = "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR"
        plugin("HARNESS_JAR")
    }
}

/** A 车道聚合：先提示构建 jar，再跑 e2eSmoke（须 env 指向产物）。 */
tasks.register("runMcTestkitSmoke") {
    group = "verification"
    description =
        "A 车道：mc-testkit Paper smoke（须已构建产品/桩 jar 并设置 " +
            "MC_TESTKIT_E2E_PLUGIN_UNDER_TEST_JAR 与 HARNESS_JAR；-PmcTestkit.botDir=e2e/bot）"
    dependsOn("e2eSmoke")
}

tasks.register("runMcTestkitFoliaSmoke") {
    group = "verification"
    description = "A 车道：mc-testkit Folia smoke（场景 smoke-folia；依赖同上）"
    // 任务名由 scenario key 生成：smoke-folia → SmokeFolia
    dependsOn("e2eSmokeFolia")
}
