package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;

/** 玩家加入计数服务（FR-18）L0 逻辑穷举：计数递增 / 多玩家隔离 / EventBus 订阅 / 入参校验。 */
class PlayerJoinCounterServiceTest {

    private static PlayerRef player() {
        return new PlayerRef(UUID.randomUUID(), "Steve");
    }

    private static PlayerJoinCounterService service(PersistencePort p, MessagePort m) {
        return new PlayerJoinCounterService(p, m);
    }

    @Test
    @DisplayName("首次加入：计数从 0→1，发「你已加入 1 次」")
    void 首次加入() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        PlayerRef p = player();

        service(persistence, message).onPlayerJoined(p);

        assertEquals(1, persistence.store.size());
        assertEquals(
                "1",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + p.getUuid()));
        assertEquals(1, message.sent.size());
        assertEquals("你已加入 1 次", message.sent.get(0));
    }

    @Test
    @DisplayName("同一玩家多次加入：计数递增")
    void 多次加入递增() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        PlayerJoinCounterService svc = service(persistence, message);
        PlayerRef p = player();

        svc.onPlayerJoined(p);
        svc.onPlayerJoined(p);
        svc.onPlayerJoined(p);

        assertEquals("3", persistence.store.values().iterator().next());
        assertEquals(3, message.sent.size());
        assertEquals("你已加入 3 次", message.sent.get(2));
    }

    @Test
    @DisplayName("不同玩家计数互不干扰")
    void 多玩家隔离() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        PlayerJoinCounterService svc = service(persistence, message);
        PlayerRef a = player();
        PlayerRef b = player();

        svc.onPlayerJoined(a);
        svc.onPlayerJoined(b);
        svc.onPlayerJoined(a);

        assertEquals(
                "2",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + a.getUuid()));
        assertEquals(
                "1",
                persistence.store.get(
                        PlayerJoinCounterService.NAMESPACE
                                + "/"
                                + PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX
                                + b.getUuid()));
        assertEquals(3, message.sent.size());
    }

    @Test
    @DisplayName("经 EventBus 订阅：发布加入事件被响应")
    void 经事件总线订阅() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        PlayerJoinCounterService svc = service(persistence, message);
        SimpleEventBus bus = new SimpleEventBus();
        svc.register(bus);
        PlayerRef p = player();

        bus.publish(new PlayerJoinedEvent(p));

        assertEquals("你已加入 1 次", message.sent.get(0));
        assertTrue(persistence.store.size() == 1);
    }

    @Test
    @DisplayName("脏数据计数按 0 处理，不阻断主流程")
    void 脏数据容错() {
        FakePersistence persistence = new FakePersistence();
        FakeMessage message = new FakeMessage();
        PlayerRef p = player();
        persistence.write(
                PlayerJoinCounterService.NAMESPACE,
                PlayerJoinCounterService.JOIN_COUNT_KEY_PREFIX + p.getUuid(),
                "not-a-number");

        service(persistence, message).onPlayerJoined(p);

        assertEquals("1", persistence.store.values().iterator().next());
        assertEquals("你已加入 1 次", message.sent.get(0));
    }

    @Test
    @DisplayName("构造 / 订阅入参为空即拒")
    void 入参校验() {
        FakePersistence p = new FakePersistence();
        FakeMessage m = new FakeMessage();
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(null, m));
        assertThrows(NullPointerException.class, () -> new PlayerJoinCounterService(p, null));
        assertThrows(NullPointerException.class, () -> service(p, m).register(null));
    }

    // —— 测试替身（手写假端口，纯内存）——

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
}
