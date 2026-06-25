package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * 进程内回环传输（模拟服 GameTest 用，FR-23①）：在同一进程内把"服务端"与"客户端"两个 {@link TransportPort}
 * 直连——一端 {@code send} 的字节立即投递到另一端的 {@code onReceive}，无真实网络。
 *
 * <p>用于在真实 Fabric 服务端运行期跑通产品网络栈（握手 / 往返），验证协议 + 收发管线 + 装配在实际 mod 加载器
 * 运行时正确（捕获纯 JVM 单测看不到的 relocation / 类加载问题），headless 自动跑。
 */
public final class LoopbackTransport {

    /** 服务端视角下"客户端"连接句柄。 */
    private final ConnectionHandle clientConnection = new ConnectionHandle() {};
    /** 客户端视角下"服务端"连接句柄（客户端逻辑用无连接发送，不依赖它）。 */
    private final ConnectionHandle serverConnection = new ConnectionHandle() {};

    private volatile BiConsumer<ConnectionHandle, byte[]> serverReceiver;
    private volatile BiConsumer<ConnectionHandle, byte[]> clientReceiver;

    /** 服务端连接句柄（供断言服务端会话状态）。 */
    public ConnectionHandle clientConnection() {
        return clientConnection;
    }

    /** 服务端侧传输：向客户端发字节、收客户端来的字节。 */
    public TransportPort server() {
        return new TransportPort() {
            @Override
            public void send(ConnectionHandle connection, byte[] data) {
                if (clientReceiver != null) {
                    clientReceiver.accept(serverConnection, data);
                }
            }

            @Override
            public void send(byte[] data) {
                throw new UnsupportedOperationException("服务端侧不支持无连接发送");
            }

            @Override
            public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
                serverReceiver = handler;
            }

            @Override
            public int maxPayloadSize() {
                return 32767;
            }
        };
    }

    /** 客户端侧传输：向服务端发字节、收服务端来的字节。 */
    public TransportPort client() {
        return new TransportPort() {
            @Override
            public void send(ConnectionHandle connection, byte[] data) {
                throw new UnsupportedOperationException("客户端侧只无连接发送");
            }

            @Override
            public void send(byte[] data) {
                if (serverReceiver != null) {
                    serverReceiver.accept(clientConnection, data);
                }
            }

            @Override
            public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
                clientReceiver = handler;
            }

            @Override
            public int maxPayloadSize() {
                return 32767;
            }
        };
    }
}
