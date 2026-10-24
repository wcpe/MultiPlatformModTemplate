package top.wcpe.mc.mpmt.acceptance.gametest;

import java.util.Objects;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepStatus;

/**
 * 验收场景基类（ADR-0014）：在 {@link ServerGameTest} 之上提供"等客户端就绪 + 给客户端排程一步验证"能力。
 *
 * <p>{@link AcceptanceClient} 由引导层在登记前经 {@link #bindClient} 注入（避免静态可变单例）。
 * 子类 {@link #run} 内：{@link #awaitClientReady()} → 切主线程搭环境 / 驱动 API → {@link #runClientStep} 联调客户端。
 */
public abstract class ServerScenario implements ServerGameTest {

    /**
     * 默认等客户端就绪超时（毫秒）。
     *
     * <p>realserver 客户端冷启动（资源重载 + TitleScreen/quickPlay）在慢机上常超过 3 分钟；
     * Forge 1.12.2 冷启动另含音效/材质图集，实测可到约 11 分钟。首场景若仅等 360s 会在
     * HYBRID CatServer 上「等客户端连入超时」。与真服看门狗 DEADLINE_MS（≥20 分钟）对齐，默认 15 分钟。
     */
    public static final long DEFAULT_CLIENT_READY_TIMEOUT_MS = 900_000L;
    /** 默认单步客户端验证超时（毫秒）。 */
    public static final long DEFAULT_CLIENT_STEP_TIMEOUT_MS = 30_000L;

    private AcceptanceClient client;

    /** 由引导层注入排程客户端（登记前调用一次）。 */
    public final void bindClient(AcceptanceClient client) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    /** 取排程客户端；未绑定即失败快。 */
    protected final AcceptanceClient client() {
        if (client == null) {
            throw new IllegalStateException("AcceptanceClient 未绑定（应由引导层在登记前 bindClient）");
        }
        return client;
    }

    /** 等客户端连入并通道就绪；超时即 FAIL。 */
    protected final void awaitClientReady() {
        awaitClientReady(DEFAULT_CLIENT_READY_TIMEOUT_MS);
    }

    /** 等客户端连入并通道就绪（自定超时）；超时即 FAIL。 */
    protected final void awaitClientReady(long timeoutMs) {
        if (!client().awaitReady(timeoutMs)) {
            throw new ServerGameTestAssertionError("等客户端连入超时 timeoutMs=" + timeoutMs);
        }
    }

    /** 给客户端排程一步验证并阻塞等回报；非 OK 即 FAIL。 */
    protected final StepResultPacket runClientStep(String stepId, String paramsJson) {
        return runClientStep(stepId, paramsJson, DEFAULT_CLIENT_STEP_TIMEOUT_MS);
    }

    /** 给客户端排程一步验证（自定超时）并阻塞等回报；非 OK 即 FAIL。 */
    protected final StepResultPacket runClientStep(String stepId, String paramsJson, long timeoutMs) {
        StepResultPacket result = client().runStep(id(), stepId, paramsJson, timeoutMs);
        if (result.getStatus() != StepStatus.OK) {
            throw new ServerGameTestAssertionError(
                    "客户端步骤未通过 step=" + stepId + " status=" + result.getStatus() + " msg=" + result.getMessage());
        }
        return result;
    }

    /**
     * 默认的「服务端已观测到产品握手 ESTABLISHED」等待（N 次 tick 超时）。
     *
     * <p>握手验收若只让客户端自证，同一份 PASS 报告在不同车道的含义会不一致：
     * 能读到服务端状态机的车道实际验了两端，读不到的只验了客户端。
     * 子类应覆写本方法，用本平台的产品网络特性走
     * {@code ServerNetworkFeature.handshakeService().stateOf(connection)} 做服务端断言。
     *
     * <p>默认实现为空操作，供确实无法访问产品服务端状态的平台（如跨类加载器分 jar 的宿主）沿用；
     * 覆写方应在等待失败时通过 {@code context.assertTrue} 记为 FAIL。
     *
     * @param context 服务端上下文
     */
    protected void awaitServerHandshakeEstablished(ServerGameTestContext context) {
        // 默认不额外断言：平台无法访问产品服务端状态时保持既有行为
    }
}
