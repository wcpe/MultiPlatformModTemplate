package top.wcpe.mc.mpmt.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 协议包帧编解码往返一致与非法输入处理（FR-04 验收）。 */
class PacketCodecTest {

    /** 各类包的代表样本（含多字节 UTF、空串、long 边界）。 */
    static List<Packet> samplePackets() {
        return Arrays.asList(
                new ClientHelloPacket(ProtocolVersion.CURRENT, "1.0.0"),
                new ClientHelloPacket(ProtocolVersion.CURRENT, "你好-mpmt"),
                new ServerHelloPacket(ProtocolVersion.CURRENT, "session-abc", true),
                new ServerHelloPacket(ProtocolVersion.CURRENT, "", false),
                new PingPacket(0L),
                new PingPacket(Long.MIN_VALUE),
                new PingPacket(Long.MAX_VALUE),
                new PongPacket(-1L),
                new ClientIdReportPacket("deadbeef-客户端标识"),
                new ServerMessagePacket("欢迎来到 MPMT"),
                new DisconnectPacket("已被封禁"),
                new FragmentPacket(7, 1, 3, 0x1234ABCD, new byte[] {1, 2, 3, -1, 127}));
    }

    @ParameterizedTest
    @MethodSource("samplePackets")
    @DisplayName("帧编解码往返一致：decode(encode(p)) 等于 p")
    void 包编解码往返一致(Packet packet) {
        PacketCodec codec = new PacketCodec();
        byte[] bytes = codec.encode(packet);
        Packet decoded = codec.decode(bytes);
        assertEquals(packet, decoded);
    }

    @ParameterizedTest
    @MethodSource("samplePackets")
    @DisplayName("字节稳定：两次编码字节完全一致")
    void 两次编码字节一致(Packet packet) {
        PacketCodec codec = new PacketCodec();
        assertArrayEquals(codec.encode(packet), codec.encode(packet));
    }

    @Test
    @DisplayName("帧头：首两字节为当前协议版本与包 id")
    void 帧头版本与id正确() {
        byte[] bytes = new PacketCodec().encode(new PingPacket(7L));
        assertEquals(ProtocolVersion.CURRENT, bytes[0] & 0xFF);
        assertEquals(PacketIds.PING, bytes[1] & 0xFF);
    }

    @Test
    @DisplayName("未知包 id：抛 ProtocolException")
    void 未知id_抛异常() {
        byte[] unknown = new byte[] {(byte) ProtocolVersion.CURRENT, (byte) 0x7E};
        assertThrows(ProtocolException.class, () -> new PacketCodec().decode(unknown));
    }

    @Test
    @DisplayName("截断字节：抛 ProtocolException 而非崩溃")
    void 截断字节_抛异常() {
        PacketCodec codec = new PacketCodec();
        byte[] full = codec.encode(new PingPacket(123L));
        byte[] truncated = Arrays.copyOf(full, full.length - 1);
        assertThrows(ProtocolException.class, () -> codec.decode(truncated));
    }

    @Test
    @DisplayName("空字节流：抛 ProtocolException 而非崩溃")
    void 空字节_抛异常() {
        assertThrows(ProtocolException.class, () -> new PacketCodec().decode(new byte[0]));
    }
}
