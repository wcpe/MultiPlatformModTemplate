package top.wcpe.mc.mpmt.gradle.realserver

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * 真服验收门禁的契约测试。
 *
 * 断言口径（ADR-0029）：只允许落在**结构化数据**（`PlatformLane` 目录、枚举、矩阵标识）、
 * **运行期行为**或**构建产物**上。禁止读 `.java` / `.gradle.kts` / `.kt` 源码做文本包含判断：
 * 那种写法会把实现细节固化成契约，任何等价抽取都会让测试红，而真正的行为回归反而不一定暴露
 * （ADR-0027 背景已记录过该问题）。
 *
 * 原「读脚本文本断言任务名 / 依赖 / 文案」的一批用例已删除，它们保护的约束改由下列机制保证：
 * - 车道集合与任务路径 → [PlatformLane] / [PlatformLaneCatalog] + [PlatformLaneCatalogTest]
 * - 任务接线与执行顺序 → Gradle 任务图本身（缺任务、成环、顺序错会直接构建失败）
 * - 报告判定强度 → [RealServerReportValidatorTest] 对 v2 报告的逐字段校验
 * - 属性名与失败文案 → 真服验收运行：缺属性或判定不达标时门禁直接失败
 */
class RealServerGateContractTest {
    @Test
    fun `262 三车道各有独立根任务且不并入版本矩阵门`() {
        // 结构化事实：262 三条车道在目录里带 REALSERVER262 默认矩阵，版本矩阵门不含它们。
        val lanes262 =
            PlatformLaneCatalog.all().filter { it.defaultMatrix == MATRIX_262 }
        assertEquals(
            setOf("Fabric262", "Forge262", "Bukkit262"),
            lanes262.map { it.id }.toSet(),
        )
        lanes262.forEach { lane ->
            assertTrue(
                lane.rootTaskPath.endsWith(":${lane.verifyTaskName}"),
                "262 车道根任务路径异常：${lane.rootTaskPath}",
            )
        }
    }

    @Test
    fun `全部车道根任务名互不重复`() {
        // 根任务名重复会让 Gradle 直接注册失败；这里以结构化数据提前锁定。
        val names = PlatformLaneCatalog.all().map { it.rootTaskName() }
        assertEquals(names.size, names.toSet().size, "根任务名存在重复：$names")
    }

    @Test
    fun `mc testkit 指南使用当前 Bukkit 工程与产品产物路径`() {
        // 文档契约：指南里的命令行与产物路径必须与现实一致（README 不是源码，允许按文本核对）。
        val guide = readRootFile("e2e/README.md")
        assertTrue(guide.contains(":platform:bukkit:1.20.1:shadowJar"))
        assertTrue(guide.contains("platform/bukkit/1.20.1/build/libs/mpmt-bukkit-1.20.1-<version>.jar"))
        assertFalse(guide.contains(":platform-bukkit:server-1.20.1:shadowJar"))
        assertFalse(guide.contains("\${'$'}PWD/platform-bukkit/build/libs/"))
    }

    @Test
    fun `本机 Maven 兜底只放行 wcpe 的坐标组与 loom 的发布坐标`() {
        // 仓库内容过滤器只写不可读（Gradle 未开放回读 API），故允许清单提为 gradle.properties
        // 的结构化属性，由本用例守住；settings.gradle.kts 只按该属性逐项 includeGroup，
        // 清单为空时在配置期直接失败（ADR-0029）。
        val properties = Properties()
        Files.newInputStream(repositoryRoot().resolve("gradle.properties")).use(properties::load)
        val declared = properties.getProperty(LOCAL_MAVEN_GROUPS_PROPERTY)
        assertNotNull(declared, "缺少 $LOCAL_MAVEN_GROUPS_PROPERTY")
        val groups = declared.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            setOf(
                "top.wcpe.mc-testkit",
                "top.wcpe.mc",
                // wcpe-loom 的 5 个插件 marker：includeGroup 是精确匹配，逐个列出
                "top.wcpe.loom",
                "top.wcpe.loom-companion",
                "top.wcpe.loom-no-remap",
                "top.wcpe.loom-remap",
                "top.wcpe.loom-repositories",
                // loom 的实现本体（marker 依赖它，缺了会静默回落旧版本）
                "dev.architectury",
            ),
            groups,
            "本机 Maven 兜底作用域被放宽：$groups",
        )
        // 白名单口径（ADR-0032）：默认只允许 top.wcpe.* 命名空间；唯一的第三方例外是
        // dev.architectury——wcpe-loom 的插件 marker 在 top.wcpe.loom*，但其实现
        // （architectury-loom）发布在 dev.architectury 下，本机发布新版本后两者缺一不可。
        // 新增任何其它第三方组都必须同时改本断言与 ADR，不允许静默放宽。
        val allowedThirdParty = setOf("dev.architectury")
        groups.forEach { group ->
            assertTrue(
                group.startsWith("top.wcpe.") || group in allowedThirdParty,
                "允许的坐标组必须限于 top.wcpe.* 命名空间或已登记的第三方例外：$group",
            )
        }
    }

    @Test
    fun `严格报告门注册在任意工程上并强制本轮属性契约`() {
        // 行为断言：把插件应用到假工程，验证任务注册与 -Pmpmt.acceptance.* 属性契约，
        // 而不是读插件源码文本。属性名与失败判定强度在此被真正执行。
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(RealServerReportGatePlugin::class.java)
        val gate = project.tasks.findByName("verifyRealServerReportsStrict")
        assertNotNull(gate, "未注册 verifyRealServerReportsStrict")

        val failure =
            Assertions.assertThrows(
                GradleException::class.java,
            ) {
                gate!!.actions.forEach { it.execute(gate) }
            }
        assertTrue(
            failure.message.orEmpty().contains(RUN_ID_PROPERTY),
            "缺少本轮标识时未按属性契约失败：${failure.message}",
        )
    }

    @Test
    fun `真服验收扩展以结构化属性暴露宿主参数`() {
        // 扩展属性是结构化契约：下游按名读取，改名会在此处失败。
        val project = ProjectBuilder.builder().build()
        project.pluginManager.apply(MpmtRealServerAcceptancePlugin::class.java)
        val extension = project.extensions.getByType(MpmtRealServerAcceptanceExtension::class.java)
        assertNotNull(extension.paperJavaVersion)
        assertNotNull(extension.acceptanceRunId)
        assertNotNull(extension.acceptanceStartEpochMs)
        assertNotNull(extension.acceptanceClientProductJar)
        assertNotNull(extension.acceptanceClientAcceptanceJar)
    }

    private fun readRootFile(name: String): String =
        Files.readString(repositoryRoot().resolve(name), StandardCharsets.UTF_8)

    private fun repositoryRoot(): Path = Path.of(System.getProperty("user.dir")).parent.parent

    private companion object {
        const val MATRIX_262 = "REALSERVER262"
        const val RUN_ID_PROPERTY = "mpmt.acceptance.runId"
        const val LOCAL_MAVEN_GROUPS_PROPERTY = "mpmt.localMavenGroups"
    }
}
