# ADR-0018：Forge 端用 Mixin 拦截裸 CustomPayload 统一裸字节收发（取代 SimpleChannel）

## 状态
已接受

## 背景
FR-11② 要求异构客户端（含 **Forge 客户端**）经 protocol 与异构服务端（含 **Bukkit/Paper 服**）互通，这是区别于 Architectury 类方案的核心价值（见 ARCHITECTURE §跨端，PRD §6 实机验收项）。

Forge 端此前（commit `5be8c05`）用 Forge `SimpleChannel`（`NetworkRegistry.newSimpleChannel`）实现跨端传输，但它**只覆盖 Forge↔Forge**：

- 实测 `EventNetworkChannel` 对裸 `Serverbound/ClientboundCustomPayloadPacket` 的收包监听**根本不触发**，故退而用 `SimpleChannel`；
- 而 `SimpleChannel` 会加消息索引帧字节，且仅在 Forge↔Forge 经 FML 握手协商后可用。

2026-06-26 的最小 spike（真实 Paper 服 + 程序化 Forge 客户端）给出定论：**Forge 客户端连 Bukkit/Paper 服时能发不能收**。客户端日志 `Connected to a vanilla server. Catching up missing behaviour.`——Forge 判定非 Forge 服为 **vanilla 连接**后，主动切回 vanilla 兼容模式以便正常游玩，**副作用是门控掉了 modded 通道（SimpleChannel/EventNetworkChannel）的入站派发**：来自 Paper 的裸 payload 在 Forge 网络层被丢弃，监听器从不触发。出站（裸 `ServerboundCustomPayloadPacket`）则 Paper `Messenger` 照收、正常。

Forge 公共 API 无法在 vanilla 连接上收 modded 通道裸包；社区对"Forge 客户端 ↔ Bukkit 服插件消息"的标准解法是 **Mixin** 切入客户端收包管线。Mixin 是本项目**预期内**的平台胶水工具（见 `.claude/rules/static-analysis.md`、`scope-discipline.md`：第二期引入平台胶水"可能含少量 Kotlin / Mixin"），**非红线禁项**（红线禁的是 TabooLib / 自建命令框架 / Architectury / 重型 DI）。

## 决策
Forge 端跨端通信改为**纯裸字节 vanilla `CustomPayload`，收包经 Mixin 拦截 vanilla 收包入口路由到我方 receiver**，不再使用 `SimpleChannel` / `EventNetworkChannel` / `NetworkRegistry`：

- **客户端**：Mixin 切 `net.minecraft.client.multiplayer.ClientPacketListener#handleCustomPayload`，命中我方通道（`mpmt:main` / `mpmt-test:acceptance`）即读裸字节交 `ForgeRawPayloadRouter` 派发并 `cancel`（其余 payload 放行给原版/Forge）。
- **服务端**：Mixin 切 `net.minecraft.server.network.ServerGamePacketListenerImpl#handleCustomPayload`，同理（路由出发送方 `ServerPlayer`）。
- **发包**：客户端 `ServerboundCustomPayloadPacket`、服务端 `ClientboundCustomPayloadPacket`，均裸字节，无帧。

本决策**取代** commit `5be8c05` 引入的 Forge `SimpleChannel` 机制（该机制未单独立 ADR，本 ADR 为 Forge 跨端传输机制的权威记录）。

引入依赖：`org.spongepowered:mixin`（Forge 47 运行期已捆绑 0.8.5）+ 构建期注解处理器 / MixinGradle（生成 refmap 供 reobf 生产期解析）。

## 理由
- 拦截挂在**原版收包入口**、不看对端是否 Forge，故**同时打通 Forge↔Forge 与 Forge↔Bukkit**——一套机制覆盖两类对端，消除"为不同对端各写一套通道"的分叉。
- 全裸字节使 Forge 线缆与 Bukkit/Fabric **一致**（protocol 单一真源、字节布局两端共用，ADR-0006），不再有 SimpleChannel 的帧字节特例。
- Mixin 是 Forge/Fabric 生态标准、社区对该场景的既定解法，且本项目已预期其作为平台胶水工具，不破红线。

## 后果
- 正面：**Forge 客户端 ↔ Bukkit/Paper 服互通成立（FR-11②）**；Forge 收发统一裸字节、与全项目对齐；删除 SimpleChannel 帧特例与 `NetworkRegistry` 注册时机相关的脆弱点。
- 负面 / 成本：platform-forge 引入 Mixin 工具链（构建复杂度 +；refmap / reobf 须正确配置，否则生产期 mixin apply 失败 "target method not found"）；Mixin 切原版私有方法，**MC 版本升级时需校验目标方法签名**——此属 L4 版本适配范畴（ADR-0003），1.20.1 单锚点暂无差异，将来按 `vX_Y` 隔离。
- 约束：新增 MC 版本时，若该版本 `handleCustomPayload` 签名变化，Mixin 目标须随 `vX_Y` 适配；Mixin 仅用于"原版无公共扩展点"的平台胶水缝合，不作为业务机制泛滥。
- 验证门：**Forge↔Forge（dev↔dev runServer/runClient）+ Forge 客户端 → 真实 Paper 服**两套 realserver 均须 `RESULT PASS`（realserver 维度由用户实机确认）。

## 备选方案
- **保留 SimpleChannel 仅做 Forge↔Forge，放弃 Forge↔Bukkit**：违背 FR-11② 的核心价值（异构客户端↔异构服互通），且把 Forge 客户端排除在最关键桥接链路外——否决。
- **客户端混用两套机制（Forge 服走 SimpleChannel、vanilla 服走裸字节）**：同一通道无法在一次构建里既发帧字节又发裸字节；且收包仍卡在 vanilla 门控——技术上不成立，否决。
- **ASM coremod / 自写 Transformer 代替 Mixin**：比 Mixin 更重、更易碎、生态非主流——否决。
- **引入 Architectury 统包其网络层**：红线禁项（重型统包框架作默认机制）——否决。
