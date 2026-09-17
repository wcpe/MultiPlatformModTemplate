package top.wcpe.mc.mpmt.platform.forge.modern.contract;

import java.io.DataInputStream;
import java.io.IOException;
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

/** 不依赖测试框架的独立车道静态与产物契约入口。 */
public final class Forge262ContractMain {

    private static final Pattern ENCODED =
            Pattern.compile("\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    // 车道脚本为 Kotlin DSL：mappings 声明形态为 mappings(...)（Groovy 时代为 mappings channel:）
    private static final Pattern MAPPINGS_DECLARATION =
            Pattern.compile("(?m)^\\s*(mappings\\s+channel\\s*:|mappings\\s*\\()");

    private Forge262ContractMain() {
        // 契约入口不实例化
    }

    public static void main(String[] args) throws Exception {
        Path repositoryRoot = propertyPath("mpmt.test.repositoryRoot");
        Path projectDir = propertyPath("mpmt.test.projectDir");
        verifyFrozenMatrix();
        verifyIndependentBuild(repositoryRoot, projectDir);
        verifyDevRunClasspath(projectDir);
        verifyNetworkSources(projectDir);
        verifyAcceptanceSources(projectDir);
        verifyGoldenVectors(projectDir);
        verifyJars(
                propertyPath("mpmt.test.productJar"),
                propertyPath("mpmt.test.acceptanceJar"));
    }

    private static void verifyFrozenMatrix() {
        require("26.2".equals(System.getProperty("mpmt.test.minecraftVersion")),
                "Minecraft 版本未冻结为 26.2");
        require("26.2-65.0.9".equals(System.getProperty("mpmt.test.forgeVersion")),
                "Forge 版本未冻结为 26.2-65.0.9");
        require("1.17.2".equals(System.getProperty("mpmt.test.loomVersion")),
                "loom 版本未冻结为 1.17.1（WCPE Loom 正式版，ADR-0025）");
        require("9.6.1".equals(System.getProperty("mpmt.test.gradleVersion")),
                "Gradle 版本未冻结为 9.6.1");
        require(Runtime.version().feature() == 25, "契约测试必须运行于 Java 25");
    }

    private static void verifyIndependentBuild(Path repositoryRoot, Path projectDir)
            throws IOException {
        String build = read(projectDir.resolve("build.gradle.kts"));
        String properties = read(projectDir.resolve("gradle.properties"));
        String rootSettings = read(repositoryRoot.resolve("settings.gradle.kts"));
        // ADR-0026：车道为根构建子模块——不再持有独立 settings / 自有 wrapper / 反向 includeBuild
        require(!Files.exists(projectDir.resolve("settings.gradle.kts")),
                "子模块车道不得再持有独立 settings");
        require(!Files.exists(projectDir.resolve("gradlew")),
                "子模块车道不得再持有自有 wrapper");
        require(!build.contains("includeBuild"), "子模块车道不得复合加载根构建");
        require(build.contains("options.release.set(25)"), "Java 编译目标必须为 25");
        require(!MAPPINGS_DECLARATION.matcher(build).find(),
                "Minecraft 26.2 不得声明 mappings（无混淆版本走 disableObfuscation 管线）");
        // ADR-0025：插件统一为 top.wcpe.loom（WCPE Loom），版本在根 settings 单点 pin
        require(build.contains("top.wcpe.loom"), "必须使用 WCPE Loom（top.wcpe.loom，ADR-0025）");
        require(rootSettings.contains("top.wcpe.loom") && rootSettings.contains("1.17.2"),
                "根 settings 未冻结 top.wcpe.loom 1.17.1");
        require(properties.contains("loom.platform=forge"), "gradle.properties 须声明 loom.platform=forge");
        require(properties.contains("fabric.loom.disableObfuscation=true"),
                "gradle.properties 须声明 disableObfuscation（26.2 无混淆无官方 mappings）");
        // ADR-0026 决策 6：共享核心经同根构建项目产物消费，不再按 build/libs 路径硬编码
        require(build.contains("moduleJar("), "共享模块必须经同根构建项目产物消费");
        require(!build.contains("sharedJars"), "不得再按 build/libs 路径硬编码共享 JAR");
        require(build.contains("create(\"acceptanceServer\")")
                        && build.contains("create(\"acceptanceClient\")")
                        && build.contains("runRealServerAcceptance"),
                "缺少要求的验收运行入口");
        require(build.contains("mpmt.acceptance.artifact.server-runtime"),
                "真实服务端运行文件必须由调用方显式传入");
        require(build.contains("val realServerHostRequested"),
                "真实服务端入口必须显式识别主机任务");
        require(build.contains("tasks.register(\"runRealServerAcceptanceHost\")")
                        && build.contains("dependsOn(tasks.named(\"runAcceptanceServer\"))"),
                "真实服务端入口必须复用 loom runAcceptanceServer 启动链路");
        require(build.contains("val acceptanceServerRunDirectory")
                        && build.contains("if (realServerHostRequested) \"run-realserver\" else \"run-acceptance-server\"")
                        && build.contains("runDir(acceptanceServerRunDirectory)")
                        && build.contains("project.file(\"$acceptanceServerRunDirectory/acceptance-report.txt\").absolutePath"),
                "真实服务端入口必须写入独立运行目录的报告");
        require(!build.contains("tasks.register(\"runRealServerAcceptanceHost\", Exec::class.java)")
                        && !build.contains("tasks.register<Exec>(\"runRealServerAcceptanceHost\")"),
                "真实服务端入口不得直接以原版 server.jar 启动");
        require(projectDir.endsWith(Paths.get("platform", "forge", "26.2")),
                "车道工程目录必须为 platform/forge/26.2");
    }

    private static void verifyNetworkSources(Path projectDir) throws IOException {
        String payload = read(projectDir.resolve(
                "common/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/net/ForgeTypedPayload.java"));
        String channel = read(projectDir.resolve(
                "common/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/net/ForgeTypedPayloadChannel.java"));
        String entry = read(projectDir.resolve(
                "common/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.java"));
        String transport = read(projectDir.resolve(
                "server/src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/net/ForgeServerTransport.java"));
        require(payload.contains("CustomPacketPayload"), "L4 必须实现 CustomPacketPayload");
        require(payload.contains("StreamCodec"), "L4 必须使用 StreamCodec");
        require(channel.contains("ChannelBuilder") && channel.contains("PayloadConnection"),
                "L4 必须使用 Forge PayloadChannel API");
        require(channel.contains(".optional()"), "产品与控制 payload 通道必须为 optional");
        require(entry.contains("fromNamespaceAndPath(\"mpmt\", \"main\")"),
                "产品通道必须为 mpmt:main");
        require(entry.contains("ServerStartedEvent.BUS")
                        && entry.contains("ServerStoppedEvent.BUS"),
                "产品入口必须按 Forge 26.2 生命周期装配和清理服务端闭环");
        require(transport.contains("onConnected") && transport.contains("onDisconnected"),
                "服务端传输必须为同一底层玩家维持稳定连接句柄");
        require(channel.contains("BiConsumer<ServerPlayer, byte[]>"), "服务端上层必须只接收裸 byte[]");
        require(channel.contains("Consumer<byte[]>"), "客户端上层必须只接收裸 byte[]");
    }

    private static void verifyDevRunClasspath(Path projectDir) throws IOException {
        String build = read(projectDir.resolve("build.gradle.kts"));
        String properties = read(projectDir.resolve("gradle.properties"));
        int serverStart = build.indexOf("create(\"acceptanceServer\")");
        int clientStart = build.indexOf("create(\"acceptanceClient\")");
        require(serverStart >= 0 && clientStart > serverStart, "缺少 server/client 运行配置");
        require(build.contains("fun installDevAcceptanceMod")
                        && build.contains("from(acceptanceJar")
                        && build.contains("into(modsDir)"),
                "必须提供将独立验收 JAR 安装到运行目录的最小适配");
        require(build.contains("include(\"mpmt-*.jar\")") && build.contains("!candidate.delete()"),
                "验收运行目录必须清理旧版 MPMT JAR，避免重复加载");
        require(build.contains("fun prepareAcceptanceServerProperties")
                        && build.contains("\"online-mode\" to \"false\"")
                        && build.contains("\"enforce-secure-profile\" to \"false\"")
                        && build.contains("prepareAcceptanceServerProperties(runDir)"),
                "真实服务端验收必须关闭线上认证，允许本地 Dev 客户端连接");
        require(build.contains("installDevAcceptanceMod(runDir)")
                        && build.contains("installDevAcceptanceMod(project.file(\"run-acceptance-client\"))"),
                "runAcceptanceServer/runAcceptanceClient 均须安装验收伴侣 JAR");
        require(!properties.contains("net.minecraftforge.gradle.merge-source-sets"),
                "不得重新启用已被实机证伪的 merge-source-sets 推测配置");
    }

    private static void verifyAcceptanceSources(Path projectDir) throws IOException {
        String acceptance = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge262AcceptanceMod.java"));
        String acceptanceMetadata = read(projectDir.resolve(
                "src/acceptance/resources/META-INF/mods.toml"));
        String driver = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/AcceptanceDriver.java"));
        String companion = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/ForgeAcceptanceClientCompanion.java"));
        require(acceptance.contains("mpmt-test") && acceptance.contains("acceptance"),
                "验收控制通道必须为 mpmt-test:acceptance");
        require(acceptanceMetadata.contains("versionRange=\"[${version},)\""),
                "验收伴侣必须从仓库版本真源声明产品依赖下限");
        require(driver.contains("AcceptanceReportV2Factory.create"),
                "同栈服务端必须调用 AcceptanceReportV2Factory");
        require(companion.contains("new ClientReadyPacket"), "客户端必须上报 v2 ClientReadyPacket");
        require(companion.contains("getMajor()") && companion.contains("getExecutable()"),
                "客户端必须上报实际 Java major 和 executable");
        for (String scenario : List.of("product-handshake", "product-roundtrip", "client-hud")) {
            require(companion.contains(scenario), "客户端缺少 required 场景：" + scenario);
        }
        require(companion.contains("HudKind.ACTIONBAR"), "client-hud 必须验证 ACTIONBAR");
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

    private static void verifyJars(Path productPath, Path acceptancePath) throws IOException {
        try (JarFile product = new JarFile(productPath.toFile());
                JarFile acceptance = new JarFile(acceptancePath.toFile())) {
            require(product.getEntry(
                            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
                    != null, "产品 JAR 缺少入口");
            require(product.getEntry(
                            "top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class")
                    != null, "产品 JAR 缺少 core-server");
            require(product.getEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class") != null,
                    "产品 JAR 缺少 protocol");
            require(entries(product, "top/wcpe/mc/mpmt/acceptance/").isEmpty(),
                    "产品 JAR 误包含 acceptance");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge262AcceptanceMod.class")
                    != null, "验收 JAR 缺少入口");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class")
                    != null, "验收 JAR 缺少共享 acceptance 核心");
            require(entries(acceptance, "top/wcpe/mc/mpmt/core/").isEmpty(),
                    "验收 JAR 重复包含产品 core");
            require(entries(acceptance, "top/wcpe/mc/mpmt/protocol/").isEmpty(),
                    "验收 JAR 重复包含 protocol");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge262Mod.class")
                    == null, "验收 JAR 重复包含产品入口");
            verifyMajor(product, "top/wcpe/mc/mpmt/platform/forge/modern/", 69);
            verifyMajor(acceptance, "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/", 69);
        }
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
