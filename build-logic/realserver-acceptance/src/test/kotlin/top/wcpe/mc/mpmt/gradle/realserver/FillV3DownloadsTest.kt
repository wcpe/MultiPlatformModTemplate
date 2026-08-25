package top.wcpe.mc.mpmt.gradle.realserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FillV3DownloadsTest {
    @Test
    fun `解析 builds latest 的 id`() {
        val json = """{"id": 123, "downloads": {}}"""
        assertEquals(123, FillV3Downloads.parseLatestBuildId(json))
    }

    @Test
    fun `解析 server default 下载 url`() {
        val json =
            """
            {
              "id": 1,
              "downloads": {
                "server:default": {
                  "name": "paper.jar",
                  "checksums": {"sha256": "abc"},
                  "size": 1,
                  "url": "https://fill-data.example/paper.jar"
                }
              }
            }
            """.trimIndent()
        assertEquals(
            "https://fill-data.example/paper.jar",
            FillV3Downloads.parseDownloadUrl(json, FillV3Downloads.KEY_PRIMARY),
        )
    }

    @Test
    fun `缺 id 抛错`() {
        assertThrows(IllegalStateException::class.java) {
            FillV3Downloads.parseLatestBuildId("{}")
        }
    }
}
