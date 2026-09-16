# ADR-0026：平台车道统一为根构建子模块（取消加载器插件隔离）

## 状态

已接受（取代 [ADR-0007](0007-composite-build-loader-isolation.md) 的"加载器插件物理隔离"决策；部分取代 [ADR-0021](0021-p2-version-matrix-toolchain-isolation.md) 决策 2）

## 背景

[ADR-0007](0007-composite-build-loader-isolation.md) 的隔离理由是：**各加载器各自的构建插件**（Fabric 的 Loom、Forge 的 ForgeGradle、NeoForge 的 NeoGradle、Sponge 的 SpongeGradle）都重度接管构建（MC 依赖解析、重映射、运行配置），同处一个构建会"类路径打架、所需 Gradle / JDK 版本不一、`pluginManagement` 互相干扰"。

该前提已被 [ADR-0025](0025-wcpe-loom-toolchain-unification.md)（本 ADR 同步改写为 `top.wcpe.loom`）消除：mod 平台（fabric / forge / neoforge）的构建插件已统一为**同一个** architectury-loom fork 插件，它原生支持多平台、多 MC 版本共存于同一构建（该 fork 自带 `multi-mc-versions`、`multiProjectDirectLoom` 等多子项目夹具与测试），不再存在"多代插件互相冲突"。

与此同时，独立构建的代价已经具体化并持续累积：

- **9 份重复的 settings / pluginManagement**：每车道各有一份 `settings.gradle.kts`，重复声明仓库与插件版本 pin；插件与仓库解析实际是同一份。
- **5 条自有 wrapper 车道**（fabric/26.2、forge/1.12.2、forge/1.21.1、forge/26.2、neoforge/1.20.2）与 6 处反向 `includeBuild("../../..")`：为绕开版本墙而存在。
- **受控内部 JAR 与"根只校验文件"编排**：车道把 core 产物按**文件路径**消费（`internalJar()` / `sharedJars`），根门禁只能做"文件存在性 + 文本报告解析"，并在提示里写明"根构建不会嵌套调用该 wrapper"；`prepareFabric262Inputs` / `prepareNeoForge1202Inputs` 这类"先手工构建再供车道消费"的中间层随之产生。
- **编排绕行**：`includedBuildOrNull` / `dependsOnIncludedIfPresent` 在车道未加载时**静默降级为 warn**，门禁可能空跑通过；`buildAll` 需要单独聚合 `gradle.includedBuilds`。
- **契约面膨胀**：契约测试大量断言 `includeBuild` / 车道 `settings.gradle.kts` / 车道 `gradlew(.bat)` / 独立 wrapper 的存在，把"构建拓扑"固化成测试契约。

此外，独立构建原本还承担"每车道独立 Gradle / JDK 代际"的隔离，而 [ADR-0025](0025-wcpe-loom-toolchain-unification.md) 已把 Gradle 全车道收敛到 9.6.1，这部分收益同时消失。

## 决策

1. **平台车道一律作为根构建的普通子模块**：`platform:fabric:{1.20.1,1.21.1,26.2}`、`platform:forge:{1.12.2,1.20.1,1.21.1,26.2}`、`platform:neoforge:1.20.2`、`platform:sponge:1.20.1` 由根 `settings.gradle.kts` 直接 `include`。**删除**：车道 `settings.gradle.kts`、车道自有 wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/**`）、车道反向 `includeBuild("../../..")` 及其 `dependencySubstitution`、根侧针对车道的 `includeBuild` 与 `includedBuildOrNull` / `dependsOnIncludedIfPresent` 助手与全部调用点。
2. **保留 `build-logic/realserver-acceptance` 的 `pluginManagement { includeBuild(...) }`**。本 ADR 的"取消复合构建"**只针对平台车道**：Gradle 插件工程必须经 `includeBuild` 或 `buildSrc` 引入，属构建基础设施而非平台隔离。settings 末尾那处**同名二次 include**（`mpmt-realserver-acceptance-logic`，仅为让 `gradle.includedBuilds` 枚举到它）删除。
3. **构建插件统一为 `top.wcpe.loom` 1.17.1**（详见改写后的 [ADR-0025](0025-wcpe-loom-toolchain-unification.md)），由根 `pluginManagement` 统一 pin 与提供仓库。
4. **根构建硬要求守护 JVM ≥ 25**：`fabric/26.2` 与 `forge/26.2` 两车道的 loom 管线与车道自身都在**配置期**硬校验（MC 26.2 的 `javaVersion` 与车道脚本的显式守卫）。子模块无法"按 JDK 条件跳过"，因此取消 `JavaVersion.current() < 25` 的 include 降级逻辑，改为**根构建统一以 JDK 25 运行**（低版本直接配置期失败并给出明确文案）。
5. **车道级平台声明保留在车道自己的 `gradle.properties`**：`loom.platform`（forge×4、neoforge）与 `fabric.loom.disableObfuscation`（fabric/26.2、forge/26.2）继续逐车道声明——Gradle 的项目级属性作用域天然支持，且这两个值必须在 `apply plugin` 之前可见。**构建级**键（`org.gradle.daemon` / `parallel` / `caching` / `jvmargs`）在子模块粒度无效，一律并入根 `gradle.properties`。
6. **车道消费核心/模块/API 一律用项目依赖**：`project(":core:domain")` 等直接依赖，删除模块坐标（`top.wcpe.mc.mpmt:*`，原经复合构建依赖替换解析）与文件级受控 JAR（`internalJar()` / `sharedJars` / `verifySharedJar` / `verifyInternalJars` / `prepare*Inputs`）。
7. **产物流转改为任务依赖**：根门禁直接依赖 `:platform:…:任务` 并用 `layout.buildDirectory` 读取产物；禁止"根只校验文件是否存在"的旁路。真服验收仍按 [ADR-0014](0014-realserver-acceptance-harness.md) / [ADR-0023](0023-p3-r7-automated-release-authority.md) 的**报告 + 严格门**模型读取报告文件校验（该契约不变）。
8. **静态分析与覆盖率门禁全车道接入**：平台车道作为普通子模块继承根 `subprojects` 的 checkstyle / PMD / SpotBugs(+FindSecBugs) / ktlint / detekt / kover，以及 JaCoCo LINE ≥ 0.70 底线（并入 `check`），并修到全绿——不再有"平台车道仅出报告、不设底线"的豁免。
9. **禁止开启 Gradle isolated projects**：loom 的属性解析在 isolated projects 下直接返回 null，`loom.platform` 会**静默退化**为 fabric、`fabric.loom.disableObfuscation` 恒为 false（不报错）。该限制写入 `architecture-invariants`。

## 理由

- 隔离前提已消失：单一 loom 插件 + 单一 Gradle 版本，使"插件代际冲突"和"Gradle/JDK 代际污染"两个原始理由都不再成立。
- 单构建把"构建拓扑"从**契约**降级为**实现细节**：契约测试不再需要断言 includeBuild / wrapper 的存在，门禁不再有空跑的降级分支（`dependsOnIncludedIfPresent` 的静默 warn 是真实风险）。
- 项目依赖取代"文件路径 + 存在性校验"：Gradle 自己保证顺序与产物，`prepare*Inputs` 手工链路与其错误文案一并消失。
- 保留插件工程的 `includeBuild` 是 Gradle 的硬性要求；把它与"平台车道隔离"区分开，避免为了口号牺牲插件化封装。

## 后果

- 正面：9 份 settings / 5 条 wrapper / 6 处反向 include 全部收敛为 1 份根构建；门禁不再有空跑降级；车道间依赖由 Gradle 保证；插件与仓库解析单点化。
- 正面：车道产物与报告路径不变（`build/reobfJar/output.jar`、`build/reobfShadowJar/output.jar`、`build/libs/…` 命名保持），根 `collectReleaseArtifacts` 的 13 个 jar 契约与真服报告门契约保持不变。
- 负面：**根构建必须用 JDK 25**（26.2 两车道的配置期硬校验），低 JDK 下无法配置；日常构建的 JVM 与内存占用抬升（根 `-Xmx` 需覆盖 forge/26.2 的 4g 需求）。
- 负面：**车道级构建隔离参数失效**（`org.gradle.daemon/parallel/caching=false`、车道级 `-Xmx`）——统一构建只有一个 daemon 与一套并行/缓存策略；forge/1.12.2 的"低内存串行"特性只能靠根参数或任务级约束近似。
- 负面：**配置期副作用进入根构建**——forge/1.12.2 原在配置期下载 Forge userdev 4 个 jar、重写 userdev3、写最小 POM，子模块化后成为根构建任何任务（含 `./gradlew help`）的固定代价；必须改为惰性（任务/provider 内）执行。
- 负面：静态分析与覆盖率门禁全量接入后，平台胶水模块需要补测试与整改（原为"只出报告"）。
- 约束：**SpongeGradle 与 loom 同处一个构建**已实测通过（`platform:sponge:sponge-1.20.1` 与 8 条 loom 车道同一根构建内配置、构建均成功）。若日后 SpongeGradle 升级引入冲突，回退方案是把该车道重新隔离为独立构建，并以修订本 ADR 的方式记录（不得静默违背）。
- 约束（实测得出，加入车道时必须遵守）：**平台车道的 `project.name` 必须全局唯一**。loom 以 `project.name` 作为共享服务键（`JarManifestService.get` → `"LoomJarManifestService:" + project.getName()`），跨加载器存在同名版本段（fabric 与 forge 都有 `1.20.1` / `1.21.1` / `26.2`），同名工程会撞键并在运行期报 `JarManifestService$Inject_ cannot be cast to JarManifestService`（`jar` 与 `remapJar` 均受影响）。落地方式：工程路径末段带加载器前缀（`platform:fabric:fabric-1.20.1` …），再用 `projectDir` 映射回 `platform/<loader>/<版本>` 目录。
- 约束（实测得出）：**无混淆车道必须应用 `top.wcpe.loom-no-remap` 变体插件**，不能只依赖 `fabric.loom.disableObfuscation=true`。loom 的 `MixinAPMappingService` 会遍历**全构建**的 loom 工程（`GradleUtils.allLoomProjects`），仅跳过 `LoomNoRemapGradlePlugin.isApplied()` 的工程；纯属性方式会让该服务对无 mappings 的车道调用 `getMappingConfiguration()` 而抛 `Cannot get mappings configuration in a non-obfuscated environment`。
- 约束（实测得出）：**loom 插件必须在根 `plugins {}` 以 `apply false` 声明**（`top.wcpe.loom` 与 `top.wcpe.loom-no-remap` 各一次），使插件类路径在全构建只加载一份。若仅由各车道在自己的 `plugins {}` 里请求，Gradle 会按子树各建插件类加载器，loom 的跨工程迭代（`MixinAPMappingService` → `LoomGradleExtension.get(otherProject)`）会因跨 classloader 而报 `LoomGradleExtensionImpl_Decorated cannot be cast to LoomGradleExtension`。
- 约束：单构建共享 `ArtifactMetadata` 缓存（按 `ArtifactRef` 值相等）；**禁止多个车道共享同一个本地文件 jar** 作为 mod 依赖，否则可能出现按另一平台计算的元数据。
- 约束：`SrgProvider` 的 mojmap 映射表为 static `HashMap`，`--parallel` 下多 forge/neoforge 车道并行配置存在竞态，需在验收中做重复构建稳定性观察。
- 约束：若采用 settings 级仓库 + `FAIL_ON_PROJECT_REPOS`，需实测 loom 的 LWJGL3 升级路径（JDK≥19 + 老 MC）是否触发动态加仓冲突。

## 备选方案

- **维持 includeBuild 隔离**：隔离前提（多代插件、Gradle/JDK 代际墙）已被 ADR-0025 消除，保留只会持续承担重复 settings / wrapper / 受控 JAR / 空跑降级成本——否决。
- **保留 5 条自有 wrapper 车道，只把 5 条 includeBuild 车道子模块化**：一半统一、一半不统一，编排仍需两套模型与"禁止嵌套 gradlew"的绕行——否决。
- **把插件工程改造为 `buildSrc`**：能满足"无 includeBuild"，但会改变契约测试的目录/工作目录推导并削弱插件化封装，收益不足以抵消成本——否决（保留 `pluginManagement` 的 includeBuild）。
- **无条件要求 JDK 25 之外的降级方案**（低 JDK 时跳过 26.2 子模块）：`include` 级跳过会让验收清单（13 jar）缺件，任务级跳过又无法绕开"配置期硬校验"——否决，改为根构建统一 JDK 25。
- **继续用文件路径消费 core 产物（受控内部 JAR）**：与"子模块"自相矛盾，且需要维护存在性校验与手工前置任务——否决。
