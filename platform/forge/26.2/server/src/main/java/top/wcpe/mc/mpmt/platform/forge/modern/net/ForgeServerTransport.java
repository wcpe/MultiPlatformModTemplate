package top.wcpe.mc.mpmt.platform.forge.modern.net;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** Forge 26.2 服务端裸字节传输适配。 */
public final class ForgeServerTransport implements TransportPort {

    private final ForgeTypedPayloadChannel channel;
    private final Map<ServerPlayerRef, ForgeConnectionHandle> handles = new ConcurrentHashMap<>();

    public ForgeServerTransport(ForgeTypedPayloadChannel channel) {
        this.channel = Objects.requireNonNull(channel, "通道不能为空");
    }

    /** 按底层 ServerPlayer 身份获取或创建稳定连接句柄。 */
    public ForgeConnectionHandle onConnected(net.minecraft.server.level.ServerPlayer player) {
        return handles.computeIfAbsent(
                new ServerPlayerRef(player), ref -> new ForgeConnectionHandle(player));
    }

    /** 玩家断开后清除其连接句柄。 */
    public ForgeConnectionHandle onDisconnected(net.minecraft.server.level.ServerPlayer player) {
        return handles.remove(new ServerPlayerRef(player));
    }

    /** 清除全部连接句柄。 */
    public void clearConnections() {
        handles.clear();
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        channel.sendToPlayer(((ForgeConnectionHandle) connection).player(), data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "收包器不能为空");
        channel.registerServerReceiver(
                (player, data) -> handler.accept(onConnected(player), data));
    }

    @Override
    public int maxPayloadSize() {
        return ForgeTypedPayloadChannel.MAX_PAYLOAD_SIZE;
    }

    /** ServerPlayer 的身份键（按对象身份比较，避免重连误用旧句柄）。 */
    private static final class ServerPlayerRef {
        private final net.minecraft.server.level.ServerPlayer player;

        ServerPlayerRef(net.minecraft.server.level.ServerPlayer player) {
            this.player = player;
        }

        @Override
        @SuppressWarnings("PMD.CompareObjectsWithEquals")
        public boolean equals(Object o) {
            return this == o || (o instanceof ServerPlayerRef other && player == other.player);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(player);
        }
    }
}
