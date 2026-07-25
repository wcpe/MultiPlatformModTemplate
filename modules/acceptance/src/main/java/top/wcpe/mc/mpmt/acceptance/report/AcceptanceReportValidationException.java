package top.wcpe.mc.mpmt.acceptance.report;

/** v2 报告格式或严格校验失败。 */
public final class AcceptanceReportValidationException extends IllegalArgumentException {

    public AcceptanceReportValidationException(String message) {
        super(message);
    }

    public AcceptanceReportValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
