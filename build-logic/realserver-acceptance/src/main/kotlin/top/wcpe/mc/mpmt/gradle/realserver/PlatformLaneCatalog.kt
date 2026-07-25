package top.wcpe.mc.mpmt.gradle.realserver

/**
 * 全服务端 lane 目录：给根任务图与文档生成用。
 *
 * <p>B 完整要求：五宿主全覆盖；mod 客户端只走自有 gametest 伴侣进服。
 */
object PlatformLaneCatalog {

    fun all(): List<PlatformLane> = PlatformLane.allLanes()

    fun modDedicated(): List<PlatformLane> =
        all().filter { it.serverKind == PlatformLane.ServerKind.MOD_DEDICATED }

    fun pluginHosts(): List<PlatformLane> =
        all().filter { it.serverKind == PlatformLane.ServerKind.PLUGIN_HOST }

    fun rootTaskNames(): List<String> = all().map { it.rootTaskName() }

    fun summaryLines(): List<String> =
        all().map { lane ->
            "${lane.id}: server=${lane.serverKind} client=${lane.clientKind} " +
                "verify=${lane.verifyTaskName} report~=${lane.defaultReportHint}"
        }
}
