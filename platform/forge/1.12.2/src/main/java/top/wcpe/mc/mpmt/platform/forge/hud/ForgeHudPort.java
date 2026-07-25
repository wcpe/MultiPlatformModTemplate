package top.wcpe.mc.mpmt.platform.forge.hud;

import top.wcpe.mc.mpmt.protocol.PacketDispatcher;

/** 不暴露 Minecraft 类型的 1.12.2 HUD 接缝。 */
public interface ForgeHudPort {

    void register(PacketDispatcher dispatcher);

    ForgeHudSnapshot snapshot();

    void clear();
}
