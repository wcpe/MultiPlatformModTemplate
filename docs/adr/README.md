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
| [0007](0007-composite-build-loader-isolation.md) | 用 Gradle 复合构建隔离各加载器工具链，Bukkit 家族按系列收敛 | 已接受（决策 2 已被 [0026](0026-single-build-subproject-unification.md) 取代） |
| [0008](0008-hybrid-server-active-platform.md) | 融合服务端支持与"活跃平台"语义细化（细化 ADR-0002） | 已接受 |
| [0009](0009-command-config-framework.md) | 命令框架策略：各平台用各自原生命令框架（不引入 TabooLib），入口 L3、执行抽到共享 | 已接受 |
| [0010](0010-config-and-resource-paths.md) | 配置与资源路径：平台无关共享模块（YAML/JSON 加载 + 预设目录/资源位置） | 已接受 |
| [0011](0011-eventbus-domain-decoupling.md) | 功能域事件驱动解耦：自有 EventBus 作域间转发，域间禁止直接 / 循环依赖 | 已接受 |
| [0012](0012-packaging-and-dependency-isolation.md) | 打包与依赖隔离：第三方依赖 relocate、core 进各平台产物的方式 | 已接受 |
| [0013](0013-threading-and-scheduling.md) | 线程模型与归属调度：Folia 无主线程，SchedulerPort 按归属调度 | 已接受 |
| [0014](0014-realserver-acceptance-harness.md) | realserver 验收：服务端驱动 / 客户端验证 / 单一权威报告（镜像 AllinCore-New ADR-0020） | 已接受 |
| [0015](0015-domain-organization.md) | 功能域组织与拆分约定：域模板 + 注册 + 包→模块成长，不预建空壳 | 部分被 [0028](0028-mc-shared-cross-lane-source-layer.md) 取代（决策 1 / 4） |
| [0016](0016-mappings-policy.md) | 反混淆映射策略：仅适用于 26.1 前仍以混淆制品发布的版本 | 已被 [0022](0022-unobfuscated-minecraft-naming-policy.md) 取代 |
| [0017](0017-assembly-orchestration-in-l2.md) | 平台发现 / 装配编排归属 L2 platform-spi（细化 ADR-0002，守 L1⊄L2） | 已接受 |
| [0018](0018-forge-mixin-raw-payload.md) | Forge 端用 Mixin 拦截裸 CustomPayload 统一裸字节收发（取代 SimpleChannel，打通 Forge 客户端↔Bukkit 服） | 已接受 |
| [0019](0019-bukkit-paper-api-baseline.md) | Bukkit 家族编译基线改用 paper-api（compileOnly）以接 Folia 区域调度 API（细化 ADR-0007） | 已接受 |
| [0020](0020-sponge-rc1365-runtime-baseline.md) | Sponge 第一期开箱运行基线固定为 RC1365 与 Java 17（细化 ADR-0004/0007/0014） | 已接受 |
| [0021](0021-p2-version-matrix-toolchain-isolation.md) | P2 有效版本矩阵、工具链隔离与严格验收入口（本仓 ADR-0021；勿与 AllinCore-New ADR-0020 混淆） | 已接受（决策 2 已被 [0025](0025-wcpe-loom-toolchain-unification.md)/[0026](0026-single-build-subproject-unification.md) 取代） |
| [0022](0022-unobfuscated-minecraft-naming-policy.md) | Minecraft 26.1+ 使用无混淆原始命名，并按加载器采用非映射构建链路（取代 0016） | 已接受 |
| [0023](0023-p3-r7-automated-release-authority.md) | P3 R7 严格自动化验收作为 FR-16 的最终发布权威（细化 0014） | 已接受 |
| [0024](0024-github-actions-remote-gate-separation.md) | GitHub Actions 远端质量门与真服验收分离 | 已接受 |
| [0025](0025-wcpe-loom-toolchain-unification.md) | 构建插件统一 WCPE Loom（`top.wcpe.loom` 1.17.1）并收敛 Gradle 车道至 9.6.1（部分取代 0021 的工具链隔离） | 已接受 |
| [0026](0026-single-build-subproject-unification.md) | 平台车道统一为根构建子模块（取消加载器插件隔离，取代 0007 决策 2、0021 决策 2） | 已接受 |
| [0027](0027-build-convention-plugins.md) | 构建约定插件分层：公共流程进插件、车道只做配置（细化 0026） | 已接受 |
| [0028](0028-mc-shared-cross-lane-source-layer.md) | mc-shared 跨 lane MC 源码共享层 + 功能域模块化（取代 0015 决策 1 / 4） | 提议中（待审批） |
| [0029](0029-contract-tests-assert-artifacts-not-source-text.md) | 契约测试只断言产物 / 运行期行为 / 结构化数据，禁止读源码文本（收紧 0027 背景） | 已接受 |
| [0030](0030-domain-module-layout.md) | 功能域按 API / internal / 实现三段八模块组织，全部零 MC（细化 0028 决策 5，补充 0015 空壳口径） | 已接受 |
| [0031](0031-mc-shared-loader-dimension.md) | 跨 lane 共享源码增加「加载器」维度，与版本区间正交（细化 0028 决策 2/3） | 已弃用（mc-shared 定义上不碰加载器，加载器差异归 platform 层） |

| [0032](0032-wcpe-loom-local-publish-maven-groups.md) | 本机 Maven 兜底放开 wcpe-loom 的发布坐标组 `top.wcpe.loom` / `dev.architectury`（修订 0029 后果节的 mavenLocal 作用域） | 已接受 |
| [0033](0033-forge-artifact-namespace-named.md) | Forge 车道 patched jar 的重映射目标统一到 named，消除与运行期制品的命名空间并存 | 已弃用（实测证伪，见文内「证伪后的修正」） |
| [0034](0034-forge-member-supplementary-remap.md) | 按 Forge 自身两版 jar 的成员差异，对最终 jar 做补充成员名改写 | 提议中 |
| [0035](0035-acceptance-jar-does-not-shade-product-classes.md) | 验收插件不 shade 产品侧类，改由产品插件在运行期提供（修 ClassLoader 冲突） | 已接受 |
| [0036](0036-architecture-guard-structural-only.md) | 架构守卫只做结构性检查，分层约束交给依赖声明与 ArchUnit | 已接受 |

> 模板：状态 / 背景 / 决策 / 理由 / 后果 / 备选方案，见 [`_template.md`](_template.md)。

> **别慌通读**：ADR 有意稀少（只为重大决策写），理解现状看 [`../ARCHITECTURE.md`](../ARCHITECTURE.md)，ADR 只按需查"为什么"；被取代的归档不打扰，当前架构 = 未取代的活跃集。增长过快是滥写信号——日常变更归 PRD 状态列 + CHANGELOG。
