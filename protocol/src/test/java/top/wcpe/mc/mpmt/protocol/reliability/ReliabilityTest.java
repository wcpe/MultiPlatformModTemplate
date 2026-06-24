package top.wcpe.mc.mpmt.protocol.reliability;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.wcpe.mc.mpmt.protocol.packet.FragmentPacket;

/** 可靠性层：分片 → 重组往返 + 乱序 + CRC 检出 + 超时清理（FR-24，testing-and-quality §2）。 */
class ReliabilityTest {

    private static final int MAX_CHUNK = 16;

    private static byte[] data(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 31 + 7);
        }
        return b;
    }

    private static byte[] reassembleAll(Reassembler r, List<FragmentPacket> fragments) {
        byte[] result = null;
        for (FragmentPacket f : fragments) {
            Optional<byte[]> out = r.accept(f);
            if (out.isPresent()) {
                result = out.get();
            }
        }
        return result;
    }

    @ParameterizedTest
    @DisplayName("分片→重组往返一致：覆盖空 / 不足一片 / 整除 / 带余 / 大包")
    @ValueSource(ints = {0, 1, 15, 16, 17, 48, 50, 100000})
    void 分片重组往返(int size) {
        byte[] payload = data(size);
        List<FragmentPacket> fragments = new Fragmenter().split(7, payload, MAX_CHUNK);
        byte[] reassembled = reassembleAll(new Reassembler(1000L), fragments);
        assertArrayEquals(payload, reassembled);
    }

    @Test
    @DisplayName("乱序到达仍可重组")
    void 乱序重组() {
        byte[] payload = data(50);
        List<FragmentPacket> fragments = new ArrayList<>(new Fragmenter().split(1, payload, MAX_CHUNK));
        Collections.reverse(fragments);
        byte[] reassembled = reassembleAll(new Reassembler(1000L), fragments);
        assertArrayEquals(payload, reassembled);
    }

    @Test
    @DisplayName("单片：载荷不超上限时一片即重组")
    void 单片立即重组() {
        byte[] payload = data(10);
        List<FragmentPacket> fragments = new Fragmenter().split(1, payload, MAX_CHUNK);
        assertEquals(1, fragments.size());
        assertArrayEquals(payload, new Reassembler(1000L).accept(fragments.get(0)).orElse(null));
    }

    @Test
    @DisplayName("CRC 不符：篡改某片载荷后重组被丢弃")
    void crc检出篡改() {
        byte[] payload = data(50);
        List<FragmentPacket> fragments = new ArrayList<>(new Fragmenter().split(1, payload, MAX_CHUNK));
        // 篡改第 0 片载荷，但保留原 crc（模拟传输损坏）
        FragmentPacket original = fragments.get(0);
        byte[] tampered = original.getPayload().clone();
        tampered[0] = (byte) (tampered[0] ^ 0xFF);
        fragments.set(0, new FragmentPacket(original.getSeqId(), original.getIndex(),
                original.getTotal(), original.getCrc32(), tampered));

        Reassembler r = new Reassembler(1000L);
        byte[] result = reassembleAll(r, fragments);
        // 集齐但 CRC 不符 → 丢弃，无完整结果产出
        org.junit.jupiter.api.Assertions.assertNull(result);
    }

    @Test
    @DisplayName("超时清理：未集齐的分组过期后被清除")
    void 超时清理() {
        byte[] payload = data(50);
        List<FragmentPacket> fragments = new Fragmenter().split(1, payload, MAX_CHUNK);
        AtomicLong clock = new AtomicLong(0L);
        Reassembler r = new Reassembler(1000L, clock::get);

        r.accept(fragments.get(0));
        assertTrue(r.pendingCount() > 0, "应有待重组分组");

        clock.set(2000L);
        r.tickTimeouts();
        assertEquals(0, r.pendingCount(), "超时分组应被清理");

        // 未超时则不清理
        r.accept(fragments.get(0));
        r.tickTimeouts();
        assertFalse(r.pendingCount() == 0, "未超时不应被清理");
    }
}
