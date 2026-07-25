# 验收编排：仅 Gradle（禁 sh）

> 用户硬约束：禁止用 shell 脚本做验收/严格门编排；对齐 AllinCore `realserver-acceptance`；允许 mc-testkit。

## 1. 禁止

- 新增或扩展 `scripts/*.sh`、`.ps1`、`.bat` 作为验收、严格门、多矩阵聚合的**用户入口**。
- 在 Gradle 任务 / 插件内再起 `gradlew` / `gradle` 子进程（会死锁 `.gradle`）。
- 把 `buildAll` 当作 版本矩阵门禁。

## 2. 允许 / 必须

- 用户入口：`./gradlew runRealServerAcceptance`、`./gradlew runVersionMatrixGate`、平台内 `runAcceptance*`、B 增强 `ensurePaperRealServerHost`、A 车道 `runMcTestkitSmoke` / `e2e*`。
- 编排实现：`build-logic/realserver-acceptance`（`PaperHostService` + 报告门禁）、任务图；根工程 `top.wcpe.mc-testkit` + `e2e/` 脚手架。
- 报告模型（如 `AcceptanceReportV2`）可保留；**入口**必须是 Gradle 任务，不是 shell。

## 3. 与产品边界

- 通用能力：`core-*` / `protocol` / `platform-*` 主源集。
- 验收编排：仅 build-logic + 测试模块 + 可选 mc-testkit；不进产品 jar。
