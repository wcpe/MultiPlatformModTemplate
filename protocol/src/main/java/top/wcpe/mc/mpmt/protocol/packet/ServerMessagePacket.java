package top.wcpe.mc.mpmt.protocol.packet;

import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/** S2C 服务端消息：握手欢迎 / 提示 / 封禁告知等文本。 */
@Value
public class ServerMessagePacket implements Packet {

    /** 文本内容。 */
    String text;

    @Override
    public int id() {
        return PacketIds.SERVER_MESSAGE;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeUtf(text);
    }

    /** 与 {@link #encode} 对称的解码。 */
    public static ServerMessagePacket decode(ProtocolBufReader buf) {
        return new ServerMessagePacket(buf.readUtf());
    }
}
