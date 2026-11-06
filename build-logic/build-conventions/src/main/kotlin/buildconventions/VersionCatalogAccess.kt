package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * 从本插件工程自己的版本目录（`gradle/libs.versions.toml`）取一个 `[versions]` 值。
 *
 * <p>为什么需要它：本插件工程是**独立 included build**（父仓 settings 里的 includeBuild），
 * 它的 `libs` 访问器只在本工程的构建脚本里可用；而依赖接线写在 Kotlin 源码里，那里没有 `libs`。
 * 而“版本写死在 Kotlin 常量里”正是本次要消除的问题（原先 junit 在这里写 5.10.3、
 * 其余车道用 5.14.4）。所以从 catalog 读，保持与其它车道同一个真源。
 *
 * <p>本工程 catalog 缺项直接失败，不静默回落——静默回落会让版本分叉再次隐形。
 */
internal fun Project.versionsCatalogVersion(key: String): String =
    extensions
        .getByType(VersionCatalogsExtension::class.java)
        .named("libs")
        .findVersion(key)
        .orElseThrow {
            GradleException("gradle/libs.versions.toml 的 [versions] 缺少 `$key`：依赖版本必须单一真源")
        }
        .requiredVersion
