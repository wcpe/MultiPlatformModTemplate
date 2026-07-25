package top.wcpe.mc.mpmt.core.domain.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine.State;

/** 握手状态机迁移穷举（正常 / 拒绝 / 封禁 / 非法迁移，对应 testing-and-quality §2「握手」）。 */
class HandshakeStateMachineTest {

    @Test
    @DisplayName("兼容且未封禁：CONNECTED → HELLO_OK → ESTABLISHED")
    void 兼容路径() {
        HandshakeStateMachine sm = new HandshakeStateMachine();
        assertEquals(State.CONNECTED, sm.state());
        assertEquals(State.HELLO_OK, sm.onClientHello(true));
        assertEquals(State.ESTABLISHED, sm.onClientId(false));
    }

    @Test
    @DisplayName("不兼容：CONNECTED → REJECTED")
    void 不兼容被拒() {
        HandshakeStateMachine sm = new HandshakeStateMachine();
        assertEquals(State.REJECTED, sm.onClientHello(false));
    }

    @Test
    @DisplayName("被封禁：HELLO_OK → REJECTED")
    void 封禁被拒() {
        HandshakeStateMachine sm = new HandshakeStateMachine();
        sm.onClientHello(true);
        assertEquals(State.REJECTED, sm.onClientId(true));
    }

    @Test
    @DisplayName("非法迁移：未问候不能上报标识、问候后不能重复问候、被拒后不能再推进")
    void 非法迁移快速失败() {
        HandshakeStateMachine fresh = new HandshakeStateMachine();
        assertThrows(IllegalStateException.class, () -> fresh.onClientId(false));

        HandshakeStateMachine helloOk = new HandshakeStateMachine();
        helloOk.onClientHello(true);
        assertThrows(IllegalStateException.class, () -> helloOk.onClientHello(true));

        HandshakeStateMachine rejected = new HandshakeStateMachine();
        rejected.onClientHello(false);
        assertThrows(IllegalStateException.class, () -> rejected.onClientId(false));
    }
}
