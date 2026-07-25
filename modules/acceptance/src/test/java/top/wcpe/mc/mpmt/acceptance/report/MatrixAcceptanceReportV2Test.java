package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;

/** 矩阵 v2 报告门面：属性校验、装配与失败路径契约。 */
class MatrixAcceptanceReportV2Test {

    private static final String[] MATRIX_PROPS = {
        MatrixAcceptanceReportV2.MATRIX_PROPERTY,
        MatrixAcceptanceReportV2.REPORT_PROPERTY,
        "mpmt.acceptance.runId",
        "mpmt.acceptance.startEpochMs",
        "mpmt.acceptance.javaExecutable",
        "mpmt.acceptance.artifact.server-runtime",
        "mpmt.acceptance.artifact.server-product",
        "mpmt.acceptance.artifact.server-acceptance",
        "mpmt.acceptance.artifact.client-product",
        "mpmt.acceptance.artifact.client-acceptance"
    };

    @TempDir Path tempDir;

    @AfterEach
    void 清理系统属性() {
        for (String name : MATRIX_PROPS) {
            System.clearProperty(name);
        }
    }

    @Test
    @DisplayName("仅 R1–R6 视为矩阵模式激活")
    void 矩阵模式探测() {
        assertFalse(MatrixAcceptanceReportV2.matrixModeActive());
        System.setProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY, "R1");
        assertTrue(MatrixAcceptanceReportV2.matrixModeActive());
        System.setProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY, "bukkit");
        assertFalse(MatrixAcceptanceReportV2.matrixModeActive());
    }

    @Test
    @DisplayName("缺必填属性时启动校验失败")
    void 缺属性拒绝() {
        System.setProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY, "R1");
        assertThrows(
                IllegalArgumentException.class,
                MatrixAcceptanceReportV2::validateRequiredProperties);
    }

    @Test
    @DisplayName("属性齐全时可装配 PASS 报告；无客户端 Java 则结构化 FAIL")
    void 装配与失败() throws IOException {
        installValidProperties();
        MatrixAcceptanceReportV2.validateRequiredProperties();

        AcceptanceClient ready = new AcceptanceClient(bytes -> {});
        ready.onClientReady(
                new ClientReadyPacket(
                        AcceptanceControlCodec.PROTOCOL_VERSION, 17, "C:/client/java.exe"));
        List<ScenarioResult> results =
                Collections.singletonList(
                        new ScenarioResult(
                                "acceptance",
                                "product-handshake",
                                ScenarioStatus.PASS,
                                1L,
                                "通过"));
        String pass = MatrixAcceptanceReportV2.renderReport(ready, results);
        assertTrue(pass.startsWith("SERVER-GAMETEST-REPORT v2\n"));
        assertTrue(pass.contains("RESULT PASS"));

        String fail =
                MatrixAcceptanceReportV2.renderFailure(
                        new AcceptanceClient(bytes -> {}), "客户端未就绪");
        assertTrue(fail.startsWith("SERVER-GAMETEST-REPORT v2\n"));
        assertTrue(fail.contains("RESULT FAIL"));
    }

    @Test
    @DisplayName("deleteOldReport 删除既有报告文件")
    void 删除旧报告() throws IOException {
        Path report = tempDir.resolve("old-report.txt");
        Files.write(report, "stale".getBytes(StandardCharsets.UTF_8));
        System.setProperty(MatrixAcceptanceReportV2.REPORT_PROPERTY, report.toString());
        assertTrue(Files.exists(report));
        MatrixAcceptanceReportV2.deleteOldReport();
        assertFalse(Files.exists(report));
    }

    private void installValidProperties() throws IOException {
        System.setProperty(MatrixAcceptanceReportV2.MATRIX_PROPERTY, "R1");
        System.setProperty("mpmt.acceptance.runId", "run-matrix-test");
        System.setProperty("mpmt.acceptance.startEpochMs", "1000");
        System.setProperty("mpmt.acceptance.javaExecutable", "C:/server/java.exe");
        for (String role :
                new String[] {
                    "server-runtime",
                    "server-product",
                    "server-acceptance",
                    "client-product",
                    "client-acceptance"
                }) {
            Path file = tempDir.resolve(role + ".jar");
            Files.write(file, role.getBytes(StandardCharsets.UTF_8));
            System.setProperty("mpmt.acceptance.artifact." + role, file.toString());
        }
        System.setProperty(
                MatrixAcceptanceReportV2.REPORT_PROPERTY,
                tempDir.resolve("report.txt").toString());
    }
}
