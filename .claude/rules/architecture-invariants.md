# 架构不变量（防架构漂移）

> 以下是本项目锁定的架构约束（依据 `docs/ARCHITECTURE.md` 与 `docs/adr/`）。**违反任一条即为架构漂移。**
> 确需改变某条 → 先写新 ADR 取代旧决策、经确认后再改；**禁止在代码里静默违背**。

## 1. 分层与依赖方向（依据 ADR-0001）
- 五层 L0→L4，**依赖只能由外层（下层）指向内层（上层）**：L0 不知道任何下层存在。
- **L0 `core-domain` 不得 import 任何平台 / Minecraft 原生类型**（无 `org.bukkit.*` / `net.minecraft.*` / `net.fabricmc.*` / `net.minecraftforge.*` / `net.neoforged.*` / `org.spongepowered.*`）。
- **L1（core-runtime / core-server / core-client / protocol）不得 import 平台原生类型**，只认 L0 端口与 L2 SPI。
- 平台细节只允许出现在 **L3/L4**。玩法逻辑只写在 L0，跨平台/跨版本差异全部被 L3/L4 关在接口之后。
- 平台对象（Bukkit `Player`、Minecraft `ServerPlayer` 等）**不得**进入 L0/L1，只能在 L3 内部包装为端口暴露的领域视图。
- **命令入口在 L3**，各平台用**原生命令框架**（Bukkit/Paper/Sponge 原生、Fabric/Forge/NeoForge Brigadier；**不引入 TabooLib**）；**命令 / 监听器的执行逻辑必须抽到共享 L0/L1**，L3 只做注册 + 参数解析 + 线程切换，**不在 L3 写执行逻辑**（依据 ADR-0009）。
- 配置与资源目录的**基路径只经 `DataDirectoryPort` 由平台提供**，共享模块（`core-config`/`core-paths`）只拼相对预设位置、**不硬编码绝对路径**；配置 / 路径平台无关、客户端服务端共用（依据 ADR-0010）。
- **功能域之间禁止直接依赖**（不 import 兄弟域），跨域协作经**自有 EventBus**转发；**禁止任何模块 / 域相互依赖与循环依赖**（全依赖图无环、同层无互依）（依据 ADR-0011）。

## 2. 平台抽象唯一机制（依据 ADR-0002）
- 平台能力的发现与装配**只走 SPI + `ServiceLoader` + `PlatformProvider`(Holder)**；公共层不得硬编码任何平台分支。
- `PlatformProvider` **只在启动期一次性装配、之后只读**，禁止承载可变业务状态（防静态可变单例滥用）。
- 每进程有且仅有一个**活跃平台绑定**（恰好 1）；但**进程内可存在多个平台**（融合服如 CatServer：Bukkit 与 Forge 同在，合法）——区分"存在"与"活跃绑定"。**配置错误是"我方多个入口同时激活"**（如同进程既装我方 Bukkit 插件又装 Forge mod），此时启动期失败快（依据 ADR-0008）。
- 融合服（CatServer/Mohist/Magma/Arclight 等 Forge+Bukkit）上以 **Bukkit 入口**加载、绑定 Bukkit 家族为唯一活跃平台，融合环境经 `FeatureGate.HYBRID_FORGE_BUKKIT` 探测；**不激活我方 Forge 入口**（依据 ADR-0008）。
- 新增平台=实现 SPI + 注册 `META-INF/services`，不改公共层。
- Bukkit 家族（Bukkit/Spigot/Paper/Folia）收敛为**单一插件构建**，Paper/Folia 差异（如 Folia 区域调度）运行期经 `FeatureGate` 适配，**不为其拆独立平台构建/模块**（依据 ADR-0007）。

## 3. 多版本差异收敛（依据 ADR-0003）
- **L0–L2 不得出现任何 MC 版本判断**；版本"特判"只允许在平台模块内的 `version-api` 实现（`vX_Y`）里。
- 平台 / 版本差异不写成散落 if-else/switch，经 `FeatureGate`（能力探测）+ 版本适配接口收敛。
- 锚点版本 1.12.2 / 1.20.1 / 1.21.1 / 26.2（26.2 为 MC 新版号方案、无 `1.` 前缀，模块 `v26_2`）；新增版本=加一个 `vX_Y` 模块，不改既有版本与公共层。

## 4. 技术栈与 Java 版本锁定（依据 ADR-0004 / ADR-0007）
- **L0–L2 严格编译为 Java 8 字节码**（`sourceCompatibility = 8`），不得使用 Java 9+ 语法 / API——须以 `javac --release 8` 或 animal-sniffer **强制**（仅锁 sourceCompatibility 不够，依据 ADR-0004）；Lombok 仅用于 L0/L1 Java 模块。
- 平台胶水（L3/L4）按各 loader 最低 JDK 编译，但仍依赖 Java 8 核心。
- 第三方运行期依赖（snakeyaml/gson 等）**统一 relocate 到 `top.wcpe.mc.mpmt.libs.*`**；core 打进各 loader 产物的方式逐平台明确、core 不被 remap（依据 ADR-0012）。
- 构建为自定义 Gradle **复合构建**（Kotlin DSL）：核心与 Bukkit 家族为根构建常规模块，带专属插件的平台（Fabric/Loom、Forge/ForgeGradle、NeoForge/NeoGradle、Sponge/SpongeGradle）各为经 `includeBuild` 引入的独立构建——**禁止把这些 loader 插件塞进同一构建**（依据 ADR-0007）。**不引入 Architectury** 或与之冲突的统包框架；换构建框架 = 架构决策，走新 ADR。

## 5. 跨端协议单一真源（依据 ADR-0006）
- 协议包定义 / 字节布局 / 版本号**只在 `protocol` 一处权威定义**，客户端与服务端共用，禁止双源各自定义。
- 跨端通信经握手**版本协商**（`MIN_SUPPORTED`），破坏性协议变更须 ADR + CHANGELOG 写明迁移并维护 `MIN_SUPPORTED`。
- 序列化不得依赖平台类型；底层收发只经 `TransportPort`。

## 红线（出现即停止并先确认）
L0/L1 出现平台或 MC 版本 import · 公共层硬编码平台/版本 if-else · 绕过 SPI/ServiceLoader 直连平台 · `PlatformProvider` 承载可变业务状态 · L0–L2 用 Java 9+ 特性或破坏 Java 8 兼容 · 把 Loom/ForgeGradle/NeoGradle/SpongeGradle 塞进同一构建 · 为 Folia/Paper 各拆独立构建/模块 · 我方多入口在同进程同时激活（融合服上既激活 Bukkit 又激活 Forge）· 在本脚手架自建命令框架或引入 TabooLib，或把命令执行逻辑写进 L3 平台层（应：各平台原生命令框架、入口 L3、执行逻辑抽到共享，见 ADR-0009）· 在共享层硬编码绝对路径而非经 DataDirectoryPort（见 ADR-0010）· 功能域间直接依赖或任何循环依赖（应经 EventBus 解耦，见 ADR-0011）· 用平台事件系统替代自有 EventBus 作域间总线 · 无归属 `runSync` 在 Folia 碰世界/实体态（应经 runForEntity/Location/Global，见 ADR-0013）· 第三方依赖未 relocate 直接打包（Bukkit 必类冲突，见 ADR-0012）· 引入 Architectury/重型 DI 作默认机制 · 协议双源定义或去掉版本协商 · 静默违背任一已接受 ADR。
