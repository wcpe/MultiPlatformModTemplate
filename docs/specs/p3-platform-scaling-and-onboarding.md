# 功能规格：第三期 · 平台规模化与对外上手

> 状态：开发中　·　关联 PRD：FR-16、FR-17、FR-18　·　基线：`v0.2.0`　·　本轮对账范围：`v0.2.0..HEAD`

## 1. 背景与目标

第三期主题是"沿平台与版本轴继续铺 + 对外可用"。当前状态：

- P2（FR-12）已交付 `@v0.2.0`：1.21.1 / 1.12.2 跨版本工具链隔离、CatServer 实跑、R1–R6 合规矩阵、用户第二期实机确认通过。
- 26.2 三车道、`v26_2`、R7 目录与严格报告门已完成本地实现；当前候选提交已以冻结 Paper build 71 生成 Paper、Fabric、Forge 同轮 `p3-r7-1787686232087` 报告并通过根 `:runP3R7Gate`。依 ADR-0023，此证据已完成 FR-16 的最终自动化验收；FR 状态仍待随 P3 发布统一流转。
- `docs/VERSIONING.md`、`.github/RELEASE_TEMPLATE.md`、README 的克隆入口及 Counter 上手指南 / 纯 L0 示例已进入仓库；仓库已实际启用为公开 GitHub Template。当前候选提交已在干净克隆复现换名与 Counter 纯 JVM 测试（本机预热缓存），但尚无 `v0.3.0` 或 GitHub Release。

本规格冻结第三期"做什么、不做、谁先谁后、算什么齐"，**不写实现**；实现按规格走 `sdd-develop-feature` / `sdd-release-version`。

### 1.1 实施前提

- 基线为 `v0.2.0`；本轮文档仅对账该 tag 至当前 `HEAD` 的六条变更，不将旧基线提交号或特性分支名当作现状。
- 根 Gradle wrapper 已为 **9.6.1**；26.2 的平台车道须使用 Java 25。L0–L2 仍维持 Java 8 字节码。
- 严格遵守 SCOPE / scope-discipline §2–§3：不为未来预留空壳、版本接口随 26.2 等需要时再新增 `v26_2` 实现。

## 2. 范围（做什么 / 不做）

### 2.1 范围内

- **FR-16 · 26.2 版本适配**：在已落地的 L4 `version-api` + `vX_Y` 机制（ADR-0003）下，为有效平台子集实现 `v26_2` 与 R7 验收；当前代码、构建和真服证据必须一并闭环。
- **FR-17 · 脚手架发布与版本化**：仓库根元信息（README、CHANGELOG 段位规则、`/.github/`、`/docs/VERSIONING.md` 等）使外部用户一眼看得懂版本节奏；不引入新工具链，沿用现有 `:collectReleaseArtifacts` + `git tag vX.Y.Z`。公开 GitHub Template 已实际启用；目标版本的 GitHub Release 仍是独立外部操作。
- **FR-18 · 上手文档与示例**：在 `docs/` 下提供"克隆模板 → 在 L0 写一个最小玩法"路径文档 + **Counter** 非产品级示例作骨架范本；Counter 的生命周期必须包含异步持久化、实体归属消息调度及玩家离开时的周期句柄释放；不增加产品玩法。

### 2.2 范围外（不属于本期）

- 26.2 + NeoForge / 26.2 + Sponge 等不真实存在的格子（按 PRD §5 + ADR-0021 非笛卡尔积硬约束）。
- FR-23 已是 P1 验收门；P3 只复用其 realserver v2 设施并新增 R7 矩阵目录，不重新定义 FR-23 的验收语义。
- 玩法产品化、生产服运维脚本、CI 流水线云迁移等（属第三期以外或更后期）。
- 在 26.2 没出现前预铺 `v26_2` 空骨架（违反 scope-discipline §3）。

## 3. 有效矩阵（26.2 / FR-16）

按 ADR-0003、PRD §5（非笛卡尔积）：

| 维度 | 服务端产品 | 客户端产品 / 伴侣 | 说明 |
|---|---|---|---|
| MC 26.2 · Paper | 产品插件 + 验收插件 | Fabric / Forge 客户端产品 mod + 验收伴侣 | 根子工程 `platform/bukkit/26.2`，任务路径 `:platform:bukkit:26.2:*` |
| MC 26.2 · Fabric | 服务端 mod | Fabric 客户端产品 mod + 验收伴侣 | 独立目录 `platform/fabric/26.2`，根 `includeBuild` 名 `platform-fabric-26.2` |
| MC 26.2 · Forge | 服务端 mod | Forge 客户端产品 mod + 验收伴侣 | 独立目录 `platform/forge/26.2` 与其自有 wrapper；根不嵌套调用，只消费既有制品和报告 |
| MC 26.2 · NeoForge / Sponge | **不属于本期**：NeoForge 仅锚 1.20.2、Sponge 与 26.2 当前不同步 | — | 见 §2.2 |

工具链隔离沿用 P2 的原则，但 P3 有独立冻结值：根 wrapper 为 Gradle **9.6.1**；Forge 26.2 必须使用 `platform/forge/26.2/gradlew`（Java 25、Gradle 9.6.1、ForgeGradle 7.0.31），禁止根嵌套 gradlew；Fabric 26.2 由根 includeBuild 编排，根先准备受控内部 JAR；具体坐标、哈希与命名策略以 [`fr-26_2-adapter.md`](fr-26_2-adapter.md) 为唯一权威位置。

复用现有 Gradle 入口：`:runP2RealServerAcceptance` 不扩张 26.2，**26.2 走全 lane 的 `:runRealServerAcceptance`**（NeoForge/Sponge 不阻断的同款聚合），与 ADR-0021 / 操作手册 §3 不冲突。

## 4. 设计概述

### 4.1 FR-16 · 26.2

- 入口为三个物理车道：Bukkit `platform/bukkit/26.2`（根子工程）、Fabric `platform/fabric/26.2`（独立 Loom includeBuild）与 Forge `platform/forge/26.2`（独立 ForgeGradle、自有 wrapper）。Folia、NeoForge、Sponge 不建 26.2 格。
- 三个平台按 L4 抽象挂接 `v26_2`，运行期探测 MC 版本选中该实现；26.1+ 的无混淆命名规则见 ADR-0022，而非已被取代的 ADR-0016。
- `MatrixScenarioCatalog` 已定义 R7 的三个 required 场景：`product-handshake`、`product-roundtrip`、`client-hud`。根 `:runP3R7Build` 与 `:runP3R7RealServerAcceptance` 分别检查三车道产物与同一轮报告，`:runP3R7Gate` 聚合两者；报告门同时校验 `MATRIX R7`、`RUN_ID`、制品哈希、required 场景与最终结果。当前候选提交已通过冻结 Paper build 71 的同轮 `p3-r7-1787686232087`；依 ADR-0023，该严格门是 FR-16 的最终自动化验收。
- 验收：每条 26.2 realserver lane 产生当前 R7 的 `RESULT PASS`，同轮严格报告门通过即完成 FR-16 的最终自动化验收（ADR-0023）。

### 4.2 FR-17 · 脚手架发布与版本化

- 仓库元信息：`.github/RELEASE_TEMPLATE.md`、`docs/VERSIONING.md`（SemVer 节奏 + `:collectReleaseArtifacts` 产出与 `git tag` 关系 + 路线图链接）。
- 现有 `tools/README.md`（renameScaffold 用法）已齐；本规格只补"产物如何对外发布"一节，不重写工具。
- 范围明确：公开 GitHub Template 的"复刻提示"由 README 顶部"从模板起步"段承担，不引入 GitHub Actions / 云端流水线（属第三期外）。

### 4.3 FR-18 · 上手文档与示例

- 新增 `docs/HOWTO-CLONE-AND-WRITE-PLAY.md`：克隆 → `renameScaffold`（dry-run → 写盘）→ 在 L0 写一个最小玩法域（Counter：玩家加入时异步持久化首次加入与计数、按实体归属发消息、离开释放周期句柄；非产品玩法）→ 编译并运行纯 JVM 测试。指南同时说明使用者如何借用既有 L3 范本，把自己的产品域接入目标平台并在真实 Paper/Fabric/Forge 服验证。
- 新增 `examples/counter`（域包 + 接入范本）：参考 ADR-0015 域组织，不预建其它空域骨架；可被 `renameScaffold` 视为可选复制项。
- 文档走 `docs/specs/_template` 五段式：背景 / 范围 / 设计 / 任务拆分 / 验收；与 P2 `p2-version-matrix.md` 同结构。

## 5. 任务拆分（确认后执行）

- [x] T1 · 落 `docs/specs/fr-26_2-adapter.md`，冻结 26.2 工具链 / 制品 / 受控制品哈希（2026-07-26 确认 Paper/Fabric/Forge 均存在；Folia 无 26.2）
- [x] T2 · platform-bukkit 26.2 + Fabric 26.2 + Forge 26.2 三车道独立工程（当前根 P3 构建门与 Forge 自有 wrapper 打包均已通过；Folia 不建）
- [x] T3 · `version-api` + `v26_2` 实现（Bukkit / Fabric / Forge）；运行期探测装配与当前构建 / 纯 JVM 验证已通过
- [x] T4 · R7 矩阵轨 + realserver v2 报告契约（公共三场景、严格当前报告校验与冻结 Paper build 71 三车道同轮 `p3-r7-1787686232087` 已通过）
- [x] T5 · ADR-0023 的 R7 最终自动化验收（26.2 真实 Paper、Fabric、Forge 三车道）
- [ ] T6 · 模板元信息与远端发布（仓库内 `docs/VERSIONING.md` + `.github/RELEASE_TEMPLATE.md` + README 入口及公开 GitHub Template 已就绪；`v0.3.0` / GitHub Release 尚未创建）
- [x] T7 · 上手指南 `docs/HOWTO-CLONE-AND-WRITE-PLAY.md` + `examples/counter` 示例域与接入范本（以 `79bf3c9` 创建的干净克隆已通过换名 dry-run、写盘与 `:core:domain:compileJava :examples:counter:compileJava :examples:counter:test`；使用本机预热缓存）
- [x] T8 · 文档同步：PRD FR-16/17/18 状态、ARCHITECTURE §6 / §2.5、ADR 索引与 CHANGELOG 已对账本轮变更；不修改 API，因为没有公共契约变化

## 6. 验收标准

- **FR-16**
  - `:runRealServerAcceptance` 含 26.2 车道且 `RESULT PASS`（R7）；归档报告存在 `MATRIX R7 / RUN_ID / 制品哈希 / required 三场景恰好一次 PASS`。
  - 在同一 `RUN_ID` 下，使用冻结 Paper build 71 生成 Paper、Fabric、Forge 三车道当前报告，并由 `runP3R7Gate` 验证通过。
  - 同轮 R7 报告通过 `:runP3R7Gate` 的严格验证（ADR-0023）；随后才可随发布标 `已交付@vX.Y.Z`。
- **FR-17**
  - 仓库元信息齐：README 顶部"从模板起步"段 + `docs/VERSIONING.md` + `.github/RELEASE_TEMPLATE.md`；外部用户无须看仓库根 README + CHANGELOG + docs/INDEX 三处才能拼出发布流程。
  - 不引入新 CI / 云端流水线。
  - 公开 GitHub Template 已实际启用；目标版本的 `v0.3.0` / GitHub Release 仍须在验收完成后实际创建，本地 tag / 文档存在不替代该远端操作。
- **FR-18**
  - `docs/HOWTO-CLONE-AND-WRITE-PLAY.md` 在干净工作区能照做完：克隆 → renameScaffold → 参照 Counter 编写 L0 玩法域 → 编译并通过纯 JVM 测试。该路径已在本机预热缓存下的干净克隆复现，不能宣称为冷缓存或新机器验证。
  - `examples/counter` 走纯 JVM 单测（不依赖任何 L3 平台代码）；其不预置跨平台 L3 装配。使用者把自己的产品域接入既有 L3 范本后，再自行构建目标平台产物并完成真服验证。
- **每项 FR 单独通过前不得互相预标交付**。第三期发版在三项全部已交付后由 `sdd-release-version` 统一落 `v0.3.0`、tag，**不预标、不 push**。

## 7. 风险 / 待定

- Forge 26.2 的 Java 25 / Gradle 9.6.1 / ForgeGradle 7.0.31 已冻结；根门只检查其自有 wrapper 生成的制品与报告，不能嵌套启动该 wrapper。
- R7 历史报告已因冻结制品与严格报告门更新而失效；仅当前候选提交的同轮报告经 `:runP3R7Gate` 通过才满足 ADR-0023。不得以 P2 R1–R6 归档、单元测试、构建产物或未经过严格门的报告替代该证据。
- 上手示例已定为 Counter：玩家加入时异步记录首次加入与计数，消息按实体归属调度，玩家离开时释放周期句柄。
- `renameScaffold` 已存在，本规格不修改它的参数；如示例需要 `mpmt.scaffold.rewriteChannels=true`，文档里写明建议在产品化时再开。
