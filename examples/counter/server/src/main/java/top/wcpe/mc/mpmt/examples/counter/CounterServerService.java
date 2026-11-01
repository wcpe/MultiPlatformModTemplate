package top.wcpe.mc.mpmt.examples.counter;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import top.wcpe.mc.mpmt.kernel.event.EventBusPort;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 服务端计数服务（示范域 counter）。
 *
 * <p>放在 {@code server}：域内共用算法在 {@code core} 的 {@link JoinCounter}，
 * 本类只负责服务端编排——订阅共用事件、异步落盘、持有周期句柄（ADR-0030）。
 */
public final class CounterServerService implements CounterQueryPort, CounterAdminPort, CounterSnapshotReader {

    private final JoinCounter counter;
    private final SchedulerPort scheduler;
    private final LongSupplier nowMillis;
    private final CounterConfig config;
    private final Map<UUID, AutoCloseable> reminders = new ConcurrentHashMap<>();

    /**
     * 以缺省配置构造。
     *
     * <p>周期提示的时序用 {@link CounterConfig} 的缺省值；需要可配置时用
     * {@link #CounterServerService(PersistencePort, SchedulerPort, LongSupplier, CounterConfig)}。
     *
     * @param persistence 持久化端口，不可为空
     * @param scheduler   调度端口，不可为空
     * @param nowMillis   时钟，不可为空
     */
    public CounterServerService(PersistencePort persistence, SchedulerPort scheduler, LongSupplier nowMillis) {
        this(persistence, scheduler, nowMillis, new CounterConfig());
    }

    /**
     * 以显式配置构造。
     *
     * @param persistence 持久化端口，不可为空
     * @param scheduler   调度端口，不可为空
     * @param nowMillis   时钟，不可为空
     * @param config      服务端配置，不可为空；传入前已由加载方归一
     */
    public CounterServerService(
            PersistencePort persistence, SchedulerPort scheduler, LongSupplier nowMillis, CounterConfig config) {
        this.counter = new JoinCounter(Objects.requireNonNull(persistence, "持久化端口不能为空"));
        this.scheduler = Objects.requireNonNull(scheduler, "调度端口不能为空");
        this.nowMillis = Objects.requireNonNull(nowMillis, "时钟不能为空");
        this.config = Objects.requireNonNull(config, "配置不能为空");
    }

    /**
     * 订阅共用加入事件（经自有 EventBus 协作，ADR-0011）。
     *
     * @param eventBus 事件总线，不可为空
     */
    public void register(EventBusPort eventBus) {
        Objects.requireNonNull(eventBus, "事件总线不能为空");
        eventBus.subscribe(CounterJoinEvent.class, event -> startReminder(event.getPlayer()));
    }

    @Override
    public int joinCount(PlayerRef player) {
        Objects.requireNonNull(player, "玩家不能为空");
        return counter.current(player.getUuid().toString());
    }

    @Override
    public CounterSnapshot read(PlayerRef player) {
        Objects.requireNonNull(player, "玩家不能为空");
        String uuid = player.getUuid().toString();
        // 内部抽象 CounterSnapshotReader 的实现：把"次数 + 首次加入时间"打包返回，
        // 调用方无需知道存储形态，也不必承担两次读取的一致性责任（ADR-0030 决策 5）。
        return new CounterSnapshot(player.getUuid(), counter.current(uuid), counter.firstJoinMillis(uuid));
    }

    @Override
    public void reset(PlayerRef player) {
        Objects.requireNonNull(player, "玩家不能为空");
        // 契约（CounterAdminPort#reset）只要求重置累计次数，首次加入时间保持不变。
        // 不能改调 recordFirstJoin：它在键已存在时直接返回，对已存在玩家是 no-op。
        counter.resetCount(player.getUuid().toString());
    }

    /**
     * 玩家离开时关闭所持有的周期句柄，防止调度任务泄漏。
     *
     * @param player 目标玩家
     */
    public void onPlayerLeft(PlayerRef player) {
        Objects.requireNonNull(player, "玩家不能为空");
        closeQuietly(reminders.remove(player.getUuid()));
    }

    /** 记录首次加入并启动可释放的周期提示。 */
    private void startReminder(PlayerRef player) {
        String uuid = player.getUuid().toString();
        counter.recordFirstJoin(uuid, nowMillis.getAsLong());
        AutoCloseable handle =
                scheduler.runTimer(
                        config.reminderDelayTicks(),
                        config.reminderPeriodTicks(),
                        () -> scheduler.runAsync(() -> counter.current(uuid)));
        closeQuietly(reminders.put(player.getUuid(), handle));
    }

    private static void closeQuietly(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception ex) {
            // 关闭失败不应影响主流程；交由调度器自行回收
            java.util.logging.Logger.getLogger(CounterServerService.class.getName())
                    .log(java.util.logging.Level.WARNING, "关闭 Counter 周期句柄失败", ex);
        }
    }
}
