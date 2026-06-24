package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** HUD 下发服务（FR-27）：经 dispatcher 发出正确的 ServerHudMessagePacket。 */
class HudMessageServiceTest {

    private final CapturingTransport transport = new CapturingTransport();
    private final HudMessageService service =
            new HudMessageService(new PacketDispatcher(transport, new PacketCodec()));

    private static ConnectionHandle conn() {
        return new ConnectionHandle() {
        };
    }

    private ServerHudMessagePacket lastSent() {
        return (ServerHudMessagePacket) new PacketCodec().decode(transport.lastBytes);
    }

    @Test
    @DisplayName("通用文本类下发：副标题空、时长默认 0")
    void 通用文本下发() {
        ConnectionHandle c = conn();
        service.send(c, HudKind.ACTIONBAR, "动作栏");

        assertSame(c, transport.lastConn);
        ServerHudMessagePacket sent = lastSent();
        assertEquals(HudKind.ACTIONBAR, sent.getKind());
        assertEquals("动作栏", sent.getText());
        assertEquals("", sent.getSubtitle());
        assertEquals(0L, sent.getDurationMillis());
    }

    @Test
    @DisplayName("标题下发：保留副标题与时长")
    void 标题下发() {
        service.sendTitle(conn(), "主标题", "副标题", 2000L);

        ServerHudMessagePacket sent = lastSent();
        assertEquals(HudKind.TITLE, sent.getKind());
        assertEquals("主标题", sent.getText());
        assertEquals("副标题", sent.getSubtitle());
        assertEquals(2000L, sent.getDurationMillis());
    }

    @Test
    @DisplayName("五参下发：逐字段透传")
    void 五参下发() {
        service.send(conn(), HudKind.TOAST, "弹窗", "", 100L);

        ServerHudMessagePacket sent = lastSent();
        assertEquals(HudKind.TOAST, sent.getKind());
        assertEquals("弹窗", sent.getText());
        assertEquals(100L, sent.getDurationMillis());
    }

    @Test
    @DisplayName("构造 / 下发入参为空即拒")
    void 入参校验() {
        assertThrows(NullPointerException.class, () -> new HudMessageService(null));
        assertThrows(NullPointerException.class, () -> service.send(null, HudKind.CHAT, "x"));
        assertThrows(NullPointerException.class, () -> service.send(conn(), null, "x"));
        assertThrows(NullPointerException.class, () -> service.send(conn(), HudKind.CHAT, null));
        assertThrows(
                NullPointerException.class, () -> service.send(conn(), HudKind.CHAT, "x", null, 0L));
    }

    /** 捕获服务端发出的字节（只验证发送，忽略接收注册）。 */
    private static final class CapturingTransport implements TransportPort {
        ConnectionHandle lastConn;
        byte[] lastBytes;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            this.lastConn = connection;
            this.lastBytes = data;
        }

        @Override
        public void send(byte[] data) {
            this.lastBytes = data;
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            // 本测试只验证发送方向，无需接收
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }
    }
}
