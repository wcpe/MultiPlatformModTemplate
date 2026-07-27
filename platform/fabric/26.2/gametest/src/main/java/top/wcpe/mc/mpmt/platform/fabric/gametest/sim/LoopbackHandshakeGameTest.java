package top.wcpe.mc.mpmt.platform.fabric.gametest.sim;

import java.util.ArrayList;
import java.util.List;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTest;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.core.client.HandshakeClientService;
import top.wcpe.mc.mpmt.core.domain.ban.BanRegistry;
import top.wcpe.mc.mpmt.core.domain.net.HandshakeStateMachine;
import top.wcpe.mc.mpmt.core.server.HandshakeServerService;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.PingPacket;
import top.wcpe.mc.mpmt.protocol.packet.PongPacket;

/**
 * 模拟服回环握手 GameTest（FR-23①）：在真实 Fabric 服务端运行期，经进程内回环跑通完整产品网络栈——
 * 握手 + 版本协商 + 弱标识上报 + 欢迎建会话 + Ping/Pong 往返，全部断言。无外部客户端、headless 自动跑。
 *
 * <p>与 realserver 套件互补：realserver 验证真实平台传输 + 真实客户端；本套件验证 L1 协议 / 收发管线 / 握手
 * 服务在实际 mod 加载器运行时正确（捕获 relocation / 类加载等纯 JVM 单测看不到的问题）。
 */
public final class LoopbackHandshakeGameTest implements ServerGameTest {

    @Override
    public String suite() {
        return "simulation";
    }

    @Override
    public String id() {
        return "handshake-loopback";
    }

    @Override
    public void run(ServerGameTestContext context) {
        LoopbackTransport loop = new LoopbackTransport();
        PacketCodec codec = new PacketCodec();
        PacketDispatcher serverDispatcher = new PacketDispatcher(loop.server(), codec);
        PacketDispatcher clientDispatcher = new PacketDispatcher(loop.client(), codec);

        HandshakeServerService server =
                new HandshakeServerService(serverDispatcher, () -> "sim-session", new BanRegistry());
        HandshakeClientService client =
                new HandshakeClientService(clientDispatcher, "sim-1.0", () -> "sim-code");

        // 服务端示例：收 Ping 回 Pong；客户端收集 Pong
        serverDispatcher.on(
                PacketIds.PING,
                (connection, packet) ->
                        serverDispatcher.send(connection, new PongPacket(((PingPacket) packet).getNonce())));
        List<Long> pongs = new ArrayList<>();
        clientDispatcher.on(PacketIds.PONG, (connection, packet) -> pongs.add(((PongPacket) packet).getNonce()));

        // 发起握手（回环同步推进）
        client.startHandshake();

        context.assertTrue(client.isAccepted(), "握手应被接受");
        context.assertEquals("sim-session", client.sessionId(), "会话 id 应匹配");
        context.assertEquals("欢迎", client.lastServerMessage(), "应收到欢迎消息");
        context.assertTrue(!client.isDisconnected(), "不应被断开");
        context.assertEquals(
                HandshakeStateMachine.State.ESTABLISHED,
                server.stateOf(loop.clientConnection()),
                "服务端应建立会话");

        // 往返
        clientDispatcher.send(new PingPacket(42L));
        context.assertEquals(1, pongs.size(), "应收到一个 Pong");
        context.assertEquals(42L, pongs.get(0).longValue(), "Pong nonce 应往返一致");
    }
}
