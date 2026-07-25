package top.wcpe.mc.mpmt.acceptance.report;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 从本轮真实运行身份、制品文件和场景结果装配 v2 报告。 */
public final class AcceptanceReportV2Factory {

    private AcceptanceReportV2Factory() {
        // 工具类不实例化
    }

    public static AcceptanceReportV2 create(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            Path serverRuntime,
            Path serverProduct,
            Path serverAcceptance,
            Path clientProduct,
            Path clientAcceptance,
            List<ScenarioResult> results) throws IOException {
        requireMetadata(runId, matrix, startEpochMs, serverJava, clientJava);
        List<ReportArtifact> artifacts = Arrays.asList(
                artifact("server-runtime", serverRuntime),
                artifact("server-product", serverProduct),
                artifact("server-acceptance", serverAcceptance),
                artifact("client-product", clientProduct),
                artifact("client-acceptance", clientAcceptance));
        return AcceptanceReportV2.create(
                runId,
                matrix,
                startEpochMs,
                serverJava,
                clientJava,
                artifacts,
                scenarios(results));
    }

    public static AcceptanceReportV2 failure(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            String message) {
        ReportScenario failure = new ReportScenario(
                "framework-report-v2", ScenarioStatus.ERROR, 0L, safeMessage(message));
        return AcceptanceReportV2.create(
                safeValue(runId, "missing-run-id"),
                safeValue(matrix, "INVALID"),
                Math.max(0L, startEpochMs),
                safeJava(serverJava, "missing-server-java"),
                safeJava(clientJava, "missing-client-java"),
                Collections.emptyList(),
                Collections.singletonList(failure));
    }

    /** 解析本进程 Java 主版本；{@code executable} 为空时回退到 {@code java.home}/bin/java。 */
    public static JavaRuntimeInfo currentJava(String executable) {
        String resolved = executable;
        if (resolved == null || resolved.isEmpty()) {
            String home = System.getProperty("java.home");
            resolved = (home == null || home.isEmpty())
                    ? "java"
                    : home + java.io.File.separator + "bin" + java.io.File.separator + "java";
        }
        return new JavaRuntimeInfo(javaMajor(System.getProperty("java.specification.version")), resolved);
    }

    public static String sha256(Path file) throws IOException {
        Objects.requireNonNull(file, "制品路径不能为空");
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return lowerHex(digest.digest());
    }

    private static void requireMetadata(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava) {
        ReportValueChecks.requireSingleLine("RUN_ID", runId);
        ReportValueChecks.requireSingleLine("MATRIX", matrix);
        if (!Arrays.asList("R1", "R2", "R3", "R4", "R5", "R6").contains(matrix)) {
            throw new IllegalArgumentException("未知 MATRIX：" + matrix);
        }
        if (startEpochMs <= 0L) {
            throw new IllegalArgumentException("START_EPOCH_MS 必须为正数");
        }
        Objects.requireNonNull(serverJava, "服务端 Java 信息不能为空");
        Objects.requireNonNull(clientJava, "客户端 Java 信息不能为空");
    }

    private static ReportArtifact artifact(String role, Path file) throws IOException {
        return new ReportArtifact(role, sha256(file));
    }

    private static List<ReportScenario> scenarios(List<ScenarioResult> results) {
        Objects.requireNonNull(results, "场景结果不能为空");
        List<ReportScenario> scenarios = new ArrayList<>(results.size());
        for (ScenarioResult result : results) {
            Objects.requireNonNull(result, "场景结果不能包含 null");
            scenarios.add(new ReportScenario(
                    result.getId(), result.getStatus(), result.getDurationMs(), result.getMessage()));
        }
        return scenarios;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 不支持 SHA-256", e);
        }
    }

    private static String lowerHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static int javaMajor(String specificationVersion) {
        if (specificationVersion == null || specificationVersion.isEmpty()) {
            throw new IllegalArgumentException("无法读取 java.specification.version");
        }
        String major = specificationVersion.startsWith("1.")
                ? specificationVersion.substring(2)
                : specificationVersion;
        int separator = major.indexOf('.');
        return Integer.parseInt(separator < 0 ? major : major.substring(0, separator));
    }

    private static JavaRuntimeInfo safeJava(JavaRuntimeInfo value, String missingExecutable) {
        return value == null ? new JavaRuntimeInfo(1, missingExecutable) : value;
    }

    private static String safeValue(String value, String fallback) {
        if (value == null || value.isEmpty()
                || value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return fallback;
        }
        return value;
    }

    private static String safeMessage(String message) {
        return message == null || message.isEmpty() ? "未知 v2 报告装配错误" : message;
    }
}
