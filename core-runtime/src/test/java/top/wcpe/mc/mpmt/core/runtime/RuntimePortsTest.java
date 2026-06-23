package top.wcpe.mc.mpmt.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 端口注册表的注册 / 读取 / 边界（对应 testing-and-quality §2「端口工厂构造的端口非空」）。 */
class RuntimePortsTest {

    /** 测试用端口接口与实现。 */
    interface SamplePort {
        String hello();
    }

    private static final class SamplePortImpl implements SamplePort {
        @Override
        public String hello() {
            return "hi";
        }
    }

    @Test
    @DisplayName("注册后可按类型取回同一实例")
    void 注册后可取回() {
        RuntimePorts ports = new RuntimePorts();
        SamplePort impl = new SamplePortImpl();
        ports.register(SamplePort.class, impl);

        assertSame(impl, ports.get(SamplePort.class));
        assertTrue(ports.contains(SamplePort.class));
        assertEquals(Optional.of(impl), ports.find(SamplePort.class));
    }

    @Test
    @DisplayName("未注册：get 抛异常、find 为空、contains 为 false")
    void 未注册的端口() {
        RuntimePorts ports = new RuntimePorts();
        assertThrows(IllegalStateException.class, () -> ports.get(SamplePort.class));
        assertFalse(ports.find(SamplePort.class).isPresent());
        assertFalse(ports.contains(SamplePort.class));
    }

    @Test
    @DisplayName("重复注册同一端口类型：抛异常")
    void 重复注册() {
        RuntimePorts ports = new RuntimePorts();
        ports.register(SamplePort.class, new SamplePortImpl());
        assertThrows(IllegalStateException.class, () -> ports.register(SamplePort.class, new SamplePortImpl()));
    }

    @Test
    @DisplayName("null 入参抛 NPE")
    void null入参() {
        RuntimePorts ports = new RuntimePorts();
        assertThrows(NullPointerException.class, () -> ports.register(null, new SamplePortImpl()));
        assertThrows(NullPointerException.class, () -> ports.register(SamplePort.class, null));
        assertThrows(NullPointerException.class, () -> ports.get(null));
    }
}
