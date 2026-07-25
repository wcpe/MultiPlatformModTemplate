package top.wcpe.mc.mpmt.platform.neoforge.acceptance.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import top.wcpe.mc.mpmt.platform.neoforge.acceptance.NeoForgeAcceptanceControlChannel;

/**
 * 验收客户端伴侣装配接缝（仅客户端环境，ADR-0014）：在 {@code Dist.CLIENT} 上注册
 * {@link NeoForgeAcceptanceClientCompanion}。
 *
 * <p>本类<b>仅客户端</b>（{@link OnlyIn}(Dist.CLIENT)）：由验收 mod 在 {@code FMLEnvironment.dist==Dist.CLIENT}
 * 守卫下调用，确保客户端专有的伴侣 / 验证器 / {@code Minecraft} 引用不被服务端加载。
 */
@OnlyIn(Dist.CLIENT)
public final class NeoForgeAcceptanceClientInit {

    private NeoForgeAcceptanceClientInit() {
        // 工具类不实例化
    }

    /**
     * 在验收控制通道上注册客户端伴侣（含 verifier ServiceLoader 发现 + 客户端控制监听 + tick 服务）。
     *
     * @param control 验收控制通道（{@code mpmt-test:acceptance}），由服务端验收通道构造期注册并传入
     */
    public static void activate(NeoForgeAcceptanceControlChannel control) {
        new NeoForgeAcceptanceClientCompanion().register(control);
    }
}
