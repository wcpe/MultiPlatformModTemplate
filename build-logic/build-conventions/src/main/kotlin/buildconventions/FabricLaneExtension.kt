package buildconventions

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * fabric 车道配置：车道脚本只声明"本车道是什么"，重复的接入流程由 `build-conventions.fabric` 承担。
 *
 * 默认值取自三条车道的公共约定（产品任务 `remapJar`、报告路径、服务端地址、看门狗截止等），
 * 车道只在偏离约定时才写对应属性。
 */
abstract class FabricLaneExtension {
    /** MC 版本：产物名、run 文案与 `fabric.mod.json` 元数据都用它。 */
    abstract val mcVersion: Property<String>

    /** 车道目标 Java 版本：工具链与 `fabric.mod.json` 元数据都用它。 */
    abstract val targetJavaVersion: Property<Int>

    /** fabric-loader 版本（对应 `loaderDependency` 元数据）。 */
    abstract val loaderVersion: Property<String>

    /** fabric-api 版本（对应 `fabricApiDependency` 元数据）。 */
    abstract val fabricApiVersion: Property<String>

    /** 产品 jar 的生产任务名：`remapJar`；MC 26.1+ 无混淆、由 `shadowJar` 直接产出时改为它。 */
    abstract val productTaskName: Property<String>

    /** 模拟服默认轨场景清单：默认取三条 fabric 车道的公共约定，车道偏离时才覆写。 */
    abstract val simScenarios: ListProperty<String>

    /** 真服默认轨场景清单：默认取三条 fabric 车道的公共约定，车道偏离时才覆写。 */
    abstract val realScenarios: ListProperty<String>

    /** 模拟服报告文件（默认 `build/acceptance/sim-report.txt`）。 */
    abstract val simulatorReport: RegularFileProperty

    /** 验收报告文件（默认 `build/acceptance/server-report.txt`）。 */
    abstract val acceptanceReport: RegularFileProperty

    /** 验收服务端地址（默认 `127.0.0.1:25571`，对齐 `run/server.properties` 的 server-port）。 */
    abstract val acceptanceServerAddress: Property<String>

    /** 矩阵轨 java 可执行文件的环境变量名（如 `MPMT_JAVA21_HOME`）；留空表示本车道无矩阵轨。 */
    abstract val matrixJavaHomeEnvironment: Property<String>

    /** acceptanceServer run 是否显式依赖 `gametestClasses`（1.20.1 车道原样不加，故可关）。 */
    abstract val acceptanceServerCompilesGametest: Property<Boolean>

    /** 客户端 run 是否把解析后的验收服务端地址写回同名系统属性（1.20.1 车道原样不写）。 */
    abstract val acceptanceClientExposesServerProperty: Property<Boolean>

    /** acceptanceServer run 的默认报告路径是否采用 `-Pmpmt.acceptance.report` 覆盖值（26.2 单进程编排需要）。 */
    abstract val acceptanceServerUsesOverriddenReport: Property<Boolean>
}
