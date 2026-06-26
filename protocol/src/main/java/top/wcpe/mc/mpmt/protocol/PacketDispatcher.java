package top.wcpe.mc.mpmt.protocol;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.reliability.Fragmenter;
import top.wcpe.mc.mpmt.protocol.reliability.Reassembler;

/**
 * 跨端收发管线（FR-19）：在 {@link TransportPort}（裸字节）之上用 {@link PacketCodec} 收发协议包。
 *
 * <p>发送：包 → 编码 → 经 TransportPort 发字节。接收：TransportPort 回调裸字节 → 解码 → 按包 id 路由到处理器。
 * 非法 / 截断字节被捕获记录、不崩溃（API.md §2）；无处理器的包静默忽略。线程安全（处理器表并发安全）。
 *
 * <p><b>可靠性层（FR-24）装配在此、对上层透明</b>：发送时若编码超过平台单包上限（{@link TransportPort#maxPayloadSize}）
 * 则经 {@link Fragmenter} 切片逐片发；接收时 {@link PacketIds#FRAGMENT} 包先经 {@link Reassembler} 重组（CRC 校验 +
 * 超时清理），集齐后按原包重新入站路由。小于上限的包原样收发、行为不变。重连重同步（{@code ResyncCoordinator}）由
 * 服务端 / 客户端网络特性按需装配在本管线之上。
 *
 * <p>处理器可能在任意网络线程被调用；处理器若触碰世界 / 领域状态，须自行经 SchedulerPort 按归属切线程（ADR-0013）。
 */
public final class PacketDispatcher {

    private static final Logger LOGGER = Logger.getLogger(PacketDispatcher.class.getName());

    /** 分片包头部保守预留字节（id + seqId/index/total 变长 + crc32 + 片长前缀），用于从单包上限推算单片载荷上限。 */
    private static final int FRAGMENT_OVERHEAD = 32;
    /** 分片重组超时（毫秒）：超时未集齐即清理，防残片泄露。 */
    private static final long REASSEMBLE_TIMEOUT_MS = 30_000L;

    /** 包处理器。 */
    @FunctionalInterface
    public interface Handler {
        void handle(ConnectionHandle connection, Packet packet);
    }

    private final TransportPort transport;
    private final PacketCodec codec;
    private final Map<Integer, Handler> handlers = new ConcurrentHashMap<>();

    // 可靠性层（FR-24）：分片/重组对上层透明
    private final Fragmenter fragmenter = new Fragmenter();
    private final Reassembler reassembler = new Reassembler(REASSEMBLE_TIMEOUT_MS);
    private final AtomicInteger fragmentSeq = new AtomicInteger();
    private final int maxChunk;

    public PacketDispatcher(TransportPort transport, PacketCodec codec) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        // 单片载荷上限 = 平台单包上限 - 分片头部预留（至少 1）
        this.maxChunk = Math.max(1, transport.maxPayloadSize() - FRAGMENT_OVERHEAD);
        this.transport.onReceive(this::onBytes);
    }

    /** 注册某包 id 的处理器。 */
    public void on(int packetId, Handler handler) {
        handlers.put(packetId, Objects.requireNonNull(handler, "handler 不能为空"));
    }

    /** 客户端：发送一个包给服务端。 */
    public void send(Packet packet) {
        sendBytes(null, codec.encode(packet));
    }

    /** 服务端：发送一个包给指定连接。 */
    public void send(ConnectionHandle connection, Packet packet) {
        sendBytes(Objects.requireNonNull(connection, "connection 不能为空"), codec.encode(packet));
    }

    /** 按单包上限决定直发或分片发（connection 为 null 走客户端无连接发送）。 */
    private void sendBytes(ConnectionHandle connection, byte[] encoded) {
        if (encoded.length <= maxChunk) {
            rawSend(connection, encoded);
            return;
        }
        // 超单包上限：切片后逐片发（FR-24），各片自身 < maxChunk 不会再分片
        for (FragmentPacket fragment :
                fragmenter.split(fragmentSeq.incrementAndGet(), encoded, maxChunk)) {
            rawSend(connection, codec.encode(fragment));
        }
    }

    private void rawSend(ConnectionHandle connection, byte[] bytes) {
        if (connection == null) {
            transport.send(bytes);
        } else {
            transport.send(connection, bytes);
        }
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
        // 可靠性层（FR-24）：分片包先重组，集齐 + CRC 通过后按原包重新入站路由
        if (packet.id() == PacketIds.FRAGMENT) {
            reassembler.tickTimeouts();
            Optional<byte[]> reassembled = reassembler.accept((FragmentPacket) packet);
            reassembled.ifPresent(full -> onBytes(connection, full));
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
