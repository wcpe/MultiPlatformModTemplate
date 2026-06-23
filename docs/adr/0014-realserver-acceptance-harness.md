# ADR-0014：realserver 验收测试架构——服务端驱动、客户端验证、单一权威报告

## 状态
已接受

## 背景
MVP 验收门要求 realserver 测试：**等待客户端进入 → 触发场景 → 客户端与服务端双重断言**（FR-23 / PRD §6）。参考项目 **`D:\Projects\AllinCore-New`** 已落地此模式（其 ADR-0020「server drives, client verifies」+ 单一权威报告），含 `platform/bukkit/gametest-support`、`acceptance-driver`、测试控制协议、Gradle 插件 `runRealServerAcceptance`。本脚手架**镜像该成熟实现**，不另起炉灶。

> 勘误：先前误查了 `D:\Projects\AllinCore`（旧项目，仅 Tier1/2、无此实现）；正确参考是 **AllinCore-New**。

## 决策
1. **服务端驱动、客户端验证**：服务端场景（`ServerScenario`）`setup`（主线程 `onMain`）→ `drive`（**直调真实 API、非命令**）→ `runClientStep(stepId, paramsJson, timeoutMs)` 下发 **S2C `RunStep`** → 客户端跑验证器 → 回 **C2S `StepResult{seq,status,resultJson}`**（`seq` 匹配 `CompletableFuture` + 超时）→ 服务端**断言两端结果 + 自身状态**。
2. **单一权威报告 + Gradle 门禁**：服务端聚合所有结果写 `RESULT PASS|FAIL`（含 PASS/FAIL/ERROR/SKIP + TOTAL）报告；Gradle 任务读 `RESULT` 行，非 PASS 即构建失败。
3. **测试控制协议独立于产品协议**：手写 codec、**仅 test 作用域、不入产品 jar / 产品协议**（避免测试代码污染产品）。
4. **等待进入 + 分层超时 + 看门狗**：`awaitClientReady`（`CountDownLatch` + C2S `ClientReady` 包）；client-ready / client-step / per-scenario / 绝对 deadline 多层超时；看门狗守护线程超时写 fallback 报告并 `halt`，防卡死。
5. **客户端是程序化 gametest 实例**（经真实网络连入），**非人工玩家**；故 realserver 可自动化，但需在能下载 MC / 映射并起进程的机器上跑（**用户本机**），不在受限 CI 环境。
6. **模块划分镜像**：服务端 `gametest-support`（`ServerGameTest` + `ServerGameTestContext`(onMain/waitTicks/assertTrue…) + `ServerGameTestRunner` + `Registry`）+ `acceptance-driver`（`ServerScenario` + `AcceptanceClient` 协调 + 控制通道/协议 + Bootstrap）+ 客户端 gametest 验证器 + Gradle 插件。

## 理由
- AllinCore-New 已验证此模式可靠（seq 匹配 future、latch 等待、单一权威报告、多层超时看门狗）；镜像成熟实现降低风险，契合"参考已验证工程"。
- "服务端驱动 + 单一权威"避免两端各自报告导致的权威分散与难聚合。

## 后果
- 正面：realserver 双端断言可自动化、有明确 pass/fail 门禁。
- 负面：需起真实服 + 客户端 gametest 两进程，重、需用户本机；**跨平台时各服务端的控制通道按其网络机制实现**（Bukkit 插件消息 / mod 加载器自定义包），且各服务端命令/收发时机差异同样适用（见 ADR-0006）。
- 约束：测试控制协议不入产品；realserver 属实机维度、用户确认；模拟服 headless 套件（in-process 回环）仍为可在 CI 自动跑的轻量门。

## 备选方案
- **客户端驱动 + 服务端听**（AllinCore-New ADR-0020 指出的旧模式）：权威分散、难聚合——否决。
- **纯 in-process 模拟充当 realserver 门**：覆盖不到真实网络 / 登录时序——不足，保留为"模拟服套件"另一道门。
