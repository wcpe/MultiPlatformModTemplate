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
- **端口（Port）**：L0 声明、L3 实现的能力抽象。Counter 只用两个：
  - `PersistencePort`：读写字符串键值（具体存储由平台决定）
  - `MessagePort`：向玩家发一条聊天文本
- **EventBus**：域间协作总线。平台 L3 把原生"玩家加入"事件适配为 `PlayerJoinedEvent` 投递到总线，L0 订阅即可。

### 3.2 Counter 做了什么

[`PlayerJoinCounterService`](../examples/counter/src/main/java/top/wcpe/mc/mpmt/examples/counter/PlayerJoinCounterService.java)：

1. 玩家加入 → 读 `counter/join-count:<uuid>`
2. 计数 +1 → 写回
3. 发消息「你已加入 N 次」

比 [`PlatformCapabilityExample`](../core/domain/src/main/java/top/wcpe/mc/mpmt/domain/capability/PlatformCapabilityExample.java) 更简单：无调度、无心跳，一眼看懂数据流。

### 3.3 核心代码骨架

```java
public final class PlayerJoinCounterService {
    static final String NAMESPACE = "counter";
    static final String JOIN_COUNT_KEY_PREFIX = "join-count:";

    private final PersistencePort persistence;
    private final MessagePort message;

    public PlayerJoinCounterService(PersistencePort persistence, MessagePort message) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
    }

    public void register(EventBusPort eventBus) {
        eventBus.subscribe(PlayerJoinedEvent.class, e -> onPlayerJoined(e.getPlayer()));
    }

    void onPlayerJoined(PlayerRef player) {
        String key = JOIN_COUNT_KEY_PREFIX + player.getUuid();
        int next = persistence.read(NAMESPACE, key).map(Integer::parseInt).orElse(0) + 1;
        persistence.write(NAMESPACE, key, Integer.toString(next));
        message.send(player, "你已加入 " + next + " 次");
    }
}
```

> 真实平台上，阻塞 IO 应经 `SchedulerPort.runAsync` 切异步；本示例为教学简洁直接同步读写。

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

L0 写完后，在各平台 L3 入口实例化并注册。**本指南不改平台代码**，只给装配示意：

```java
// 伪代码：在平台 onEnable / SERVER_STARTED 阶段
PersistencePort persistence = /* 平台提供的文件/DB 实现 */;
MessagePort message = /* 平台提供的聊天实现 */;
EventBusPort eventBus = /* 运行时装配的自有 EventBus */;

PlayerJoinCounterService counter = new PlayerJoinCounterService(persistence, message);
counter.register(eventBus);

// 平台把原生 PlayerJoin 事件适配为 PlayerJoinedEvent 后投递：
// eventBus.publish(new PlayerJoinedEvent(playerRef));
```

入口位置参考：

| 平台 | 入口类 |
|---|---|
| Bukkit/Paper/Folia | `platform/bukkit/*/…/MpmtBukkitPlugin` |
| Fabric | `platform/fabric/*/…/MpmtFabricBootstrap` |
| Forge | `platform/forge/*/…/MpmtForgeMod` |

已有的 `PlatformCapabilityExample` 在各平台 `capability` 包里有完整 L3 装配范本，可对照。

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
