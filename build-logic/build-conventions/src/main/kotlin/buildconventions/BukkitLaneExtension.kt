package buildconventions

import org.gradle.api.provider.Property

/**
 * bukkit 车道配置：车道脚本只声明"本车道是什么"，重复的 loader 接入流程由 `build-conventions.bukkit` 承担。
 *
 * 参数口径与原各车道内联实现逐字对应（`plugin.yml` 展开键、`mpmt.test.*` 属性名、relocate 目标、
 * 验收 jar 名等对外契约均不变）；默认值取自四条车道的公共约定，车道只在偏离约定时才写对应属性。
 */
abstract class BukkitLaneExtension {
    /** MC 版本：产品/验收产物名、`mpmt.test.minecraftVersion` 与 realserver `paperVersion` 都用它。 */
    abstract val mcVersion: Property<String>

    /** 编译期 API 坐标（`compileOnly` / `testImplementation` / 验收 `compileOnly` 三处同源）。 */
    abstract val apiCoordinate: Property<String>

    /** 车道目标 Java 版本：`options.release`、测试 launcher 与 `mpmt.test.javaVersion` 的默认取值。 */
    abstract val targetJavaVersion: Property<Int>

    /** 编译器工具链版本（默认同 [targetJavaVersion]）：26.2 须用 25 才能解析 paper-api（class major 69）。 */
    abstract val compilerJavaVersion: Property<Int>

    /** 编译任务是否用 `options.release`；Java 8 工具链无 `--release`，改由 source/targetCompatibility 表达。 */
    abstract val releaseTargetVersion: Property<Boolean>

    /** 产品入口是否合并 `:platform:bukkit:modern`（1.12.2 车道无现代适配器）。 */
    abstract val modernProduct: Property<Boolean>

    /** 是否创建 `acceptanceTest` 源集与 `acceptanceContractTest` 任务（仅 1.20.1 车道有）。 */
    abstract val acceptanceTestSourceSet: Property<Boolean>

    /** `plugin.yml` 的 `api-version` 行取值；空串表示不注入该行（1.12.2）。 */
    abstract val apiVersion: Property<String>

    /** `plugin.yml` 是否注入 `folia-supported: true`（1.12.2 不注入）。 */
    abstract val foliaSupported: Property<Boolean>

    /**
     * `bungeecord-chat` 本地补丁 jar 的仓库相对路径；非空表示 API 依赖需排除 `net.md-5:bungeecord-chat`
     * 并由该 jar 顶替（1.12.2 的 spigot-api 依赖已废弃的 bungeecord-chat）。
     */
    abstract val bungeeChatFallbackJar: Property<String>

    /** `mpmt.test.productChannel`。 */
    abstract val productChannel: Property<String>

    /** `mpmt.test.acceptanceChannel`。 */
    abstract val acceptanceChannel: Property<String>

    /** `mpmt.test.regionSchedulerClass`。 */
    abstract val regionSchedulerClass: Property<String>

    /** `mpmt.test.javaVersion` 的取值（Java 8 车道用 `1.8`）；默认取 [targetJavaVersion] 的字面值。 */
    abstract val testJavaVersion: Property<String>

    /** 是否注册托管 Paper 宿主自检任务 `ensurePaperRealServerHost`（1.20.1 车道）。 */
    abstract val managedPaperHost: Property<Boolean>
}
