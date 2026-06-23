# 变更日志

本项目所有重要变更记录于此。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 未发布版本

### 新增
- 落地 **L3 platform-bukkit 平台胶水**（FR-07 起步，Bukkit 家族单一构建）：普通 Java + shadow，编译针对 **spigot-api 1.20.1** 基线（compileOnly），`BukkitPlatformBootstrap`（SPI + `META-INF/services` 注册）+ `BukkitFeatureGate`（按类存在探测 Folia / 融合服能力）+ `MpmtBukkitPlugin`（JavaPlugin 入口，进服后经本插件类加载器装配运行时）。打包：shade 共享核心 + relocate snakeyaml 进插件 jar（`verifyPackaging` 守护，**closes ADR-0012 Bukkit relocate 验证**）。集成测试用 **MockBukkit**（无真实服）验证装配链路：插件启用 → 平台装配为 bukkit → FeatureGate 分流（FR-23）。真实 Paper 服装配为实机维度（待用户确认）。
- 落地 **L2 platform-spi 平台抽象层**（FR-05 / FR-06 起步）：`PlatformBootstrap` SPI + `FeatureGate`（`supports(Capability)` 能力探测）+ `PlatformProvider`（Holder，一次性装配后只读，重复 boot 失败快）+ `PlatformAssembler`（`ServiceLoader` 发现唯一活跃平台，零 / 多入口启动期失败快）。新增 **ADR-0017**（平台发现 / 装配编排归属 L2、L1 不依赖 L2，细化 ADR-0002）。集成测试 6 例（含经 `META-INF/services` 注册的假平台被发现并把端口注入 L1 运行时）。
- 落地 **L1 core-runtime 框架编排**（FR-02 起步）：`MpmtRuntime` 生命周期（NEW→ENABLED→DISABLED，转换守护、非法转换失败快）+ `Feature` / `FeatureRegistry`（按序登记、按名去重、启用顺序 / 停用逆序）+ `RuntimePorts`（类型安全端口注册表，装配期写入、之后只读）+ `RuntimeContext`（向特性暴露 EventBus 与端口）。平台发现在 L2、注入本运行时，L1 不依赖 L2（ADR-0001）。纯 JVM 单测 8 例覆盖生命周期时序 / 守护 / 上下文装配 / 启用异常传播 / 端口注册边界。
- 落地 **L1 protocol 跨端协议骨架**（FR-04 起步）：`ProtocolVersion`（`CURRENT` / `MIN_SUPPORTED` + `isCompatible` 版本协商）；编解码原语 `ProtocolBufWriter` / `ProtocolBufReader` + `byte[]` 默认实现（字节布局与 MC 线缆对齐，非法 / 截断输入抛 `ProtocolException` 不崩溃）；`Packet` + `PacketCodec`（帧头 + 按 id 注册表分发）；握手包 `ClientHello` / `ServerHello` 与往返包 `Ping` / `Pong`。纯 JVM 单测 46 例覆盖往返一致 / 版本协商边界 / VarInt 与 long 边界 / 非法与截断输入（FR-04 / ADR-0006 / testing-and-quality §2）。
- 落地 **L0 自有 EventBus 内核**（FR-01 起步）：`EventBusPort`（订阅 / 发布）+ `DomainEvent` 标记接口 + `SimpleEventBus` 默认实现——平台无关、线程安全（`ConcurrentHashMap` + `CopyOnWriteArrayList`）、按精确类型分发、订阅者异常隔离、零第三方依赖（JDK 自带日志）。纯 JVM 单测覆盖正常 / 类型隔离 / 无订阅者 / 异常隔离 / null 校验 / 并发（FR-01 / ADR-0011 / testing-and-quality §2）。
- 落地 **M0 Gradle 复合构建骨架 + 打包 spike + 跨栈 spike**（`docs/specs/build-skeleton-and-spikes.md`）：
  - Gradle 8.10.2 wrapper + 根复合构建（Kotlin DSL）；`core-domain`（根常规模块，JDK 8 工具链，零第三方运行期依赖）+ `platform-fabric`（独立 includeBuild·Loom 1.7.4·MC 1.20.1·Mojang 官方映射·Java 17）。
  - **打包 spike 通过**：core 纯 Java 经 shadow shade 进 Fabric remapped jar 且不被 remap、snakeyaml relocate 到 `top.wcpe.mc.mpmt.libs.*`、`fabric.mod.json` 注入版本号；自动化校验任务 `verifyPackaging` 守护。**结论：core 消费走 includeBuild 依赖替换成立，未触发 ADR-0012 的 mavenLocal 回退。**
  - **跨栈 spike 通过**：自动化字节等价测试（12 例）证明 Fabric `FriendlyByteBuf` 与普通 `byte[]` 路径逐字节一致，为协议单一真源铺路。
  - **Java 8 强制**：core 用 JDK 8 工具链编译，误用 Java 9+ API 编译失败（ADR-0004）。
  - 新增 **ADR-0016**（反混淆映射策略：锚点有官方映射用 Mojmap、无官方走各 loader 自带）。
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
