package top.wcpe.mc.mpmt.acceptance.gametest;

/** 服务端 GameTest 跳过（计为 SKIP，不计失败）。 */
public final class ServerGameTestSkipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServerGameTestSkipException(String message) {
        super(message);
    }
}
