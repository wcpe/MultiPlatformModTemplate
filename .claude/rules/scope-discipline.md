# 范围纪律（防范围漂移 / 镀金）

> 依据 `docs/PRD.md` 的分期。**只做当前阶段该做的，不提前做、不顺手做。**

## 1. 第一期（MVP）只做

目标：**证明"逻辑/胶水完全分离"成立 + 基础网络全平台可用**。仅做以下（以 PRD §4 优先级 P1 列为准，含 FR-01~FR-11、FR-13~FR-15、FR-19~FR-31）：

- L0 功能域骨架：领域内核（基类型 + 平台端口接口集 Player/World/Scheduler/Message/Persistence/Transport/DataDirectory + **自有 EventBus**，接口 EventBusPort）+ 功能域（互不依赖、经 EventBus 协作，FR-31）。
- L1：core-runtime 编排（生命周期 / 特性注册 / 端口装配）、core-server / core-client 公共逻辑骨架、protocol 协议骨架 + 版本协商。
- L2：platform-spi（SPI + PlatformProvider Holder + ServiceLoader 发现）、FeatureGate 能力探测。
- L3：platform-bukkit（Bukkit 家族单一构建，含 Paper 与 **Folia**，Folia 经 FeatureGate 适配区域调度）、platform-sponge（SpongeGradle·独立 includeBuild）、platform-fabric（Loom·独立 includeBuild·双端）、platform-forge（ForgeGradle·独立 includeBuild·client/server 分离代理）、platform-neoforge（NeoGradle·1.20.2 固定 Gradle 8.14.5 自有 wrapper + 受控内部 JAR，根 Gradle 9 只校验其产物 / 报告）——**核心锚点 1.20.1**，全平台跑通基础网络与示例。
- L4：版本适配机制（version-api + vX_Y），先落地 1.20.1。
- smoke 冒烟特性：①单一 L0 逻辑在 Paper/Fabric/Forge 各端一致运行；②异构客户端（Fabric/Forge）经 protocol 与异构服务端（Paper/Bukkit）互通（握手+版本协商+往返包），证桥接成立。
- 基础跨端网络（FR-19~FR-25）：跨平台传输（Bukkit/Folia/Sponge/Fabric/Forge/NeoForge + 单人回环）+ 务实可靠性层（分片/重组+CRC/重连重同步，L1 平台无关）+ 进服握手 + 客户端标识上报（弱标识）+ 标识封禁（原生命令，Bukkit 为进服后即踢）+ 融合服（CatServer）适配设计（实跑随 1.12.2，P2）+ 测试（**MVP 验收门**：mod 加载器 GameTest **模拟服套件 + realserver 套件**须完成通过；Bukkit/Sponge 用 MockBukkit/真实服手测）。
- 初期基础示例（FR-26~FR-28）：平台能力三件套（玩家事件 / 调度含 Folia 区域 / 持久化）+ 跨端消息 HUD + 会话/心跳。
- 共享基础模块（FR-29、FR-30）：core-config（YAML/JSON 配置加载）+ core-paths（预设目录 / 资源位置）+ DataDirectoryPort（平台提供基目录），客户端/服务端共享。

> 此清单是"该做什么"的权威边界，凡不在其中的能力都属越界。

## 2. MVP 严禁出现（属后续阶段）

- **其他版本适配**：1.21.1 / 1.12.2 / 26.2 的 `vX_Y` 模块（属 P2/P3 FR-12、FR-16）——MVP 只做 1.20.1；**CatServer 实跑随 1.12.2（P2）**，本期只做适配设计 + FeatureGate 钩子（FR-25）。
> 注：Folia / Sponge / NeoForge 的**基础网络与示例支持已纳入第一期（P1，FR-13~FR-15）**，不再属本节禁止项；本节"平台"维度的后续仅剩版本轴与 CatServer 实跑。
- **脚手架发布 / 模板化**：模板仓库发布 / 版本化（属 P3 FR-17）。
- **玩法开发者文档与脚手架示例**（属 P3 FR-18）。
- **任何产品级玩法**：本项目是脚手架 / 模板，永不交付具体玩法；smoke 仅为架构验证载体，不得膨胀为玩法。
- **自建命令框架 / 引入 TabooLib**：永久不在本脚手架——各平台用各自**原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier）；命令入口 L3、执行逻辑抽到共享（ADR-0009）。永久边界，非"后续阶段"。（配置与资源路径是本脚手架提供的平台无关共享模块，见 ADR-0010 / FR-29、FR-30，属第一期交付，不在此禁止。）

一旦在代码 / 数据模型 / 契约里看到上述能力的提前实现或占位字段 → **删除，或停下来问**，不得镀金。

## 3. 不为未来预留空壳
- 不写"以后可能用"的抽象、配置项、接口、字段（例如为 Folia/Sponge 预留的空 SPI 实现、为 26.2 预留的空 `v26_2` 模块）。需要时再加。
- 后续阶段能力到时按平台 / 版本新增模块，当前不留占位。
- **功能域先作 `core-domain` 内的包、用到才建，不预建空域模块 / 空 api-impl 骨架**；够大 / 需复用 / 需独立演进再提升为独立 Gradle 模块（域只依赖内核 + EventBus、不依赖兄弟域，见 ADR-0015 / ADR-0011）。
- 例外：端口接口与 SPI 是 L0/L2 的**本期交付物**（FR-01/FR-05），其设计天然面向多平台多版本，不算镀金——但只实现 MVP 所需的端口，不为未用能力先铺接口。

## 4. 越界先问
- 若某任务看起来需要某个后续阶段能力（如必须先有 Folia 适配才能完成）→ **停止并向用户确认**，不自行扩大范围。
- 简洁方案优先：实现远多于必要（如 200 行 vs 50 行）时重写。资深工程师会觉得过度复杂的，就是过度。
