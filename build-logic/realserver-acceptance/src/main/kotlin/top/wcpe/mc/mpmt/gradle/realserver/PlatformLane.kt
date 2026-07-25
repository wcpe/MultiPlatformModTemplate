package top.wcpe.mc.mpmt.gradle.realserver

/**
 * B 车道：全部服务端形态 + 对应进服客户端。
 *
 * <p>客户端一律由**对应 loader 的 gametest / acceptance 伴侣**进服（自写 GameTest 伴侣、
 * quickPlay 或 FG/Neo runClient），不用 shell；mineflayer 仅 A 辅车道（mc-testkit）。
 *
 * <p>includeBuild 名与仓库根 settings 对齐：Fabric/Forge 按 MC 版本拆独立构建；
 * Bukkit 为根多模块（`platform:bukkit:版本`），本枚举的 includedBuildName 为 null，
 * 根包装任务直接 dependsOn 对应 server 工程。
 */
enum class PlatformLane(
    val id: String,
    val displayName: String,
    val serverKind: ServerKind,
    val clientKind: ClientKind,
    /** composite includeBuild 名；null = 根多模块（Bukkit 家族） */
    val includedBuildName: String?,
    /** 平台/模块内门禁任务名 */
    val verifyTaskName: String,
    val defaultReportHint: String,
    /** 可选：默认矩阵（R5/R6）；空=P1 */
    val defaultMatrix: String = "",
    /**
     * 根工程内任务路径（仅 includedBuildName==null 时用）。
     * 例：`:platform:bukkit:1.20.1:runRealServerAcceptance`
     */
    val rootProjectTaskPath: String? = null,
) {
    FABRIC(
        id = "Fabric",
        displayName = "Fabric 1.20.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FABRIC_GAMETEST,
        includedBuildName = "platform-fabric-1.20.1",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
    ),
    FABRIC_121(
        id = "Fabric121",
        displayName = "Fabric 1.21.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FABRIC_GAMETEST,
        includedBuildName = "platform-fabric-1.21.1",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
    ),
    FORGE(
        id = "Forge",
        displayName = "Forge 1.20.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FORGE_ACCEPTANCE,
        includedBuildName = "platform-forge-1.20.1",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run-server/acceptance-report.txt",
    ),
    NEOFORGE(
        id = "NeoForge",
        displayName = "NeoForge 1.20.2 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.NEOFORGE_ACCEPTANCE,
        includedBuildName = "platform-neoforge",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run-server/acceptance-report.txt",
    ),
    BUKKIT(
        id = "Bukkit",
        displayName = "Paper 1.20.1 插件宿主",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        includedBuildName = null,
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        rootProjectTaskPath = ":platform:bukkit:1.20.1:runRealServerAcceptance",
    ),
    FOLIA(
        id = "Folia",
        displayName = "Folia 1.20.1 插件宿主（区域调度）",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        includedBuildName = null,
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        defaultMatrix = "R6",
        rootProjectTaskPath = ":platform:bukkit:1.20.1:runRealServerAcceptance",
    ),
    CATSERVER(
        id = "CatServer",
        displayName = "CatServer 1.12.2 融合服（Bukkit 活跃）",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FORGE_112_OPTIONAL,
        includedBuildName = null,
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        defaultMatrix = "R5",
        rootProjectTaskPath = ":platform:bukkit:1.12.2:runRealServerAcceptance",
    ),
    SPONGE(
        id = "Sponge",
        displayName = "SpongeVanilla 1.20.1 宿主",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        includedBuildName = "platform-sponge",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run/acceptance-report.txt",
    ),
    ;

    fun rootTaskName(): String = "runRealServerAcceptance$id"

    enum class ServerKind {
        MOD_DEDICATED,
        PLUGIN_HOST,
    }

    enum class ClientKind {
        /** Fabric gametest 伴侣 + quickPlay */
        FABRIC_GAMETEST,

        /** Forge acceptance 伴侣 */
        FORGE_ACCEPTANCE,

        /** NeoForge acceptance 伴侣 */
        NEOFORGE_ACCEPTANCE,

        /** Forge 1.12.2 伴侣（R5 optional） */
        FORGE_112_OPTIONAL,
    }

    companion object {
        @Suppress("DEPRECATION")
        fun allLanes(): List<PlatformLane> = values().toList()
    }
}
