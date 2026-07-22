package top.wcpe.mc.mpmt.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentRetryRequestPacket;
import top.wcpe.mc.mpmt.protocol.reliability.Fragmenter;
import top.wcpe.mc.mpmt.protocol.reliability.OutboundFragmentCache;
import top.wcpe.mc.mpmt.protocol.reliability.Reassembler;

/**
 * 跨端协议收发管线：负责包编解码、路由及 FR-24 分片可靠性。
 *
 * <p>接收端首次重组超时发送 {@link FragmentRetryRequestPacket}；发送端按连接与序列从有限出站缓存重发整组一次。
 * 接收端第二次超时，或发送端找不到缓存时，调用 {@link ResyncHandler} 升级重同步。
 */
public final class PacketDispatcher {

    private static final Logger LOGGER = Logger.getLogger(PacketDispatcher.class.getName());
    private static final int FRAGMENT_OVERHEAD = 32;
    private static final long REASSEMBLE_TIMEOUT_MS = 30_000L;
    private static final long COMPLETED_GROUP_TTL_MS = 30_000L;
    private static final long OUTBOUND_CACHE_TTL_MS = 60_000L;
    private static final int MAX_FRAGMENT_TOTAL = 8192;
    private static final int MAX_REASSEMBLY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CACHED_GROUPS = 64;
    private static final int MAX_CACHED_BYTES = 8 * 1024 * 1024;

    /** 包处理器。 */
    @FunctionalInterface
    public interface Handler {
        void handle(ConnectionHandle connection, Packet packet);
    }

    /** 分片恢复失败后的重同步处理器。 */
    @FunctionalInterface
    public interface ResyncHandler {
        void requestResync(ConnectionHandle connection, int seqId);
    }

    /** 可靠性资源限制，包内可见以便纯 JVM 测试使用小容量边界。 */
    static final class ReliabilityConfig {
        private final long reassembleTimeoutMillis;
        private final long completedGroupTtlMillis;
        private final long outboundCacheTtlMillis;
        private final int maxFragmentTotal;
        private final int maxFragmentPayloadBytes;
        private final int maxReassemblyBytes;
        private final int maxCachedGroups;
        private final int maxCachedBytes;

        ReliabilityConfig(
                long reassembleTimeoutMillis,
                long completedGroupTtlMillis,
                long outboundCacheTtlMillis,
                int maxFragmentTotal,
                int maxFragmentPayloadBytes,
                int maxReassemblyBytes,
                int maxCachedGroups,
                int maxCachedBytes) {
            this.reassembleTimeoutMillis = reassembleTimeoutMillis;
            this.completedGroupTtlMillis = completedGroupTtlMillis;
            this.outboundCacheTtlMillis = outboundCacheTtlMillis;
            this.maxFragmentTotal = maxFragmentTotal;
            this.maxFragmentPayloadBytes = maxFragmentPayloadBytes;
            this.maxReassemblyBytes = maxReassemblyBytes;
            this.maxCachedGroups = maxCachedGroups;
            this.maxCachedBytes = maxCachedBytes;
        }
    }

    private final TransportPort transport;
    private final PacketCodec codec;
    private final LongSupplier clock;
    private final ResyncHandler resyncHandler;
    private final Map<Integer, Handler> handlers = new ConcurrentHashMap<>();
    private final Fragmenter fragmenter = new Fragmenter();
    private final Reassembler reassembler;
    private final OutboundFragmentCache outboundCache;
    private final AtomicInteger fragmentSeq = new AtomicInteger();
    private final int maxChunk;
    private volatile boolean clientEndpoint;

    public PacketDispatcher(TransportPort transport, PacketCodec codec) {
        this(transport, codec, defaultConfig(transport, REASSEMBLE_TIMEOUT_MS),
                System::currentTimeMillis, PacketDispatcher::logResyncRequired);
    }

    public PacketDispatcher(
            TransportPort transport,
            PacketCodec codec,
            long reassembleTimeoutMillis,
            LongSupplier clock,
            ResyncHandler resyncHandler) {
        this(transport, codec, defaultConfig(transport, reassembleTimeoutMillis), clock, resyncHandler);
    }

    PacketDispatcher(
            TransportPort transport,
            PacketCodec codec,
            ReliabilityConfig config,
            LongSupplier clock,
            ResyncHandler resyncHandler) {
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.codec = Objects.requireNonNull(codec, "codec 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.resyncHandler = Objects.requireNonNull(resyncHandler, "resyncHandler 不能为空");
        this.maxChunk = Math.max(1, transport.maxPayloadSize() - FRAGMENT_OVERHEAD);
        this.reassembler = createReassembler(config);
        this.outboundCache = new OutboundFragmentCache(
                config.outboundCacheTtlMillis, config.maxCachedGroups, config.maxCachedBytes);
        this.transport.onReceive(this::onBytes);
    }

    /** 注册某包 id 的处理器。 */
    public void on(int packetId, Handler handler) {
        handlers.put(packetId, Objects.requireNonNull(handler, "handler 不能为空"));
    }

    /** 客户端：发送一个包给服务端。 */
    public void send(Packet packet) {
        clientEndpoint = true;
        sendBytes(null, codec.encode(packet));
    }

    /** 服务端：发送一个包给指定连接。 */
    public void send(ConnectionHandle connection, Packet packet) {
        sendBytes(Objects.requireNonNull(connection, "connection 不能为空"), codec.encode(packet));
    }

    /** 主动推进超时、完成组去重和出站缓存清扫。 */
    public void tickReliability() {
        outboundCache.evictExpired(clock.getAsLong());
        reassembler.tickTimeouts();
    }

    /** 连接断开时清除该连接的出站缓存、待重组分组、超时历史与完成组标记。 */
    public void onDisconnected(ConnectionHandle connection) {
        outboundCache.clearConnection(connection);
        reassembler.clearConnection(connection);
        if (clientEndpoint) {
            outboundCache.clearConnection(null);
            reassembler.clearConnection(null);
        }
    }

    private Reassembler createReassembler(ReliabilityConfig config) {
        return new Reassembler(
                config.reassembleTimeoutMillis,
                config.completedGroupTtlMillis,
                config.maxFragmentTotal,
                config.maxFragmentPayloadBytes,
                config.maxReassemblyBytes,
                clock,
                this::onFragmentTimeout);
    }

    private void sendBytes(ConnectionHandle connection, byte[] encoded) {
        if (encoded.length <= maxChunk) {
            rawSend(connection, encoded);
            return;
        }
        int seqId = fragmentSeq.incrementAndGet();
        List<byte[]> frames = encodeFragments(fragmenter.split(seqId, encoded, maxChunk));
        outboundCache.put(connection, seqId, frames, clock.getAsLong());
        sendFrames(connection, frames);
    }

    private List<byte[]> encodeFragments(List<FragmentPacket> fragments) {
        List<byte[]> frames = new ArrayList<>(fragments.size());
        for (FragmentPacket fragment : fragments) {
            frames.add(codec.encode(fragment));
        }
        return frames;
    }

    private void sendFrames(ConnectionHandle connection, List<byte[]> frames) {
        for (byte[] frame : frames) {
            rawSend(connection, frame);
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
        Packet packet = decode(data);
        if (packet == null) {
            return;
        }
        if (packet.id() == PacketIds.FRAGMENT) {
            acceptFragment(connection, (FragmentPacket) packet);
            return;
        }
        if (packet.id() == PacketIds.FRAGMENT_RETRY_REQUEST) {
            handleRetryRequest(connection, (FragmentRetryRequestPacket) packet);
            return;
        }
        route(connection, packet);
    }

    private void acceptFragment(ConnectionHandle connection, FragmentPacket fragment) {
        reassembler.tickTimeouts();
        Optional<byte[]> full = reassembler.accept(connection, fragment);
        full.ifPresent(bytes -> onBytes(connection, bytes));
    }

    private Packet decode(byte[] data) {
        try {
            return codec.decode(data);
        } catch (ProtocolException ex) {
            LOGGER.log(Level.WARNING, "丢弃非法或截断的入站包：" + ex.getMessage());
            return null;
        }
    }

    private void route(ConnectionHandle connection, Packet packet) {
        Handler handler = handlers.get(packet.id());
        if (handler == null) {
            LOGGER.log(Level.FINE, "无处理器，忽略包 id=0x{0}", Integer.toHexString(packet.id()));
            return;
        }
        handler.handle(connection, packet);
    }

    private void onFragmentTimeout(ConnectionHandle connection, int seqId, int timeoutCount) {
        if (timeoutCount == 1) {
            sendRetryRequest(connection, seqId);
            return;
        }
        reassembler.clearGroup(connection, seqId);
        resyncHandler.requestResync(connection, seqId);
    }

    private void sendRetryRequest(ConnectionHandle connection, int seqId) {
        ConnectionHandle destination = clientEndpoint ? null : connection;
        rawSend(destination, codec.encode(new FragmentRetryRequestPacket(seqId)));
    }

    private void handleRetryRequest(ConnectionHandle connection, FragmentRetryRequestPacket request) {
        ConnectionHandle cacheConnection = clientEndpoint ? null : connection;
        List<byte[]> frames = outboundCache.takeForRetry(
                cacheConnection, request.getSeqId(), clock.getAsLong());
        if (frames == null) {
            resyncHandler.requestResync(connection, request.getSeqId());
            return;
        }
        sendFrames(cacheConnection, frames);
    }

    private static ReliabilityConfig defaultConfig(TransportPort transport, long timeoutMillis) {
        int maxPayload = Math.max(1, transport.maxPayloadSize());
        return new ReliabilityConfig(
                timeoutMillis,
                COMPLETED_GROUP_TTL_MS,
                OUTBOUND_CACHE_TTL_MS,
                MAX_FRAGMENT_TOTAL,
                maxPayload,
                MAX_REASSEMBLY_BYTES,
                MAX_CACHED_GROUPS,
                MAX_CACHED_BYTES);
    }

    private static void logResyncRequired(ConnectionHandle connection, int seqId) {
        LOGGER.warning("分片恢复失败，需要重同步，seqId=" + seqId);
    }
}
