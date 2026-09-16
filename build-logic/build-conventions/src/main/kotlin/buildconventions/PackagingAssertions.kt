package buildconventions

import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

/** 一个 jar 的只读视图：条目名集合与条目文本，避免各车道各写一遍 zip 读取。 */
class JarView(val file: File) {
    val entries: Set<String> = ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

    fun text(name: String): String =
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(name) ?: throw GradleException("${file.name} 缺少 $name")
            zip.getInputStream(entry).use { input -> String(input.readBytes(), StandardCharsets.UTF_8) }
        }
}

/**
 * 打包断言：失败文案统一带车道前缀（`<前缀> 打包校验失败：<原因>`）。
 *
 * 动词按意图命名（存在 / 唯一 / 隔离 / relocate / 服务描述符 / 元数据），
 * 车道只声明断言清单，zip 读取与判定细节集中在插件里。
 */
class PackagingAssertions(
    private val laneLabel: String,
    private val logger: Logger,
) {
    fun must(condition: Boolean, message: String) {
        if (!condition) throw GradleException("$laneLabel 打包校验失败：$message")
    }

    /** 条目必须存在。 */
    fun mustContain(jar: JarView, entry: String, message: String) = must(jar.entries.contains(entry), message)

    /** 条目必须不存在。 */
    fun mustNotContain(jar: JarView, entry: String, message: String) = must(!jar.entries.contains(entry), message)

    /** 以给定前缀开头的条目必须存在（用于 shade / relocate 校验）。 */
    fun mustContainPrefix(jar: JarView, prefix: String, message: String) =
        must(jar.entries.any { it.startsWith(prefix) }, message)

    /** 以给定前缀开头的条目数必须恰好等于 expected（唯一性断言）。 */
    fun mustContainPrefixExactly(jar: JarView, prefix: String, expected: Int, message: String) =
        must(jar.entries.count { it.startsWith(prefix) } == expected, message)

    /** 给定前缀一律不允许出现（隔离断言，如"未误打包平台 API"）。 */
    fun mustNotBundle(jar: JarView, prefixes: List<String>, message: String) =
        must(prefixes.none { prefix -> jar.entries.any { it.startsWith(prefix) } }, message)

    /** 服务描述符内容必须与期望实现类一致（忽略首尾空白）。 */
    fun mustServiceEquals(jar: JarView, serviceEntry: String, implementation: String, message: String) =
        must(jar.text(serviceEntry).trim() == implementation, message)

    /** 元数据条目（plugin.yml / mods.toml / fabric.mod.json 等）必须包含给定片段。 */
    fun mustMetadataContains(jar: JarView, metadataEntry: String, fragment: String, message: String) =
        must(jar.text(metadataEntry).contains(fragment), message)

    /** 既有通过日志（文案由车道逐字提供，保持输出不变）。 */
    fun log(message: String) = logger.lifecycle(message)
}
