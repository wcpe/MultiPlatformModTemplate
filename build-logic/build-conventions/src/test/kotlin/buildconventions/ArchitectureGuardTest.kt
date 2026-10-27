package buildconventions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 架构守卫「依赖方向」判定的行为测试（ADR-0028 决策 7 / ADR-0030 决策 6）。
 *
 * 这些用例存在的原因：该判定无法由 ArchUnit 表达（同包不同模块切不开），也无法由依赖声明
 * 本身拦住（写成 `implementation(project(...))` 一样编译通过）。它是这两条红线的唯一机检，
 * 判定错一格就会误报或漏报，而漏报等于红线消失。
 */
class ArchitectureGuardTest {
    @Test
    fun `跨域依赖被检出`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":core:domains:alpha:alpha-core" to setOf(":core:domains:beta:beta-core")),
            )

        assertEquals(1, violations.size, "跨域依赖应被检出 1 条")
        assertEquals(ArchitectureViolation.Kind.DEPENDENCY_DIRECTION, violations[0].kind)
        assertTrue(violations[0].detail.contains("域之间不得互相依赖"), violations[0].detail)
    }

    @Test
    fun `examples 域也被纳入跨域检查`() {
        // 原实现只收 :core:domains: 前缀，examples 作为范本完全不受约束（ADR-0036 背景三）。
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":examples:counter:counter-core" to setOf(":examples:other:other-core")),
            )

        assertEquals(1, violations.size, "examples 域的跨域依赖也应被检出")
    }

    @Test
    fun `client 依赖 server 被检出`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":core:domains:capability:client" to setOf(":core:domains:capability:server")),
            )

        assertEquals(1, violations.size, "实现模块横向依赖对端实现应被检出")
        assertTrue(violations[0].detail.contains("不得依赖对端实现"), violations[0].detail)
    }

    @Test
    fun `server 依赖 client 同样被检出`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":core:domains:capability:server" to setOf(":core:domains:capability:client")),
            )

        assertEquals(1, violations.size, "反方向也应被检出")
    }

    @Test
    fun `examples 的 client 依赖 server 被检出`() {
        // 这两端处在同一个包 top.wcpe.mc.mpmt.examples.counter，ArchUnit 的 slices() 切不开，
        // 只能靠模块级依赖判定——本用例正是该缺口的存在证明。
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":examples:counter:client" to setOf(":examples:counter:server")),
            )

        assertEquals(1, violations.size, "examples 的 client 依赖 server 应被检出")
    }

    @Test
    fun `同域内依赖抽象模块放行`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(
                    ":core:domains:capability:server" to
                        setOf(
                            ":core:domains:capability:common-api",
                            ":core:domains:capability:server-api",
                            ":core:domains:capability:server-internal",
                            ":core:domains:capability:core",
                        ),
                ),
            )

        assertTrue(violations.isEmpty(), "同域内依赖抽象模块不应报违规：$violations")
    }

    @Test
    fun `域外依赖不受本规则管辖`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":core:domains:capability:core" to setOf(":core:kernel", ":core:spi")),
            )

        assertTrue(violations.isEmpty(), "域外依赖（:core:kernel 等）不应报违规：$violations")
    }

    @Test
    fun `抽象模块之间的依赖不受方向限制`() {
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(":core:domains:capability:client-internal" to setOf(":core:domains:capability:client-api")),
            )

        assertTrue(violations.isEmpty(), "抽象模块不受方向限制：$violations")
    }

    @Test
    fun `真实域的现状全部通过`() {
        // 与生产接线同源的形态：域内只依赖本端抽象 + 域外内核。
        val violations =
            ArchitectureGuard.checkDomainModuleDirections(
                mapOf(
                    ":core:domains:capability:common-api" to setOf(":core:kernel"),
                    ":core:domains:capability:server-api" to setOf(":core:domains:capability:common-api"),
                    ":core:domains:capability:server-internal" to setOf(":core:domains:capability:server-api"),
                    ":core:domains:capability:core" to setOf(":core:domains:capability:common-api"),
                    ":core:domains:capability:server" to
                        setOf(
                            ":core:domains:capability:server-internal",
                            ":core:domains:capability:core",
                        ),
                    ":examples:counter:common-api" to setOf(":core:kernel"),
                    ":examples:counter:client-api" to setOf(":examples:counter:common-api"),
                    ":examples:counter:server-api" to setOf(":examples:counter:common-api"),
                ),
            )

        assertTrue(violations.isEmpty(), "本仓真实域结构不应报违规：$violations")
    }

    @Test
    fun `空输入不报违规但调用方须自行防空转`() {
        // 判定层对空输入返回空列表是正确的；「空输入即失败」由插件侧负责，
        // 这样判定保持纯函数、可被单元测试直接驱动。
        assertTrue(ArchitectureGuard.checkDomainModuleDirections(emptyMap()).isEmpty())
    }
}
