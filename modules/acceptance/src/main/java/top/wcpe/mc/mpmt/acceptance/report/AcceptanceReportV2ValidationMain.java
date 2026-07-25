package top.wcpe.mc.mpmt.acceptance.report;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** 供构建门禁调用的 v2 报告严格校验入口。 */
public final class AcceptanceReportV2ValidationMain {

    private static final int ARGUMENT_COUNT = 13;

    private AcceptanceReportV2ValidationMain() {
        // 工具类不实例化
    }

    public static void main(String[] args) throws IOException {
        if (args.length != ARGUMENT_COUNT) {
            throw new IllegalArgumentException("v2 校验参数数量错误：" + args.length);
        }
        Path reportFile = workspaceFile(args[0]);
        AcceptanceReportV2Expectation expectation =
                new AcceptanceReportV2Expectation(
                        args[1],
                        args[2],
                        Long.parseLong(args[3]),
                        new JavaRuntimeInfo(Integer.parseInt(args[4]), args[5]),
                        new JavaRuntimeInfo(Integer.parseInt(args[6]), args[7]),
                        artifacts(args));
        String report = new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8);
        long modifiedEpochMs = Files.getLastModifiedTime(reportFile).toMillis();
        AcceptanceReportV2Validator.validate(report, modifiedEpochMs, expectation);
    }

    private static List<ReportArtifact> artifacts(String[] args) throws IOException {
        return Arrays.asList(
                artifact("server-runtime", args[8]),
                artifact("server-product", args[9]),
                artifact("server-acceptance", args[10]),
                artifact("client-product", args[11]),
                artifact("client-acceptance", args[12]));
    }

    private static ReportArtifact artifact(String role, String path) throws IOException {
        return new ReportArtifact(role, AcceptanceReportV2Factory.sha256(workspaceFile(path)));
    }

    static Path workspaceFile(String path) throws IOException {
        Path workspaceRoot = new File(".").getCanonicalFile().toPath();
        Path candidate = new File(path).getCanonicalFile().toPath();
        if (!candidate.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("文件必须位于当前工作区内：" + path);
        }
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("文件不存在或不是普通文件：" + path);
        }
        return candidate;
    }
}
