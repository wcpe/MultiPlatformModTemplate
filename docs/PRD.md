# 产品需求文档（PRD）：MultiPlatformModTemplate

> 需求的单一真源（WHAT / WHY），也是需求登记册 + 路线图——全生命周期都在记。每个需求在 §4 加一行 FR（带优先级 + 状态），交付即标版本。单功能的详细规格放 `docs/specs/`。

## 1. 背景与目标

Minecraft 生态长期割裂：服务端软件（Bukkit/Spigot/Paper/Folia/Sponge）与模组加载器（Fabric/Forge/NeoForge）API 互不相通，且同一阵营内不同 Minecraft 版本（1.12 → 最新）API 也持续漂移。开发者要让一套玩法跑遍多平台多版本，往往被迫为每个组合重写。

**MultiPlatformModTemplate（MPMT）** 是一套**可克隆复用的多平台 mod 玩法脚手架 / 模板**，要解决的是：**让一份玩法逻辑只写一次（完全平台无关），即可落地到任意服务端平台 + 任意客户端平台、并覆盖多个 Minecraft 版本。** 价值主张一句话：**玩法写一次，平台与版本的差异交给脚手架。**

实现手段是清晰的分层（L0 功能域 → L1 编排/端逻辑/协议 → L2 平台抽象 SPI → L3 平台胶水 → L4 版本适配），把"逻辑"与"胶水"彻底分离。架构详见 [`ARCHITECTURE.md`](ARCHITECTURE.md)。

### 非目标

- **不做具体玩法产品**：本仓库是脚手架 / 模板，只含最小冒烟特性验证架构，不交付任何产品级玩法。
- **不作为依赖库 / SDK 发布**：交付形态是被克隆 / 复用的项目模板，不发布 Maven 依赖坐标供他人 import；玩法写在克隆出的副本里。
- **不做"只跨 mod 加载器"的窄方案**：必须同时桥接 Bukkit 家族服务端软件，区别于 Architectury 类方案。
- **不追求一次性覆盖全平台全版本**：按分期推进（§7），先证明分离成立，再铺开矩阵。
- **不默认引入反射魔法 / 重型 DI / 重型中间件**：保持简单优先，需要时先走 ADR。
- **不自建命令框架，也不引入 TabooLib**：各平台用**各自原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier）；命令入口在 L3，执行逻辑抽到共享 L0/L1（见 ADR-0009）。
- **配置与资源路径是平台无关共享模块**（不经 TabooLib，属第一期交付物）：`core-config`（YAML/JSON 加载工具）+ `core-paths`（预设目录 / 资源位置，调用方引用、不自算），见 ADR-0010。

## 2. 角色

- **玩法开发者**：克隆本脚手架编写跨平台玩法，只面对 L0/L1 API，不碰平台差异。本模板的主要服务对象。
- **平台实现者**：为新平台 / 新版本编写 L3/L4 胶水，实现 SPI。
- **服务器运维 / 整合包作者**：把基于 MPMT 的玩法部署到实际服务端 / 客户端。
- **脚手架维护者**：维护核心分层、SPI 契约与协议演进。

## 3. 用户故事

- 作为**玩法开发者**，我希望在 L0 写一次玩法逻辑，就能同时跑在 Paper、Fabric、Forge 上，以便不再为每个平台重写。
- 作为**玩法开发者**，我希望通过端口（Port）请求"调度任务 / 发消息 / 持久化"等能力，而不感知底层是哪个平台，以便逻辑可纯 JVM 单元测试。
- 作为**平台实现者**，我希望新增一个平台只需实现一组 SPI 并经 ServiceLoader 注册，以便低成本扩展。
- 作为**平台实现者**，我希望版本差异被关在 `version-api` 接口后，新增一个 MC 版本只加一个 `vX_Y` 模块，以便不污染公共逻辑。
- 作为**运维**，我希望同一份玩法可自由组合"任意服务端 + 任意客户端"，以便适配玩家的多样环境。

## 4. 功能需求（FR）

| 编号 | 需求 | 优先级 | 状态 |
|---|---|---|---|
| FR-01 | L0 功能域骨架：领域模型（Lombok 值对象）+ 平台端口接口集（Player/World/Scheduler/Message/Persistence/Transport/DataDirectory）+ 自有 EventBus（订阅/发布接口 EventBusPort，L0 内核） | P1 | 开发中 |
| FR-02 | L1 框架编排：生命周期、特性（Feature）注册、端口装配 | P1 | 开发中 |
| FR-03 | L1 端逻辑骨架：core-server / core-client 公共逻辑分模块 | P1 | 开发中 |
| FR-04 | protocol 跨端协议骨架：包定义单一真源 + 版本协商（CURRENT/MIN_SUPPORTED） | P1 | 开发中 |
| FR-05 | platform-spi：SPI 接口集 + PlatformProvider(Holder) + ServiceLoader 发现装配 | P1 | 开发中 |
| FR-06 | FeatureGate 能力探测机制：承载平台/版本"特判"，杜绝散落 if-else | P1 | 开发中 |
| FR-07 | platform-bukkit 服务端胶水：Bukkit 家族单一构建覆盖 Bukkit/Spigot/Paper（Folia 能力见 FR-13），锚点 1.20.1 | P1 | 开发中 |
| FR-08 | platform-fabric 胶水（Loom·独立 includeBuild）：客户端 + 服务端双端入口（锚点 1.20.1） | P1 | 开发中 |
| FR-09 | platform-forge 胶水（ForgeGradle·独立 includeBuild）：客户端 / 服务端分离代理（锚点 1.20.1） | P1 | 开发中 |
| FR-10 | L4 版本适配机制：version-api + vX_Y 运行期按 MC 版本装配（先落地 1.20.1） | P1 | 计划 |
| FR-11 | smoke 冒烟特性（两证）：① 同一份 L0 逻辑经端口在 Paper/Fabric/Forge 各端一致运行——证"逻辑/胶水完全分离"；② 异构客户端（Fabric/Forge mod）经 protocol 与异构服务端（Paper/Bukkit 插件）完成握手 + 版本协商 + 一次往返包——证"服务端软件 ↔ 模组加载器"桥接成立 | P1 | 开发中 |
| FR-12 | 多版本扩展：新增 1.21.1、1.12.2 版本适配，验证 L4 机制跨版本成立 | P2 | 计划 |
| FR-13 | Folia 支持：并入 Bukkit 家族构建，经 FeatureGate 适配 RegionScheduler（验证特判机制，不拆独立构建） | P1 | 计划 |
| FR-14 | platform-sponge（SpongeGradle·独立 includeBuild）：基础网络与示例可用 | P1 | 计划 |
| FR-15 | platform-neoforge（NeoGradle·独立 includeBuild；**NeoForge 无 1.20.1，锚点取 1.20.2**）：基础网络与示例可用 | P1 | 计划 |
| FR-16 | 最新版本 26.2 适配（MC 今年最新版本号，新版号方案无 `1.` 前缀），验证架构前向可扩展 | P3 | 计划 |
| FR-17 | 脚手架发布与版本化：作为模板仓库（如 GitHub Template）发布 + VERSION 注入各平台构建产物 + 版本化/标签 | P3 | 计划 |
| FR-18 | 玩法开发者上手：克隆模板后如何在 L0 写玩法的文档 + 示例（非产品玩法） | P3 | 计划 |
| FR-19 | 跨端网络收发框架：基于 protocol 的 C2S/S2C 收发管线 + 各平台经 `TransportPort` 注册通道（具体平台见 FR-20），附若干发包示例 | P1 | 开发中 |
| FR-20 | 跨平台传输：Bukkit/Folia/Sponge（插件消息）+ Fabric/Forge/NeoForge 服务端（各网络 API）+ 单人世界（集成服内存回环）均实现 `TransportPort`；上层逻辑不变 | P1 | 计划 |
| FR-21 | 进服握手 + 客户端标识上报：握手协商后客户端上报**弱客户端标识**（默认基于可得弱硬件/系统属性 SHA-256，可伪造/可随机化，`MachineCodeProvider` 可插拔），服务端接收并回发消息 | P1 | 开发中 |
| FR-22 | 标识封禁：服务端**原生命令**按标识封禁/解封，被封标识再次进服**尽快踢出**（Bukkit 仅能进服后踢；标识不可信、可缺席，封禁为威慑非安全保证） | P1 | 计划 |
| FR-23 | 测试与 MVP 验收门：纯 JVM 单测 + **mod 加载器 GameTest「模拟服套件」（单人/集成 headless，`gradle runGameTest`，in-process 回环自动跑）** + **「realserver 套件」（真实专用服，服务端驱动：等待程序化客户端进入 → 触发场景 → 客户端与服务端双重断言、客户端回报、服务端聚合单一权威报告 pass/fail，镜像 AllinCore-New ADR-0020、见 ADR-0014）**——**二者均须在 MVP 完成并通过、作为 MVP 验收门**；Bukkit 家族/Sponge（无 GameTest）用 MockBukkit/测试设施 + 真实服手测达同等覆盖。realserver 维度需用户实机确认 | P1 | 计划 |
| FR-24 | 网络可靠性层（L1·平台无关，`TransportPort` 之上）：分片 + 有序重组(+CRC) + 重连/重同步 + 重组超时重请求；所有平台共享 | P1 | 开发中 |
| FR-25 | 融合服（CatServer 等 Forge+Bukkit）适配设计：以 Bukkit 入口加载 + FeatureGate HYBRID_FORGE_BUKKIT + 不变量细化（ADR-0008）；实跑需 1.12.2，属 P2 | P1 | 计划 |
| FR-26 | 平台能力示例：玩家事件（EventBusPort）+ 调度（SchedulerPort 含 Folia 区域经 FeatureGate）+ 持久化（PersistencePort），跨平台一致 | P1 | 计划 |
| FR-27 | 跨端消息/HUD 示例：server→client 下发 title/actionbar/toast/聊天消息 | P1 | 计划 |
| FR-28 | 会话 + 心跳示例：会话注册表/在线列表 + keepalive ping/pong + RTT（兼重连检测，联动重连重同步） | P1 | 计划 |
| FR-29 | 平台无关配置加载模块 `core-config`（共享 L1）：YAML / JSON 等配置文件加载为类型化模型 | P1 | 计划 |
| FR-30 | 客户端/服务端共享目录与资源路径模块 `core-paths` + `DataDirectoryPort`：预设标准位置，调用方引用、不自算路径 | P1 | 计划 |
| FR-31 | 自有 EventBus（L0 内核）：平台无关发布/订阅 + 域间事件转发，支撑功能域解耦与拆分；平台事件经适配器桥接入总线 | P1 | 计划 |

> 状态取值：计划 / 开发中 / 已交付@vX.Y.Z。优先级：P1(MVP) / P2 / P3。
> 标 `已交付` 有门：仅当该 FR 的 §6 / spec 验收标准全部满足、对应测试 + 实机验收通过后，由 `sdd-release-version` 发版时统一标 `已交付@vX.Y.Z`——开发/修复过程中不得自行预标。

## 5. 非功能需求（NFR）

- **兼容性**：L0–L2 核心严格 Java 8 字节码，可被 1.12 起的老平台加载；目标平台覆盖 Paper/Folia/Sponge/Fabric/Forge/NeoForge，目标版本锚点 1.12.2 / 1.20.1 / 1.21.1 / 26.2 且前向可扩展。其中 **26.2 为 Minecraft 今年最新版本号**（新版号方案，已无 `1.` 前缀；按 FR-16/P3 适配）。**仅锚点版本提供验证保证**；锚点之间的版本（如 1.13–1.19）按需新增 `vX_Y` 适配，**不承诺连续全覆盖**。**有效矩阵非笛卡尔积**：1.12.2 无 Fabric、NeoForge 始于 1.20.2、各平台版本节奏不同——每版本只在其真实存在的平台验证。L0–L2 的 Java 8 须以 `javac --release 8` 或 animal-sniffer 强制（仅锁 `sourceCompatibility` 不够，见 ADR-0004）。
- **可测试性**：L0 玩法逻辑零平台依赖，必须可在纯 JVM 下单元测试穷举（一级质量指标）。
- **分离度（核心指标）**：同一份 L0 字节码不经改动即可在不同平台运行；L0/L1 中不得出现任何平台原生类型 import。
- **性能**：不在游戏主线程 / tick 内做阻塞 IO 或远程调用；大批量数据走流式 / 分页（见全局性能约束）。
- **向后兼容**：协议变更经 `MIN_SUPPORTED` 协商，破坏性变更须在 CHANGELOG + ADR 写明迁移。
- **可维护性**：平台 / 版本差异收敛到 L3/L4 接口后，公共层不因新增平台/版本而改动。

## 6. 验收标准

整体"做完"（按分期分别验收，见 §7）的可验证判据：

- **L0 纯逻辑测试全绿**：核心领域逻辑与协议编解码有单元测试覆盖正常 / 边界 / 错误路径。
- **分离度静态校验通过**：自动化检查确认 L0/L1 无平台原生 import、依赖方向单向（可由构建期约束或测试断言守护）。
- **【MVP 验收门 · 自动化测试必须完成并通过】GameTest 两套**：① **模拟服 GameTest 套件**——mod 加载器单人 / 集成 headless 测试服（`gradle runGameTest`，可在 CI/本机自动跑）跑通网络收发 / 进服握手 / 标识上报与封禁 / 分片重组 / 重连重同步 / 基础示例；② **realserver GameTest 套件**——同套 GameTest 部署到**真实专用服**运行通过（实机维度，须用户确认）。**GameTest 仅覆盖 mod 加载器（Fabric/Forge/NeoForge）**；Bukkit 家族/Sponge（无 GameTest）以 MockBukkit + 真实服脚本化 / 手测达到同等场景覆盖。**这两套是 MVP 版本的验收门——未完成并通过即 MVP 未交付。**
- **【需用户实机确认】MVP 单端一致运行**：`smoke` 冒烟特性在**真实** Paper 服务端、Fabric 客户端/服务端、Forge 客户端/服务端上均正确运行且行为一致——此项有实机维度，**测试绿不替代真机能用**，须由用户在真实环境复验并确认通过。
- **【需用户实机确认】跨端跨平台桥接互通**（项目桥接价值的核心验收）：异构组合下冒烟特性经协议完成握手 + 版本协商 + 往返通信且行为正确，至少覆盖 **Fabric 客户端 ↔ Paper/Bukkit 服务端**、**Forge 客户端 ↔ Paper/Bukkit 服务端**、**Forge 客户端 ↔ Forge 服务端** 三组用户点名场景；须用户在真实异构环境复验确认。
- **平台/版本扩展验收**：每新增一个平台（P2/P3）或版本（P2/P3），其冒烟特性须在该目标上实机通过（用户确认）。

## 7. 分期（路线）

各期只描述主题 / 目标；**具体哪个 FR 属于哪期，以 §4 FR 表的优先级列为唯一来源**。

- **第一期（MVP）**：**证明"完全分离 + 跨端桥接"成立**——立起 L0–L4 全分层骨架与 SPI / 协议 / 版本适配机制，并让**全部目标平台**（Bukkit/Paper/Folia/Sponge/Fabric/Forge/NeoForge + 单人世界，核心锚点 1.20.1）跑通基础网络与同一份 L0 逻辑；以 Paper/Fabric/Forge 重点验证异构客户端 ↔ 服务端经协议桥接互通。此外把**基础跨端网络做扎实**（跨平台传输 + 务实可靠性层：分片/重组/重连重同步）并落地示例功能（进服握手 → 客户端标识上报 → 服务端消息 → 标识封禁，FR-19~FR-25）+ 初期基础示例（平台能力 / 跨端消息 / 会话心跳，FR-26~FR-28），让脚手架开箱即用；融合服（CatServer）适配设计就位、实跑随 1.12.2（P2）。

> 第一期**实施顺序**（评审）：先 **Paper + Fabric + Forge** 跑通"一份 L0 逻辑 + 一次往返 + 握手"作骨架证明（FR-11 两证），再铺 Folia/Sponge/NeoForge 与完整可靠性 / 三组示例——"全平台跑通"是 P1 收尾目标、非 MVP 必过门。NeoForge 锚点取 1.20.2（无 1.20.1）。客户端侧**渲染必在 L3/L4、各平台各版本各写**，"写一次"主要在协议 / 状态 / 输入意图层（core-client 较薄），不夸大客户端复用。
- **第二期**：**沿版本轴铺开**——1.21.1 / 1.12.2 验证 L4 跨版本；CatServer 实跑（需 1.12.2）。
- **第三期**：**规模化与对外**——最新版本（26.2）、脚手架模板化发布、玩法开发者上手文档与示例。

> 分期是少数粗粒度阶段，不随 FR 增长而改。某期是否完成看 §4 表里该期 FR 状态是否都 `已交付`。

## 8. 术语表

- **L0–L4**：分层编号，L0 最内（纯功能域）、L4 最外（版本适配）。详见 ARCHITECTURE §2。
- **端口（Port）**：L0 声明的、对外界能力的抽象接口（六边形架构的 port）。
- **胶水（Glue）**：L3/L4 中把平台 / 版本原生 API 适配成端口实现的代码。
- **SPI**：Service Provider Interface，平台实现者需实现的接口集，经 ServiceLoader 发现。
- **Holder（PlatformProvider）**：运行期访问当前平台能力的静态访问点。
- **FeatureGate**：运行期能力探测 / 特性开关，承载平台与版本"特判"。问"当前环境是否支持某能力"（如 Folia 区域调度、某版本 API）而非"你是哪个平台"，据此分流；与端口配合，让一份 L0 逻辑跑遍异构平台。接口在 L2 platform-spi、各平台 L3 实现。
- **特判**：针对特定平台 / 版本的特殊处理（如 Folia 区域调度），经 FeatureGate + 版本接口收敛，不写成散落 if-else。
- **锚点版本**：明确支持并验证的 MC 版本基准：1.12.2 / 1.20.1 / 1.21.1 / 26.2。**26.2 是 MC 今年最新版本号**——MC 在 26.x 改用新版号方案、不再带 `1.` 前缀（故其版本适配模块为 `v26_2`，旧版本仍 `v1_12` 等）。仅锚点提供验证保证，锚点之间版本按需新增 `vX_Y`、不承诺连续全覆盖。
- **loader（加载器）**：Fabric / Forge / NeoForge 等模组加载器。
- **NMS**：net.minecraft.server 内部实现，跨版本漂移剧烈，由 L4 隔离。
