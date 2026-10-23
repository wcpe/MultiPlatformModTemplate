package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/** 共用契约测试：键格式与事件载荷（示范域 counter）。 */
class CounterContractTest {

    @Test
    void 计数键按前缀拼接玩家UUID() {
        String uuid = UUID.randomUUID().toString();
        assertEquals("join-count:" + uuid, CounterKeys.joinCountKey(uuid));
        assertEquals("first-join:" + uuid, CounterKeys.firstJoinKey(uuid));
    }

    @Test
    void 事件携带玩家与累计次数() {
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "Alice");
        CounterJoinEvent event = new CounterJoinEvent(player, 3);
        assertEquals(player, event.getPlayer());
        assertEquals(3, event.getCount());
    }
}
