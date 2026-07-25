package top.wcpe.mc.mpmt.acceptance.report;

import lombok.Value;

/** 报告绑定的单端 Java 主版本与可执行文件。 */
@Value
public class JavaRuntimeInfo {

    int major;
    String executable;

    public JavaRuntimeInfo(int major, String executable) {
        if (major <= 0) {
            throw new IllegalArgumentException("Java 主版本必须为正数：" + major);
        }
        this.major = major;
        this.executable = ReportValueChecks.requireSingleLine("Java executable", executable);
    }
}
