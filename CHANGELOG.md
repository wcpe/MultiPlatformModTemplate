# 变更日志

本项目所有重要变更记录于此。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 未发布版本

### 新增
- 建立 SDD 规格与治理脚手架：PRD、ARCHITECTURE（含 Mermaid 架构图）、基础 ADR（分层 / 平台抽象 SPI / 多版本适配 / Java8 核心 / 构建复合构建与加载器隔离 / 跨端协议）、API 契约骨架、运维与安全说明。
- 建立防漂移规则集 `.claude/rules/`（架构不变量、范围纪律、验证门、提交规范等）。
- 规划基础跨端网络（跨平台传输 Bukkit/Folia/Sponge/Fabric/Forge/NeoForge + 单人回环 + 务实可靠性层：分片/重组+CRC/重连重同步）+ 进服握手 + 机器码上报 + 机器码封禁 + 融合服（CatServer）适配 + 三层测试（FR-19~FR-25），新增 ADR-0008（融合服 / 活跃平台语义细化），产出 `docs/specs/network-handshake-machine-code-ban.md`。
- 规划初期基础示例（平台能力三件套 / 跨端消息 HUD / 会话心跳，FR-26~FR-28），产出 `docs/specs/foundational-examples.md`。
- 明确命令框架策略（ADR-0009）：各平台用**各自原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier，**不引入 TabooLib**），命令入口在 L3、执行逻辑抽到共享 L0/L1（L2 仅薄 CommandRegistrar 接缝）。
- 据架构可行性评审修订设计：新增 **ADR-0012**（打包 / relocation）、**ADR-0013**（线程归属调度——Folia 无主线程 → SchedulerPort `runForEntity`/`runForLocation`/`runGlobal`）；机器码降级为"**弱客户端标识**"（可伪造 / 可缺席）；握手 / 封禁统一"**进服后即踢**"（Bukkit 插件消息仅 PLAY 阶段）；跨端互通明确"**双端均装我方组件**"前提 + 能力探测降级；网络注册管线随版本纳入 **L4**；**NeoForge 锚点 1.20.2**（无 1.20.1）；测试按平台族（GameTest 仅 mod 加载器）；Java 8 强制 `--release`/animal-sniffer；P1 实施顺序先 Paper+Fabric+Forge；客户端"写一次"价值据实下调。
- 明确 **MVP 验收门 = 自动化测试两套**：①模拟服 GameTest 套件（mod 加载器单人/集成 headless，`gradle runGameTest`，in-process 回环自动跑）②**realserver 套件**（真实专用服，**服务端驱动、客户端验证、单一权威报告**：等待程序化客户端进入 → 触发场景 → 客户端与服务端双重断言 → 客户端回报、服务端聚合 `RESULT PASS|FAIL`、Gradle 门禁，**镜像 AllinCore-New ADR-0020 → 本项目新增 ADR-0014**；测试控制协议仅 test 作用域不入产品协议）——均须完成并通过方算 MVP 交付；Bukkit 家族/Sponge（无 GameTest）以 MockBukkit + 真实服手测达同等覆盖（FR-23 / PRD §6）。
- 新增 **ADR-0015**（功能域组织与拆分约定）：域 = `core-domain` 内的包、只依赖内核 + EventBus、core-runtime 注册、**够大再提升为独立模块、不预建空域**（轻量约定，区别于 AllinCore-New 的 per-domain 重结构）；同步 ARCHITECTURE L0 内部结构、scope-discipline §3。
- 规划平台无关配置与资源路径共享模块（ADR-0010 / FR-29、FR-30）：core-config（YAML/JSON 加载）+ core-paths（预设目录 / 资源位置）+ DataDirectoryPort（平台提供基目录），客户端 / 服务端共用。
- 校正最新版本号为 **26.2**（MC 新版号方案、无 `1.` 前缀，版本模块 `v26_2`）；新增 **ADR-0011**（自有 EventBus 作域间转发解耦、功能域间禁止直接 / 循环依赖，FR-31）；线程模型补充**客户端渲染线程**与 netty 网络线程的线程安全要求。

### 变更
<对现有功能的改动。>

### 修复
<本版本修复的缺陷。>

### 移除
<被删除的功能。>

> 发版时把"未发布版本"段切成 `## [X.Y.Z] - YYYY-MM-DD`，再新建空的"未发布版本"段。
