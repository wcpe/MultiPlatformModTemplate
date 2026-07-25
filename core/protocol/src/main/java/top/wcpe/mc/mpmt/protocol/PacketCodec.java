package top.wcpe.mc.mpmt.protocol;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import top.wcpe.mc.mpmt.protocol.codec.ByteArrayProtocolReader;
import top.wcpe.mc.mpmt.protocol.codec.ByteArrayProtocolWriter;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentRetryRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequiredPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 帧编解码器：在包体外套统一帧头 {@code [protocolVersion:u8][packetId:u8]}（network spec §3.2），
 * 并按 id 注册表分发解码（用注册表而非 switch，避免散落分支，符合反模式禁令）。
 *
 * <p>线程安全：注册在构造期一次性完成、之后只读，可被多线程并发 {@link #encode} / {@link #decode}。
 */
public final class PacketCodec {

    /** 包解码器：从缓冲读出一个包体。 */
    @FunctionalInterface
    public interface PacketDecoder {
        Packet decode(ProtocolBufReader buf);
    }

    private final Map<Integer, PacketDecoder> decoders = new HashMap<>();

    public PacketCodec() {
        register(PacketIds.CLIENT_HELLO, ClientHelloPacket::decode);
        register(PacketIds.SERVER_HELLO, ServerHelloPacket::decode);
        register(PacketIds.SERVER_MESSAGE, ServerMessagePacket::decode);
        register(PacketIds.SERVER_HUD_MESSAGE, ServerHudMessagePacket::decode);
        register(PacketIds.DISCONNECT, DisconnectPacket::decode);
        register(PacketIds.CLIENT_ID_REPORT, ClientIdReportPacket::decode);
        register(PacketIds.PING, PingPacket::decode);
        register(PacketIds.PONG, PongPacket::decode);
        register(PacketIds.RESYNC_REQUEST, ResyncRequestPacket::decode);
        register(PacketIds.RESYNC_REQUIRED, ResyncRequiredPacket::decode);
        register(PacketIds.FRAGMENT, FragmentPacket::decode);
        register(PacketIds.FRAGMENT_RETRY_REQUEST, FragmentRetryRequestPacket::decode);
    }

    private void register(int id, PacketDecoder decoder) {
        decoders.put(id, decoder);
    }

    /** 编码：写帧头（当前协议版本 + 包 id）再写包体。 */
    public byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet 不能为空");
        ProtocolBufWriter buf = new ByteArrayProtocolWriter();
        buf.writeByte(ProtocolVersion.CURRENT);
        buf.writeByte(packet.id());
        packet.encode(buf);
        return buf.toByteArray();
    }

    /** 解码：读帧头取 id 分发到对应解码器；未知 id 抛 {@link ProtocolException}。 */
    public Packet decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes 不能为空");
        ProtocolBufReader buf = new ByteArrayProtocolReader(bytes);
        // 帧协议版本：读出以推进读指针；帧级兼容性处理留待后续，应用层版本协商走 ClientHello
        buf.readUnsignedByte();
        int id = buf.readUnsignedByte();
        PacketDecoder decoder = decoders.get(id);
        if (decoder == null) {
            throw new ProtocolException("未知包 id：0x" + Integer.toHexString(id));
        }
        return decoder.decode(buf);
    }
}
