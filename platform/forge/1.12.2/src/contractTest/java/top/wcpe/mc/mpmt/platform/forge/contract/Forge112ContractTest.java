package top.wcpe.mc.mpmt.platform.forge.contract;

import java.io.ByteArrayOutputStream;
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
import top.wcpe.mc.mpmt.kernel.port.ConnectionHandle;
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

/**
 * 不依赖外部测试框架的 1.12.2 严格契约测试入口。
 *
 * <p>全部断言只落在三类可信输入上：构建注入的 {@code mpmt.test.*} 系统属性、jar 内编译产物
 * （条目 / class 常量池 / 已展开的元数据）、运行期行为。不回读 {@code build.gradle.kts} 或
 * {@code .java} 源码做文本判断。
 */
public final class Forge112ContractTest {

    private static final int EXPECTED_GOLDEN_VECTORS = 37;
    /** 产品入口类在 jar 内的路径：产品通道常量与目标 MC 版本契约都落在它的常量池上。 */
    private static final String PRODUCT_ENTRY = "top/wcpe/mc/mpmt/platform/forge/MpmtForgeMod.class";
    /** 验收入口类在 jar 内的路径：验收独占通道常量落在它的常量池上。 */
    private static final String ACCEPTANCE_ENTRY =
            "top/wcpe/mc/mpmt/platform/forge/acceptance/MpmtForgeAcceptanceMod.class";
    /** 产品通道的裸名称（不带命名空间冒号）。 */
    private static final String PRODUCT_CHANNEL = "MPMT";
    /** 验收控制通道的裸名称（不带命名空间冒号）。 */
    private static final String ACCEPTANCE_CHANNEL = "MPMTTEST";
    private static final Pattern GOLDEN_VECTOR =
            Pattern.compile(
                    "\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*?"
                            + "\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"",
                    Pattern.DOTALL);

    private Forge112ContractTest() {
    }

    public static void main(String[] args) throws Exception {
        verifyJava8Runtime();
        verifyClientOnlyOptionalMetadata();
        verifyAcceptanceControlV2();
        verifyJoinAfterPlayerReadyTick();
        verifyHandshakeLifecycle();
        verifyWireGoldenVectors();
        verifyPackagedContract();
        verifyArtifactIsolation();
        System.out.println("1.12.2 契约测试全部通过");
    }

    /**
     * 契约测试自身必须跑在 Java 8 上：1.12.2 客户端与本车道全部字节码都以 Java 8 为上限，
     * 换更高 JVM 运行会掩盖运行期不兼容。
     */
    private static void verifyJava8Runtime() {
        require("1.8".equals(System.getProperty("java.specification.version")), "契约测试必须运行于 Java 8");
    }

    /** 客户端 mod 的可选性：{@code @Mod} 注解契约 + 服务端缺失时的运行期放行行为。 */
    private static void verifyClientOnlyOptionalMetadata() {
        assertOptionalMod(MpmtForgeMod.class, "mpmt");
        assertOptionalMod(MpmtForgeAcceptanceMod.class, "mpmt_acceptance");

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
        // wire-v1.json 是结构化期望值 fixture（非 .java / 非 .gradle.kts），作为编码金标读入
        File golden = new File(propertyFile("mpmt.test.repositoryRoot"),
                "core/protocol/src/test/resources/golden/wire-v1.json");
        Matcher matcher = GOLDEN_VECTOR.matcher(read(golden));
        PacketCodec codec = new PacketCodec();
        int count = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            byte[] expected = Base64.getDecoder().decode(matcher.group(2));
            Packet decoded = codec.decode(expected);
            require(equal(expected, codec.encode(decoded)), "PacketCodec 偏离 wire-v1：" + name);
            FMLProxyPacket carrier = ForgePayloadCodec.outgoing(PRODUCT_CHANNEL, expected);
            require(equal(expected, ForgePayloadCodec.incoming(carrier)), "1.12.2 外层改变裸 payload：" + name);
            count++;
        }
        require(count == EXPECTED_GOLDEN_VECTORS, "wire-v1 向量数量不符：" + count);
    }

    /**
     * 发布物契约：通道常量、目标 MC 版本、元数据展开与 MCP→SRG 重映射痕迹。
     *
     * <p>这些约束原本靠回读 {@code build.gradle.kts} 文本保证，现全部改为读 jar 内真实内容：
     * 常量池精确匹配（不会像字节子串匹配那样把 {@code MPMT} 命中到 {@code MPMTTEST}）。
     */
    private static void verifyPackagedContract() throws IOException {
        String version = propertyValue("mpmt.test.version");
        JarView product = JarView.read(propertyFile("mpmt.test.productJar"));
        JarView acceptance = JarView.read(propertyFile("mpmt.test.acceptanceJar"));

        ClassFileConstantPool productEntry = product.constantPool(PRODUCT_ENTRY);
        ClassFileConstantPool acceptanceEntry = acceptance.constantPool(ACCEPTANCE_ENTRY);

        // 通道常量：产品与验收各占一条裸通道，且互不复用（含 ':' 的命名空间形式不合契约）
        require(productEntry.hasUtf8(PRODUCT_CHANNEL), "产品入口类常量池缺少裸通道 MPMT");
        require(!productEntry.hasUtf8Prefix(PRODUCT_CHANNEL + ":"), "产品通道必须为裸 MPMT，不得带命名空间");
        require(!productEntry.hasUtf8(ACCEPTANCE_CHANNEL), "产品入口类不得引用验收通道 MPMTTEST");
        require(acceptanceEntry.hasUtf8(ACCEPTANCE_CHANNEL), "验收入口类常量池缺少独占控制通道 MPMTTEST");
        require(
                !acceptanceEntry.hasUtf8Prefix(ACCEPTANCE_CHANNEL + ":"),
                "验收通道必须为裸 MPMTTEST，不得带命名空间");
        require(
                !product.containsConstant(ACCEPTANCE_CHANNEL),
                "产品 JAR 泄漏验收通道 MPMTTEST");

        // 目标 MC 版本与 Forge 入口注解都落在产物上：版本 pin 写错会让客户端在真实服上被拒载
        require(productEntry.hasUtf8("[1.12.2]"), "产品入口类未声明 acceptedMinecraftVersions=[1.12.2]");
        require(
                productEntry.hasUtf8("Lnet/minecraftforge/fml/common/Mod;"),
                "产品入口类未应用 Forge @Mod 注解");
        // 共享核心接入：入口类常量池必须引用共享核心实现，证明 L0–L2 是经项目产物消费而非桩
        require(
                productEntry.hasClassReference("top/wcpe/mc/mpmt/core/client/DefaultMachineCodeProvider"),
                "产品入口类未接入共享核心 DefaultMachineCodeProvider");

        // 重映射痕迹：生产 jar 的成员名必须是 SRG（func_/field_）形态，
        // 否则客户端加载即崩——这条同时排除了走 no-remap 变体的链路（Forge 版本错配也会在此暴露）
        require(product.hasSrgMemberNames(), "产品 JAR 未见 SRG 成员名，疑似走了 no-remap 链路");
        require(!productEntry.hasUtf8("getMinecraft"), "产品入口类仍用 MCP 名 getMinecraft，未重映射");

        // 元数据：构建期必须把 ${version} 占位符展开成真实版本（发布物可见契约）
        assertPackagedMetadata(product.entryText("mcmod.info"), "mpmt", version, false);
        assertPackagedMetadata(
                acceptance.entryText("mcmod.info"), "mpmt_acceptance", version, true);
    }

    /** 校验 jar 内已展开的 {@code mcmod.info}：modid / 客户端独有 / 容忍服务端缺失 / 版本已展开。 */
    private static void assertPackagedMetadata(
            String metadata, String expectedModId, String expectedVersion, boolean acceptanceCompanion) {
        require(metadata.contains("\"modid\": \"" + expectedModId + "\""), "mcmod.info modid 不符：" + expectedModId);
        require(metadata.contains("\"clientSideOnly\": true"), "mcmod.info 缺少 clientSideOnly：" + expectedModId);
        require(
                metadata.contains("\"acceptableRemoteVersions\": \"*\""),
                "mcmod.info 缺少 acceptableRemoteVersions：" + expectedModId);
        require(metadata.contains("\"mcversion\": \"1.12.2\""), "mcmod.info mcversion 不符：" + expectedModId);
        require(
                metadata.contains("\"version\": \"" + expectedVersion + "\""),
                "mcmod.info 版本占位符未展开：" + expectedModId);
        require(!metadata.contains("${version}"), "mcmod.info 残留未展开占位符：" + expectedModId);
        if (acceptanceCompanion) {
            // 验收伴侣不打包产品实现类（同 classloader 下由产品 jar 提供），故必须声明对 mpmt 的依赖
            require(
                    metadata.contains("\"dependencies\": [\"mpmt\"]"),
                    "验收 mcmod.info 未声明对产品的依赖");
        }
    }

    /** 双 JAR 隔离：产品与验收各自只含自己的实现，且字节码上限为 Java 8。 */
    private static void verifyArtifactIsolation() throws IOException {
        File productJar = propertyFile("mpmt.test.productJar");
        File acceptanceJar = propertyFile("mpmt.test.acceptanceJar");
        String version = propertyValue("mpmt.test.version");
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
        // 共享核心已进产品 JAR：这条同时替代了"共享模块经同根构建项目产物消费"的构建脚本断言
        product.requireEntry("top/wcpe/mc/mpmt/core/client/ClientNetworkFeature.class");
        product.requireEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class");
        product.requireEntry("mcmod.info");
        require(!product.hasPrefix("top/wcpe/mc/mpmt/acceptance/"), "产品 JAR 混入 acceptance 共享模块");
        require(!product.hasPrefix("top/wcpe/mc/mpmt/platform/forge/acceptance/"), "产品 JAR 混入验收伴侣");

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

        require(!product.hasEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class"), "产品 JAR 不得含服务端产品逻辑");
        require(!acceptance.hasEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class"), "验收 JAR 不得含服务端产品逻辑");
        product.requireJava8Classes();
        acceptance.requireJava8Classes();
    }

    private static String propertyValue(String name) {
        String value = System.getProperty(name);
        require(value != null && !value.isEmpty(), "缺少系统属性：" + name);
        return value;
    }

    private static File propertyFile(String name) {
        File file = new File(propertyValue(name));
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

    /** jar 产物视图：条目、条目内容、class 常量池与字节码版本都从 jar 内真实数据读取。 */
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

        /** 读取 jar 内单个条目的原始字节；条目缺失直接判失败。 */
        private byte[] entryBytes(String entry) throws IOException {
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry zipEntry = zip.getEntry(entry);
                require(zipEntry != null, file.getName() + " 缺少条目：" + entry);
                try (InputStream input = zip.getInputStream(zipEntry)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int read = input.read(chunk);
                    while (read > 0) {
                        buffer.write(chunk, 0, read);
                        read = input.read(chunk);
                    }
                    return buffer.toByteArray();
                }
            }
        }

        /** 读取 jar 内文本条目（如已展开的 mcmod.info）。 */
        private String entryText(String entry) throws IOException {
            return new String(entryBytes(entry), StandardCharsets.UTF_8);
        }

        /** 解析 jar 内某个 class 的常量池。 */
        private ClassFileConstantPool constantPool(String entry) throws IOException {
            return ClassFileConstantPool.read(entryBytes(entry));
        }

        /** jar 内是否有任一类的常量池含该 UTF8 原文（精确匹配，避免子串误判）。 */
        private boolean containsConstant(String value) throws IOException {
            for (String entry : entries) {
                if (entry.endsWith(".class") && constantPool(entry).hasUtf8(value)) {
                    return true;
                }
            }
            return false;
        }

        /** 是否出现 SRG 成员名（{@code func_} / {@code field_} 前缀）：证明 MCP→SRG 重映射真的跑过。 */
        private boolean hasSrgMemberNames() throws IOException {
            for (String entry : entries) {
                if (!entry.endsWith(".class")) {
                    continue;
                }
                ClassFileConstantPool pool = constantPool(entry);
                if (pool.hasUtf8Prefix("func_") || pool.hasUtf8Prefix("field_")) {
                    return true;
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
