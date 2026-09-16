package top.wcpe.mc.mpmt.platform.fabric.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.ref.PlayerRef;
import top.wcpe.mc.mpmt.platform.fabric.MinecraftTestSupport;

/**
 * Fabric 消息端口：按 UUID 找在线玩家并发送系统消息；离线静默丢弃（不抛错、不误发）。
 *
 * <p>玩家替身覆写系统消息入口以记录发出内容，避免触碰真实网络连接。
 */
class FabricMessagePortTest {

    @Test
    @DisplayName("只向在线玩家投递文本，离线玩家静默丢弃")
    void 按在线状态投递() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        RecordingPlayer online = RecordingPlayer.of(UUID.randomUUID(), "甲");
        MinecraftTestSupport.installPlayerList(server, online);

        FabricMessagePort port = new FabricMessagePort(server);

        port.send(new PlayerRef(online.getUUID(), "甲"), "第一条");
        port.send(new PlayerRef(UUID.randomUUID(), "乙"), "不应投递");

        assertEquals(List.of("第一条"), online.messages());
    }

    @Test
    @DisplayName("同一玩家多次投递按顺序累积，文本原样送达")
    void 重复投递保序() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        RecordingPlayer player = RecordingPlayer.of(UUID.randomUUID(), "乙");
        MinecraftTestSupport.installPlayerList(server, player);

        FabricMessagePort port = new FabricMessagePort(server);
        PlayerRef ref = new PlayerRef(player.getUUID(), "乙");
        port.send(ref, "第一");
        port.send(ref, "第二");

        assertEquals(List.of("第一", "第二"), player.messages());
    }

    @Test
    @DisplayName("候选玩家全离线时不投递任何消息")
    void 全离线不投递() {
        MinecraftServer server = MinecraftTestSupport.newServer();
        RecordingPlayer player = RecordingPlayer.of(UUID.randomUUID(), "丙");
        MinecraftTestSupport.installPlayerList(server);

        new FabricMessagePort(server).send(new PlayerRef(player.getUUID(), "丙"), "不应投递");

        assertTrue(player.messages().isEmpty());
    }

    @Test
    @DisplayName("服务端为空失败快")
    void 服务端为空失败快() {
        assertThrows(NullPointerException.class, () -> new FabricMessagePort(null));
    }

    /** 记录型玩家：覆写系统消息入口，避免触碰真实连接。 */
    private static final class RecordingPlayer extends ServerPlayer {

        private List<String> messages;

        private RecordingPlayer() {
            // 仅满足编译器；实例经 Unsafe 分配，本构造函数不会执行
            super(null, null, null, null);
        }

        static RecordingPlayer of(UUID playerId, String name) {
            RecordingPlayer player = MinecraftTestSupport.allocateInstance(RecordingPlayer.class);
            player.messages = new ArrayList<>();
            MinecraftTestSupport.seedPlayer(player, playerId, name);
            return player;
        }

        @Override
        public void sendSystemMessage(Component component) {
            messages.add(component.getString());
        }

        List<String> messages() {
            return messages;
        }
    }
}
