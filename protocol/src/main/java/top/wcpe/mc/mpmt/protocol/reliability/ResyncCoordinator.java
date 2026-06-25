package top.wcpe.mc.mpmt.protocol.reliability;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;

/**
 * 重同步协调（可靠性层，L1 平台无关，FR-24 / network spec §3.3）：把"连接重建 / 心跳超时后请求重发权威状态"
 * 的收发管线收敛到一处。
 *
 * <p><b>客户端</b>经 {@link #requestResync(long)} 发 {@link ResyncRequestPacket}；<b>服务端</b>构造时注入
 * {@link ResyncRequestHandler}，收到请求即据修订号重发权威状态（重发内容由应用域决定，本类只管收发）。
 * 客户端不收 C2S 请求、其处理器不会被触发，可传 no-op。线程安全依赖底层 {@link PacketDispatcher}。
 */
public final class ResyncCoordinator {

    /** 服务端收到重同步请求的处理器：据连接与修订号重发权威状态。 */
    @FunctionalInterface
    public interface ResyncRequestHandler {
        void onResyncRequest(ConnectionHandle connection, long sinceRevision);
    }

    private final PacketDispatcher dispatcher;

    public ResyncCoordinator(PacketDispatcher dispatcher, ResyncRequestHandler handler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        Objects.requireNonNull(handler, "handler 不能为空");
        dispatcher.on(
                PacketIds.RESYNC_REQUEST,
                (connection, packet) ->
                        handler.onResyncRequest(connection, ((ResyncRequestPacket) packet).getSinceRevision()));
    }

    /** 客户端：请求重同步（重发自 {@code sinceRevision} 起的权威状态）。 */
    public void requestResync(long sinceRevision) {
        dispatcher.send(new ResyncRequestPacket(sinceRevision));
    }
}
