package top.wcpe.mc.mpmt.smoke;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;

/**
 * 单人世界（集成服内存回环）传输契约固化（FR-20）：在纯 JVM 下穷举进程内回环 {@link TransportPort} 的裸字节往返语义。
 *
 * <p>单人世界即 Fabric 集成服——客户端与服务端同进程、MC 经内存连接互通，平台产品适配（FabricServerTransport /
 * FabricClientTransport）在此场景由现有内存连接自动覆盖、无需单人专属产品代码。本测试把"同 JVM 回环 TransportPort"
 * 的契约（双向裸字节往返、连接句柄、maxPayloadSize、空 / 大载荷边界）固定下来，作为单人语义的可回归依据。
 */
class InProcessLoopbackTransportTest {

    @Test
    @DisplayName("客户端发 → 服务端收：裸字节按原样往返，携带客户端连接句柄")
    void 客户端到服务端裸字节往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        List<byte[]> received = new ArrayList<>();
        List<ConnectionHandle> conns = new ArrayList<>();
        loop.server().onReceive((conn, data) -> {
            conns.add(conn);
            received.add(data);
        });

        byte[] payload = {1, 2, 3, -7, 0, 127, -128};
        loop.client().send(payload);

        assertEquals(1, received.size());
        assertArrayEquals(payload, received.get(0), "服务端收到的字节应与客户端发的逐字节一致");
        assertSame(loop.clientConnection(), conns.get(0), "服务端收包应携带代表该客户端的连接句柄");
    }

    @Test
    @DisplayName("服务端发 → 客户端收：按连接句柄回投，裸字节一致")
    void 服务端到客户端裸字节往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        List<byte[]> received = new ArrayList<>();
        loop.client().onReceive((conn, data) -> received.add(data));

        byte[] payload = {42, -1, 0, 100};
        loop.server().send(loop.clientConnection(), payload);

        assertEquals(1, received.size());
        assertArrayEquals(payload, received.get(0), "客户端收到的字节应与服务端发的逐字节一致");
    }

    @Test
    @DisplayName("双向多次往返：服务端原样回显，请求 / 响应一一对应")
    void 双向多次往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        // 服务端：收到什么原样回发给来源连接
        loop.server().onReceive((conn, data) -> loop.server().send(conn, data));
        List<byte[]> echoes = new ArrayList<>();
        loop.client().onReceive((conn, data) -> echoes.add(data));

        byte[] a = {1};
        byte[] b = {2, 2};
        byte[] c = {3, 3, 3};
        loop.client().send(a);
        loop.client().send(b);
        loop.client().send(c);

        assertEquals(3, echoes.size());
        assertArrayEquals(a, echoes.get(0));
        assertArrayEquals(b, echoes.get(1));
        assertArrayEquals(c, echoes.get(2));
    }

    @Test
    @DisplayName("空载荷：长度为 0 的字节数组照常往返")
    void 空载荷往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        List<byte[]> received = new ArrayList<>();
        loop.server().onReceive((conn, data) -> received.add(data));

        byte[] empty = new byte[0];
        loop.client().send(empty);

        assertEquals(1, received.size());
        assertEquals(0, received.get(0).length, "空载荷应原样投递、长度为 0");
    }

    @Test
    @DisplayName("载荷不超过 maxPayloadSize：上限大小的字节数组完整往返")
    void 上限大小载荷往返() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        int max = loop.client().maxPayloadSize();
        assertTrue(max > 0, "单包载荷上限应为正数");

        List<byte[]> received = new ArrayList<>();
        loop.server().onReceive((conn, data) -> received.add(data));

        byte[] big = new byte[max];
        for (int i = 0; i < max; i++) {
            big[i] = (byte) i;
        }
        loop.client().send(big);

        assertEquals(1, received.size());
        assertArrayEquals(big, received.get(0), "上限大小载荷应逐字节完整往返");
    }

    @Test
    @DisplayName("未注册收包回调时发送：静默丢弃、不抛异常")
    void 无接收方时静默丢弃() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        // 未注册任何 onReceive 即发送，不应抛异常
        loop.client().send(new byte[] {9});
        loop.server().send(loop.clientConnection(), new byte[] {9});
    }

    @Test
    @DisplayName("服务端侧不支持无连接发送、客户端侧寻址发送回退为无连接发送")
    void 发送方向语义() {
        InProcessLoopbackTransport loop = new InProcessLoopbackTransport();
        TransportPort server = loop.server();
        assertThrows(
                UnsupportedOperationException.class,
                () -> server.send(new byte[] {1}),
                "服务端发送须指定连接");

        // 客户端侧 send(conn, data) 应回退为无连接发送，等价于 send(data)
        List<byte[]> received = new ArrayList<>();
        loop.server().onReceive((conn, data) -> received.add(data));
        loop.client().send(loop.clientConnection(), new byte[] {5, 6});
        assertEquals(1, received.size());
        assertArrayEquals(new byte[] {5, 6}, received.get(0));
    }
}
