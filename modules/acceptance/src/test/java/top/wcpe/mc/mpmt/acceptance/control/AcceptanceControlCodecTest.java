package top.wcpe.mc.mpmt.acceptance.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** 验收控制协议 codec 往返一致与非法输入处理（ADR-0014）。 */
class AcceptanceControlCodecTest {

    static List<AcceptanceControlPacket> samples() {
        return Arrays.asList(
                new ClientReadyPacket(2, 17, "C:/jdk-17/bin/java.exe"),
                new ClientReadyPacket(Integer.MAX_VALUE, 21, "/usr/bin/java"),
                new RunStepPacket("scenario", "step", "{\"k\":1}", 7),
                new RunStepPacket("场景-中文", "", "{}", 0),
                new StepResultPacket("s", "st", 3, StepStatus.OK, "{\"r\":true}", "通过"),
                new StepResultPacket("s", "st", 5, StepStatus.FAIL, "{}", "失败原因"),
                new StepResultPacket("s", "st", 9, StepStatus.ERROR, "{}", ""));
    }

    @Test
    @DisplayName("ClientReady v2 往返保留 Java 主版本与可执行文件")
    void clientReadyV2字段() {
        ClientReadyPacket packet = new ClientReadyPacket(2, 21, "D:/jdk-21/bin/java.exe");
        ClientReadyPacket decoded =
                (ClientReadyPacket) AcceptanceControlCodec.decode(AcceptanceControlCodec.encode(packet));
        assertEquals(2, decoded.getProtocolVersion());
        assertEquals(21, decoded.getJavaMajor());
        assertEquals("D:/jdk-21/bin/java.exe", decoded.getJavaExecutable());
        assertEquals(2, AcceptanceControlCodec.PROTOCOL_VERSION);
    }

    @ParameterizedTest
    @MethodSource("samples")
    @DisplayName("编解码往返一致：decode(encode(p)) 等于 p")
    void 往返一致(AcceptanceControlPacket packet) {
        assertEquals(packet, AcceptanceControlCodec.decode(AcceptanceControlCodec.encode(packet)));
    }

    @ParameterizedTest
    @MethodSource("samples")
    @DisplayName("字节稳定：两次编码字节完全一致")
    void 字节稳定(AcceptanceControlPacket packet) {
        assertArrayEquals(AcceptanceControlCodec.encode(packet), AcceptanceControlCodec.encode(packet));
    }

    @Test
    @DisplayName("未知类型码：抛 IllegalArgumentException")
    void 未知类型码() {
        assertThrows(IllegalArgumentException.class, () -> AcceptanceControlCodec.decode(new byte[] {99}));
    }

    @Test
    @DisplayName("截断字节：抛 IllegalArgumentException 而非崩溃")
    void 截断字节() {
        byte[] full = AcceptanceControlCodec.encode(new RunStepPacket("a", "b", "c", 1));
        byte[] truncated = Arrays.copyOf(full, full.length - 2);
        assertThrows(IllegalArgumentException.class, () -> AcceptanceControlCodec.decode(truncated));
    }

    @Test
    @DisplayName("空字节流：抛 IllegalArgumentException")
    void 空字节() {
        assertThrows(IllegalArgumentException.class, () -> AcceptanceControlCodec.decode(new byte[0]));
    }

    @Test
    @DisplayName("StepStatus 线缆码稳定可往返、未知码即拒")
    void 状态线缆码() {
        for (StepStatus status : StepStatus.values()) {
            assertSame(status, StepStatus.fromWire(status.wire()));
        }
        assertThrows(IllegalArgumentException.class, () -> StepStatus.fromWire(0));
        assertThrows(IllegalArgumentException.class, () -> StepStatus.fromWire(99));
    }
}
