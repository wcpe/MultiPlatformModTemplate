package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.port.PersistencePort;

/** 共用实现测试：自增、首次加入记录与脏数据容错（示范域 counter）。 */
class JoinCounterTest {

    @Test
    void 自增从零开始并逐次累加() {
        JoinCounter counter = new JoinCounter(new InMemoryPersistence());
        assertEquals(0, counter.current("uuid-1"));
        assertEquals(1, counter.increment("uuid-1"));
        assertEquals(2, counter.increment("uuid-1"));
        assertEquals(2, counter.current("uuid-1"));
    }

    @Test
    void 首次加入只记录一次() {
        JoinCounter counter = new JoinCounter(new InMemoryPersistence());
        assertTrue(counter.recordFirstJoin("uuid-2", 1000L));
        assertFalse(counter.recordFirstJoin("uuid-2", 2000L));
    }

    @Test
    void 清空累计次数但保留首次加入时间() {
        InMemoryPersistence store = new InMemoryPersistence();
        JoinCounter counter = new JoinCounter(store);
        counter.increment("uuid-4");
        counter.increment("uuid-4");
        counter.recordFirstJoin("uuid-4", 5000L);

        counter.resetCount("uuid-4");

        assertEquals(0, counter.current("uuid-4"), "清零后累计次数应为 0");
        assertEquals(5000L, counter.firstJoinMillis("uuid-4"), "清零不应动首次加入时间");
    }

    @Test
    void 脏数据按零处理不阻断主流程() {
        InMemoryPersistence store = new InMemoryPersistence();
        store.write(CounterKeys.NAMESPACE, CounterKeys.joinCountKey("uuid-3"), "not-a-number");
        JoinCounter counter = new JoinCounter(store);
        assertEquals(0, counter.current("uuid-3"));
        assertEquals(1, counter.increment("uuid-3"));
    }

    /** 内存持久化桩：仅本测试使用。 */
    private static final class InMemoryPersistence implements PersistencePort {
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
}
