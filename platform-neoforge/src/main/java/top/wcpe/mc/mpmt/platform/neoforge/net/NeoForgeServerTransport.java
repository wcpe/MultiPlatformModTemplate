package top.wcpe.mc.mpmt.platform.neoforge.net;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.neoforge.version.NeoForgeServerNetwork;

/**
 * NeoForge 服务端传输适配（L3）：把 L0 {@link TransportPort} 委托给启动期选定的 L4 网络适配器。
 *
 * <p>版本敏感的通道注册、收发与单包上限由 {@link NeoForgeServerNetwork} 吸收；本类只管理连接表与上层回调。
 */
public final class NeoForgeServerTransport implements TransportPort {

    private final NeoForgeServerNetwork network;
    private final Map<UUID, NeoForgeConnectionHandle> connections = new ConcurrentHashMap<>();

    public NeoForgeServerTransport(NeoForgeServerNetwork network) {
        this.network = Objects.requireNonNull(network, "network 不能为空");
    }

    /** 注入客户端收包处理器（仅 {@code Dist.CLIENT} 调用）。 */
    public void setClientReceiver(Consumer<byte[]> receiver) {
        network.setClientReceiver(receiver);
    }

    /** 客户端经同一产品通道向服务端发送原始协议字节。 */
    public void sendToServer(byte[] data) {
        network.sendToServer(data);
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((NeoForgeConnectionHandle) connection).player();
        network.send(player, data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        network.registerReceiver(
                (player, data) -> handler.accept(currentConnection(player), data));
    }

    /** 玩家进入 PLAY 阶段时取得该物理连接唯一句柄。 */
    public NeoForgeConnectionHandle onConnected(ServerPlayer player) {
        return currentConnection(player);
    }

    /** 玩家退出时仅移除同一原生玩家对象对应的物理连接。 */
    public NeoForgeConnectionHandle onDisconnected(ServerPlayer player) {
        NeoForgeConnectionHandle current = connections.get(player.getUUID());
        if (current == null || !samePlayer(current, player)) {
            return null;
        }
        return connections.remove(player.getUUID(), current) ? current : null;
    }

    /** 服务端停止时释放全部连接句柄。 */
    public void clearConnections() {
        connections.clear();
    }

    @Override
    public int maxPayloadSize() {
        return network.maxPayloadSize();
    }

    private NeoForgeConnectionHandle currentConnection(ServerPlayer player) {
        return connections.compute(
                player.getUUID(),
                (playerId, current) ->
                        current == null || !samePlayer(current, player)
                                ? (NeoForgeConnectionHandle) network.connectionOf(player)
                                : current);
    }

    /** 物理连接按原生玩家对象身份比较，不能用 UUID 相等替代。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean samePlayer(NeoForgeConnectionHandle connection, ServerPlayer player) {
        return connection.player() == player;
    }
}
