package top.wcpe.mc.mpmt.platform.forge.mixin;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeRawPayloadRouter;

/**
 * 服务端入站自定义负载拦截（L3 服务端，ADR-0018）：在原版 {@code ServerGamePacketListenerImpl#handleCustomPayload}
 * 最前注入，识别我方通道则取出裸字节、带发送方 {@link ServerPlayer} 切服务端主线程分发后取消原版处理。
 *
 * <p>与 {@link ClientPacketListenerMixin} 对称：用于 Forge 作<b>服务端</b>时收 Forge 客户端的裸包（Forge↔Forge）；
 * Forge↔Bukkit 时服务端是 Bukkit、用其原生 Messenger，不经本拦截。统一裸字节收发见 ADR-0018。
 *
 * <p><b>线程</b>：{@code @At("HEAD")} 首趟在网络线程；同步判定 + 取裸字节后经 {@code MinecraftServer#execute} 切服务端
 * 主线程分发（与旧 SimpleChannel 主线程语义一致），并 {@code cancel}。
 */
@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    /** 由 Mixin 生成字段访问器，避免 ForgeGradle 直接改写本类成员名。 */
    @Accessor("player")
    public abstract ServerPlayer mpmt$getPlayer();

    // PMD 误报：该方法不被 Java 代码调用，而由 Mixin 字节码变换器在运行期织入原版方法作回调（@Inject）；
    // 静态分析看不到此调用链，故按构造精确抑制（static-analysis §3：@SuppressWarnings 配合说明）。
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void mpmt$onCustomPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        ResourceLocation channel = packet.getIdentifier();
        if (!ForgeRawPayloadRouter.hasServer(channel)) {
            return; // 非我方通道：放行原版
        }
        FriendlyByteBuf data = packet.getData();
        byte[] bytes = ForgeRawPayloadRouter.readAll(data);
        ServerPlayer sender = mpmt$getPlayer();
        sender.server.execute(() -> ForgeRawPayloadRouter.dispatchServer(sender, channel, bytes));
        ci.cancel();
    }
}
