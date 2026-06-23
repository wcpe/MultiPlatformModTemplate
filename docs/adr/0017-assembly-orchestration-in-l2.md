# ADR-0017：平台发现 / 装配编排归属 L2 platform-spi（细化 ADR-0002）

## 状态
已接受

## 背景
[ADR-0002](0002-platform-abstraction-spi.md) 表述为"`core-runtime` 启动时发现唯一活跃平台……注入 `PlatformProvider`"。但这与 [ADR-0001](0001-layered-architecture.md) 的依赖方向冲突：SPI 接口（`PlatformBootstrap`）、`PlatformProvider`(Holder)、`FeatureGate` 都在 **L2 `platform-spi`**，而 `core-runtime` 是 **L1**；L1 不得依赖 L2（依赖只向内）。若让 L1 做 ServiceLoader 发现，就要 import L2 类型，触红线。需明确"发现 / 装配编排"到底放哪一层。

## 决策
- **平台发现与装配编排归属 L2 `platform-spi`**：由 `PlatformProvider.boot(ClassLoader, MpmtRuntime)` 驱动——经 `PlatformAssembler` 用 `ServiceLoader` 发现唯一活跃平台（零 / 多入口失败快），调平台 `assemble` 把端口注入 L1 运行时的 `RuntimePorts`，再固化平台标识与 `FeatureGate` 为只读 Holder。
- **L1 `core-runtime` 只做"接收侧"**：提供 `RuntimePorts`（被注入）、`Feature`/`FeatureRegistry`、生命周期；**不发现、不依赖 L2**，对"谁来装配"无感。
- 本 ADR **细化而非取代** ADR-0002：其"SPI + ServiceLoader + PlatformProvider(Holder) + 启动期一次性装配后只读"的核心决策完全保留，仅把"由谁执行发现"的执行边界落到 L2，以满足 ADR-0001。

## 理由
- 唯一不触犯 ADR-0001 依赖方向红线的落法：发现要 import L2 的 SPI 类型，故必须在 L2（或更外层）执行，不能在 L1。
- L1 保持纯净、可纯 JVM 测试（用假端口 / 假特性），平台发现的集成测试归 L2（用假平台 + `META-INF/services`）。
- 与 ADR-0002 的访问点（Holder）语义一致，只是把编排执行点说清楚。

## 后果
- 正面：分层红线不破；L1/L2 职责清晰；两层各自可独立测试。
- 负面：`PlatformProvider` 同时承担"Holder + 装配编排"双职责（boot + 只读访问），需以"装配后只读、不承载可变业务状态"约束（ADR-0002 已有此约束）。
- 约束：L3 平台入口在进程启动时调用 `PlatformProvider.boot(本模块ClassLoader, runtime)`；跨隔离类加载器的多入口探测（融合服）属 L3，平台级共享存储另行实现（ADR-0002 / ADR-0008 已列）。

## 备选方案
- **L1 core-runtime 自做发现**：需 L1 import L2 SPI，违反 ADR-0001——否决。
- **新设独立 bootstrap 模块（L2.5）专做装配**：当前仅一处编排，单独模块属过度设计——否决（YAGNI），需要时再拆。
