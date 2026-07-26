# 功能规格：FR-16 · MC 26.2 版本适配（冻结）

> 状态：草拟（T1 冻结完成，实现未开）　·　关联 PRD：FR-16　·　父规格：[p3-platform-scaling-and-onboarding](p3-platform-scaling-and-onboarding.md)　·　架构：[ADR-0003](../adr/0003-multi-version-adapter.md) / [ADR-0021](../adr/0021-p2-version-matrix-toolchain-isolation.md)

## 1. 背景与目标

第三期 FR-16 要把 L4 锚点从 1.21.1 前推到 **MC 26.2**（新版号方案，无 `1.` 前缀，模块名 `v26_2`）。本文件是 **26.2 工具链 / 受控制品 / 有效格子** 的冻结真源；实现（T2–T5）必须按本表，禁止跟随 latest。

### 1.1 T1 结论（存在性，核对日 2026-07-26）

| 目标 | 存在？ | 证据摘要 |
|---|---|---|
| Minecraft 26.2 正式版 | **是**（stable） | Fabric Meta `versions/game` 含 `"version":"26.2","stable":true` |
| Paper 26.2 | **是**（SUPPORTED；构建通道目前 ALPHA/BETA，无 STABLE） | Paper Fill API v3：`versions/26.2` 有 builds 10–71；`java.minimum=25` |
| Fabric 26.2 | **是** | Loader `0.19.3` 有 `loader/26.2` profile；Fabric API `0.155.2+26.2`（Modrinth / Fabric Maven）；intermediary `26.2` 存在；Yarn 26.2 条目为 0（倾向官方 Mojmap） |
| Forge 26.2 | **是** | promotions `26.2-latest=65.0.9`；`forge-26.2-65.0.9-userdev.jar` 可拉取 |
| Folia 26.2 | **否** | Fill API v3 Folia 版本树仅见 `26.1` / `1.21` 系，无 `26.2` |
| NeoForge / Sponge 26.2 | **不在本期** | 父规格 §2.2 / §3；T1 不冻结 |

**硬结论**：Paper / Fabric / Forge 三格均可开 T2；Folia 26.2 不得建格；NeoForge/Sponge 不进 FR-16。

## 2. 有效矩阵

| 维度 | 服务端产品 | 客户端产品 / 伴侣 | JDK | 说明 |
|---|---|---|---|---|
| MC 26.2 · Paper | Bukkit 产品插件 + 验收插件 | Fabric / Forge 客户端产品 + 验收伴侣 | **Java 25+**（Paper 官方 minimum） | 与 1.21.1 同端口契约 |
| MC 26.2 · Fabric | 服务端 mod | Fabric 客户端产品 + 验收伴侣 | 构建/真服按 Loom/MC 要求（建议与 Paper 对齐 25+） | 独立 includeBuild |
| MC 26.2 · Forge | 服务端 mod | Forge 客户端产品 + 验收伴侣 | 按 Forge 26.2 工具链（T2 实装时再钉死 Gradle/FG） | **自有 launcher**，禁止根嵌套 gradlew |
| Folia / NeoForge / Sponge · 26.2 | — | — | — | **不建格** |

复用验收入口：`:runP2RealServerAcceptance` **不**扩张 26.2；26.2 走全 lane `:runRealServerAcceptance` 子集，矩阵轨 **R7**（公共三场景：`product-handshake` / `product-roundtrip` / `client-hud`）。

## 3. 冻结版本、制品与来源标识

核对日：**2026-07-26**。来源标识用于追溯，文档不存裸下载口令以外的密钥。

| 目标 | 冻结版本 / 制品 | SHA-256 / 锁定方式 | 来源标识 |
|---|---|---|---|
| Minecraft 26.2 | 正式版 `26.2`（stable） | 游戏本体由 loader 解析 | Fabric Meta `versions/game` |
| Paper 26.2 | build **71**；`paper-26.2-71.jar`；61,744,713 字节 | `36fee4f3a7020eb2e2d6f8d70d849beaf0f024d86f09302b9ccf2d96f266127e` | Paper Fill API v3：`projects/paper/versions/26.2/builds/71`；通道 **BETA** |
| Fabric Loader | `0.19.3` | 依赖坐标锁定 | Fabric Meta `versions/loader/26.2` |
| Fabric intermediary | `26.2` | 依赖坐标锁定 | Fabric Meta intermediary |
| Fabric API | `0.155.2+26.2` | 依赖坐标锁定（Maven / Modrinth `fabric-api-0.155.2+26.2.jar`） | Fabric Maven metadata + Modrinth project `P7dR8mSH` |
| Yarn / 映射 | **无 Yarn 26.2 发布** | T2 默认走 **官方 Mojmap**（与现代 Fabric 车道一致）；若后续出现 Yarn 再评估 | Fabric Meta `versions/yarn` 对 26.2 计数 0 |
| Forge 26.2 | `26.2-65.0.9`（promotions latest） | 依赖坐标锁定；userdev 已确认可下载 | Forge promotions_slim + Maven `forge-26.2-65.0.9` |

受控制品启动前必须校验冻结哈希与大小；**禁止**跟随 Paper/Forge latest。Paper 当前无 STABLE 通道构建——冻结在 BETA build 71，T2 开工前若需升 build，必须更新本表哈希后再动代码。

### 3.1 关键运行约束

- **Paper 26.2 官方 `java.minimum = 25`**。本机真服 / CI 必须提供 JDK 25+（建议 `MPMT_JAVA25_HOME`）；不得用 21 跑 Paper 26.2 宿主。
- L0–L2 仍 **`--release 8`**（ADR-0004）；仅 26.2 平台车道抬高工具链。
- Forge 26.2 的 Gradle / ForgeGradle / JDK 精确三元组在 T2 建工程时钉死并回填本表 §4（T1 仅锁定 Forge 版本坐标与「自有 launcher」约束）。

## 4. 构建车道（T2 实现时遵守）

| MC | 工程路径 | launcher | 备注 |
|---|---|---|---|
| Paper/Bukkit 26.2 | `platform/bukkit/26.2` | 根 `./gradlew :platform:bukkit:26.2:…` | 对齐 1.21.1 布局；产物名 `mpmt-bukkit-26.2-$VERSION` |
| Fabric 26.2 | `platform/fabric/26.2` | 独立 includeBuild | Loom + Loader 0.19.3 + API 0.155.2+26.2 |
| Forge 26.2 | `platform/forge/26.2` | **`./platform/forge/26.2/gradlew` 自有 wrapper** | 禁止根嵌套；FG/Gradle 版本 T2 钉死 |

L4：`version-api` + `v26_2` 实现；运行期探测 MC 版本选 `v26_2`。

## 5. 验收（实现后）

- R7 合规 `SERVER-GAMETEST-REPORT v2`：含 `MATRIX R7` / `RUN_ID` / 制品哈希；公共三场景恰好一次 PASS；末行 `RESULT PASS`。
- Paper / Fabric / Forge 三格至少各一份可复核归档报告。
- 用户第三期实机最终确认通过后，才可由 `sdd-release-version` 将 FR-16 标 `已交付@v…`。
- **不得**因 Folia/NeoForge/Sponge 无 26.2 而宣称全平台宇宙通过。

## 6. 任务拆分

- [x] T1 · 存在性确认 + 本冻结规格（本文件）
- [ ] T2 · 三车道独立工程（仅 Paper/Fabric/Forge）
- [ ] T3 · `version-api` + `v26_2` + 纯 JVM 单测
- [ ] T4 · R7 矩阵轨与报告契约
- [ ] T5 · 用户第三期实机确认
- [ ] 文档同步：PRD FR-16、ARCHITECTURE、CHANGELOG；必要时补 ADR

## 7. 风险 / 待定

- Paper 26.2 构建长期停在 BETA 通道：升 build 必须重冻哈希。
- **JDK 25** 对本仓库既有 JDK 8/17/21 矩阵是新增轴；CI / 开发机未装 25 则真服无法跑。
- Fabric 无 Yarn 26.2：统一 Mojmap，避免混映射。
- Forge 26.2 FG/Gradle 代际可能高于 1.21.1 车道，T2 禁止与根工具链混用。
- Folia 无 26.2：区域调度回归仍锚 1.20.1 / 既有 R6，不纳入 R7。
