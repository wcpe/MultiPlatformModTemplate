# ADR-0025：构建插件统一 WCPE Loom（`top.wcpe.loom` 1.17.1）并收敛 Gradle 车道

## 状态

已接受（部分取代 [ADR-0021](0021-p2-version-matrix-toolchain-isolation.md) 的工具链隔离；车道形态由 [ADR-0026](0026-single-build-subproject-unification.md) 决定）

## 背景

此前 mod 平台各车道按"版本隔离工具链"各自持有构建插件与 Gradle：fabric 三车道用自 fork fabric-loom（1.17-wcpe-4）；forge 1.21.1 / 1.20.1 用 ForgeGradle 6.0.54，forge 1.12.2 用 ForgeGradle 3（0.197），neoforge 1.20.2 用 NeoGradle userdev 7.0.116；forge/26.2 用 ForgeGradle 7.0.31。ForgeGradle / NeoGradle 各代与新代 Gradle 的兼容墙，迫使 Forge / NeoForge 车道长期维持自有 wrapper 或外部 Gradle（forge/1.20.1 曾依赖 PATH 上的 Gradle 8.14.5，无法进入根复合构建）；[ADR-0021](0021-p2-version-matrix-toolchain-isolation.md) 的"Forge 工具链按版本物理隔离"决策即因该版本墙而生。

本 ADR 的初版采纳 **essential-gradle-toolkit（EGT）** 的 `gg.essential.loom`（architectury-loom fork）以消除该墙。本版改写为 **WCPE Loom**：

- WCPE Loom 是 architectury-loom（`architectury` 分支，Loom 1.17 代系）的定制 fork，直接上游基底为 architectury-loom 的 `026ce830`（含上游 "Fix dev launch issues in unobfuscated Forge" 修复）。
- 相对 EGT，它**重新接回老版本 Forge（1.8–1.16，ForgeGradle 2 时代）支持链**（本项目用到的是 1.12.2），并叠加并发缓存、配置缓存兼容、legacy 支持迁移等补丁；本仓所需的 26.2 无混淆管线修复（`merge` 步骤）已在该线上游基底内，**不再需要 mavenLocal 本地开发版**。
- 插件 id 为自有域名 `top.wcpe.loom`（并保留 `dev.architectury.loom` 等兼容别名，本仓不使用）；实现坐标 `dev.architectury:architectury-loom`，发布仓库 `maven.wcpe.top/repository/maven-releases/`。

## 决策

1. **mod 平台构建插件统一为 `top.wcpe.loom` 1.17.1**（WCPE Loom，architectury-loom 1.17 线 fork）：fabric 1.20.1 / 1.21.1 / 26.2；forge 1.20.1 / 1.21.1 / 1.12.2（走 legacy 链路）；forge 26.2（无混淆）；neoforge 1.20.2。插件由根 `pluginManagement` 单点 pin，仓库为 `maven.wcpe.top/repository/maven-releases/`（无需鉴权）。
2. **essential-gradle-toolkit 退役**：`gg.essential.loom` 1.13.44 与 mavenLocal 本地开发版 `1.15-wcpe-latest` 一并移除；不再需要根或车道的 `mavenLocal` 兜底（`mavenLocal` 白名单仅保留给 mc-testkit 坐标）。
3. **Gradle 全车道统一 9.6.1**（不变）：车道不再各自维护 wrapper 版本。构建守护 JVM **≥ 25**——26.2 两车道的 loom 管线与车道脚本均在配置期硬校验，具体由 [ADR-0026](0026-single-build-subproject-unification.md) 决策 4 规定为"根构建统一 JDK 25"。Java 目标版本不变、由 toolchain 承担（fabric 26.2 / forge 26.2 = 25、forge 1.21.1 = 21、forge 1.20.1 / neoforge 1.20.2 / fabric 1.20.1 / sponge = 17、forge 1.12.2 = 8）。
4. **26.2 无混淆链路**：fabric/26.2 与 forge/26.2 以 `fabric.loom.disableObfuscation=true` 走无映射链路（与 no-remap 插件变体等价）；无混淆 Forge 的预补丁复用 Forge 自己 mcp_config 的 `merge` 步骤产出 vanilla 合并 jar（上游修复，见背景），无需本地开发版。26.1+ 的命名策略仍由 [ADR-0022](0022-unobfuscated-minecraft-naming-policy.md) 裁决。
5. **legacy Forge（1.12.2）由 WCPE Loom 的 legacy 链路承担**：判定为"userdev 配置无 `mcp` 段"，据此合成 ForgeGradle 2 形态 manifest；车道必须声明 Pack200 provider（`forge.pack200Provider`）且**不得**使用 no-remap 变体——两者是 legacy 链路成立的前置条件。WCPE Loom 文档声称 legacy 覆盖 1.8–1.16，本项目只承诺并验收 1.12.2。
6. **车道形态由 [ADR-0026](0026-single-build-subproject-unification.md) 决定**：所有 mod 平台车道（含 sponge）作为根构建普通子模块，删除平台车道的一切 `includeBuild` / 车道 `settings.gradle.kts` / 自有 wrapper / 反向 include 与依赖替换。本 ADR 只决定"用哪个插件、什么版本、什么链路"。
7. **根门禁契约不变**：产物路径（`build/reobfJar/output.jar`、`build/reobfShadowJar/output.jar`、`build/libs/…` 产品命名）与真服验收任务 / 属性 / 编排逐项等价保留。

## 理由

- 统一构建插件与 Gradle 版本消除 ForgeGradle / NeoGradle 版本墙：各车道不再维护多代 Gradle / 插件组合。
- 迁移到 WCPE Loom 而非停留在 EGT：EGT 无 legacy Forge（1.8–1.16）链路，1.12.2 车道只能靠 ForgeGradle 3 特殊处理；WCPE Loom 把 legacy 链路接回同一插件，且 26.2 无混淆管线的缺口修复已入其上游基底，使"全车道单插件"真正成立（这正是 [ADR-0026](0026-single-build-subproject-unification.md) 单构建的前提）。
- 用正式版而非本地开发版：`1.17.1` 为 tag 发布的正式版，其 `src/main/java` 与开发线 HEAD 零差异，CI / 他人机器可直接从远程仓库解析，不再依赖"仅维护者本机可构建"的 mavenLocal 制品。
- 插件 id 换为 `top.wcpe.loom`：采用 fork 的自有 id（其兼容别名仍指向同一实现），避免与 EGT 的坐标混淆。

## 后果

- 正面：mod 平台构建插件与 Gradle 版本单一化；自 fork fabric-loom（1.17-wcpe-4）与 ForgeGradle / NeoGradle 各代全部退役；forge/1.20.1 回归根构建统一车道。
- 正面：forge/26.2 从"mavenLocal 本地开发版 + 自有 wrapper"升级为"正式版插件 + 根构建子模块"，[ADR-0026](0026-single-build-subproject-unification.md) 的单构建因此可行。
- 负面：历史真服验收证据因构建插件更换而失效，须按新构建重取（旧制品不再可比）。
- 约束：构建守护 JVM ≥ 25（26.2 车道的配置期硬校验），根构建统一 JDK 25。
- 约束：插件版本固定为 `1.17.1`；升级需复验 8 条车道的构建、契约与真服报告门（尤其 legacy 1.12.2 与 26.2 无混淆两条链路）。
- 约束：**已知覆盖缺口**——WCPE Loom 自带的集成矩阵没有 fabric 1.21.1 与 forge 1.21.1 的用例（机制上与已覆盖版本同路径）；这两条车道的结论只能由本仓验收给出。26.x 的 fork 侧测试在 JDK < 25 时静默跳过（视为通过），故 26.2 结论必须在 JDK 25 下取。

## 备选方案

- **全面 multi-version preprocess 架构**（Architectury 式统包 + 预处理）：Bukkit / Sponge 无 loom 可用，且与 [ADR-0026](0026-single-build-subproject-unification.md) 的子模块布局冲突——否决。
- **仅迁 fabric，Forge / NeoForge 车道不动**：达不到统一，版本墙与多代 Gradle 依旧——否决。
- **继续用 essential-gradle-toolkit（`gg.essential.loom` 1.13.44）**：无 legacy Forge 链路，1.12.2 无法并入单一插件；且 26.2 无混淆管线依赖本地开发版——否决。
- **沿用 mavenLocal 的 `1.15-wcpe-latest` 开发版**：违背"可复现构建"（仅维护者本机可构建），且 1.15 线为冻结归档线——否决。
- **插件构建工程改造为 buildSrc 以彻底消除 includeBuild**：见 [ADR-0026](0026-single-build-subproject-unification.md) 备选方案——否决（保留 `pluginManagement` 的 includeBuild）。
