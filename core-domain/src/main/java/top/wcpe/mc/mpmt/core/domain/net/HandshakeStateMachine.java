package top.wcpe.mc.mpmt.core.domain.net;

/**
 * 握手状态机（L0 纯逻辑，可穷举单测）：{@code CONNECTED → HELLO_OK / REJECTED → ESTABLISHED}。
 *
 * <p>只表达状态迁移规则，不做 IO、不依赖协议层：版本是否兼容由 L1 服务用
 * {@code ProtocolVersion.isCompatible} 算好后以布尔传入（守 L0 不依赖 L1，ADR-0001）。非法迁移快速失败。
 */
public final class HandshakeStateMachine {

    /** 握手状态。 */
    public enum State {
        CONNECTED,
        HELLO_OK,
        ESTABLISHED,
        REJECTED
    }

    private State state = State.CONNECTED;

    /** 当前状态。 */
    public State state() {
        return state;
    }

    /**
     * 收到 ClientHello：按版本兼容性推进。
     *
     * @param compatible 版本是否兼容（由 L1 依 ProtocolVersion 判定后传入）
     * @return 新状态（兼容 → HELLO_OK；不兼容 → REJECTED）
     * @throws IllegalStateException 不在 CONNECTED 状态
     */
    public State onClientHello(boolean compatible) {
        if (state != State.CONNECTED) {
            throw new IllegalStateException("非法握手迁移：onClientHello 时状态为 " + state);
        }
        state = compatible ? State.HELLO_OK : State.REJECTED;
        return state;
    }

    /**
     * 握手完成、建立会话。
     *
     * @throws IllegalStateException 不在 HELLO_OK 状态
     */
    public State onEstablished() {
        if (state != State.HELLO_OK) {
            throw new IllegalStateException("非法握手迁移：onEstablished 时状态为 " + state);
        }
        state = State.ESTABLISHED;
        return state;
    }
}
