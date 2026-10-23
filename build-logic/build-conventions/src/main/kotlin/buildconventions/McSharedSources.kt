package buildconventions

import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import java.io.File

/**
 * `mc-shared/` 跨 lane 源码树的挂载器（ADR-0028 决策 1/2/3，ADR-0030 决策 2）。
 *
 * `mc-shared/` 是**源码树不是 Gradle 模块**：没有 `build.gradle.kts`、不进 `settings.gradle.kts`，
 * 由各车道用自己的 MC API 各编一次。本对象只负责把它挂进车道的源集。
 *
 * 两条硬规则：
 * 1. **纯加法**：只用 `srcDir(...)` 追加，绝不 `setSrcDirs` 覆盖、也绝不用 exclude 做减法。
 *    减法漏了会静默编错，加法漏了会立刻编译失败——后者是可发现的失败。
 * 2. **按命名组挂载**：同一份源码只允许被**命名组相同**的车道命中。命名组按 MC 映射口径划分
 *    （mcp ≤1.12.2 / mojmap 1.20.1~1.21.1 / unobfuscated 26.2），跨组共享会因成员名不同而编译失败。
 *
 * 目录约定：
 * ```
 * mc-shared/
 * ├── common/   client 与 server 都需要的 MC 实现
 * ├── client/   仅客户端需要
 * └── server/   仅服务端需要
 * ```
 */
object McSharedSources {

    /** 源码树根目录名（相对根工程）。 */
    const val ROOT_DIR = "mc-shared"

    /** 端目录名。 */
    enum class Side(val dir: String) {
        COMMON("common"),
        CLIENT("client"),
        SERVER("server"),
    }

    /**
     * 把 `mc-shared/<side>/src/main/java` 与命中的 `mc-shared/gensrc/<range>/<side>/src/main/java`
     * 追加到给定源目录集合。
     *
     * 只有目录真实存在时才追加：空目录不建、也不挂——避免"挂了但没有内容"的隐性空壳
     * （ADR-0015 / ADR-0030 决策 3）。
     *
     * @param project 车道工程
     * @param java    车道的 main 源集 java 源目录集合
     * @param sides   该车道需要挂载的端；双端车道传 COMMON+CLIENT+SERVER，纯服务端传 COMMON+SERVER
     * @param mcVersion 车道实际 MC 版本，用于匹配 `gensrc/<range>` 区间目录；为空则只挂根目录
     * @return 实际挂载的目录数量
     */
    fun mount(project: Project, java: SourceDirectorySet, sides: List<Side>, mcVersion: String? = null): Int {
        var mounted = 0
        sides.forEach { side ->
            val dir = project.rootProject.file("$ROOT_DIR/${side.dir}/src/main/java")
            if (dir.isDirectory) {
                java.srcDir(dir)
                mounted++
            }
        }
        if (mcVersion != null) {
            mounted += mountVersionRanges(project, java, sides, mcVersion)
        }
        return mounted
    }

    /**
     * 挂载命中版本区间的 `gensrc/<range>` 目录（ADR-0028 决策 3：直挂真源、不做文本替换）。
     *
     * @param project 车道工程
     * @param java    目标源目录集合
     * @param sides   端
     * @param mcVersion 车道 MC 版本
     * @return 实际挂载的目录数量
     */
    private fun mountVersionRanges(
        project: Project,
        java: SourceDirectorySet,
        sides: List<Side>,
        mcVersion: String,
    ): Int {
        val gensrc = project.rootProject.file("$ROOT_DIR/gensrc")
        if (!gensrc.isDirectory) {
            return 0
        }
        var mounted = 0
        gensrc.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .filter { McSharedRanges.matches(it.name, mcVersion) }
            .sortedBy { it.name }
            .forEach { rangeDir ->
                sides.forEach { side ->
                    // 必须相对根工程解析：mc-shared 在仓库根，不在车道目录下。
                    val dir = project.rootProject.file("$ROOT_DIR/gensrc/${rangeDir.name}/${side.dir}/src/main/java")
                    if (dir.isDirectory) {
                        java.srcDir(dir)
                        mounted++
                    }
                }
            }
        return mounted
    }

    /**
     * 判断某个 `mc-shared` 端目录是否已有源码，用于构建期自检与文档口径。
     *
     * @param project 车道工程
     * @param side    端
     * @return 该端目录下是否存在至少一个文件
     */
    fun hasSources(project: Project, side: Side): Boolean {
        val dir: File = project.rootProject.file("$ROOT_DIR/${side.dir}/src/main/java")
        return dir.isDirectory && dir.walkTopDown().any { it.isFile }
    }
}
