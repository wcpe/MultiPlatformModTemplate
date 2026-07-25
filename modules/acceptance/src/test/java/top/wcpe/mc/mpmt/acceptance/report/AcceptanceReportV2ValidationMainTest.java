package top.wcpe.mc.mpmt.acceptance.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** v2 报告校验命令行的工作区文件边界。 */
class AcceptanceReportV2ValidationMainTest {

    @Test
    @DisplayName("允许读取工作区内的普通文件")
    void 允许工作区文件() throws IOException {
        Path directory = Paths.get("build", "tmp", "validation-main-test");
        Files.createDirectories(directory);
        Path file = directory.resolve("report.txt");
        Files.write(file, "report".getBytes(StandardCharsets.UTF_8));

        Path validated = AcceptanceReportV2ValidationMain.workspaceFile(file.toString());

        assertEquals(file.toRealPath(), validated);
    }

    @Test
    @DisplayName("拒绝读取工作区外的文件")
    void 拒绝工作区外文件() throws IOException {
        Path external = Files.createTempFile("mpmt-report-", ".txt");
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AcceptanceReportV2ValidationMain.workspaceFile(external.toString()));
        } finally {
            Files.deleteIfExists(external);
        }
    }

    @Test
    @DisplayName("拒绝工作区内不存在的文件")
    void 拒绝不存在文件() {
        Path missing = Paths.get("build", "tmp", "validation-main-test", "missing.txt");

        assertThrows(
                IllegalArgumentException.class,
                () -> AcceptanceReportV2ValidationMain.workspaceFile(missing.toString()));
    }
}
