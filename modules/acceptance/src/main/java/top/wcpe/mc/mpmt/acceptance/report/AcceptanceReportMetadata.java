package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 权威验收报告元数据；字段均不能为空，场景清单按调用方提供的稳定顺序保存。 */
public final class AcceptanceReportMetadata {

    private final String commit;
    private final String version;
    private final String platform;
    private final String mcVersion;
    private final String serverVersion;
    private final String productJarSha256;
    private final List<String> scenarios;

    public AcceptanceReportMetadata(
            String commit,
            String version,
            String platform,
            String mcVersion,
            String serverVersion,
            String productJarSha256,
            List<String> scenarios) {
        this.commit = requireLineValue(commit, "commit");
        this.version = requireLineValue(version, "VERSION");
        this.platform = requireLineValue(platform, "platform");
        this.mcVersion = requireLineValue(mcVersion, "mcVersion");
        this.serverVersion = requireLineValue(serverVersion, "serverVersion");
        this.productJarSha256 = requireSha256(productJarSha256);
        this.scenarios = copyScenarios(scenarios);
    }

    private static String requireLineValue(String value, String name) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.trim().isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " 必须是非空单行文本");
        }
        return value;
    }

    private static String requireSha256(String value) {
        String sha256 = requireLineValue(value, "productJarSha256");
        if (!sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("productJarSha256 必须是 64 位十六进制");
        }
        return sha256.toLowerCase(java.util.Locale.ROOT);
    }

    private static List<String> copyScenarios(List<String> scenarios) {
        Objects.requireNonNull(scenarios, "scenarios 不能为空");
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("scenarios 不能为空");
        }
        List<String> copy = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (String scenario : scenarios) {
            String value = requireLineValue(scenario, "scenario");
            if (value.indexOf(',') >= 0 || !unique.add(value)) {
                throw new IllegalArgumentException("scenario 非法或重复：" + value);
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    public String getCommit() {
        return commit;
    }

    public String getVersion() {
        return version;
    }

    public String getPlatform() {
        return platform;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public String getProductJarSha256() {
        return productJarSha256;
    }

    public List<String> getScenarios() {
        return Collections.unmodifiableList(new ArrayList<>(scenarios));
    }
}
