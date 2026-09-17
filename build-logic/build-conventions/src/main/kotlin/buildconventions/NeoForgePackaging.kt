package buildconventions

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.jvm.tasks.Jar
import java.io.File

/** 产品薄包任务名：loom 约定产物落 `build/devlibs` 且带 `dev` 分类器（用户开发版，非最终产品）。 */
private const val NEOFORGE_PLAIN_JAR_TASK: String = "jar"

/** 产品自包含中间产物任务名（shade core/spi + relocate snakeyaml）。 */
private const val NEOFORGE_SHADOW_JAR_TASK: String = "shadowJar"

/** 最终产品任务名：NeoForge 运行期即官方 Mojmap，remapJar 恒等重映射后落产品命名。 */
internal const val NEOFORGE_REMAP_JAR_TASK: String = "remapJar"

/** 打包校验任务名。 */
private const val NEOFORGE_VERIFY_PACKAGING_TASK: String = "verifyPackaging"

/** 打包出口任务名。 */
private const val NEOFORGE_PACKAGE_ARTIFACTS_TASK: String = "packageArtifacts"

/** 需 shade 进产品 jar 的专用配置名（车道按此名声明并加依赖）。 */
internal const val NEOFORGE_SHADOW_BUNDLE_CONFIGURATION: String = "shadowBundle"

/** 普通 jar 的 classifier（loom 约定：用户开发版，非最终产品）。 */
private const val NEOFORGE_DEV_CLASSIFIER: String = "dev"

/** shadowJar 中间产物 classifier。 */
private const val NEOFORGE_SHADOW_CLASSIFIER: String = "dev-shadow"

/** snakeyaml 原包名。 */
private const val SNAKEYAML_PACKAGE: String = "org.yaml.snakeyaml"

/** snakeyaml relocate 目标包名（ADR-0012：第三方一律 relocate 到 libs.*，防类冲突）。 */
private const val SNAKEYAML_RELOCATED_PACKAGE: String = "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml"

/** Maven 元数据归档噪声（relocate 不改写，故产品与验收产物一律剔除）。 */
internal const val NEOFORGE_MAVEN_METADATA_EXCLUDE: String = "META-INF/maven/**"

/**
 * 最终产品 jar 文件（`remapJar` 产物，无 classifier）：realserver 与模拟服驱动按它算 SHA。
 *
 * 取值与产物命名同源（`base { archivesName }` + 工程版本），避免两处各写一遍路径。
 */
internal fun neoforgeProductJarFile(project: Project): File =
    project.tasks.named(NEOFORGE_REMAP_JAR_TASK, Jar::class.java).get().archiveFile.get().asFile

/**
 * 打包链路：薄包 `jar`（classifier `dev`）→ 自包含中间产物 `shadowJar` → 最终产品 `remapJar`。
 *
 * shadowJar shade `shadowBundle` 并 relocate snakeyaml；NeoForge 1.20.2 生产运行期即 Mojang 命名
 * （arch-loom usesMojangAtRuntime 恒真）→ remapJar 恒等重映射，产出无 classifier 的最终产品 jar
 * （承担 userdev 时代 shadowJar 的产品语义与输出路径）。
 *
 * shadow 与 loom 的类型不在插件工程编译类路径内，`configurations` / `relocate` / `inputFile` 经动态调用表达。
 */
internal fun configureNeoForgePackagingChain(project: Project) {
    project.tasks.named(NEOFORGE_PLAIN_JAR_TASK, Jar::class.java).configure {
        archiveClassifier.set(NEOFORGE_DEV_CLASSIFIER)
    }
    project.tasks.named(NEOFORGE_SHADOW_JAR_TASK, Jar::class.java).configure {
        archiveClassifier.set(NEOFORGE_SHADOW_CLASSIFIER)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        invokeGroovy(
            "setConfigurations",
            listOf(project.configurations.getByName(NEOFORGE_SHADOW_BUNDLE_CONFIGURATION)),
        )
        invokeGroovy("relocate", SNAKEYAML_PACKAGE, SNAKEYAML_RELOCATED_PACKAGE)
        exclude(NEOFORGE_MAVEN_METADATA_EXCLUDE)
        // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    // remapJar 改吃 shadowJar 产物，使 core / 第三方随之进入最终产品 jar（恒等映射不改动内容，仅落位产品命名）
    val shadowJar = project.tasks.named(NEOFORGE_SHADOW_JAR_TASK, Jar::class.java)
    project.tasks.named(NEOFORGE_REMAP_JAR_TASK, Jar::class.java).configure {
        dependsOn(shadowJar)
        (groovyValue("getInputFile") as RegularFileProperty).set(shadowJar.flatMap { it.archiveFile })
        archiveClassifier.set("")
    }
}

/**
 * 打包校验：最终产品必须是无 classifier 的 remapJar 产物（内容为 shadowJar 的恒等重映射），
 * 并包含运行所需核心与平台元数据。
 *
 * 断言清单与失败文案与迁移前车道脚本逐条一致。
 */
internal fun registerNeoForgePackagingVerification(project: Project) {
    val productJar = project.tasks.named(NEOFORGE_REMAP_JAR_TASK, Jar::class.java)
    val plainJar = project.tasks.named(NEOFORGE_PLAIN_JAR_TASK, Jar::class.java)
    val shadowJar = project.tasks.named(NEOFORGE_SHADOW_JAR_TASK, Jar::class.java)
    project.tasks.register(NEOFORGE_VERIFY_PACKAGING_TASK) {
        group = "verification"
        description = "校验 NeoForge mod jar：核心 shade、snakeyaml relocate、mods.toml/services 在位、未打入 Minecraft"
        dependsOn(productJar)
        // 输出冲突与可复现性断言所需取值在配置期取出：动作内不得再访问 project / Task（配置缓存要求），
        // 断言取值与判定顺序、失败文案与迁移前逐字一致。
        val plainArchive = plainJar.get().archiveFile.get().asFile
        val shadowPreservesTimestamps = shadowJar.get().isPreserveFileTimestamps
        val shadowReproducibleOrder = shadowJar.get().isReproducibleFileOrder
        packagingVerification(
            laneLabel = "NeoForge",
            product = productJar.flatMap { it.archiveFile },
            acceptance = null,
        ) { product, _ ->
            must(plainArchive != product.file, "普通 jar 与最终产品 jar 输出路径冲突")
            must(!shadowPreservesTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
            must(shadowReproducibleOrder, "最终产品未启用可复现文件顺序")
            mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "核心类未 shade 进 mod jar")
            mustContain(
                product,
                "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class",
                "platform-spi 未 shade 进 mod jar",
            )
            mustContain(product, "top/wcpe/mc/mpmt/platform/neoforge/MpmtNeoForgeMod.class", "缺少 NeoForge mod 主类")
            mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "snakeyaml 未 relocate 到 libs.*")
            mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
            mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
            mustContain(product, "META-INF/mods.toml", "缺少 META-INF/mods.toml")
            mustContain(
                product,
                "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap",
                "缺少 SPI services 声明",
            )
            mustNotBundle(product, listOf("net/minecraft/"), "误把 Minecraft 类打入 mod jar")
            println("NeoForge 打包校验通过：")
            println(" 产物 = ${product.file.name}（条目数 ${product.entries.size}）")
            println(" 核心已 shade、snakeyaml 已 relocate、mods.toml/services 在位、未打入 Minecraft")
        }
    }
    project.tasks.named("assemble").configure { dependsOn(NEOFORGE_VERIFY_PACKAGING_TASK) }
}

/** 打包并校验入口：产品与 realserver 验收产物（验收驱动 mod jar 由验收接入层注册）。 */
internal fun registerNeoForgePackageArtifacts(project: Project) {
    project.tasks.register(NEOFORGE_PACKAGE_ARTIFACTS_TASK) {
        group = "build"
        description = "构建 NeoForge 产品与 realserver 验收产物"
        dependsOn(NEOFORGE_VERIFY_PACKAGING_TASK, NEOFORGE_ACCEPTANCE_JAR_TASK)
    }
}
