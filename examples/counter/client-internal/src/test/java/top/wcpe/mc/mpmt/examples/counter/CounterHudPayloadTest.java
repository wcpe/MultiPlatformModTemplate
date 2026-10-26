package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 内部 DTO 测试：载荷结构与渲染文案（示范域 counter）。 */
class CounterHudPayloadTest {

    @Test
    void 载荷保留玩家标识与次数() {
        UUID id = UUID.randomUUID();
        CounterHudPayload payload = new CounterHudPayload(id, "Alice", 7);

        assertEquals(id, payload.playerId());
        assertEquals("Alice", payload.playerName());
        assertEquals(7, payload.joinCount());
    }

    @Test
    void 渲染文案由载荷单点决定() {
        CounterHudPayload payload = new CounterHudPayload(UUID.randomUUID(), "Bob", 3);

        assertEquals("Bob 已加入 3 次", payload.displayText());
    }
}
