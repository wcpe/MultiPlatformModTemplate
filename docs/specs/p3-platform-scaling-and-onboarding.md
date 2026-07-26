# 功能规格：第三期 · 平台规模化与对外上手

> 状态：草拟　·　关联 PRD：FR-16、FR-17、FR-18　·　分支：feature/p3-platform-scaling-and-onboarding

## 1. 背景与目标

第三期主题是"沿平台与版本轴继续铺 + 对外可用"。当前状态：

- P2（FR-12）已交付 `@v0.2.0`：1.21.1 / 1.12.2 跨版本工具链隔离、CatServer 实跑、R1–R6 合规矩阵、用户第二期实机确认通过。
- 锚点版本仍差 `26.2`（FR-16）；模板化发布与上手文档（FR-17 / FR-18）未落地。
- 现状：开发者拿到的脚手架在 `renameScaffold` / VERSION 注入 / 各平台产物上已可用，但缺少 26.2、最新标签与第三期示例作为模板推广的硬支撑。

本规格冻结第三期"做什么、不做、谁先谁后、算什么齐"，**不写实现**；实现按规格走 `sdd-develop-feature` / `sdd-release-version`。

### 1.1 实施前提

- 在分支 `feature/p3-platform-scaling-and-onboarding` 上。
- 基线：v0.2.0（`v0.2.0` tag 起，HEAD `e90882e` 当前指向 `chore(release): 发布 0.2.0`）。
- 严格遵守 SCOPE / scope-discipline §2–§3：不为未来预留空壳、版本接口随 26.2 等需要时再新增 `v26_2` 实现。

## 2. 范围（做什么 / 不做）

### 2.1 范围内

- **FR-16 · 26.2 版本适配**：在已落地的 L4 `version-api` + `vX_Y` 机制（ADR-0003）下，新增 `v26_2` 实现，覆盖有效平台子集（按 §3 矩阵）。
- **FR-17 · 脚手架发布与版本化**：仓库根元信息（README、CHANGELOG 段位规则、`/.github/`、`/docs/VERSIONING.md` 等）使外部用户一眼看得懂版本节奏；不引入新工具链，沿用现有 `:collectReleaseArtifacts` + `git tag vX.Y.Z`。
- **FR-18 · 上手文档与示例**：在 `docs/` 下新增"克隆模板 → 在 L0 写一个最小玩法"路径文档 + 一个**非产品级**示例（示例域：Arena / Announcer / Counter 选其一）作骨架范本；不增加产品玩法。

### 2.2 范围外（不属于本期）

- 26.2 + NeoForge / 26.2 + Sponge 等不真实存在的格子（按 PRD §5 + ADR-0021 非笛卡尔积硬约束）。
- FR-23 已成 P1 验收门，不在本期再调整；如 26.2 车道需要新加 realserver lane，由 §3 落地任务走 `sdd-develop-feature` 后续增量。
- 玩法产品化、生产服运维脚本、CI 流水线云迁移等（属第三期以外或更后期）。
- 在 26.2 没出现前预铺 `v26_2` 空骨架（违反 scope-discipline §3）。

## 3. 有效矩阵（26.2 / FR-16）

按 ADR-0003、PRD §5（非笛卡尔积）：

| 维度 | 服务端产品 | 客户端产品 / 伴侣 | 说明 |
|---|---|---|---|
| MC 26.2 · Paper | 产品插件 + 验收插件 | Fabric / Forge 客户端产品 mod + 验收伴侣 | 与 1.21.1 同样的端口 |
| MC 26.2 · Fabric | 服务端 mod | Fabric 客户端产品 mod + 验收伴侣 | Loom `26.2` |
| MC 26.2 · Forge | 服务端 mod | Forge 客户端产品 mod + 验收伴侣 | ForgeGradle 现役版本需冻结到本规格 |
| MC 26.2 · NeoForge / Sponge | **不属于本期**：NeoForge 仅锚 1.20.2、Sponge 与 26.2 当前不同步 | — | 见 §2.2 |

工具链隔离与 P2 一致：Forge 26.2 必须**自有 launcher**（参 ADR-0021 §4.1），禁止根嵌套 gradlew；JDK / Gradle / ForgeGradle / Fabric Loader 版本在落地阶段由 `sdd-develop-feature`（FR-16）冻结入 `docs/specs/fr-26_2-adapter.md` 并对应填版本表与 SHA-256；本规格只承诺占位与冻结流程，不替代具体冻结值。

复用现有 Gradle 入口：`:runP2RealServerAcceptance` 不扩张 26.2，**26.2 走全 lane 的 `:runRealServerAcceptance`**（NeoForge/Sponge 不阻断的同款聚合），与 ADR-0021 / 操作手册 §3 不冲突。

## 4. 设计概述

### 4.1 FR-16 · 26.2

- 入口为各平台 26.2 车道：Bukkit（`platform-bukkit/26.2`，独立 includeBuild 同 1.21.1 layout）、Fabric（`platform-fabric/26.2`，独立 Loom includeBuild）、Forge（`platform-forge/26.2`，独立 ForgeGradle includeBuild、`gradlew` 自有 wrapper）。
- `version-api` 增加 `v26_2` 实现（platform-{bukkit,fabric,forge} 均按 L4 抽象挂接），运行期探测 MC 版本选 `v26_2`。
- 协议字节、realserver v2 与 R1–R6 矩阵契约在现有基础上加 R7 = 26.2 公共三场景 PASS（仅第一阶段需要的子集）；新建独立 spec `docs/specs/fr-26_2-adapter.md` 冻结契约与版本，**本规格不替它写**。
- 验收：26.2 realserver lane 报告 `RESULT PASS` + 用户第三期实机最终确认（参 P2 PRD §7 / §11.1 等价约定）。

### 4.2 FR-17 · 脚手架发布与版本化

- 仓库元信息：`.github/RELEASE_TEMPLATE.md`、`docs/VERSIONING.md`（SemVer 节奏 + `:collectReleaseArtifacts` 产出与 `git tag` 关系 + 路线图链接）。
- 现有 `tools/README.md`（renameScaffold 用法）已齐；本规格只补"产物如何对外发布"一节，不重写工具。
- 范围明确：模板仓库 / 对外发布的"复刻提示"由 README 顶部"克隆本模板"段承担，不引入 GitHub Actions / 云端流水线（属第三期外）。

### 4.3 FR-18 · 上手文档与示例

- 新增 `docs/HOWTO-CLONE-AND-WRITE-PLAY.md`：克隆 → `renameScaffold`（dry-run → 写盘）→ 在 L0 写一个最小玩法域（Counter：玩家加入递增、首次加入时间持久化、离开释放句柄；非产品玩法）→ 在每个目标平台 `shadowJar`/loader build → 在真实 Paper/Fabric/Forge 服跑通。
- 新增 `examples/counter`（域包 + 最小装配）：参考 ADR-0015 域组织，不预建其它空域骨架；可被 `renameScaffold` 视为可选复制项。
- 文档走 `docs/specs/_template` 五段式：背景 / 范围 / 设计 / 任务拆分 / 验收；与 P2 `p2-version-matrix.md` 同结构。

## 5. 任务拆分（确认后执行）

- [x] T1 · 落 `docs/specs/fr-26_2-adapter.md`，冻结 26.2 工具链 / 制品 / 受控制品哈希（2026-07-26 确认 Paper/Fabric/Forge 均存在；Folia 无 26.2）
- [ ] T2 · platform-bukkit 26.2 + Fabric 26.2 + Forge 26.2 三车道独立工程（仅存在的三格；Folia 不建）
- [ ] T3 · `version-api` + `v26_2` 实现（Bukkit / Fabric / Forge）；运行期探测装配；纯 JVM 单测
- [ ] T4 · R7 矩阵轨 + realserver v2 报告契约（`MatrixScenarioCatalog` 加 `R7` 公共三场景 required；不改其它矩阵轨）
- [ ] T5 · 用户第三期实机最终确认（26.2 真实 Paper + Fabric/Forge 服实测）
- [x] T6 · 模板元信息（`docs/VERSIONING.md` + `.github/RELEASE_TEMPLATE.md` + README"克隆本模板"段强化）
- [x] T7 · 上手指南 `docs/HOWTO-CLONE-AND-WRITE-PLAY.md` + `examples/counter` 示例域与最小装配
- [ ] T8 · 文档同步：PRD FR-16/17/18 状态；ARCHITECTURE §6 / §2.5；新增 / 取代 ADR（如 26.2 适配衍生新决策）；CHANGELOG；同步 sdd-skills 与项目 `.claude/rules`

## 6. 验收标准

- **FR-16**
  - `:runRealServerAcceptance` 含 26.2 车道且 `RESULT PASS`（R7）；归档报告存在 `MATRIX R7 / RUN_ID / 制品哈希 / required 三场景恰好一次 PASS`。
  - 用户第三期实机最终确认通过（明文），否则不标 `已交付@vX.Y.Z`。
- **FR-17**
  - 仓库元信息齐：README 顶部"克隆本模板"段 + `docs/VERSIONING.md` + `.github/RELEASE_TEMPLATE.md`；外部用户无须看仓库根 README + CHANGELOG + docs/INDEX 三处才能拼出发布流程。
  - 不引入新 CI / 云端流水线。
- **FR-18**
  - `docs/HOWTO-CLONE-AND-WRITE-PLAY.md` 在干净工作区能照做完：克隆 → renameScaffold → 实现 Counter → 三平台 `shadowJar` / loader build → 真服联通。
  - `examples/counter` 走纯 JVM 单测（不依赖任何 L3 平台代码）；真服实跑为示例维度、可选。
- **每项 FR 单独通过前不得互相预标交付**。第三期发版在三项全部已交付后由 `sdd-release-version` 统一落 `v0.3.0`、tag，**不预标、不 push**。

## 7. 风险 / 待定

- 26.2 在 Forge 的 ForgeGradle / JDK 可能跨代；启动前必须冻结工具链 + SHA-256（与 P2 §3 同模板）。
- 若 26.2 在某平台当下不可用（官方未到位 / NeoForge 节奏），按 §3 不强铺；T2 先确认存在性。
- 上手示例域名取 Arena / Announcer / Counter 中哪一个需进一步评审（避免争议可改 Counter，是最朴素的事件 → 调度 → 持久化三件套）。
- `renameScaffold` 已存在，本规格不修改它的参数；如示例需要 `mpmt.scaffold.rewriteChannels=true`，文档里写明建议在产品化时再开。
