package top.wcpe.mc.mpmt.gradle.realserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformLaneCatalogTest {
    @Test
    fun `覆盖全部服务端宿主含 Folia 与 CatServer`() {
        val ids = PlatformLaneCatalog.all().map { it.id }.toSet()
        assertEquals(
            setOf(
                "Fabric",
                "Fabric121",
                "Fabric262",
                "Forge",
                "Forge262",
                "NeoForge",
                "Bukkit",
                "Bukkit262",
                "Folia",
                "CatServer",
                "Sponge",
            ),
            ids,
        )
    }

    @Test
    fun `全部车道均为根子模块且任务路径与根 settings 对齐`() {
        // ADR-0026：不再有"复合构建名"与"根多模块路径"之分，每条车道只有唯一根任务路径
        assertEquals(
            ":platform:fabric:fabric-1.20.1:runRealServerAcceptance",
            PlatformLane.FABRIC.rootTaskPath,
        )
        assertEquals(
            ":platform:fabric:fabric-1.21.1:runRealServerAcceptance",
            PlatformLane.FABRIC_121.rootTaskPath,
        )
        assertEquals(
            ":platform:fabric:fabric-26.2:runRealServerAcceptance",
            PlatformLane.FABRIC_262.rootTaskPath,
        )
        assertEquals(
            ":platform:forge:forge-1.20.1:runRealServerAcceptance",
            PlatformLane.FORGE.rootTaskPath,
        )
        assertEquals(
            ":platform:forge:forge-26.2:runRealServerAcceptance",
            PlatformLane.FORGE_262.rootTaskPath,
        )
        assertEquals(
            ":platform:neoforge:neoforge-1.20.2:runRealServerAcceptance",
            PlatformLane.NEOFORGE.rootTaskPath,
        )
        assertEquals(
            ":platform:sponge:sponge-1.20.1:runRealServerAcceptance",
            PlatformLane.SPONGE.rootTaskPath,
        )
        assertEquals(
            ":platform:bukkit:1.20.1:runRealServerAcceptance",
            PlatformLane.BUKKIT.rootTaskPath,
        )
        assertEquals(
            ":platform:bukkit:1.12.2:runRealServerAcceptance",
            PlatformLane.CATSERVER.rootTaskPath,
        )
        assertEquals(
            ":platform:bukkit:26.2:runRealServerAcceptance",
            PlatformLane.BUKKIT_262.rootTaskPath,
        )
        // 全部路径都必须是根工程任务全路径（以 ':' 开头且含工程段）
        PlatformLaneCatalog.all().forEach { lane ->
            assertTrue(
                lane.rootTaskPath.startsWith(":platform:"),
                "lane ${lane.id} 的根任务路径须指向 platform 子模块，实际 ${lane.rootTaskPath}",
            )
        }
    }

    @Test
    fun `客户端均为自有 gametest 或 acceptance 伴侣`() {
        PlatformLaneCatalog.all().forEach { lane ->
            val kind = lane.clientKind.name
            assertTrue(
                kind.contains("GAMETEST") ||
                    kind.contains("ACCEPTANCE") ||
                    kind.contains("112"),
                "lane ${lane.id} 客户端须为自有伴侣，实际 ${lane.clientKind}",
            )
        }
    }

    @Test
    fun `根任务名无重复且前缀正确`() {
        val names = PlatformLaneCatalog.rootTaskNames()
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("runRealServerAcceptance") })
    }

    @Test
    fun `HYBRID SCHEDULER 默认矩阵绑定 CatServer 与 Folia`() {
        assertEquals("HYBRID", PlatformLane.CATSERVER.defaultMatrix)
        assertEquals("SCHEDULER", PlatformLane.FOLIA.defaultMatrix)
        assertEquals("REALSERVER262", PlatformLane.BUKKIT_262.defaultMatrix)
        assertEquals("REALSERVER262", PlatformLane.FABRIC_262.defaultMatrix)
        assertEquals("REALSERVER262", PlatformLane.FORGE_262.defaultMatrix)
    }
}
