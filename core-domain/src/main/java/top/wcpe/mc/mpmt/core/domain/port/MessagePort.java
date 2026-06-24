package top.wcpe.mc.mpmt.core.domain.port;

import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;

/**
 * 消息端口（L0）：向指定玩家发送一条文本消息（聊天栏）。
 *
 * <p>只暴露平台无关的领域视图（{@link PlayerRef} + 文本），不暴露任何平台原生对象。
 * 发送通常碰玩家状态，调用方须经 {@code SchedulerPort.runForEntity} 按归属切线程后再调用（ADR-0013）。
 * 跨端 HUD / title 等富消息属 FR-27，另经协议下发。
 */
public interface MessagePort {

    /** 向玩家发送一条文本消息。 */
    void send(PlayerRef player, String text);
}
