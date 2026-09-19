import buildconventions.FabricLaneExtension
import buildconventions.packagingVerification
import buildconventions.verifyDefaultTrackReport
import buildconventions.verifyFabricProductJar
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask

// Fabric 1.20.1 车道（根构建子模块）：common + server + client 分目录 → mpmt-fabric-1.20.1-<version>.jar。
// 不可变契约：产物名与路径、jar → shadowJar → remapJar 打包链路（core 不被 remap、snakeyaml relocate，ADR-0012）、
// 唯一 L4（v1_20）、fabric.mod.json 元数据、realserver 默认轨 REAL_REQUIRED 场景清单与判定强度（ADR-0014）。
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

// 本子模块仅服务 MC 1.20.1（每版本一个子模块）
val mcVersion = "1.20.1"
val loaderVersion = "0.16.5"
val fabricApiVersion = "0.92.2+1.20.1"
val targetJavaVersion = 17
val selectedL4Name = "v1_20"
val unselectedL4Name = "v1_21"
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
// 质量工具链：装配由 build-conventions.quality 插件承担（本工程无偏离项）

// fabric 车道参数：版本与本车道接线差异在此声明；
// gametest 源集与依赖接线、Loom run、验收元数据注入、模拟服门禁、mod 元数据展开、单测系统属性均由插件承担。
val laneArgFabricApiVersion = fabricApiVersion
val laneArgLoaderVersion = loaderVersion
val laneArgMcVersion = mcVersion
val laneArgTargetJavaVersion = targetJavaVersion
fabricLane {
    mcVersion.set(laneArgMcVersion)
    targetJavaVersion.set(laneArgTargetJavaVersion)
    loaderVersion.set(laneArgLoaderVersion)
    fabricApiVersion.set(laneArgFabricApiVersion)
    // 本车道偏离项：acceptanceServer 不额外依赖 gametestClasses、客户端 run 不写回服务端地址系统属性、无矩阵轨
    acceptanceServerCompilesGametest.set(false)
    acceptanceClientExposesServerProperty.set(false)
}

// 插件公开 API 需要扩展实例（块外传参用），故在此取回一次
val fabric = extensions.getByType(buildconventions.FabricLaneExtension::class.java)

// 专用配置：需 shade 进产物并 relocate 的内容（core + 第三方运行期依赖），不参与 Loom remap
val shadowBundle: Configuration by configurations.creating

val verifyVersionSelection by tasks.registering {
    group = "verification"
    description = "校验本版本构建仅含固定 L4 目录（" + selectedL4Name + "）"
    // 源树与两个 L4 名在配置期取出：动作只捕获这些值，不捕获脚本对象（配置缓存要求），判定与文案不变。
    val javaTree = fileTree("common/src/main/java")
    val selectedPattern = "**/version/" + selectedL4Name + "/**"
    val unselectedPattern = "**/version/" + unselectedL4Name + "/**"
    val selectedL4 = selectedL4Name
    val unselectedL4 = unselectedL4Name
    doLast {
        val hasSelected = javaTree.matching { include(selectedPattern) }.files.isNotEmpty()
        val hasUnselected = javaTree.matching { include(unselectedPattern) }.files.isNotEmpty()
        if (!hasSelected) throw GradleException("Fabric 版本校验失败：缺少 " + selectedL4)
        if (hasUnselected) throw GradleException("Fabric 版本校验失败：混入 " + unselectedL4)
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
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
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
    // 车道版本与选中 / 未选中 L4 名在配置期取成局部值：断言 lambda 只捕获这些值，
    // 不捕获脚本对象（配置缓存要求）；断言内容、顺序与失败文案不变。
    val laneMcVersion = mcVersion
    val selectedL4 = selectedL4Name
    val unselectedL4 = unselectedL4Name
    packagingVerification(
        laneLabel = "Fabric",
        mcVersion = laneMcVersion,
        product = tasks.named<RemapJarTask>("remapJar").flatMap { it.archiveFile },
        acceptance = null,
    ) { product, _ ->
        verifyFabricProductJar(product, laneMcVersion, selectedL4, unselectedL4)
    }
}

tasks.named("build") {
    dependsOn(verifyPackaging, verifyVersionSelection)
}

// realserver 验收门禁：严格校验 acceptance v2 + 默认轨 REAL_REQUIRED 全 PASS（ADR-0014）。
// 实跑：① runAcceptanceServer ② runAcceptanceClient（须显示）③ 本任务读报告。
tasks.register("runRealServerAcceptance") {
    group = "verification"
    description = "严格校验 Fabric realserver acceptance v2 报告与完整默认轨 REAL_REQUIRED"
    doLast {
        val report = fabric.acceptanceReport.get().asFile
        // 校验实现与其余车道共用 build-conventions 的单份实现（判定顺序与失败文案逐字保留）。
        verifyDefaultTrackReport(project, report, "", "fabric", fabric.realScenarios.get())
    }
}

// 模拟服默认轨场景清单交插件门禁（runSimNetworkAcceptance）逐项校验
