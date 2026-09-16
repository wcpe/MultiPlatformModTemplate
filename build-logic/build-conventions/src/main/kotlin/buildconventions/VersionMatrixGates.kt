package buildconventions

import org.gradle.api.Project

/**
 * 版本矩阵门（FR-12 / ADR-0021，注册在根工程）：矩阵核心真服门 + 矩阵构建门 + 聚合门。
 *
 * 不调用 buildAll；不阻断 NeoForge / Sponge；26.2 三车道有独立交付门 `runRealServerGate262`。
 */
internal fun registerVersionMatrixGates(project: Project) {
    registerMatrixAcceptanceGate(project)
    registerMatrixBuildGate(project)
    registerMatrixAggregateGate(project)
}

/**
 * 版本矩阵核心真服门禁（FR-12）：仅矩阵相关车道，不阻断 NeoForge / Sponge。
 *
 * <p>各 lane 仍须先自行完成「服 + 自有 gametest 客户端」并落 RESULT PASS 报告；
 * 本任务只读权威报告，不嵌套 gradlew、不调用 buildAll。
 */
private fun registerMatrixAcceptanceGate(project: Project) {
    project.tasks.register("runVersionMatrixRealServerAcceptance") {
        group = "verification"
        description =
            "版本矩阵核心 realserver 门禁：Fabric 1.20/1.21 + Forge 1.20 + Bukkit/Folia + CatServer（不含 NeoForge/Sponge）"
        dependsOn(
            "runRealServerAcceptanceFabric",
            "runRealServerAcceptanceFabric121",
            "runRealServerAcceptanceForge",
            "runRealServerAcceptanceBukkit",
            "runRealServerAcceptanceFolia",
            "runRealServerAcceptanceCatServer",
        )
    }
}

/**
 * 版本矩阵构建（无真服）：对齐每版本独立工程路径，废除 -Pmpmt.minecraftVersion。
 * Forge 1.21.1 / 1.12.2 须用各自目录自有 launcher，本任务只打印命令不嵌套 gradlew。
 */
private fun registerMatrixBuildGate(project: Project) {
    project.tasks.register("verifyVersionMatrixBuild") {
        group = "verification"
        description =
            "版本矩阵构建：Bukkit 三版本 + Fabric 两版本 + Forge 1.20.1 打包校验；打印 1.21/1.12 Forge 独立 launcher 命令"
        dependsOn(
            ":platform:bukkit:1.12.2:verifyPackaging",
            ":platform:bukkit:1.20.1:verifyPackaging",
            ":platform:bukkit:1.21.1:verifyPackaging",
        )
        dependsOn(":platform:fabric:fabric-1.20.1:verifyPackaging")
        dependsOn(":platform:fabric:fabric-1.21.1:verifyPackaging")
        dependsOn(":platform:forge:forge-1.20.1:verifyPackaging")
        doLast {
            val java8 = System.getenv("MPMT_JAVA8_HOME")
            val java17 = System.getenv("MPMT_JAVA17_HOME")
            val java21 = System.getenv("MPMT_JAVA21_HOME")
            if (java8.isNullOrBlank() || java17.isNullOrBlank() || java21.isNullOrBlank()) {
                logger.warn(
                    "[verifyVersionMatrixBuild] 建议设置 MPMT_JAVA8_HOME / MPMT_JAVA17_HOME / " +
                        "MPMT_JAVA21_HOME（跨代车道显式 JDK；当前未齐）",
                )
            } else {
                logger.lifecycle(
                    "[verifyVersionMatrixBuild] JDK 环境：8=$java8 17=$java17 21=$java21",
                )
            }
            logger.lifecycle(
                """
                |[verifyVersionMatrixBuild] 矩阵构建门已通过（全部平台车道为根子模块，ADR-0026）。
                |版本矩阵真服子门：./gradlew :runVersionMatrixRealServerAcceptance（须先矩阵车道落 RESULT PASS）
                |全 lane（含 NeoForge/Sponge）：./gradlew :runRealServerAcceptance
                """.trimMargin(),
            )
        }
    }
}

/**
 * 版本矩阵聚合门（FR-12 / ADR-0021）：
 * 构建矩阵 + 版本矩阵核心 realserver 报告门；不调用 buildAll，不阻断 NeoForge/Sponge。
 */
private fun registerMatrixAggregateGate(project: Project) {
    project.tasks.register("runVersionMatrixGate") {
        group = "verification"
        description =
            "版本矩阵门禁：verifyVersionMatrixBuild + runVersionMatrixRealServerAcceptance（不含 NeoForge/Sponge）"
        dependsOn("verifyVersionMatrixBuild", "runVersionMatrixRealServerAcceptance")
    }
}
