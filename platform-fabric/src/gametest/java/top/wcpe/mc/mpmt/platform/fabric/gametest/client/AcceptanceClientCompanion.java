package top.wcpe.mc.mpmt.platform.fabric.gametest.client;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.RunStepPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;
import top.wcpe.mc.mpmt.platform.fabric.gametest.AcceptanceControlChannelId;

/**
 * realserver 验收客户端伴侣（仅客户端环境，ADR-0014）：程序化客户端连入真实服后，逐 tick 服务服务端下发的
 * {@link RunStepPacket}——经 {@link ClientVerifierRegistry} 找验证器轮询、判定后回 {@link StepResultPacket}。
 *
 * <p>连接由启动参数 {@code --quickPlayMultiplayer <addr>} 完成（Round H 起服编排），本伴侣不做程序化连接；
 * 进世界（{@code ClientPlayConnectionEvents.JOIN}）即上报 {@link ClientReadyPacket}。入站在网络线程入队、
 * 服务在客户端 tick 线程出队（队列并发安全；验证器只在 tick 线程读客户端态，ADR-0013）。
 */
@Environment(EnvType.CLIENT)
public final class AcceptanceClientCompanion {

    private static final Logger LOGGER = LoggerFactory.getLogger("mpmt-acceptance");
    private static final int STEP_TIMEOUT_TICKS = 200;

    private final ClientVerifierRegistry verifiers = new ClientVerifierRegistry();
    private final Queue<RunStepPacket> inbound = new ConcurrentLinkedQueue<>();

    private RunStepPacket active;
    private int ticksInStep;

    /** 注册控制通道接收 + 进服上报就绪 + 逐 tick 服务。 */
    public void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                AcceptanceControlChannelId.CHANNEL,
                (client, handler, buf, responseSender) -> {
                    byte[] data = readAll(buf);
                    try {
                        AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
                        if (packet instanceof RunStepPacket) {
                            inbound.add((RunStepPacket) packet);
                        }
                    } catch (RuntimeException e) {
                        LOGGER.warn("丢弃非法验收控制包：{}", e.getMessage());
                    }
                });
        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) ->
                        send(new ClientReadyPacket(AcceptanceControlCodec.PROTOCOL_VERSION)));
        ClientTickEvents.END_CLIENT_TICK.register(this::serveTick);
        LOGGER.info("realserver 验收客户端伴侣已注册");
    }

    private void serveTick(Minecraft client) {
        if (client.player == null) {
            return; // 未进世界
        }
        if (active == null) {
            active = inbound.poll();
            ticksInStep = 0;
            if (active == null) {
                return; // 无待服务步骤
            }
        }
        VerifyOutcome outcome = evaluate(client);
        if (outcome == null) {
            ticksInStep++;
            if (ticksInStep < STEP_TIMEOUT_TICKS) {
                return; // 继续轮询
            }
            outcome = VerifyOutcome.fail("客户端步骤超时 " + STEP_TIMEOUT_TICKS + " tick");
        }
        send(
                new StepResultPacket(
                        active.getScenarioId(),
                        active.getStepId(),
                        active.getSeq(),
                        outcome.status(),
                        outcome.resultJson(),
                        outcome.message()));
        active = null;
    }

    private VerifyOutcome evaluate(Minecraft client) {
        ClientVerifier verifier = verifiers.find(active.getScenarioId());
        if (verifier == null) {
            return VerifyOutcome.error("无客户端验证器：scenarioId=" + active.getScenarioId());
        }
        try {
            return verifier.poll(
                    new VerifyStep(active.getStepId(), active.getParamsJson(), ticksInStep),
                    new RealServerClientContext(client));
        } catch (RuntimeException e) {
            return VerifyOutcome.error("验证器异常：" + e.getMessage());
        }
    }

    private static void send(AcceptanceControlPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        buf.writeBytes(AcceptanceControlCodec.encode(packet));
        ClientPlayNetworking.send(AcceptanceControlChannelId.CHANNEL, buf);
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }
}
