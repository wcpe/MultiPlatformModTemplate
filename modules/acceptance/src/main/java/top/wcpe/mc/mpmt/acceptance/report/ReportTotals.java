package top.wcpe.mc.mpmt.acceptance.report;

import java.util.List;
import lombok.Value;

/** v2 报告声明的场景汇总。 */
@Value
public class ReportTotals {

    int total;
    int pass;
    int fail;
    int error;
    int skip;

    public ReportTotals(int total, int pass, int fail, int error, int skip) {
        if (total < 0 || pass < 0 || fail < 0 || error < 0 || skip < 0) {
            throw new IllegalArgumentException("TOTAL 各计数不能为负数");
        }
        this.total = total;
        this.pass = pass;
        this.fail = fail;
        this.error = error;
        this.skip = skip;
    }

    static ReportTotals from(List<ReportScenario> scenarios) {
        int pass = 0;
        int fail = 0;
        int error = 0;
        int skip = 0;
        for (ReportScenario scenario : scenarios) {
            switch (scenario.getStatus()) {
                case PASS:
                    pass++;
                    break;
                case FAIL:
                    fail++;
                    break;
                case ERROR:
                    error++;
                    break;
                case SKIP:
                    skip++;
                    break;
                default:
                    throw new IllegalStateException("未知场景状态：" + scenario.getStatus());
            }
        }
        return new ReportTotals(scenarios.size(), pass, fail, error, skip);
    }

    boolean isPass() {
        return total > 0 && fail == 0 && error == 0;
    }

    String toReportLine() {
        return "TOTAL " + total + " PASS " + pass + " FAIL " + fail + " ERROR " + error + " SKIP " + skip;
    }
}
