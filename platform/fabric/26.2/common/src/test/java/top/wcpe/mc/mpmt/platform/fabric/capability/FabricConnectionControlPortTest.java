package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.EntityRef;
import top.wcpe.mc.mpmt.platform.fabric.MinecraftTestSupport;
import top.wcpe.mc.mpmt.platform.fabric.net.FabricConnectionHandle;

/**
 * Fabric 连接控制端口：连接标识解析、按 UUID 重新查询当前玩家并断开。
 *
 * <p>断开走玩家真实 {@code connection.disconnect(...)}，测试以记录型连接监听器观测
 * "是否断开了正确的玩家、理由是否原样传达"。
 */
class FabricConnectionControlPortTest {

    @Test
    @DisplayName("entityOf 返回连接对应玩家的实体引用（UUID 保真）")
    void 实体引用解析() {
        Fixture fixture = new Fixture();
        UUID playerId = UUID.randomUUID();
        ServerPlayer player = fixture.online(playerId, "甲");
        FabricConnectionHandle handle = MinecraftTestSupport.handleOf(player);

        EntityRef entity = fixture.port().entityOf(handle);

        assertEquals(playerId, entity.getId());
    }

    @Test
    @DisplayName("断开在线玩家：向该玩家连接发出断开并原样传达理由")
    void 断开在线玩家() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.online(UUID.randomUUID(), "乙");
        RecordingConnection connection = fixture.attachConnection(player);

        fixture.port().disconnect(MinecraftTestSupport.handleOf(player), "已被封禁");

        assertEquals(1, connection.disconnects.size());
        assertEquals("已被封禁", connection.disconnects.get(0));
        assertTrue(connection.otherPackets.isEmpty(), "断开只应发一条断开包，不得先发其它包");
    }

    @Test
    @DisplayName("玩家已离线：静默跳过，不抛错也不误发到别的连接")
    void 玩家离线静默跳过() {
        Fixture fixture = new Fixture();
        ServerPlayer player = fixture.offline(UUID.randomUUID(), "丙");
        RecordingConnection connection = fixture.attachConnection(player);

        fixture.port().disconnect(MinecraftTestSupport.handleOf(player), "会被忽略");

        assertTrue(connection.disconnects.isEmpty(), "离线玩家的连接不应收到断开");
    }

    @Test
    @DisplayName("同 UUID 的不同玩家实例（重连）按当前在线实例断开")
    void 重连后按当前实例断开() {
        Fixture fixture = new Fixture();
        UUID playerId = UUID.randomUUID();
        ServerPlayer stale = fixture.offline(playerId, "重连玩家");
        ServerPlayer current = fixture.online(playerId, "重连玩家");
        RecordingConnection staleConnection = fixture.attachConnection(stale);
        RecordingConnection currentConnection = fixture.attachConnection(current);

        fixture.port().disconnect(MinecraftTestSupport.handleOf(stale), "重连后断开");

        assertTrue(staleConnection.disconnects.isEmpty(), "旧实例的连接不应被使用");
        assertEquals(1, currentConnection.disconnects.size(), "须断开当前在线实例");
        assertEquals("重连后断开", currentConnection.disconnects.get(0));
    }

    @Test
    @DisplayName("服务端为空失败快")
    void 服务端为空失败快() {
        assertThrows(NullPointerException.class, () -> new FabricConnectionControlPort(null));
    }

    /** 一次性夹具：服务端替身 + 连接控制端口。 */
    private static final class Fixture {

        private final MinecraftServer server = MinecraftTestSupport.newServer();
        private final FabricConnectionControlPort port;

        Fixture() {
            // 玩家表先置空态：产品按 UUID 查询时得到 null 即为"该玩家不在线"
            MinecraftTestSupport.installPlayerList(server);
            this.port = new FabricConnectionControlPort(server);
        }

        FabricConnectionControlPort port() {
            return port;
        }

        ServerPlayer online(UUID playerId, String name) {
            ServerPlayer existing = existingOnline();
            ServerPlayer player = MinecraftTestSupport.newPlayer(playerId, name);
            if (existing == null) {
                MinecraftTestSupport.installPlayerList(server, player);
            } else {
                MinecraftTestSupport.installPlayerList(server, existing, player);
            }
            return player;
        }

        private ServerPlayer existingOnline() {
            java.util.List<ServerPlayer> online = server.getPlayerList().getPlayers();
            return online.isEmpty() ? null : online.get(0);
        }

        ServerPlayer offline(UUID playerId, String name) {
            return MinecraftTestSupport.newPlayer(playerId, name);
        }

        /** 给玩家的连接字段装上记录型监听器，以捕获断开与出站包。 */
        RecordingConnection attachConnection(ServerPlayer player) {
            RecordingConnection connection = MinecraftTestSupport.allocateInstance(RecordingConnection.class);
            connection.disconnects = new java.util.ArrayList<>();
            connection.otherPackets = new java.util.ArrayList<>();
            MinecraftTestSupport.set(connection, "player", player);
            MinecraftTestSupport.set(player, "connection", connection);
            return connection;
        }
    }

    /** 记录型连接监听器：捕获断开理由与其它出站包。 */
    private static final class RecordingConnection extends ServerGamePacketListenerImpl {

        private java.util.List<String> disconnects;
        private java.util.List<Packet<?>> otherPackets;

        private RecordingConnection() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null, null, null, null);
        }

        @Override
        public void disconnect(net.minecraft.network.chat.Component reason) {
            disconnects.add(reason.getString());
        }

        @Override
        public void send(Packet<?> packet) {
            if (packet instanceof ClientboundSystemChatPacket
                    || packet instanceof ClientboundPlayerInfoUpdatePacket) {
                otherPackets.add(packet);
            }
        }
    }
}
