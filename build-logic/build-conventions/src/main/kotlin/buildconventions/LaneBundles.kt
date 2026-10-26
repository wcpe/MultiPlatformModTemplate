package buildconventions

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration

/**
 * 车道打包配置的**集中声明口**。
 *
 * 背景：13 条平台车道都要声明「哪些依赖需要 shade 进产物」，历史上在各车道脚本里
 * 逐个写 `configurations.create("shadowBundle")`，同一件事重复 15 次，且命名在
 * 车道间漂移（forge 用 product/acceptanceBundle，其余用 shadowBundle）。
 *
 * 这里把「配置名 → 用途」收敛成枚举：车道只说需要哪几类打包配置，创建与命名由本处负责。
 * **配置名逐字保持历史值**，因为它们已被车道脚本与 build-conventions 的接线代码大量引用
 * （`shadowBundle` 41 处、`productBundle` 8 处等），改名属于不可变契约变更。
 */
enum class LaneBundle(
    /** Gradle 配置名，与历史写法逐字一致，供 `configurations.getByName` 等按名引用。 */
    val configurationName: String,
    /** 用途说明，写进配置描述，便于 `./gradlew dependencies` 自解释。 */
    val purpose: String,
) {
    /** 产品产物需要 shade 的依赖集合。 */
    SHADOW("shadowBundle", "产品产物 shade 依赖集合"),

    /** forge 系车道的产品打包配置（与 SHADOW 同义，历史命名差异）。 */
    PRODUCT("productBundle", "产品产物 shade 依赖集合（forge 系命名）"),

    /** 验收驱动产物需要 shade 的依赖集合。 */
    ACCEPTANCE("acceptanceBundle", "验收驱动产物 shade 依赖集合"),

    /** 验收驱动产物的 shadow 依赖集合（部分车道与 ACCEPTANCE 并用）。 */
    ACCEPTANCE_SHADOW("acceptanceShadowBundle", "验收驱动产物 shade 依赖集合（shadow 命名）"),
}

/**
 * 按需声明车道打包配置，返回可直接用于 `implementation(...)` 的 [Configuration]。
 *
 * 幂等：同一配置名重复声明不会抛错（`maybeCreate` 语义），便于车道脚本按需组合。
 *
 * 用法：
 * ```kotlin
 * val bundles = declareLaneBundles(LaneBundle.SHADOW)
 * dependencies {
 *     bundles[LaneBundle.SHADOW](project(":core:kernel"))
 * }
 * ```
 */
fun org.gradle.api.Project.declareLaneBundles(vararg kinds: LaneBundle): Map<LaneBundle, Configuration> =
    kinds.associateWith { kind ->
        // 只补 description（原写法没有，不影响解析语义）；canBeConsumed/canBeResolved 保持
        // Gradle 默认值 true，与历史 `configurations.create(name)` 完全一致。
        configurations.maybeCreate(kind.configurationName).apply {
            description = kind.purpose
        }
    }
