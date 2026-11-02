package top.wcpe.mc.mpmt.examples.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.kernel.port.ConfigPort;

/** 配置加载测试（域侧）：缺文件回缺省、有值则用、非法值归一、错误不被吞（示范域 counter）。 */
class CounterConfigsTest {

    @Test
    void 缺文件时返回缺省配置() {
        CounterConfig config = new CounterConfigs(new StubConfigPort()).loadOrDefault();

        assertEquals(CounterConfig.DEFAULT_REMINDER_DELAY_TICKS, config.reminderDelayTicks());
        assertEquals(CounterConfig.DEFAULT_REMINDER_PERIOD_TICKS, config.reminderPeriodTicks());
    }

    @Test
    void 端口给出值时用该值() {
        StubConfigPort port = new StubConfigPort();
        port.put(CounterConfigs.FILE_NAME, new CounterConfig(7L, 99L));

        CounterConfig config = new CounterConfigs(port).loadOrDefault();

        assertEquals(7L, config.reminderDelayTicks());
        assertEquals(99L, config.reminderPeriodTicks());
    }

    @Test
    void 端口给出非法值时被归一() {
        StubConfigPort port = new StubConfigPort();
        port.put(CounterConfigs.FILE_NAME, new CounterConfig(0L, -5L));

        CounterConfig config = new CounterConfigs(port).loadOrDefault();

        assertEquals(CounterConfig.DEFAULT_REMINDER_DELAY_TICKS, config.reminderDelayTicks());
        assertEquals(CounterConfig.DEFAULT_REMINDER_PERIOD_TICKS, config.reminderPeriodTicks());
    }

    @Test
    void 端口抛错时不静默吞() {
        ConfigPort broken = new ConfigPort() {
            @Override
            public <T> Optional<T> read(String fileName, Class<T> type) {
                throw new IllegalStateException("配置文件写坏了");
            }
        };

        assertThrows(IllegalStateException.class, () -> new CounterConfigs(broken).loadOrDefault());
    }

    @Test
    void 自定义文件名也会传给端口() {
        StubConfigPort port = new StubConfigPort();
        port.put("other.json", new CounterConfig(3L, 4L));

        CounterConfig config = new CounterConfigs(port).loadOrDefault("other.json");

        assertEquals(3L, config.reminderDelayTicks());
        assertEquals(4L, config.reminderPeriodTicks());
    }

    /** 内存配置端口桩：按文件名返回预设值。 */
    private static final class StubConfigPort implements ConfigPort {
        private final Map<String, Object> values = new HashMap<>();

        void put(String fileName, Object value) {
            values.put(fileName, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> read(String fileName, Class<T> type) {
            return Optional.ofNullable((T) values.get(fileName));
        }
    }
}
