package top.wcpe.mc.mpmt.acceptance.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** v2 报告值对象的公共输入约束。 */
final class ReportValueChecks {

    private ReportValueChecks() {
        // 工具类不实例化
    }

    static String requireSingleLine(String name, String value) {
        Objects.requireNonNull(value, name + " 不能为空");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空串");
        }
        if (value.indexOf('\t') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " 不能包含制表符或换行");
        }
        return value;
    }

    static <T> List<T> immutableSnapshot(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    static String requireSha256(String value) {
        requireSingleLine("sha256", value);
        if (value.length() != 64) {
            throw new IllegalArgumentException("sha256 必须为 64 位小写十六进制");
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (!isLowerHex(digit)) {
                throw new IllegalArgumentException("sha256 必须为 64 位小写十六进制");
            }
        }
        return value;
    }

    private static boolean isLowerHex(char value) {
        return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
    }
}
