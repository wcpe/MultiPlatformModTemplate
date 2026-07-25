package top.wcpe.mc.mpmt.protocol.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.wcpe.mc.mpmt.protocol.ProtocolException;

/** 编解码原语往返一致与非法 / 截断输入处理（锁定字节布局，FR-04 验收）。 */
class CodecPrimitivesTest {

    @ParameterizedTest
    @DisplayName("VarInt 往返：边界与负值")
    @ValueSource(ints = {0, 1, 127, 128, 255, 256, 16383, 16384, 2097151, 2097152, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
    void varint往返(int value) {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeVarInt(value);
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals(value, reader.readVarInt());
    }

    @ParameterizedTest
    @DisplayName("定长 int 往返：边界（4 字节大端）")
    @ValueSource(ints = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x1234ABCD})
    void int往返(int value) {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeInt(value);
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals(value, reader.readInt());
        assertEquals(0, reader.remaining());
    }

    @ParameterizedTest
    @DisplayName("long 往返：边界")
    @ValueSource(longs = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
    void long往返(long value) {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeLong(value);
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals(value, reader.readLong());
    }

    @Test
    @DisplayName("UTF 往返：含多字节字符与空串")
    void utf往返() {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeUtf("héllo-你好-😀").writeUtf("");
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals("héllo-你好-😀", reader.readUtf());
        assertEquals("", reader.readUtf());
    }

    @Test
    @DisplayName("boolean / bytes 往返")
    void boolean与bytes往返() {
        byte[] payload = {0, 1, 2, -1, 127, -128};
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeBoolean(true).writeBoolean(false).writeBytes(payload);
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals(true, reader.readBoolean());
        assertEquals(false, reader.readBoolean());
        assertArrayEquals(payload, reader.readBytes());
    }

    @Test
    @DisplayName("混合往返后无残留字节")
    void 混合往返无残留() {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeByte(0x80).writeVarInt(300).writeUtf("x").writeLong(42L).writeBoolean(true);
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertEquals(0x80, reader.readUnsignedByte());
        assertEquals(300, reader.readVarInt());
        assertEquals("x", reader.readUtf());
        assertEquals(42L, reader.readLong());
        assertEquals(true, reader.readBoolean());
        assertEquals(0, reader.remaining());
    }

    @Test
    @DisplayName("截断 readVarInt：抛 ProtocolException")
    void 截断varint_抛异常() {
        ProtocolBufReader reader = new ByteArrayProtocolReader(new byte[0]);
        assertThrows(ProtocolException.class, reader::readVarInt);
    }

    @Test
    @DisplayName("越界 readLong：抛 ProtocolException")
    void 越界long_抛异常() {
        ProtocolBufReader reader = new ByteArrayProtocolReader(new byte[] {1, 2, 3});
        assertThrows(ProtocolException.class, reader::readLong);
    }

    @Test
    @DisplayName("VarInt 过长（>5 字节）：抛 ProtocolException")
    void varint过长_抛异常() {
        byte[] illegal = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x01};
        ProtocolBufReader reader = new ByteArrayProtocolReader(illegal);
        assertThrows(ProtocolException.class, reader::readVarInt);
    }

    @Test
    @DisplayName("非法长度前缀（声明超长）：抛 ProtocolException 而非 OOM")
    void 非法长度前缀_抛异常() {
        ByteArrayProtocolWriter writer = new ByteArrayProtocolWriter();
        writer.writeVarInt(Integer.MAX_VALUE); // 声明超大长度但无后续数据
        ProtocolBufReader reader = new ByteArrayProtocolReader(writer.toByteArray());
        assertThrows(ProtocolException.class, reader::readUtf);
    }
}
