# ADR-0021：P2 有效版本矩阵、工具链隔离与严格验收入口

## 状态

已接受

## 背景

FR-12 要把 1.20.1 锚点扩展到 1.21.1 与 1.12.2，FR-25 同期要求 CatServer 1.12.2 实跑。目标横跨多代 Java、Gradle、ForgeGradle 与 CustomPayload API；若把版本组合、工具链与验收入口留给各模块自行决定，会出现无效矩阵、插件代际冲突、错误 JVM、线缆漂移及旧报告误放行。

本 ADR 是**本仓库的 ADR-0021**。外部参考记录必须写全称 **“AllinCore-New ADR-0020”**，不得用简称混淆。

本 ADR 细化而不取代 [ADR-0003](0003-multi-version-adapter.md)、[ADR-0004](0004-java8-core-lombok.md)、[ADR-0007](0007-composite-build-loader-isolation.md)、[ADR-0008](0008-hybrid-server-active-platform.md)、[ADR-0013](0013-threading-and-scheduling.md)、[ADR-0014](0014-realserver-acceptance-harness.md)、[ADR-0016](0016-mappings-policy.md)、[ADR-0018](0018-forge-mixin-raw-payload.md)、[ADR-0019](0019-bukkit-paper-api-baseline.md)。

完整版本、哈希、来源标识、工具链映射、通道、golden vectors、报告字段、required scenarios 与子门清单以 [`../specs/p2-version-matrix.md`](../specs/p2-version-matrix.md) 为唯一权威位置；本 ADR 不复制长清单。

## 决策

1. **有效矩阵非笛卡尔积**：1.21.1 只覆盖 Paper/Fabric/Forge；1.12.2 只覆盖 Bukkit/CatServer 与 Forge 客户端；Folia 固定为 1.20.1 回归。Sponge、NeoForge、26.2 不属于 P2。
2. **Forge 工具链按版本物理隔离**：1.20.1、1.21.1、1.12.2 各有唯一 launcher/Gradle/ForgeGradle/Forge/JDK 映射；一次配置只加载目标版本对应的 ForgeGradle 与 userdev。legacy 构建不加入根 `includeBuild`，只产客户端。
3. **L4 负责版本差异**：Bukkit/Fabric/Forge 现代构建按 `mpmt.minecraftVersion` 选择 L4；新旧通道注册、CustomPayload 与 Mixin 目标差异不得进入 L0–L2，产品 payload 字节保持一致。
4. **1.12 通道与产品/验收隔离固定**：产品通道与验收通道各自由对应 jar 独占，产品 jar 不携带验收控制逻辑；控制通道成功不能代替产品场景。
5. **线缆兼容由人工锁定 golden vectors 裁决**：先从基线生成，再由各版本 L4 对裸 payload 做逐字节回归；平台外层通道包装可不同。
6. **唯一聚合入口**：P2 只允许 **Gradle 任务** `./gradlew :runVersionMatrixGate`（历史别名 `runP2StrictCheck` 等价；真服子集 `runP2RealServerAcceptance`）聚合；编排在 `build-logic/realserver-acceptance` / 可选 mc-testkit；**禁止** `scripts/*.sh` 入口；不得使用 `buildAll`。串行、显式选择 Java 8/17/21，排除 Sponge/NeoForge/26.2；禁止嵌套 `gradlew`。全 lane（含 NeoForge/Sponge）另用 `:runRealServerAcceptance`。
7. **realserver 权威升级为 v2 报告**：报告绑定 runId、matrix、启动时间、双端 JVM、制品哈希与 required scenarios；validator 对缺失、重复、旧文件、SKIP/FAIL/ERROR、哈希/JDK/进程不符一律拒绝。
8. **CatServer 只激活 Bukkit**：Forge 1.12 客户端必须 client-only/optional，允许加入无我方 Forge 服务端 mod 的 CatServer；加入后才开始产品握手。融合服不变量任一失败即写 FAIL 并清理，不得降级为控制通道 PASS。
9. **测试先行且串行实施**：先守卫与失败测试，再按 Fabric 1.21、Forge 1.21、Bukkit 1.21/1.12、Forge legacy、realserver、严格门、文档推进。

## 理由

- 非笛卡尔积矩阵避免为空洞或不存在的组合制造构建与承诺。
- 唯一工具链映射和物理隔离能阻断 Gradle/ForgeGradle/JDK 代际污染。
- golden vectors 把“协议 payload 不变”从口头约束变为跨版本逐字节证据。
- 唯一聚合脚本与 v2 报告把构建、实机进程、制品和结果绑定到同一次运行，防止旧报告或仅控制通道通过。
- client-only/optional 是 Forge 客户端连接无我方 Forge mod 的 CatServer 的前置条件，必须纳入产品验收而非只测 Bukkit 控制通道。
- 把完整易变清单集中到 spec，ADR 只保留长期决策，避免双源漂移。

## 后果

- 正面：矩阵、工具链、线缆与验收权威唯一，P2 结果可复现、可审计。
- 正面：CatServer 上保持唯一 Bukkit 活跃平台，不引入无用途的 Forge 服务端产品。
- 负面：需维护三套 Java 与三条 Forge 构建车道，且严格门必须串行，耗时增加。
- 负面：报告生成器、validator、golden vectors 与脚本守卫都需新增测试。
- 约束：CatServer 固定制品虽已冻结 SHA 与大小，相关 realserver 每次启动前仍必须复核。
- 约束：P2 通过不等于全仓所有平台 realserver 通过，发布措辞必须保持范围边界。

## 备选方案

- **平台 × 版本全笛卡尔积**：包含不存在或非本期组合——否决。
- **多代 Forge 共用一个配置生命周期**：插件与 JDK 代际冲突——否决。
- **根 `buildAll` 作为 P2 聚合门**：会把非 P2 includeBuild 拉入依赖图，且不能表达实机顺序与显式 JVM——否决。
- **运行时实现反向生成 golden 期望值**：无法发现兼容性回退——否决。
- **旧版报告或控制通道 PASS 直接放行**：不能证明本轮产品链路与制品身份——否决。
- **CatServer 同装我方 Bukkit 插件与 Forge 服务端 mod**：违反唯一活跃平台不变量——否决。
