# ADR-0002：平台抽象用 SPI + ServiceLoader + PlatformProvider(Holder)

## 状态
已接受

## 背景
L0/L1 需要在运行期拿到"当前平台"的能力实现（端口实现），但又不能依赖任何具体平台。需要一种机制：平台胶水自我声明、核心层自动发现并装配，且对"任意服务端 + 任意客户端"组合开放。AllinCore 用 `AllinCoreProvider`（volatile 单例）做访问点、Fabric 入口手动注册，给了直接借鉴。

## 决策
- 在 L2 `platform-spi` 定义 SPI：`PlatformBootstrap`、`ServerAdapter`、`ClientAdapter` 及各端口工厂、`FeatureGate`。
- 平台胶水经 **Java `ServiceLoader`（`META-INF/services`）** 注册 `PlatformBootstrap`。
- `core-runtime` 启动时发现唯一活跃平台，调用其工厂构造端口实现，注入 **`PlatformProvider`**（Holder 访问点）与 L1。
- 运行期对平台能力的访问统一经 `PlatformProvider`，不在公共层硬编码任何平台分支。

## 理由
- `ServiceLoader` 是 JDK 原生、零额外依赖、Java 8 即可用，契合"简单优先 + 最大兼容"。
- Holder 提供稳定访问点，避免把平台对象层层透传。
- 新增平台 = 实现 SPI + 注册一行 services，扩展成本低（开闭）。

## 后果
- 正面：平台可插拔；核心层与平台零编译期耦合；易于为测试注入假平台（test double）。
- 负面：`PlatformProvider` 是全局访问点，须约束为"启动期一次性装配、之后只读"，禁止承载可变业务状态（防静态可变单例滥用）。
- 负面（实施注意，评审）：ServiceLoader 默认用线程上下文类加载器，在 Fabric/Forge/NeoForge 隔离 mod 类加载器与 Paper PluginClassLoader 下未必扫到本 jar 的 `META-INF/services`——调用须**显式传入本模块 ClassLoader**。
- 约束：每进程有且仅有一个活跃平台；"我方多入口同时激活失败快"（ADR-0008）在隔离类加载器下两 jar 的静态 Holder 互不可见，需借**平台级共享存储**（如 Bukkit ServicesManager / 固定系统属性 / 文件锁）跨类加载器探测，不能只靠静态字段。
- 约束：每进程有且仅有一个活跃平台；多平台同时存在视为配置错误并在启动期失败快。

## 备选方案
- **重型 DI 容器（Spring/Guice 等）**：超出需要、增加体积与启动成本、与各加载器类隔离冲突——否决（违反简单优先）。
- **编译期生成 + 硬编码入口（AllinCore 式 Fabric-only）**：无法满足"任意平台可插拔"，每加平台改核心——否决。
- **反射扫描自造发现**：重复造 ServiceLoader 的轮子且更易错——否决。
