package top.wcpe.mc.mpmt.core.client;

import java.util.Objects;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;

/**
 * 客户端握手服务（平台无关）：进服后发 ClientHello，处理 ServerHello 记录协商结果。
 *
 * <p>握手结果（是否接受 / 会话 id）以 volatile 暴露：网络线程写、其它线程读快照（ADR-0013）。
 */
public final class HandshakeClientService {

    private final PacketDispatcher dispatcher;
    private final String modVersion;
    private volatile boolean accepted;
    private volatile String sessionId;

    public HandshakeClientService(PacketDispatcher dispatcher, String modVersion) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher 不能为空");
        this.modVersion = Objects.requireNonNull(modVersion, "modVersion 不能为空");
        dispatcher.on(PacketIds.SERVER_HELLO, this::onServerHello);
    }

    /** 发起握手：发送 ClientHello（本端当前协议版本 + mod 版本）。 */
    public void startHandshake() {
        dispatcher.send(new ClientHelloPacket(ProtocolVersion.CURRENT, modVersion));
    }

    private void onServerHello(ConnectionHandle connection, Packet packet) {
        ServerHelloPacket hello = (ServerHelloPacket) packet;
        this.sessionId = hello.getSessionId();
        this.accepted = hello.isAccepted();
    }

    /** 服务端是否接受了握手（版本兼容）。 */
    public boolean isAccepted() {
        return accepted;
    }

    /** 服务端分配的会话 id（未接受时为空串、未握手为 null）。 */
    public String sessionId() {
        return sessionId;
    }
}
