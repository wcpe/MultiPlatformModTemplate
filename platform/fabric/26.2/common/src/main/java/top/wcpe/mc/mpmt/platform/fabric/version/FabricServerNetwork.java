package top.wcpe.mc.mpmt.platform.fabric.version;

import java.util.function.BiConsumer;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;

/**
 * Fabric 服务端网络绑定（L4 版本适配接口，ADR-0003）：封装<b>随 MC 版本漂移</b>的服务端通道注册与收发。
 *
 * <p>1.20.1 用 {@code ServerPlayNetworking}(ResourceLocation + FriendlyByteBuf)；1.20.5+ 改 CustomPayload + StreamCodec
 * （network spec §3.4）。故注册管线必须经此接口的 {@code vX_Y} 实现适配，<b>不写死在 L3 单一 net 里</b>（ADR-0003 约束）。
 * 仅以裸 {@code byte[]} 与不透明 {@link ConnectionHandle} 交互，不向上暴露任何 MC 类型。
 */
public interface FabricServerNetwork {

    /**
     * 注册服务端收包回调（裸字节 + 发来连接的句柄）。
     *
     * <p>回调在服务端网络线程触发；上层（L1 PacketDispatcher）须线程安全，碰世界 / 领域态前经
     * SchedulerPort 按归属切线程（ADR-0013）。
     */
    void registerReceiver(BiConsumer<ConnectionHandle, byte[]> handler);

    /** 向指定连接发送裸字节。 */
    void send(ConnectionHandle connection, byte[] data);

    /** 当前版本单包载荷上限（字节），供上层分片决策。 */
    int maxPayloadSize();
}
