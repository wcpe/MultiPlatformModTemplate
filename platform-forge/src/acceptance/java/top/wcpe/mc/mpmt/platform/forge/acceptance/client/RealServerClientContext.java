package top.wcpe.mc.mpmt.platform.forge.acceptance.client;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 客户端验证上下文（仅客户端环境）：给验证器在客户端 tick 线程读真实客户端 / 渲染态。 */
@OnlyIn(Dist.CLIENT)
public final class RealServerClientContext {

    private final Minecraft client;

    public RealServerClientContext(Minecraft client) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
    }

    /** 真实客户端实例（验证器经它读玩家 / 世界 / 渲染快照）。 */
    public Minecraft client() {
        return client;
    }
}
