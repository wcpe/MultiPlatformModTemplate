package top.wcpe.mc.mpmt.core.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运行时生命周期、特性时序与上下文装配（FR-02 / FR-03 验收）。 */
class MpmtRuntimeTest {

    /** 记录启用 / 停用调用顺序的假特性。 */
    private static final class RecordingFeature implements Feature {
        private final String name;
        private final List<String> log;

        RecordingFeature(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void onEnable(RuntimeContext context) {
            log.add("enable:" + name);
        }

        @Override
        public void onDisable(RuntimeContext context) {
            log.add("disable:" + name);
        }
    }

    @Test
    @DisplayName("启用按注册顺序、停用按逆序")
    void 启用顺序与停用逆序() {
        List<String> log = new ArrayList<>();
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.features().register(new RecordingFeature("a", log));
        runtime.features().register(new RecordingFeature("b", log));

        runtime.enable();
        runtime.disable();

        assertEquals(Arrays.asList("enable:a", "enable:b", "disable:b", "disable:a"), log);
        assertEquals(MpmtRuntime.Phase.DISABLED, runtime.phase());
    }

    @Test
    @DisplayName("生命周期守护：重复启用 / 未启用即停用 均抛异常")
    void 生命周期守护() {
        MpmtRuntime runtime = new MpmtRuntime();
        assertThrows(IllegalStateException.class, runtime::disable);
        runtime.enable();
        assertThrows(IllegalStateException.class, runtime::enable);
        runtime.disable();
        assertThrows(IllegalStateException.class, runtime::disable);
    }

    @Test
    @DisplayName("上下文向特性提供 EventBus 与已注册端口")
    void 上下文提供能力() {
        MpmtRuntime runtime = new MpmtRuntime();
        RuntimePortsTest.SamplePort port = () -> "hi";
        runtime.ports().register(RuntimePortsTest.SamplePort.class, port);

        List<Object> captured = new ArrayList<>();
        runtime.features().register(new Feature() {
            @Override
            public String name() {
                return "capability-probe";
            }

            @Override
            public void onEnable(RuntimeContext context) {
                captured.add(context.eventBus());
                captured.add(context.port(RuntimePortsTest.SamplePort.class));
            }
        });

        runtime.enable();

        assertNotNull(captured.get(0));
        assertSame(port, captured.get(1));
        // 上下文即运行时自身
        assertSame(runtime.eventBus(), captured.get(0));
    }

    @Test
    @DisplayName("特性启用异常向上传播（启动期失败快，不静默吞掉）")
    void 启用异常传播() {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.features().register(new Feature() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public void onEnable(RuntimeContext context) {
                throw new IllegalStateException("故意启用失败");
            }
        });

        assertThrows(IllegalStateException.class, runtime::enable);
        // 启用失败后仍停留在 NEW（未标记 ENABLED）
        assertEquals(MpmtRuntime.Phase.NEW, runtime.phase());
    }
}
