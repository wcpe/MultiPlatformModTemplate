package buildconventions

import org.gradle.api.Project
import org.gradle.api.Task

/**
 * 根侧真服验收入口（Gradle only，禁止 .sh 脚本编排）。
 *
 * B 完整：全部服务端 lane；客户端 = 各 loader 自有 gametest/acceptance 伴侣进服。
 * 对齐 AllinCore：根薄包装，门直接委托车道的根工程任务路径；禁止嵌套 gradlew。
 *
 * 各 lane 门只读平台内 `runRealServerAcceptance` 的权威报告，不重复实现判定。
 */
internal fun registerRealServerLaneGates(project: Project) {
    registerLaneCoverageEntry(project)
    registerFabricLaneGates(project)
    registerForgeLaneGates(project)
    registerNeoForgeLaneGate(project)
    registerBukkitLaneGates(project)
    registerSpongeLaneGate(project)
    registerLaneAggregateGate(project)
}

/** 打印 B 车道覆盖（与 build-logic PlatformLaneCatalog 一致，根侧可离线查看）。 */
private fun registerLaneCoverageEntry(project: Project) {
    project.tasks.register("listRealServerLanes") {
        group = "help"
        description = "列出 B 车道：全服务端 + 自有 gametest 客户端进服方式"
        doLast {
            logger.lifecycle(
                """
                |[mpmt-realserver] B 车道覆盖（全部平台车道为根构建子模块，ADR-0026）
                |  Fabric 1.20.1   :platform:fabric:fabric-1.20.1    客户端=Fabric gametest
                |  Fabric 1.21.1   :platform:fabric:fabric-1.21.1    客户端=Fabric gametest
                |  Fabric 26.2     :platform:fabric:fabric-26.2      客户端=Fabric gametest（须 JDK 25）
                |  Forge 1.20.1    :platform:forge:forge-1.20.1     客户端=Forge acceptance
                |  Forge 1.21.1    :platform:forge:forge-1.21.1     客户端=Forge acceptance
                |  Forge 1.12.2    :platform:forge:forge-1.12.2     client-only（真服走 CatServer/HYBRID）
                |  Forge 26.2      :platform:forge:forge-26.2       客户端=Forge acceptance（须 JDK 25）
                |  NeoForge 1.20.2 :platform:neoforge:neoforge-1.20.2  客户端=NeoForge acceptance
                |  Bukkit/Paper    :platform:bukkit:1.20.1    客户端=Fabric gametest
                |  Folia           同上 1.20.1；矩阵默认 SCHEDULER
                |  CatServer（HYBRID 矩阵）    :platform:bukkit:1.12.2 + Forge 1.12 client-only 伴侣（禁止 Forge 服务端 mod）
                |  Sponge          :platform:sponge:sponge-1.20.1
                |入口（请用绝对路径 :task，避免匹配子工程同名任务）：
                |  ./gradlew :runRealServerAcceptance          # 默认轨全服务端门（不含 26.2）
                |  ./gradlew :runRealServerGate262              # 26.2 三车道权威门（矩阵轨 + 严格轮次校验）
                |  ./gradlew :runRealServerAcceptanceFabric
                |  ./gradlew :verifyVersionMatrixBuild
                |  ./gradlew :runVersionMatrixGate
                |  ./gradlew :collectReleaseArtifacts   # → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
                |  ./gradlew :buildAll
                |B 增强：
                |  ./gradlew :platform:bukkit:1.20.1:ensurePaperRealServerHost -Pmpmt.realserver.autoHost=true
                |A 辅车道：./gradlew :runMcTestkitSmoke（被测插件自动取 bukkit 1.20.1 shadowJar；矩阵见 ci.yml）
                |注：根构建须以 JDK 25 运行（26.2 两条车道的配置期硬校验，ADR-0026）。
                """.trimMargin(),
            )
        }
    }
}

/** 车道门统一外壳：verification 组 + 描述 + 各自依赖接线。 */
private fun registerLaneGate(
    project: Project,
    taskName: String,
    descriptionText: String,
    configure: Task.() -> Unit,
) {
    project.tasks.register(taskName) {
        group = "verification"
        description = descriptionText
        configure()
    }
}

// --- 各服务端 lane：委托平台内 runRealServerAcceptance（读权威报告）---

private fun registerFabricLaneGates(project: Project) {
    registerLaneGate(
        project,
        "runRealServerAcceptanceFabric",
        "Fabric 1.20.1 专用服门禁：须先 runAcceptanceServer + Fabric gametest 客户端进服",
    ) {
        dependsOn(":platform:fabric:fabric-1.20.1:runRealServerAcceptance")
    }

    registerLaneGate(
        project,
        "runRealServerAcceptanceFabric121",
        "Fabric 1.21.1 专用服门禁",
    ) {
        dependsOn(":platform:fabric:fabric-1.21.1:runRealServerAcceptance")
    }

    registerLaneGate(
        project,
        "runRealServerAcceptanceFabric262",
        "Fabric 26.2 专用服门禁：须先完成服务端与 Fabric gametest 客户端实跑",
    ) {
        dependsOn(":platform:fabric:fabric-26.2:runRealServerAcceptance")
    }
}

private fun registerForgeLaneGates(project: Project) {
    registerLaneGate(
        project,
        "runRealServerAcceptanceForge",
        "Forge 1.20.1 专用服门禁：须先实跑 Forge 服 + Forge acceptance 客户端伴侣",
    ) {
        dependsOn(":platform:forge:forge-1.20.1:runRealServerAcceptance")
    }

    /** Forge 1.21.1：根子模块车道（ADR-0026），直接委托其报告门。 */
    project.tasks.register("runRealServerAcceptanceForge121") {
        group = "verification"
        description = "Forge 1.21.1 专用服门禁：委托车道 runRealServerAcceptance（须先完成起服 + 客户端伴侣实跑）"
        dependsOn(":platform:forge:forge-1.21.1:runRealServerAcceptance")
    }

    /**
     * Forge 26.2：根子模块车道，直接委托其报告门。
     * 轮次 / 制品哈希 / 三场景的严格校验由 verifyRealServerReportsStrict（build-logic）承担。
     */
    project.tasks.register("runRealServerAcceptanceForge262") {
        group = "verification"
        description = "Forge 26.2 专用服门禁：委托车道 runRealServerAcceptance"
        dependsOn(":platform:forge:forge-26.2:runRealServerAcceptance")
    }

    /** Forge 1.12.2 client-only：真服走 CatServer（HYBRID 矩阵），禁止 Forge 专用服。 */
    project.tasks.register("runRealServerAcceptanceForge112") {
        group = "verification"
        description =
            "Forge 1.12.2 伴侣构建门：构建 client-only 伴侣；真服请用 :runRealServerAcceptanceCatServer（禁止 Forge 服务端 mod）"
        dependsOn(":platform:forge:forge-1.12.2:prepareClientCompanionArtifacts")
        doLast {
            logger.lifecycle(
                """
                |[runRealServerAcceptanceForge112]
                |ADR-0021：1.12.2 Forge 只产 client-only 客户端，不得装入 CatServer 作服务端 mod。
                |真服矩阵 HYBRID：
                |  ./gradlew :runRealServerAcceptanceCatServer
                |  # 即 :platform:bukkit:1.12.2:runRealServerAcceptance（报告 RESULT PASS）
                |客户端伴侣产物（本次已构建）：
                |  platform/forge/1.12.2/build/reobfJar/output.jar
                |  platform/forge/1.12.2/build/reobfAcceptanceJar/output.jar
                """.trimMargin(),
            )
        }
    }
}

/** NeoForge 1.20.2：根子模块车道（ADR-0026），直接委托其报告门。 */
private fun registerNeoForgeLaneGate(project: Project) {
    project.tasks.register("runRealServerAcceptanceNeoForge") {
        group = "verification"
        description = "NeoForge 专用服门禁：委托车道 runRealServerAcceptance（须先完成起服 + 客户端伴侣实跑）"
        dependsOn(":platform:neoforge:neoforge-1.20.2:runRealServerAcceptance")
    }
}

private fun registerBukkitLaneGates(project: Project) {
    registerLaneGate(
        project,
        "runRealServerAcceptanceBukkit",
        "Paper/Bukkit 宿主门禁（默认 1.20.1）：产品+验收插件部署后，Fabric gametest 客户端进服写报告",
    ) {
        dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
    }

    registerLaneGate(
        project,
        "runRealServerAcceptanceBukkit262",
        "Paper 26.2 真服宿主门禁：须先部署产品与验收插件并完成客户端进服",
    ) {
        dependsOn(":platform:bukkit:26.2:runRealServerAcceptance")
    }

    registerLaneGate(
        project,
        "runRealServerAcceptanceFolia",
        "Folia 宿主门禁（矩阵 SCHEDULER）：1.20.1 产物，Folia 实跑 + Fabric gametest 客户端",
    ) {
        dependsOn(":platform:bukkit:1.20.1:runRealServerAcceptance")
    }

    registerLaneGate(
        project,
        "runRealServerAcceptanceCatServer",
        "CatServer 融合服门禁（矩阵 HYBRID）：Bukkit 1.12.2 活跃 + Forge 1.12.2 optional 客户端",
    ) {
        dependsOn(":platform:bukkit:1.12.2:runRealServerAcceptance")
    }
}

private fun registerSpongeLaneGate(project: Project) {
    registerLaneGate(
        project,
        "runRealServerAcceptanceSponge",
        "Sponge 宿主门禁：Sponge 服 + Fabric gametest 客户端进服",
    ) {
        dependsOn(":platform:sponge:sponge-1.20.1:runRealServerAcceptance")
    }
}

/**
 * 默认：全服务端 lane 串行门禁（各 lane 须已自行完成「服 + 自有 gametest 客户端」并落报告）。
 *
 * 两条 26.2 车道（Bukkit 26.2 与 Fabric 26.2）**没有默认轨**：它们只有矩阵轨 REALSERVER262，
 * 报告路径为 `server-report-realserver262.txt`，且按 `-Pmpmt.acceptance.runId` 校验本轮归属。
 * 故本聚合门刻意**不含**它们——26.2 的权威入口是 `:runRealServerGate262`
 * （ADR-0023：三车道 + 严格报告校验 + 本轮轮次匹配）。曾经把它们挂进来会让本门在任何时刻必红。
 */
private fun registerLaneAggregateGate(project: Project) {
    project.tasks.register("runRealServerAcceptance") {
        group = "verification"
        description =
            "B 完整：全服务端 realserver 报告门禁（默认轨车道；26.2 请用 :runRealServerGate262）"
        dependsOn(
            "runRealServerAcceptanceFabric",
            "runRealServerAcceptanceFabric121",
            "runRealServerAcceptanceForge",
            "runRealServerAcceptanceForge262",
            "runRealServerAcceptanceNeoForge",
            "runRealServerAcceptanceBukkit",
            "runRealServerAcceptanceFolia",
            "runRealServerAcceptanceCatServer",
            "runRealServerAcceptanceSponge",
        )
    }
}
