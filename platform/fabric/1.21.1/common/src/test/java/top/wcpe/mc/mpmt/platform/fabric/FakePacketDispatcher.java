package top.wcpe.mc.mpmt.platform.fabric;

import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;

/**
 * 记录型收发管线：以真实 {@link PacketDispatcher} + 捕获型传输端口驱动客户端处理路径。
 *
 * <p>投递走真实字节编解码（{@link PacketCodec}），因此被驱动的是产品的完整收包链路，
 * 而非仅处理器桩。
 */
public final class FakePacketDispatcher {

    private final PacketDispatcher dispatcher;
    private final LoopbackTransport transport = new LoopbackTransport();

    public FakePacketDispatcher() {
        this.dispatcher = new PacketDispatcher(transport, new PacketCodec());
    }

    /** 底层真实分发器（供产品注册处理器）。 */
    public PacketDispatcher dispatcher() {
        return dispatcher;
    }

    /** 以真实字节把包投递进本管线的收包入口。 */
    public void deliverTo(Packet packet) {
        transport.deliver(new PacketCodec().encode(packet));
    }

    /** 捕获型传输端口：把入站字节回灌到分发器注册的收包处理器。 */
    private static final class LoopbackTransport implements TransportPort {

        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            // 用例只关心入站方向
        }

        @Override
        public void send(byte[] data) {
            // 用例只关心入站方向
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1_048_576;
        }

        void deliver(byte[] data) {
            if (receiver == null) {
                throw new IllegalStateException("分发器尚未注册收包处理器");
            }
            receiver.accept(null, data);
        }
    }
}
