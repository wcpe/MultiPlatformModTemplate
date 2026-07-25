package top.wcpe.mc.mpmt.platform.bukkit.net;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.bukkit.version.BukkitServerNetwork;

/** Bukkit 服务端传输端口：把上层裸字节收发委托给启动期选定的版本适配器。 */
public final class BukkitServerTransport implements TransportPort {

    private final BukkitServerNetwork network;
    private volatile BiConsumer<ConnectionHandle, byte[]> receiveHandler;
    private volatile Consumer<ConnectionHandle> handledCallback;

    public BukkitServerTransport(BukkitServerNetwork network) {
        this.network = Objects.requireNonNull(network, "network 不能为空");
    }

    @Override
    public void send(ConnectionHandle connection, byte[] data) {
        network.send(connection, data);
    }

    @Override
    public void send(byte[] data) {
        throw new UnsupportedOperationException("服务端传输不支持无连接发送");
    }

    @Override
    public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
        receiveHandler = Objects.requireNonNull(handler, "handler 不能为空");
        network.registerReceiver(this::handle);
    }

    /** 注册上层处理完成回调，供平台入口执行握手后的连接处置。 */
    public void onHandled(Consumer<ConnectionHandle> callback) {
        handledCallback = Objects.requireNonNull(callback, "callback 不能为空");
    }

    @Override
    public int maxPayloadSize() {
        return network.maxPayloadSize();
    }

    private void handle(ConnectionHandle connection, byte[] data) {
        BiConsumer<ConnectionHandle, byte[]> handler = receiveHandler;
        if (handler == null) {
            return;
        }
        handler.accept(connection, data);
        Consumer<ConnectionHandle> callback = handledCallback;
        if (callback != null) {
            callback.accept(connection);
        }
    }
}
