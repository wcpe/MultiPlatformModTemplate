package top.wcpe.mc.mpmt.platform.fabric.gametest.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * 验收 harness 客户端入口（gametest 测试 mod 的 client entrypoint）：注册 {@link AcceptanceClientCompanion}。
 * 客户端经启动参数 {@code --quickPlayMultiplayer <addr>} 自连真实服后，伴侣逐 tick 服务验收步骤（Round H 起服编排）。
 */
@Environment(EnvType.CLIENT)
public final class AcceptanceGametestClientInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        CapabilityMessageTracker.register();
        new AcceptanceClientCompanion().register();
    }
}
