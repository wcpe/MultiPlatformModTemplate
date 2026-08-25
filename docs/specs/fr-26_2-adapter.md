# 功能规格：FR-16 · MC 26.2 版本适配（冻结）

> 状态：开发中（T1–T5 已完成；待随 P3 `v0.3.0` 统一发布）　·　关联 PRD：FR-16　·　父规格：[p3-platform-scaling-and-onboarding](p3-platform-scaling-and-onboarding.md)　·　架构：[ADR-0003](../adr/0003-multi-version-adapter.md) / [ADR-0021](../adr/0021-p2-version-matrix-toolchain-isolation.md) / [ADR-0022](../adr/0022-unobfuscated-minecraft-naming-policy.md) / [ADR-0023](../adr/0023-p3-r7-automated-release-authority.md)

## 1. 背景与目标

第三期 FR-16 要把 L4 锚点从 1.21.1 前推到 **MC 26.2**（新版号方案，无 `1.` 前缀，模块名 `v26_2`）。本文件是 **26.2 工具链 / 受控制品 / 有效格子** 的冻结真源；T2–T4 的实现与本地验证必须按本表，T5 由 ADR-0023 定义的 R7 严格自动化门完成，禁止跟随 latest。

### 1.1 T1 结论（存在性，核对日 2026-07-26）

| 目标 | 存在？ | 证据摘要 |
|---|---|---|
| Minecraft 26.2 正式版 | **是**（stable） | Fabric Meta `versions/game` 含 `"version":"26.2","stable":true` |
| Paper 26.2 | **是**（SUPPORTED；构建通道目前 ALPHA/BETA，无 STABLE） | T1 冻结服务端 build 71；当前编译 API 使用 build 72；`java.minimum=25` |
| Fabric 26.2 | **是** | Loader `0.19.3` 有 `loader/26.2` profile；Fabric API `0.155.2+26.2`（Modrinth / Fabric Maven）；Minecraft 26.1+ 上游制品不再混淆，使用 Fabric 的无混淆 Loom 链路，无需 Mojmap、Yarn 或 intermediary remap |
| Forge 26.2 | **是** | promotions `26.2-latest=65.0.9`；`forge-26.2-65.0.9-userdev.jar` 可拉取 |
| Folia 26.2 | **否** | Fill API v3 Folia 版本树仅见 `26.1` / `1.21` 系，无 `26.2` |
| NeoForge / Sponge 26.2 | **不在本期** | 父规格 §2.2 / §3；T1 不冻结 |

**硬结论**：Paper / Fabric / Forge 三格均可开 T2；Folia 26.2 不得建格；NeoForge/Sponge 不进 FR-16。

## 2. 有效矩阵

| 维度 | 服务端产品 | 客户端产品 / 伴侣 | JDK | 说明 |
|---|---|---|---|---|
| MC 26.2 · Paper | Bukkit 产品插件 + 验收插件 | Fabric / Forge 客户端产品 + 验收伴侣 | **Java 25+**（Paper 官方 minimum） | 与 1.21.1 同端口契约 |
| MC 26.2 · Fabric | 服务端 mod | Fabric 客户端产品 + 验收伴侣 | 构建/真服按 Loom/MC 要求（建议与 Paper 对齐 25+） | 独立 includeBuild；无混淆 Loom，无 mappings/remap |
| MC 26.2 · Forge | 服务端 mod | Forge 客户端产品 + 验收伴侣 | **JDK 25 + Gradle 9.6.1 + ForgeGradle 7.0.31** | **自有 launcher**，禁止根嵌套 gradlew |
| Folia / NeoForge / Sponge · 26.2 | — | — | — | **不建格** |

复用验收入口：`:runP2RealServerAcceptance` **不**扩张 26.2；26.2 走全 lane `:runRealServerAcceptance` 子集，矩阵轨 **R7**（公共三场景：`product-handshake` / `product-roundtrip` / `client-hud`）。

## 3. 冻结版本、制品与来源标识

核对日：**2026-07-26**。来源标识用于追溯，文档不存裸下载口令以外的密钥。

| 目标 | 冻结版本 / 制品 | SHA-256 / 锁定方式 | 来源标识 |
|---|---|---|---|
| Minecraft 26.2 | 正式版 `26.2`（stable） | 游戏本体由 loader 解析 | Fabric Meta `versions/game` |
| Paper 26.2 | build **71**；`paper-26.2-71.jar`；61,744,713 字节 | `36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e` | Paper Fill API v3：`projects/paper/versions/26.2/builds/71`；通道 **BETA** |
| Paper API（编译期） | `io.papermc.paper:paper-api:26.2.build.72-beta`；`platform/bukkit/26.2/libs/paper-api-26.2.build.72-beta.jar`；2,883,617 字节 | `ff4dd8b88beb95e990a900f587da3644d44345ce2bc6e8a11b851f6dfb98742b` | Bukkit 26.2 构建脚本的受控本地 compileOnly 输入；不打入产品 jar |
| Fabric Loader | `0.19.3` | 依赖坐标锁定 | Fabric Meta `versions/loader/26.2` |
| Fabric 命名 / 重映射 | **不适用** | Minecraft 26.1+ 使用上游原始命名，不声明 mappings，不以 intermediary/remap 生成最终制品 | [ADR-0022](../adr/0022-unobfuscated-minecraft-naming-policy.md) |
| Fabric API | `0.155.2+26.2` | 依赖坐标锁定（Maven / Modrinth `fabric-api-0.155.2+26.2.jar`） | Fabric Maven metadata + Modrinth project `P7dR8mSH` |
| Yarn / Mojmap | **均不需要** | Minecraft 26.1+ 上游制品不再混淆；Fabric 26.2 使用 `net.fabricmc.fabric-loom` 的无混淆构建链路 | [ADR-0022](../adr/0022-unobfuscated-minecraft-naming-policy.md) |
| Forge 26.2 | `26.2-65.0.9`（promotions latest） | 依赖坐标锁定；userdev 已确认可下载 | Forge promotions_slim + Maven `forge-26.2-65.0.9` |

受控制品启动前必须校验冻结哈希与大小；**禁止**跟随 Paper/Forge latest。Paper 当前无 STABLE 通道构建——真服运行时仍冻结在 BETA build 71；编译 API build 72 是单独受控输入，不得把两者混作同一制品。若任一输入升级，必须先更新本表哈希后再动代码。

### 3.1 关键运行约束

- **Paper 26.2 官方 `java.minimum = 25`**。本机真服 / CI 必须提供 JDK 25+（建议 `MPMT_JAVA25_HOME`）；不得用 21 跑 Paper 26.2 宿主。
- 根 Gradle wrapper 固定为 **9.6.1**；根只直接编排 Bukkit 与 Fabric 26.2。L0–L2 仍 **`--release 8`**（ADR-0004），不因 P3 改变；仅 26.2 平台车道抬高工具链。
- Forge 26.2 固定使用 **JDK 25 + Gradle 9.6.1 + ForgeGradle 7.0.31**，Forge 坐标固定为 **`26.2-65.0.9`**；必须使用 `platform/forge/26.2/gradlew` 自有 wrapper，禁止根构建嵌套调用。
- P3 Paper 自动宿主只请求冻结 build 71 的 Fill API 元数据与受信任 HTTPS 下载地址；缓存命中及下载完成后均核对表中大小和 SHA-256。此冻结不扩张到未声明冻结值的历史 Paper 宿主。

## 4. 构建车道（T2 实现时遵守）

| MC | 工程路径 | launcher | 备注 |
|---|---|---|---|
| Paper/Bukkit 26.2 | `platform/bukkit/26.2` | 根 `./gradlew :platform:bukkit:26.2:…` | 根子工程；产物名 `mpmt-bukkit-26.2-$VERSION` |
| Fabric 26.2 | `platform/fabric/26.2` | 根 `./gradlew :buildFabric262` | 独立 includeBuild `platform-fabric-26.2`；根先准备受控内部 JAR；无 mappings/remap |
| Forge 26.2 | `platform/forge/26.2` | **`./platform/forge/26.2/gradlew` 自有 wrapper** | JDK 25 + Gradle 9.6.1 + ForgeGradle 7.0.31 + Forge 26.2-65.0.9；根不 include、不嵌套 |

L4：`version-api` + `v26_2` 实现；运行期探测 MC 版本选 `v26_2`。

## 5. 验收（实现后）

- R7 合规 `SERVER-GAMETEST-REPORT v2`：含 `MATRIX R7` / `RUN_ID` / 制品哈希；公共三场景恰好一次 PASS；末行 `RESULT PASS`。
- Paper / Fabric / Forge 三格必须各有一份**属于当前运行**的可复核 R7 报告；`:runP3R7Gate` 只聚合该证据，不会生成或补造报告。
- 旧的本地 `RESULT PASS` 只可作为历史尝试；冻结 Paper build 71、完整五制品哈希、同一 `RUN_ID` / 开始毫秒与严格解析门已在当前候选提交的 `p3-r7-1787686232087` 三车道报告中验证通过。该证据满足 ADR-0023 定义的 T5 最终自动化验收。
- 同轮 R7 严格门通过后，`sdd-release-version` 可随第三期统一发布将 FR-16 标 `已交付@v…`。
- P2 的 `:runVersionMatrixGate` 与 R1–R6 报告不含 26.2，不能作为本条验收证据。
- **不得**因 Folia/NeoForge/Sponge 无 26.2 而宣称全平台宇宙通过。

## 6. 任务拆分

- [x] T1 · 存在性确认 + 本冻结规格（本文件）
- [x] T2 · 三车道独立工程（仅 Paper/Fabric/Forge；当前根 P3 构建门与 Forge 自有 wrapper 打包已通过）
- [x] T3 · `version-api` + `v26_2` + 纯 JVM 单测（当前实现与验证已通过）
- [x] T4 · R7 矩阵轨与严格当前报告（冻结 Paper build 71 后，三车道同轮 `p3-r7-1787686232087` 已通过根 `:runP3R7Gate`）
- [x] T5 · ADR-0023 的 R7 最终自动化验收（冻结 Paper build 71 的三车道同轮严格门）
- [x] 文档同步：PRD FR-16、ARCHITECTURE、CHANGELOG 与 ADR-0023 已对账

## 7. 风险 / 待定

- Paper 26.2 构建长期停在 BETA 通道：升 build 必须重冻哈希。
- **JDK 25** 对本仓库既有 JDK 8/17/21 矩阵是新增轴；CI / 开发机未装 25 则真服无法跑。
- Fabric 26.2 不使用 Yarn、Mojmap 或 intermediary remap；Minecraft 26.1+ 直接使用上游无混淆原始命名，详见 ADR-0022。
- Forge 26.2 固定 JDK 25 + Gradle 9.6.1 + ForgeGradle 7.0.31，禁止与根工具链混用；ForgeGradle 内部转换不等同 Mojang mappings 文件。
- Folia 无 26.2：区域调度回归仍锚 1.20.1 / 既有 R6，不纳入 R7。
