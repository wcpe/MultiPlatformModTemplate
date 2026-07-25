package top.wcpe.mc.mpmt.acceptance.gametest;

/** 服务端 GameTest 断言失败（计为 FAIL）。 */
public final class ServerGameTestAssertionError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServerGameTestAssertionError(String message) {
        super(message);
    }
}
