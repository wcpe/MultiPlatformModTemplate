# 功能规格：初期基础示例（平台能力 / 跨端消息 / 会话心跳）

> 状态：草拟　·　关联 PRD：FR-26 / FR-27 / FR-28　·　分支：feature/foundational-examples
> 设计评审稿 + 接口骨架，经确认后再写实现。目的：让脚手架**开箱即用**，并示范"一份 L0 逻辑跨平台一致""跨端能力"的写法。

## 1. 背景与目标

在网络 + 握手 + 机器码封禁之外，补三组**基础示例能力**，演示端口在各平台一致、跨端下发、会话与心跳。它们既是可运行示例，也是玩法开发者的范本。

**明确边界**：**不自建命令框架、不引入 TabooLib**——各平台用各自**原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier）；**命令入口在 L3、执行逻辑抽到共享 L0/L1**（见 [ADR-0009](../adr/0009-command-config-framework.md)）。配置与资源路径则是平台无关共享模块（`core-config`/`core-paths`，见 [ADR-0010](../adr/0010-config-and-resource-paths.md)）。

## 2. 范围

**范围内**
- A 平台能力三件套（FR-26）：玩家事件（join/leave/chat 经 `EventBusPort`）、调度（同步/异步/延迟/周期经 `SchedulerPort`，Folia 区域调度经 FeatureGate）、持久化（玩家数据经 `PersistencePort`）。
- C 跨端消息 / HUD（FR-27）：server→client 下发 title / actionbar / toast / 聊天消息。
- D 会话 + 心跳（FR-28）：握手后会话注册表 / 在线列表；keepalive ping/pong + RTT（兼做重连检测，配合网络层重连重同步）。

**范围外**：自建命令框架 / 引入 TabooLib（→ ADR-0009：各平台原生命令框架，入口 L3、执行共享）；配置 / 资源路径共享模块（→ ADR-0010 / FR-29、FR-30，独立基础设施）；任何产品级玩法。

## 3. 设计（分层映射 + 接口骨架）

### A 平台能力三件套
- **L0 内核 / 端口**：`EventBusPort`（**自有 EventBus 的订阅/发布接口**，L0 内核实现，承载域间转发）、`SchedulerPort`（同步/异步/延迟/周期）、`PersistencePort`（读写）。
- **L0 领域**：示例逻辑——如"玩家加入→记录首次加入时间（持久化）→广播欢迎（消息）→每 N tick 心跳（调度）"，纯逻辑可单测。
- **L3 各平台**：把平台事件（Bukkit `PlayerJoinEvent` / Fabric `ServerPlayConnectionEvents` 等）适配为领域事件、**投递到自有 EventBus（可跨域转发，ADR-0011）**；`SchedulerPort` 适配各平台调度（**Folia 经 FeatureGate 选 RegionScheduler**）；`PersistencePort` 文件实现。
- **线程**：监听器通常在主线程触发（**Folia 无单一主线程**）；回调进 L0 前涉及共享可变状态须线程安全；耗时操作经 `SchedulerPort.runAsync`，碰世界 / 实体态经 `runForEntity`/`runForLocation` **按归属**切线程（见 testing-and-quality §2、ADR-0013）。

```java
// L0 端口（示意签名）
public interface EventBusPort {
    void subscribe(Class<? extends DomainEvent> type, java.util.function.Consumer<DomainEvent> handler);
    void publish(DomainEvent event);
}
public interface SchedulerPort {                 // 按归属调度（ADR-0013）；禁止无归属 runSync 碰世界态
    void runForEntity(EntityRef entity, Runnable task);   // 碰实体态；Folia: EntityScheduler，他平台: 主线程
    void runForLocation(WorldRef world, int x, int z, Runnable task); // 碰方块/区域态；Folia: RegionScheduler
    void runGlobal(Runnable task);                         // 全局态；Folia: GlobalRegionScheduler
    void runAsync(Runnable task);
    AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task); // 句柄可取消，确保释放
}
```

### C 跨端消息 / HUD
- **L1 protocol**：新增 `ServerHudMessagePacket`(S2C)，字段 `kind`(枚举 TITLE/ACTIONBAR/TOAST/CHAT) + `text`(+可选副标题/时长)。复用网络收发与可靠性层。
- **L1 core-server**：`HudMessageService.send(player, kind, text)` 经 `TransportPort` 下发。
- **L3 各客户端**（Fabric/Forge/NeoForge）：收到后调用各端渲染 API 显示——**渲染在客户端渲染线程**：网络线程收消息后以线程安全方式发布状态、渲染线程读快照显示，不在渲染线程改共享状态；服务端平台（Bukkit/Folia/Sponge）作为发送方。

### D 会话 + 心跳
- **L1**：`SessionRegistry`（握手 ESTABLISHED 后登记 `uuid + machineCode + sessionId + 在线状态`；提供在线列表查询）。
- **心跳**：复用 demo `PingPacket`/`PongPacket`，服务端周期发 Ping、客户端回 Pong，计算 RTT；**超时未回 → 标记疑似掉线 → 配合网络层 `ResyncCoordinator` 触发重连重同步**（见网络 spec）。
- 纯逻辑（会话状态机、超时判定）在 L0/L1，可单测。

## 4. 任务拆分（确认后执行）
- [x] E1（L0）：SchedulerPort（按归属，ADR-0013）/PersistencePort/MessagePort 端口 + 领域引用（PlayerRef/EntityRef/WorldRef）+ capability 功能域示例逻辑 + 纯 JVM 单测（EventBusPort 此前已落地）。各平台 L3 适配见 E2。
- [ ] E2（L3）：各平台事件→EventBus 适配、调度适配（Folia FeatureGate）、文件持久化
- [ ] E3（L1+L3）：ServerHudMessagePacket + HudMessageService + 各客户端渲染
- [ ] E4（L1）：SessionRegistry + 心跳/RTT + 超时→重连重同步联动
- [ ] 文档同步：ARCHITECTURE（端口/事件/调度机制）、API、CHANGELOG

## 5. 验收标准
- 纯 JVM 单测：示例领域逻辑、会话状态机、心跳超时判定、RTT 计算穷举。
- 集成测试（**MVP 验收门**，按平台族）：mod 加载器 **GameTest 模拟服套件**（headless 自动跑）**+ realserver 套件**（等待玩家进入 → 玩家事件链 / HUD 下发 / 心跳往返与超时重连 → **客户端+服务端双重断言、服务端聚合**）；Bukkit 家族 / Sponge（无 GameTest）用 MockBukkit / 真实服手测。
- 【需用户实机确认】各平台实机：同一份 L0 示例在 Paper/Fabric/Forge（及后续 Folia/Sponge/NeoForge）一致运行；Folia 区域调度分支经 FeatureGate 正确。

## 6. 风险 / 待定
- **Folia 调度**：区域调度语义与全局主线程差异大，须经 FeatureGate 分支并各自验证（P2 实机）。
- **HUD 渲染跨版本差异**：title/toast API 跨 MC 版本有差异，由 L4 版本适配吸收（本期 1.20.1）。
- **命令 / 配置边界**：命令各平台用**原生框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier，**不引入 TabooLib**），入口 L3、执行共享（ADR-0009）；配置与资源路径走平台无关共享模块 `core-config`/`core-paths`（ADR-0010）。
