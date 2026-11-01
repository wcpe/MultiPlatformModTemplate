package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 配置模型测试：缺省值、显式构造与非法值归一（示范域 counter）。 */
class CounterConfigTest {

    @Test
    void 无参构造给出缺省值() {
        CounterConfig config = new CounterConfig();

        assertEquals(CounterConfig.DEFAULT_REMINDER_DELAY_TICKS, config.reminderDelayTicks());
        assertEquals(CounterConfig.DEFAULT_REMINDER_PERIOD_TICKS, config.reminderPeriodTicks());
    }

    @Test
    void 显式构造保留给定值() {
        CounterConfig config = new CounterConfig(7L, 99L);

        assertEquals(7L, config.reminderDelayTicks());
        assertEquals(99L, config.reminderPeriodTicks());
    }

    @Test
    void 合法值归一后不变() {
        CounterConfig config = new CounterConfig(7L, 99L);

        assertSame(config, config.normalized(), "归一应就地修改并返回自身");
        assertEquals(7L, config.reminderDelayTicks());
        assertEquals(99L, config.reminderPeriodTicks());
    }

    @Test
    void 非正延迟回落缺省值() {
        CounterConfig config = new CounterConfig(0L, 99L).normalized();

        assertEquals(CounterConfig.DEFAULT_REMINDER_DELAY_TICKS, config.reminderDelayTicks());
        assertEquals(99L, config.reminderPeriodTicks(), "合法的间隔不应被连带修改");
    }

    @Test
    void 非正间隔回落缺省值() {
        CounterConfig config = new CounterConfig(7L, -5L).normalized();

        assertEquals(7L, config.reminderDelayTicks(), "合法的延迟不应被连带修改");
        assertEquals(CounterConfig.DEFAULT_REMINDER_PERIOD_TICKS, config.reminderPeriodTicks());
    }

    @Test
    void toString带上两个字段() {
        String text = new CounterConfig(7L, 99L).toString();

        assertTrue(text.contains("reminderDelayTicks=7"), "实际：" + text);
        assertTrue(text.contains("reminderPeriodTicks=99"), "实际：" + text);
    }
}
