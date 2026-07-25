package top.wcpe.mc.mpmt.core.server;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/**
 * HUD 下发服务（L1 core-server，FR-27）：把 title / actionbar / toast / chat 编为
 * {@link ServerHudMessagePacket} 经 {@link PacketDispatcher} 下发给指定连接。
 *
 * <p>平台无关；客户端 L3 收到后按 {@link HudKind} 在渲染线程呈现。以 {@link ConnectionHandle} 定位目标，
 * 按玩家（PlayerRef）下发的便捷重载待与会话注册表整合后再加（FR-28）。
 */
public final class HudMessageService {

    /** 无副标题占位（空串）。 */
    private static final String NO_SUBTITLE = "";
    /** 平台默认时长占位。 */
    private static final long DEFAULT_DURATION = 0L;

    private final PacketDispatcher dispatcher;

    public HudMessageService(PacketDispatcher dispatcher) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
    }

    /** 下发文本类 HUD（actionbar / toast / chat，或不带副标题与时长的 title）。 */
    public void send(ConnectionHandle connection, HudKind kind, String text) {
        send(connection, kind, text, NO_SUBTITLE, DEFAULT_DURATION);
    }

    /** 下发 HUD，逐字段透传。 */
    public void send(
            ConnectionHandle connection, HudKind kind, String text, String subtitle, long durationMillis) {
        Objects.requireNonNull(connection, "connection 不能为空");
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(text, "text 不能为空");
        Objects.requireNonNull(subtitle, "subtitle 不能为空");
        dispatcher.send(connection, new ServerHudMessagePacket(kind, text, subtitle, durationMillis));
    }

    /** 便捷：下发标题（带副标题与时长）。 */
    public void sendTitle(ConnectionHandle connection, String title, String subtitle, long durationMillis) {
        send(connection, HudKind.TITLE, title, subtitle, durationMillis);
    }
}
