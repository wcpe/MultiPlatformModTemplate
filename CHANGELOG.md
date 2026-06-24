# 变更日志

本项目所有重要变更记录于此。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## 未发布版本

### 新增
- 落地 **realserver 验收 harness 平台无关核心**（FR-23 起步，ADR-0014，镜像 AllinCore-New）：新增不发布的 `acceptance` 测试设施模块（独立于产品协议、不入产品 jar）——测试控制协议 `ClientReadyPacket`(C2S)/`RunStepPacket`(S2C)/`StepResultPacket`(C2S) + `StepStatus`（稳定线缆码）+ 手写 `AcceptanceControlCodec`（int 长度前缀 UTF-8，非法/截断/未知类型即拒）；`AcceptanceClient` 客户端排程协调（seq→`CompletableFuture` 对账 + `CountDownLatch` 就绪门闩 + 单步超时 + `failAllPending` 收尾唤醒，线程安全）；单一权威报告 `AcceptanceReport`（`SERVER-GAMETEST-REPORT` + TOTAL 统计 + 末行 `RESULT PASS|FAIL`，SKIP 不计失败、空结果判失败）。纯 JVM 单测 30 例（codec 往返/字节稳定/边界 18、协调 seq/门闩/超时/收尾 6、报告聚合/判定 6）。Fabric 控制通道 / 场景 / 客户端验证器 + Gradle 起服编排属实机维度、待后续。
- 落地 **Fabric 客户端传输 + 客户端网络装配特性**（FR-19 推进，与服务端对称）：L1 core-client 新增 `ClientNetworkFeature`（实现 `Feature`，装配 `PacketDispatcher` + `HandshakeClientService`，平台无关、纯 JVM 单测 3 例覆盖发起握手发 ClientHello / 被接受后上报弱标识 / 收服务端消息 / 入参校验）；core-client 增依赖 core-runtime。platform-fabric 新增 L4 `FabricClientNetwork`(接口) + `v1_20.V1_20ClientNetwork`（`@Environment(CLIENT)`，1.20.1 `ClientPlayNetworking`）+ `FabricNetworkBindings.clientNetwork` 装配点 + L3 `FabricClientTransport`（`@Environment(CLIENT)`，无连接发送 + 服务端哨兵句柄）；版本探测抽出共享 `FabricVersions.detect()`（服务端入口同步改用）；`MpmtFabricClientBootstrap` 以独立客户端运行时装配本特性，连入服务端（`ClientPlayConnectionEvents.JOIN`）时发起握手；`fabric.mod.json` 声明依赖 fabric-api。core-client shade 进 Fabric 产物，编译 + 打包校验通过，运行期收发待 GameTest。
- 落地 **服务端网络装配特性 + Fabric 接入**（FR-19 推进）：新增 L1 core-server `ServerNetworkFeature`（实现 core-runtime `Feature`）——`onEnable` 取平台注入的 `TransportPort`，装配 `PacketDispatcher` + `HandshakeServerService` + `HudMessageService` + 示例 Ping→Pong；**平台无关，各平台注入自己的 `TransportPort` 即复用同一份服务端网络装配**（"逻辑写一次"）。core-server 增依赖 core-runtime（ARCHITECTURE §2.2）。Fabric 入口 `MpmtFabricBootstrap` 登记本特性，core-server shade 进 Fabric 产物。纯 JVM 单测 3 例（装配后握手 + 标识上报欢迎 + Ping/Pong 往返、装配产物启用前后可见性、入参校验）；Fabric 编译 + 打包校验通过，运行期收发待 GameTest。
- 落地 **Fabric L4 版本适配机制 + 服务端真实传输**（FR-10/FR-20 起步，platform-fabric）：新增 fabric-api 依赖（网络 API 来源）；L4 `version` 子层 `SupportedVersion`（运行期探测 MC 版本→选锚点 1.20.1，缺失即失败快，纯 JVM 单测 2 例）+ `FabricServerNetwork`(版本无关接口) + `v1_20.V1_20ServerNetwork`（用 1.20.1 `ServerPlayNetworking` + `ResourceLocation`(`mpmt:main`) + `FriendlyByteBuf` 实现裸字节收发，唯一引用版本 API 处）+ `FabricNetworkBindings`（按版本装配的唯一 switch 装配点）；L3 `FabricServerTransport`(实现 L0 `TransportPort`，委托 L4 绑定) + `FabricConnectionHandle`(封装 `ServerPlayer`、UUID 相等，平台原生对象不泄漏进 L0/L1)；`FabricPlatformBootstrap.assemble` 探测版本→装配绑定→注册 `TransportPort`。**编译期经真实 Fabric 1.20.1 + Mojmap 验证**；版本选择 + 装配缺运行时失败快有纯 JVM 单测；运行期收发与客户端方向（ClientPlayNetworking）待 GameTest / 后续。
- 落地 **跨端 HUD 消息 L1 层**（FR-27 起步，纯 JVM 平台无关）：protocol 新增 `ServerHudMessagePacket`(S2C 0x05) + `HudKind`（TITLE/ACTIONBAR/TOAST/CHAT，**稳定线缆码而非 ordinal**，未知码抛 `ProtocolException`），字节布局 `kind(VarInt)+text(UTF)+subtitle(UTF)+durationMillis(long)`，注册进 PacketCodec 并纳入帧往返一致测试；core-server 新增 `HudMessageService`（`send(ConnectionHandle, HudKind, text[, subtitle, durationMillis])` / `sendTitle`，经 PacketDispatcher 下发，入参非空校验）。纯 JVM 单测：HUD 字段级往返 + 线缆码边界 7 例、下发服务 4 例、并入帧往返样本 4 类。客户端各平台渲染（L3，渲染线程读不可变快照）与按玩家定位下发（整合会话注册表）待后续。
- 落地 **平台能力示例 L0 层**（FR-26 起步，纯 JVM 平台无关）：新增 L0 端口 `SchedulerPort`（按归属调度 `runForEntity`/`runForLocation`/`runGlobal`/`runAsync`/`runTimer`，实现 ADR-0013，触碰世界 / 实体态必经带归属入口、周期句柄可关闭）+ `PersistencePort`（namespace/key 字符串读写）+ `MessagePort`（向玩家发文本）；新增 L0 内核领域引用 `PlayerRef`/`EntityRef`/`WorldRef`（Lombok 值对象）；新增 `capability` 功能域：`PlayerJoinedEvent`/`PlayerLeftEvent`（经自有 EventBus 协作，ADR-0011）+ `PlatformCapabilityExample`（玩家加入→异步持久化首次加入时间→按归属发欢迎→注册周期心跳；离开→关闭心跳句柄释放资源；时钟可注入）。纯 JVM 单测 7 例（首次/再次加入、心跳周期、离开释放、重复加入防泄露、经 EventBus 订阅、入参校验）。各平台事件桥接 / 调度 / 持久化 L3 实现待后续，实机一致性维度待用户确认。
- 新增 **共享目录与资源路径模块**（FR-30，纯 JVM 平台无关，ADR-0010）：新增 L0 端口 `DataDirectoryPort`（`@FunctionalInterface`，平台提供基目录 `java.nio.file.Path baseDirectory()`，`Path` 为 JDK 标准类型守 L0 不依赖平台）+ L1 模块 `core-paths` 的 `ResourcePaths`（在基目录下预设 `config/`/`data/`/`resources/` 标准目录，`configFile`/`dataFile`/`resourceFile` 解析相对名，调用方引用预设不自算路径；相对名规范化后越界 / 绝对路径即拒、基目录为空失败快）。客户端 / 服务端共用同一份预设。纯 JVM 单测 8 例（预设位置 / 相对名解析 / 嵌套 / 越界拒绝 / 绝对路径拒绝 / 空白名 / 失败快 / 端口为空）。平台基目录实现待平台胶水按需提供。
- 新增 **会话与心跳逻辑**（FR-28 起步，纯 JVM 平台无关）：`SessionRegistry`（服务端在线会话登记 / 查询 / 下线 / 在线列表，线程安全；core-server 接入 Lombok 用于 Session 值对象）+ `RttTracker`（客户端心跳 RTT 计算 + 超时清扫判疑似丢失，时钟可注入）。纯 JVM 单测 7 例（会话 3 + RTT 4）。周期发包与重连重同步联动由上层 / 平台驱动（后续）。
- 新增 **协议包**（FR-21/22）：`ClientIdReportPacket`(C2S 0x81)、`ServerMessagePacket`(S2C 0x02)、`DisconnectPacket`(S2C 0x03)，注册进 PacketCodec、纳入往返一致测试。
- 新增 **L0 弱客户端标识与封禁域**（FR-21 / FR-22 起步）：`MachineCode`（Lombok 值对象，弱标识）+ `BanEntry` + `BanRegistry`（线程安全 ban/unban/isBanned/list，纯逻辑）+ `MachineCodeProvider` 端口（客户端侧弱标识提供者）。core-domain 接入 Lombok（首批领域值对象，ADR-0004/FR-01）。纯 JVM 单测 4 例穷举封禁表。
- 落地 **网络可靠性层·分片与重组**（FR-24 起步，L1 protocol·平台无关·线程安全）：`FragmentPacket`（id 0x10）+ `Fragmenter`（按上限切片、各片携带完整载荷 CRC32）+ `Reassembler`（按 seqId 归组、乱序可重组、CRC 校验、超时清理，时钟可注入）；codec 增加定长 `int`（4 字节大端，供 CRC32）。纯 JVM 单测 12 例（往返各尺寸 / 乱序 / 单片 / CRC 检出 / 超时清理）。（重连 / 重同步随会话特性后续。）
- 落地 **握手服务 + 跨端冒烟集成**（FR-03 / FR-11 / FR-21 起步）：
  - L0 `HandshakeStateMachine` 扩展标识上报迁移：`onClientId(banned)`（HELLO_OK → ESTABLISHED / REJECTED）。
  - L1 `core-server`：`HandshakeServerService`（收 ClientHello → 版本协商 → 回 ServerHello；收 ClientIdReport → 封禁校验 → 欢迎建会话 / 告知封禁并通知断开，真实踢出由平台 L3 调度执行）。
  - L1 `core-client`：`HandshakeClientService`（发 ClientHello、被接受后上报弱标识、接收服务端消息 / 断开通知，volatile 暴露结果）+ `DefaultMachineCodeProvider`（弱系统属性 SHA-256，原始来源可注入）。
  - `smoke`（不发布）：`InProcessLoopbackTransport`（进程内回环传输，FR-20）+ 集成测试，**纯 JVM 跑通"进服握手 + 版本协商 + 标识上报 + 封禁判定 + 一次往返包"全链路 + 不兼容版本被拒 + 被封禁被通知断开**（FR-11 ② 的逻辑证明；真实异构互通为实机维度）。
- 新增 **跨端收发核心**（FR-19 / FR-21 起步）：
  - L0：`TransportPort`（裸 `byte[]` 收发，不依赖协议层以守 L0⊄L1）+ `ConnectionHandle`（不透明连接句柄）+ `HandshakeStateMachine`（纯逻辑状态机 CONNECTED→HELLO_OK/REJECTED→ESTABLISHED，非法迁移失败快、版本兼容性由 L1 传入）。
  - L1 protocol：`PacketDispatcher` 收发管线——在 TransportPort 之上用 PacketCodec 编码发送 / 解码按 id 路由，非法 / 截断 / 未知字节不崩溃，无处理器静默忽略，处理器表并发安全。
  - 纯 JVM 单测：握手迁移 3 例 + 收发管线 4 例。
- 落地 **L3 platform-forge 平台胶水**（FR-09 起步，独立 includeBuild·ForgeGradle 6.0.54·Forge 1.20.1-47.4.2·官方映射）：`ForgePlatformBootstrap`（SPI + `META-INF/services` 注册）+ `ForgeFeatureGate`（Forge 无 Folia；同进程有 Bukkit 判融合服；客户端发行环境判集成服）+ `MpmtForgeMod`（`@Mod` 入口，构造期装配运行时）+ **client/server 分离代理**（`SidedProxy`/`ClientProxy`/`ServerProxy`，按 `FMLEnvironment.dist` 选择）。打包：shade platform-spi + core + relocate snakeyaml，再 **reobf 到 SRG** 供真实 Forge 运行；`verifyPackaging` 守护。已验证 **ForgeGradle 可在本仓库 Gradle 8.10 起来**；纯 JVM 测试 2 例验证经真实 ServiceLoader 发现 Forge 入口 + FeatureGate 分流；真实客户端 / 服务端为实机维度。
- 落地 **L3 platform-fabric 平台胶水**（FR-08 起步，由 M0 spike 载体升级为真正胶水）：`FabricPlatformBootstrap`（SPI + `META-INF/services` 注册）+ `FabricFeatureGate`（Fabric 无 Folia / 非融合服，集成服按发行环境探测，纯 JVM 下保守判否）+ **main/client 双端入口**（`MpmtFabricBootstrap` 两端共用、一次性装配运行时；`MpmtFabricClientBootstrap` 客户端接缝）。打包链路升级为 shade platform-spi + core 全链 + relocate snakeyaml 进 remapped jar（`verifyPackaging` 加查 platform-spi 在位）。纯 JVM 测试 2 例验证经真实 ServiceLoader 发现 Fabric 入口 + FeatureGate 分流；真实客户端 / GameTest 模拟服为实机维度（随网络 / smoke 特性落地）。
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
