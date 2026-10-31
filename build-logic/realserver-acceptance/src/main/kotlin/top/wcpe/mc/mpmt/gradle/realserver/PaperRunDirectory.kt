package top.wcpe.mc.mpmt.gradle.realserver

import java.io.File
import java.util.UUID

/** 离线玩家的固定用户名：报告与 ops 白名单都按它对齐。 */
private const val OFFLINE_PLAYER_NAME = "WCPE"

/** 验收服玩家上限与 OP 等级。 */
private const val MAX_PLAYERS = 20
private const val OPERATOR_LEVEL = 4

/**
 * Paper 运行目录的一次性铺装。
 *
 * <p>`eula`、`server.properties`、`ops.json` 是宿主起服的前置事实，内容固定；
 * 抽出来只为让 [PaperHostService.startPaper] 读起来是「铺装 → 部署插件 → 启动」三步。
 */
internal object PaperRunDirectory {
    fun prepare(runDir: File, port: Int) {
        runDir.mkdirs()
        File(runDir, "eula.txt").writeText("eula=true\n")
        File(runDir, "server.properties").writeText(serverProperties(port))
        File(runDir, "ops.json").writeText(operatorList())
    }

    private fun serverProperties(port: Int): String =
        """
        |server-port=$port
        |online-mode=false
        |max-players=$MAX_PLAYERS
        |spawn-protection=0
        |level-type=flat
        |generate-structures=false
        |spawn-monsters=true
        |spawn-animals=true
        |spawn-npcs=false
        |difficulty=normal
        |motd=MPMT realserver acceptance
        |enable-command-block=true
        |white-list=false
        |enforce-whitelist=false
        |
        """.trimMargin()

    private fun operatorList(): String {
        val offlineUuid =
            UUID.nameUUIDFromBytes("OfflinePlayer:$OFFLINE_PLAYER_NAME".toByteArray(Charsets.UTF_8))
        return """
            |[
            |  {
            |    "uuid": "$offlineUuid",
            |    "name": "$OFFLINE_PLAYER_NAME",
            |    "level": $OPERATOR_LEVEL,
            |    "bypassesPlayerLimit": true
            |  }
            |]
            |
            """.trimMargin()
    }
}
