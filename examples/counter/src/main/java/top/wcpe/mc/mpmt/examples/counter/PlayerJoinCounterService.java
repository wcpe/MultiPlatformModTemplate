package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;

/**
 * 玩家加入计数服务（FR-18 上手示例域）：演示最小 L0 玩法写法。
 *
 * <p>比 {@code PlatformCapabilityExample} 更简单：只用 {@link PersistencePort} + {@link MessagePort}，
 * 无调度、无心跳。玩家每次加入时读持久化计数 → +1 → 写回 → 发"你已加入 N 次"。
 *
 * <p>纯领域逻辑、零平台依赖；可在纯 JVM 下单测穷举。
 */
public final class PlayerJoinCounterService {

    /** 本示例持久化命名空间（包级可见，供同包测试引用）。 */
    static final String NAMESPACE = "counter";

    /** 加入次数字段键前缀（拼玩家 UUID；包级可见同上）。 */
    static final String JOIN_COUNT_KEY_PREFIX = "join-count:";

    private final PersistencePort persistence;
    private final MessagePort message;

    public PlayerJoinCounterService(PersistencePort persistence, MessagePort message) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
    }

    /** 订阅玩家加入事件（经自有 EventBus 协作，ADR-0011）。 */
    public void register(EventBusPort eventBus) {
        Objects.requireNonNull(eventBus, "事件总线不能为空");
        eventBus.subscribe(PlayerJoinedEvent.class, event -> onPlayerJoined(event.getPlayer()));
    }

    /**
     * 玩家加入：读计数 → +1 → 写回 → 发消息。
     *
     * <p>真实平台上，阻塞 IO 应经 {@code SchedulerPort.runAsync} 切异步；本示例为教学简洁，
     * 直接同步读写，让读者一眼看懂数据流。
     */
    void onPlayerJoined(PlayerRef player) {
        String key = JOIN_COUNT_KEY_PREFIX + player.getUuid();
        int previous =
                persistence
                        .read(NAMESPACE, key)
                        .map(PlayerJoinCounterService::parseCount)
                        .orElse(0);
        int next = previous + 1;
        persistence.write(NAMESPACE, key, Integer.toString(next));
        message.send(player, "你已加入 " + next + " 次");
    }

    /** 解析计数；非法值按 0 处理，避免脏数据阻断主流程。 */
    private static int parseCount(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
