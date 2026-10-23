package buildconventions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 架构守卫的行为测试（ADR-0028 决策 7）。
 *
 * 守卫的判定错一格就会误报或漏报：误报会让开发者习惯性绕过，漏报等于没有守卫。
 * 这些用例覆盖三档、脱敏、形态、顶层语义与孤儿检查。
 */
class ArchitectureGuardTest {
    @Test
    fun `STRICT 档检出 MC 与加载器引用`(
        @TempDir temp: File,
    ) {
        val core = File(temp, "core/foo/src/main/java").apply { mkdirs() }
        File(core, "Bad.java").writeText(
            """
            package foo;
            import net.minecraft.server.MinecraftServer;
            import net.fabricmc.api.ModInitializer;
            class Bad { }
            """.trimIndent(),
        )

        val violations = ArchitectureGuard.scanSources(temp, "core", ArchitectureTier.STRICT)

        assertTrue(violations.isNotEmpty(), "core 下引用 MC / 加载器应被检出")
        assertTrue(violations.all { it.kind == ArchitectureViolation.Kind.PLATFORM_REFERENCE })
    }

    @Test
    fun `MC_AWARE 档允许 MC 但禁止加载器`(
        @TempDir temp: File,
    ) {
        val dir = File(temp, "mc-shared/common/src/main/java").apply { mkdirs() }
        File(dir, "Ok.java").writeText(
            """
            package x;
            import net.minecraft.world.entity.Entity;
            class Ok { Entity e; }
            """.trimIndent(),
        )
        File(dir, "Bad.java").writeText(
            """
            package x;
            import net.fabricmc.api.ModInitializer;
            class Bad { }
            """.trimIndent(),
        )

        val violations = ArchitectureGuard.scanSources(temp, "mc-shared", ArchitectureTier.MC_AWARE)

        assertEquals(1, violations.size, "只应检出加载器引用")
        assertTrue(violations.single().detail.contains("net.fabricmc."))
    }

    @Test
    fun `注释与字符串里的平台前缀不算违规`(
        @TempDir temp: File,
    ) {
        val core = File(temp, "core/foo/src/main/java").apply { mkdirs() }
        File(core, "Doc.java").writeText(
            """
            package foo;
            /**
             * 本模块禁止 import net.fabricmc.api.ModInitializer，也不得引用 net.minecraft.world.World。
             */
            class Doc {
                // 反面示例：import net.neoforged.bus.api.IEventBus;
                String note = "禁止 net.fabricmc.api.ModInitializer";
            }
            """.trimIndent(),
        )

        val violations = ArchitectureGuard.scanSources(temp, "core", ArchitectureTier.STRICT)

        assertTrue(violations.isEmpty(), "注释与字符串必须被脱敏，不得误报：${violations.map { it.describe() }}")
    }

    @Test
    fun `mc-shared 出现构建脚本即违规`(
        @TempDir temp: File,
    ) {
        val shared = File(temp, "mc-shared/common").apply { mkdirs() }
        File(shared, "build.gradle.kts").writeText("// 不该存在")

        val violations = ArchitectureGuard.checkMcSharedShape(temp)

        assertEquals(1, violations.size)
        assertEquals(ArchitectureViolation.Kind.MC_SHARED_SHAPE, violations.single().kind)
    }

    @Test
    fun `mc-shared 为空时不报形态违规`(
        @TempDir temp: File,
    ) {
        assertTrue(ArchitectureGuard.checkMcSharedShape(temp).isEmpty())
    }

    @Test
    fun `顶层语义校验拒绝白名单外的前缀`(
        @TempDir temp: File,
    ) {
        File(temp, "settings.gradle.kts").writeText(
            """
            include("core:kernel")
            include("platform:bukkit")
            include("illegal:thing")
            """.trimIndent(),
        )

        val violations = ArchitectureGuard.checkTopLevelSemantics(temp)

        assertEquals(1, violations.size)
        assertTrue(violations.single().detail.contains("illegal"))
    }

    @Test
    fun `白名单内的顶层前缀全部通过`(
        @TempDir temp: File,
    ) {
        File(temp, "settings.gradle.kts").writeText(
            ArchitectureGuard.ALLOWED_TOP_LEVEL.joinToString("\n") { "include(\"$it:x\")" },
        )

        assertTrue(ArchitectureGuard.checkTopLevelSemantics(temp).isEmpty())
    }

    @Test
    fun `mc-shared 被 include 成模块必须报违规`(
        @TempDir temp: File,
    ) {
        // mc-shared 是纯源码树：一旦进 settings 变成 Gradle 模块，跨版本编译能力立刻失效
        File(temp, "settings.gradle.kts").writeText("include(\"mc-shared:common\")")

        val violations = ArchitectureGuard.checkTopLevelSemantics(temp)

        assertEquals(1, violations.size)
        assertTrue(violations.single().detail.contains("mc-shared"))
    }

    @Test
    fun `带前导冒号的绝对路径写法不算违规`(
        @TempDir temp: File,
    ) {
        // Gradle 允许 include(":e2e:harness")；前导冒号不代表顶层目录为空
        File(temp, "settings.gradle.kts").writeText("include(\":e2e:harness\")")

        val violations = ArchitectureGuard.checkTopLevelSemantics(temp)

        assertTrue(violations.isEmpty(), "前导冒号不应被误判：${violations.map { it.describe() }}")
    }

    @Test
    fun `孤儿检查检出空壳端目录与未命中区间`(
        @TempDir temp: File,
    ) {
        // 空壳端目录：存在但无源码
        File(temp, "mc-shared/client").mkdirs()
        // 有源码的端目录：不算孤儿
        val common = File(temp, "mc-shared/common/src/main/java").apply { mkdirs() }
        File(common, "A.java").writeText("package x; class A { }")
        // 未命中任何活跃车道的区间目录
        File(temp, "mc-shared/gensrc/1.19").mkdirs()

        val violations = ArchitectureGuard.checkOrphans(temp, listOf("1.20.1", "1.21.1"))

        val detail = violations.map { it.describe() }
        assertTrue(detail.any { it.contains("mc-shared/client") }, "空壳 client 目录应被检出：$detail")
        assertTrue(detail.any { it.contains("1.19") }, "未命中区间应被检出：$detail")
        assertFalse(detail.any { it.contains("mc-shared/common") }, "有源码的 common 不应报孤儿：$detail")
    }

    @Test
    fun `孤儿检查放过命中活跃版本的区间目录`(
        @TempDir temp: File,
    ) {
        File(temp, "mc-shared/gensrc/1.20-1.21").mkdirs()

        val violations = ArchitectureGuard.checkOrphans(temp, listOf("1.20.1", "1.21.1"))

        assertTrue(violations.none { it.where.contains("1.20-1.21") })
    }

    @Test
    fun `实现模块横向依赖对端实现即违规`() {
        val deps =
            mapOf(
                ":core:domains:x:client" to setOf(":core:domains:x:client-internal", ":core:domains:x:server"),
            )

        val violations = ArchitectureGuard.checkDomainModuleDirections(deps)

        assertEquals(1, violations.size, "client 依赖 server 实现应被检出：${violations.map { it.describe() }}")
        assertEquals(ArchitectureViolation.Kind.DEPENDENCY_DIRECTION, violations.single().kind)
    }

    @Test
    fun `域之间互相依赖即违规`() {
        // 护栏「域之间零依赖」：跨域协作只能经 EventBus，直接依赖另一个域的模块必须报错
        val deps =
            mapOf(
                ":core:domains:x:client" to setOf(":core:domains:y:common-api"),
            )

        val violations = ArchitectureGuard.checkDomainModuleDirections(deps)

        assertEquals(1, violations.size, "跨域依赖应被检出：${violations.map { it.describe() }}")
        assertEquals(ArchitectureViolation.Kind.DEPENDENCY_DIRECTION, violations.single().kind)
        assertTrue(violations.single().detail.contains("EventBus"))
    }

    @Test
    fun `实现模块依赖本端抽象与共用逻辑不算违规`() {
        val deps =
            mapOf(
                ":core:domains:x:client" to
                    setOf(":core:domains:x:client-internal", ":core:domains:x:core", ":core:domains:x:common-api"),
                ":core:domains:x:server" to
                    setOf(":core:domains:x:server-internal", ":core:domains:x:core", ":core:domains:x:common-api"),
            )

        val violations = ArchitectureGuard.checkDomainModuleDirections(deps)

        assertTrue(violations.isEmpty(), "依赖抽象与共用逻辑不应被拦：${violations.map { it.describe() }}")
    }

    @Test
    fun `抽象模块之间互相依赖不受方向限制`() {
        val deps =
            mapOf(
                ":core:domains:x:client-api" to setOf(":core:domains:x:common-api"),
                ":core:domains:x:client-internal" to setOf(":core:domains:x:server-internal"),
            )

        val violations = ArchitectureGuard.checkDomainModuleDirections(deps)

        assertTrue(violations.isEmpty(), "api / internal 属抽象层，不受实现横向依赖限制：${violations.map { it.describe() }}")
    }

    @Test
    fun `域外同名模块不被误判为端实现`() {
        // :core:client / :core:server 是分层核心模块，不是域的端实现，不应参与方向判定
        val deps =
            mapOf(
                ":core:domains:x:server" to setOf(":core:client", ":core:server"),
            )

        val violations = ArchitectureGuard.checkDomainModuleDirections(deps)

        assertTrue(violations.isEmpty(), "域外依赖不应被误判：${violations.map { it.describe() }}")
    }
}
