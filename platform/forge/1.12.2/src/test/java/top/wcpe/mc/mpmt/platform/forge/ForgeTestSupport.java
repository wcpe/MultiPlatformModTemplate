package top.wcpe.mc.mpmt.platform.forge;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.platform.forge.client.ForgeClientSession;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudPort;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudSnapshot;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransportPort;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;

/**
 * Forge 1.12.2 测试用的最小替身（Java 8 兼容）。
 *
 * <p>1.12.2 被测类只依赖我方 L0/L1 接缝（{@link ForgeClientTransportPort}、{@link MachineCodeProvider}）
 * 与纯数据对象，故用假实现注入即可在纯 JVM 下驱动，无需真实 Minecraft 客户端运行态。
 */
public final class ForgeTestSupport {

    private ForgeTestSupport() {
        // 工具类不实例化
    }

    /** 记录收发调用的客户端传输替身。 */
    public static final class FakeClientTransport implements ForgeClientTransportPort {
        /** 向服务端发送的负载（按顺序）。 */
        public final List<byte[]> sent = new ArrayList<byte[]>();
        /** {@code clearReceiver} 被callCount。 */
        public int clearCount;
        private final int maxPayload;
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        public FakeClientTransport() {
            this(32767);
        }

        public FakeClientTransport(int maxPayload) {
            this.maxPayload = maxPayload;
        }

        /** 是否isReceiverRegistered。 */
        public boolean isReceiverRegistered() {
            return receiver != null;
        }

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            throw new UnsupportedOperationException("client-only 传输不支持向指定客户端发送");
        }

        @Override
        public void send(byte[] data) {
            sent.add(data);
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return maxPayload;
        }

        @Override
        public void clearReceiver() {
            clearCount++;
            receiver = null;
        }

        /** 模拟服务端来包；{@code null} 连接句柄符合 1.12.2 客户端语义。 */
        public void 模拟来包(byte[] data) {
            if (receiver == null) {
                throw new IllegalStateException("receiver未注册");
            }
            receiver.accept(null, data);
        }
    }

    /** 固定返回值的machineCode提供者替身。 */
    public static final class FakeMachineCodeProvider implements MachineCodeProvider {
        private final String machineCode;
        private int callCount;

        public FakeMachineCodeProvider(String machineCode) {
            this.machineCode = machineCode;
        }

        @Override
        public String get() {
            callCount++;
            return machineCode;
        }

        public int callCount() {
            return callCount;
        }
    }

    /** 记录注册 / 清空的最小 HUD 接缝替身。 */
    public static final class FakeHud implements ForgeHudPort {
        private PacketDispatcher dispatcher;
        private ForgeHudSnapshot snapshot;
        /** {@code register} 被callCount。 */
        public int registerCount;
        /** {@code clear} 被callCount。 */
        public int clearCount;

        @Override
        public void register(PacketDispatcher dispatcher) {
            registerCount++;
            this.dispatcher = dispatcher;
        }

        @Override
        public ForgeHudSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void clear() {
            clearCount++;
            this.snapshot = null;
        }

        /** 是否已拿到dispatcher。 */
        public boolean isRegistered() {
            return dispatcher != null;
        }

        public PacketDispatcher dispatcher() {
            return dispatcher;
        }

        /** 直接摆一个snapshot（模拟收包后的结果）。 */
        public void 设snapshot(ForgeHudSnapshot value) {
            this.snapshot = value;
        }
    }

    /** 用假端口装配一个真实的客户端产品session。 */
    public static ForgeClientSession session(FakeClientTransport transport, FakeHud hud) {
        return new ForgeClientSession(
                transport, hud, "9.9.9", new FakeMachineCodeProvider("machineCode"));
    }

    /** 用全新假端口装配一个真实session（使用方从返回值自行取用假端口）。 */
    public static ForgeClientSession session(FakeClientTransport transport) {
        return session(transport, new FakeHud());
    }
}
