# 功能规格：GitHub Actions 交付治理

> 状态：开发中　·　关联 PRD：FR-32　·　分支：`dev`

## 1. 背景与目标

仓库已经是公开 GitHub Template，但尚未配置 GitHub Actions。现有的 `:buildAll`、各独立 Gradle 车道、静态检查和发布制品聚合只能在维护者本机验证，拉取请求与推送没有远端可复核的质量结果。

维护者确认将本能力作为新的治理需求实施，不追溯改变第三期 FR-16 至 FR-18 的验收语义。尤其是：P3 R7 的最终自动化证据仍由 ADR-0023 所定义的本机真实进程 Gradle 门产生，不能把 GitHub Hosted Runner 的普通构建伪装成 R7 真服验收。

目标是在不引入凭证、不自动发布、不破坏独立 Gradle 车道的前提下，使模板开箱具备可复现的远端质量、发布、安全与依赖维护工作流。

## 2. 需求

### 2.1 范围内

- 新增 `ci.yml`：对 pull request、`main`/`dev` 推送和手动触发执行完整可构建验证。
  - 固定检出公开 `wcpe/mc-testkit` 的 `v0.5.1` 提交并发布到 Runner 的 Maven Local，复用本仓已有的本地回退，不依赖当前不可用的线上 plugin marker。
  - 明确安装 Java 8、17、21、25；Forge 1.20.1 固定使用 GitHub Actions 安装的 Gradle 8.14.5，Forge 1.12.2、Forge 1.21.1、Forge 26.2 与 NeoForge 1.20.2 只通过各自 wrapper 构建；根 Gradle 9 不嵌套调用任何独立车道。
  - 在独立车道产物齐备后执行根 `:buildAll`，并校验 Counter、E2E harness 与 bot 模板可构建/静态检查。
  - 上传测试报告、静态检查报告和失败诊断；成功的默认分支运行上传可发布 `build/dist` 制品。
- 新增 `release.yml`：只允许维护者手动触发；校验输入 tag 与 `VERSION` 一致、重建并校验完整制品、上传 Release assets，然后创建 GitHub Release。
  - 不监听 tag push，不自动创建 tag，不发布 Maven 制品；实际发版仍须维护者先按 `sdd-release-version` 取得本地 release 提交与附注 tag，并明确推送 tag。
  - 仅该工作流声明最小必要的 `contents: write`；其余工作流保持只读权限。
- 新增 `dependency-review.yml`：在 pull request 上审查依赖变更，阻断高风险新增依赖。
- 新增 `codeql.yml`：在 pull request、默认分支推送、每周计划任务和手动触发时执行 Java/Kotlin 安全分析。
- 新增 `.github/dependabot.yml`：每周检查 Gradle 与 GitHub Actions 依赖更新。
- 将 CI/CD 入口、边界、产物与本机 R7 的区别同步到 PRD、架构/运维、版本说明、README、CHANGELOG、规则索引和规格导航。

### 2.2 范围外

- 不在 GitHub Hosted Runner 运行 `:runP3R7Gate`、`runRealServerAcceptance` 或其它需真实服务端与程序化客户端的真服门；这些继续由维护者本机经 Gradle 执行。
- 不新增 shell/PowerShell 验收入口，不在 Gradle 任务中嵌套 Gradle。
- 不写入或要求 Maven/NVD/Sonar 等外部服务凭证；不发布 Maven 制品。
- 不自动推送 tag、创建 release、合并 PR、修改分支保护或仓库权限；发布工作流只能由有权限的维护者显式触发。
- 不改变 P3 FR-16 的 R7 权威，也不以 CI 成功替代任何已定义的真服证据。

## 3. 设计

### 3.1 共用构建前置

工作流使用 Ubuntu Hosted Runner 和经完整提交 SHA 固定的官方 GitHub/Gradle Actions。所有普通工作流只授予 `contents: read`；发布工作流单独声明 `contents: write`。

`mc-testkit` 的本地回退在每个需要解析根插件的 job 开始时建立：检出固定 `v0.5.1` 提交，执行其 `publishToMavenLocal`，再构建本仓。这样 CI 与本机相同地只暴露该插件的两个指定 group，不会把任意本地 Maven 内容带入构建。

### 3.2 验证工作流

`ci.yml` 的单一质量 job 串行准备独立车道，保证所有发布制品都来自同一 commit：

1. 构建并发布固定的 mc-testkit 到 Maven Local。
2. 使用根 Gradle 生成 Forge 1.12.2 所需的 Java 8 共享 JAR；该步骤只生成受控输入，不启动任何独立 wrapper。
3. 分别切换到对应 Java 运行时，使用固定 Gradle 8.14.5 构建 Forge 1.20.1，并执行 Forge 1.12.2、Forge 1.21.1、Forge 26.2 和 NeoForge 1.20.2 的自有 wrapper；NeoForge 先由根任务准备受控输入。
4. 使用 Java 25 执行根 `:buildAll`，让根只消费上述已生成的制品和报告。
5. 构建 E2E harness，执行 bot 的锁文件安装、lint 与格式只读检查。
6. 无论成功失败都上传诊断；仅默认分支成功运行保存完整发布制品。

采用并发组取消被同一 PR/分支后续提交淘汰的旧运行，不取消 `main`、`dev` 与手动运行。

### 3.3 发布与安全工作流

`release.yml` 只接受一个形如 `vX.Y.Z` 的手动输入。它先检出该 tag、读取 `VERSION`，拒绝不匹配输入或已有同名 Release；随后复用完整构建步骤并将 `build/dist` 的 13 个产品 jar 作为 Release assets。它不生成版本号、不修改 CHANGELOG、不创建 tag。

依赖审查只读取 pull request 的依赖差异。CodeQL 只写入 GitHub 安全事件；Dependabot 仅创建更新 PR。它们均不读取仓库 secrets，也不以第三方密钥为前提。

### 3.4 决策记录

新增 ADR-0024，记录“远端持续集成是可复现质量门，而非 realserver 验收替代物”的长期边界；保留 ADR-0014 与 ADR-0023 的本机真服权威不变。

## 4. 任务拆分

- [x] 登记 PRD FR-32，并创建 ADR-0024。
- [x] 实现固定 mc-testkit 本地发布、全车道构建和报告归档的 CI 工作流。
- [x] 实现手动 Release 工作流与不可变 tag/version 校验。
- [x] 实现 PR 依赖审查、CodeQL 与 Dependabot。
- [x] 用 actionlint/工作流语法检查及本地对应 Gradle 门验证 Actions 配置。
- [ ] 推送到 `dev`，观察 GitHub Actions 运行并逐项修复至绿色。
- [x] 文档同步：PRD、ARCHITECTURE、OPERATIONS、VERSIONING、README、CHANGELOG、规则索引与规格导航。

## 5. 验收标准

- pull request 与 `main`/`dev` 推送能自动出现 CI 结果；同一分支的新提交会取消旧运行，但默认分支与手动运行不被取消。
- CI 在无预热 Maven Local 的 Hosted Runner 上固定构建 mc-testkit `0.5.1`，再完成五条独立 Forge/NeoForge 构建车道与 `:buildAll`；根 Gradle 未嵌套调用独立车道。
- CI 成功时产生且可下载完整 `build/dist`；失败时仍能下载测试、静态分析和 Gradle 诊断。
- E2E harness、bot 锁文件安装、lint 与格式检查通过。
- Release 工作流只能手动触发；不匹配的 tag/VERSION、已有 Release 或缺失制品会失败且不创建 Release；有效输入创建包含 13 个产品 jar 的 GitHub Release。
- PR 依赖审查、CodeQL 和 Dependabot 均可被 GitHub 识别并在预期事件触发。
- 不存在任何自动远端 R7 运行；P3 R7 的通过仍只引用本机 Gradle 证据，CI 绿不改变其验收权威。
- 远端 `dev` 的全部新工作流绿色后，才可将其作为后续发布流程的普通构建前置；正式发布仍须单独获得维护者授权。

## 6. 风险 / 待定

- 全矩阵首次冷缓存构建时间较长，且依赖 Mojang、Paper、Gradle Plugin Portal、Maven Central 等上游可用性；缓存只加速，不能作为通过证据。
- GitHub Hosted Runner 不具备本机真服验收的受控运行条件，故 R7 必须持续留在本机 Gradle 门。
- GitHub Environment 的人工审批与分支保护属于仓库设置，不能仅凭 workflow 文件启用；如需强制发布审批，维护者须后续在仓库设置中创建 `release` Environment。
