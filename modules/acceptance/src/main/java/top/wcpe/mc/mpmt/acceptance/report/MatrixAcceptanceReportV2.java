package top.wcpe.mc.mpmt.acceptance.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;

/**
 * R1–R7 矩阵 realserver 的 v2 报告装配与本轮系统属性校验（平台无关）。
 *
 * <p>各平台验收驱动在声明 {@code -Dmpmt.acceptance.matrix=Rn} 时使用本类；默认 P1 轨仍走
 * {@link AcceptanceReport} + {@link P1ScenarioMatrix}。
 */
public final class MatrixAcceptanceReportV2 {

    public static final String REPORT_PROPERTY = "mpmt.acceptance.report";
    public static final String MATRIX_PROPERTY = "mpmt.acceptance.matrix";

    private static final String RUN_ID_PROPERTY = "mpmt.acceptance.runId";
    private static final String START_EPOCH_PROPERTY = "mpmt.acceptance.startEpochMs";
    private static final String JAVA_EXECUTABLE_PROPERTY = "mpmt.acceptance.javaExecutable";
    private static final String ARTIFACT_PROPERTY_PREFIX = "mpmt.acceptance.artifact.";
    private static final List<String> MATRICES =
            Arrays.asList("R1", "R2", "R3", "R4", "R5", "R6", "R7");
    private static final List<String> ARTIFACT_ROLES =
            Arrays.asList(
                    "server-runtime",
                    "server-product",
                    "server-acceptance",
                    "client-product",
                    "client-acceptance");

    private MatrixAcceptanceReportV2() {
        // 工具类不实例化
    }

    /** 是否声明了 R1–R7 矩阵（有则走矩阵 v2 路径）。 */
    public static boolean matrixModeActive() {
        String matrix = System.getProperty(MATRIX_PROPERTY);
        return matrix != null && MATRICES.contains(matrix.trim());
    }

    /** 启动期严格校验本轮身份、矩阵、Java 与五类制品路径。 */
    public static void validateRequiredProperties() {
        requiredSingleLine(RUN_ID_PROPERTY);
        String matrix = requiredSingleLine(MATRIX_PROPERTY);
        if (!MATRICES.contains(matrix)) {
            throw new IllegalArgumentException("未知验收矩阵：" + matrix);
        }
        long startEpochMs = parseLong(START_EPOCH_PROPERTY);
        if (startEpochMs <= 0L) {
            throw new IllegalArgumentException("验收启动时间必须为正数");
        }
        requiredSingleLine(JAVA_EXECUTABLE_PROPERTY);
        for (String role : ARTIFACT_ROLES) {
            requiredSingleLine(ARTIFACT_PROPERTY_PREFIX + role);
        }
    }

    /** 激活后先删除旧报告，避免本轮失败时被旧文件冒充。 */
    public static void deleteOldReport() throws IOException {
        String reportPath = System.getProperty(REPORT_PROPERTY);
        if (reportPath == null || reportPath.trim().isEmpty()) {
            return;
        }
        Files.deleteIfExists(Paths.get(reportPath));
    }

    /** 正常场景结果装配为 v2；装配异常也只返回结构化 v2 失败报告。 */
    public static String renderReport(AcceptanceClient client, List<ScenarioResult> results) {
        JavaRuntimeInfo serverJava = null;
        try {
            serverJava =
                    AcceptanceReportV2Factory.currentJava(
                            requiredSingleLine(JAVA_EXECUTABLE_PROPERTY));
            AcceptanceReportV2 report =
                    AcceptanceReportV2Factory.create(
                            requiredSingleLine(RUN_ID_PROPERTY),
                            requiredSingleLine(MATRIX_PROPERTY),
                            parseLong(START_EPOCH_PROPERTY),
                            serverJava,
                            requiredClientJava(client),
                            artifactPath("server-runtime"),
                            artifactPath("server-product"),
                            artifactPath("server-acceptance"),
                            artifactPath("client-product"),
                            artifactPath("client-acceptance"),
                            results);
            return AcceptanceReportV2Renderer.render(report);
        } catch (IOException | RuntimeException e) {
            return renderFailure(client, serverJava, "装配 v2 验收报告失败：" + describe(e));
        }
    }

    /** 看门狗、启动装配或驱动异常统一渲染为 v2 RESULT FAIL。 */
    public static String renderFailure(AcceptanceClient client, String message) {
        return renderFailure(client, serverJavaOrNull(), message);
    }

    private static String renderFailure(
            AcceptanceClient client, JavaRuntimeInfo serverJava, String message) {
        AcceptanceReportV2 failure =
                AcceptanceReportV2Factory.failure(
                        System.getProperty(RUN_ID_PROPERTY),
                        System.getProperty(MATRIX_PROPERTY),
                        startEpochOrInvalid(),
                        serverJava,
                        client == null ? null : client.clientJava(),
                        message);
        return AcceptanceReportV2Renderer.render(failure);
    }

    public static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : "：" + message);
    }

    private static JavaRuntimeInfo requiredClientJava(AcceptanceClient client) {
        JavaRuntimeInfo clientJava = client == null ? null : client.clientJava();
        if (clientJava == null) {
            throw new IllegalStateException("客户端未上报 Java 运行身份");
        }
        return clientJava;
    }

    private static Path artifactPath(String role) {
        return Paths.get(requiredSingleLine(ARTIFACT_PROPERTY_PREFIX + role));
    }

    private static String requiredSingleLine(String name) {
        String value = System.getProperty(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少系统属性 -D" + name);
        }
        if (value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("系统属性不得包含换行或制表符：" + name);
        }
        return value;
    }

    private static long parseLong(String name) {
        try {
            return Long.parseLong(requiredSingleLine(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("系统属性必须为整数：" + name, e);
        }
    }

    private static long startEpochOrInvalid() {
        try {
            return Long.parseLong(System.getProperty(START_EPOCH_PROPERTY, "-1"));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static JavaRuntimeInfo serverJavaOrNull() {
        try {
            return AcceptanceReportV2Factory.currentJava(
                    System.getProperty(JAVA_EXECUTABLE_PROPERTY));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
