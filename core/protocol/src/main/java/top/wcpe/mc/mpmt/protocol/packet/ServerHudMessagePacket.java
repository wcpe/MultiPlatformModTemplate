package top.wcpe.mc.mpmt.protocol.packet;

import lombok.NonNull;
import lombok.Value;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufReader;
import top.wcpe.mc.mpmt.protocol.codec.ProtocolBufWriter;

/**
 * S2C 跨端 HUD 消息（FR-27）：服务端向客户端下发 title / actionbar / toast / chat。
 *
 * <p>字节布局（单一真源，ADR-0006）：{@code kind 线缆码(VarInt) + text(UTF) + subtitle(UTF) + durationMillis(long)}。
 * 客户端按 {@link #kind} 选择渲染方式；渲染在客户端渲染线程读不可变快照（ADR-0013）。
 */
@Value
public class ServerHudMessagePacket implements Packet {

    /** 呈现类型。 */
    @NonNull
    HudKind kind;

    /** 主文本（CHAT/ACTIONBAR/TOAST 的正文，或 TITLE 的主标题）。 */
    @NonNull
    String text;

    /** 副标题（仅 TITLE 有意义；空串表示无）。 */
    @NonNull
    String subtitle;

    /** 显示时长（毫秒；0 表示平台默认）。 */
    long durationMillis;

    @Override
    public int id() {
        return PacketIds.SERVER_HUD_MESSAGE;
    }

    @Override
    public void encode(ProtocolBufWriter buf) {
        buf.writeVarInt(kind.wireId());
        buf.writeUtf(text);
        buf.writeUtf(subtitle);
        buf.writeLong(durationMillis);
    }

    /** 与 {@link #encode} 对称的解码（非法 kind 线缆码由 {@link HudKind#fromWireId} 拒绝）。 */
    public static ServerHudMessagePacket decode(ProtocolBufReader buf) {
        HudKind kind = HudKind.fromWireId(buf.readVarInt());
        String text = buf.readUtf();
        String subtitle = buf.readUtf();
        long durationMillis = buf.readLong();
        return new ServerHudMessagePacket(kind, text, subtitle, durationMillis);
    }
}
