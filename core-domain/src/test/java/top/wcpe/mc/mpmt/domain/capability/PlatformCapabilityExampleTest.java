package top.wcpe.mc.mpmt.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;

/** 平台能力示例（FR-26）L0 逻辑穷举：事件 / 持久化 / 消息 + 按归属·异步·周期调度 + 资源释放。 */
class PlatformCapabilityExampleTest {

    private static final long FIXED_NOW = 123456789L;
    private static final String WELCOME_FIRST = "欢迎首次加入服务器！";
    private static final String WELCOME_BACK = "欢迎回来！";
    private static final String HEARTBEAT_TEXT = "在线心跳";

    private static final LongSupplier CLOCK = () -> FIXED_NOW;

    private static PlayerRef player() {
        return new PlayerRef(UUID.randomUUID(), "Steve");
    }

    private PlatformCapabilityExample example(PersistencePort p, MessagePort m, SchedulerPort s) {
        return new PlatformCapabilityExample(p, m, s, CLOCK);
    }

    @Test
    @DisplayName("首次加入：异步写入加入时间 + 按归属发欢迎")
    void 首次加入() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();

        example(persistence, message, scheduler).onPlayerJoined(p);

        // 持久化 IO 经异步线程（不在主线程做阻塞 IO，testing-and-quality §2 高风险区）
        assertEquals(1, scheduler.asyncCount);
        // 持久化写入了首次加入时间
        assertEquals(1, persistence.store.size());
        assertTrue(persistence.store.values().contains(Long.toString(FIXED_NOW)));
        // 发了首次欢迎，且经 runForEntity 按玩家归属调度
        assertEquals(1, message.sent.size());
        assertEquals(WELCOME_FIRST, message.sent.get(0));
        assertTrue(scheduler.entityCalls.contains(new EntityRef(p.getUuid())));
    }

    @Test
    @DisplayName("再次加入：不重复写入，发欢迎回来")
    void 再次加入() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();
        // 预置已有加入记录（引用生产常量，避免键字面量复制）
        persistence.write(
                PlatformCapabilityExample.NAMESPACE,
                PlatformCapabilityExample.FIRST_JOIN_KEY_PREFIX + p.getUuid(),
                "1");

        example(persistence, message, scheduler).onPlayerJoined(p);

        assertEquals(1, persistence.store.size()); // 未新增
        assertEquals("1", persistence.store.values().iterator().next()); // 未覆盖
        assertEquals(WELCOME_BACK, message.sent.get(0));
    }

    @Test
    @DisplayName("心跳：注册周期任务，触发时按归属周期发送")
    void 心跳周期发送() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlayerRef p = player();

        example(persistence, message, scheduler).onPlayerJoined(p);

        // 注册了周期任务，延迟 / 周期等于约定常量
        assertTrue(scheduler.timerTask != null);
        assertEquals(PlatformCapabilityExample.HEARTBEAT_DELAY_TICKS, scheduler.timerDelay);
        assertEquals(PlatformCapabilityExample.HEARTBEAT_PERIOD_TICKS, scheduler.timerPeriod);
        int before = message.sent.size();
        // 手动触发两次心跳
        scheduler.timerTask.run();
        scheduler.timerTask.run();
        assertEquals(before + 2, message.sent.size());
        assertEquals(HEARTBEAT_TEXT, message.sent.get(message.sent.size() - 1));
    }

    @Test
    @DisplayName("离开：关闭心跳句柄释放资源，重复离开无副作用")
    void 离开释放资源() {
        FakeScheduler scheduler = new FakeScheduler();
        PlatformCapabilityExample ex = example(new FakePersistence(), new FakeMessage(), scheduler);
        PlayerRef p = player();

        ex.onPlayerJoined(p);
        assertEquals(0, scheduler.closeCount);
        ex.onPlayerLeft(p);
        assertEquals(1, scheduler.closeCount); // 句柄被关闭
        ex.onPlayerLeft(p); // 已离开再离开
        assertEquals(1, scheduler.closeCount); // 无副作用
    }

    @Test
    @DisplayName("同玩家重复加入：先关旧句柄防泄露")
    void 重复加入防泄露() {
        FakeScheduler scheduler = new FakeScheduler();
        PlatformCapabilityExample ex = example(new FakePersistence(), new FakeMessage(), scheduler);
        PlayerRef p = player();

        ex.onPlayerJoined(p);
        ex.onPlayerJoined(p); // 第二次加入应先关闭第一次的句柄
        assertEquals(1, scheduler.closeCount);
    }

    @Test
    @DisplayName("经 EventBus 订阅：发布加入 / 离开事件被响应")
    void 经事件总线订阅() {
        FakeMessage message = new FakeMessage();
        FakeScheduler scheduler = new FakeScheduler();
        PlatformCapabilityExample ex = example(new FakePersistence(), message, scheduler);
        SimpleEventBus bus = new SimpleEventBus();
        ex.register(bus);
        PlayerRef p = player();

        bus.publish(new PlayerJoinedEvent(p));
        assertEquals(WELCOME_FIRST, message.sent.get(0));

        bus.publish(new PlayerLeftEvent(p));
        assertEquals(1, scheduler.closeCount); // 离开关闭心跳
    }

    @Test
    @DisplayName("构造 / 订阅入参为空即拒")
    void 入参校验() {
        FakePersistence p = new FakePersistence();
        FakeMessage m = new FakeMessage();
        FakeScheduler s = new FakeScheduler();
        assertThrows(NullPointerException.class, () -> new PlatformCapabilityExample(null, m, s, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlatformCapabilityExample(p, null, s, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlatformCapabilityExample(p, m, null, CLOCK));
        assertThrows(NullPointerException.class, () -> new PlatformCapabilityExample(p, m, s, null));
        assertThrows(NullPointerException.class, () -> example(p, m, s).register(null));
    }

    // —— 测试替身（手写假端口，纯内存、同步执行）——

    /** 假持久化：内存 map，键为 namespace/key。 */
    private static final class FakePersistence implements PersistencePort {
        final Map<String, String> store = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(store.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            store.put(namespace + "/" + key, value);
        }
    }

    /** 假消息端口：记录发出的文本。 */
    private static final class FakeMessage implements MessagePort {
        final List<String> sent = new ArrayList<>();

        @Override
        public void send(PlayerRef player, String text) {
            sent.add(text);
        }
    }

    /** 假调度：归属 / 异步 / 全局任务立即同步执行；周期任务记录待手动触发；句柄关闭计数。 */
    private static final class FakeScheduler implements SchedulerPort {
        final List<EntityRef> entityCalls = new ArrayList<>();
        Runnable timerTask;
        long timerDelay;
        long timerPeriod;
        int closeCount;
        int asyncCount;

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            entityCalls.add(entity);
            task.run();
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            asyncCount++;
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            this.timerDelay = delayTicks;
            this.timerPeriod = periodTicks;
            this.timerTask = task;
            return () -> closeCount++;
        }
    }
}
