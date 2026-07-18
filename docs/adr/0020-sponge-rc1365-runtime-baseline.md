# ADR-0020：Sponge 第一期开箱运行基线固定为 RC1365 与 Java 17

## 状态
已接受

## 背景

Sponge 第一阶段需要在 Minecraft 1.20.1 的真实可部署服务端上完成产品网络、平台能力、跨端 HUD 与 realserver 验收。此前动态 SpongeAPI SNAPSHOT 已演进到不同的连接状态 API 与 Java 21 字节码，而可部署的 SpongeVanilla 1.20.1 `11.0.0-RC1365` 使用 Java 17 和较早的连接回调模型，导致“编译通过”不能代表插件可在目标服务端开箱运行。

本决策是对既有 [ADR-0004](0004-java8-core-lombok.md) 的平台胶水 JDK 策略、[ADR-0007](0007-composite-build-loader-isolation.md) 的 Sponge 独立构建策略和 [ADR-0014](0014-realserver-acceptance-harness.md) 的真实服务端验收策略所做的 Sponge 平台长期细化。它不取代这些 ADR，也不修改其正文或通用约束。

## 决策

Sponge 第一期开箱运行基线固定为 SpongeVanilla 1.20.1 `11.0.0-RC1365` 与 Java 17；编译类路径固定为 `org.spongepowered:spongeapi:11.0.0-20230826.165715-4`，其 SHA-256 为 `1278386c819b2009d69241e3b9356b44c3be247e7da7ea21be42aceb444459e3`。

同时采用以下约束：

- SpongeGradle 插件元数据 `apiVersion` 保持 `11.0.0-SNAPSHOT`，仅 Gradle 依赖解析固定到上述时间戳制品。
- SpongeAPI 由运行平台提供，不 shade 进产品 jar 或 acceptance jar。
- 产品通道与验收控制通道统一使用 `RawPlayDataHandler<ServerPlayerConnection>`。
- `ServerSideConnectionEvent.Disconnect` 使用 `event.profile()` 取得离开玩家资料。
- L0–L2、协议字节、UUID 连接句柄和 Fabric 客户端不降级。
- 未来采用新连接状态 API 的 Sponge 服务端作为独立后续适配，不在 RC1365 产物中加入反射双兼容。
- PRD 在开发期保持开发中，正式发版时由 release 流程统一标记已交付。

## 理由

- RC1365 是当前第一期目标中能够真实部署并完成端到端验收的 SpongeVanilla 1.20.1 服务端，固定它能把“可编译”收敛为“可运行、可验证”。
- 固定时间戳 SpongeAPI 制品及校验值消除动态 SNAPSHOT 漂移，使构建输入可复现，并与 RC1365 的运行时二进制保持一致。
- 保留 SpongeGradle 元数据 `apiVersion` 可避免把插件声明与依赖解析机制混为一谈；依赖坐标才是编译类路径的固定点。
- 旧连接回调只封装在 Sponge L3 胶水内，因此无需改动 L0–L2、产品协议或 Fabric 客户端，兼容收敛不会扩散到共享架构。
- 独立后续适配比反射双兼容更容易测试、诊断和维护，也符合 ADR-0007 的加载器隔离与 ADR-0014 的实机证据要求。
- 2026-07-18 的真实服务端报告已经证明能力场景与网络冒烟场景均通过，决策有可重复的验收依据，而非仅基于 API 推断。

## 后果

- `platform-sponge` 的构建与运行均以 Java 17 为基线，第一期部署说明不再把 Java 21 作为 Sponge 要求。
- SpongeAPI 依赖升级必须显式修改固定坐标与校验信息，并重新执行 Sponge 构建门、Fabric 客户端回归门和真实服务端验收。
- 产品和验收网络代码依赖 RC1365 的 `ServerPlayerConnection` 回调模型；未来新 API 适配需要单独实现并单独验收。
- 产品 jar 与 acceptance jar 继续保持 SpongeAPI 外置，避免重复类、类加载冲突和不必要的产物膨胀。
- 第一阶段获得明确的开箱运行组合：Java 17 + SpongeVanilla RC1365 + 固定时间戳 SpongeAPI 编译制品。
- 权威 realserver 证据为 `.tmp/sponge-rc1365-realserver/acceptance-sponge-realserver-2026-07-18.txt`，包含 `capability-first-join` 与 `smoke` 两项 PASS，汇总为 `TOTAL 2 PASS 2`，末行 `RESULT PASS`。
- harness 仍可后续做两项非阻塞加固：复用运行目录前清理首次加入状态；运行前确保报告路径新鲜且可写。这两项不构成本次 P1 阻塞。

## 备选方案

- **继续等待采用新连接状态 API 的可部署 SpongeVanilla 服务端**：不采用。该方案没有确定交付时间，会让第一期长期停留在编译级验证，无法满足真实服务端验收目标。
- **在同一产物中用反射兼容两套连接 API**：不采用。它增加运行期分支、弱化类型安全并扩大测试矩阵，且把平台版本差异隐蔽在反射中，不符合独立适配和失败快原则。
- **放弃 Sponge P1**：不采用。Sponge 已在第一期范围内，RC1365 路线能够在不降级共享层与客户端的前提下完成构建和真服验收，放弃缺乏必要性。
