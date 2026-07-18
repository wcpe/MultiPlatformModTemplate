# 功能规格：SpongeVanilla RC1365 兼容与 realserver 验收

> 状态：已完成（2026-07-18）　·　关联 PRD：FR-14、FR-20、FR-23、FR-26、FR-27　·　分支：dev

## 1. 背景与目标

第一期要求 `platform-sponge` 在 Minecraft 1.20.1 真实服务端上跑通基础网络、平台能力示例、跨端 HUD 与 realserver 验收。

最终可部署基线确定为 SpongeVanilla 1.20.1 `11.0.0-RC1365` 与 Java 17。为避免动态 SNAPSHOT 漂移，编译类路径固定到与 RC1365 兼容的时间戳 SpongeAPI 制品；产品通道与验收控制通道统一使用 RC1365 的 `RawPlayDataHandler<ServerPlayerConnection>` 回调模型。

本规格完成第一期 Sponge 可交付基线收敛，并取得真实服务端权威报告 `RESULT PASS`。未来若采用新连接状态 API 的可部署服务端成为目标，将作为独立后续适配实现，不在同一产物内做反射双兼容。

## 2. 需求（要什么）

- `platform-sponge` 的第一期运行基线固定为 SpongeVanilla 1.20.1 服务端发行版 `11.0.0-RC1365` 与 Java 17。
- 编译类路径固定为 `org.spongepowered:spongeapi:11.0.0-20230826.165715-4`，制品 SHA-256 为 `1278386c819b2009d69241e3b9356b44c3be247e7da7ea21be42aceb444459e3`。
- SpongeGradle 插件元数据 `apiVersion` 保持 `11.0.0-SNAPSHOT`；该值是插件元数据声明，不参与依赖版本选择。仅 Gradle 依赖解析固定到上述时间戳制品。
- SpongeAPI 保持平台提供依赖，不得 shade 进产品 jar 或 acceptance jar。
- 产品通道与验收控制通道均使用 `RawPlayDataHandler<ServerPlayerConnection>`，从连接取得玩家身份后转交现有 UUID 连接句柄与上层网络服务。
- `ServerSideConnectionEvent.Disconnect` 使用 `event.profile()` 取得离开玩家资料。
- 保持现有 `TransportPort`、L0–L2、协议字节、UUID 连接句柄、Fabric 客户端与上层网络特性不变，不因 Sponge 运行基线调整而降级。
- 真实服务端验收按 ADR-0014 完成完整链路：程序化 Fabric 客户端上报 `ClientReady` → 服务端驱动场景 → 控制通道下发 `RunStep` → 客户端验证 → 回传 `StepResult` → 服务端同时断言自身状态与客户端结果 → 写单一权威报告。
- realserver 场景必须同时证明：
  - Fabric 验收伴侣成功连入 RC1365；
  - 验收控制通道 C2S/S2C 往返成功，但控制通道结果不替代产品通道断言；
  - 产品通道 C2S 握手被 Sponge 服务端接收并完成状态迁移与版本协商；
  - 产品通道 S2C ACTIONBAR 代表性 HUD 被 Fabric 客户端接收并验证，其余 TITLE/TOAST/CHAT 类型继续由共享协议与 Fabric 客户端自动化测试覆盖；
  - FR-26 能力场景验证玩家加入事件桥接、`SchedulerPort` 调度、`PersistencePort` 持久化、`DataDirectoryPort` 数据目录与 `MessagePort` 消息发送；
  - 服务端权威报告末行为 `RESULT PASS`。
- 新增 ADR-0020，长期记录第一期选择 RC1365/Java 17 可部署基线、固定 API 制品策略及未来新版适配边界。

- 范围内：`platform-sponge` 构建基线、产品/验收网络适配、必要的旧 API 生命周期兼容、Sponge 模块测试、网络与能力 realserver 场景、RC1365 编排与正式文档同步。
- 不做（范围外）：同一产物同时兼容 RC1365 与未来新连接状态 API；反射兼容层；修改 L0–L2、协议或其他平台；降低 Fabric 客户端能力；提前实现 1.21.1、1.12.2 或 26.2 Sponge 适配；把 acceptance 适配代码打入产品 jar。

## 3. 设计（怎么做）

- 构建基线：
  - `platform-sponge` 使用 Java 17。
  - Gradle 编译依赖固定为 `org.spongepowered:spongeapi:11.0.0-20230826.165715-4`，并以 SHA-256 `1278386c819b2009d69241e3b9356b44c3be247e7da7ea21be42aceb444459e3` 记录已核验制品。
  - SpongeGradle 的 `apiVersion` 保持 `11.0.0-SNAPSHOT`；产品与验收插件元数据保持既有声明，仅依赖解析使用固定时间戳制品。
  - SpongeAPI 仅用于编译，不进入任何 shade 产物。
- 产品网络：
  - `SpongeServerTransport` 使用 `RawPlayDataHandler<ServerPlayerConnection>` 回调。
  - 在 L3 内从 `ServerPlayerConnection` 取得当前玩家与 UUID，再包装为现有 `SpongeConnectionHandle`；平台对象不泄漏到 L0/L1。
  - 发送路径继续按 UUID 获取最新在线玩家，避免重连后持有失效平台对象。
- 验收网络：
  - `SpongeAcceptanceControlChannel` 使用相同的 `RawPlayDataHandler<ServerPlayerConnection>` 模型，仅负责控制协议入站转发，继续与产品通道隔离且不进入产品 jar。
  - `SpongeSmokeServerScenario` 同时断言产品握手服务端状态与客户端 ACTIONBAR HUD；只有服务端状态和客户端 `StepResult` 均通过，场景才记为 PASS。
  - `SpongeCapabilityServerScenario` 驱动真实玩家加入后的事件桥接、调度、持久化、数据目录与消息端口，并把结果纳入同一权威报告。
- 生命周期与能力：
  - 入口、通道注册、服务端启动、玩家加入/离开、停服与资源释放按 RC1365 API 编译；`ServerSideConnectionEvent.Disconnect` 通过 `event.profile()` 取得资料。
  - 网络回调触碰领域状态或游戏对象前仍经 `SchedulerPort` 切到 Sponge 服务端线程，不在网络线程直接操作游戏状态。
  - 未发生兼容差异的 capability 类不扩大重构。
- 未来边界：
  - 未来新连接状态 API 作为独立后续适配，不在 RC1365 产物中加入反射分支或双 API 兼容层。
  - 新适配不得改写 L0–L2、协议或 Fabric 客户端既有契约。
- 文档同步：以本规格、ADR-0020、`docs/ARCHITECTURE.md` 与 `CHANGELOG.md` 记录最终事实；PRD 保持开发中，正式发版时由 release 流程统一标记已交付。

## 4. 任务拆分

- [x] 从 RC1365 兼容制品确定并记录 SpongeAPI 固定坐标与 SHA-256。
- [x] 建立 Java 17 与固定旧 API 编译基线，移除对不兼容新连接状态 API 的链接。
- [x] 为 `RawPlayDataHandler<ServerPlayerConnection>` 下的产品通道与验收控制通道补充自动化测试。
- [x] 将 `SpongeServerTransport` 与 `SpongeAcceptanceControlChannel` 迁移到 RC1365 连接模型。
- [x] 按编译和实机结果最小修复插件生命周期与 capability API 差异，包括 `ServerSideConnectionEvent.Disconnect#profile()`。
- [x] 增加产品 C2S 握手成功的 realserver 服务端状态断言。
- [x] 增加 FR-26 能力 realserver 场景，覆盖玩家事件、调度、持久化、数据目录与消息端口。
- [x] 构建产品插件与独立 acceptance 插件，检查依赖隔离和插件元数据。
- [x] 在 `127.0.0.1:25599` 启动 Java 17 的 SpongeVanilla RC1365，以 Fabric 验收伴侣连接并取得新鲜 `RESULT PASS`。
- [x] 新增 ADR-0020，并同步 ARCHITECTURE、ADR 索引、CHANGELOG 与 CONTRIBUTING，替换旧 Sponge 阻断描述。

## 5. 验收结果

- Sponge 质量门通过：`./gradlew -Dnet.minecraftforge.gradle.check.certs=false -p platform-sponge clean check shadowJar acceptanceJar`，结果 `BUILD SUCCESSFUL`。
- Fabric 客户端回归门通过：`test gametestClasses`，结果 `BUILD SUCCESSFUL`。
- 产品 jar 与 acceptance jar 均不打入 SpongeAPI；产品 jar 保持 core shade 与 SnakeYAML relocate，acceptance jar 保持测试设施自包含、控制通道与产品通道隔离且不污染产品协议。
- 真实服务端环境：Java 17、`127.0.0.1:25599`、SpongeVanilla 1.20.1 `11.0.0-RC1365`。
- 权威报告：`.tmp/sponge-rc1365-realserver/acceptance-sponge-realserver-2026-07-18.txt`。
- 报告结果：
  - `acceptance/capability-first-join`：PASS；
  - `acceptance/smoke`：PASS；
  - `TOTAL 2 PASS 2 FAIL 0 ERROR 0 SKIP 0`；
  - 末行 `RESULT PASS`。
- 验收证明 Fabric 客户端连入、控制通道往返、产品握手、ACTIONBAR HUD、玩家事件桥接、调度、持久化、数据目录与消息发送均在 RC1365 真服链路成立。

## 6. 风险与后续边界

- RC1365 是较早的候选服务端版本；本路线承诺的是第一期可部署、可构建、可验收的稳定基线，不代表自动兼容未来 Sponge 服务端/API。
- 固定时间戳制品避免动态 SNAPSHOT 漂移；升级该坐标、校验值或运行基线必须作为独立适配重新构建并执行 realserver 验收。
- 未来新连接状态 API 必须以独立适配推进，不采用反射双兼容，不降低 L0–L2、协议或 Fabric 客户端能力。
- 以下为两项**非阻塞** harness 加固建议，不影响本次 P1 完成判定：
  - 复用既有 realserver 运行目录前，清理 capability 首次加入状态，避免历史持久化数据影响 `capability-first-join` 场景的新鲜性。
  - 启动验收前，确认权威报告路径可写并清理旧报告，确保读取的是本轮新鲜结果。
