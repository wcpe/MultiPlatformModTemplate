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
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
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
    @DisplayName("不同连接相同 seqId 的分片互不干扰")
    void 不同连接同序列隔离() {
        ConnectionHandle firstConnection = new EqualConnection(1);
        ConnectionHandle secondConnection = new EqualConnection(1);
        List<FragmentPacket> first = new Fragmenter().split(9, data(40), MAX_CHUNK);
        List<FragmentPacket> second = new Fragmenter().split(9, data(41), MAX_CHUNK);
        Reassembler reassembler = new Reassembler(1000L);
        byte[] firstResult = null;
        byte[] secondResult = null;

        for (int index = 0; index < first.size(); index++) {
            firstResult = resultOrPrevious(reassembler.accept(firstConnection, first.get(index)), firstResult);
            secondResult = resultOrPrevious(reassembler.accept(secondConnection, second.get(index)), secondResult);
        }

        assertArrayEquals(data(40), firstResult);
        assertArrayEquals(data(41), secondResult);
    }

    @Test
    @DisplayName("旧连接迟到断线不得清除同标识的新物理连接分组")
    void 旧连接断线不清新连接() {
        ConnectionHandle oldConnection = new EqualConnection(2);
        ConnectionHandle newConnection = new EqualConnection(2);
        List<FragmentPacket> fragments = new Fragmenter().split(10, data(40), MAX_CHUNK);
        Reassembler reassembler = new Reassembler(1000L);

        reassembler.accept(newConnection, fragments.get(0));
        reassembler.clearConnection(oldConnection);
        byte[] result = reassembleRemaining(reassembler, newConnection, fragments);

        assertArrayEquals(data(40), result);
    }

    @Test
    @DisplayName("同一分组 total 或 crc 元数据不一致时拒绝整组")
    void 元数据不一致拒绝() {
        List<FragmentPacket> fragments = new Fragmenter().split(3, data(40), MAX_CHUNK);
        FragmentPacket first = fragments.get(0);

        assertMetadataMismatchRejected(first,
                new FragmentPacket(3, 1, first.getTotal() + 1, first.getCrc32(), data(1)));
        assertMetadataMismatchRejected(first,
                new FragmentPacket(3, 1, first.getTotal(), first.getCrc32() + 1, data(1)));
    }

    private static byte[] resultOrPrevious(Optional<byte[]> result, byte[] previous) {
        return result.isPresent() ? result.get() : previous;
    }

    private static void assertMetadataMismatchRejected(FragmentPacket first, FragmentPacket inconsistent) {
        Reassembler reassembler = new Reassembler(1000L);
        reassembler.accept(first);

        assertFalse(reassembler.accept(inconsistent).isPresent());
        assertEquals(0, reassembler.pendingCount(), "元数据冲突后应丢弃整组");
    }

    @Test
    @DisplayName("重组资源上限：拒绝过大 total、单片载荷与总缓冲占用")
    void 重组资源上限() {
        AtomicLong clock = new AtomicLong(0L);
        Reassembler reassembler = limitedReassembler(clock, 2, 4, 6);
        ConnectionHandle connection = new ConnectionHandle() { };

        reassembler.accept(connection, new FragmentPacket(1, 0, 3, 1, data(1)));
        assertEquals(0, reassembler.pendingCount(), "超过 total 上限不得建组");
        reassembler.accept(connection, new FragmentPacket(2, 0, 2, 1, data(5)));
        assertEquals(0, reassembler.pendingCount(), "超过单片载荷上限不得建组");

        reassembler.accept(connection, new FragmentPacket(3, 0, 2, 1, data(4)));
        reassembler.accept(connection, new FragmentPacket(4, 0, 2, 1, data(4)));
        assertEquals(1, reassembler.pendingCount(), "超过总缓冲上限的新组应被拒绝");
        assertEquals(4, reassembler.bufferedBytes());
    }

    @Test
    @DisplayName("完成组短期去重，去重期限后允许复用序列号")
    void 完成组短期去重() {
        AtomicLong clock = new AtomicLong(0L);
        Reassembler reassembler = limitedReassembler(clock, 2, 16, 32);
        byte[] payload = data(4);
        FragmentPacket fragment = new FragmentPacket(5, 0, 1, Fragmenter.crc32(payload), payload);

        assertTrue(reassembler.accept(fragment).isPresent());
        assertFalse(reassembler.accept(fragment).isPresent(), "完成组期限内不得重复产出");
        clock.set(1001L);
        reassembler.tickTimeouts();
        assertTrue(reassembler.accept(fragment).isPresent(), "去重期限后应允许序列号复用");
    }

    private static Reassembler limitedReassembler(
            AtomicLong clock, int maxTotal, int maxPayloadBytes, int maxBufferedBytes) {
        return new Reassembler(
                1000L, 1000L, maxTotal, maxPayloadBytes, maxBufferedBytes,
                clock::get, (connection, seqId, timeoutCount) -> { });
    }

    private static byte[] reassembleRemaining(
            Reassembler reassembler, ConnectionHandle connection, List<FragmentPacket> fragments) {
        byte[] result = null;
        for (int index = 1; index < fragments.size(); index++) {
            result = resultOrPrevious(reassembler.accept(connection, fragments.get(index)), result);
        }
        return result;
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

    /** equals 相同但对象身份不同的物理连接。 */
    private static final class EqualConnection implements ConnectionHandle {
        private final int id;

        EqualConnection(int id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualConnection && id == ((EqualConnection) other).id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }
}
