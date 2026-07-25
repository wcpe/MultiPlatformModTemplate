package top.wcpe.mc.mpmt.platform.fabric.version;

import top.wcpe.mc.mpmt.protocol.PacketDispatcher;

/** 不暴露 Minecraft 类型的客户端 HUD 适配器。 */
public interface FabricHud {

    void register(PacketDispatcher dispatcher);

    HudSnapshot snapshot();

    void clear();
}
