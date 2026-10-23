package top.wcpe.mc.mpmt.examples.counter;

import java.util.Objects;
import top.wcpe.mc.mpmt.kernel.port.MessagePort;
import top.wcpe.mc.mpmt.kernel.port.SchedulerPort;
import top.wcpe.mc.mpmt.kernel.ref.EntityRef;
import top.wcpe.mc.mpmt.kernel.ref.PlayerRef;

/**
 * 客户端展示实现（示范域 counter）。
 *
 * <p>放在 {@code client}：这里放客户端实现，通过 {@link CounterDisplayPort} 对外暴露（ADR-0030）。
 * 展示动作按实体归属切线程，符合"无单一主线程"的调度约束（ADR-0013）。
 *
 * <p>同时实现内部抽象 {@link CounterHudRenderer}：域内调用方只依赖该接口，不依赖本实现。
 */
public final class ChatCounterDisplay implements CounterDisplayPort, CounterHudRenderer {

    private final MessagePort message;
    private final SchedulerPort scheduler;
    private final CounterHudRenderer renderer;

    /**
     * 构造。
     *
     * @param message   消息端口，不可为空
     * @param scheduler 调度端口，不可为空
     */
    public ChatCounterDisplay(MessagePort message, SchedulerPort scheduler) {
        this.message = Objects.requireNonNull(message, "消息端口不能为空");
        this.scheduler = Objects.requireNonNull(scheduler, "调度端口不能为空");
        this.renderer = this;
    }

    @Override
    public void showJoinCount(PlayerRef player, int count) {
        Objects.requireNonNull(player, "玩家不能为空");
        // 对外契约（CounterDisplayPort）保持不变；内部转成 internal DTO 交给渲染接口，
        // 使展示实现与调用方都只依赖 client-internal 的抽象（ADR-0030 决策 5）。
        renderer.render(new CounterHudPayload(player.getUuid(), player.getName(), count));
    }

    @Override
    public void render(CounterHudPayload payload) {
        Objects.requireNonNull(payload, "渲染载荷不能为空");
        PlayerRef player = new PlayerRef(payload.playerId(), payload.playerName());
        scheduler.runForEntity(
                new EntityRef(payload.playerId()), () -> message.send(player, payload.displayText()));
    }
}
