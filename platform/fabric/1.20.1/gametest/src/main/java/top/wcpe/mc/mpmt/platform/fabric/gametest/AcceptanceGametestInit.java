package top.wcpe.mc.mpmt.platform.fabric.gametest;

import net.fabricmc.api.ModInitializer;
import top.wcpe.mc.mpmt.platform.fabric.gametest.sim.SimDriverBootstrap;

/**
 * 验收 harness 服务端入口（gametest 测试 mod 的 main entrypoint）：触发 realserver 验收驱动与模拟服 GameTest 驱动注册。
 * 各自经系统属性（{@code -Dmpmt.acceptance=true} / {@code -Dmpmt.simtest=true}）激活，未激活则 NOP。
 */
public final class AcceptanceGametestInit implements ModInitializer {

    @Override
    public void onInitialize() {
        AcceptanceDriverBootstrap.register();
        SimDriverBootstrap.register();
    }
}
