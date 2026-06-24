package top.wcpe.mc.mpmt.platform.fabric.gametest;

import net.fabricmc.api.ModInitializer;

/**
 * 验收 harness 服务端入口（gametest 测试 mod 的 main entrypoint）：触发 {@link AcceptanceDriverBootstrap#register()}。
 * 仅当 {@code -Dmpmt.acceptance=true} 时实际激活，否则 NOP。
 */
public final class AcceptanceGametestInit implements ModInitializer {

    @Override
    public void onInitialize() {
        AcceptanceDriverBootstrap.register();
    }
}
