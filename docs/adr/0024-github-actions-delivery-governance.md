# ADR-0024：GitHub Actions 远端质量门与真服验收分离

## 状态

已接受

## 背景

本仓库作为公开 GitHub Template，需要让 pull request、默认开发分支和正式发布获得远端可复核的构建、静态检查、依赖审查与制品证据。此前第三期规格把云端流水线排除在产品实现范围之外，因而仓库没有任何 GitHub Actions 工作流。

同时，ADR-0014 规定 realserver 验收需要真实服务端和程序化客户端进程，ADR-0023 仅把该真实进程的同轮 R7 Gradle 报告作为 FR-16 的最终自动化证据。Hosted Runner 的普通构建不能替代这些验收。

根构建还依赖 `mc-testkit:0.5.1` 的本地 Maven 回退；线上 plugin marker 当前不可靠，新的远端工作流不能假设该仓库可用。

## 决策

1. GitHub Actions 承担远端持续集成、手动 Release、依赖审查、CodeQL 与依赖更新治理；质量构建使用 `:buildAll` 和独立 Gradle 车道。
2. 每个需要根构建的 job 固定检出 `wcpe/mc-testkit` 的 `v0.5.1` 提交，先执行 `publishToMavenLocal`，只向 Maven Local 提供本仓限定的插件坐标。
3. Forge 1.20.1 固定由 CI 安装的 Gradle 8.14.5 直接构建；Forge 1.12.2、Forge 1.21.1、Forge 26.2 与 NeoForge 1.20.2 始终由各自 wrapper 直接构建。根 Gradle 9 只消费其已生成制品，绝不嵌套启动独立车道。
4. 真服与 R7 仍只由维护者本机通过 Gradle 运行。CI 成功是可复现的构建质量证据，不是 realserver 通过或发布授权。
5. Release 只经维护者手动触发：先验证已推送 tag 与 `VERSION`，重建 13 个产品 jar 后创建 GitHub Release。工作流不创建 tag、不发布 Maven 制品。
6. 默认权限为只读；仅 Release job 声明 `contents: write`，CodeQL 仅声明 `security-events: write`。

## 理由

- 将多代 Gradle/JDK 车道显式写入 CI，避免 Hosted Runner 偶然使用错误 Java 或污染专属 loader 工具链。
- 固定公开 mc-testkit 提交可重现本机的最小 Maven 回退，又不需要密钥或不稳定远端 marker。
- 手动 Release 保留维护者对 tag、Release 和发布资产的外部状态授权，符合现有版本流程。
- 把真服证据和 CI 构建分开，避免将普通 `BUILD SUCCESSFUL` 误报为跨端真实环境验收。

## 后果

- 正面：模板用户可在远端看到统一的质量结果、可下载诊断和默认分支制品；依赖与静态安全审查有持续入口。
- 正面：发布资产从 tag 对应提交冷构建，避免复用不明来源的旧工作区产物。
- 负面：首次冷缓存需要下载多个 JDK、Gradle、loader 与 Minecraft 依赖，执行时间较长且受上游可用性影响。
- 负面：工作流文件本身不能设置分支保护或 Environment 审批；维护者需在仓库设置中配置这些保护。
- 约束：任何真服、R7 或发布授权的结论仍须依据其各自权威证据，不能仅引用 CI 绿灯。

## 备选方案

- 只运行根 `:buildAll`：无法在冷 Runner 提供 NeoForge 与跨代 Forge 所需的独立制品，否决。
- 让根 Gradle 嵌套启动独立 wrapper：违反 ADR-0007 和工具链隔离约束，否决。
- 在 Hosted Runner 运行 R7：不满足 ADR-0014 的真实服务端/客户端运行条件，否决。
- tag push 自动创建 Release：把外部发布授权隐含在推 tag 中，无法保留维护者显式确认，否决。
