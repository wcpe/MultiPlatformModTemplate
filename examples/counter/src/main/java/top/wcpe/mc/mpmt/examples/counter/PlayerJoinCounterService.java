package top.wcpe.mc.mpmt.examples.counter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.event.EventBusPort;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;

/**
 * 玩家加入计数服务（FR-18 上手示例域）：演示最小 L0 玩法写法。
 *
 * <p>玩家加入时异步写入首次加入时间与累计次数，再按玩家归属发送次数。
 * 它还会持有一个周期提示句柄，在玩家离开时关闭，防止调度任务泄漏。
 *
 * <p>纯领域逻辑、零平台依赖；可在纯 JVM 下单测穷举。
 */
public final class PlayerJoinCounterService {

    private static final Logger LOGGER = Logger.getLogger(PlayerJoinCounterService.class.getName());

    /** 本示例持久化命名空间（包级可见，供同包测试引用）。 */
    static final String NAMESPACE = "counter";

    /** 加入次数字段键前缀（拼玩家 UUID；包级可见同上）。 */
    static final String JOIN_COUNT_KEY_PREFIX = "join-count:";
    /** 首次加入时间字段键前缀（拼玩家 UUID）。 */
    static final String FIRST_JOIN_KEY_PREFIX = "first-join:";
    /** 周期提示首次延迟（tick）。 */
    static final long REMINDER_DELAY_TICKS = 20L;
    /** 周期提示间隔（tick）。 */
    static final long REMINDER_PERIOD_TICKS = 1200L;

    private final PersistencePort persistence;
    private final MessagePort message;
    private final SchedulerPort scheduler;
    private final LongSupplier nowMillis;
    private final Map<UUID, AutoCloseable> reminders = new ConcurrentHashMap<>();

    public PlayerJoinCounterService(
            PersistencePort persistence, MessagePort message, SchedulerPort scheduler, LongSupplier nowMillis) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度端口不能为空");
        this.nowMillis = Objects.requireNonNull(nowMillis, "时钟不能为空");
    }

    /** 订阅玩家进、退事件（经自有 EventBus 协作，ADR-0011）。 */
    public void register(EventBusPort eventBus) {
        Objects.requireNonNull(eventBus, "事件总线不能为空");
        eventBus.subscribe(PlayerJoinedEvent.class, event -> onPlayerJoined(event.getPlayer()));
        eventBus.subscribe(PlayerLeftEvent.class, event -> onPlayerLeft(event.getPlayer()));
    }

    /**
     * 玩家加入：异步持久化首次加入时间与计数，并启动可释放的周期提示。
     */
    void onPlayerJoined(PlayerRef player) {
        startReminder(player);
        scheduler.runAsync(() -> persistJoinAndNotify(player));
    }

    /** 玩家离开：关闭所持有的周期提示句柄。 */
    void onPlayerLeft(PlayerRef player) {
        closeQuietly(reminders.remove(player.getUuid()));
    }

    private void persistJoinAndNotify(PlayerRef player) {
        String firstJoinKey = FIRST_JOIN_KEY_PREFIX + player.getUuid();
        if (!persistence.read(NAMESPACE, firstJoinKey).isPresent()) {
            persistence.write(NAMESPACE, firstJoinKey, Long.toString(nowMillis.getAsLong()));
        }
        int next = incrementJoinCount(player);
        sendJoinCount(player, next);
    }

    private int incrementJoinCount(PlayerRef player) {
        String key = JOIN_COUNT_KEY_PREFIX + player.getUuid();
        int next =
                persistence.read(NAMESPACE, key).map(PlayerJoinCounterService::parseCount).orElse(0) + 1;
        persistence.write(NAMESPACE, key, Integer.toString(next));
        return next;
    }

    private void startReminder(PlayerRef player) {
        AutoCloseable handle =
                scheduler.runTimer(
                        REMINDER_DELAY_TICKS,
                        REMINDER_PERIOD_TICKS,
                        () -> scheduler.runAsync(() -> sendJoinCount(player, currentJoinCount(player))));
        closeQuietly(reminders.put(player.getUuid(), handle));
    }

    private int currentJoinCount(PlayerRef player) {
        return persistence
                .read(NAMESPACE, JOIN_COUNT_KEY_PREFIX + player.getUuid())
                .map(PlayerJoinCounterService::parseCount)
                .orElse(0);
    }

    private void sendJoinCount(PlayerRef player, int count) {
        scheduler.runForEntity(
                new EntityRef(player.getUuid()), () -> message.send(player, "你已加入 " + count + " 次"));
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, "关闭 Counter 周期提示句柄失败", ex);
        }
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
