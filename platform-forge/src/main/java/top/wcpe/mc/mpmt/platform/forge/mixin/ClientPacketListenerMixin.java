package top.wcpe.mc.mpmt.platform.forge.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.wcpe.mc.mpmt.platform.forge.net.ForgeRawPayloadRouter;

/**
 * 客户端入站自定义负载拦截（L3 客户端，ADR-0018）：在原版 {@code ClientPacketListener#handleCustomPayload} 最前注入，
 * 识别我方通道（经 {@link ForgeRawPayloadRouter}）则取出裸字节、切客户端线程分发后取消原版处理。
 *
 * <p><b>为何用 Mixin</b>：Forge 判定对端为 vanilla 连接（连 Bukkit/Paper 服）后门控掉 modded 通道入站派发，
 * Forge 客户端收不到 Bukkit 服的裸包；本拦截挂在原版收包入口、不看对端是否 Forge，故对 Forge 服与 Bukkit 服都触发。
 *
 * <p><b>线程</b>：{@code @At("HEAD")} 首趟在 netty 网络线程执行（早于原版 {@code ensureRunningOnSameThread} 的线程切换）；
 * 此处仅同步读通道判定是否拦截 + 取裸字节（{@code getData()} 返回副本、不扰原版），随后 {@code Minecraft#execute} 切
 * 客户端主线程分发（与旧 SimpleChannel 主线程语义一致），并 {@code cancel} 取消原版后续（不再走原版/重排程）。
 */
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void mpmt$onCustomPayload(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        ResourceLocation channel = packet.getIdentifier();
        if (!ForgeRawPayloadRouter.hasClient(channel)) {
            return; // 非我方通道：放行原版
        }
        FriendlyByteBuf data = packet.getData();
        byte[] bytes = ForgeRawPayloadRouter.readAll(data);
        Minecraft.getInstance().execute(() -> ForgeRawPayloadRouter.dispatchClient(channel, bytes));
        ci.cancel();
    }
}
