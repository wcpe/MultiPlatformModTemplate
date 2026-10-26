package top.wcpe.mc.mpmt.domain.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 共用键约定测试（capability 域）。 */
class CapabilityKeysTest {

    @Test
    void 首次加入键按前缀拼接玩家标识() {
        String uuid = UUID.randomUUID().toString();

        assertEquals("first-join:" + uuid, CapabilityKeys.firstJoinKey(uuid));
    }

    @Test
    void 命名空间与键前缀稳定() {
        assertEquals("capability-example", CapabilityKeys.NAMESPACE);
        assertEquals("first-join:", CapabilityKeys.FIRST_JOIN_PREFIX);
    }
}
