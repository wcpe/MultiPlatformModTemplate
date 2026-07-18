package top.wcpe.mc.mpmt.platform.sponge.acceptance.scenario;

import java.util.Optional;
import java.util.UUID;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.plugin.PluginContainer;
import top.wcpe.mc.mpmt.acceptance.gametest.ServerGameTestContext;
import top.wcpe.mc.mpmt.platform.sponge.MpmtSpongePlugin;

final class SpongeScenarioSupport {

    private SpongeScenarioSupport() {}

    static MpmtSpongePlugin productPlugin(ServerGameTestContext context) {
        Object instance =
                Sponge.pluginManager()
                        .plugin("mpmt")
                        .map(PluginContainer::instance)
                        .orElse(null);
        context.assertTrue(instance instanceof MpmtSpongePlugin, "未取得 mpmt 产品插件实例");
        return (MpmtSpongePlugin) instance;
    }

    static UUID onlinePlayerId(ServerGameTestContext context) {
        Optional<ServerPlayer> player = Sponge.server().onlinePlayers().stream().findFirst();
        context.assertTrue(player.isPresent(), "无在线玩家");
        return player.get().uniqueId();
    }
}
