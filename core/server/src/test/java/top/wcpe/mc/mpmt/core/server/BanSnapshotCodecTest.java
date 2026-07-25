package top.wcpe.mc.mpmt.core.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ban.BanEntry;
import top.wcpe.mc.mpmt.core.domain.ban.MachineCode;

/** 封禁快照格式的确定性、版本与异常输入测试。 */
class BanSnapshotCodecTest {

    private final BanSnapshotCodec codec = new BanSnapshotCodec();

    @Test
    @DisplayName("编码按机器码排序并使用版本化 Base64 行格式")
    void 确定性编码() {
        List<BanEntry> entries = Arrays.asList(entry("z", "末尾"), entry("a", "首行\n原因"));

        String encoded = codec.encode(entries);

        assertEquals(
                "v1\n" + base64("a") + "\t" + base64("首行\n原因") + "\n" + base64("z") + "\t" + base64("末尾"),
                encoded);
        assertEquals(encoded, codec.encode(Arrays.asList(entries.get(1), entries.get(0))));
    }

    @Test
    @DisplayName("编码后解码保留全部条目")
    void 往返一致() {
        List<BanEntry> decoded = codec.decode(codec.encode(Arrays.asList(entry("b", "原因二"), entry("a", "原因一"))));

        assertEquals(Arrays.asList(entry("a", "原因一"), entry("b", "原因二")), decoded);
    }

    @Test
    @DisplayName("未知版本或损坏行明确失败")
    void 非法快照失败() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("v2"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("v1\nnot-base64\talso-bad"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("v1\n" + base64("a")));
    }

    private static BanEntry entry(String code, String reason) {
        return new BanEntry(new MachineCode(code), reason);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
