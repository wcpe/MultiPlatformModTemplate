package top.wcpe.mc.mpmt.core.domain.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine.State;

/** 握手状态机迁移穷举（正常 / 拒绝 / 非法迁移，对应 testing-and-quality §2「握手」）。 */
class HandshakeStateMachineTest {

    @Test
    @DisplayName("兼容路径：CONNECTED → HELLO_OK → ESTABLISHED")
    void 兼容路径() {
        HandshakeStateMachine sm = new HandshakeStateMachine();
        assertEquals(State.CONNECTED, sm.state());
        assertEquals(State.HELLO_OK, sm.onClientHello(true));
        assertEquals(State.ESTABLISHED, sm.onEstablished());
    }

    @Test
    @DisplayName("不兼容：CONNECTED → REJECTED")
    void 不兼容被拒() {
        HandshakeStateMachine sm = new HandshakeStateMachine();
        assertEquals(State.REJECTED, sm.onClientHello(false));
    }

    @Test
    @DisplayName("非法迁移：被拒后不能再握手、未问候不能建立")
    void 非法迁移快速失败() {
        HandshakeStateMachine rejected = new HandshakeStateMachine();
        rejected.onClientHello(false);
        assertThrows(IllegalStateException.class, () -> rejected.onClientHello(true));
        assertThrows(IllegalStateException.class, rejected::onEstablished);

        HandshakeStateMachine fresh = new HandshakeStateMachine();
        assertThrows(IllegalStateException.class, fresh::onEstablished);

        HandshakeStateMachine helloOk = new HandshakeStateMachine();
        helloOk.onClientHello(true);
        assertThrows(IllegalStateException.class, () -> helloOk.onClientHello(true));
    }
}
