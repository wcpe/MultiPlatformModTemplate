package top.wcpe.mc.mpmt.platform.neoforge.acceptance;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.NetworkRegistry;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.simple.SimpleChannel;
import top.wcpe.mc.mpmt.acceptance.AcceptanceClient;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlCodec;
import top.wcpe.mc.mpmt.acceptance.control.AcceptanceControlPacket;
import top.wcpe.mc.mpmt.acceptance.control.ClientReadyPacket;
import top.wcpe.mc.mpmt.acceptance.control.StepResultPacket;

/**
 * NeoForge 验收控制通道（realserver harness，ADR-0014）：用 NeoForge {@link SimpleChannel} 在
 * {@code mpmt-test:acceptance} 通道上把测试控制协议（ClientReady/RunStep/StepResult）桥接到平台无关
 * {@link AcceptanceClient}。
 *
 * <p><b>SimpleChannel 收发</b>（与产品 {@link top.wcpe.mc.mpmt.platform.neoforge.net.NeoForgeServerTransport}
 * 同机制）：单一 {@code RawControlMessage} 透传 {@code byte[]}；{@code consumerMainThread} 按收发端分流——服务端收
 * （{@code ctx.getSender()!=null}）解码后分派 ClientReady/StepResult 给 {@link AcceptanceClient}；客户端收回
 * {@link #setClientReceiver} 注入的处理器（由客户端伴侣注入）。NeoForge 20.2 网络为 Forge 系、运行期官方 Mojmap、
 * 无 SRG/reobf；SimpleChannel 自带帧字节，故只适用 NeoForge↔NeoForge（realserver 目标，无需 Mixin）。
 *
 * <p>出站（服务端→客户端）由 {@link AcceptanceClient} 经注入回调触发，经 {@link PacketDistributor} 发给当前连入的
 * 单个测试客户端；客户端→服务端经 {@link #sendToServer} 经通道发给服务端。
 */
public final class NeoForgeAcceptanceControlChannel {

    /** 验收通道网络协议版本。 */
    private static final String PROTOCOL_VERSION = "1";
    /** 单一原始字节控制消息判别 id。 */
    private static final int RAW_MESSAGE_ID = 0;

    private final SimpleChannel channel;

    /** 服务端在启动后绑定（出站发送定位玩家用）。 */
    private volatile MinecraftServer server;

    /** 客户端收包回调；由客户端伴侣经 {@link #setClientReceiver} 注入。 */
    private volatile Consumer<byte[]> clientReceiver;

    private final AcceptanceClient client;

    public NeoForgeAcceptanceControlChannel() {
        // 注入出站回调：AcceptanceClient 要发字节时经通道发给测试客户端
        this.client = new AcceptanceClient(this::sendToClient);
        // 通道在构造期注册（NetworkRegistry 锁定窗口内），与产品 NeoForgeServerTransport 一致
        this.channel =
                NetworkRegistry.newSimpleChannel(
                        NeoForgeAcceptanceControlChannelId.CHANNEL,
                        () -> PROTOCOL_VERSION,
                        version -> true,
                        version -> true);
        registerRawMessage();
    }

    private void registerRawMessage() {
        channel.messageBuilder(RawControlMessage.class, RAW_MESSAGE_ID)
                .encoder((msg, buf) -> buf.writeByteArray(msg.data))
                .decoder(buf -> new RawControlMessage(buf.readByteArray()))
                .consumerMainThread(
                        (msg, ctx) -> {
                            // NeoForge 20.2 的 consumerMainThread 直接传 Context（非 Supplier）
                            ServerPlayer sender = ctx.getSender();
                            if (sender != null) {
                                onServerControl(msg.data);
                            } else {
                                Consumer<byte[]> receiver = clientReceiver;
                                if (receiver != null) {
                                    receiver.accept(msg.data);
                                }
                            }
                            ctx.setPacketHandled(true);
                        })
                .add();
    }

    /** 服务端启动后绑定（供出站发送定位在线玩家）。 */
    public void bindServer(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
    }

    /** 取平台无关排程协调器（供场景绑定）。 */
    public AcceptanceClient client() {
        return client;
    }

    /** 注入客户端收包处理器（仅 {@code Dist.CLIENT} 由伴侣调用，收 S2C 控制字节）。 */
    public void setClientReceiver(Consumer<byte[]> receiver) {
        this.clientReceiver = Objects.requireNonNull(receiver, "receiver 不能为空");
    }

    /** 客户端→服务端：经通道把控制字节发给服务端（客户端伴侣上报 ClientReady/StepResult 用）。 */
    public void sendToServer(byte[] data) {
        channel.sendToServer(new RawControlMessage(data));
    }

    /** 服务端收到客户端控制字节（已在主线程，由 consumerMainThread 切入）：解码后分派给协调器。 */
    private void onServerControl(byte[] data) {
        org.slf4j.LoggerFactory.getLogger("mpmt-acceptance")
                .info("收到客户端控制 payload，{} 字节", data.length);
        try {
            AcceptanceControlPacket packet = AcceptanceControlCodec.decode(data);
            if (packet instanceof ClientReadyPacket) {
                client.onClientReady((ClientReadyPacket) packet);
            } else if (packet instanceof StepResultPacket) {
                client.onStepResult((StepResultPacket) packet);
            }
            // RunStep 为 S2C，不应入站；忽略以容错
        } catch (RuntimeException e) {
            // 非法 / 截断 / 未知控制包：丢弃不打断接收器（类比产品收发的容错）
            org.slf4j.LoggerFactory.getLogger("mpmt-acceptance")
                    .warn("丢弃非法验收控制包：{}", e.getMessage());
        }
    }

    /** 客户端断开：异常完成所有挂起步骤（由驱动监听 PlayerLoggedOut 调用）。 */
    public void onClientDisconnected() {
        client.failAllPending("客户端断开");
    }

    /** 出站：切主线程把控制字节经通道发给当前连入的测试客户端（单客户端模式取第一个在线玩家）。 */
    private void sendToClient(byte[] data) {
        MinecraftServer current = server;
        if (current == null) {
            // 服务端尚未绑定（正常流程不会发生：发送都在 ServerStarted 绑定之后）
            return;
        }
        current.execute(
                () -> {
                    List<ServerPlayer> players = current.getPlayerList().getPlayers();
                    if (!players.isEmpty()) {
                        ServerPlayer player = players.get(0);
                        channel.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new RawControlMessage(data));
                    }
                });
    }

    /** 原始字节控制消息载体（SimpleChannel 要求消息为类型化对象，仅透传 byte[]）。 */
    private static final class RawControlMessage {
        private final byte[] data;

        RawControlMessage(byte[] data) {
            this.data = data;
        }
    }
}
