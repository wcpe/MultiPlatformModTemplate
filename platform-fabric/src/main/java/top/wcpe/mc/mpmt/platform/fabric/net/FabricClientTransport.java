package top.wcpe.mc.mpmt.platform.fabric.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.fabric.version.FabricClientNetwork;

/**
 * Fabric 客户端传输适配（L3，仅客户端环境）：把 L0 {@link TransportPort} 适配到 {@link FabricClientNetwork} 版本绑定。
 *
 * <p>客户端只有一条到服务端的连接、无寻址发送；故 {@link #send(byte[])} 走客户端发送，
 * {@link #send(ConnectionHandle, byte[])} 不支持。收包以 {@link #SERVER} 哨兵句柄上报（上层客户端逻辑用
 * 无连接发送回复、不依赖该句柄）。版本差异由注入的 {@link FabricClientNetwork}（L4 vX_Y）吸收（ADR-0003）。
 */
@Environment(EnvType.CLIENT)
public final class FabricClientTransport implements TransportPort {

    /** 服务端连接哨兵：客户端只有一条到服务端的连接，用单一句柄表示。 */
    private static final ConnectionHandle SERVER = new ConnectionHandle() {
    };

    private final FabricClientNetwork network;

    public FabricClientTransport(FabricClientNetwork network) {
        this.network = Objects.requireNonNull(network, "network 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        throw new UnsupportedOperationException("客户端传输只支持无连接发送（向服务端）");
    }

    @Override
    public void send(byte[] data) {
        network.send(data);
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        network.registerReceiver(data -> handler.accept(SERVER, data));
    }

    /** 客户端唯一服务端连接句柄，供断线时清理协议可靠性状态。 */
    public ConnectionHandle serverConnection() {
        return SERVER;
    }

    @Override
    public int maxPayloadSize() {
        return network.maxPayloadSize();
    }
}
