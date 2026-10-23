package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.EntityRef;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;
import top.wcpe.mc.mpmt.kernel.ref.WorldRef;

/** 客户端实现测试：展示动作按实体归属切线程（示范域 counter）。 */
class ChatCounterDisplayTest {

    @Test
    void 展示动作切到实体线程并发出消息() {
        RecordingScheduler scheduler = new RecordingScheduler();
        List<String> sent = new ArrayList<>();
        MessagePort message = (p, text) -> sent.add(text);
        PlayerRef player = new PlayerRef(java.util.UUID.randomUUID(), "Alice");

        new ChatCounterDisplay(message, scheduler).showJoinCount(player, 7);

        assertEquals(player.getUuid(), scheduler.lastEntity.getId());
        assertEquals(java.util.Collections.singletonList("Alice 已加入 7 次"), sent);
    }

    /** 调度桩：立即执行并记录归属。 */
    private static final class RecordingScheduler implements SchedulerPort {
        private EntityRef lastEntity;

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            lastEntity = entity;
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
            return () -> {
                // 测试无需真实周期
            };
        }
    }
}
