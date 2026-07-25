package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/** 模拟服专用双向回环传输；支持暂停、乱序、丢弃和篡改单帧，故障状态不会进入产品产物。 */
public final class LoopbackTransport {

    private final UUID playerId = UUID.randomUUID();
    private final Direction serverToClient = new Direction();
    private final Direction clientToServer = new Direction();
    private volatile LoopbackConnection clientConnection = new LoopbackConnection(playerId, 1L);
    private volatile LoopbackConnection serverConnection = new LoopbackConnection(UUID.randomUUID(), 1L);
    private volatile BiConsumer<ConnectionHandle, byte[]> serverReceiver;
    private volatile BiConsumer<ConnectionHandle, byte[]> clientReceiver;
    private volatile int maxPayloadSize = 32767;
    private long generation = 1L;

    /** 当前服务端视角下的客户端物理连接。 */
    public ConnectionHandle clientConnection() {
        return clientConnection;
    }

    /** 创建同 UUID 的新物理连接，供重连代际测试。 */
    public synchronized ConnectionHandle reconnect() {
        generation++;
        clientConnection = new LoopbackConnection(playerId, generation);
        serverConnection = new LoopbackConnection(UUID.randomUUID(), generation);
        return clientConnection;
    }

    /** 调小单包上限以触发产品分片逻辑。 */
    public void maxPayloadSize(int value) {
        if (value <= 32) {
            throw new IllegalArgumentException("maxPayloadSize 必须大于分片头开销");
        }
        maxPayloadSize = value;
    }

    /** 暂停并清空服务端到客户端方向，后续帧由测试显式投递。 */
    public void captureServerFrames() {
        serverToClient.capture();
    }

    /** 暂停并清空客户端到服务端方向，后续帧由测试显式投递。 */
    public void captureClientFrames() {
        clientToServer.capture();
    }

    public List<byte[]> serverFrames() {
        return serverToClient.snapshot();
    }

    public List<byte[]> clientFrames() {
        return clientToServer.snapshot();
    }

    public void deliverServerFrame(int index) {
        deliverServerBytes(serverToClient.frame(index));
    }

    public void deliverServerBytes(byte[] frame) {
        receiver(clientReceiver, "客户端").accept(serverConnection, copy(frame));
    }

    public void deliverClientFrame(int index) {
        receiver(serverReceiver, "服务端").accept(clientConnection, clientToServer.frame(index));
    }

    /** 服务端侧传输：向客户端发字节、收客户端来的字节。 */
    public TransportPort server() {
        return new TransportPort() {
            @Override
            public void send(ConnectionHandle connection, byte[] data) {
                serverToClient.forward(serverConnection, data, clientReceiver);
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
                return maxPayloadSize;
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
                clientToServer.forward(clientConnection, data, serverReceiver);
            }

            @Override
            public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
                clientReceiver = handler;
            }

            @Override
            public int maxPayloadSize() {
                return maxPayloadSize;
            }
        };
    }

    private static BiConsumer<ConnectionHandle, byte[]> receiver(
            BiConsumer<ConnectionHandle, byte[]> target, String endpoint) {
        if (target == null) {
            throw new IllegalStateException(endpoint + "尚未注册收包处理器");
        }
        return target;
    }

    private static byte[] copy(byte[] source) {
        return source.clone();
    }

    /** 单向链路故障控制；仅存在于 gametest 源集。 */
    private static final class Direction {
        private final List<byte[]> frames = new ArrayList<>();
        private boolean capturing;

        private synchronized void capture() {
            frames.clear();
            capturing = true;
        }

        private void forward(
                ConnectionHandle connection,
                byte[] data,
                BiConsumer<ConnectionHandle, byte[]> target) {
            byte[] frame = copy(data);
            synchronized (this) {
                if (capturing) {
                    frames.add(frame);
                    return;
                }
            }
            receiver(target, "对端").accept(connection, frame);
        }

        private synchronized List<byte[]> snapshot() {
            List<byte[]> result = new ArrayList<>();
            for (byte[] frame : frames) {
                result.add(copy(frame));
            }
            return Collections.unmodifiableList(result);
        }

        private synchronized byte[] frame(int index) {
            return copy(frames.get(index));
        }
    }

    /** equals 只表达同玩家，物理连接代际必须由产品代码按对象身份区分。 */
    private static final class LoopbackConnection implements ConnectionHandle {
        private final UUID playerId;
        private final long generation;

        private LoopbackConnection(UUID playerId, long generation) {
            this.playerId = playerId;
            this.generation = generation;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof LoopbackConnection
                    && playerId.equals(((LoopbackConnection) other).playerId);
        }

        @Override
        public int hashCode() {
            return playerId.hashCode();
        }

        @Override
        public String toString() {
            return playerId + "#" + generation;
        }
    }
}
