package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client;

/**
 * 客户端验证器（ADR-0014）：对某场景（{@link #scenarioId}）的某步在客户端 tick 线程做断言。
 *
 * <p>{@link #poll} 每 tick 调用一次：返回 {@code null} 表示本 tick 尚不可判定（下 tick 再试），
 * 返回非空表示已判定（OK/FAIL/ERROR），伴侣据此回报 StepResult。各验证器经 {@code ServiceLoader} 注册。
 */
public interface ClientVerifier {

    /** 与服务端场景一致的 scenarioId。 */
    String scenarioId();

    /** 在客户端 tick 线程轮询判定；{@code null}=本 tick 未判定。 */
    VerifyOutcome poll(VerifyStep step, RealServerClientContext context);
}
