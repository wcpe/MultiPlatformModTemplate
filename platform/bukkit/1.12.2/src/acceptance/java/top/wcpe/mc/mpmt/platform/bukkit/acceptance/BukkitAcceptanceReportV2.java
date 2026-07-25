package top.wcpe.mc.mpmt.platform.bukkit.acceptance;

import java.io.IOException;
import java.util.List;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.report.MatrixAcceptanceReportV2;
import top.wcpe.mc.mpmt.acceptance.report.ScenarioResult;

/**
 * Bukkit realserver 矩阵 v2 报告门面：委托平台无关 {@link MatrixAcceptanceReportV2}，
 * 保留既有类名供本模块调用方使用。
 */
final class BukkitAcceptanceReportV2 {

    static final String REPORT_PROPERTY = MatrixAcceptanceReportV2.REPORT_PROPERTY;
    static final String MATRIX_PROPERTY = MatrixAcceptanceReportV2.MATRIX_PROPERTY;

    private BukkitAcceptanceReportV2() {
        // 工具类不实例化
    }

    static boolean matrixModeActive() {
        return MatrixAcceptanceReportV2.matrixModeActive();
    }

    static void validateRequiredProperties() {
        MatrixAcceptanceReportV2.validateRequiredProperties();
    }

    static void deleteOldReport() throws IOException {
        MatrixAcceptanceReportV2.deleteOldReport();
    }

    static String renderReport(AcceptanceClient client, List<ScenarioResult> results) {
        return MatrixAcceptanceReportV2.renderReport(client, results);
    }

    static String renderFailure(AcceptanceClient client, String message) {
        return MatrixAcceptanceReportV2.renderFailure(client, message);
    }

    static String describe(Throwable throwable) {
        return MatrixAcceptanceReportV2.describe(throwable);
    }
}
