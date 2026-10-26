package top.wcpe.mc.mpmt.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.EntityRef;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;
import top.wcpe.mc.mpmt.kernel.ref.WorldRef;

/** 内部抽象实现测试：快照读取与首次加入查询（capability 域）。 */
class CapabilitySnapshotReaderTest {

    @Test
    void 快照按存储键返回首次加入时间() {
        InMemory store = new InMemory();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Ann");
        store.write(CapabilityKeys.NAMESPACE, CapabilityKeys.firstJoinKey(player.getUuid().toString()), "4242");

        CapabilitySnapshot snapshot = example(store).read(player);

        assertEquals(player.getUuid(), snapshot.playerId());
        assertEquals(4242L, snapshot.firstJoinMillis());
        assertTrue(!snapshot.firstJoin(), "已有记录不应判为首次");
    }

    @Test
    void 无记录时快照判为首次加入() {
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Bob");

        CapabilitySnapshot snapshot = example(new InMemory()).read(player);

        assertEquals(0L, snapshot.firstJoinMillis());
        assertTrue(snapshot.firstJoin(), "无记录应判为首次");
    }

    @Test
    void 对外契约查询首次加入时间() {
        InMemory store = new InMemory();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Cid");
        store.write(CapabilityKeys.NAMESPACE, CapabilityKeys.firstJoinKey(player.getUuid().toString()), "99");

        assertEquals(99L, example(store).firstJoinMillis(player));
    }

    @Test
    void 脏数据按零处理() {
        InMemory store = new InMemory();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Dan");
        store.write(CapabilityKeys.NAMESPACE, CapabilityKeys.firstJoinKey(player.getUuid().toString()), "oops");

        assertEquals(0L, example(store).firstJoinMillis(player));
    }

    private static PlatformCapabilityExample example(PersistencePort store) {
        return new PlatformCapabilityExample(store, (p, t) -> { }, new NoopScheduler(), () -> 1L);
    }

    /** 内存持久化桩。 */
    private static final class InMemory implements PersistencePort {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(values.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            values.put(namespace + "/" + key, value);
        }
    }

    /** 调度桩：立即执行。 */
    private static final class NoopScheduler implements SchedulerPort {
        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
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
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            return () -> { };
        }
    }
}
