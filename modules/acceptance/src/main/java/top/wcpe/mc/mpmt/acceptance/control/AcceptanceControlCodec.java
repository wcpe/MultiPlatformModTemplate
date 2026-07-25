package top.wcpe.mc.mpmt.acceptance.control;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 验收测试控制协议的手写 codec（ADR-0014）：与产品协议<b>完全隔离</b>，服务端与客户端各持一份字节对齐实现。
 *
 * <p>字节布局：{@code [type:byte][字段…]}；字符串为 {@code [int 长度][UTF-8 字节]}（避开 {@code writeUTF} 的 64KB 上限）。
 * 非法 / 截断字节解码即抛 {@link IllegalArgumentException}，不静默错乱。
 */
public final class AcceptanceControlCodec {

    /** 控制协议版本（兼容校验）。v2 在 ClientReady 中附带 Java 运行身份。 */
    public static final int PROTOCOL_VERSION = 2;

    private static final int TYPE_CLIENT_READY = 1;
    private static final int TYPE_RUN_STEP = 2;
    private static final int TYPE_STEP_RESULT = 3;

    private AcceptanceControlCodec() {
        // 工具类不实例化
    }

    /** 编码为字节。 */
    public static byte[] encode(AcceptanceControlPacket packet) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            if (packet instanceof ClientReadyPacket) {
                ClientReadyPacket ready = (ClientReadyPacket) packet;
                out.writeByte(TYPE_CLIENT_READY);
                out.writeInt(ready.getProtocolVersion());
                out.writeInt(ready.getJavaMajor());
                writeString(out, ready.getJavaExecutable());
            } else if (packet instanceof RunStepPacket) {
                RunStepPacket p = (RunStepPacket) packet;
                out.writeByte(TYPE_RUN_STEP);
                writeString(out, p.getScenarioId());
                writeString(out, p.getStepId());
                writeString(out, p.getParamsJson());
                out.writeInt(p.getSeq());
            } else if (packet instanceof StepResultPacket) {
                StepResultPacket p = (StepResultPacket) packet;
                out.writeByte(TYPE_STEP_RESULT);
                writeString(out, p.getScenarioId());
                writeString(out, p.getStepId());
                out.writeInt(p.getSeq());
                out.writeByte(p.getStatus().wire());
                writeString(out, p.getResultJson());
                writeString(out, p.getMessage());
            } else {
                throw new IllegalArgumentException("未知控制包类型：" + packet.getClass().getName());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真正 IO 失败；保守包装
            throw new IllegalStateException("控制包编码失败", e);
        }
    }

    /** 解码字节；非法 / 截断 / 未知类型即抛 {@link IllegalArgumentException}。 */
    public static AcceptanceControlPacket decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int type = in.readByte();
            switch (type) {
                case TYPE_CLIENT_READY:
                    return new ClientReadyPacket(in.readInt(), in.readInt(), readString(in));
                case TYPE_RUN_STEP:
                    return new RunStepPacket(
                            readString(in), readString(in), readString(in), in.readInt());
                case TYPE_STEP_RESULT:
                    return new StepResultPacket(
                            readString(in),
                            readString(in),
                            in.readInt(),
                            StepStatus.fromWire(in.readByte()),
                            readString(in),
                            readString(in));
                default:
                    throw new IllegalArgumentException("未知控制包类型码：" + type);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("控制包解码失败（非法或截断）", e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("非法字符串长度：" + length);
        }
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
