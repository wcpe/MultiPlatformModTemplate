package top.wcpe.mc.mpmt.platform.fabric.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 版本车道、单一 L4、元数据、Java 与产物命名契约。 */
class FabricBuildContractTest {

    @Test
    @DisplayName("只编译选中 L4 且使用目标 JDK")
    void 单一L4与Jdk() {
        String selected = System.getProperty("mpmt.test.minecraftVersion");
        String selectedClass =
                "1.20.1".equals(selected)
                        ? "top.wcpe.mc.mpmt.platform.fabric.version.v1_20.V1_20ServerNetwork"
                        : "top.wcpe.mc.mpmt.platform.fabric.version.v1_21.V1_21ServerNetwork";
        String excludedClass =
                "1.20.1".equals(selected)
                        ? "top.wcpe.mc.mpmt.platform.fabric.version.v1_21.V1_21ServerNetwork"
                        : "top.wcpe.mc.mpmt.platform.fabric.version.v1_20.V1_20ServerNetwork";

        assertTrue(classExists(selectedClass));
        assertFalse(classExists(excludedClass));
        assertEquals(System.getProperty("mpmt.test.javaVersion"), System.getProperty("java.specification.version"));
        assertTrue(System.getProperty("mpmt.test.archiveName").contains(selected));
    }

    @Test
    @DisplayName("产品与验收元数据精确冻结到选中车道")
    void 元数据精确匹配目标() throws IOException {
        String productMetadata = resource("fabric.mod.json");
        String acceptanceMetadata =
                new String(
                        Files.readAllBytes(Paths.get(System.getProperty("mpmt.test.acceptanceMetadata"))),
                        StandardCharsets.UTF_8);
        String selected = System.getProperty("mpmt.test.minecraftVersion");
        String loader = "1.21.1".equals(selected) ? "0.19.3" : "0.16.5";
        String fabricApi =
                "1.21.1".equals(selected) ? "0.116.14+1.21.1" : "0.92.2+1.20.1";

        assertEquals(loader, System.getProperty("mpmt.test.loaderDependency"));
        assertEquals(fabricApi, System.getProperty("mpmt.test.fabricApiDependency"));
        assertMetadata(productMetadata, selected, loader, fabricApi);
        assertMetadata(acceptanceMetadata, selected, loader, fabricApi);
        assertTrue(acceptanceMetadata.contains("\"id\": \"mpmt-acceptance\""));
    }

    @Test
    @DisplayName("产品与验收产物名称包含平台角色和目标版本")
    void 产物命名契约() {
        String selected = System.getProperty("mpmt.test.minecraftVersion");
        String projectVersion = System.getProperty("mpmt.test.projectVersion");

        assertEquals("mpmt-fabric-" + selected, System.getProperty("mpmt.test.archiveName"));
        assertEquals(
                "mpmt-fabric-acceptance-" + selected + "-" + projectVersion + ".jar",
                System.getProperty("mpmt.test.acceptanceArchiveName"));
    }

    private static void assertMetadata(
            String metadata, String minecraftVersion, String loaderVersion, String fabricApiVersion) {
        assertTrue(metadata.contains("\"minecraft\": \"~" + minecraftVersion + "\""));
        assertTrue(metadata.contains("\"java\": \">=" + System.getProperty("mpmt.test.javaVersion") + "\""));
        assertTrue(metadata.contains("\"fabricloader\": \"" + loaderVersion + "\""));
        assertTrue(metadata.contains("\"fabric-api\": \"" + fabricApiVersion + "\""));
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, FabricBuildContractTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = FabricBuildContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("缺少测试资源：" + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
