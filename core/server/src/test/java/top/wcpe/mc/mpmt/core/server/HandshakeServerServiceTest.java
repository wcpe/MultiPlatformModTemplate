package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.DisconnectPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 握手与会话登记、连接生命周期的回归测试。 */
class HandshakeServerServiceTest {

    private final PacketCodec codec = new PacketCodec();

    @Test
    @DisplayName("握手成功登记服务端下发的会话编号与机器码")
    void 握手成功登记会话() {
        Fixture fixture = new Fixture(new BanRegistry());
        TestConnection connection = new TestConnection(UUID.randomUUID());

        fixture.service.onConnected(connection);
        ServerHelloPacket hello = fixture.hello(connection);
        fixture.receive(connection, new ClientIdReportPacket("machine-a"));

        SessionRegistry.Session session = fixture.sessions.get(connection).orElseThrow(AssertionError::new);
        assertEquals(hello.getSessionId(), session.getSessionId());
        assertEquals(new MachineCode("machine-a"), session.getMachineCode());
        assertEquals(HandshakeStateMachine.State.ESTABLISHED, fixture.service.stateOf(connection));
    }

    @Test
    @DisplayName("封禁机器码拒绝握手且不登记会话")
    void 封禁不登记() {
        BanRegistry bans = new BanRegistry();
        bans.ban(new MachineCode("blocked"), "测试封禁");
        Fixture fixture = new Fixture(bans);
        TestConnection connection = new TestConnection(UUID.randomUUID());

        fixture.service.onConnected(connection);
        fixture.hello(connection);
        fixture.receive(connection, new ClientIdReportPacket("blocked"));

        assertFalse(fixture.sessions.get(connection).isPresent());
        assertEquals(HandshakeStateMachine.State.REJECTED, fixture.service.stateOf(connection));
    }

    @Test
    @DisplayName("封禁服务未 READY 时拒绝标识上报且请求真实断开")
    void 封禁服务未就绪拒绝() {
        Fixture fixture = new Fixture(new BanRegistry(), () -> BanService.State.INITIALIZING);
        TestConnection connection = new TestConnection(UUID.randomUUID());

        fixture.service.onConnected(connection);
        fixture.hello(connection);
        fixture.receive(connection, new ClientIdReportPacket("machine-a"));

        assertFalse(fixture.sessions.get(connection).isPresent());
        assertEquals(HandshakeStateMachine.State.REJECTED, fixture.service.stateOf(connection));
        assertTrue(((ServerMessagePacket) fixture.packetFromEnd(2)).getText().contains("尚未就绪"));
        assertTrue(fixture.packetFromEnd(1) instanceof DisconnectPacket);
        assertEquals(1, fixture.disconnectReasons.size());
        assertTrue(fixture.currentChecks.get(0).getAsBoolean());
    }

    @Test
    @DisplayName("同 UUID 新物理连接重置状态且旧断开事件不清除新会话")
    void 新物理连接不继承旧状态() {
        Fixture fixture = new Fixture(new BanRegistry());
        UUID playerId = UUID.randomUUID();
        TestConnection oldConnection = new TestConnection(playerId);
        establish(fixture, oldConnection, "old-machine");

        TestConnection newConnection = new TestConnection(playerId);
        fixture.service.onConnected(newConnection);
        assertNull(fixture.service.stateOf(oldConnection));
        assertEquals(HandshakeStateMachine.State.CONNECTED, fixture.service.stateOf(newConnection));
        assertFalse(fixture.sessions.get(newConnection).isPresent());

        fixture.receive(oldConnection, new ClientHelloPacket(ProtocolVersion.CURRENT, "stale"));
        assertEquals(HandshakeStateMachine.State.CONNECTED, fixture.service.stateOf(newConnection));

        establish(fixture, newConnection, "new-machine");
        fixture.service.onDisconnected(oldConnection);
        assertTrue(fixture.sessions.get(newConnection).isPresent());

        fixture.service.onDisconnected(newConnection);
        assertFalse(fixture.sessions.get(newConnection).isPresent());
        assertNull(fixture.service.stateOf(newConnection));
    }

    private static void establish(Fixture fixture, TestConnection connection, String machineCode) {
        fixture.service.onConnected(connection);
        fixture.hello(connection);
        fixture.receive(connection, new ClientIdReportPacket(machineCode));
    }

    private final class Fixture {
        private final FakeTransport transport = new FakeTransport();
        private final SessionRegistry sessions = new SessionRegistry();
        private final List<String> disconnectReasons = new ArrayList<>();
        private final List<BooleanSupplier> currentChecks = new ArrayList<>();
        private final HandshakeServerService service;
        private int sequence;

        private Fixture(BanRegistry bans) {
            this(bans, () -> BanService.State.READY);
        }

        private Fixture(BanRegistry bans, Supplier<BanService.State> banState) {
            PacketDispatcher dispatcher = new PacketDispatcher(transport, codec);
            service = new HandshakeServerService(
                    dispatcher,
                    () -> "s-" + ++sequence,
                    bans,
                    sessions,
                    banState,
                    (connection, reason, currentCheck) -> {
                        disconnectReasons.add(reason);
                        currentChecks.add(currentCheck);
                    });
        }

        private ServerHelloPacket hello(ConnectionHandle connection) {
            receive(connection, new ClientHelloPacket(ProtocolVersion.CURRENT, "test"));
            return (ServerHelloPacket) codec.decode(transport.sent.get(transport.sent.size() - 1));
        }

        private void receive(ConnectionHandle connection, Packet packet) {
            transport.receiver.accept(connection, codec.encode(packet));
        }

        private Packet packetFromEnd(int offset) {
            return codec.decode(transport.sent.get(transport.sent.size() - offset));
        }
    }

    private static final class FakeTransport implements TransportPort {
        private final List<byte[]> sent = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sent.add(data);
        }

        @Override
        public void send(byte[] data) {
            throw new UnsupportedOperationException("服务端不支持无连接发送");
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }
    }

    private static final class TestConnection implements ConnectionHandle {
        private final UUID playerId;

        private TestConnection(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TestConnection && playerId.equals(((TestConnection) other).playerId);
        }

        @Override
        public int hashCode() {
            return playerId.hashCode();
        }
    }
}
