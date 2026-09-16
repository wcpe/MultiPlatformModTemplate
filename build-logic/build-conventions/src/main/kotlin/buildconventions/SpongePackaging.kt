package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar

/** 产品薄包任务名（仅本模块类，`plain` classifier）。 */
private const val SPONGE_PLAIN_JAR_TASK: String = "jar"

/** 产品自包含任务名（shade core/spi + relocate 第三方）。Sponge 不 remap，它就是最终产物。 */
internal const val SPONGE_SHADOW_JAR_TASK: String = "shadowJar"

/** 打包校验任务名。 */
private const val SPONGE_VERIFY_PACKAGING_TASK: String = "verifyPackaging"

/** 需 shade 进产品 jar 的专用配置名（车道按此名声明并加依赖）。 */
private const val SPONGE_SHADOW_BUNDLE_CONFIGURATION: String = "shadowBundle"

/** shadow 插件扩展名：只用于取 shadow 插件的类加载器（其类型不在插件工程编译类路径内）。 */
internal const val SPONGE_SHADOW_EXTENSION: String = "shadow"

/** shadow 插件的自包含 jar 任务类型全名。 */
internal const val SPONGE_SHADOW_JAR_CLASS: String = "com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar"

/** snakeyaml 原包名。 */
private const val SNAKEYAML_PACKAGE: String = "org.yaml.snakeyaml"

/** snakeyaml relocate 目标包名（ADR-0012：第三方一律 relocate 到 libs.*，防类冲突）。 */
private const val SNAKEYAML_RELOCATED_PACKAGE: String = "top.wcpe.mc.mpmt.libs.org.yaml.snakeyaml"

/**
 * 打包链路：薄包 `jar`（classifier `plain`）与自包含产品 `shadowJar`（classifier 空）。
 *
 * 最终插件 jar = shadowJar（shade `shadowBundle` + relocate snakeyaml）；Sponge 运行期不 remap、
 * 无 reobf，故不再接 copy / reobf 任务。
 */
internal fun configureSpongePackagingChain(project: Project) {
    project.tasks.named(SPONGE_PLAIN_JAR_TASK, Jar::class.java).configure { archiveClassifier.set("plain") }
    project.tasks.named(SPONGE_SHADOW_JAR_TASK, Jar::class.java).configure {
        archiveClassifier.set("")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        // 仅打入 shadowBundle 指定内容（`configurations` 只存在于 shadow 类型上，经动态调用表达）
        invokeGroovy(
            "setConfigurations",
            listOf(project.configurations.getByName(SPONGE_SHADOW_BUNDLE_CONFIGURATION)),
        )
        from(spongeMetadataDirectory(project))
        invokeGroovy("relocate", SNAKEYAML_PACKAGE, SNAKEYAML_RELOCATED_PACKAGE)
        exclude("META-INF/maven/**")
        // shadow 改配置不刷新缓存指纹，令其确定性重跑、不缓存（与其它平台一致）
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

/**
 * 打包校验：最终插件必须与普通薄包分离，并完整包含核心、SPI、元数据和已 relocate 依赖。
 *
 * 断言清单与失败文案与迁移前车道脚本逐条一致。
 */
internal fun registerSpongePackagingVerification(project: Project) {
    val productJar = project.tasks.named(SPONGE_SHADOW_JAR_TASK, Jar::class.java)
    project.tasks.register(SPONGE_VERIFY_PACKAGING_TASK) {
        group = "verification"
        description = "校验 Sponge 插件 jar：最终产品自包含、输出不冲突、未打入 SpongeAPI"
        dependsOn(project.tasks.named(SPONGE_SHADOW_JAR_TASK))
        packagingVerification(
            laneLabel = "Sponge",
            product = productJar.flatMap { it.archiveFile },
            acceptance = null,
        ) { product, _ ->
            val shadow = project.tasks.named(SPONGE_SHADOW_JAR_TASK, Jar::class.java).get()
            val plain = project.tasks.named(SPONGE_PLAIN_JAR_TASK, Jar::class.java).get()
            must(plain.archiveFile.get().asFile != product.file, "普通 jar 与最终 shadowJar 输出路径冲突")
            must(!shadow.isPreserveFileTimestamps, "最终产品仍保留源文件时间戳，无法确定性构建")
            must(shadow.isReproducibleFileOrder, "最终产品未启用可复现文件顺序")
            mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "核心类未 shade 进插件 jar")
            mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "platform-spi 未 shade 进插件 jar")
            mustContain(product, "top/wcpe/mc/mpmt/platform/sponge/MpmtSpongePlugin.class", "缺少插件主类")
            mustContainPrefix(
                product,
                "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/",
                "snakeyaml 未 relocate 到 libs.*",
            )
            mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
            mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
            mustContain(product, "META-INF/sponge_plugins.json", "缺少 META-INF/sponge_plugins.json")
            mustContain(
                product,
                "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap",
                "缺少 SPI services 声明",
            )
            mustNotBundle(product, listOf("org/spongepowered/api/"), "误把 SpongeAPI 打入插件 jar（应由服务端提供）")
        }
    }
    project.tasks.named("assemble").configure { dependsOn(SPONGE_VERIFY_PACKAGING_TASK) }
}
