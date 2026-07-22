package top.wcpe.mc.mpmt.platform.forge.net;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.platform.forge.capability.ForgeHudRenderer;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 客户端 HUD 接缝：在统一客户端 PacketDispatcher 上登记渲染处理器。 */
@OnlyIn(Dist.CLIENT)
public final class ForgeClientHudReceiver {

    private ForgeClientHudReceiver() {
        // 工具类不实例化
    }

    /** 使用 ClientNetworkFeature 创建的唯一 dispatcher 注册 HUD 处理器。 */
    public static void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) ->
                        ForgeHudRenderer.render((ServerHudMessagePacket) packet));
    }
}
