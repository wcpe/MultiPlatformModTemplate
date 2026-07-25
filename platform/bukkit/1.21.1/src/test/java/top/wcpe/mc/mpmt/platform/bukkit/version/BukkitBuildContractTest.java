package top.wcpe.mc.mpmt.platform.bukkit.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Bukkit 版本车道、单一 L4、metadata、JDK 与产物命名契约。 */
class BukkitBuildContractTest {

    private static final String[] L4_ADAPTERS = {
        "top.wcpe.mc.mpmt.platform.bukkit.version.v1_12.V1_12BukkitVersionAdapter",
        "top.wcpe.mc.mpmt.platform.bukkit.version.v1_20.V1_20BukkitVersionAdapter",
        "top.wcpe.mc.mpmt.platform.bukkit.version.v1_21.V1_21BukkitVersionAdapter"
    };

    @Test
    @DisplayName("只编译选中的唯一 L4 并使用目标 JDK 与产物名")
    void 单一L4与工具链() {
        long adapters = Arrays.stream(L4_ADAPTERS).filter(BukkitBuildContractTest::classExists).count();
        String selected = System.getProperty("mpmt.test.minecraftVersion");

        assertEquals(1L, adapters, "每个构建只能存在一个 L4 适配器");
        assertEquals(System.getProperty("mpmt.test.javaVersion"), System.getProperty("java.specification.version"));
        assertTrue(System.getProperty("mpmt.test.archiveName").contains(selected));
        assertEquals(
                "1.12.2".equals(selected),
                !classExists("top.wcpe.mc.mpmt.platform.bukkit.capability.FoliaSchedulerPort"));
    }

    @Test
    @DisplayName("产品与验收 metadata 匹配目标版本且入口角色隔离")
    void metadata匹配目标版本() throws IOException {
        String product = resource("plugin.yml");
        String acceptance = file(System.getProperty("mpmt.test.acceptanceMetadata"));
        String apiVersion = System.getProperty("mpmt.test.apiVersion");
        boolean folia = Boolean.parseBoolean(System.getProperty("mpmt.test.foliaMetadata"));

        assertTrue(product.contains("main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin"));
        assertFalse(product.contains("MpmtBukkitAcceptancePlugin"));
        assertTrue(acceptance.contains("MpmtBukkitAcceptancePlugin"));
        assertFalse(acceptance.contains("main: top.wcpe.mc.mpmt.platform.bukkit.MpmtBukkitPlugin"));
        assertMetadata(product, apiVersion, folia);
        assertMetadata(acceptance, apiVersion, folia);
    }

    private static void assertMetadata(String metadata, String apiVersion, boolean folia) {
        if (apiVersion.isEmpty()) {
            assertFalse(metadata.contains("api-version:"));
        } else {
            assertTrue(metadata.contains("api-version: '" + apiVersion + "'"));
        }
        assertEquals(folia, metadata.contains("folia-supported: true"));
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, BukkitBuildContractTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = BukkitBuildContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("缺少测试资源：" + name);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String file(String path) throws IOException {
        Path file = Paths.get(path);
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
