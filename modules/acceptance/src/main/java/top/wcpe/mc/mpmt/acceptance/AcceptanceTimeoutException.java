package top.wcpe.mc.mpmt.acceptance;

/** 验收客户端排程超时 / 中断 / 收尾时抛出。 */
public final class AcceptanceTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AcceptanceTimeoutException(String message) {
        super(message);
    }
}
