package top.wcpe.mc.mpmt.platform.fabric.net;

import java.util.Objects;
import java.util.function.BiConsumer;
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
        network.registerReceiver(handler);
    }

    @Override
    public int maxPayloadSize() {
        return network.maxPayloadSize();
    }
}
