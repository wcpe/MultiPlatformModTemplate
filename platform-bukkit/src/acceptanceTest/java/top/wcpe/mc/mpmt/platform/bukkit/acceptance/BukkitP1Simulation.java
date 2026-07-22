package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReport;
import top.wcpe.mc.mpmt.acceptance.report.AcceptanceReportMetadata;
import top.wcpe.mc.mpmt.acceptance.report.P1ScenarioMatrix;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioStatus;
import top.wcpe.mc.mpmt.core.client.HandshakeClientService;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;
import top.wcpe.mc.mpmt.core.domain.event.SimpleEventBus;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionControlPort;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.MessagePort;
import top.wcpe.mc.mpmt.core.domain.port.PersistencePort;
import top.wcpe.mc.mpmt.core.domain.port.SchedulerPort;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.core.domain.ref.WorldRef;
import top.wcpe.mc.mpmt.core.server.HandshakeServerService;
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
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.reliability.Fragmenter;
import top.wcpe.mc.mpmt.protocol.reliability.OutboundFragmentCache;
import top.wcpe.mc.mpmt.protocol.reliability.Reassembler;

/** Bukkit 1.20.1 验收制品内的 P1 模拟服套件；故障注入只存在于本源集。 */
public final class BukkitP1Simulation {

    private static final String SUITE = "acceptance";

    private BukkitP1Simulation() {
        // 工具类不实例化
    }

    /** 顺序执行 Forge 适用的完整 P1 清单。 */
    public static List<ScenarioResult> run() {
        List<ScenarioResult> results = new ArrayList<>();
        scenario(results, P1ScenarioMatrix.HANDSHAKE_SUCCESS, BukkitP1Simulation::handshakeSuccess);
        scenario(results, P1ScenarioMatrix.HANDSHAKE_INCOMPATIBLE, BukkitP1Simulation::handshakeIncompatible);
        scenario(results, P1ScenarioMatrix.MACHINE_CODE_SESSION, BukkitP1Simulation::machineCodeSession);
        scenario(results, P1ScenarioMatrix.BAN_RECONNECT, BukkitP1Simulation::banReconnect);
        scenario(results, P1ScenarioMatrix.UNBAN_RECONNECT, BukkitP1Simulation::unbanReconnect);
        scenario(results, P1ScenarioMatrix.FRAGMENT_CRC, BukkitP1Simulation::fragmentCrc);
        scenario(results, P1ScenarioMatrix.FRAGMENT_TIMEOUT_RETRY_RESYNC, BukkitP1Simulation::fragmentTimeout);
        scenario(results, P1ScenarioMatrix.SESSION_HEARTBEAT_RTT_TIMEOUT, BukkitP1Simulation::sessionHeartbeat);
        scenario(results, P1ScenarioMatrix.CAPABILITY_EVENT_BUS, BukkitP1Simulation::capabilityEventBus);
        scenario(results, P1ScenarioMatrix.HUD_TITLE, () -> hud(HudKind.TITLE, "title-token"));
        scenario(results, P1ScenarioMatrix.HUD_ACTIONBAR, () -> hud(HudKind.ACTIONBAR, "actionbar-token"));
        scenario(results, P1ScenarioMatrix.HUD_TOAST, () -> hud(HudKind.TOAST, "toast-token"));
        scenario(results, P1ScenarioMatrix.HUD_CHAT, () -> hud(HudKind.CHAT, "chat-token"));
        scenario(results, P1ScenarioMatrix.REAL_ROUND_TRIP, BukkitP1Simulation::roundTrip);
        return results;
    }

    /** JavaExec 入口：执行模拟套件或严格校验已有报告。 */
    public static void main(String[] args) throws IOException {
        if (args.length == 2 && "verify".equals(args[0])) {
            verify(Paths.get(args[1]));
            return;
        }
        List<ScenarioResult> results = run();
        String report = AcceptanceReport.render(metadata("bukkit"), results);
        Path output = Paths.get(requiredProperty("mpmt.acceptance.report"));
        write(output, report);
        if (!AcceptanceReport.isAcceptedReport(report)) {
            throw new IllegalStateException("Bukkit 纯 JVM P1验收报告未通过严格校验");
        }
    }

    /** 构造 acceptance v2 元数据，供模拟服与 realserver 驱动共用。 */
    public static AcceptanceReportMetadata metadata(String defaultPlatform) {
        String platform = System.getProperty("mpmt.acceptance.platform", defaultPlatform);
        return new AcceptanceReportMetadata(
                requiredProperty("mpmt.acceptance.commit"),
                requiredProperty("mpmt.acceptance.version"),
                platform,
                requiredProperty("mpmt.acceptance.mcVersion"),
                requiredProperty("mpmt.acceptance.serverVersion"),
                productJarSha256(),
                P1ScenarioMatrix.requiredFor(platform));
    }

    private static void verify(Path report) throws IOException {
        String text = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        if (!AcceptanceReport.isAcceptedReport(text)) {
            throw new IllegalStateException("验收报告未通过 acceptance v2 严格校验：" + report);
        }
    }

    private static void scenario(List<ScenarioResult> results, String fullId, Scenario body) {
        long started = System.nanoTime();
        try {
            body.run();
            results.add(result(fullId, ScenarioStatus.PASS, started, "场景通过"));
        } catch (RuntimeException | AssertionError error) {
            results.add(result(fullId, ScenarioStatus.FAIL, started, error.getMessage()));
        }
    }

    private static ScenarioResult result(
            String fullId, ScenarioStatus status, long started, String message) {
        String id = fullId.substring(fullId.indexOf('/') + 1);
        long duration = (System.nanoTime() - started) / 1_000_000L;
        return new ScenarioResult(SUITE, id, status, duration, String.valueOf(message));
    }

    private static void handshakeSuccess() {
        Loopback loop = new Loopback();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);
        HandshakeServerService server =
                new HandshakeServerService(serverDispatcher, () -> "bukkit-session", new BanRegistry());
        HandshakeClientService client =
                new HandshakeClientService(clientDispatcher, "bukkit-acceptance", () -> "bukkit-code");

        client.startHandshake();

        require(client.isAccepted(), "兼容握手应成功");
        require("bukkit-session".equals(client.sessionId()), "会话编号不匹配");
        require(server.stateOf(loop.clientConnection()) == HandshakeStateMachine.State.ESTABLISHED,
                "服务端未建立会话");
    }

    private static void handshakeIncompatible() {
        ServerHarness harness = new ServerHarness(new BanRegistry());
        harness.connect();
        harness.receive(new ClientHelloPacket(ProtocolVersion.CURRENT + 1, "incompatible"));

        ServerHelloPacket hello = (ServerHelloPacket) harness.lastPacket();
        require(!hello.isAccepted(), "不兼容协议应被拒绝");
        require(harness.state() == HandshakeStateMachine.State.REJECTED, "服务端应进入拒绝态");
    }

    private static void machineCodeSession() {
        SessionRegistry sessions = new SessionRegistry();
        ServerHarness harness = new ServerHarness(new BanRegistry(), sessions);
        harness.establish("bukkit-machine-code");

        require(harness.state() == HandshakeStateMachine.State.ESTABLISHED, "机器码上报后应建立会话");
        SessionRegistry.Session session =
                sessions.get(harness.connection()).orElseThrow(AssertionError::new);
        require("bukkit-machine-code".equals(session.getMachineCode().getValue()), "会话机器码未登记");
        require("server-session".equals(session.getSessionId()), "会话编号未登记");
    }

    private static void banReconnect() {
        BanRegistry bans = new BanRegistry();
        bans.ban(new MachineCode("blocked-code"), "验收封禁");
        ServerHarness harness = new ServerHarness(bans);

        harness.establish("blocked-code");

        require(harness.state() == HandshakeStateMachine.State.REJECTED, "封禁后重连应拒绝");
    }

    private static void unbanReconnect() {
        BanRegistry bans = new BanRegistry();
        MachineCode code = new MachineCode("released-code");
        bans.ban(code, "验收封禁");
        bans.unban(code);
        ServerHarness harness = new ServerHarness(bans);

        harness.establish(code.getValue());

        require(harness.state() == HandshakeStateMachine.State.ESTABLISHED, "解封后应恢复握手");
    }

    private static void fragmentCrc() {
        byte[] payload = payload(96);
        List<FragmentPacket> fragments = new ArrayList<>(new Fragmenter().split(7, payload, 16));
        FragmentPacket original = fragments.get(0);
        byte[] corrupted = original.getPayload().clone();
        corrupted[0] ^= 0x5A;
        fragments.set(0, new FragmentPacket(original.getSeqId(), original.getIndex(),
                original.getTotal(), original.getCrc32(), corrupted));

        Reassembler reassembler = new Reassembler(1000L);
        Optional<byte[]> complete = Optional.empty();
        for (FragmentPacket fragment : fragments) {
            complete = reassembler.accept(fragment);
        }
        require(!complete.isPresent(), "CRC 损坏不得产出完整载荷");
    }

    private static void fragmentTimeout() {
        AtomicLong clock = new AtomicLong();
        List<Integer> timeouts = new ArrayList<>();
        ConnectionHandle connection = new TestConnection();
        Reassembler reassembler = new Reassembler(
                10L, 100L, 32, 1024, 4096, clock::get,
                (ignored, seqId, count) -> timeouts.add(count));
        FragmentPacket first = new Fragmenter().split(9, payload(64), 16).get(0);

        reassembler.accept(connection, first);
        clock.set(11L);
        reassembler.tickTimeouts();
        OutboundFragmentCache cache = new OutboundFragmentCache(100L, 4, 1024);
        cache.put(connection, 9, Arrays.asList(new byte[] {1}, new byte[] {2}), clock.get());
        require(cache.takeForRetry(connection, 9, clock.get()) != null, "首次超时应可整组重请求");
        reassembler.accept(connection, first);
        clock.set(22L);
        reassembler.tickTimeouts();

        require(timeouts.equals(Arrays.asList(1, 2)), "第二次超时应升级重同步");
        require(cache.takeForRetry(connection, 9, clock.get()) == null, "重发缓存只能消费一次");
    }

    private static void sessionHeartbeat() {
        Loopback loop = new Loopback();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);
        SessionRegistry sessions = new SessionRegistry();
        SessionRegistry.Session established =
                sessions.register(loop.clientConnection(), "heartbeat-session", new MachineCode("heartbeat-code"));
        ManualScheduler scheduler = new ManualScheduler();
        FakeConnectionControl connections = new FakeConnectionControl();
        top.wcpe.mc.mpmt.core.server.HeartbeatService server =
                new top.wcpe.mc.mpmt.core.server.HeartbeatService(
                        sessions, serverDispatcher, scheduler, connections);
        top.wcpe.mc.mpmt.core.client.HeartbeatService client =
                new top.wcpe.mc.mpmt.core.client.HeartbeatService(clientDispatcher);

        scheduler.tick();
        SessionRegistry.Session withRtt = sessions.get(loop.clientConnection()).orElseThrow(AssertionError::new);
        require(withRtt.getRttMillis() >= 0L, "Ping/Pong 后应记录 RTT");
        SessionRegistry.Session required = sessions.markResyncRequired(withRtt).orElseThrow(AssertionError::new);
        require(required.getState() == SessionRegistry.State.RESYNC_REQUIRED, "首次超时应进入重同步宽限");
        SessionRegistry.Session complete =
                sessions.markResyncComplete(required, 2L).orElseThrow(AssertionError::new);
        server.onResyncComplete(complete);
        require(complete.getState() == SessionRegistry.State.RESYNC_COMPLETE, "宽限期重同步应恢复会话");
        client.close();
        server.close();
        require(established.getGeneration() == complete.getGeneration(), "会话 generation 不应漂移");
    }

    private static void capabilityEventBus() {
        MemoryPersistence persistence = new MemoryPersistence();
        RecordingMessage messages = new RecordingMessage();
        ManualScheduler scheduler = new ManualScheduler();
        PlatformCapabilityExample example =
                new PlatformCapabilityExample(persistence, messages, scheduler, () -> 123L);
        SimpleEventBus eventBus = new SimpleEventBus();
        PlayerRef player = new PlayerRef(UUID.randomUUID(), "AcceptancePlayer");
        example.register(eventBus);

        eventBus.publish(new PlayerJoinedEvent(player));
        require(!persistence.values.isEmpty(), "加入事件应经 EventBus 触发持久化");
        require(!messages.values.isEmpty(), "加入事件应经归属调度发消息");
        scheduler.tick();
        eventBus.publish(new PlayerLeftEvent(player));
        require(scheduler.closed.get() == 1, "离开事件应释放 capability 心跳句柄");
    }

    private static void hud(HudKind kind, String token) {
        PacketCodec codec = new PacketCodec();
        ServerHudMessagePacket decoded = (ServerHudMessagePacket) codec.decode(
                codec.encode(new ServerHudMessagePacket(kind, token, token + "-sub", 77L)));
        require(decoded.getKind() == kind, "HUD 类型未保持");
        require(token.equals(decoded.getText()), "HUD 独立 token 未保持");
    }

    private static void roundTrip() {
        Loopback loop = new Loopback();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher server = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher client = new PacketDispatcher(loop.client(), codec);
        AtomicLong pong = new AtomicLong(-1L);
        server.on(PacketIds.PING,
                (connection, packet) -> server.send(connection, new PongPacket(((PingPacket) packet).getNonce())));
        client.on(PacketIds.PONG,
                (connection, packet) -> pong.set(((PongPacket) packet).getNonce()));

        client.send(new PingPacket(4242L));

        require(pong.get() == 4242L, "集成回环 Ping/Pong 不一致");
    }

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) (index * 31 + 7);
        }
        return data;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String requiredProperty(String key) {
        String value = System.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("缺少验收元数据系统属性：" + key);
        }
        return value;
    }

    private static String productJarSha256() {
        String supplied = System.getProperty("mpmt.acceptance.productJarSha256");
        if (supplied != null && !supplied.trim().isEmpty()) {
            return supplied;
        }
        Path product = Paths.get(requiredProperty("mpmt.acceptance.productJar"));
        try {
            byte[] bytes = Files.readAllBytes(product);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest) {
                value.append(String.format("%02x", part));
            }
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException | IOException error) {
            throw new IllegalStateException("计算产品 jar SHA-256 失败：" + product, error);
        }
    }

    private static void write(Path file, String report) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, report.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Scenario {
        void run();
    }

    private static final class ServerHarness {
        private final PacketCodec codec = new PacketCodec();
        private final FakeTransport transport = new FakeTransport();
        private final ConnectionHandle connection = new TestConnection();
        private final HandshakeServerService service;

        private ServerHarness(BanRegistry bans) {
            this(bans, new SessionRegistry());
        }

        private ServerHarness(BanRegistry bans, SessionRegistry sessions) {
            service = new HandshakeServerService(
                    new PacketDispatcher(transport, codec), () -> "server-session", bans, sessions);
        }

        private ConnectionHandle connection() {
            return connection;
        }

        private void connect() {
            service.onConnected(connection);
        }

        private void establish(String machineCode) {
            connect();
            receive(new ClientHelloPacket(ProtocolVersion.CURRENT, "acceptance"));
            receive(new ClientIdReportPacket(machineCode));
        }

        private void receive(Packet packet) {
            transport.receive(connection, codec.encode(packet));
        }

        private Packet lastPacket() {
            return codec.decode(transport.sent.get(transport.sent.size() - 1));
        }

        private HandshakeStateMachine.State state() {
            return service.stateOf(connection);
        }
    }

    private static final class Loopback {
        private final ConnectionHandle clientConnection = new TestConnection();
        private final ConnectionHandle serverConnection = new TestConnection();
        private BiConsumer<ConnectionHandle, byte[]> serverReceiver;
        private BiConsumer<ConnectionHandle, byte[]> clientReceiver;

        private ConnectionHandle clientConnection() {
            return clientConnection;
        }

        private TransportPort server() {
            return transport(
                    (connection, data) -> clientReceiver.accept(serverConnection, data),
                    handler -> serverReceiver = handler,
                    false);
        }

        private TransportPort client() {
            return transport(
                    (connection, data) -> serverReceiver.accept(clientConnection, data),
                    handler -> clientReceiver = handler,
                    true);
        }

        private static TransportPort transport(
                BiConsumer<ConnectionHandle, byte[]> sender,
                java.util.function.Consumer<BiConsumer<ConnectionHandle, byte[]>> receiver,
                boolean client) {
            return new TransportPort() {
                @Override
                public void send(ConnectionHandle connection, byte[] data) {
                    if (client) {
                        throw new UnsupportedOperationException("客户端只支持无连接发送");
                    }
                    sender.accept(connection, data);
                }

                @Override
                public void send(byte[] data) {
                    if (!client) {
                        throw new UnsupportedOperationException("服务端只支持有连接发送");
                    }
                    sender.accept(null, data);
                }

                @Override
                public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
                    receiver.accept(handler);
                }

                @Override
                public int maxPayloadSize() {
                    return 32767;
                }
            };
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
            sent.add(data);
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        private void receive(ConnectionHandle connection, byte[] data) {
            receiver.accept(connection, data);
        }
    }

    private static final class ManualScheduler implements SchedulerPort {
        private final AtomicInteger closed = new AtomicInteger();
        private Runnable timer;

        @Override
        public void runForEntity(EntityRef entity, Runnable task) {
            task.run();
        }

        @Override
        public void runForLocation(WorldRef world, int x, int z, Runnable task) {
            task.run();
        }

        @Override
        public void runGlobal(Runnable task) {
            task.run();
        }

        @Override
        public void runAsync(Runnable task) {
            task.run();
        }

        @Override
        public AutoCloseable runTimer(long delayTicks, long periodTicks, Runnable task) {
            timer = task;
            return closed::incrementAndGet;
        }

        private void tick() {
            require(timer != null, "周期任务尚未注册");
            timer.run();
        }
    }

    private static final class FakeConnectionControl implements ConnectionControlPort {
        @Override
        public EntityRef entityOf(ConnectionHandle connection) {
            return new EntityRef(UUID.randomUUID());
        }

        @Override
        public void disconnect(ConnectionHandle connection, String reason) {
            throw new AssertionError("宽限期内不应断开：" + reason);
        }
    }

    private static final class MemoryPersistence implements PersistencePort {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public Optional<String> read(String namespace, String key) {
            return Optional.ofNullable(values.get(namespace + "/" + key));
        }

        @Override
        public void write(String namespace, String key, String value) {
            values.put(namespace + "/" + key, value);
        }
    }

    private static final class RecordingMessage implements MessagePort {
        private final List<String> values = new ArrayList<>();

        @Override
        public void send(PlayerRef player, String text) {
            values.add(text);
        }
    }

    private static final class TestConnection implements ConnectionHandle {
    }
}
