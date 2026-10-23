package buildconventions

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * 功能域模块的 Gradle 路径前缀（ADR-0028 D5）。
 *
 * 域模块位于 `core/domains/<域>/<域>-<后缀>`，Gradle 路径形如
 * `:core:domains:<域>:<域>-<后缀>`（ADR-0030 的三段布局）。
 */
private const val DOMAIN_PATH_PREFIX = ":core:domains:"

/**
 * 返回全部已纳入根构建的功能域模块依赖。
 *
 * 用途：平台车道用 `implementation(mpmtDomains())` 一次性消费所有域，使
 * 「新增功能域 = 新建 `core/domains/<域>` 模块 + services 声明」而**无需改动任何车道脚本**
 * （ADR-0028 D6）。域是 L0 代码，车道消费它是 L3/L4 → L0 的合法方向。
 *
 * 实现说明：在配置期枚举根构建子项目，筛选路径前缀。域数量是个位数，遍历开销可忽略；
 * 相比「在 `core/domains` 建一个聚合模块 api 依赖各域」，本方案不需要新增域时改聚合清单。
 *
 * 关键约束：ADR-0030 引入 `<域>/<域>-<后缀>` 嵌套后，`:core:domains:<域>` 本身会变成
 * **没有构建脚本、也没有变体的隐式父项目**。若把它也返回给车道，依赖解析会报
 * "No variants exist"。故这里只取真正带构建脚本的叶子模块。
 */
fun Project.mpmtDomains(): List<ProjectDependency> =
    rootProject.subprojects
        .filter { it.path.startsWith(DOMAIN_PATH_PREFIX) }
        .filter { it.buildFile.isFile }
        .sortedBy { it.path }
        .map { dependencies.project(it.path) }
