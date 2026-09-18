package buildconventions

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
    mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "core 类未 shade 进产物")
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
