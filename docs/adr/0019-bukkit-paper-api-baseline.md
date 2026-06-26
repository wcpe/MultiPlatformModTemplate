# ADR-0019：Bukkit 家族编译基线改用 paper-api（compileOnly）以接 Folia 统一调度 API（细化 ADR-0007）

## 状态
已接受

## 背景
FR-13（Folia 支持）要按归属把任务调度到 Folia 的区域 / 实体线程，需调用 Folia 统一调度 API——`Bukkit#getRegionScheduler` / `getGlobalRegionScheduler` / `getAsyncScheduler`、`Entity#getScheduler`，及 `io.papermc.paper.threadedregions.scheduler.*`（`RegionScheduler`/`EntityScheduler`/`GlobalRegionScheduler`/`AsyncScheduler`/`ScheduledTask`）。这些类是 **Paper 在 spigot-api 之上新增**的 Folia 兼容调度 API，**spigot-api 没有**。

ADR-0007 原定 Bukkit 家族单一构建的编译基线为 spigot-api。直接编译引用 Folia 调度 API 在 spigot-api 下无法通过；备选的反射调用冗长、易错、且笔误只在真实 Folia 服运行期才暴露（无编译校验）。

## 决策
Bukkit 家族单一构建的**编译基线（compileOnly）从 spigot-api 改为 paper-api**（`io.papermc.paper:paper-api:1.20.1-R0.1-SNAPSHOT`，PaperMC 仓库；该仓库本已在 `repositories`）。

- paper-api 是 spigot-api 的**二进制超集**，现有 `org.bukkit.*` 引用照常编译，无需改动既有代码。
- **运行期仍是 Bukkit 家族**：`compileOnly` 不把 paper-api 打进产物、不强制 Paper 运行期；插件在纯 Spigot 上跑只用 spigot 子集，Folia 调度等 paper-only API **一律经 `FeatureGate`（`Capability.REGION_SCHEDULER` 等）门控**、非 Folia 不触发。
- **仍单一构建、不为 Folia 拆模块**（守 ADR-0007 核心不变量）。
- 本 ADR **细化 ADR-0007** 的"spigot-api 基线"为"**paper-api 编译基线、Bukkit 家族运行基线**"，ADR-0007 其余决策不变。

## 理由
- Folia 调度 API 只在 paper-api；编译期可见可校验，比反射稳、代码干净。
- paper-api 超集不破坏 Spigot 运行期兼容（运行基线仍是 Bukkit 家族）。
- 单一构建、运行期 FeatureGate 适配的架构（ADR-0007/0003/0013）完全保持。

## 后果
- 正面：Folia 区域调度 API 编译期可校验、`FoliaSchedulerPort` 代码直白；不引入反射脆弱性。
- 负面 / 约束：**paper-only API 调用必须经 `FeatureGate` 守卫**——在纯 Spigot（非 Paper）服上误调 paper-only 方法会运行期 `NoSuchMethodError`；Folia 是 Paper 衍生、必有这些 API，故"探测到 Folia 才调"安全。新增任何 paper-only API 使用同此约束。
- 编译依赖体量从 spigot-api 增至 paper-api（PaperMC 仓库已配置，无新增仓库）。

## 备选方案
- **反射调用 Folia 调度 API**（不改基线、零新依赖、纯运行期适配）：代码冗长、无编译校验、笔误只在真 Folia 服暴露——否决（用户选 paper-api）。
- **保留 spigot-api 并额外加 paper-api compileOnly**：二者同包（`org.bukkit.*`）在 classpath 上相互遮蔽、易出二义——否决，改为单一 paper-api 基线。
- **为 Folia 拆独立构建/模块**：违反 ADR-0007 单一构建不变量——否决。
