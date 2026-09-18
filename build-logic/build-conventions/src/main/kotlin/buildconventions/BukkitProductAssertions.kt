package buildconventions

/**
 * bukkit 四条车道共用的产品/验收产物打包断言骨架（`verifyPackaging` 的检查体）。
 *
 * 四条车道的断言是**交错**的：公共断言之间夹着车道特有的检查，而断言按顺序 fail-fast
 *（先报哪条取决于顺序），故本函数不重排、只把公共骨架提出来，车道差异以布尔开关表达，
 * 逐位还原各车道原序列（与迁移前逐条等价，失败文案不变）。
 *
 * 开关与车道的对应：
 * - [l4Prefix]：1.12.2/1.20.1/1.21.1 传 `v1_` 前缀；26.2 传 null（该车道用"排除 Modern 与基础适配器"式计数）
 * - [expectFoliaSchedulerClass]：现代车道（1.20.1/1.21.1/26.2）为 true
 * - [rejectProductMainInAcceptance]：验收产物不得混入产品入口（1.12.2/1.20.1 为 true；1.21.1/26.2 不检查）
 * - [rejectAcceptanceInProductYml]：产品 `plugin.yml` 不得混入验收入口（仅 1.12.2）
 * - [rejectModernYmlFields]：产品不得含 Folia 类 / `folia-supported` / `api-version`（仅 1.12.2）
 * - [apiVersion]：现代车道的 `api-version` 取值；1.12.2 传 null（该车道反向断言"不得含该字段"）
 */
@Suppress("LongParameterList")
fun PackagingAssertions.verifyBukkitProducts(
    product: JarView,
    acceptance: JarView?,
    mcVersion: String,
    adapterClassPath: String,
    adapterClass: String,
    l4Prefix: String?,
    expectFoliaSchedulerClass: Boolean,
    rejectProductMainInAcceptance: Boolean,
    rejectAcceptanceInProductYml: Boolean,
    rejectModernYmlFields: Boolean,
    apiVersion: String?,
) {
    val acceptanceFile = acceptance ?: error("缺少验收产物输入")
    val adapterService =
        "META-INF/services/top.wcpe.mc.mpmt.platform.bukkit.version.BukkitVersionAdapter"

    must(product.file.name.contains(mcVersion), "产品产物名未包含 MC 版本")
    must(acceptanceFile.file.name.contains(mcVersion), "验收产物名未包含 MC 版本")
    mustContain(product, adapterClassPath, "产品缺少选中的 L4 适配器")
    val l4AdapterCount =
        if (l4Prefix != null) {
            product.entries.count { it.startsWith(l4Prefix) && it.endsWith("BukkitVersionAdapter.class") } == 1
        } else {
            product.entries.count {
                it.endsWith("BukkitVersionAdapter.class") &&
                    !it.endsWith("ModernBukkitVersionAdapter.class") &&
                    it != "top/wcpe/mc/mpmt/platform/bukkit/version/BukkitVersionAdapter.class"
            } == 1
        }
    must(l4AdapterCount, "产品包含零个或多个 L4 适配器")
    mustServiceEquals(product, adapterService, adapterClass, "产品 adapter services 与目标不符")
    mustContain(product, "top/wcpe/mc/mpmt/core/domain/Mpmt.class", "产品未 shade 核心")
    mustContain(product, "top/wcpe/mc/mpmt/platform/spi/PlatformProvider.class", "产品未 shade SPI")
    mustContain(product, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "产品缺少入口")
    if (expectFoliaSchedulerClass) {
        mustContain(
            product,
            "top/wcpe/mc/mpmt/platform/bukkit/capability/FoliaSchedulerPort.class",
            "现代产品缺少 Folia 调度类",
        )
    }
    mustNotBundle(product, listOf("top/wcpe/mc/mpmt/platform/bukkit/acceptance/"), "产品混入 acceptance")
    mustContain(
        acceptanceFile,
        "top/wcpe/mc/mpmt/platform/bukkit/acceptance/MpmtBukkitAcceptancePlugin.class",
        "验收缺少入口",
    )
    if (rejectProductMainInAcceptance) {
        mustNotContain(acceptanceFile, "top/wcpe/mc/mpmt/platform/bukkit/MpmtBukkitPlugin.class", "验收混入产品入口")
    }
    mustNotBundle(product, listOf("org/bukkit/", "io/papermc/"), "产品误打入 Bukkit/Paper API")
    mustContainPrefix(product, "top/wcpe/mc/mpmt/libs/org/yaml/snakeyaml/", "产品 snakeyaml 未 relocate")
    mustMetadataContains(
        product,
        "plugin.yml",
        "main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin",
        "产品 metadata 入口错误",
    )
    if (rejectAcceptanceInProductYml) {
        must(!product.text("plugin.yml").contains("MpmtBukkitAcceptancePlugin"), "产品 metadata 混入验收入口")
    }
    if (apiVersion != null) {
        mustMetadataContains(product, "plugin.yml", "folia-supported: true", "现代产品缺少 folia 字段")
        mustMetadataContains(product, "plugin.yml", "api-version: '$apiVersion'", "产品 api-version 错误")
    }
    mustMetadataContains(acceptanceFile, "plugin.yml", "MpmtBukkitAcceptancePlugin", "验收 metadata 入口错误")
    if (rejectModernYmlFields) {
        must(product.entries.none { it.endsWith("FoliaSchedulerPort.class") }, "1.12 产品混入 Folia 类")
        must(!product.text("plugin.yml").contains("folia-supported:"), "1.12 产品不得含 folia 字段")
        must(!product.text("plugin.yml").contains("api-version:"), "1.12 产品不得含 api-version")
    }
    mustContain(
        product,
        "META-INF/services/top.wcpe.mc.mpmt.platform.spi.PlatformBootstrap",
        "缺少 PlatformBootstrap services",
    )
    log("Bukkit $mcVersion 打包校验通过：产品=${product.file.name}，验收=${acceptanceFile.file.name}")
}
