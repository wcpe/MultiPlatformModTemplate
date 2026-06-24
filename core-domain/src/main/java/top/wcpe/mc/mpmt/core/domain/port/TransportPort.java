package top.wcpe.mc.mpmt.core.domain.port;

import java.util.function.BiConsumer;

/**
 * 跨端字节传输端口（L0）：只负责<b>裸字节</b>收发，不认协议包类型。
 *
 * <p>包 ↔ 字节的编解码由 L1 protocol 在本端口之上完成（ADR-0006），故本端口签名用 {@code byte[]} 而非 Packet，
 * 以守 L0 不依赖 L1 的分层方向（ADR-0001）。各平台 L3 适配各自网络通道实现本端口。
 *
 * <p><b>线程契约</b>：{@link #onReceive} 回调可能在任意网络线程触发；上层处理碰世界 / 领域状态前，
 * 须经 SchedulerPort 按归属切到正确线程（Folia 无单一主线程，ADR-0013）。
 */
public interface TransportPort {

    /** 服务端：向指定连接发送字节。 */
    void send(ConnectionHandle connection, byte[] data);

    /** 客户端：向服务端发送字节。 */
    void send(byte[] data);

    /** 注册收包回调（连接句柄 + 裸字节）。 */
    void onReceive(BiConsumer<ConnectionHandle, byte[]> handler);

    /** 当前平台 / 版本单包载荷上限（字节），供上层分片决策。 */
    int maxPayloadSize();
}
