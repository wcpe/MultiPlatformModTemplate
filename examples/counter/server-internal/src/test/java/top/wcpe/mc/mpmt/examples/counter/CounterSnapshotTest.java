package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 内部 DTO 测试：服务端计数快照（示范域 counter）。 */
class CounterSnapshotTest {

    @Test
    void 快照保留标识与两项数据() {
        UUID id = UUID.randomUUID();
        CounterSnapshot snapshot = new CounterSnapshot(id, 5, 1234L);

        assertEquals(id, snapshot.playerId());
        assertEquals(5, snapshot.joinCount());
        assertEquals(1234L, snapshot.firstJoinMillis());
    }

    @Test
    void 无首次加入时间判为首次加入() {
        UUID id = UUID.randomUUID();

        assertTrue(new CounterSnapshot(id, 0, 0L).firstJoin(), "无首次加入时间应判为首次");
        assertFalse(new CounterSnapshot(id, 1, 1000L).firstJoin(), "有首次加入时间不应判为首次");
    }

    @Test
    void 快照读取器接口可由实现方提供() {
        UUID id = UUID.randomUUID();
        CounterSnapshotReader reader = player -> new CounterSnapshot(player.getUuid(), 2, 500L);

        CounterSnapshot snapshot = reader.read(new top.wcpe.mc.mpmt.kernel.ref.PlayerRef(id, "Zoe"));

        assertEquals(id, snapshot.playerId());
        assertEquals(2, snapshot.joinCount());
        assertEquals(500L, snapshot.firstJoinMillis());
    }

    @Test
    void 空标识被拒绝() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class,
                () -> new CounterSnapshot(null, 0, 0L));
    }
}
