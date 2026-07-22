package top.wcpe.mc.mpmt.core.domain.port;

import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;

/**
 * 连接控制端口：把不透明连接映射到实体归属，并执行平台真实断开。
 *
 * <p>调用方在断开前必须经 {@link SchedulerPort#runForEntity} 切到实体所属执行线程。
 */
public interface ConnectionControlPort {

    /** 返回连接对应的实体引用，供按归属调度。 */
    EntityRef entityOf(ConnectionHandle connection);

    /** 在实体所属执行线程真实断开连接。 */
    void disconnect(ConnectionHandle connection, String reason);
}
