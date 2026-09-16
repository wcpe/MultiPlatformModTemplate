package top.wcpe.mc.mpmt.gradle.realserver

/**
 * B 车道：全部服务端形态 + 对应进服客户端。
 *
 * <p>客户端一律由**对应 loader 的 gametest / acceptance 伴侣**进服（自写 GameTest 伴侣、
 * quickPlay 或 FG/Neo runClient），不用 shell；mineflayer 仅 A 辅车道（mc-testkit）。
 *
 * <p>ADR-0026 起全部平台车道均为**根构建子模块**，因此每条车道只需一个根工程任务路径；
 * 不再区分"复合构建名"与"根多模块路径"。
 */
enum class PlatformLane(
    val id: String,
    val displayName: String,
    val serverKind: ServerKind,
    val clientKind: ClientKind,
    /** 根工程内该车道的真服门任务全路径；例：`:platform:fabric:fabric-1.20.1:runRealServerAcceptance` */
    val rootTaskPath: String,
    /** 平台/模块内门禁任务名 */
    val verifyTaskName: String,
    val defaultReportHint: String,
    /** 可选：默认矩阵（HYBRID/SCHEDULER/REALSERVER262）；空=默认轨 */
    val defaultMatrix: String = "",
) {
    FABRIC(
        id = "Fabric",
        displayName = "Fabric 1.20.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:fabric:fabric-1.20.1:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
    ),
    FABRIC_121(
        id = "Fabric121",
        displayName = "Fabric 1.21.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:fabric:fabric-1.21.1:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
    ),
    FABRIC_262(
        id = "Fabric262",
        displayName = "Fabric 26.2 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:fabric:fabric-26.2:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report-realserver262.txt",
        defaultMatrix = "REALSERVER262",
    ),
    FORGE(
        id = "Forge",
        displayName = "Forge 1.20.1 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FORGE_ACCEPTANCE,
        rootTaskPath = ":platform:forge:forge-1.20.1:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run-server/acceptance-report.txt",
    ),
    FORGE_262(
        id = "Forge262",
        displayName = "Forge 26.2 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.FORGE_ACCEPTANCE,
        // JDK 25 由根构建统一要求（ADR-0026 决策 4）；车道为标准子模块。
        rootTaskPath = ":platform:forge:forge-26.2:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run-acceptance-server/acceptance-report.txt",
        defaultMatrix = "REALSERVER262",
    ),
    NEOFORGE(
        id = "NeoForge",
        displayName = "NeoForge 1.20.2 专用服",
        serverKind = ServerKind.MOD_DEDICATED,
        clientKind = ClientKind.NEOFORGE_ACCEPTANCE,
        rootTaskPath = ":platform:neoforge:neoforge-1.20.2:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "run-server/acceptance-report.txt",
    ),
    BUKKIT(
        id = "Bukkit",
        displayName = "Paper 1.20.1 插件宿主",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:bukkit:1.20.1:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
    ),
    BUKKIT_262(
        id = "Bukkit262",
        displayName = "Paper 26.2 插件宿主",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:bukkit:26.2:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        defaultMatrix = "REALSERVER262",
    ),
    FOLIA(
        id = "Folia",
        displayName = "Folia 1.20.1 插件宿主（区域调度）",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:bukkit:1.20.1:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        defaultMatrix = "SCHEDULER",
    ),
    CATSERVER(
        id = "CatServer",
        displayName = "CatServer 1.12.2 融合服（Bukkit 活跃）",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FORGE_112_OPTIONAL,
        rootTaskPath = ":platform:bukkit:1.12.2:runRealServerAcceptance",
        verifyTaskName = "runRealServerAcceptance",
        defaultReportHint = "build/acceptance/server-report.txt",
        defaultMatrix = "HYBRID",
    ),
    SPONGE(
        id = "Sponge",
        displayName = "SpongeVanilla 1.20.1 宿主",
        serverKind = ServerKind.PLUGIN_HOST,
        clientKind = ClientKind.FABRIC_GAMETEST,
        rootTaskPath = ":platform:sponge:sponge-1.20.1:runRealServerAcceptance",
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

        /** Forge 1.12.2 伴侣（HYBRID optional） */
        FORGE_112_OPTIONAL,
    }

    companion object {
        @Suppress("DEPRECATION")
        fun allLanes(): List<PlatformLane> = values().toList()
    }
}
