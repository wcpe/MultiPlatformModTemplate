package top.wcpe.mc.mpmt.core.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.MachineCodeProvider;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * 客户端握手服务（平台无关）：发 ClientHello → 收 ServerHello（被接受则上报弱客户端标识）→ 接收服务端消息 / 断开通知。
 *
 * <p>结果以 volatile 暴露：网络线程写、其它线程读快照（ADR-0013）。弱标识由可插拔 {@link MachineCodeProvider} 提供。
 */
public final class HandshakeClientService {

    private final PacketDispatcher dispatcher;
    private final String modVersion;
    private final MachineCodeProvider machineCodeProvider;

    private volatile boolean accepted;
    private volatile String sessionId;
    private volatile String lastServerMessage;
    private volatile boolean disconnected;
    private volatile String disconnectReason;

    public HandshakeClientService(PacketDispatcher dispatcher, String modVersion, MachineCodeProvider machineCodeProvider) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        this.machineCodeProvider = Objects.requireNonNull(machineCodeProvider, "machineCodeProvider 不能为空");
        dispatcher.on(PacketIds.SERVER_HELLO, this::onServerHello);
        dispatcher.on(PacketIds.SERVER_MESSAGE, this::onServerMessage);
        dispatcher.on(PacketIds.DISCONNECT, this::onDisconnect);
    }

    /** 发起握手：发送 ClientHello。 */
    public void startHandshake() {
        dispatcher.send(new ClientHelloPacket(ProtocolVersion.CURRENT, modVersion));
    }

    private void onServerHello(ConnectionHandle connection, Packet packet) {
        ServerHelloPacket hello = (ServerHelloPacket) packet;
        this.sessionId = hello.getSessionId();
        this.accepted = hello.isAccepted();
        if (hello.isAccepted()) {
            // 被接受 → 上报弱客户端标识
            dispatcher.send(new ClientIdReportPacket(machineCodeProvider.get()));
        }
    }

    private void onServerMessage(ConnectionHandle connection, Packet packet) {
        this.lastServerMessage = ((ServerMessagePacket) packet).getText();
    }

    private void onDisconnect(ConnectionHandle connection, Packet packet) {
        this.disconnected = true;
        this.disconnectReason = ((DisconnectPacket) packet).getReason();
    }

    /** 服务端是否接受了握手（版本兼容）。 */
    public boolean isAccepted() {
        return accepted;
    }

    /** 服务端分配的会话 id。 */
    public String sessionId() {
        return sessionId;
    }

    /** 最近一次收到的服务端消息。 */
    public String lastServerMessage() {
        return lastServerMessage;
    }

    /** 是否已收到断开通知。 */
    public boolean isDisconnected() {
        return disconnected;
    }

    /** 断开原因（未断开为 null）。 */
    public String disconnectReason() {
        return disconnectReason;
    }
}
