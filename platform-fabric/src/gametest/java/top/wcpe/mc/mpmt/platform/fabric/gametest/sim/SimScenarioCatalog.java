package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.core.client.ClientNetworkFeature;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.runtime.MpmtRuntime;
import top.wcpe.mc.mpmt.core.server.HandshakeServerService;
import top.wcpe.mc.mpmt.core.server.HeartbeatService;
import top.wcpe.mc.mpmt.core.server.HudMessageService;
import top.wcpe.mc.mpmt.core.server.ServerNetworkFeature;
import top.wcpe.mc.mpmt.core.server.SessionRegistry;
import top.wcpe.mc.mpmt.domain.capability.PlatformCapabilityExample;
import top.wcpe.mc.mpmt.domain.capability.PlayerJoinedEvent;
import top.wcpe.mc.mpmt.domain.capability.PlayerLeftEvent;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.ProtocolVersion;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** Fabric 模拟服 P1 场景目录；顺序与 acceptance v2 元数据声明保持一致。 */
final class SimScenarioCatalog {

    private SimScenarioCatalog() {
        // 工具类不实例化
    }

    static List<ServerGameTest> all() {
        return Arrays.asList(
                test(P1ScenarioMatrix.HANDSHAKE_SUCCESS, SimScenarioCatalog::handshakeSuccess),
                test(P1ScenarioMatrix.HANDSHAKE_INCOMPATIBLE, SimScenarioCatalog::handshakeIncompatible),
                test(P1ScenarioMatrix.MACHINE_CODE_SESSION, SimScenarioCatalog::machineCodeSession),
                test(P1ScenarioMatrix.BAN_RECONNECT, SimScenarioCatalog::banReconnect),
                test(P1ScenarioMatrix.UNBAN_RECONNECT, SimScenarioCatalog::unbanReconnect),
                test(P1ScenarioMatrix.FRAGMENT_CRC, SimScenarioCatalog::fragmentCrc),
                test(P1ScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC, SimScenarioCatalog::fragmentTimeoutRetryResync),
                test(P1ScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT, SimScenarioCatalog::sessionHeartbeatRttTimeout),
                test(P1ScenarioMatrix.CAPABILITY_EVENT_BUS, SimScenarioCatalog::capabilityEventBus),
                hud(P1ScenarioMatrix.HUD_TITLE, HudKind.TITLE, "sim-title-token"),
                hud(P1ScenarioMatrix.HUD_ACTIONBAR, HudKind.ACTIONBAR, "sim-actionbar-token"),
                hud(P1ScenarioMatrix.HUD_TOAST, HudKind.TOAST, "sim-toast-token"),
                hud(P1ScenarioMatrix.HUD_CHAT, HudKind.CHAT, "sim-chat-token"),
                test(P1ScenarioMatrix.INTEGRATED_LOOPBACK, SimScenarioCatalog::integratedLoopback));
    }

    static List<String> scenarioIds() {
        List<String> ids = new ArrayList<>();
        for (ServerGameTest test : all()) {
            ids.add(test.suite() + '/' + test.id());
        }
        return Collections.unmodifiableList(ids);
    }

    private static ServerGameTest test(String scenario, SimTestSupport.ScenarioAction action) {
        return SimTestSupport.test(id(scenario), action);
    }

    private static String id(String scenario) {
        return scenario.substring(scenario.indexOf('/') + 1);
    }

    private static void handshakeSuccess(ServerGameTestContext context) {
        SimTestSupport.HandshakeFixture fixture = new SimTestSupport.HandshakeFixture();
        fixture.start();
        context.assertTrue(fixture.client.isAccepted(), "兼容握手应成功");
        context.assertTrue(!fixture.client.isDisconnected(), "兼容握手不应断开");
        context.assertEquals(
                HandshakeStateMachine.State.ESTABLISHED,
                fixture.server.stateOf(fixture.loop.clientConnection()),
                "服务端握手状态应建立");
    }

    private static void handshakeIncompatible(ServerGameTestContext context) {
        LoopbackTransport loop = new LoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);
        HandshakeServerService server = new HandshakeServerService(
                serverDispatcher, () -> "unused", new top.wcpe.mc.mpmt.core.domain.ban.BanRegistry());
        List<ServerHelloPacket> replies = new ArrayList<>();
        clientDispatcher.on(PacketIds.SERVER_HELLO, (connection, packet) -> replies.add((ServerHelloPacket) packet));
        server.onConnected(loop.clientConnection());

        clientDispatcher.send(new ClientHelloPacket(ProtocolVersion.CURRENT + 1, "future"));

        context.assertEquals(1, replies.size(), "不兼容握手应返回一次协商结果");
        context.assertTrue(!replies.get(0).isAccepted(), "过新协议必须明确拒绝");
        context.assertEquals(
                HandshakeStateMachine.State.REJECTED,
                server.stateOf(loop.clientConnection()),
                "服务端状态应为拒绝");
    }

    private static void machineCodeSession(ServerGameTestContext context) {
        SimTestSupport.HandshakeFixture fixture = new SimTestSupport.HandshakeFixture();
        fixture.start();
        SessionRegistry.Session session = fixture.sessions.get(fixture.loop.clientConnection()).orElse(null);
        context.assertTrue(session != null, "机器码上报后应登记会话");
        context.assertEquals(
                new MachineCode(SimTestSupport.MACHINE_CODE),
                session == null ? null : session.getMachineCode(),
                "会话应保存上报机器码");
        context.assertEquals(fixture.client.sessionId(), session == null ? null : session.getSessionId(), "会话编号应一致");
    }

    private static void banReconnect(ServerGameTestContext context) {
        SimTestSupport.HandshakeFixture fixture = new SimTestSupport.HandshakeFixture();
        fixture.start();
        fixture.bans.ban(new MachineCode(SimTestSupport.MACHINE_CODE), "模拟封禁");
        fixture.reconnect();
        fixture.start();

        context.assertTrue(fixture.client.isDisconnected(), "被封机器码重连应收到断开通知");
        context.assertEquals(1, fixture.disconnects.get(), "被封重连应请求真实断开一次");
        context.assertEquals(0, fixture.sessions.onlineCount(), "拒绝后不得保留在线会话");
    }

    private static void unbanReconnect(ServerGameTestContext context) {
        SimTestSupport.HandshakeFixture fixture = new SimTestSupport.HandshakeFixture();
        MachineCode code = new MachineCode(SimTestSupport.MACHINE_CODE);
        fixture.bans.ban(code, "模拟封禁");
        fixture.bans.unban(code);
        fixture.reconnect();
        fixture.start();

        context.assertTrue(fixture.client.isAccepted(), "解封后重连应恢复握手");
        context.assertTrue(!fixture.client.isDisconnected(), "解封后不应断开");
        context.assertEquals(1, fixture.sessions.onlineCount(), "解封后应重新登记会话");
    }

    private static void fragmentCrc(ServerGameTestContext context) {
        LoopbackTransport loop = fragmentLoop();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher sender = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher receiver = new PacketDispatcher(loop.client(), codec);
        List<ServerMessagePacket> received = new ArrayList<>();
        receiver.on(PacketIds.SERVER_MESSAGE, (connection, packet) -> received.add((ServerMessagePacket) packet));

        loop.captureServerFrames();
        ServerMessagePacket reordered = new ServerMessagePacket(SimTestSupport.largeText("reorder"));
        sender.send(loop.clientConnection(), reordered);
        deliverReverse(loop);
        context.assertEquals(Collections.singletonList(reordered), received, "乱序分片应按序重组");

        loop.captureServerFrames();
        sender.send(loop.clientConnection(), new ServerMessagePacket(SimTestSupport.largeText("crc")));
        deliverCorrupted(loop, codec);
        context.assertEquals(1, received.size(), "CRC 不一致的完整分组必须丢弃");
    }

    private static void fragmentTimeoutRetryResync(ServerGameTestContext context) {
        LoopbackTransport loop = fragmentLoop();
        PacketCodec codec = new PacketCodec();
        AtomicLong clock = new AtomicLong();
        List<Integer> resyncs = new ArrayList<>();
        PacketDispatcher sender = dispatcher(loop.server(), codec, clock, new ArrayList<>());
        PacketDispatcher receiver = dispatcher(loop.client(), codec, clock, resyncs);
        receiver.send(new top.wcpe.mc.mpmt.protocol.packet.PongPacket(-1L));
        loop.captureServerFrames();

        sender.send(loop.clientConnection(), new ServerMessagePacket(SimTestSupport.largeText("timeout")));
        int originalCount = loop.serverFrames().size();
        loop.deliverServerFrame(0);
        clock.set(11L);
        receiver.tickReliability();
        context.assertEquals(originalCount * 2, loop.serverFrames().size(), "首次超时应请求并重发整组");
        assertRetriedGroup(context, loop.serverFrames(), originalCount);

        loop.deliverServerFrame(originalCount);
        clock.set(22L);
        receiver.tickReliability();
        context.assertEquals(1, resyncs.size(), "第二次超时应升级重同步");
    }

    private static void sessionHeartbeatRttTimeout(ServerGameTestContext context) {
        LoopbackTransport loop = new LoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);
        top.wcpe.mc.mpmt.core.client.HeartbeatService clientHeartbeat =
                new top.wcpe.mc.mpmt.core.client.HeartbeatService(clientDispatcher);
        SessionRegistry sessions = new SessionRegistry();
        sessions.register(loop.clientConnection(), "heartbeat-session", new MachineCode(SimTestSupport.MACHINE_CODE));
        SimTestSupport.ManualScheduler scheduler = new SimTestSupport.ManualScheduler();
        SimTestSupport.RecordingConnections connections = new SimTestSupport.RecordingConnections();
        SimTestSupport.Clock clock = new SimTestSupport.Clock();
        HeartbeatService serverHeartbeat = SimTestSupport.heartbeat(
                sessions, serverDispatcher, scheduler, connections, clock.now::get, 10L, 10L);

        loop.captureClientFrames();
        scheduler.tick();
        clock.set(7L);
        loop.deliverClientFrame(0);
        context.assertEquals(7L, sessions.get(loop.clientConnection()).get().getRttMillis(), "Pong 应更新 RTT");

        clientHeartbeat.close();
        scheduler.tick();
        clock.set(18L);
        scheduler.tick();
        context.assertEquals(
                SessionRegistry.State.RESYNC_REQUIRED,
                sessions.get(loop.clientConnection()).get().getState(),
                "首次超时应进入重同步宽限");
        clock.set(29L);
        scheduler.tick();
        scheduler.runEntityTasks();
        context.assertEquals(1, connections.disconnectReasons.size(), "宽限再次超时应断开一次");
        serverHeartbeat.close();
    }

    private static void capabilityEventBus(ServerGameTestContext context) {
        SimpleEventBus eventBus = new SimpleEventBus();
        SimTestSupport.MemoryPersistence persistence = new SimTestSupport.MemoryPersistence();
        SimTestSupport.RecordingMessage messages = new SimTestSupport.RecordingMessage();
        SimTestSupport.ManualScheduler scheduler = new SimTestSupport.ManualScheduler();
        PlatformCapabilityExample example = new PlatformCapabilityExample(persistence, messages, scheduler, () -> 123L);
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "模拟玩家");
        example.register(eventBus);

        eventBus.publish(new PlayerJoinedEvent(player));
        scheduler.runEntityTasks();
        context.assertEquals(1, persistence.values.size(), "EventBus 加入事件应触发持久化");
        context.assertEquals(1, messages.messages.size(), "加入事件应按归属发送欢迎消息");
        context.assertEquals(1, scheduler.handles.size(), "加入事件应登记 capability 心跳");

        eventBus.publish(new PlayerLeftEvent(player));
        context.assertTrue(scheduler.handles.get(0).closed, "离开事件应释放 capability 心跳句柄");
    }

    private static ServerGameTest hud(String scenario, HudKind kind, String token) {
        return test(scenario, context -> verifyHud(context, kind, token));
    }

    private static void verifyHud(ServerGameTestContext context, HudKind kind, String token) {
        LoopbackTransport loop = new LoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher server = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher client = new PacketDispatcher(loop.client(), codec);
        HudMessageService hud = new HudMessageService(server);
        List<ServerHudMessagePacket> received = new ArrayList<>();
        client.on(PacketIds.SERVER_HUD_MESSAGE, (connection, packet) -> received.add((ServerHudMessagePacket) packet));

        if (kind == HudKind.TITLE) {
            hud.sendTitle(loop.clientConnection(), token, token + "-subtitle", 1500L);
        } else {
            hud.send(loop.clientConnection(), kind, token);
        }

        context.assertEquals(1, received.size(), kind + " 应恰好收到一次");
        context.assertEquals(kind, received.get(0).getKind(), "HUD 类型必须保持");
        context.assertEquals(token, received.get(0).getText(), "HUD token 必须保持且不得串类");
    }

    private static void integratedLoopback(ServerGameTestContext context) {
        LoopbackTransport loop = new LoopbackTransport();
        SimTestSupport.ManualScheduler scheduler = new SimTestSupport.ManualScheduler();
        SimTestSupport.RecordingConnections connections = new SimTestSupport.RecordingConnections();
        SessionRegistry sessions = new SessionRegistry();
        ServerNetworkFeature serverFeature = new ServerNetworkFeature(
                new top.wcpe.mc.mpmt.core.domain.ban.BanRegistry(), () -> "integrated-session", sessions);
        ClientNetworkFeature clientFeature = new ClientNetworkFeature("sim-1.0", () -> SimTestSupport.MACHINE_CODE);
        MpmtRuntime serverRuntime = runtime(loop.server(), scheduler, connections);
        MpmtRuntime clientRuntime = new MpmtRuntime();
        clientRuntime.ports().register(TransportPort.class, loop.client());
        serverRuntime.features().register(serverFeature);
        clientRuntime.features().register(clientFeature);
        serverRuntime.enable();
        clientRuntime.enable();

        serverFeature.onConnected(loop.clientConnection());
        clientFeature.startHandshake();
        List<ServerHudMessagePacket> hud = new ArrayList<>();
        clientFeature.dispatcher().on(
                PacketIds.SERVER_HUD_MESSAGE, (connection, packet) -> hud.add((ServerHudMessagePacket) packet));
        serverFeature.hudMessageService().send(loop.clientConnection(), HudKind.CHAT, "integrated-token");

        context.assertTrue(clientFeature.handshakeClient().isAccepted(), "集成回环应跑通完整握手装配");
        context.assertEquals(1, sessions.onlineCount(), "集成回环应登记共享会话");
        context.assertEquals("integrated-token", hud.get(0).getText(), "集成回环应跑通产品 HUD 收发");
        clientRuntime.disable();
        serverRuntime.disable();
    }

    private static MpmtRuntime runtime(
            TransportPort transport, SchedulerPort scheduler, ConnectionControlPort connections) {
        MpmtRuntime runtime = new MpmtRuntime();
        runtime.ports().register(TransportPort.class, transport);
        runtime.ports().register(SchedulerPort.class, scheduler);
        runtime.ports().register(ConnectionControlPort.class, connections);
        return runtime;
    }

    private static LoopbackTransport fragmentLoop() {
        LoopbackTransport loop = new LoopbackTransport();
        loop.maxPayloadSize(64);
        return loop;
    }

    private static PacketDispatcher dispatcher(
            TransportPort transport, PacketCodec codec, AtomicLong clock, List<Integer> resyncs) {
        return new PacketDispatcher(
                transport,
                codec,
                10L,
                clock::get,
                (connection, seqId) -> resyncs.add(seqId));
    }

    private static void deliverReverse(LoopbackTransport loop) {
        List<byte[]> frames = loop.serverFrames();
        for (int index = frames.size() - 1; index >= 0; index--) {
            loop.deliverServerFrame(index);
        }
    }

    private static void deliverCorrupted(LoopbackTransport loop, PacketCodec codec) {
        List<byte[]> frames = loop.serverFrames();
        for (int index = 0; index < frames.size(); index++) {
            byte[] frame = frames.get(index);
            loop.deliverServerBytes(index == 0 ? corrupt(frame, codec) : frame);
        }
    }

    private static byte[] corrupt(byte[] frame, PacketCodec codec) {
        FragmentPacket fragment = (FragmentPacket) codec.decode(frame);
        byte[] payload = fragment.getPayload().clone();
        payload[0] ^= 0x01;
        return codec.encode(new FragmentPacket(
                fragment.getSeqId(), fragment.getIndex(), fragment.getTotal(), fragment.getCrc32(), payload));
    }

    private static void assertRetriedGroup(
            ServerGameTestContext context, List<byte[]> frames, int originalCount) {
        for (int index = 0; index < originalCount; index++) {
            context.assertTrue(
                    Arrays.equals(frames.get(index), frames.get(index + originalCount)),
                    "重发必须覆盖整组且逐帧一致，index=" + index);
        }
    }
}
