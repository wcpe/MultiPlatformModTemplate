package top.wcpe.mc.mpmt.platform.forge.modern.contract;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不依赖测试框架的独立车道静态与产物契约入口。
 *
 * <p>断言对象只允许是**编译产物与结构化数据**：构建注入的 {@code mpmt.test.*} 系统属性矩阵、产品 / 验收
 * JAR 的条目与 class 常量池、JAR 内已展开的 {@code META-INF/mods.toml}、期望值 fixture
 * （{@code wire-v1.json}）。构建脚本 / 源码文本一律不作断言依据——它们既不是运行期行为，
 * 也把实现细节固化成了契约（ADR-0027 记录的同代反模式）。
 */
public final class Forge262ContractMain {

    /** 产品 / 验收 JAR 内平台包前缀。 */
    private static final String MODERN = "top/wcpe/mc/mpmt/platform/forge/modern/";

    private static final String ACCEPTANCE = MODERN + "acceptance/";

    /** 验收 JAR 内已展开的 mod 元数据。 */
    private static final String MODS_TOML = "META-INF/mods.toml";

    private static final Pattern ENCODED =
            Pattern.compile("\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    /** 服务端收包器形参签名：{@code BiConsumer<ServerPlayer, byte[]>}。 */
    private static final Pattern SERVER_RECEIVER =
            Pattern.compile("Ljava/util/function/BiConsumer<Lnet/minecraft/server/level/ServerPlayer;\\[B>;");

    /** 客户端收包器形参签名：{@code Consumer<byte[]>}。 */
    private static final Pattern CLIENT_RECEIVER =
            Pattern.compile("Ljava/util/function/Consumer<\\[B>;");

    /** mods.toml 自身版本声明（行首 version=，避免匹配到 loaderVersion）。 */
    private static final Pattern MOD_VERSION = Pattern.compile("(?m)^version=\"([^\"]+)\"");

    /** mods.toml 中产品 mpmt 依赖的版本区间。 */
    private static final Pattern PRODUCT_VERSION_RANGE =
            Pattern.compile("modId=\"mpmt\"[\\s\\S]*?versionRange=\"([^\"]+)\"");

    private static final List<String> REQUIRED_SCENARIOS =
            List.of("product-handshake", "product-roundtrip", "client-hud");

    private Forge262ContractMain() {
        // 契约入口不实例化
    }

    public static void main(String[] args) throws Exception {
        Path projectDir = propertyPath("mpmt.test.projectDir");
        verifyFrozenMatrix();
        verifyGoldenVectors(projectDir);
        try (JarFile product = new JarFile(propertyPath("mpmt.test.productJar").toFile());
                JarFile acceptance = new JarFile(propertyPath("mpmt.test.acceptanceJar").toFile())) {
            verifyJars(product, acceptance);
            verifyNetworkArtifacts(product);
            verifyAcceptanceArtifacts(acceptance);
            verifyAcceptanceMetadata(acceptance);
        }
    }

    private static void verifyFrozenMatrix() {
        require("26.2".equals(System.getProperty("mpmt.test.minecraftVersion")),
                "Minecraft 版本未冻结为 26.2");
        require("26.2-65.0.9".equals(System.getProperty("mpmt.test.forgeVersion")),
                "Forge 版本未冻结为 26.2-65.0.9");
        require("1.17.14".equals(System.getProperty("mpmt.test.loomVersion")),
                "loom 版本未冻结为 1.17.14（WCPE Loom 正式版，ADR-0025）");
        require("9.6.1".equals(System.getProperty("mpmt.test.gradleVersion")),
                "Gradle 版本未冻结为 9.6.1");
        require(Runtime.version().feature() == 25, "契约测试必须运行于 Java 25");
    }

    /**
     * 产品侧网络契约：只看产品 JAR 内 net 相关 class 的常量池。
     *
     * <p>L4 实现 {@code CustomPacketPayload}（直接接口表）、用 {@code StreamCodec} 编解码、
     * 用 Forge {@code ChannelBuilder} / {@code PayloadConnection} 注册 optional 通道、
     * 通道名为 {@code mpmt:main}、对上层只暴露裸 {@code byte[]}；入口按 Forge 26.2 生命周期事件
     * 装配 / 清理服务端闭环，服务端传输对同一底层玩家提供连接建立与断开两个稳定句柄。
     */
    private static void verifyNetworkArtifacts(JarFile product) throws IOException {
        ClassFileConstantPool payload = classPool(product, MODERN + "net/ForgeTypedPayload.class");
        require(payload.interfaces().contains("net/minecraft/network/protocol/common/custom/CustomPacketPayload"),
                "L4 载荷未实现 CustomPacketPayload");
        require(payload.classReferences().contains("net/minecraft/network/codec/StreamCodec"),
                "L4 载荷未使用 StreamCodec");
        ClassFileConstantPool channel =
                classPool(product, MODERN + "net/ForgeTypedPayloadChannel.class");
        require(channel.classReferences().contains("net/minecraftforge/network/ChannelBuilder"),
                "L4 通道未使用 ChannelBuilder");
        require(channel.classReferences().contains("net/minecraftforge/network/payload/PayloadConnection"),
                "L4 通道未使用 Forge PayloadConnection");
        require(channel.memberReferences().contains("net/minecraftforge/network/ChannelBuilder.optional"),
                "产品与控制载荷通道必须为 optional");
        requireUtf8Matching(channel, SERVER_RECEIVER, "服务端上层必须只接收裸 byte[]");
        requireUtf8Matching(channel, CLIENT_RECEIVER, "客户端上层必须只接收裸 byte[]");
        ClassFileConstantPool entry = classPool(product, MODERN + "MpmtForge262Mod.class");
        require(entry.stringConstants().contains("mpmt") && entry.stringConstants().contains("main"),
                "产品通道必须为 mpmt:main");
        requireMemberNamed(entry, "fromNamespaceAndPath", "产品通道必须由资源定位符工厂声明");
        require(entry.classReferences().contains("net/minecraftforge/event/server/ServerStartedEvent")
                        && entry.classReferences().contains("net/minecraftforge/event/server/ServerStoppedEvent"),
                "产品入口必须按 Forge 26.2 生命周期装配和清理服务端闭环");
        ClassFileConstantPool transport = classPool(product, MODERN + "net/ForgeServerTransport.class");
        require(transport.declaredMethods().contains("onConnected")
                        && transport.declaredMethods().contains("onDisconnected"),
                "服务端传输必须为同一底层玩家维持稳定连接句柄");
    }

    /**
     * 验收侧契约：只看验收 JAR 内 acceptance 相关 class 的常量池。
     *
     * <p>控制通道为 {@code mpmt-test:acceptance}、同栈服务端调用 {@code AcceptanceReportV2Factory.create}、
     * 客户端上报 v2 {@code ClientReadyPacket} 与实际 Java major / executable、required 场景齐全、
     * client-hud 校验 {@code HudKind.ACTIONBAR}。
     */
    private static void verifyAcceptanceArtifacts(JarFile acceptance) throws IOException {
        ClassFileConstantPool mod = classPool(acceptance, ACCEPTANCE + "MpmtForge262AcceptanceMod.class");
        require(mod.stringConstants().contains("mpmt-test") && mod.stringConstants().contains("acceptance"),
                "验收控制通道必须为 mpmt-test:acceptance");
        requireMemberNamed(mod, "fromNamespaceAndPath", "验收控制通道必须由资源定位符工厂声明");
        ClassFileConstantPool driver = classPool(acceptance, ACCEPTANCE + "AcceptanceDriver.class");
        require(driver.memberReferences().contains(
                        "top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.create"),
                "同栈服务端必须调用 AcceptanceReportV2Factory.create");
        ClassFileConstantPool companion = classPool(acceptance, ACCEPTANCE + "ForgeAcceptanceClientCompanion.class");
        require(companion.memberReferences().contains(
                        "top/wcpe/mc/mpmt/acceptance/control/ClientReadyPacket.<init>"),
                "客户端必须上报 v2 ClientReadyPacket");
        requireMemberNamed(companion, "getMajor", "客户端必须上报实际 Java major");
        requireMemberNamed(companion, "getExecutable", "客户端必须上报实际 Java executable");
        for (String scenario : REQUIRED_SCENARIOS) {
            require(companion.stringConstants().contains(scenario), "客户端缺少 required 场景：" + scenario);
        }
        require(companion.memberReferences().contains(
                        "top/wcpe/mc/mpmt/protocol/packet/HudKind.ACTIONBAR"),
                "client-hud 必须验证 ACTIONBAR");
    }

    /**
     * 验收 mod 元数据：读验收 JAR 内**已展开**的 {@code mods.toml}。
     *
     * <p>对产品的版本区间下限必须等于本次构建实际产出的版本（占位符已由构建替换），
     * 以此保证「验收伴侣声明的产品依赖下限跟随版本真源」而非靠脚本文本。
     */
    private static void verifyAcceptanceMetadata(JarFile acceptance) throws IOException {
        JarEntry entry = acceptance.getJarEntry(MODS_TOML);
        require(entry != null, "验收 JAR 缺少 " + MODS_TOML);
        String toml;
        try (InputStream input = acceptance.getInputStream(entry)) {
            toml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(!toml.contains("${version}"), "验收 mods.toml 的版本占位符未被构建展开");
        String version = requiredGroup(MOD_VERSION, toml, "验收 mods.toml 缺少自身版本声明");
        String productRange =
                requiredGroup(PRODUCT_VERSION_RANGE, toml, "验收 mod 未声明对产品 mpmt 的依赖区间");
        require(("[" + version + ",)").equals(productRange),
                "验收 mod 对产品的版本约束必须跟随构建版本：期望 [" + version + ",)，实际 " + productRange);
    }

    private static void verifyGoldenVectors(Path projectDir) throws IOException {
        String golden = read(projectDir.resolve("src/test/resources/golden/wire-v1.json"));
        Matcher matcher = ENCODED.matcher(golden);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        require(count == 37, "wire-v1 golden 向量数量不是 37：" + count);
    }

    private static void verifyJars(JarFile product, JarFile acceptance) throws IOException {
        require(product.getEntry(MODERN + "MpmtForge262Mod.class") != null, "产品 JAR 缺少入口");
        require(product.getEntry("top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class") != null,
                "产品 JAR 缺少 core-server");
        require(product.getEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class") != null,
                "产品 JAR 缺少 protocol");
        require(entries(product, "top/wcpe/mc/mpmt/acceptance/").isEmpty(),
                "产品 JAR 误包含 acceptance");
        require(acceptance.getEntry(ACCEPTANCE + "MpmtForge262AcceptanceMod.class") != null,
                "验收 JAR 缺少入口");
        require(acceptance.getEntry(
                        "top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class")
                != null, "验收 JAR 缺少共享 acceptance 核心");
        require(entries(acceptance, "top/wcpe/mc/mpmt/core/").isEmpty(), "验收 JAR 重复包含产品 core");
        require(entries(acceptance, "top/wcpe/mc/mpmt/protocol/").isEmpty(), "验收 JAR 重复包含 protocol");
        require(acceptance.getEntry(MODERN + "MpmtForge262Mod.class") == null, "验收 JAR 重复包含产品入口");
        verifyMajor(product, MODERN, 69);
        verifyMajor(acceptance, ACCEPTANCE, 69);
    }

    private static void verifyMajor(JarFile jar, String prefix, int expected) throws IOException {
        for (JarEntry entry : entries(jar, prefix)) {
            if (!entry.getName().endsWith(".class")) {
                continue;
            }
            try (DataInputStream input = new DataInputStream(jar.getInputStream(entry))) {
                require(input.readInt() == 0xCAFEBABE, "类文件魔数错误：" + entry.getName());
                input.readUnsignedShort();
                int major = input.readUnsignedShort();
                require(major == expected,
                        "平台类 class major 错误：" + entry.getName() + " major=" + major);
            }
        }
    }

    /** 取 JAR 内某个类文件的常量池视图；条目缺失即失败（不静默跳过）。 */
    private static ClassFileConstantPool classPool(JarFile jar, String entryName) throws IOException {
        JarEntry entry = jar.getJarEntry(entryName);
        require(entry != null, "JAR 缺少类文件：" + entryName);
        try (InputStream input = jar.getInputStream(entry)) {
            return ClassFileConstantPool.read(input);
        }
    }

    private static void requireUtf8Matching(ClassFileConstantPool pool, Pattern pattern, String message) {
        require(pool.utf8Constants().stream().anyMatch(value -> pattern.matcher(value).matches()), message);
    }

    private static void requireMemberNamed(ClassFileConstantPool pool, String member, String message) {
        require(pool.memberReferences().stream().anyMatch(value -> value.endsWith("." + member)), message);
    }

    private static String requiredGroup(Pattern pattern, String text, String message) {
        Matcher matcher = pattern.matcher(text);
        require(matcher.find(), message);
        return matcher.group(1);
    }

    private static List<JarEntry> entries(JarFile jar, String prefix) {
        List<JarEntry> matches = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().startsWith(prefix)) {
                matches.add(entry);
            }
        }
        return matches;
    }

    private static Path propertyPath(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("缺少系统属性 -D" + name);
        }
        return Paths.get(value);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
