import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RemapJarTask
import java.util.zip.ZipFile

// platform-fabric（L3）：M0 阶段只验证构建骨架 + 打包链路，不含平台胶水逻辑。
// 关键链路（ADR-0012）：core 纯 Java 经 shadow shade 进产物（不被 remap），snakeyaml relocate；
// remapJar 消费 shadowJar 产物产出最终 remapped mod jar。映射用 Mojang 官方（ADR-0016）。

plugins {
    id("fabric-loom") version "1.7.4"
    id("com.gradleup.shadow") version "8.3.3"
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

// 专用配置：需 shade 进产物并 relocate 的内容（core + 第三方运行期依赖），不参与 Loom remap
val shadowBundle: Configuration by configurations.creating

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

    // 跨栈字节对齐 spike 的纯 JVM 测试
    testImplementation(platform("org.junit:junit-bom:5.10.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
