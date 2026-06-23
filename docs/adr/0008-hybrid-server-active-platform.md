# ADR-0008：融合服务端支持与"活跃平台"语义细化（细化 ADR-0002）

## 状态
已接受

## 背景
需支持 **CatServer**（Forge + Bukkit 融合服务端，仅存在于 MC 1.12.2；同类还有 Mohist / Magma / Arclight）。但 [ADR-0002](0002-platform-abstraction-spi.md) 的不变量写明"每进程有且仅有一个活跃平台；多平台并存 = 配置错误，启动期失败快"——而融合服进程里 **Bukkit 与 Forge 本就同时存在**，与该条直接冲突。

本 ADR **细化 ADR-0002 的"单一活跃平台"语义**，不取代其 SPI + ServiceLoader + PlatformProvider 机制（那部分仍有效）。

## 决策
1. **区分两个概念**：
   - **进程内存在的平台**：融合服上合法地有 >1（Bukkit 与 Forge 同在）。
   - **我们绑定的唯一活跃平台**：始终恰好 1。
2. **融合服上以 Bukkit 插件身份加载**（CatServer 等兼容 Bukkit API），绑定 `platform-bukkit` 家族为唯一活跃平台；**不激活 Forge 入口**。契合"Bukkit 家族单构建"（ADR-0007）。
3. `FeatureGate` 增 `HYBRID_FORGE_BUKKIT` 能力（按 CatServer/Mohist/Magma/Arclight 标志类存在判定），供融合环境下网络通道注册 / 调度等差异适配。
4. **"失败快"规则细化为**：**我们的多个入口同时激活**（例如同一融合服上既装了我方 Bukkit 插件又装了我方 Forge mod）才是配置错误、启动期失败快；**平台并存本身不再自动失败**。
5. **实跑 CatServer 需 1.12.2 锚点**（`platform-bukkit` + Forge 客户端 @1.12.2），属 P2；本期只落地**适配设计 + FeatureGate 钩子 + 本不变量细化**，不实跑验证。

## 理由
- 融合服是 1.12.2 生态常见部署，"支持 Bukkit 插件"是其核心价值，以 Bukkit 入口收敛最自然。
- 用 FeatureGate 承载融合环境特判，符合 ADR-0002 的"特判经能力探测"原则。
- 仅细化一条语义、不推翻 SPI 机制，改动面最小。

## 后果
- 正面：支持融合服而不破坏"唯一活跃绑定"的简单性。
- 负面：需可靠探测融合环境，以及探测"我方双入口同时激活"这一新的配置错误。
- 约束（写入 architecture-invariants）：融合服上只激活一个我方入口（Bukkit）；禁止我方多入口在同进程同时激活。

## 备选方案
- **CatServer 当独立平台**：过度，且违背其 Bukkit 兼容事实——否决。
- **维持原"多平台并存即失败"、不支持融合服**：用户明确要求支持——否决。
- **融合服上同时激活 Bukkit + Forge 两套我方胶水**：重复、状态二义、违背单一活跃绑定——否决。
