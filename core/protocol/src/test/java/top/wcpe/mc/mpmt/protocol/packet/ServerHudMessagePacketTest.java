package top.wcpe.mc.mpmt.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import top.wcpe.mc.mpmt.protocol.ProtocolException;
import top.wcpe.mc.mpmt.protocol.codec.ByteArrayProtocolReader;
import top.wcpe.mc.mpmt.protocol.codec.ByteArrayProtocolWriter;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** HUD 消息包字段级往返与 HudKind 线缆码边界（FR-27）。 */
class ServerHudMessagePacketTest {

    /** 各类型样本：覆盖带副标题/时长、空文本、多字节 UTF、时长边界。 */
    static final class Samples implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    Arguments.of(new ServerHudMessagePacket(HudKind.TITLE, "主标题", "副标题-你好", 5000L)),
                    Arguments.of(new ServerHudMessagePacket(HudKind.ACTIONBAR, "动作栏", "", 0L)),
                    Arguments.of(new ServerHudMessagePacket(HudKind.TOAST, "弹窗", "", 1L)),
                    Arguments.of(new ServerHudMessagePacket(HudKind.CHAT, "", "", Long.MAX_VALUE)));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(Samples.class)
    @DisplayName("字段级往返：encode→decode 逐字段一致")
    void 字段往返(ServerHudMessagePacket packet) {
        ProtocolBufWriter writer = new ByteArrayProtocolWriter();
        packet.encode(writer);
        ServerHudMessagePacket decoded =
                ServerHudMessagePacket.decode(new ByteArrayProtocolReader(writer.toByteArray()));
        assertEquals(packet.getKind(), decoded.getKind());
        assertEquals(packet.getText(), decoded.getText());
        assertEquals(packet.getSubtitle(), decoded.getSubtitle());
        assertEquals(packet.getDurationMillis(), decoded.getDurationMillis());
        assertEquals(packet, decoded);
    }

    @Test
    @DisplayName("HudKind 线缆码稳定且可往返")
    void 线缆码往返() {
        for (HudKind kind : HudKind.values()) {
            assertSame(kind, HudKind.fromWireId(kind.wireId()));
        }
    }

    @Test
    @DisplayName("未知 HudKind 线缆码：抛 ProtocolException")
    void 未知线缆码() {
        assertThrows(ProtocolException.class, () -> HudKind.fromWireId(0));
        assertThrows(ProtocolException.class, () -> HudKind.fromWireId(99));
    }

    @Test
    @DisplayName("解码遇非法 HudKind 码：抛 ProtocolException 而非崩溃")
    void 解码非法类型() {
        // 手工构造：kind 线缆码 = 0（非法）+ 空文本 + 空副标题 + 时长 0
        ProtocolBufWriter writer = new ByteArrayProtocolWriter();
        writer.writeVarInt(0).writeUtf("").writeUtf("").writeLong(0L);
        ByteArrayProtocolReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertThrows(ProtocolException.class, () -> ServerHudMessagePacket.decode(reader));
    }
}
