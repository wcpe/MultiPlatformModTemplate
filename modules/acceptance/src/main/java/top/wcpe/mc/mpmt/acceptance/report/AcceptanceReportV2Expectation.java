package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** validator 接收的本轮预期身份、JDK 与制品；required 场景由 R1-R6 矩阵固定决定。 */
public final class AcceptanceReportV2Expectation {

    private static final Set<String> REQUIRED_ARTIFACT_ROLES = requiredArtifactRoles();

    private final String runId;
    private final String matrix;
    private final long startEpochMs;
    private final JavaRuntimeInfo serverJava;
    private final JavaRuntimeInfo clientJava;
    private final List<ReportArtifact> artifacts;
    private final List<String> requiredScenarios;

    public AcceptanceReportV2Expectation(
            String runId,
            String matrix,
            long startEpochMs,
            JavaRuntimeInfo serverJava,
            JavaRuntimeInfo clientJava,
            List<ReportArtifact> artifacts) {
        this.runId = ReportValueChecks.requireSingleLine("预期 RUN_ID", runId);
        this.matrix = ReportValueChecks.requireSingleLine("预期 MATRIX", matrix);
        if (startEpochMs < 0L) {
            throw new IllegalArgumentException("预期启动时间不能为负数");
        }
        this.startEpochMs = startEpochMs;
        // required 场景清单以 MatrixScenarioCatalog 为单一真源
        this.requiredScenarios = MatrixScenarioCatalog.requiredFor(this.matrix);
        this.serverJava = Objects.requireNonNull(serverJava, "预期服务端 Java 信息不能为空");
        this.clientJava = Objects.requireNonNull(clientJava, "预期客户端 Java 信息不能为空");
        this.artifacts = immutableArtifacts(artifacts);
        requireArtifactRoles(this.artifacts);
    }

    private static List<ReportArtifact> immutableArtifacts(List<ReportArtifact> artifacts) {
        Objects.requireNonNull(artifacts, "预期制品不能为空");
        List<ReportArtifact> copy = new ArrayList<>(artifacts);
        Set<String> roles = new HashSet<>();
        for (ReportArtifact artifact : copy) {
            Objects.requireNonNull(artifact, "预期制品不能包含 null");
            if (!roles.add(artifact.getRole())) {
                throw new IllegalArgumentException("预期制品 role 重复：" + artifact.getRole());
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static void requireArtifactRoles(List<ReportArtifact> artifacts) {
        Set<String> roles = new HashSet<>();
        for (ReportArtifact artifact : artifacts) {
            roles.add(artifact.getRole());
        }
        if (!roles.containsAll(REQUIRED_ARTIFACT_ROLES)) {
            throw new IllegalArgumentException("预期制品缺少 v2 必需 role：" + REQUIRED_ARTIFACT_ROLES);
        }
    }

    private static Set<String> requiredArtifactRoles() {
        Set<String> roles = new HashSet<>();
        roles.add("server-runtime");
        roles.add("server-product");
        roles.add("server-acceptance");
        roles.add("client-product");
        roles.add("client-acceptance");
        return Collections.unmodifiableSet(roles);
    }

    public String getRunId() {
        return runId;
    }

    public String getMatrix() {
        return matrix;
    }

    public long getStartEpochMs() {
        return startEpochMs;
    }

    public JavaRuntimeInfo getServerJava() {
        return serverJava;
    }

    public JavaRuntimeInfo getClientJava() {
        return clientJava;
    }

    public List<ReportArtifact> getArtifacts() {
        return ReportValueChecks.immutableSnapshot(artifacts);
    }

    public List<String> getRequiredScenarios() {
        return ReportValueChecks.immutableSnapshot(requiredScenarios);
    }
}
