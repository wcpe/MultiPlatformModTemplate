package top.wcpe.mc.mpmt.smoke;

import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * 进程内回环传输（测试用，FR-20）：把一对客户端 / 服务端 {@link TransportPort} 同 JVM 互连，无需 Minecraft。
 *
 * <p>客户端 {@code send(bytes)} 同步投递到服务端收包回调（携带代表该客户端的连接句柄）；
 * 服务端 {@code send(conn, bytes)} 同步投递回客户端。同步直连，便于穷举握手与往返逻辑。
 */
public final class InProcessLoopbackTransport {

    private static final class Conn implements ConnectionHandle {
    }

    private final Conn clientConn = new Conn();
    private volatile BiConsumer<ConnectionHandle, byte[]> serverHandler;
    private volatile BiConsumer<ConnectionHandle, byte[]> clientHandler;

    private final TransportPort server = new TransportPort() {
        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            BiConsumer<ConnectionHandle, byte[]> handler = clientHandler;
            if (handler != null) {
                handler.accept(clientConn, data);
            }
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("服务端发送须指定连接");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            serverHandler = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1 << 20;
        }
    };

    private final TransportPort client = new TransportPort() {
        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            send(data);
        }

        @Override
        public void send(byte[] data) {
            BiConsumer<ConnectionHandle, byte[]> handler = serverHandler;
            if (handler != null) {
                handler.accept(clientConn, data);
            }
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            clientHandler = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 1 << 20;
        }
    };

    /** 客户端侧传输端口。 */
    public TransportPort client() {
        return client;
    }

    /** 服务端侧传输端口。 */
    public TransportPort server() {
        return server;
    }

    /** 服务端视角下代表该客户端的连接句柄。 */
    public ConnectionHandle clientConnection() {
        return clientConn;
    }
}
