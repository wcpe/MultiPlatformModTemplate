package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** v2 生产报告装配、实际制品哈希与结构化失败契约。 */
class AcceptanceReportV2FactoryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("R7 按实际文件计算五类 SHA-256 并转换场景结果")
    void 装配r7实际报告() throws IOException {
        Path serverRuntime = artifact("server-runtime");
        Path serverProduct = artifact("server-product");
        Path serverAcceptance = artifact("server-acceptance");
        Path clientProduct = artifact("client-product");
        Path clientAcceptance = artifact("client-acceptance");
        List<ScenarioResult> results = Arrays.asList(
                new ScenarioResult("acceptance", "product-handshake", ScenarioStatus.PASS, 12L, "通过"),
                new ScenarioResult("acceptance", "optional", ScenarioStatus.SKIP, 0L, "未启用"));

        AcceptanceReportV2 report = AcceptanceReportV2Factory.create(
                "run-1",
                "R7",
                1000L,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                new JavaRuntimeInfo(21, "C:/client/java.exe"),
                serverRuntime,
                serverProduct,
                serverAcceptance,
                clientProduct,
                clientAcceptance,
                results);

        assertEquals(5, report.getArtifacts().size());
        assertEquals(
                "14df78f43b7b41f79c81847972dbb0e4e3b35a561c2f59be75e2d07f8d44690e",
                report.getArtifacts().get(0).getSha256());
        assertEquals("server-runtime", report.getArtifacts().get(0).getRole());
        assertEquals("client-acceptance", report.getArtifacts().get(4).getRole());
        assertEquals("product-handshake", report.getScenarios().get(0).getId());
        assertEquals(ScenarioStatus.SKIP, report.getScenarios().get(1).getStatus());
    }

    @Test
    @DisplayName("缺少元数据或客户端身份时仍生成可解析的结构化 FAIL")
    void 结构化失败() {
        AcceptanceReportV2 failure = AcceptanceReportV2Factory.failure(
                null,
                null,
                -1L,
                new JavaRuntimeInfo(21, "C:/server/java.exe"),
                null,
                "缺少本轮元数据和客户端 Java 上报");

        String text = AcceptanceReportV2Renderer.render(failure);
        AcceptanceReportV2 parsed = AcceptanceReportV2Parser.parse(text);

        assertTrue(text.startsWith("SERVER-GAMETEST-REPORT v2\n"));
        assertFalse(text.contains("SERVER-GAMETEST-REPORT v1"));
        assertTrue(text.endsWith("RESULT FAIL\n"));
        assertFalse(parsed.isResultPass());
        assertEquals(ScenarioStatus.ERROR, parsed.getScenarios().get(0).getStatus());
        assertEquals("missing-client-java", parsed.getClientJava().getExecutable());
        assertTrue(parsed.getArtifacts().isEmpty());
    }

    @Test
    @DisplayName("制品文件缺失时拒绝伪造哈希")
    void 缺少制品拒绝() {
        Path missing = tempDir.resolve("missing.jar");
        assertThrows(IOException.class, () -> AcceptanceReportV2Factory.sha256(missing));
    }

    private Path artifact(String content) throws IOException {
        Path file = tempDir.resolve(content + ".jar");
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
