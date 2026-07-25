package top.wcpe.mc.mpmt.platform.forge.net;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Forge 裸 CustomPayload 收包路由（L3，ADR-0018）：Mixin 在原版收包入口（{@code handleCustomPayload}）拦下我方通道的
 * 裸 payload 后，交本路由按通道分发到注册的处理器。**统一 Forge↔Forge 与 Forge↔Bukkit 的收包**——拦截挂在原版包层、
 * 不看对端是否 Forge，故对 vanilla/Bukkit 服也触发（绕过 Forge 对 vanilla 连接的 modded 通道门控）。
 *
 * <p><b>生命周期</b>：处理器在启动期一次性注册（产品传输装配 / 客户端代理 init / 验收通道构造），运行期只读分发——
 * 与 {@code PlatformProvider} Holder 同模式（装配后只读）；用并发表仅为注册/分发跨线程可见，不承载可变业务状态。
 * Mixin 经 ASM 注入原版类，只能经<b>静态</b>入口回调我方代码，故本路由为静态桥。
 *
 * <p><b>线程</b>：1.20.1 的 {@code handleCustomPayload} 已在主/服务端线程执行（原版 {@code ensureRunningOnSameThread}
 * 之后），故分发在主线程，与 Bukkit 收包一致；下游处理器若需切归属线程仍经 {@code SchedulerPort}（ADR-0013）。
 */
public final class ForgeRawPayloadRouter {

    /** 客户端收包处理器（按通道）：收来自服务端的裸 payload。 */
    private static final Map<ResourceLocation, Consumer<byte[]>> CLIENT_HANDLERS = new ConcurrentHashMap<>();

    /** 服务端收包处理器（按通道）：收来自客户端的裸 payload（带发送方）。 */
    private static final Map<ResourceLocation, BiConsumer<ServerPlayer, byte[]>> SERVER_HANDLERS =
            new ConcurrentHashMap<>();

    private ForgeRawPayloadRouter() {
        // 静态桥不实例化
    }

    /** 启动期注册客户端收包处理器（收服务端→客户端裸 payload，如 HUD / RunStep）。 */
    public static void registerClient(ResourceLocation channel, Consumer<byte[]> handler) {
        CLIENT_HANDLERS.put(channel, handler);
    }

    /** 启动期注册服务端收包处理器（收客户端→服务端裸 payload，如握手 / ClientReady）。 */
    public static void registerServer(ResourceLocation channel, BiConsumer<ServerPlayer, byte[]> handler) {
        SERVER_HANDLERS.put(channel, handler);
    }

    /** 是否有客户端处理器关注此通道（Mixin 在网络线程据此同步决定是否拦截 cancel）。 */
    public static boolean hasClient(ResourceLocation channel) {
        return CLIENT_HANDLERS.containsKey(channel);
    }

    /** 是否有服务端处理器关注此通道。 */
    public static boolean hasServer(ResourceLocation channel) {
        return SERVER_HANDLERS.containsKey(channel);
    }

    /** 立即读出缓冲全部可读字节为 {@code byte[]}（解耦原版 buffer，便于切线程后分发）。 */
    public static byte[] readAll(FriendlyByteBuf buf) {
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        return data;
    }

    /**
     * 客户端分发：命中我方通道则交处理器并返回 {@code true}（Mixin 据此 {@code cancel} 拦下、不再走原版）；
     * 未命中返回 {@code false}（放行原版/Forge 处理）。
     */
    public static boolean dispatchClient(ResourceLocation channel, byte[] data) {
        Consumer<byte[]> handler = CLIENT_HANDLERS.get(channel);
        if (handler == null) {
            return false;
        }
        handler.accept(data);
        return true;
    }

    /** 服务端分发：命中我方通道则交处理器并返回 {@code true}，未命中返回 {@code false}。 */
    public static boolean dispatchServer(ServerPlayer sender, ResourceLocation channel, byte[] data) {
        BiConsumer<ServerPlayer, byte[]> handler = SERVER_HANDLERS.get(channel);
        if (handler == null) {
            return false;
        }
        handler.accept(sender, data);
        return true;
    }
}
