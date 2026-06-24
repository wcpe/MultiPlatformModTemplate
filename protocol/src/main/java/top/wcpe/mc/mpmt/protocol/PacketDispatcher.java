package top.wcpe.mc.mpmt.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * 跨端收发管线（FR-19）：在 {@link TransportPort}（裸字节）之上用 {@link PacketCodec} 收发协议包。
 *
 * <p>发送：包 → 编码 → 经 TransportPort 发字节。接收：TransportPort 回调裸字节 → 解码 → 按包 id 路由到处理器。
 * 非法 / 截断字节被捕获记录、不崩溃（API.md §2）；无处理器的包静默忽略。线程安全（处理器表并发安全）。
 *
 * <p>处理器可能在任意网络线程被调用；处理器若触碰世界 / 领域状态，须自行经 SchedulerPort 按归属切线程（ADR-0013）。
 */
public final class PacketDispatcher {

    private static final Logger LOGGER = Logger.getLogger(PacketDispatcher.class.getName());

    /** 包处理器。 */
    @FunctionalInterface
    public interface Handler {
        void handle(ConnectionHandle connection, Packet packet);
    }

    private final TransportPort transport;
    private final PacketCodec codec;
    private final Map<Integer, Handler> handlers = new ConcurrentHashMap<>();

    public PacketDispatcher(TransportPort transport, PacketCodec codec) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.transport.onReceive(this::onBytes);
    }

    /** 注册某包 id 的处理器。 */
    public void on(int packetId, Handler handler) {
        handlers.put(packetId, Objects.requireNonNull(handler, "handler 不能为空"));
    }

    /** 客户端：发送一个包给服务端。 */
    public void send(Packet packet) {
        transport.send(codec.encode(packet));
    }

    /** 服务端：发送一个包给指定连接。 */
    public void send(ConnectionHandle connection, Packet packet) {
        transport.send(connection, codec.encode(packet));
    }

    private void onBytes(ConnectionHandle connection, byte[] data) {
        Packet packet;
        try {
            packet = codec.decode(data);
        } catch (ProtocolException ex) {
            // 非法 / 截断 / 未知包：丢弃并记录，不崩溃
            LOGGER.log(Level.WARNING, "丢弃非法或截断的入站包：" + ex.getMessage());
            return;
        }
        Handler handler = handlers.get(packet.id());
        if (handler == null) {
            LOGGER.log(Level.FINE, "无处理器，忽略包 id=0x{0}", Integer.toHexString(packet.id()));
            return;
        }
        handler.handle(connection, packet);
    }
}
