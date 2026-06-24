package top.wcpe.mc.mpmt.platform.fabric.version;

import java.util.function.Consumer;

/**
 * Fabric 客户端网络绑定（L4 版本适配接口，ADR-0003）：封装<b>随 MC 版本漂移</b>的客户端通道注册与收发。
 *
 * <p>1.20.1 用 {@code ClientPlayNetworking}(ResourceLocation + FriendlyByteBuf)；1.20.5+ codec 化
 * （network spec §3.4）。客户端只有一条到服务端的连接，故收发不带连接句柄。仅以裸 {@code byte[]} 交互，
 * 不向上暴露任何 MC 类型。
 */
public interface FabricClientNetwork {

    /**
     * 注册客户端收包回调（裸字节，来自服务端）。
     *
     * <p>回调可能在网络线程触发；上层（PacketDispatcher / 握手服务）以 volatile 暴露结果、线程安全，
     * 渲染只在渲染线程读不可变快照（ADR-0013）。
     */
    void registerReceiver(Consumer<byte[]> handler);

    /** 向服务端发送裸字节。 */
    void send(byte[] data);

    /** 当前版本单包载荷上限（字节），供上层分片决策。 */
    int maxPayloadSize();
}
