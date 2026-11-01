# 上手指南：克隆模板并编写自己的玩法

> 目标读者：想用本脚手架写自己玩法的开发者。
> 对应规格：[`specs/p3-platform-scaling-and-onboarding.md`](specs/p3-platform-scaling-and-onboarding.md) §4.3 / FR-18。
> 示例域：[`examples/counter`](../examples/counter)（纯 L0、零平台依赖、可纯 JVM 单测）。

## 1. 前置条件

| 项 | 说明 |
|---|---|
| JDK | 换名（`./init.sh`）**不需要 JDK**；编译与构建根工程须 **JDK 25**（26.2 两条车道在配置期硬校验，见 ADR-0026），1.12.2 车道另需 JDK 8 |
| Gradle | 用仓库自带 `./gradlew` wrapper，无需单独安装 |
| Git | 克隆 / 分支管理 |
| bash + perl | 仅 `init.sh` 需要（Git Bash / macOS / Linux 自带） |
| mc-testkit | 上游 plugin marker 当前不可用；根构建只会从本机 Maven 缓存回退解析插件及实现模块。首次配置失败时，先按 [`OPERATIONS.md`](OPERATIONS.md) 准备本地制品；此临时前提不等于冷缓存或新机器验证已通过。 |

## 2. 获取模板并重命名

两种方式任选；**换名步骤完全相同**，区别只是历史与后续同步方式。

| 方式 | 得到的仓库 | 适用 |
|---|---|---|
| **Use this template**（推荐） | **全新历史**（单条初始提交），与模板无共同祖先 | 开始自己的项目；不需要跟随模板后续更新 |
| `git clone` | **带上模板的完整开发史** | 想参考模板演进过程、或打算手动 merge 模板更新 |

```bash
# 方式一：在 GitHub 页面点 “Use this template” 创建新仓库，然后 clone 自己的仓库
git clone <你的新仓库 URL> mygame
cd mygame

# 方式二：直接克隆模板（会带上模板历史）
git clone <模板仓库 URL> mygame
cd mygame
```

换名（零 JDK 依赖，不需要先跑通 Gradle）：

```bash
# 预览（dry-run，不修改文件）
./init.sh --dry-run --id mygame --group com.example.mygame --name MyGame

# 确认无误后写盘
./init.sh --id mygame --group com.example.mygame --name MyGame
```

参数含义见 [`tools/README.md`](../tools/README.md)。
**产品化时**若要改协议通道名（`mpmt:main` 等），加 `--rewrite-channels`；互通双方必须同一通道。

> 换名完成后可删除 `init.sh`（它已完成使命）。

## 3. 写一个玩法域——以 Counter 为范本

`examples/counter` 是官方范本域，按 [ADR-0030](adr/0030-domain-module-layout.md) 的八模块标准组织。
它只演示「玩家加入时计数并提示」这一条规则，但已经完整跑通了从领域算法到平台装配的整条链路。

### 3.1 域的模块布局

八模块是标准天花板，**按需长出**：真的没有客户端差异的域就不建 `client-api` / `client-internal`。
counter 当前长到全量八模块，因为它真的要跑客户端显示：

| 模块 | 内容 | counter 里的实例 |
|---|---|---|
| `common-api` | 对客户端与服务端都暴露的契约 | `CounterKeys`（持久化键常量）、`CounterQueryPort`、`CounterJoinEvent` |
| `client-api` | 仅客户端可见的契约 | `CounterDisplayPort` |
| `server-api` | 仅服务端可见的契约 | `CounterAdminPort`（重置计数） |
| `core` | 纯算法，零平台、不碰端口实现 | `JoinCounter`（计数与首次加入时间） |
| `server-internal` | 服务端内部接口与 DTO | `CounterServerInstallPoint`、`CounterSnapshotReader`、`CounterSnapshot` |
| `client-internal` | 客户端内部接口与 DTO | `CounterClientInstallPoint`、`CounterHudPayload`、`CounterHudRenderer` |
| `server` | 服务端实现 | `CounterServerService`、`CounterServerInstaller` |
| `client` | 客户端实现 | `ChatCounterInstaller`、`ChatCounterDisplay` |

依赖方向（[ADR-0030](adr/0030-domain-module-layout.md) 决策 5）：

```
common-api  ←─ client-api ──→ client-internal ──→ client ──┐
     ↑      ←─ server-api ──→ server-internal ──→ server ──┤
     └────────────── core ──────────────────────┘
```

两条铁律：`-client-api` / `-server-api` **不得被对端引用**；`client` 与 `server` **禁止横向互依**（架构守卫机检）。
两端要协作只能经 `common-api` / `core` / 各自的 `-internal` 抽象，或在平台装配处（组合根）同时持有两侧实现。

### 3.2 先写 core：纯算法，零依赖

[`JoinCounter`](../examples/counter/core/src/main/java/top/wcpe/mc/mpmt/examples/counter/JoinCounter.java)
只管两件事：累计次数、记录首次加入时间。它不碰事件、不碰调度，也不知道自己跑在客户端还是服务端：

```java
public final class JoinCounter {
    private final PersistencePort persistence;

    public JoinCounter(PersistencePort persistence) { … }

    public int current(String uuid) { … }

    public int increment(String uuid) { … }

    public boolean recordFirstJoin(String uuid, long nowMillis) { … }

    public long firstJoinMillis(String uuid) { … }

    public void resetCount(String uuid) { … }
}
```

键名不散落在算法里，而是收在 `common-api` 的 `CounterKeys`（`namespace = "counter"`、
`join-count:<uuid>`、`first-join:<uuid>`）——因为客户端也要用同一套键去读。

### 3.3 再写 server：把算法接上事件与调度

[`CounterServerService`](../examples/counter/server/src/main/java/top/wcpe/mc/mpmt/examples/counter/CounterServerService.java)
是**实现模块**，它把契约、算法、端口三者拼起来：

```java
public final class CounterServerService
        implements CounterQueryPort, CounterAdminPort, CounterSnapshotReader {

    public CounterServerService(PersistencePort persistence, SchedulerPort scheduler, LongSupplier nowMillis) {
        this.counter = new JoinCounter(persistence);
        this.scheduler = scheduler;
        this.nowMillis = nowMillis;
    }

    /** 订阅共用加入事件（经 EventBus 协作，ADR-0011）。 */
    public void register(EventBusPort eventBus) {
        eventBus.subscribe(CounterJoinEvent.class, event -> startReminder(event.getPlayer()));
    }

    private void startReminder(PlayerRef player) {
        String uuid = player.getUuid().toString();
        counter.recordFirstJoin(uuid, nowMillis.getAsLong());
        AutoCloseable handle = scheduler.runTimer(
                REMINDER_DELAY_TICKS, REMINDER_PERIOD_TICKS,
                () -> scheduler.runAsync(() -> counter.current(uuid)));
        closeQuietly(reminders.put(player.getUuid(), handle));
    }

    /** 玩家离开时关闭周期句柄，防止调度任务泄漏。 */
    public void onPlayerLeft(PlayerRef player) { … }
}
```

> 两个线程约束：**阻塞的持久化一定经 `SchedulerPort.runAsync`**，**面向玩家的动作一定经
> `SchedulerPort.runForEntity`**。Folia 没有全局主线程，按实体归属调度才能不抛线程错误（ADR-0013）。

### 3.4 让域支持配置（可选）

域要读自己的配置，**只经 L0 端口 `ConfigPort`**——不接 snakeyaml、不知道文件在哪，也不需要依赖
`:core:config`（域外依赖仍然只有 `:core:kernel`，ADR-0030 决策 5）。「从哪个目录读、用什么格式」
是平台的事：各平台在能力装配时注册 `DirectoryConfigPort`（`core-config` 提供的实现，读
`<基目录>/config/<文件名>`），域只管要数据。counter 的提醒间隔就是这么做的，三步：

**① 配置模型放 `-internal`**（[`CounterConfig`](../examples/counter/server-internal/src/main/java/top/wcpe/mc/mpmt/examples/counter/CounterConfig.java)）：

```java
public final class CounterConfig {
    public static final long DEFAULT_REMINDER_DELAY_TICKS = 20L;
    public static final long DEFAULT_REMINDER_PERIOD_TICKS = 1200L;

    private long reminderDelayTicks = DEFAULT_REMINDER_DELAY_TICKS;
    private long reminderPeriodTicks = DEFAULT_REMINDER_PERIOD_TICKS;

    public CounterConfig() { }                  // 供 snakeyaml 实例化
    public long reminderDelayTicks() { … }
    public long reminderPeriodTicks() { … }
    public CounterConfig normalized() { … }     // 非正数回落缺省
}
```

字段**私有 + 访问器**：snakeyaml 以 `BeanAccess.FIELD` 按字段名反射写入，不需要 setter；
公开可变原始类型字段会被 SpotBugs 判为 `PA_PUBLIC_PRIMITIVE_ATTRIBUTE`。

**② 域内加载器收 `ConfigPort`**（[`CounterConfigs`](../examples/counter/server/src/main/java/top/wcpe/mc/mpmt/examples/counter/CounterConfigs.java)）：

```java
public final class CounterConfigs {
    public static final String FILE_NAME = "counter.yml";

    private final ConfigPort configPort;   // top.wcpe.mc.mpmt.kernel.port.ConfigPort

    public CounterConfig loadOrDefault() {
        // 文件不存在 → empty → 缺省值；存在但内容非法 → ConfigLoadException 直接抛出，不静默回落
        return configPort.read(FILE_NAME, CounterConfig.class)
                .map(CounterConfig::normalized)
                .orElseGet(CounterConfig::new);
    }
}
```

**③ 服务消费配置值**：`CounterServerService` 的 4 参构造收 `CounterConfig`，
`startReminder` 用 `config.reminderDelayTicks()` / `config.reminderPeriodTicks()` 而非硬编码常量。

配置文件落在平台配置目录（`<基目录>/config/counter.yml`），扩展名决定格式（`.yml` / `.yaml` / `.json`）：

```yaml
reminderDelayTicks: 20
reminderPeriodTicks: 1200
```

> **域外依赖仍然只有 `:core:kernel`**：`ConfigPort` 就定义在那里。`:core:config` 是 L1 实现，
> 只出现在平台车道的依赖里（各平台 bootstrap 注册 `new DirectoryConfigPort(dataDirectory)`），
> 域既不需要也不允许依赖它——那会把 snakeyaml 与文件 I/O 拖进 L0（ADR-0030 决策 5）。
> snakeyaml 已由各车道的 shade 链 relocate 到 `libs.*`，不会与其它 mod 冲突（ADR-0012）。

### 3.5 最后接上平台

域自己不负责「怎么装到服上」。各平台车道在 capability bootstrap 里注册
`PersistencePort` / `ConfigPort` / `MessagePort` / `SchedulerPort`，然后调 domain 提供的 `*Installer`：

| 平台 | L3 装配范本 |
|---|---|
| Bukkit/Paper/Folia | [`BukkitCapabilityBootstrap`](../platform/bukkit/common/src/main/java/top/wcpe/mc/mpmt/platform/bukkit/capability/BukkitCapabilityBootstrap.java) |
| Fabric 26.2 | [`FabricCapabilityBootstrap`](../platform/fabric/26.2/common/src/main/java/top/wcpe/mc/mpmt/platform/fabric/capability/FabricCapabilityBootstrap.java) |
| Forge 26.2 | [`ForgeCapabilityBootstrap`](../platform/forge/26.2/common/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/capability/ForgeCapabilityBootstrap.java) |

counter 本身**没有已落地的跨平台 L3 装配**——它故意停在域内，不伪装成已部署到所有 loader。
要把自己的域接上去，按上面的范本把 `PlatformCapabilityExample` 换成你的 `*Installer`，
保留同样的 EventBus 订阅与进退事件桥接即可。

## 4. 编译与测试

域是多个模块，所以命令按模块给（没有 `:examples:counter:test` 这种聚合任务）：

```bash
# 只跑算法的纯 JVM 单测（最快，无需任何平台依赖）
./gradlew :examples:counter:core:test

# 跑实现模块的单测
./gradlew :examples:counter:server:test :examples:counter:client:test

# 全部模块一起
./gradlew :examples:counter:common-api:test :examples:counter:core:test \
           :examples:counter:server:test :examples:counter:client:test

# 聚合全部平台产物 → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew :collectReleaseArtifacts
```

自己新建域时，记得在 `settings.gradle.kts` 的域清单里登记模块——没登记就不会参与构建，
而架构守卫的顶层语义校验会把它当成漏网项报错。


## 5. 真服验证

1. 用 `:collectReleaseArtifacts` 拿到目标平台 jar。
2. 部署到真实 Paper / Fabric / Forge 服（产品 jar；验收场景另加验收 jar）。
3. 进服观察「你已加入 N 次」是否按预期递增。
4. 个人开发验证可用对应 lane 的 `:runRealServerAcceptance*`；P2 矩阵门是 `./gradlew :runVersionMatrixGate`（与上手示例无关）。

## 6. 下一步

| 想了解… | 去看 |
|---|---|
| 完整分层与依赖方向 | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| 更完整的示例（调度 / 心跳 / 首次加入欢迎） | `PlatformCapabilityExample` |
| 端口 / SPI / 协议契约 | [`API.md`](API.md) |
| 第三期规划（26.2 / 模板发布 / 上手） | [`specs/p3-platform-scaling-and-onboarding.md`](specs/p3-platform-scaling-and-onboarding.md) |
| 版本节奏与发版流程 | [`VERSIONING.md`](VERSIONING.md) |
| 脚手架换名参数 | [`../tools/README.md`](../tools/README.md) |
