# 接口契约：MultiPlatformModTemplate

> 对外接口的单一真源。始终原地更新到当前契约。
> 当前为骨架阶段：以下为各接口面的**约定形态**，随实现逐步落地后在此补全签名与字段。

## 1. 通用约定

MPMT 作为脚手架 / 模板，其接口分三个面（玩法开发者在克隆出的工程里使用，平台实现者扩展平台），各自服务不同角色：

| 接口面 | 面向 | 所在层 | 形态 |
|---|---|---|---|
| **端口 API（Port API）** | 玩法开发者 | L0 `core-domain` | Java 接口，玩法经其请求平台能力 |
| **平台 SPI** | 平台实现者 | L2 `platform-spi` | Java 接口 + `ServiceLoader` 约定 |
| **跨端协议** | 脚手架内部 / 高级用法 | L1 `protocol` | 包定义 + 字节布局 + 版本号 |

- **版本化**：脚手架版本号唯一来源是根 `VERSION`；协议另有独立的协议版本号（`CURRENT` / `MIN_SUPPORTED`）。
- **稳定性**：标注 `@since` / 稳定性级别（稳定 / 实验）；破坏性变更走 SemVer major 并在 CHANGELOG + ADR 写明迁移。
- **Java 版本**：本接口面（L0–L2）以 Java 8 编译，调用方无需高 JDK。

## 2. 错误约定

- 端口方法以**领域异常**表达失败（不吞异常、不用异常控制正常流程）；统一在 L1 / 平台边界转换为对外可理解的结果。
- SPI 装配失败（无平台、或**我方多个入口同时激活**如同进程既装 Bukkit 插件又装 Forge mod、或端口工厂返回空）→ **启动期失败快**，给出明确中文诊断（融合服上平台并存本身合法，见 ADR-0008）。
- 协议层非法 / 不兼容 / 截断输入 → 明确拒绝并记录，不静默错乱、不崩溃。

## 3. 端口 API（L0，面向玩法开发者）

玩法逻辑通过下列端口请求外界能力（具体方法签名随实现补全）：

- `PlayerPort`：玩家信息查询 / 操作的领域视图。
- `WorldPort`：世界 / 方块 / 实体的领域视图。
- `SchedulerPort`：任务调度（同步 / 异步 / 延迟 / 周期），屏蔽各平台调度差异（含 Folia 区域调度特判，经 FeatureGate；区域调度的真机验证维度见 PRD / spec）。
- `EventBusPort`：**自有 EventBus** 的订阅 / 发布接口（L0 内核实现，承载域间事件转发；非平台端口，无 L3 实现）。**已落地**：`<E extends DomainEvent> void subscribe(Class<E> type, Consumer<E> handler)` + `void publish(DomainEvent event)`；标记接口 `DomainEvent`；默认实现 `SimpleEventBus`（平台无关、线程安全、按精确类型分发、订阅者异常隔离、零第三方依赖）。
- `MessagePort`：向玩家 / 频道发送消息。
- `PersistencePort`：玩法状态的读写持久化。
- `TransportPort`：跨端字节收发（供 `protocol` 使用，不直接面向玩法）。

> 端口只暴露领域视图，**不暴露任何平台原生对象**。MVP 只实现冒烟特性所需的端口子集，其余按需增量添加（见 scope-discipline）。

## 4. 平台 SPI（L2，面向平台实现者）

新增平台 = 实现下列 SPI 并经 `META-INF/services` 注册：

- `PlatformBootstrap`：平台入口，声明平台标识、构造各端口工厂、暴露 `FeatureGate`。
- `ServerAdapter` / `ClientAdapter`：服务端 / 客户端侧装配入口（按平台是否含该端实现）。
- 各端口的工厂：把平台原生 API 适配为对应端口实现。
- `FeatureGate`：能力探测（如"是否 Folia 区域调度可用"、"该版本是否具备某 API"），承载平台 / 版本特判。

发现与装配机制见 [ADR-0002](adr/0002-platform-abstraction-spi.md) 与 [ADR-0017](adr/0017-assembly-orchestration-in-l2.md)；运行期访问点为 `PlatformProvider`（Holder）。

**已落地（M4 骨架）**：

- `PlatformBootstrap`：`String platformId()` + `FeatureGate featureGate()` + `void assemble(RuntimePorts ports)`（平台把端口注入运行时）。
- `FeatureGate`：`boolean supports(Capability)`；`Capability` 枚举（当前：`REGION_SCHEDULER` / `HYBRID_FORGE_BUKKIT` / `INTEGRATED_SERVER`，随特性增量添加）。
- `PlatformProvider`（Holder）：`static boot(ClassLoader, MpmtRuntime)`（一次性装配、之后只读）+ `get()` / `isBooted()` / `platformId()` / `featureGate()`；重复 boot 失败快。
- `PlatformAssembler`：`ServiceLoader` 发现 + 唯一活跃平台选择（零 / 多入口抛 `PlatformAssemblyException`，启动期失败快）；须显式传入承载平台 services 的 ClassLoader。
- `ServerAdapter` / `ClientAdapter` 与各端口工厂随平台胶水落地时补全。

## 5. 跨端协议（L1 `protocol`）

- **单一真源**：包定义 / 字节布局 / 方向（S2C/C2S）/ 协议版本号只在 `protocol` 一处定义，客户端与服务端共用。
- **版本协商**：握手时按 `MIN_SUPPORTED` 判定兼容；不兼容明确拒绝。
- **传输无关**：序列化不依赖平台类型，底层经 `TransportPort` 收发。
- 协议演进规则见 [ADR-0006](adr/0006-cross-end-protocol.md)。
- **已落地（M2 骨架）**：
  - `ProtocolVersion`：`CURRENT` / `MIN_SUPPORTED` + `isCompatible(int)` 版本协商。
  - 编解码：`ProtocolBufWriter` / `ProtocolBufReader`（字节布局与 MC 线缆对齐：VarInt / UTF / long 大端 / byte / bytes）+ 默认实现 `ByteArrayProtocolWriter` / `ByteArrayProtocolReader`；非法 / 截断输入抛 `ProtocolException`、不崩溃。
  - `Packet`（`id()` + `encode`，约定对称 `static decode`）+ `PacketCodec`（帧头 `[protocolVersion:u8][packetId:u8]` + 按 id 注册表分发）。
  - 包：`ClientHelloPacket` / `ServerHelloPacket`（握手）、`PingPacket` / `PongPacket`（往返）；其余包随网络特性增量添加。
