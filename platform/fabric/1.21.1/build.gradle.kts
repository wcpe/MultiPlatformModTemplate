import buildconventions.FabricLaneExtension
import buildconventions.packagingVerification
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

// Fabric 1.21.1 车道（根构建子模块）：common + server + client 分目录 → mpmt-fabric-1.21.1-<version>.jar。
// 不可变契约：产物名与路径、jar → shadowJar → remapJar 打包链路（core 不被 remap、snakeyaml relocate，ADR-0012）、
// 唯一 L4（v1_21）、fabric.mod.json 元数据、realserver 默认轨 REAL_REQUIRED 场景清单与判定强度（ADR-0014）。
// gametest 接入层 / Loom run / 验收注入 / 模拟服门禁 / 元数据展开集中在 build-conventions.fabric（ADR-0027）。
plugins {
    id("build-conventions.quality")
    id("top.wcpe.loom")
    // 车道约定插件须在 loom 之后应用：gametest 源集要晚于 loom 按现有源集注册 migrate*Mappings 任务的时机创建
    id("build-conventions.fabric")
    id("com.gradleup.shadow") version "8.3.11"
    // 分析类插件（spotbugs / ktlint / detekt / kover）不在车道内声明：由 build-conventions.quality 应用，
    // 版本与类路径由根 plugins{} 单点 pin，重复声明会分裂插件类加载器并破坏 loom 的清单服务。
}

group = "top.wcpe.mc.mpmt"

// 本子模块仅服务 MC 1.21.1（每版本一个子模块）
val mcVersion = "1.21.1"
val loaderVersion = "0.19.3"
val fabricApiVersion = "0.116.14+1.21.1"
val targetJavaVersion = 21
val selectedL4Name = "v1_21"
val unselectedL4Name = "v1_20"
val snakeyamlVersion = "2.2"
// 依赖 platform-spi（经 api 传递 core-runtime + core-domain），经项目依赖消费
val platformApiCoordinate = project(":platform:fabric:fabric-api")
val spiCoordinate = project(":core:spi")
// 依赖 core-server（服务端网络装配特性 ServerNetworkFeature；经 api 传递 protocol + core-runtime）
val serverCoordinate = project(":core:server")
// 依赖 core-client（客户端网络装配特性 ClientNetworkFeature + 弱标识提供者）
val clientCoordinate = project(":core:client")
// realserver 验收 harness 平台无关核心（仅 gametest 接入层用，不入产品 jar，ADR-0014）
val acceptanceCoordinate = project(":modules:acceptance")

base {
    // 最终产物名同时标识平台与 MC 目标，避免跨车道串扰
    archivesName.set("mpmt-fabric-$mcVersion")
}

java {
    // Fabric 胶水按目标版本固定 Java 17 / 21
    toolchain { languageVersion = JavaLanguageVersion.of(targetJavaVersion) }
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}
// 质量工具链：装配由 build-conventions.quality 插件承担；本车道仅偏离分析 JVM（跟随本车道目标）
quality {
    analysisJavaVersion.set(targetJavaVersion)
}

// fabric 车道参数：版本与矩阵轨 JDK 环境变量在此声明；
// gametest 源集与依赖接线、Loom run、验收元数据注入、模拟服门禁、mod 元数据展开、单测系统属性均由插件承担。
val fabric = extensions.getByType(FabricLaneExtension::class.java)
fabric.mcVersion.set(mcVersion)
fabric.targetJavaVersion.set(targetJavaVersion)
fabric.loaderVersion.set(loaderVersion)
fabric.fabricApiVersion.set(fabricApiVersion)
// 矩阵轨 java 可执行文件取自本车道目标 JDK（21）
fabric.matrixJavaHomeEnvironment.set("MPMT_JAVA21_HOME")

// 专用配置：需 shade 进产物并 relocate 的内容（core + 第三方运行期依赖），不参与 Loom remap
val shadowBundle: Configuration by configurations.creating

// 单版本构建内：common / server / client 分目录（服客分离、平台只胶水）；L4 已固定拷入 common。
// Loom remap 仍由本车道工程完成（多模块各挂 Loom 代价高且易冲突）。
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
val verifyVersionSelection by tasks.registering {
    group = "verification"
    description = "校验本版本构建仅含固定 L4 目录（" + selectedL4Name + "）"
    doLast {
        val javaTree = fileTree("common/src/main/java")
        val hasSelected = javaTree.matching { include("**/version/" + selectedL4Name + "/**") }.files.isNotEmpty()
        val hasUnselected = javaTree.matching { include("**/version/" + unselectedL4Name + "/**") }.files.isNotEmpty()
        if (!hasSelected) throw GradleException("Fabric 版本校验失败：缺少 " + selectedL4Name)
        if (hasUnselected) throw GradleException("Fabric 版本校验失败：混入 " + unselectedL4Name)
    }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    // Fabric 平台 API：提供网络收发（fabric-networking-api-v1）等；编译期依赖，运行期由宿主提供
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    // 共享核心（platform-spi + 传递的 core-runtime/core-domain）：纯 Java、非 mod 依赖、不参与 remap
    implementation(platformApiCoordinate)
    implementation(spiCoordinate)
    shadowBundle(platformApiCoordinate)
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

// 打包校验：core shade、snakeyaml relocate、唯一 L4、fabric.mod.json
val verifyPackaging by tasks.registering {
    group = "verification"
    description = "校验 Fabric 产品 jar：core shade、snakeyaml relocate、唯一 L4、mod 元数据"
    dependsOn(tasks.named("remapJar"), verifyVersionSelection)
    packagingVerification(
        laneLabel = "Fabric",
        mcVersion = mcVersion,
        product = tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
        acceptance = null,
    ) { product, _ ->
        val selectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$selectedL4Name/"
        val unselectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$unselectedL4Name/"
        must(product.file.name.contains(mcVersion), "产物名未包含 MC 版本")
        mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "core 类未 shade 进产物")
        mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "platform-spi 未 shade 进产物")
        mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "snakeyaml 未 relocate")
        mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
        mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
        mustContain(product, "fabric.mod.json", "产物缺少 fabric.mod.json")
        mustNotBundle(product, listOf("net/minecraft/"), "产物内不应直接包含 Minecraft 类")
        mustContainPrefix(product, selectedPrefix, "缺少选中 L4：$selectedL4Name")
        mustNotBundle(product, listOf(unselectedPrefix), "混入未选中 L4：$unselectedL4Name")
        log("Fabric $mcVersion 打包校验通过：${product.file.name}（条目 ${product.entries.size}）")
    }
}

tasks.named("build") {
    dependsOn(verifyPackaging, verifyVersionSelection)
}

val realRequiredScenarios =
    listOf(
        "acceptance/handshake-success",
        "acceptance/handshake-incompatible",
        "acceptance/machine-code-session",
        "acceptance/ban-reconnect",
        "acceptance/unban-reconnect",
        "acceptance/fragment-crc",
        "acceptance/fragment-timeout-retry-resync",
        "acceptance/session-heartbeat-rtt-timeout",
        "acceptance/capability-eventbus",
        "acceptance/hud-title",
        "acceptance/hud-actionbar",
        "acceptance/hud-toast",
        "acceptance/hud-chat",
        "acceptance/real-round-trip",
    )

// realserver 验收门禁：严格校验 acceptance v2 + 默认轨 REAL_REQUIRED 全 PASS（ADR-0014）。
// 实跑：① runAcceptanceServer ② runAcceptanceClient（须显示）③ 本任务读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description =
        "严格校验 Fabric realserver acceptance v2 报告（默认轨；-Pmpmt.acceptance.matrix 声明矩阵值时校验 MATRIX + 公共三场景 + RESULT PASS）"
    doLast {
        val matrixId = (project.findProperty("mpmt.acceptance.matrix") as String?)?.trim().orEmpty()
        val report =
            if (matrixId.isNotEmpty()) {
                val custom = (project.findProperty("mpmt.acceptance.report") as String?)?.trim().orEmpty()
                if (custom.isNotEmpty()) {
                    file(custom)
                } else {
                    layout.buildDirectory.file("acceptance/server-report-${matrixId.lowercase()}.txt").get().asFile
                }
            } else {
                fabric.acceptanceReport.get().asFile
            }
        if (!report.exists()) {
            throw GradleException(
                "未找到验收报告（先跑 runAcceptanceServer + runAcceptanceClient）：${report.absolutePath}",
            )
        }
        val text = report.readText()
        logger.lifecycle("[realserver] 服务端权威验收报告：\n$text")
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.firstOrNull() != "SERVER-GAMETEST-REPORT v2") {
            throw GradleException("[realserver] 报告不是 acceptance v2")
        }
        if (matrixId.isNotEmpty()) {
            val matrixLine = lines.firstOrNull { it.startsWith("MATRIX\t") || it.startsWith("MATRIX ") }
            if (matrixLine == null || !matrixLine.contains(matrixId)) {
                throw GradleException("[realserver] 矩阵报告缺少 MATRIX $matrixId：$matrixLine")
            }
            if (lines.last() != "RESULT PASS") {
                throw GradleException("[realserver] 矩阵 $matrixId 未通过：${report.absolutePath}")
            }
            val common = listOf("product-handshake", "product-roundtrip", "client-hud")
            for (id in common) {
                val hit =
                    lines.any {
                        it.startsWith("SCENARIO\t$id\tPASS") ||
                            it.startsWith("PASS $id") ||
                            it.contains("\t$id\tPASS")
                    }
                if (!hit) {
                    throw GradleException("[realserver] 矩阵 $matrixId 缺少公共场景 PASS：$id")
                }
            }
            logger.lifecycle("[realserver] 矩阵 $matrixId 报告 PASS：${report.absolutePath}")
            return@doLast
        }
        val metadata =
            lines.filter { it.startsWith("META ") }.associate { line ->
                val entry = line.removePrefix("META ")
                val separator = entry.indexOf('=')
                if (separator <= 0) throw GradleException("[realserver] 非法元数据行：$line")
                entry.substring(0, separator) to entry.substring(separator + 1)
            }
        val requiredMetadata =
            listOf("commit", "VERSION", "platform", "mcVersion", "serverVersion", "productJarSha256", "scenarios")
        if (requiredMetadata.any { metadata[it].isNullOrBlank() }) {
            throw GradleException("[realserver] 报告缺少 acceptance v2 必需元数据")
        }
        if (metadata["platform"] != "fabric") {
            throw GradleException("[realserver] platform 元数据必须为 fabric：${metadata["platform"]}")
        }
        if (!metadata.getValue("productJarSha256").matches(Regex("[0-9a-fA-F]{64}"))) {
            throw GradleException("[realserver] productJarSha256 元数据非法")
        }
        if (metadata["scenarios"] != realRequiredScenarios.joinToString(",")) {
            throw GradleException("[realserver] 报告场景声明不完整：${metadata["scenarios"]}")
        }
        val resultLines = lines.filter { it.startsWith("RESULT ") }
        if (resultLines != listOf("RESULT PASS") || lines.last() != "RESULT PASS") {
            throw GradleException("[realserver] 报告必须仅有一个末行 RESULT PASS")
        }
        val scenarioLines =
            lines.filter {
                it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("ERROR ") || it.startsWith("SKIP ")
            }
        val scenarios = scenarioLines.associateBy { it.split(' ', limit = 3)[1] }
        if (scenarios.size != scenarioLines.size || scenarios.keys != realRequiredScenarios.toSet()) {
            throw GradleException("[realserver] 实际场景与默认轨 REAL_REQUIRED 不一致：${scenarios.keys}")
        }
        if (scenarioLines.any { !it.startsWith("PASS ") }) {
            throw GradleException("[realserver] 默认轨场景存在非 PASS 结果")
        }
        logger.lifecycle(
            "[realserver] 验收通过 ✓ acceptance v2，${realRequiredScenarios.size} 项 REAL_REQUIRED 全部 PASS",
        )
    }
}

val simRequiredScenarios =
    listOf(
        "acceptance/handshake-success",
        "acceptance/handshake-incompatible",
        "acceptance/machine-code-session",
        "acceptance/ban-reconnect",
        "acceptance/unban-reconnect",
        "acceptance/fragment-crc",
        "acceptance/fragment-timeout-retry-resync",
        "acceptance/session-heartbeat-rtt-timeout",
        "acceptance/capability-eventbus",
        "acceptance/hud-title",
        "acceptance/hud-actionbar",
        "acceptance/hud-toast",
        "acceptance/hud-chat",
        "acceptance/integrated-loopback",
    )

// 模拟服默认轨场景清单交插件门禁（runSimNetworkAcceptance）逐项校验
fabric.simScenarios.set(simRequiredScenarios)
