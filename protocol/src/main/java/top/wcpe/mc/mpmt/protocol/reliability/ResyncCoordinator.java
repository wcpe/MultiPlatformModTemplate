package top.wcpe.mc.mpmt.protocol.reliability;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequestPacket;
import top.wcpe.mc.mpmt.protocol.packet.ResyncRequiredPacket;

/**
 * 显式区分客户端与服务端角色的重同步协调器。
 *
 * <p>服务端只处理 C2S {@link ResyncRequestPacket} 并可下发 {@link ResyncRequiredPacket}；客户端只处理
 * S2C {@link ResyncRequiredPacket} 并发送 {@link ResyncRequestPacket}。错误方向的包不会注册处理器。
 */
public final class ResyncCoordinator {

    /** 服务端收到重同步请求后的处理器。 */
    @FunctionalInterface
    public interface ResyncRequestHandler {
        void onResyncRequest(ConnectionHandle connection, long sinceRevision);
    }

    private enum Role {
        SERVER,
        CLIENT
    }

    private final PacketDispatcher dispatcher;
    private final Role role;

    /**
     * 兼容旧 API：该构造器安全地创建服务端角色，不注册客户端方向处理器。
     */
    public ResyncCoordinator(PacketDispatcher dispatcher, ResyncRequestHandler handler) {
        this(dispatcher, Role.SERVER, Objects.requireNonNull(handler, "handler 不能为空"));
    }

    private ResyncCoordinator(
            PacketDispatcher dispatcher, Role role, ResyncRequestHandler handler) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.role = role;
        if (role == Role.SERVER) {
            registerServerHandler(handler);
        } else {
            registerClientHandler();
        }
    }

    /** 创建只处理服务端方向的协调器。 */
    public static ResyncCoordinator forServer(
            PacketDispatcher dispatcher, ResyncRequestHandler handler) {
        return new ResyncCoordinator(
                dispatcher, Role.SERVER, Objects.requireNonNull(handler, "handler 不能为空"));
    }

    /** 创建只处理客户端方向的协调器。 */
    public static ResyncCoordinator forClient(PacketDispatcher dispatcher) {
        return new ResyncCoordinator(dispatcher, Role.CLIENT, null);
    }

    /** 服务端：要求指定连接发起重同步。 */
    public void requireResync(ConnectionHandle connection, long authoritativeRevision) {
        requireRole(Role.SERVER);
        dispatcher.send(
                Objects.requireNonNull(connection, "connection 不能为空"),
                new ResyncRequiredPacket(authoritativeRevision));
    }

    /** 客户端：请求重发自指定修订起的权威状态。 */
    public void requestResync(long sinceRevision) {
        requireRole(Role.CLIENT);
        dispatcher.send(new ResyncRequestPacket(sinceRevision));
    }

    private void registerServerHandler(ResyncRequestHandler handler) {
        dispatcher.on(
                PacketIds.RESYNC_REQUEST,
                (connection, packet) ->
                        handler.onResyncRequest(
                                connection, ((ResyncRequestPacket) packet).getSinceRevision()));
    }

    private void registerClientHandler() {
        dispatcher.on(
                PacketIds.RESYNC_REQUIRED,
                (connection, packet) ->
                        requestResync(((ResyncRequiredPacket) packet).getAuthoritativeRevision()));
    }

    private void requireRole(Role required) {
        if (role != required) {
            throw new IllegalStateException("重同步协调器角色不允许此操作：" + role);
        }
    }
}
