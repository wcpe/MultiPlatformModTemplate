package top.wcpe.mc.mpmt.platform.forge.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ForgePayloadCodec} 的 1.12.2 自定义负载外层编解码。
 *
 * <p>本类纯字节搬运、不触达客户端运行态，故可在纯 JVM 下用真实 {@code PacketBuffer} 验证。
 */
class ForgePayloadCodecTest {

    @Test
    @DisplayName("出站：只包装通道名，内部负载原样透传")
    void 出站负载原样透传() {
        byte[] data = {1, 2, 3, -1, 0, 127};

        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                ForgePayloadCodec.outgoing("MPMT", data);

        assertEquals("MPMT", packet.channel());
        assertArrayEquals(data, ForgePayloadCodec.incoming(packet));
    }

    @Test
    @DisplayName("出站：空负载也保留为零长度缓冲区而非丢失")
    void 出站空负载可往返() {
        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                ForgePayloadCodec.outgoing("MPMT", new byte[0]);

        assertEquals(0, ForgePayloadCodec.incoming(packet).length);
    }

    @Test
    @DisplayName("出站：复制入参，调用方事后改数组不影响已构造负载")
    void 出站复制入参() {
        byte[] data = {9, 9, 9};

        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                ForgePayloadCodec.outgoing("MPMT", data);
        data[0] = 0;

        assertArrayEquals(new byte[] {9, 9, 9}, ForgePayloadCodec.incoming(packet));
    }

    @Test
    @DisplayName("入站：读取不推进读指针，可重复解出同一负载")
    void 入站可重复读取() {
        byte[] data = {4, 5, 6};
        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                ForgePayloadCodec.outgoing("MPMT", data);

        byte[] first = ForgePayloadCodec.incoming(packet);
        byte[] second = ForgePayloadCodec.incoming(packet);

        assertArrayEquals(first, second);
        assertArrayEquals(data, second);
        assertNotSame(first, second, "每次应产出独立数组，避免调用方互相污染");
    }

    @Test
    @DisplayName("入站：读指针已推进时仍按 readerIndex 起读，不受残留读态影响")
    void 入站按读指针起读() {
        PacketBuffer buffer = new PacketBuffer(Unpooled.wrappedBuffer(new byte[] {1, 2, 3, 4}));
        buffer.readByte();
        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                new net.minecraftforge.fml.common.network.internal.FMLProxyPacket(buffer, "MPMT");

        assertArrayEquals(new byte[] {2, 3, 4}, ForgePayloadCodec.incoming(packet));
    }

    @Test
    @DisplayName("出站：通道名或负载为空即拒绝")
    void 出站拒绝空入参() {
        assertThrows(NullPointerException.class, () -> ForgePayloadCodec.outgoing(null, new byte[0]));
        assertThrows(NullPointerException.class, () -> ForgePayloadCodec.outgoing("MPMT", null));
    }

    @Test
    @DisplayName("入站：包为空即拒绝")
    void 入站拒绝空包() {
        assertThrows(NullPointerException.class, () -> ForgePayloadCodec.incoming(null));
    }

    @Test
    @DisplayName("往返：大负载（含全部字节取值）不截断")
    void 往返大负载不截断() {
        byte[] data = new byte[32767];
        for (int index = 0; index < data.length; index++) {
            data[index] = (byte) (index % 256);
        }

        net.minecraftforge.fml.common.network.internal.FMLProxyPacket packet =
                ForgePayloadCodec.outgoing("MPMT", data);

        assertArrayEquals(data, ForgePayloadCodec.incoming(packet));
        assertTrue(ForgePayloadCodec.incoming(packet).length == data.length);
    }

    @Test
    @DisplayName("工具类：仅一条私有构造，无参数")
    void 工具类私有构造() throws Exception {
        java.lang.reflect.Constructor<ForgePayloadCodec> constructor =
                ForgePayloadCodec.class.getDeclaredConstructor();

        assertEquals(0, constructor.getParameterCount());
        assertTrue(
                java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                "工具类不应暴露构造入口");
    }
}
