package top.wcpe.mc.mpmt.domain.capability;

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

/**
 * 平台能力示例（功能域 capability，FR-26）：演示"一份 L0 逻辑经端口在各平台一致运行"。
 *
 * <p>玩家加入时：异步（{@link SchedulerPort#runAsync}）经 {@link PersistencePort} 读 / 写首次加入时间 →
 * 按归属（{@link SchedulerPort#runForEntity}）经 {@link MessagePort} 发欢迎 → 注册周期心跳
 * （{@link SchedulerPort#runTimer}）。玩家离开时关闭其心跳句柄释放资源。
 *
 * <p>纯领域逻辑、零平台依赖；功能域经 EventBus 协作、不直接依赖兄弟域（ADR-0011）。
 * 触碰玩家状态的操作一律经带归属调度切线程（ADR-0013）。
 */
public final class PlatformCapabilityExample {

    private static final Logger LOGGER = Logger.getLogger(PlatformCapabilityExample.class.getName());

    /** 本示例持久化命名空间（包级可见，供同包测试引用避免键复制）。 */
    static final String NAMESPACE = "capability-example";
    /** 首次加入时间记录的键前缀（拼玩家 UUID；包级可见同上）。 */
    static final String FIRST_JOIN_KEY_PREFIX = "first-join:";
    /** 心跳首次延迟（tick，约 1 秒；包级可见同上）。 */
    static final long HEARTBEAT_DELAY_TICKS = 20L;
    /** 心跳周期（tick，约 5 秒；包级可见同上）。 */
    static final long HEARTBEAT_PERIOD_TICKS = 100L;
    /** 首次加入欢迎语。 */
    private static final String WELCOME_FIRST = "欢迎首次加入服务器！";
    /** 再次加入欢迎语。 */
    private static final String WELCOME_BACK = "欢迎回来！";
    /** 心跳提示语。 */
    private static final String HEARTBEAT_TEXT = "在线心跳";

    private final PersistencePort persistence;
    private final MessagePort message;
    private final SchedulerPort scheduler;
    private final LongSupplier nowMillis;

    /** 在线玩家的心跳任务句柄（线程安全：加入 / 离开可能在不同线程）。 */
    private final Map<UUID, AutoCloseable> heartbeats = new ConcurrentHashMap<>();

    public PlatformCapabilityExample(
            PersistencePort persistence, MessagePort message, SchedulerPort scheduler, LongSupplier nowMillis) {
        this.persistence = Objects.requireNonNull(persistence, "持久化端口不能为空");
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度端口不能为空");
        this.nowMillis = Objects.requireNonNull(nowMillis, "时钟不能为空");
    }

    /** 订阅玩家加入 / 离开事件（经自有 EventBus 协作，ADR-0011）。 */
    public void register(EventBusPort eventBus) {
        Objects.requireNonNull(eventBus, "事件总线不能为空");
        eventBus.subscribe(PlayerJoinedEvent.class, event -> onPlayerJoined(event.getPlayer()));
        eventBus.subscribe(PlayerLeftEvent.class, event -> onPlayerLeft(event.getPlayer()));
    }

    /** 玩家加入：异步持久化首次加入时间 → 按归属发欢迎 → 启动心跳。 */
    void onPlayerJoined(PlayerRef player) {
        String key = FIRST_JOIN_KEY_PREFIX + player.getUuid();
        // 持久化涉及阻塞 IO：放到异步线程，不占用主线程 / tick
        scheduler.runAsync(
                () -> {
                    boolean firstJoin = !persistence.read(NAMESPACE, key).isPresent();
                    if (firstJoin) {
                        persistence.write(NAMESPACE, key, Long.toString(nowMillis.getAsLong()));
                    }
                    // 发消息碰玩家态：按归属切到玩家所属线程后再发（ADR-0013）
                    scheduler.runForEntity(
                            new EntityRef(player.getUuid()),
                            () -> message.send(player, firstJoin ? WELCOME_FIRST : WELCOME_BACK));
                });

        // 注册周期心跳：每周期按归属向玩家发在线提示
        AutoCloseable handle =
                scheduler.runTimer(
                        HEARTBEAT_DELAY_TICKS,
                        HEARTBEAT_PERIOD_TICKS,
                        () ->
                                scheduler.runForEntity(
                                        new EntityRef(player.getUuid()),
                                        () -> message.send(player, HEARTBEAT_TEXT)));
        // 同玩家重复加入：先关旧句柄防任务泄露
        closeQuietly(heartbeats.put(player.getUuid(), handle));
    }

    /** 玩家离开：关闭其心跳任务句柄释放资源。 */
    void onPlayerLeft(PlayerRef player) {
        closeQuietly(heartbeats.remove(player.getUuid()));
    }

    /** 关闭句柄；失败仅记录、不影响主流程（关闭计时句柄失败属非关键路径）。 */
    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "关闭心跳任务句柄失败", e);
        }
    }
}
