package top.wcpe.mc.mpmt.platform.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.ForgeClientHarness;
import top.wcpe.mc.mpmt.platform.forge.ForgeClientHarness.SidedHandlerRecorder;

/**
 * {@link ForgeClientTransport} 的 1.12.2 客户端裸字节通道适配器。
 *
 * <p>构造需 FML 运行态（{@code FMLEventChannel} 静态初始化读 {@code FMLCommonHandler.getSide()}），故先经
 * {@link ForgeClientHarness#installRecordingClientSide()} 装入客户端side，{@code NetworkRegistry} 即可在纯 JVM 下
 * 颁发通道。
 *
 * <p>由于 {@code FMLEventChannel} 是具体类而本工程未引入 mock 框架，出站以side委托的
 * {@code getClientToServerNetworkManager} invocation作为"确实发往服务端"的可观察证据；入站直接驱动
 * {@code onClientPayload}。
 */
class ForgeClientTransportTest {

    /**
     * 共享传输：{@code NetworkRegistry} 是进程级单例，同名通道只能注册一次，故整个测试类共用一个实例。
     * 其收包位在 {@code @BeforeEach} 清空，保证用例间互不干扰。
     */
    private static SidedHandlerRecorder side;
    private static ForgeClientTransport transport;

    @BeforeEach
    void 装入side与通道() {
        if (side == null) {
            side = ForgeClientHarness.装入可记录客户端侧别();
            transport = new ForgeClientTransport("MPMT");
        }
        side.清空();
        transport.clearReceiver();
    }

    @AfterEach
    void 清空收包位() {
        transport.clearReceiver();
    }

    @Test
    @DisplayName("构造：空通道名即拒绝")
    void 构造拒绝空通道名() {
        assertThrows(NullPointerException.class, () -> new ForgeClientTransport(null));
    }

    @Test
    @DisplayName("构造：通道名被登记为事件驱动通道，登记本身不发送任何包")
    void 构造登记通道() {
        assertFalse(
                side.调用过("getClientToServerNetworkManager"),
                "构造只登记通道，不应取值服务端connection");
    }

    @Test
    @DisplayName("载荷上限：恒为 32767（1.12.2 自定义负载上限）")
    void 载荷上限固定() {
        assertEquals(32767, transport.maxPayloadSize());
    }

    @Test
    @DisplayName("出站：向服务端发送时确实触达 FML 侧的服务端connection管理器")
    void 出站到达服务端connection管理器() {
        transport.send(new byte[] {7, 8, 9});

        assertTrue(
                side.调用过("getClientToServerNetworkManager"),
                "sendToServer 应经 FML 侧取服务端connection，实际invocation=" + side.invocationLog);
    }

    @Test
    @DisplayName("出站：空负载同样发出，不因零长度提前返回")
    void 出站空负载可发送() {
        transport.send(new byte[0]);

        assertTrue(side.调用过("getClientToServerNetworkManager"));
    }

    @Test
    @DisplayName("出站：连续发送逐次触达，不缓存也不合并")
    void 出站连续发送逐次触达() {
        transport.send(new byte[] {1});
        transport.send(new byte[] {2});
        transport.send(new byte[] {3});

        int count = 0;
        for (String invocation : side.invocationLog) {
            if (invocation.equals("getClientToServerNetworkManager")) {
                count++;
            }
        }
        assertEquals(3, count, "三次发送应三次触达");
    }

    @Test
    @DisplayName("出站：不支持向指定客户端发送（client-only 语义）")
    void 出站拒绝指定connection() {
        ConnectionHandle anyHandle =
                ConnectionHandle.class.cast(
                        ForgeClientHarness.代理(
                                ConnectionHandle.class,
                                (source, method, args) ->
                                        ForgeClientHarness.默认值(source, method, args)));

        assertThrows(
                UnsupportedOperationException.class,
                () -> transport.send(anyHandle, new byte[] {1}));
    }

    @Test
    @DisplayName("入站：未注册收包器时安静丢弃，不抛异常也不触达发送")
    void 入站无收包器时静默() {
        transport.clearReceiver();
        side.清空();

        transport.onClientPayload(来包(new byte[] {1, 2}));

        assertTrue(side.invocationLog.isEmpty(), "单纯入站不应触达出站路径");
    }

    @Test
    @DisplayName("入站：注册收包器后按connection为空的客户端语义投递负载")
    void 入站投递给收包器() {
        List<byte[]> received = new ArrayList<byte[]>();
        List<ConnectionHandle> connection = new ArrayList<ConnectionHandle>();
        transport.onReceive(
                (handle, data) -> {
                    connection.add(handle);
                    received.add(data);
                });

        transport.onClientPayload(来包(new byte[] {3, 4}));

        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {3, 4}, received.get(0));
        assertEquals(1, connection.size());
        assertEquals(null, connection.get(0), "1.12.2 客户端无connection句柄概念");
    }

    @Test
    @DisplayName("入站：连续来包逐次投递，各次负载独立")
    void 入站连续来包逐次deliver() {
        List<byte[]> received = new ArrayList<byte[]>();
        transport.onReceive((handle, data) -> received.add(data));

        transport.onClientPayload(来包(new byte[] {1}));
        transport.onClientPayload(来包(new byte[] {2, 2}));

        assertEquals(2, received.size());
        assertArrayEquals(new byte[] {1}, received.get(0));
        assertArrayEquals(new byte[] {2, 2}, received.get(1));
    }

    @Test
    @DisplayName("生命周期：clearReceiver 后不再投递，可重新注册")
    void clearReceiver后不再deliver() {
        List<byte[]> received = new ArrayList<byte[]>();
        transport.onReceive((handle, data) -> received.add(data));
        transport.clearReceiver();

        transport.onClientPayload(来包(new byte[] {9}));

        assertTrue(received.isEmpty(), "清空后不得再投递");

        transport.onReceive((handle, data) -> received.add(data));
        transport.onClientPayload(来包(new byte[] {10}));
        assertEquals(1, received.size(), "重新注册后应恢复投递");
    }

    @Test
    @DisplayName("生命周期：clearReceiver 幂等，未注册时也安全")
    void clearReceiver幂等() {
        transport.clearReceiver();
        transport.clearReceiver();

        assertFalse(side.调用过("getClientToServerNetworkManager"));
    }

    @Test
    @DisplayName("收包器：注册空回调即拒绝")
    void 拒绝空收包器() {
        assertThrows(NullPointerException.class, () -> transport.onReceive(null));
    }

    @Test
    @DisplayName("收包器：后注册的替换先注册的（单收包位）")
    void 后注册替换先注册() {
        List<String> order = new ArrayList<String>();
        transport.onReceive((handle, data) -> order.add("先"));
        transport.onReceive((handle, data) -> order.add("后"));

        transport.onClientPayload(来包(new byte[] {1}));

        assertEquals(1, order.size());
        assertEquals("后", order.get(0));
    }

    @Test
    @DisplayName("入站：收包器抛异常时向上传播，不静默吞掉")
    void 入站收包器异常传播() {
        transport.onReceive(
                (handle, data) -> {
                    throw new IllegalStateException("上层处理失败");
                });

        assertThrows(
                IllegalStateException.class, () -> transport.onClientPayload(来包(new byte[] {1})));
    }

    /** 造一个携带指定负载的入站包事件。 */
    private static FMLNetworkEvent.ClientCustomPacketEvent 来包(byte[] data) {
        FMLProxyPacket packet = ForgePayloadCodec.outgoing("MPMT", data);
        FMLNetworkEvent.ClientCustomPacketEvent event =
                (FMLNetworkEvent.ClientCustomPacketEvent)
                        未初始化(FMLNetworkEvent.ClientCustomPacketEvent.class);
        设字段(event, "packet", packet);
        return event;
    }

    private static Object 未初始化(Class<?> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造测试替身：" + type.getName(), error);
        }
    }

    /** 沿类层次查找字段并写入。 */
    private static void 设字段(Object target, String name, Object value) {
        try {
            for (Class<?> current = target.getClass();
                    current != null;
                    current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    // 继续向父类查找
                }
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法写入字段 " + name, error);
        }
    }
}
