package top.wcpe.mc.mpmt.platform.fabric.net;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricServerNetwork;

/**
 * Fabric 服务端传输适配（L3）：把 L0 {@link TransportPort} 适配到 {@link FabricServerNetwork} 版本绑定。
 *
 * <p>版本无关——具体收发 API 的版本差异由注入的 {@link FabricServerNetwork}（L4 vX_Y）吸收（ADR-0003）。
 * 本适配只服务<b>服务端</b>方向（向连接发、收连接来包）；客户端方向（无连接发送）由客户端适配另行提供。
 */
public final class FabricServerTransport implements TransportPort {

    private final FabricServerNetwork network;
    private final Map<UUID, FabricConnectionHandle> connections = new ConcurrentHashMap<>();

    public FabricServerTransport(FabricServerNetwork network) {
        this.network = Objects.requireNonNull(network, "network 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        network.send(connection, data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送（客户端发送见客户端传输适配）");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        Objects.requireNonNull(handler, "handler 不能为空");
        network.registerReceiver(
                (connection, data) -> {
                    FabricConnectionHandle received = (FabricConnectionHandle) connection;
                    handler.accept(currentConnection(received.player()), data);
                });
    }

    /** 玩家进入 PLAY 阶段时取得该物理连接唯一句柄。 */
    public FabricConnectionHandle onConnected(ServerPlayer player) {
        return currentConnection(player);
    }

    /**
     * 按在线玩家取得（或复用）当前物理连接句柄。
     *
     * <p>验收场景下发产品包时须用本方法取与产品栈一致的句柄，勿自行 {@code new}。
     */
    public FabricConnectionHandle connectionFor(ServerPlayer player) {
        return currentConnection(player);
    }

    /** 玩家退出时仅移除同一原生玩家对象对应的物理连接。 */
    public FabricConnectionHandle onDisconnected(ServerPlayer player) {
        FabricConnectionHandle current = connections.get(player.getUUID());
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

    private FabricConnectionHandle currentConnection(ServerPlayer player) {
        return connections.compute(
                player.getUUID(),
                (playerId, current) ->
                        current == null || !samePlayer(current, player)
                                ? new FabricConnectionHandle(player)
                                : current);
    }

    /** 物理连接按原生玩家对象身份比较，不能用 UUID 相等替代。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean samePlayer(FabricConnectionHandle connection, ServerPlayer player) {
        return connection.player() == player;
    }
}
