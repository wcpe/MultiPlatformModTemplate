# 上手指南：克隆模板并编写自己的玩法

> 目标读者：想用本脚手架写自己玩法的开发者。  
> 对应规格：[`specs/p3-platform-scaling-and-onboarding.md`](specs/p3-platform-scaling-and-onboarding.md) §4.3 / FR-18。  
> 示例域：[`examples/counter`](../examples/counter)（纯 L0、零平台依赖、可纯 JVM 单测）。

## 1. 前置条件

| 项 | 说明 |
|---|---|
| JDK | 根构建建议 **JDK 17 或 21**；1.12.2 车道另需 JDK 8（普通上手只用 17+ 即可） |
| Gradle | 用仓库自带 `./gradlew` wrapper，无需单独安装 |
| Git | 克隆 / 分支管理 |
| mc-testkit | 上游 plugin marker 当前不可用；根构建只会从本机 Maven 缓存回退解析插件及实现模块。首次配置失败时，先按 [`OPERATIONS.md`](OPERATIONS.md) 准备本地制品；此临时前提不等于冷缓存或新机器验证已通过。 |

## 2. 克隆并重命名

```bash
git clone <此仓库 URL> mygame
cd mygame

# 预览（dry-run，不修改文件）
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame \
  -Pmpmt.scaffold.dryRun=true

# 确认无误后写盘
./gradlew renameScaffold \
  -Pmpmt.scaffold.id=mygame \
  -Pmpmt.scaffold.group=com.example.mygame \
  -Pmpmt.scaffold.name=MyGame
```

参数含义见 [`tools/README.md`](../tools/README.md)。  
**产品化时**若要改协议通道名（`mpmt:main` 等），加 `-Pmpmt.scaffold.rewriteChannels=true`；互通双方必须同一通道。

## 3. 在 L0 写一个最小玩法域——以 Counter 为参照

### 3.1 L0 是什么

- **L0（`core-domain`）**：纯 Java 8、零平台依赖的功能域。玩法规则、领域模型、端口接口都写在这里。
- **端口（Port）**：L0 声明、L3 实现的能力抽象。Counter 用三个：
  - `PersistencePort`：读写字符串键值（具体存储由平台决定）
  - `MessagePort`：向玩家发一条聊天文本
  - `SchedulerPort`：把阻塞的持久化放到异步线程，并按玩家归属发消息或运行周期任务。
- **EventBus**：域间协作总线。平台 L3 把原生玩家进、退事件分别适配为 `PlayerJoinedEvent` 与 `PlayerLeftEvent` 投递到总线，L0 订阅即可。

### 3.2 Counter 做了什么

[`PlayerJoinCounterService`](../examples/counter/src/main/java/top/wcpe/mc/mpmt/examples/counter/PlayerJoinCounterService.java)：

1. 玩家加入 → 异步读写 `counter/first-join:<uuid>`；仅首次加入时写入毫秒时间。
2. 读 `counter/join-count:<uuid>` → 计数 +1 → 写回。
3. 按玩家归属发消息「你已加入 N 次」，并登记一个周期提示句柄。
4. 玩家离开 → 关闭其句柄，不留后台任务。

这个示例只保留一个独立的玩家计数规则；完整平台能力组合见下文的 `PlatformCapabilityExample` 装配路径。

### 3.3 核心代码骨架

```java
public final class PlayerJoinCounterService {
    static final String NAMESPACE = "counter";
    static final String JOIN_COUNT_KEY_PREFIX = "join-count:";
    static final String FIRST_JOIN_KEY_PREFIX = "first-join:";

    private final PersistencePort persistence;
    private final MessagePort message;
    private final SchedulerPort scheduler;

    public PlayerJoinCounterService(
            PersistencePort persistence, MessagePort message, SchedulerPort scheduler, LongSupplier clock) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度端口不能为空");
    }

    public void register(EventBusPort eventBus) {
        eventBus.subscribe(PlayerJoinedEvent.class, e -> onPlayerJoined(e.getPlayer()));
        eventBus.subscribe(PlayerLeftEvent.class, e -> onPlayerLeft(e.getPlayer()));
    }

    void onPlayerJoined(PlayerRef player) {
        startReminder(player); // 留住句柄，以便离开时释放
        scheduler.runAsync(() -> persistJoinAndNotify(player));
    }

    void onPlayerLeft(PlayerRef player) {
        closeReminder(player);
    }
}
```

> 上面只展示流程骨架；完整可编译实现以 [`PlayerJoinCounterService`](../examples/counter/src/main/java/top/wcpe/mc/mpmt/examples/counter/PlayerJoinCounterService.java) 为准。持久化一定经 `SchedulerPort.runAsync`，发消息一定经 `SchedulerPort.runForEntity`，以兼容 Folia 的实体归属线程。

## 4. 编译与测试

```bash
# 编译 core + examples（快）
./gradlew --no-daemon :core:domain:compileJava :examples:counter:compileJava

# 跑 examples 单测（纯 JVM，无需起服）
./gradlew --no-daemon :examples:counter:test

# 聚合全部平台产物 → build/dist/{bukkit,fabric,forge,neoforge,sponge}/
./gradlew --no-daemon :collectReleaseArtifacts
```

## 5. 在平台入口接入域

Counter 本身**没有已落地的跨平台 L3 装配**：示例故意保持为纯 L0，不应把它伪装成已经部署到所有 loader。

要把它用在自己的产品域中，复用已完整接线的 `PlatformCapabilityExample` 路径：各平台 capability bootstrap 先注册 `PersistencePort`、`MessagePort`、`SchedulerPort`，然后在同一个 `MpmtRuntime` 中实例化服务并注册至 `runtime.eventBus()`；最后把原生进、退事件分别发布为 `PlayerJoinedEvent` / `PlayerLeftEvent`。

| 平台 | 已完整的 L3 装配范本 |
|---|---|
| Bukkit/Paper/Folia | [`BukkitCapabilityBootstrap`](../platform/bukkit/common/src/main/java/top/wcpe/mc/mpmt/platform/bukkit/capability/BukkitCapabilityBootstrap.java) |
| Fabric 26.2 | [`FabricCapabilityBootstrap`](../platform/fabric/26.2/common/src/main/java/top/wcpe/mc/mpmt/platform/fabric/capability/FabricCapabilityBootstrap.java) |
| Forge 26.2 | [`ForgeCapabilityBootstrap`](../platform/forge/26.2/common/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/capability/ForgeCapabilityBootstrap.java) |

把范本中的 `PlatformCapabilityExample` 替换为 `PlayerJoinCounterService(persistence, message, scheduler, System::currentTimeMillis)`，并保留同样的 EventBus 和进退事件桥接，即可完成一次真实的平台装配。下方仅为上述装配的核心替换段，不是已经写入平台模块的代码：

```java
// 伪代码：在平台 onEnable / SERVER_STARTED 阶段
PersistencePort persistence = /* 平台提供的文件/DB 实现 */;
MessagePort message = /* 平台提供的聊天实现 */;
SchedulerPort scheduler = /* 平台提供的归属/异步调度实现 */;
EventBusPort eventBus = /* 运行时装配的自有 EventBus */;

PlayerJoinCounterService counter =
    new PlayerJoinCounterService(persistence, message, scheduler, System::currentTimeMillis);
counter.register(eventBus);

// 平台把原生进、退事件适配后投递：
// eventBus.publish(new PlayerJoinedEvent(playerRef));
// eventBus.publish(new PlayerLeftEvent(playerRef));
```

## 6. 真服验证

1. 用 `:collectReleaseArtifacts` 拿到目标平台 jar。
2. 部署到真实 Paper / Fabric / Forge 服（产品 jar；验收场景另加验收 jar）。
3. 进服观察「你已加入 N 次」是否按预期递增。
4. 个人开发验证可用对应 lane 的 `:runRealServerAcceptance*`；P2 矩阵门是 `./gradlew :runVersionMatrixGate`（与上手示例无关）。

## 7. 下一步

| 想了解… | 去看 |
|---|---|
| 完整分层与依赖方向 | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| 更完整的示例（调度 / 心跳 / 首次加入欢迎） | `PlatformCapabilityExample` |
| 端口 / SPI / 协议契约 | [`API.md`](API.md) |
| 第三期规划（26.2 / 模板发布 / 上手） | [`specs/p3-platform-scaling-and-onboarding.md`](specs/p3-platform-scaling-and-onboarding.md) |
| 版本节奏与发版流程 | [`VERSIONING.md`](VERSIONING.md) |
| 脚手架换名参数 | [`../tools/README.md`](../tools/README.md) |
