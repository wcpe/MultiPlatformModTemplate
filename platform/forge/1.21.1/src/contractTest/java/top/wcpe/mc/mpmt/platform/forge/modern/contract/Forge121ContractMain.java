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
public final class Forge121ContractMain {

    private static final Pattern ENCODED =
            Pattern.compile("\\\"encoded\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private Forge121ContractMain() {
        // 契约入口不实例化
    }

    public static void main(String[] args) throws Exception {
        Path repositoryRoot = propertyPath("mpmt.test.repositoryRoot");
        Path projectDir = propertyPath("mpmt.test.projectDir");
        verifyFrozenMatrix();
        verifyIndependentBuild(repositoryRoot, projectDir);
        verifyNetworkSources(projectDir);
        verifyAcceptanceSources(projectDir);
        verifyGoldenVectors(projectDir);
        verifyJars(
                propertyPath("mpmt.test.productJar"),
                propertyPath("mpmt.test.acceptanceJar"));
    }

    private static void verifyFrozenMatrix() {
        require("1.21.1".equals(System.getProperty("mpmt.test.minecraftVersion")),
                "Minecraft 版本未冻结为 1.21.1");
        require("1.21.1-52.1.0".equals(System.getProperty("mpmt.test.forgeVersion")),
                "Forge 版本未冻结为 1.21.1-52.1.0");
        require("6.0.54".equals(System.getProperty("mpmt.test.forgeGradleVersion")),
                "ForgeGradle 版本未冻结为 6.0.54");
        require("8.12.1".equals(System.getProperty("mpmt.test.gradleVersion")),
                "Gradle 版本未冻结为 8.12.1");
        require(Runtime.version().feature() == 21, "契约测试必须运行于 Java 21");
    }

    private static void verifyIndependentBuild(Path repositoryRoot, Path projectDir)
            throws IOException {
        String settings = read(projectDir.resolve("settings.gradle.kts"));
        String build = read(projectDir.resolve("build.gradle"));
        String wrapper = read(projectDir.resolve("gradle/wrapper/gradle-wrapper.properties"));
        // 收纳后独立 launcher 位于 platform/forge/1.21.1，不再经过旧 platform-forge 根包装脚本
        String unixLauncher = read(projectDir.resolve("gradlew"));
        String windowsLauncher = read(projectDir.resolve("gradlew.bat"));
        // 收纳后允许 settings 反向 includeBuild 根工程做依赖替换；
        // 构建期仍以本地 shared JAR 校验为准，禁止嵌套根 launcher。
        require(settings.contains("includeBuild(\"../../..\")")
                        || settings.contains("includeBuild('../../..')"),
                "settings 须反向 includeBuild 仓库根以替换共享坐标");
        require(build.contains("reobf = false"), "独立车道必须关闭 reobf");
        require(build.contains("options.release = 21"), "Java 编译目标必须为 21");
        require(build.contains("productSharedModules"), "产品必须本地消费共享产品核心");
        require(build.contains("sharedJars.each(verifySharedJar)"), "配置期必须校验共享 JAR");
        require(build.contains("sharedModuleArchives"),
                "共享 JAR 必须显式映射 Gradle 产物名");
        require(build.contains("${archive}-${repositoryVersion}.jar"),
                "共享 JAR 版本必须跟随仓库 VERSION");
        require(!build.contains("-0.1.0.jar"), "共享 JAR 路径不得固定为 0.1.0");
        String acceptanceToml = read(projectDir.resolve("src/acceptance/resources/META-INF/mods.toml"));
        require(acceptanceToml.contains("versionRange=\"[${version},)\""),
                "验收 mod 对产品的版本约束必须跟随构建版本");
        require(build.contains("独立车道不会 includeBuild 根工程"),
                "共享 JAR 校验文案须声明不依赖根 launcher 复合构建");
        require(build.contains("runAcceptanceServer")
                        && build.contains("runAcceptanceClient")
                        && build.contains("runRealServerAcceptance"),
                "缺少要求的验收运行入口");
        require(build.contains("mpmt.acceptance.artifact.server-runtime"),
                "真实服务端运行文件必须由调用方显式传入");
        require(wrapper.contains("gradle-8.12.1-bin.zip"), "wrapper 版本必须为 8.12.1");
        // 收纳后 wrapper 使用 validateDistributionUrl=true；不再硬编码历史 distributionSha256Sum
        require(wrapper.contains("validateDistributionUrl=true")
                        || wrapper.contains("distributionSha256Sum"),
                "wrapper 须启用发行包校验（validateDistributionUrl 或 distributionSha256Sum）");
        require(projectDir.endsWith(Paths.get("platform", "forge", "1.21.1")),
                "独立车道工程目录必须为 platform/forge/1.21.1");
        require(unixLauncher.contains("JAVA_HOME"), "Unix 启动脚本未强制 JAVA_HOME");
        require(windowsLauncher.contains("JAVA_HOME"), "Windows 启动脚本未强制 JAVA_HOME");
    }

    private static void verifyNetworkSources(Path projectDir) throws IOException {
        String payload = read(projectDir.resolve(
                "src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/net/ForgeTypedPayload.java"));
        String channel = read(projectDir.resolve(
                "src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/net/ForgeTypedPayloadChannel.java"));
        String entry = read(projectDir.resolve(
                "src/main/java/top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.java"));
        require(payload.contains("CustomPacketPayload"), "L4 必须实现 CustomPacketPayload");
        require(payload.contains("StreamCodec"), "L4 必须使用 StreamCodec");
        require(channel.contains("ChannelBuilder") && channel.contains("PayloadConnection"),
                "L4 必须使用 Forge PayloadChannel API");
        require(channel.contains(".optional()"), "产品与控制 payload 通道必须为 optional");
        require(entry.contains("fromNamespaceAndPath(\"mpmt\", \"main\")"),
                "产品通道必须为 mpmt:main");
        require(channel.contains("BiConsumer<ServerPlayer, byte[]>"), "服务端上层必须只接收裸 byte[]");
        require(channel.contains("Consumer<byte[]>"), "客户端上层必须只接收裸 byte[]");
    }

    private static void verifyAcceptanceSources(Path projectDir) throws IOException {
        String acceptance = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge121AcceptanceMod.java"));
        String driver = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/AcceptanceDriver.java"));
        String companion = read(projectDir.resolve(
                "src/acceptance/java/top/wcpe/mc/mpmt/platform/forge/modern/acceptance/ForgeAcceptanceClientCompanion.java"));
        require(acceptance.contains("mpmt-test") && acceptance.contains("acceptance"),
                "验收控制通道必须为 mpmt-test:acceptance");
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
                            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
                    != null, "产品 JAR 缺少入口");
            require(product.getEntry(
                            "top/wcpe/mc/mpmt/core/server/ServerNetworkFeature.class")
                    != null, "产品 JAR 缺少 core-server");
            require(product.getEntry("top/wcpe/mc/mpmt/protocol/PacketCodec.class") != null,
                    "产品 JAR 缺少 protocol");
            require(entries(product, "top/wcpe/mc/mpmt/acceptance/").isEmpty(),
                    "产品 JAR 误包含 acceptance");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/MpmtForge121AcceptanceMod.class")
                    != null, "验收 JAR 缺少入口");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/acceptance/report/AcceptanceReportV2Factory.class")
                    != null, "验收 JAR 缺少共享 acceptance 核心");
            require(entries(acceptance, "top/wcpe/mc/mpmt/core/").isEmpty(),
                    "验收 JAR 重复包含产品 core");
            require(entries(acceptance, "top/wcpe/mc/mpmt/protocol/").isEmpty(),
                    "验收 JAR 重复包含 protocol");
            require(acceptance.getEntry(
                            "top/wcpe/mc/mpmt/platform/forge/modern/MpmtForge121Mod.class")
                    == null, "验收 JAR 重复包含产品入口");
            verifyMajor(product, "top/wcpe/mc/mpmt/platform/forge/modern/", 65);
            verifyMajor(acceptance, "top/wcpe/mc/mpmt/platform/forge/modern/acceptance/", 65);
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
