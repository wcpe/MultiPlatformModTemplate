# 功能规格：基础网络（分片/可靠性/重连）+ 进服握手 + 机器码上报 + 机器码封禁

> 状态：草拟　·　关联 PRD：FR-19~FR-25　·　分支：feature/network-core
> 设计评审稿 + 接口骨架，经确认后再写实现。参考 `D:\Projects\AllinCore`（网络层）与 **`D:\Projects\AllinCore-New`（realserver 双端验收，其 ADR-0020 → 本项目 ADR-0014）**。**本稿已纳入架构可行性评审结论**（握手时机 / 跨栈桥接 / 线程归属 / 打包 / 测试分平台 等）。

## 1. 背景与目标

把**基础跨端网络通信**做扎实：一套平台无关的收发 + 务实可靠性层，在 **Bukkit/Paper/Folia/Sponge/Fabric/Forge/NeoForge 服务端及单人世界**上都能用；并落地一条可端到端测试的链路：

**进服 →（应用层）握手与版本协商 → 客户端上报客户端标识 → 服务端回发消息 → 管理员命令按标识封禁 → 被封标识再进服时尽快踢出。**

并附 demo 发包（Ping/Pong、Echo）作为收发范例。

> **关键现实（评审）**：Bukkit 插件消息**只在玩家进服后（PLAY 阶段）可用**，没有"进服前/登录期"可发自定义包的窗口。故封禁在 Bukkit 家族只能表现为"**进服后尽快踢出**"（玩家几乎无感的极短在场窗口），**不是"进服前拒绝"**。为跨平台一致，**统一采用"进服后即踢"语义**；Fabric/Forge/NeoForge 的 login 阶段拒绝仅作可选增强、不作验收前提。

## 2. 范围

**范围内**
- 跨端收发框架：`protocol` 平台无关包定义 + codec；各平台 `TransportPort` 裸收发（FR-19）。
- **跨平台传输**：Bukkit/Folia/Sponge（插件消息）+ Fabric/Forge/NeoForge（各自网络 API）+ 单人世界（集成服内存回环）+ 测试用 `InProcessLoopbackTransport`（FR-20）。**通道注册 / 收发桥接随 MC 版本漂移的部分纳入 L4**（见 §3.4）。
- **务实可靠性层**（L1·平台无关，`TransportPort` 之上）：分片 + 有序重组（+CRC）+ 重连/重同步 + 重组超时重请求（FR-24）。**分片阈值按平台/版本上限**（经 FeatureGate/`maxPayloadSize`），非硬编码常量。
- 握手 + 版本协商 + **客户端标识上报**（弱客户端标识，`MachineCodeProvider` 可插拔；见 §3.5）（FR-21）。
- 机器码封禁：服务端**原生命令** `ban/unban/list`，被封标识再进服尽快踢出（FR-22）。
- **融合服（CatServer 等）适配设计**：Bukkit 入口 + `FeatureGate.HYBRID_FORGE_BUKKIT`；实跑需 1.12.2（P2）（FR-25）。
- 测试：**按平台族**分手段（见 §5）（FR-23）。

**范围外**
- **自建命令框架**：各平台用**原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier），**不引入 TabooLib**（ADR-0009）。
- **CatServer 实跑验证**：需 1.12.2（属 P2）。
- UDP 式滑窗逐包重传（底层 TCP 已可靠）；协议代码生成（YAGNI）；机器码持久化数据库（MVP 文件经 `PersistencePort`）。

## 3. 设计

### 3.1 分层映射

| 层 | 新增/涉及 | 内容 |
|---|---|---|
| L0 core-domain | 新增 | `MachineCode`(弱标识值对象)、`BanEntry`、`BanRegistry`、`HandshakeStateMachine`（纯逻辑）；端口 `TransportPort`/`MessagePort`/`PersistencePort`/`SchedulerPort`(归属调度)/`MachineCodeProvider`；`ConnectionHandle`(不透明连接句柄) |
| L1 protocol | 新增 | codec 接口、`ProtocolVersion`、`PacketIds`、各 `Packet`、**可靠性层** `Fragmenter`/`Reassembler`(+CRC)/`ResyncCoordinator`（平台无关，线程安全） |
| L1 core-server / core-client | 新增 | `HandshakeServerService`、`BanService`、`HandshakeClientService`、`DefaultMachineCodeProvider`（弱标识，见 §3.5） |
| L2 platform-spi | 涉及 | `TransportFactory`、`FeatureGate`（`HYBRID_FORGE_BUKKIT`/`INTEGRATED_SERVER`/`REGION_SCHEDULER`/`maxPayloadSize`）、`CommandRegistrar`(薄接缝) |
| L3 各平台 | 新增 | 各实现 `TransportPort`（裸收发，**通道注册/收发桥接随版本经 L4**）+ codec 缓冲适配 + 入口装配 + **原生命令注册** |

### 3.2 协议包（单一真源在 `protocol`）
通道：`mpmt:main`（1.13+ 带命名空间；**1.12.2 用无命名空间字符串通道，由 L4 适配**）。包头：`[protocolVersion: u8][packetId: u8][payload…]`。

| 包 | 方向 | id | 字段 |
|---|---|---|---|
| `ClientHelloPacket` | C2S | 0x80 | protocolVersion:int, modVersion:utf |
| `ServerHelloPacket` | S2C | 0x01 | protocolVersion:int, sessionId:utf, accepted:bool |
| `ClientIdReportPacket` | C2S | 0x81 | clientId:utf（弱标识，SHA-256 hex） |
| `ServerMessagePacket` | S2C | 0x02 | text:utf |
| `DisconnectPacket` | S2C | 0x03 | reason:utf |
| `ServerHudMessagePacket` | S2C | 0x05 | kind:varint(HudKind 稳定线缆码), text:utf, subtitle:utf, durationMillis:long |
| `FragmentPacket` | 双向 | 0x10 | seqId:varint, index:varint, total:varint, crc32:int, payload:bytes |
| `ResyncRequestPacket` | C2S | 0x82 | sinceRevision:varint |
| `PingPacket`/`PongPacket`（demo） | C2S/S2C | 0x83/0x04 | nonce:long |

`ProtocolVersion.CURRENT=1`、`MIN_SUPPORTED=1`。

### 3.3 可靠性层（务实，平台无关）
底层 MC 通道 TCP 可靠有序，故只解决"大包"与"重连"：**分片**（超过平台/版本上限的载荷切 `FragmentPacket`，阈值经 `FeatureGate.maxPayloadSize()` 按平台/版本取，**非硬编码 30KB**）；**重组**（按 seqId 收集 + CRC32 校验，超时丢弃→触发重请求/重同步）；**重连/重同步**（连接重建或心跳超时后客户端发 `ResyncRequest`，服务端重发权威状态）。全在 L1，**线程安全实现**（onReceive 可能在任意网络线程，见 §3.9）。

### 3.4 跨平台传输 + 跨栈互通（评审重点）

| 平台 | 收发机制 | 版本漂移（→L4） |
|---|---|---|
| Bukkit/Spigot/Paper/Folia | 插件消息 `sendPluginMessage`/`PluginMessageListener` | 1.12.2 无命名空间通道；调度差异 |
| Sponge | RawDataChannel | 版本差异 |
| Fabric | `ServerPlayNetworking`/`ClientPlayNetworking` | **1.20.5+ 改 `CustomPayload`+`StreamCodec`**，1.20.1 旧式 Identifier+buf |
| Forge | SimpleChannel / payload | **线缆含 FML 封装层**；optional/`acceptVanilla` |
| NeoForge | payload 注册（optional 标志） | 1.20.5+ codec 化 |
| 单人世界 | Minecraft 集成服内存回环（加载器网络 API 自动覆盖） | — |
| 测试 | `InProcessLoopbackTransport`（同 JVM 直连，无 Minecraft） | — |

**跨栈互通硬前提与现实（评审）**：
- **双端都须装我方组件并注册同一通道**才互通——这不是"协议天然打通"，原版/未装我方组件的客户端按"非本协议端"处理。服务端经"进服后 N 秒未收到 `ClientHello` → 判非本协议端、走原版兼容路径"做**能力探测/超时降级**。
- **Forge/NeoForge 客户端连非同类服务端**：其通道默认参与 loader 连接期握手，必须标 **optional / `acceptVanilla`**，否则在 login 期就被踢、应用层 `ClientHello` 没机会发。
- **Forge 自定义包线缆格式**与裸 custom_payload 不同（含 FML 封装/索引），Paper 端 `PluginMessageListener` 收到的字节需专门拆/兼容——**同一 codec 不能直接吃**。
- **通道注册管线随版本进 L4**：protocol 字节单源 ≠ 注册管线统一（1.20.5 codec 化 / 1.12.2 老 API）。
- **建议 M0 先做最小实机 spike**：Fabric 客户端裸 payload ↔ Paper 插件通道，验证字节真对得上，再铺协议。

### 3.5 握手时序（统一"进服后即踢"语义）
```
客户端进服（已 PLAY 阶段）→C2S ClientHello(version, modVersion)
服务端 HandshakeServerService.onClientHello
  ├─ 超时未收到 ClientHello → 判为非本协议端（原版兼容，不踢）
  ├─ 版本不兼容 → S2C Disconnect + 尽快踢出
  └─ 兼容 → S2C ServerHello → 客户端 clientIdProvider.get() → C2S ClientIdReport(clientId)
服务端 onClientIdReport
  ├─ banService.isBanned(clientId) → S2C ServerMessage("已被封禁") + 尽快踢出
  └─ 否则 ESTABLISHED → SessionRegistry 登记 → S2C ServerMessage("欢迎")
  ※ ESTABLISHED 前 N 秒未上报 clientId 的处理（放行为"无标识会话"/踢出）做成配置项
管理员 /mpmt machinecode ban <code> [原因] → banService.ban → 持久化 → 在线命中者经 SchedulerPort.runForEntity 踢出
demo：C2S Ping → S2C Pong
```

**客户端标识（弱标识，评审降级）**：`MachineCodeProvider` 默认实现用"可得的弱硬件/系统属性（MAC + 系统属性）→ SHA-256"。**明确局限**：MAC 在现代 OS 常被随机化、磁盘/主板序列号在沙箱 Java mod 取不到；故该标识**同机可能变、异机可能撞，且可被反编译伪造、亦可被"不发包"缺席**。定位为"**可插拔的弱客户端标识**"——封禁是**威慑、非安全保证**，只拦"诚实上报且命中"者；强约束需叠加服务端侧身份（白名单/登录插件）。详见 SECURITY.md。

### 3.6 融合服（CatServer，依据 ADR-0008）
Bukkit 入口加载、`FeatureGate.HYBRID_FORGE_BUKKIT` 探测；命令走 **Bukkit 原生**；CatServer=1.12.2，需 Bukkit 平台 **L4**（通道注册/调度/聊天组件等随版本），实跑 P2。

### 3.7 接口骨架（Java 8 + Lombok；仅签名）
```java
// L1 protocol codec
public interface ProtocolBufWriter { void writeVarInt(int v); void writeUtf(String s); void writeLong(long v); void writeBoolean(boolean b); void writeBytes(byte[] b); }
public interface ProtocolBufReader { int readVarInt(); String readUtf(); long readLong(); boolean readBoolean(); byte[] readBytes(); }
public interface Packet { int id(); void encode(ProtocolBufWriter buf); }   // 每包另提供 static decode(ProtocolBufReader)

// L1 可靠性层（平台无关，线程安全）
public interface Fragmenter { java.util.List<FragmentPacket> split(int seqId, byte[] payload, int maxChunk); }
public interface Reassembler { java.util.Optional<byte[]> accept(FragmentPacket f); void tickTimeouts(long nowMillis); }
public interface ResyncCoordinator { void requestResync(long sinceRevision); void onResyncRequest(ConnectionHandle conn, long sinceRevision); }

// L0 端口
public interface ConnectionHandle {}   // 不透明连接句柄；平台真实对象封在 L3，L0/L1 不解析其类型
public interface TransportPort {
    void send(ConnectionHandle conn, Packet packet);   // 服务端
    void send(Packet packet);                           // 客户端
    // 契约：onReceive 可能在任意网络线程触发；L1 须线程安全；碰世界/领域状态前经带归属 SchedulerPort 切线程（ADR-0013）
    void onReceive(java.util.function.BiConsumer<ConnectionHandle, Packet> handler);
    int maxPayloadSize();   // 平台/版本相关，供分片
}
public interface MachineCodeProvider { String get(); }   // 弱客户端标识 hex，可插拔

// L0 领域（纯，可单测）
public final class BanRegistry { void ban(MachineCode c, String reason); void unban(MachineCode c); boolean isBanned(MachineCode c); java.util.List<BanEntry> list(); }
public final class HandshakeStateMachine { enum State { CONNECTED, HELLO_OK, ESTABLISHED, REJECTED } State onClientHello(int v); State onClientId(MachineCode c, BanRegistry bans); }

// L0 SchedulerPort（按归属调度，ADR-0013）
public interface SchedulerPort {
    void runForEntity(EntityRef entity, Runnable task);       // Folia: EntityScheduler；他平台: 主线程
    void runForLocation(WorldRef world, int x, int z, Runnable task); // Folia: RegionScheduler(byLocation)
    void runGlobal(Runnable task);                            // Folia: GlobalRegionScheduler
    void runAsync(Runnable task);
}

// L2 SPI
public interface TransportFactory { TransportPort create(); }
public interface CommandRegistrar { void register(String name, java.util.function.Consumer<CommandContext> handler); } // L3 用各平台原生框架实现；只"注册+转发到共享执行"（ADR-0009）
```

### 3.8 模块/文件布局（节选）
```
protocol/   …/protocol/{Packet,PacketIds,ProtocolVersion}.java + codec/ + packets/ + reliability/{Fragmenter,Reassembler,ResyncCoordinator}.java
core-domain/ …/core/domain/port/{TransportPort,ConnectionHandle,MachineCodeProvider,SchedulerPort,...}.java  …/core/domain/ban/{MachineCode,BanEntry,BanRegistry,HandshakeStateMachine}.java
core-server/ …/{HandshakeServerService,BanService}.java   core-client/ …/{HandshakeClientService,DefaultMachineCodeProvider}.java
platform-spi/ …/{TransportFactory,CommandRegistrar,CommandContext}.java
platform-bukkit/ …/net/{BukkitTransport,...} + version/{v1_20,...}/网络注册 + 原生命令(MachineCodeBanCommand)
platform-{sponge,fabric,forge,neoforge}/（各独立 includeBuild）…/net/<Platform>Transport + 版本注册 + 原生命令(Brigadier/Sponge)
```

### 3.9 线程模型与命令归属（依据 ADR-0013/0009）
- **命令入口在 L3**：各平台**原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier）；执行逻辑（如封禁）在共享 L0/L1，L3 只注册 + 解析参数 + 调度切线程。
- **线程归属**：涉及服务端主线程（命令/监听器）、netty 网络线程（`onReceive`）、客户端渲染线程（消息/HUD）。**Folia 无单一主线程**——网络/命令处理碰世界/实体状态前经 `SchedulerPort.runForEntity/runForLocation/runGlobal` 切到**归属线程**（非"主线程"）。`onReceive` 在网络线程→L3 由连接句柄解析归属再调度。
- **客户端发布**：netty 收包→客户端线程（`execute`/`enqueueWork`）→原子替换不可变快照的 `volatile` 引用→渲染线程只读；不在渲染线程改共享状态。
- **共享可变状态** `BanRegistry`/`SessionRegistry` 线程安全；**封禁成功后**才踢人，踢人经 `runForEntity` 调度（Folia 下尤甚）。`SessionRegistry` 身份真源以平台连接/玩家 UUID 为准，`clientId` 仅作附加弱标签、不作信任凭据。

## 4. 任务拆分（确认后执行）
- [ ] M0 构建骨架 + **打包 spike**（core 进 Fabric remapped jar 不被误 remap；relocate snakeyaml/gson，ADR-0012）+ **跨栈 spike**（Fabric payload ↔ Paper 通道字节对齐）
- [ ] T1 protocol codec + 包 + 注册表　[ ] T2 可靠性层（分片/重组+CRC/重连，线程安全）
- [ ] T3 L0 领域（MachineCode/BanRegistry/HandshakeStateMachine）+ 端口（含归属 SchedulerPort/ConnectionHandle）
- [ ] T4 L1 server/client 服务 + 弱标识 Provider　[ ] T5 各平台 TransportPort + **版本网络注册(L4)** + 原生命令
- [ ] T6 InProcessLoopbackTransport　[ ] T7 融合服 FeatureGate 钩子（实跑 P2）
- [ ] T8 测试（按平台族，见 §5）　[ ] 文档同步：ARCHITECTURE/API/CHANGELOG/SECURITY

## 5. 验收标准（测试按平台族，评审）
- **单测（纯 JVM，可在此跑绿）**：①每包 encode→decode 往返一致；②版本协商边界；③分片→重组往返 + CRC 检出 + 超时清理；④`BanRegistry`/`HandshakeStateMachine`（命中→REJECTED）；⑤`DefaultMachineCodeProvider` 固定输入稳定哈希；⑥`BanRegistry`/`SessionRegistry` 并发读写线程安全。
- **【MVP 验收门】GameTest 模拟服套件**（mod 加载器，单人 / 集成 headless，`gradle runGameTest`，可在 CI/本机自动跑）：握手→标识上报→欢迎、封禁后重连被踢、大包分片重组、断连重同步、基础示例。
- **【MVP 验收门 · 需用户实机确认】realserver 套件（镜像 AllinCore-New ADR-0020「服务端驱动、客户端验证」，见 ADR-0014）**：Gradle `runRealServerAcceptance` 起**真实专用服** + 连一个**程序化客户端 gametest 实例**（经真实网络连入，**非人工**）→ 服务端 `awaitClientReady`（`CountDownLatch` + 客户端 `ClientReady` 包）→ 每场景：**setup（主线程 onMain）→ drive（直调真实 API，非命令）→ `runClientStep`（S2C `RunStep` → 客户端跑验证器 → C2S `StepResult{seq,status,resultJson}`，seq 匹配 + 超时）→ 服务端断言两端结果 + 自身状态** → 服务端**聚合写单一权威报告**（`RESULT PASS|FAIL`），Gradle 读 RESULT 作门禁。测试控制协议**仅 test 作用域、手写、不入产品协议**；分层超时（ready/step/scenario/绝对）+ 看门狗。至少覆盖 Fabric/Forge；机器码命令实机生效。
- **Bukkit 家族 / Sponge（无 GameTest）**：用 MockBukkit / Sponge 测试设施 + 真实服脚本化 / 手测达**同等场景覆盖**（被封标识再进服被尽快踢出 = 进服后即踢）；单人 / 纯传输用 `InProcessLoopbackTransport`（纯 JVM）。
- **二者（模拟服 + realserver GameTest）须在 MVP 完成并通过——未通过即 MVP 未交付。**
- 涉及调度/线程的端口**按平台写契约测试**（不指望同一份 L0 测试通吃，ADR-0013）。

## 6. 风险 / 待定
- **客户端标识弱**：可伪造、可缺席（不发包即回落原版路径）、同机可变异机可撞；封禁=威慑非安全，强约束需叠加服务端身份（SECURITY.md 写清）。
- **跨栈桥接**：Forge FML 封装 / 1.12.2 无命名空间通道 / optional channel——M0 先 spike 验证字节对齐，再铺。
- **Folia 线程归属**：SchedulerPort 必须带归属（ADR-0013），否则 Folia 上无法落点。
- **打包**：core 进各 loader 产物 + relocate（ADR-0012），M0 spike 验证。
- **网络注册随版本**：纳入 L4，否则 1.12.2/1.20.5+ 引入时 L3 触红线。
- **Loom/GameTest 验证**需用户本机。
- **realserver Tier 镜像 AllinCore-New（ADR-0020 已落地，见 ADR-0014）**：其 `gametest-support`（ServerGameTest/Context/Runner）+ `acceptance-driver`（ServerScenario / AcceptanceClient / 控制协议 / Bootstrap）+ 客户端 gametest 验证器 + Gradle 插件 `runRealServerAcceptance` 是**成熟实现**，按"服务端驱动 + 客户端验证 + 单一权威报告"镜像。测试控制协议仅 test 作用域、不入产品协议。客户端是**程序化 gametest 实例（非人工）**，realserver 可自动化但需用户本机（下载 MC/映射、起进程）、由用户确认；模拟服 headless 套件（in-process 回环，无需外部客户端）可在 CI 自动跑。
