package top.wcpe.mc.mpmt.acceptance.gametest;

/**
 * 服务端 GameTest（ADR-0014）：一个由服务端驱动的验收用例。
 *
 * <p>{@link #run} 在驱动线程执行：经 {@link ServerGameTestContext} 切主线程搭环境 / 驱动真实 API / 断言。
 * 抛 {@link ServerGameTestSkipException}=SKIP、{@link ServerGameTestAssertionError}=FAIL、其他异常=ERROR。
 */
public interface ServerGameTest {

    /** 套件名（如 {@code acceptance}）。 */
    String suite();

    /** 套件内唯一 id。 */
    String id();

    /** 执行用例。 */
    void run(ServerGameTestContext context);
}
