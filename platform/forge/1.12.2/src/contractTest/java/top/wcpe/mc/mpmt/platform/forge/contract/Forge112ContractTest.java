package top.wcpe.mc.mpmt.platform.forge.contract;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.NetworkCheckHandler;
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket;
import net.minecraftforge.fml.relauncher.Side;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.platform.forge.MpmtForgeMod;
import top.wcpe.mc.mpmt.platform.forge.acceptance.MpmtForgeAcceptanceMod;
import top.wcpe.mc.mpmt.platform.forge.client.ForgeClientSession;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudPort;
import top.wcpe.mc.mpmt.platform.forge.hud.ForgeHudSnapshot;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeClientTransportPort;
import top.wcpe.mc.mpmt.platform.forge.net.ForgePayloadCodec;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.ClientHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ClientIdReportPacket;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHelloPacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/** 不依赖外部测试框架的 1.12.2 严格契约测试入口。 */
public final class Forge112ContractTest {

    private static final int EXPECTED_GOLDEN_VECTORS = 37;
    private static final Pattern GOLDEN_VECTOR =
            Pattern.compile(
                    "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                            + "\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                    Pattern.DOTALL);

    private Forge112ContractTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyFrozenBuildLane();
        verifyClientOnlyOptionalMetadata();
        verifyAcceptanceControlV2();
        verifyJoinAfterPlayerReadyTick();
        verifyHandshakeLifecycle();
        verifyWireGoldenVectors();
        verifyArtifactIsolation();
        System.out.println("1.12.2 契约测试全部通过");
    }

    private static void verifyFrozenBuildLane() throws IOException {
        require("1.8".equals(System.getProperty("java.specification.version")), "契约测试必须运行于 Java 8");
        File repositoryRoot = propertyFile("mpmt.test.repositoryRoot");
        // 布局收纳后：platform/forge/1.12.2（ADR-0007 / 目录即工程）
        File lane = new File(repositoryRoot, "platform/forge/1.12.2");
        String wrapper = read(new File(lane, "gradle/wrapper/gradle-wrapper.properties"));
        String build = read(new File(lane, "build.gradle"));
        require(wrapper.contains("gradle-5.6.4-bin.zip"), "wrapper 未冻结 Gradle 5.6.4");
        require(build.contains("ForgeGradle:3.0.197"), "未冻结 ForgeGradle 3.0.197");
        require(build.contains("1.12.2-14.23.5.2860"), "未冻结 Forge 1.12.2-14.23.5.2860");
        require(!build.contains("includeBuild"), "1.12.2 车道不得复合加载现代根构建");
        require(
                build.contains(
                        "sharedModules = ['domain', 'runtime', 'client', 'protocol', 'acceptance']"),
                "缺少完整的本地共享 JAR 输入契约");
        require(build.contains("${dir}/build/libs/${module}-${project.version}.jar"), "共享 JAR 路径不符");
        require(build.contains("sharedJars.each(verifySharedJar)"), "共享 JAR 未在配置期校验");
        require(build.contains("major > 52"), "共享 JAR 未限制 Java 8 类版本");
    }

    private static void verifyClientOnlyOptionalMetadata() throws IOException {
        assertOptionalMod(MpmtForgeMod.class, "mpmt");
        assertOptionalMod(MpmtForgeAcceptanceMod.class, "mpmt_acceptance");
        require("MPMT".equals(MpmtForgeMod.PRODUCT_CHANNEL), "产品通道必须为 MPMT");
        require(MpmtForgeMod.PRODUCT_CHANNEL.indexOf(':') < 0, "产品通道必须为裸 MPMT");
        require(
                "MPMTTEST".equals(MpmtForgeAcceptanceMod.ACCEPTANCE_CHANNEL),
                "验收通道必须为 MPMTTEST");
        require(
                MpmtForgeAcceptanceMod.ACCEPTANCE_CHANNEL.indexOf(':') < 0,
                "验收通道必须为裸 MPMTTEST");

        File repositoryRoot = propertyFile("mpmt.test.repositoryRoot");
        File lane = new File(repositoryRoot, "platform/forge/1.12.2");
        assertOptionalMetadata(new File(lane, "src/main/resources/mcmod.info"), "mpmt");
        assertOptionalMetadata(
                new File(lane, "src/acceptance/resources/mcmod.info"), "mpmt_acceptance");

        MpmtForgeMod product = new MpmtForgeMod();
        require(product.acceptRemoteVersions(Collections.emptyMap(), Side.SERVER), "optional 检查必须放行");
        require(MpmtForgeMod.optionalCheckAccepted(), "未记录服务端 optional 检查");
        require(MpmtForgeMod.remoteForgeProductAbsent(), "未记录服务端 Forge 产品缺失");
    }

    private static void assertOptionalMod(Class<?> modClass, String expectedModId) {
        Mod mod = modClass.getAnnotation(Mod.class);
        require(mod != null, "缺少 @Mod：" + modClass.getName());
        require(expectedModId.equals(mod.modid()), "modId 不符：" + modClass.getName());
        require(mod.clientSideOnly(), "mod 必须声明 clientSideOnly：" + expectedModId);
        require("*".equals(mod.acceptableRemoteVersions()), "mod 必须允许服务端缺失：" + expectedModId);
        boolean handlerFound = false;
        for (Method method : modClass.getDeclaredMethods()) {
            if (method.getAnnotation(NetworkCheckHandler.class) != null) {
                handlerFound = true;
            }
        }
        require(handlerFound, "缺少 NetworkCheckHandler：" + expectedModId);
    }

    private static void assertOptionalMetadata(File metadataFile, String expectedModId)
            throws IOException {
        String metadata = read(metadataFile);
        require(metadata.contains("\"modid\": \"" + expectedModId + "\""), "mcmod.info modid 不符");
        require(metadata.contains("\"clientSideOnly\": true"), "mcmod.info 缺少 clientSideOnly");
        require(
                metadata.contains("\"acceptableRemoteVersions\": \"*\""),
                "mcmod.info 缺少 acceptableRemoteVersions");
    }

    private static void verifyAcceptanceControlV2() {
        String executableName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String executable =
                new File(new File(System.getProperty("java.home"), "bin"), executableName)
                        .getAbsolutePath();
        ClientReadyPacket ready =
                (ClientReadyPacket)
                        AcceptanceControlCodec.decode(
                                AcceptanceControlCodec.encode(
                                        new ClientReadyPacket(
                                                AcceptanceControlCodec.PROTOCOL_VERSION,
                                                8,
                                                executable)));
        require(AcceptanceControlCodec.PROTOCOL_VERSION == 2, "验收控制协议必须为 v2");
        require(ready.getJavaMajor() == 8, "ClientReady 未保留 Java major");
        require(executable.equals(ready.getJavaExecutable()), "ClientReady 未保留 Java executable");
    }

    private static void verifyJoinAfterPlayerReadyTick() throws Exception {
        FakeTransport transport = new FakeTransport();
        ForgeClientSession session =
                new ForgeClientSession(transport, new FakeHud(), "0.1.0", () -> "tick-client-id");
        MpmtForgeMod product = new MpmtForgeMod();
        setSession(product, session);

        product.onConnected(null);
        require(transport.sent.isEmpty(), "Netty 连接事件后不得立即发送 ClientHello");
        invokeClientTick(product, TickEvent.Phase.START, true);
        invokeClientTick(product, TickEvent.Phase.END, false);
        require(transport.sent.isEmpty(), "玩家世界就绪前不得发送 ClientHello");
        invokeClientTick(product, TickEvent.Phase.END, true);
        require(transport.sent.size() == 1, "玩家就绪的 END tick 必须启动一次 join");
        invokeClientTick(product, TickEvent.Phase.END, true);
        require(transport.sent.size() == 1, "重复 tick 不得重发 ClientHello");

        product.onDisconnected(null);
        invokeClientTick(product, TickEvent.Phase.END, true);
        require(transport.sent.size() == 1, "断线后 tick 不得启动 join");
        product.onConnected(null);
        invokeClientTick(product, TickEvent.Phase.END, true);
        require(transport.sent.size() == 2, "重连后必须允许新会话启动 join");
        product.onDisconnected(null);
    }

    private static void setSession(MpmtForgeMod product, ForgeClientSession session)
            throws Exception {
        Field field = MpmtForgeMod.class.getDeclaredField("session");
        field.setAccessible(true);
        field.set(product, session);
    }

    private static void invokeClientTick(
            MpmtForgeMod product, TickEvent.Phase phase, boolean playerReady)
            throws Exception {
        Method method =
                MpmtForgeMod.class.getDeclaredMethod(
                        "handleClientTick", TickEvent.Phase.class, boolean.class);
        method.setAccessible(true);
        method.invoke(product, phase, playerReady);
    }

    private static void verifyHandshakeLifecycle() {
        FakeTransport transport = new FakeTransport();
        FakeHud hud = new FakeHud();
        ForgeClientSession session =
                new ForgeClientSession(transport, hud, "0.1.0", () -> "1.12.2-client-id");
        require(transport.sent.isEmpty(), "构造产品会话时不得提前握手");

        session.join();
        require(transport.sent.size() == 1, "连接事件后的 join 必须只先发 ClientHello");
        PacketCodec codec = new PacketCodec();
        Packet hello = codec.decode(transport.sent.get(0));
        require(hello instanceof ClientHelloPacket, "首包必须为 ClientHello");

        transport.receive(codec.encode(new ServerHelloPacket(1, "1.12.2-session", true)));
        require(transport.sent.size() == 2, "握手接受后必须上报客户端标识");
        require(codec.decode(transport.sent.get(1)) instanceof ClientIdReportPacket, "次包必须为 ClientIdReport");
        require(session.networkFeature().handshakeClient().isAccepted(), "共享客户端握手状态未接受");
        require(
                "1.12.2-session".equals(session.networkFeature().handshakeClient().sessionId()),
                "共享客户端未保留真实 sessionId");

        transport.receive(codec.encode(new ServerMessagePacket("欢迎")));
        require(
                "欢迎".equals(session.networkFeature().handshakeClient().lastServerMessage()),
                "共享客户端未接收服务端握手消息");

        boolean[] pongReceived = {false};
        session.networkFeature()
                .dispatcher()
                .on(
                        top.wcpe.mc.mpmt.protocol.PacketIds.PONG,
                        (connection, packet) ->
                                pongReceived[0] = ((PongPacket) packet).getNonce() == 20260718L);
        session.networkFeature().dispatcher().send(new PingPacket(20260718L));
        require(transport.sent.size() == 3, "产品 dispatcher 未发送 Ping");
        Packet ping = codec.decode(transport.sent.get(2));
        require(ping instanceof PingPacket, "产品往返首包必须为 Ping");
        require(((PingPacket) ping).getNonce() == 20260718L, "产品 Ping nonce 不符");
        transport.receive(codec.encode(new PongPacket(20260718L)));
        require(pongReceived[0], "产品 dispatcher 未接收匹配 Pong");

        ServerHudMessagePacket hudPacket =
                new ServerHudMessagePacket(HudKind.ACTIONBAR, "验收HUD", "", 1000L);
        transport.receive(codec.encode(hudPacket));
        ForgeHudSnapshot snapshot = session.hudSnapshot();
        require(snapshot != null && "验收HUD".equals(snapshot.text()), "HUD 未接入共享 dispatcher");

        session.disconnect();
        require(session.networkFeature() == null, "断线后必须丢弃产品网络特性");
        require(transport.receiver == null, "断线后必须清除产品收包器");
    }

    private static void verifyWireGoldenVectors() throws IOException {
        File repositoryRoot = propertyFile("mpmt.test.repositoryRoot");
        File golden = new File(repositoryRoot, "core/protocol/src/test/resources/golden/wire-v1.json");
        Matcher matcher = GOLDEN_VECTOR.matcher(read(golden));
        PacketCodec codec = new PacketCodec();
        int count = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            byte[] expected = Base64.getDecoder().decode(matcher.group(2));
            Packet decoded = codec.decode(expected);
            require(equal(expected, codec.encode(decoded)), "PacketCodec 偏离 wire-v1：" + name);
            FMLProxyPacket carrier = ForgePayloadCodec.outgoing("MPMT", expected);
            require(equal(expected, ForgePayloadCodec.incoming(carrier)), "1.12.2 外层改变裸 payload：" + name);
            count++;
        }
        require(count == EXPECTED_GOLDEN_VECTORS, "wire-v1 向量数量不符：" + count);
    }

    private static void verifyArtifactIsolation() throws IOException {
        File productJar = propertyFile("mpmt.test.productJar");
        File acceptanceJar = propertyFile("mpmt.test.acceptanceJar");
        String version = System.getProperty("mpmt.test.version");
        require(
                productJar.getName().equals("mpmt-forge-1.12.2-" + version + ".jar"),
                "产品 JAR 命名不符：" + productJar.getName());
        require(
                acceptanceJar.getName().equals(
                        "mpmt-forge-acceptance-1.12.2-" + version + ".jar"),
                "验收 JAR 命名不符：" + acceptanceJar.getName());

        JarView product = JarView.read(productJar);
        JarView acceptance = JarView.read(acceptanceJar);
        // 源码包路径为 platform.forge（不含 1.12.2 段）；目录 1.12.2 仅表示版本车道
        product.requireEntry("top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class");
        product.requireEntry("top/wcpe/mc/mpmt/core/client/ClientNetworkFeature.class");
        product.requireEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class");
        product.requireEntry("mcmod.info");
        require(!product.hasPrefix("top/wcpe/mc/mpmt/acceptance/"), "产品 JAR 混入 acceptance 共享模块");
        require(!product.hasPrefix("top/wcpe/mc/mpmt/platform/forge/acceptance/"), "产品 JAR 混入验收伴侣");
        require(product.containsAscii("MPMT"), "产品 JAR 缺少裸通道 MPMT");
        require(!product.containsAscii("MPMTTEST"), "产品 JAR 泄漏验收通道 MPMTTEST");

        acceptance.requireEntry(
                "top/wcpe/mc/mpmt/platform/forge/acceptance/MpmtForgeAcceptanceMod.class");
        acceptance.requireEntry(
                "top/wcpe/mc/mpmt/acceptance/control/AcceptanceControlCodec.class");
        acceptance.requireEntry("mcmod.info");
        require(!acceptance.hasPrefix("top/wcpe/mc/mpmt/core/"), "验收 JAR 重复打入产品 core 模块");
        require(!acceptance.hasPrefix("top/wcpe/mc/mpmt/protocol/"), "验收 JAR 重复打入产品 protocol");
        require(
                !acceptance.hasEntry("top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class"),
                "验收 JAR 混入产品入口");
        require(
                !acceptance.hasEntry("top/wcpe/mc/mpmt/platform/forge/ForgeBuildInfo.class"),
                "验收 JAR 混入产品构建信息实现");
        require(!acceptance.hasPrefix("top/wcpe/mc/mpmt/platform/forge/client/"), "验收 JAR 混入产品会话实现");
        require(!acceptance.hasPrefix("top/wcpe/mc/mpmt/platform/forge/hud/"), "验收 JAR 混入产品 HUD 实现");
        require(!acceptance.hasPrefix("top/wcpe/mc/mpmt/platform/forge/net/"), "验收 JAR 混入产品传输实现");
        require(acceptance.containsAscii("MPMTTEST"), "验收 JAR 缺少独占控制通道 MPMTTEST");

        require(!product.hasEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class"), "产品 JAR 不得含服务端产品逻辑");
        require(!acceptance.hasEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class"), "验收 JAR 不得含服务端产品逻辑");
        product.requireJava8Classes();
        acceptance.requireJava8Classes();
    }

    private static File propertyFile(String name) {
        String value = System.getProperty(name);
        require(value != null && !value.isEmpty(), "缺少系统属性：" + name);
        File file = new File(value);
        require(file.exists(), "路径不存在：" + file);
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static boolean equal(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }
        for (int index = 0; index < left.length; index++) {
            if (left[index] != right[index]) {
                return false;
            }
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeTransport implements ForgeClientTransportPort {

        private final List<byte[]> sent = new ArrayList<>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            throw new UnsupportedOperationException("测试客户端不支持服务端方向发送");
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

        @Override
        public void clearReceiver() {
            receiver = null;
        }

        private void receive(byte[] data) {
            require(receiver != null, "测试收包器尚未注册");
            receiver.accept(null, data);
        }
    }

    private static final class FakeHud implements ForgeHudPort {

        private volatile ForgeHudSnapshot snapshot;

        @Override
        public void register(PacketDispatcher dispatcher) {
            dispatcher.on(
                    top.wcpe.mc.mpmt.protocol.PacketIds.SERVER_HUD_MESSAGE,
                    (connection, packet) -> {
                        ServerHudMessagePacket hud = (ServerHudMessagePacket) packet;
                        snapshot =
                                new ForgeHudSnapshot(
                                        hud.getKind(),
                                        hud.getText(),
                                        hud.getSubtitle(),
                                        hud.getDurationMillis());
                    });
        }

        @Override
        public ForgeHudSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void clear() {
            snapshot = null;
        }
    }

    private static final class JarView {

        private final File file;
        private final List<String> entries;

        private JarView(File file, List<String> entries) {
            this.file = file;
            this.entries = entries;
        }

        private static JarView read(File file) throws IOException {
            List<String> entries = new ArrayList<>();
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    entries.add(enumeration.nextElement().getName());
                }
            }
            return new JarView(file, entries);
        }

        private boolean hasEntry(String entry) {
            return entries.contains(entry);
        }

        private void requireEntry(String entry) {
            require(hasEntry(entry), file.getName() + " 缺少条目：" + entry);
        }

        private boolean hasPrefix(String prefix) {
            for (String entry : entries) {
                if (entry.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        private boolean containsAscii(String text) throws IOException {
            byte[] needle = text.getBytes(StandardCharsets.US_ASCII);
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (entry.isDirectory()) {
                        continue;
                    }
                    try (InputStream input = zip.getInputStream(entry)) {
                        if (contains(input, needle)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private static boolean contains(InputStream input, byte[] needle) throws IOException {
            int matched = 0;
            int value;
            while ((value = input.read()) >= 0) {
                byte current = (byte) value;
                if (current == needle[matched]) {
                    matched++;
                    if (matched == needle.length) {
                        return true;
                    }
                } else {
                    matched = current == needle[0] ? 1 : 0;
                }
            }
            return false;
        }

        private void requireJava8Classes() throws IOException {
            try (ZipFile zip = new ZipFile(file)) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (DataInputStream input = new DataInputStream(zip.getInputStream(entry))) {
                        require(input.readInt() == 0xCAFEBABE, "类文件魔数错误：" + entry.getName());
                        input.readUnsignedShort();
                        int major = input.readUnsignedShort();
                        require(major <= 52, "非 Java 8 类：" + entry.getName() + " major=" + major);
                    }
                }
            }
        }
    }
}
