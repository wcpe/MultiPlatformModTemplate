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
                "Forge",
                "NeoForge",
                "Bukkit",
                "Folia",
                "CatServer",
                "Sponge",
            ),
            ids,
        )
    }

    @Test
    fun `includeBuild 与根路径对齐新版本工程`() {
        assertEquals("platform-fabric-1.20.1", PlatformLane.FABRIC.includedBuildName)
        assertEquals("platform-fabric-1.21.1", PlatformLane.FABRIC_121.includedBuildName)
        assertEquals("platform-forge-1.20.1", PlatformLane.FORGE.includedBuildName)
        assertEquals(
            ":platform:bukkit:1.20.1:runRealServerAcceptance",
            PlatformLane.BUKKIT.rootProjectTaskPath,
        )
        assertEquals(
            ":platform:bukkit:1.12.2:runRealServerAcceptance",
            PlatformLane.CATSERVER.rootProjectTaskPath,
        )
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
    fun `R5 R6 默认矩阵绑定 CatServer 与 Folia`() {
        assertEquals("R5", PlatformLane.CATSERVER.defaultMatrix)
        assertEquals("R6", PlatformLane.FOLIA.defaultMatrix)
    }
}
