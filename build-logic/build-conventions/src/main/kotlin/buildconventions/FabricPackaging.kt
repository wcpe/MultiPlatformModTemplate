package buildconventions

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar

/** fabric 产品链路里的任务名：shade 产物与 loom 的 remap 出口。 */
internal const val FABRIC_SHADOW_JAR_TASK = "shadowJar"
internal const val FABRIC_REMAP_JAR_TASK = "remapJar"

/**
 * `remapJar` 的输入接线：以其自身的 `shadowJar` 产物为输入，并去掉 classifier 作为最终产品。
 *
 * <p>三条 fabric 车道里 1.20.1 / 1.21.1 逐字相同；26.2 无混淆、产品直接由 `shadowJar` 产出
 * （`lane.productTaskName` 设为 shadowJar），故不存在 `remapJar`，本函数对它是空操作。
 *
 * <p>与 Forge / NeoForge 同因同法：loom 的 `inputFile` 与 `ShadowJar` 类型不在本插件工程的
 * 编译类路径内，故按任务名取 `AbstractArchiveTask` 并用 Groovy 动态调用设值，
 * 效果等价于车道脚本里的 `inputFile.set(shadowJar.flatMap { it.archiveFile })`。
 */
internal fun registerFabricRemapJarWiring(project: Project) {
    if (project.tasks.findByName(FABRIC_REMAP_JAR_TASK) == null) {
        return
    }

    val remapJar = project.tasks.named(FABRIC_REMAP_JAR_TASK, AbstractArchiveTask::class.java)
    val shadowJar = project.tasks.named(FABRIC_SHADOW_JAR_TASK, Jar::class.java)

    remapJar.configure {
        dependsOn(shadowJar)
        invokeGroovy("setInputFile", shadowJar.flatMap { it.archiveFile })
        archiveClassifier.set("")
    }
}

/**
 * fabric 三条车道共用的产品 jar 打包断言清单（`verifyPackaging` 的检查体）。
 *
 * 三条车道的断言逐字相同（仅 L4 名随车道不同），故收为单份实现，车道只传参与值。
 * 断言内容、顺序与失败文案与迁移前逐字一致——这是有效判定强度，改动即视为改门禁。
 *
 * @param product 产品 jar 视图。
 * @param mcVersion 车道 MC 版本（参与产物名断言与通过日志）。
 * @param selectedL4 本车道选中的 L4 名（如 `v26_2`）。
 * @param unselectedL4 同仓库内未被本车道选中的另一个 L4 名（用于「混入」断言）。
 */
fun PackagingAssertions.verifyFabricProductJar(
    product: JarView,
    mcVersion: String,
    selectedL4: String,
    unselectedL4: String,
) {
    val selectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$selectedL4/"
    val unselectedPrefix = "top/wcpe/mc/mpmt/platform/fabric/version/$unselectedL4/"
    must(product.file.name.contains(mcVersion), "产物名未包含 MC 版本")
    mustContain(product, "top/wcpe/mc/mpmt/kernel/Mpmt.class", "core 类未 shade 进产物")
    mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "platform-spi 未 shade 进产物")
    mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "snakeyaml 未 relocate")
    mustNotBundle(product, listOf("org/yaml/snakeyaml/"), "snakeyaml 原包名残留")
    mustNotBundle(product, listOf("META-INF/maven/org.yaml/"), "snakeyaml Maven 元数据残留")
    mustContain(product, "fabric.mod.json", "产物缺少 fabric.mod.json")
    mustNotBundle(product, listOf("net/minecraft/"), "产物内不应直接包含 Minecraft 类")
    mustContainPrefix(product, selectedPrefix, "缺少选中 L4：$selectedL4")
    mustNotBundle(product, listOf(unselectedPrefix), "混入未选中 L4：$unselectedL4")
    log("Fabric $mcVersion 打包校验通过：${product.file.name}（条目 ${product.entries.size}）")
}
