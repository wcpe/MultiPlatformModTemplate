package top.wcpe.mc.mpmt.platform.forge.net;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.forge.version.ForgeServerNetwork;

/**
 * Forge 服务端传输适配（L3）：把 L0 {@link TransportPort} 委托给启动期选定的 L4 网络适配器。
 *
 * <p>版本敏感的通道注册、收发与单包上限由 {@link ForgeServerNetwork} 吸收；本类只管理连接表与上层回调。
 */
public final class ForgeServerTransport implements TransportPort {

    private final ForgeServerNetwork network;
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;
    private final Map<UUID, ForgeConnectionHandle> connections = new ConcurrentHashMap<>();

    public ForgeServerTransport(ForgeServerNetwork network) {
        this.network = Objects.requireNonNull(network, "network 不能为空");
    }

    /** 产品通道资源位置（供客户端 HUD 收包注册同一通道，FR-27）。 */
    public ResourceLocation channelId() {
        return network.channelId();
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        ServerPlayer player = ((ForgeConnectionHandle) connection).player();
        network.send(player, data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        this.receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
        network.registerReceiver(
                (player, data) -> {
                    BiConsumer<ConnectionHandle, byte[]> current = receiveHandler;
                    if (current != null) {
                        current.accept(currentConnection(player), data);
                    }
                });
    }

    /** 玩家进入 PLAY 阶段时取得该物理连接唯一句柄。 */
    public ForgeConnectionHandle onConnected(ServerPlayer player) {
        return currentConnection(player);
    }

    /**
     * 按在线玩家取得（或复用）当前物理连接句柄。
     *
     * <p>验收场景下发产品包时须用本方法取与产品栈一致的句柄，勿自行 {@code new}。
     */
    public ForgeConnectionHandle connectionFor(ServerPlayer player) {
        return currentConnection(player);
    }

    /** 玩家退出时仅移除同一原生玩家对象对应的物理连接。 */
    public ForgeConnectionHandle onDisconnected(ServerPlayer player) {
        ForgeConnectionHandle current = connections.get(player.getUUID());
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

    private ForgeConnectionHandle currentConnection(ServerPlayer player) {
        return connections.compute(
                player.getUUID(),
                (playerId, current) ->
                        current == null || !samePlayer(current, player)
                                ? (ForgeConnectionHandle) network.connectionOf(player)
                                : current);
    }

    /** 物理连接按原生玩家对象身份比较，不能用 UUID 相等替代。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private static boolean samePlayer(ForgeConnectionHandle connection, ServerPlayer player) {
        return connection.player() == player;
    }
}
