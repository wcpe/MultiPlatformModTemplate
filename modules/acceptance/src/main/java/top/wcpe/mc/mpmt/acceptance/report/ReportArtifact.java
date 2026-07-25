package top.wcpe.mc.mpmt.acceptance.report;

import lombok.Value;

/** 受控制品角色及其 SHA-256。 */
@Value
public class ReportArtifact {

    String role;
    String sha256;

    public ReportArtifact(String role, String sha256) {
        this.role = ReportValueChecks.requireSingleLine("制品 role", role);
        this.sha256 = ReportValueChecks.requireSha256(sha256);
    }
}
