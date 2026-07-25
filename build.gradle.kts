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
    // A 车道：mc-testkit（Bukkit/Folia bot e2e；与 B 真 mod 客户端分 lane）
    // 仅经 maven.wcpe.top 解析插件坐标；禁止 sibling includeBuild 联调
    id("top.wcpe.mc-testkit") version "0.5.1"
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
    // 测试覆盖率：JaCoCo（报告 + 覆盖率底线门禁）。底线 LINE 0.70 取各模块当前覆盖率（最低 77%）下方整数、防回退。
    apply(plugin = "jacoco")
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

// 发布产物结构门：即使平台 build 生命周期以后发生调整，根聚合仍显式校验五平台最终可部署包。
// 本机可用 -Pmpmt.skip.forge/neoforge/sponge/fabric=true 跳过对应 includeBuild 时，仅依赖已 include 的。
fun includedBuildOrNull(name: String): org.gradle.api.initialization.IncludedBuild? =
    gradle.includedBuilds.find { it.name == name }

fun org.gradle.api.Task.dependsOnIncludedIfPresent(buildName: String, taskPath: String) {
    val ib = includedBuildOrNull(buildName)
    if (ib != null) {
        dependsOn(ib.task(taskPath))
    } else {
        doFirst {
            logger.warn("[mpmt] 跳过依赖 $buildName$taskPath（includeBuild 未加载，见 -Pmpmt.skip.*）")
        }
    }
}

val verifyReleasePackaging by tasks.registering {
    group = "verification"
    description = "校验五平台最终自包含发布产物（已 skip 的 includeBuild 仅 warn）"
    // Bukkit 已拆为每版本子工程；聚合任务在 platform-bukkit 壳上
    dependsOn(":platform:bukkit:verifyPackaging")
    dependsOnIncludedIfPresent("platform-fabric-1.20.1", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-fabric-1.21.1", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-forge-1.20.1", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-neoforge", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-sponge", ":verifyPackaging")
}

/**
 * 聚合各平台权威可发布 jar 到 build/dist/{bukkit,fabric,forge,neoforge,sponge}/。
 *
 * <p>不复制 acceptance / plain / dev-shadow / corelib。跨代 Forge 1.12/1.21 仅在产物
 * 已存在时捞入，绝不嵌套 gradlew。
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
    )
    dependsOnIncludedIfPresent("platform-fabric-1.20.1", ":remapJar")
    dependsOnIncludedIfPresent("platform-fabric-1.21.1", ":remapJar")
    dependsOnIncludedIfPresent("platform-forge-1.20.1", ":reobfShadowJar")
    dependsOnIncludedIfPresent("platform-neoforge", ":shadowJar")
    dependsOnIncludedIfPresent("platform-sponge", ":shadowJar")

    val distRoot = layout.buildDirectory.dir("dist")
    outputs.dir(distRoot)

    doLast {
        val version = mpmtVersion
        val root = project.rootDir
        val dist = distRoot.get().asFile
        if (dist.exists()) {
            dist.deleteRecursively()
        }
        listOf("bukkit", "fabric", "forge", "neoforge", "sponge").forEach { name ->
            File(dist, name).mkdirs()
        }

        fun copyNamed(src: File, loader: String, fileName: String) {
            if (!src.isFile) {
                logger.warn("[dist] 缺少产物，跳过 $loader/$fileName ← ${src.absolutePath}")
                return
            }
            val dest = File(File(dist, loader), fileName)
            src.copyTo(dest, overwrite = true)
            logger.lifecycle("[dist] $loader/$fileName  (${src.length()} bytes)")
        }

        // Bukkit
        copyNamed(
            File(root, "platform/bukkit/1.12.2/build/libs/mpmt-bukkit-1.12.2-$version.jar"),
            "bukkit",
            "mpmt-bukkit-1.12.2-$version.jar",
        )
        copyNamed(
            File(root, "platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-$version.jar"),
            "bukkit",
            "mpmt-bukkit-1.20.1-$version.jar",
        )
        copyNamed(
            File(root, "platform/bukkit/1.21.1/build/libs/mpmt-bukkit-1.21.1-$version.jar"),
            "bukkit",
            "mpmt-bukkit-1.21.1-$version.jar",
        )

        // Fabric（remap 后权威 jar）
        copyNamed(
            File(root, "platform/fabric/1.20.1/build/libs/mpmt-fabric-1.20.1-$version.jar"),
            "fabric",
            "mpmt-fabric-1.20.1-$version.jar",
        )
        copyNamed(
            File(root, "platform/fabric/1.21.1/build/libs/mpmt-fabric-1.21.1-$version.jar"),
            "fabric",
            "mpmt-fabric-1.21.1-$version.jar",
        )

        // Forge 1.20.1：必须用 reobf 输出（SRG），勿用 libs 中间 named jar
        copyNamed(
            File(root, "platform/forge/1.20.1/build/reobfShadowJar/output.jar"),
            "forge",
            "mpmt-forge-1.20.1-$version.jar",
        )

        // NeoForge / Sponge
        copyNamed(
            File(root, "platform/neoforge/1.20.2/build/libs/mpmt-neoforge-1.20.2-$version.jar"),
            "neoforge",
            "mpmt-neoforge-1.20.2-$version.jar",
        )
        copyNamed(
            File(root, "platform/sponge/1.20.1/build/libs/mpmt-sponge-1.20.1-$version.jar"),
            "sponge",
            "mpmt-sponge-1.20.1-$version.jar",
        )

        // 跨代 Forge：仅捞已存在产物（禁止嵌套 gradlew）
        val forge121 =
            File(root, "platform/forge/1.21.1/build/libs/mpmt-forge-1.21.1-$version.jar")
        if (forge121.isFile) {
            copyNamed(forge121, "forge", "mpmt-forge-1.21.1-$version.jar")
        } else {
            logger.lifecycle(
                """
                |[dist] 未找到 Forge 1.21.1 产物（可选）。请用自有 wrapper（Java 21 + Gradle 8.12.1）：
                |  ./platform/forge/1.21.1/gradlew --no-daemon jar
                |然后再跑 :collectReleaseArtifacts
                """.trimMargin(),
            )
        }
        val forge112Reobf = File(root, "platform/forge/1.12.2/build/reobfJar/output.jar")
        if (forge112Reobf.isFile) {
            copyNamed(forge112Reobf, "forge", "mpmt-forge-1.12.2-$version.jar")
        } else {
            logger.lifecycle(
                """
                |[dist] 未找到 Forge 1.12.2 reobf 产物（可选，client-only）。请用自有 wrapper（Java 8 + Gradle 5.6.4）：
                |  ./platform/forge/1.12.2/gradlew --no-daemon reobfJar
                |然后再跑 :collectReleaseArtifacts
                """.trimMargin(),
            )
        }

        logger.lifecycle("[dist] 完成：${dist.absolutePath}")
    }
}

// 一键全量构建：复合构建的 includeBuild 默认不并入根 build 生命周期，这里聚合
// 根构建各子模块 + 各独立 includeBuild 平台的 build，并显式执行最终发布产物结构门 + dist 聚合。
tasks.register("buildAll") {
    group = "build"
    description = "构建全部模块、校验发布产物并聚合到 build/dist/"
    dependsOn(subprojects.map { "${it.path}:build" })
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
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
            |[mpmt-realserver] B 车道覆盖（路径已对齐每版本工程）
            |  Fabric 1.20.1   platform-fabric-1.20.1    客户端=Fabric gametest
            |  Fabric 1.21.1   platform-fabric-1.21.1    客户端=Fabric gametest
            |  Forge 1.20.1    platform-forge-1.20.1     客户端=Forge acceptance
            |  Forge 1.21.1    platform/forge/1.21.1 自有 launcher（不 includeBuild；见 :runRealServerAcceptanceForge121）
            |  NeoForge        platform-neoforge         客户端=NeoForge acceptance
            |  Bukkit/Paper    :platform:bukkit:1.20.1   客户端=Fabric gametest
            |  Folia           同上 1.20.1；矩阵默认 R6
            |  CatServer R5    :platform:bukkit:1.12.2 + Forge 1.12 client-only 伴侣（禁止 Forge 服务端 mod）
            |  Sponge          platform-sponge
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
    dependsOnIncludedIfPresent("platform-fabric-1.20.1", ":runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceFabric121",
    "Fabric 1.21.1 专用服门禁",
) {
    dependsOnIncludedIfPresent("platform-fabric-1.21.1", ":runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceForge",
    "Forge 1.20.1 专用服门禁：须先实跑 Forge 服 + Forge acceptance 客户端伴侣",
) {
    dependsOnIncludedIfPresent("platform-forge-1.20.1", ":runRealServerAcceptance")
}

/** 跨代 Forge 1.21.1：独立 launcher，禁止嵌套 gradlew；仅打印步骤。 */
tasks.register("runRealServerAcceptanceForge121") {
    group = "verification"
    description =
        "Forge 1.21.1 专用服门禁说明（独立 launcher）：打印步骤；须在 platform/forge/1.21.1 用自有 wrapper 跑报告门"
    doLast {
        logger.lifecycle(
            """
            |[runRealServerAcceptanceForge121]
            |Forge 1.21.1 不在根 includeBuild（Gradle 8.12.1 + Java 21）。请在独立目录：
            |  cd platform/forge/1.21.1
            |  ./gradlew --no-daemon printRealServerAcceptanceRecipe
            |  ./gradlew --no-daemon packageArtifacts
            |  # 起服 + 客户端伴侣 + 报告门见 printRealServerAcceptanceRecipe
            |  ./gradlew --no-daemon verifyAcceptanceReport
            |产物可再由根 :collectReleaseArtifacts 捞入 build/dist/forge/（若 jar 已存在）。
            """.trimMargin(),
        )
    }
}

/** Forge 1.12.2 client-only：真服走 CatServer R5，禁止 Forge 专用服。 */
tasks.register("runRealServerAcceptanceForge112") {
    group = "verification"
    description =
        "Forge 1.12.2 说明：client-only；真服请用 :runRealServerAcceptanceCatServer（禁止 Forge 服务端 mod）"
    doLast {
        logger.lifecycle(
            """
            |[runRealServerAcceptanceForge112]
            |ADR-0021：1.12.2 Forge 只产 client-only 客户端，不得装入 CatServer 作服务端 mod。
            |真服矩阵 R5：
            |  ./gradlew :runRealServerAcceptanceCatServer
            |  # 即 :platform:bukkit:1.12.2:runRealServerAcceptance（报告 RESULT PASS）
            |客户端伴侣构建（Java 8 + Gradle 5.6.4，自有 wrapper）：
            |  ./platform/forge/1.12.2/gradlew --no-daemon reobfJar reobfAcceptanceJar
            |  产物：platform/forge/1.12.2/build/reobfJar/output.jar
            |        platform/forge/1.12.2/build/libs/…acceptance…
            """.trimMargin(),
        )
    }
}

registerLaneGate(
    "runRealServerAcceptanceNeoForge",
    "NeoForge 专用服门禁：须先实跑 NeoForge 服 + NeoForge acceptance 客户端伴侣",
) {
    dependsOnIncludedIfPresent("platform-neoforge", ":runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceBukkit",
    "Paper/Bukkit 宿主门禁（默认 1.20.1）：产品+验收插件部署后，Fabric gametest 客户端进服写报告",
) {
    dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceFolia",
    "Folia 宿主门禁（矩阵 R6）：1.20.1 产物，Folia 实跑 + Fabric gametest 客户端",
) {
    dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceCatServer",
    "CatServer 融合服门禁（矩阵 R5）：Bukkit 1.12.2 活跃 + Forge 1.12.2 optional 客户端",
) {
    dependsOn(":platform:bukkit:1.12.2:runRealServerAcceptance")
}

registerLaneGate(
    "runRealServerAcceptanceSponge",
    "Sponge 宿主门禁：Sponge 服 + Fabric gametest 客户端进服",
) {
    dependsOnIncludedIfPresent("platform-sponge", ":runRealServerAcceptance")
}

/** 默认：全服务端 lane 串行门禁（各 lane 须已自行完成「服 + 自有 gametest 客户端」并落报告）。 */
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "B 完整：全服务端 realserver 报告门禁（含 Fabric121 / NeoForge / Sponge；不含 Forge 1.21/1.12 自有 launcher）"
    dependsOn(
        "runRealServerAcceptanceFabric",
        "runRealServerAcceptanceFabric121",
        "runRealServerAcceptanceForge",
        "runRealServerAcceptanceNeoForge",
        "runRealServerAcceptanceBukkit",
        "runRealServerAcceptanceFolia",
        "runRealServerAcceptanceCatServer",
        "runRealServerAcceptanceSponge",
    )
}

/**
 * P2 核心矩阵真服门禁（FR-12）：仅 R1–R6 相关车道，不阻断 NeoForge / Sponge。
 *
 * <p>各 lane 仍须先自行完成「服 + 自有 gametest 客户端」并落 RESULT PASS 报告；
 * 本任务只读权威报告，不嵌套 gradlew、不调用 buildAll。
 */
tasks.register("runP2RealServerAcceptance") {
    group = "verification"
    description =
        "P2 核心矩阵 realserver 门禁：Fabric 1.20/1.21 + Forge 1.20 + Bukkit/Folia + CatServer（不含 NeoForge/Sponge）"
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
    dependsOnIncludedIfPresent("platform-fabric-1.20.1", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-fabric-1.21.1", ":verifyPackaging")
    dependsOnIncludedIfPresent("platform-forge-1.20.1", ":verifyPackaging")
    doLast {
        val java8 = System.getenv("MPMT_JAVA8_HOME")
        val java17 = System.getenv("MPMT_JAVA17_HOME")
        val java21 = System.getenv("MPMT_JAVA21_HOME")
        if (java8.isNullOrBlank() || java17.isNullOrBlank() || java21.isNullOrBlank()) {
            logger.warn(
                "[verifyVersionMatrixBuild] 建议设置 MPMT_JAVA8_HOME / MPMT_JAVA17_HOME / " +
                    "MPMT_JAVA21_HOME（P2 跨代车道显式 JDK；当前未齐）",
            )
        } else {
            logger.lifecycle(
                "[verifyVersionMatrixBuild] JDK 环境：8=$java8 17=$java17 21=$java21",
            )
        }
        logger.lifecycle(
            """
            |[verifyVersionMatrixBuild] 根可聚合部分已校验。
            |Forge 1.21.1 / 1.12.2 须在对应目录用自有 wrapper（禁止嵌套 gradlew）：
            |  # Java 21 + Gradle 8.12.1
            |  ./platform/forge/1.21.1/gradlew --no-daemon build
            |  # Java 8 + Gradle 5.6.4
            |  ./platform/forge/1.12.2/gradlew --no-daemon build
            |P2 真服子门：./gradlew :runP2RealServerAcceptance（须先 R1–R6 落 RESULT PASS）
            |全 lane（含 NeoForge/Sponge）：./gradlew :runRealServerAcceptance
            """.trimMargin(),
        )
    }
}

/**
 * P2 版本矩阵聚合门（FR-12 / ADR-0021）：
 * 构建矩阵 + P2 核心 realserver 报告门；不调用 buildAll，不阻断 NeoForge/Sponge。
 *
 * <p>文档别名：历史文稿中的 runP2StrictCheck 与本任务等价。
 */
tasks.register("runVersionMatrixGate") {
    group = "verification"
    description =
        "P2 版本矩阵门禁：verifyVersionMatrixBuild + runP2RealServerAcceptance（不含 NeoForge/Sponge）"
    dependsOn("verifyVersionMatrixBuild", "runP2RealServerAcceptance")
}

/** 历史别名：与 :runVersionMatrixGate 等价。 */
tasks.register("runP2StrictCheck") {
    group = "verification"
    description = "P2 严格门别名（等价 :runVersionMatrixGate）"
    dependsOn("runVersionMatrixGate")
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

