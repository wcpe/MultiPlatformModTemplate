# 架构决策记录（ADR）

记录本项目的重大架构决策：背景、决策、理由、后果与被否的备选。每条决策一页，便于后来者理解"为什么是这样"。

| 编号 | 决策 | 状态 |
|---|---|---|
| [0001](0001-layered-architecture.md) | 采用六边形分层架构（L0–L4，依赖只向内） | 已接受 |
| [0002](0002-platform-abstraction-spi.md) | 平台抽象用 SPI + ServiceLoader + PlatformProvider(Holder) | 已接受 |
| [0003](0003-multi-version-adapter.md) | 多版本支持用 L4 版本适配层（version-api + vX_Y） | 已接受 |
| [0004](0004-java8-core-lombok.md) | 核心层 Java 8 + Lombok，平台胶水随各 loader JDK | 已接受 |
| [0005](0005-build-toolchain.md) | 构建用自定义多模块 Gradle，不用 Architectury | 已被 [0007](0007-composite-build-loader-isolation.md) 取代 |
| [0006](0006-cross-end-protocol.md) | 跨端通信用自定义协议 + 版本协商，经 TransportPort 适配 | 已接受 |
| [0007](0007-composite-build-loader-isolation.md) | 用 Gradle 复合构建隔离各加载器工具链，Bukkit 家族按系列收敛 | 已接受 |
| [0008](0008-hybrid-server-active-platform.md) | 融合服务端支持与"活跃平台"语义细化（细化 ADR-0002） | 已接受 |
| [0009](0009-command-config-framework.md) | 命令框架策略：各平台用各自原生命令框架（不引入 TabooLib），入口 L3、执行抽到共享 | 已接受 |
| [0010](0010-config-and-resource-paths.md) | 配置与资源路径：平台无关共享模块（YAML/JSON 加载 + 预设目录/资源位置） | 已接受 |
| [0011](0011-eventbus-domain-decoupling.md) | 功能域事件驱动解耦：自有 EventBus 作域间转发，域间禁止直接 / 循环依赖 | 已接受 |
| [0012](0012-packaging-and-dependency-isolation.md) | 打包与依赖隔离：第三方依赖 relocate、core 进各平台产物的方式 | 已接受 |
| [0013](0013-threading-and-scheduling.md) | 线程模型与归属调度：Folia 无主线程，SchedulerPort 按归属调度 | 已接受 |
| [0014](0014-realserver-acceptance-harness.md) | realserver 验收：服务端驱动 / 客户端验证 / 单一权威报告（镜像 AllinCore-New ADR-0020） | 已接受 |
| [0015](0015-domain-organization.md) | 功能域组织与拆分约定：域模板 + 注册 + 包→模块成长，不预建空壳 | 已接受 |
| [0016](0016-mappings-policy.md) | 反混淆映射策略：锚点有官方映射用 Mojmap，无官方走各 loader 自带 | 已接受 |

> 模板：状态 / 背景 / 决策 / 理由 / 后果 / 备选方案，见 [`_template.md`](_template.md)。

> **别慌通读**：ADR 有意稀少（只为重大决策写），理解现状看 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)，ADR 只按需查"为什么"；被取代的归档不打扰，当前架构 = 未取代的活跃集。增长过快是滥写信号——日常变更归 PRD 状态列 + CHANGELOG。
