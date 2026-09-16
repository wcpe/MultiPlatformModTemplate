package top.wcpe.mc.mpmt.platform.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.platform.forge.client.ForgeClientSession;

/**
 * {@link MpmtForgeMod} 的客户端产品入口状态机：可选网络校验、连接 / 断线状态流转、tick 触发握手。
 *
 * <p>{@code preInitialize} 需真实 FML 运行态（{@code ForgeClientTransport} 的构造依赖
 * {@code FMLCommonHandler}），故本类只覆盖其余入口——它们的事件对象可用
 * {@code sun.misc.Unsafe.allocateInstance} 造出，且会话用<a href="#会话">真实</a>{@link ForgeClientSession}
 * 配假端口装配，从而观察"是否握手 / 是否断线清理"这一真实行为而非替身计数。
 *
 * <p>静态状态在每个用例结束复位，避免用例间互相污染。
 */
class MpmtForgeModTest {

    private ForgeTestSupport.FakeClientTransport transport;
    private ForgeTestSupport.FakeHud hud;

    @AfterEach
    void 复位静态状态() throws Exception {
        设静态("activeSession", null);
        设静态("connected", Boolean.FALSE);
        设静态("optionalCheckAccepted", Boolean.FALSE);
        设静态("remoteForgeProductAbsent", Boolean.FALSE);
    }

    @Test
    @DisplayName("常量：mod id 与产品通道稳定")
    void 常量稳定() {
        assertEquals("mpmt", MpmtForgeMod.MOD_ID);
        assertEquals("MPMT", MpmtForgeMod.PRODUCT_CHANNEL);
    }

    @Test
    @DisplayName("入口注解：@Mod 声明 client-only、可acceptedremoteVersions与元数据来源")
    void 入口注解声明clientOnly() {
        net.minecraftforge.fml.common.Mod annotation =
                MpmtForgeMod.class.getAnnotation(net.minecraftforge.fml.common.Mod.class);

        assertNotNull(annotation, "入口必须带 @Mod");
        assertEquals(MpmtForgeMod.MOD_ID, annotation.modid());
        assertTrue(annotation.clientSideOnly(), "1.12.2 产品为 client-only");
        assertEquals("*", annotation.acceptableRemoteVersions(), "应允许连接未装 mod 的服务端");
        assertEquals("[1.12.2]", annotation.acceptedMinecraftVersions());
        assertTrue(annotation.useMetadata(), "版本取自 mcmod.info 元数据");
    }

    @Test
    @DisplayName("可选校验：远端为服务端且未装我方 mod 时标记缺席并放行")
    void 可选校验放行未装mod的服务端() {
        MpmtForgeMod mod = new MpmtForgeMod();
        Map<String, String> remoteVersions = new HashMap<String, String>();
        remoteVersions.put("other", "1.0");

        boolean accepted = mod.acceptRemoteVersions(remoteVersions, Side.SERVER);

        assertTrue(accepted, "应允许连接未安装我方 mod 的服务端");
        assertTrue(MpmtForgeMod.optionalCheckAccepted());
        assertTrue(MpmtForgeMod.remoteForgeProductAbsent(), "远端缺 mpmt 条目应标记为缺席");
    }

    @Test
    @DisplayName("可选校验：远端已装我方 mod 时标记不缺席")
    void 可选校验识别已装mod() {
        MpmtForgeMod mod = new MpmtForgeMod();
        Map<String, String> remoteVersions = new HashMap<String, String>();
        remoteVersions.put(MpmtForgeMod.MOD_ID, "9.9");

        boolean accepted = mod.acceptRemoteVersions(remoteVersions, Side.SERVER);

        assertTrue(accepted);
        assertTrue(MpmtForgeMod.optionalCheckAccepted());
        assertFalse(MpmtForgeMod.remoteForgeProductAbsent(), "远端有 mpmt 条目则不算缺席");
    }

    @Test
    @DisplayName("可选校验：远端为客户端时不置位任何状态")
    void 可选校验在客户端侧不置位() {
        MpmtForgeMod mod = new MpmtForgeMod();

        boolean accepted = mod.acceptRemoteVersions(new HashMap<String, String>(), Side.CLIENT);

        assertTrue(accepted, "始终返回 true");
        assertFalse(MpmtForgeMod.optionalCheckAccepted(), "客户端侧不应置位可选校验标记");
        assertFalse(MpmtForgeMod.remoteForgeProductAbsent());
    }

    @Test
    @DisplayName("连接：置位已连接并复位本次连接的握手标记")
    void 连接置位状态() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        设字段(mod, "joinStarted", Boolean.TRUE);

        mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));

        assertTrue(MpmtForgeMod.isConnected());
        assertFalse((Boolean) 取字段(mod, "joinStarted"), "新连接应复位握手标记");
    }

    @Test
    @DisplayName("断线：复位全部状态并拆掉产品会话（清空收包器与 HUD）")
    void 断线复位全部状态() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        装真实session(mod);
        mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));
        设静态("optionalCheckAccepted", Boolean.TRUE);
        设静态("remoteForgeProductAbsent", Boolean.TRUE);
        设字段(mod, "joinStarted", Boolean.TRUE);

        mod.onDisconnected(事件(FMLNetworkEvent.ClientDisconnectionFromServerEvent.class));

        assertFalse(MpmtForgeMod.isConnected());
        assertFalse(MpmtForgeMod.optionalCheckAccepted());
        assertFalse(MpmtForgeMod.remoteForgeProductAbsent());
        assertFalse((Boolean) 取字段(mod, "joinStarted"));
        assertEquals(1, transport.clearCount, "断线应清空收包器");
        assertEquals(1, hud.clearCount, "断线应清空 HUD");
    }

    @Test
    @DisplayName("tick：未连接时不触发握手")
    void tick未连接时不握手() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        ForgeClientSession session = 装真实session(mod);

        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertNotNull(harness.客户端(), "测试替身应先装入假客户端");
            // onClientTick 先读 Minecraft.getMinecraft().player，故必须装入假客户端
            mod.onClientTick(tick事件(TickEvent.Phase.END));
        }

        assertFalse(transport.isReceiverRegistered(), "未连接不得装配会话");
        assertEquals(null, session.networkFeature());
        assertFalse((Boolean) 取字段(mod, "joinStarted"));
    }

    @Test
    @DisplayName("tick：非 END 阶段不触发握手")
    void tick非END阶段不握手() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        ForgeClientSession session = 装真实session(mod);
        mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));

        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            设玩家(harness);
            mod.onClientTick(tick事件(TickEvent.Phase.START));
        }

        assertEquals(null, session.networkFeature(), "START 阶段不得握手");
    }

    @Test
    @DisplayName("tick：玩家未就绪时不触发握手")
    void tick玩家未就绪不握手() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        ForgeClientSession session = 装真实session(mod);
        mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));

        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertEquals(null, harness.客户端().player, "假客户端不带玩家，模拟世界未就绪");
            mod.onClientTick(tick事件(TickEvent.Phase.END));
        }

        assertEquals(null, session.networkFeature(), "玩家未就绪不得握手");
    }

    @Test
    @DisplayName("tick：已连接且玩家就绪时触发一次握手，重复 tick 不重复触发")
    void tick就绪时只握手一次() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        ForgeClientSession session = 装真实session(mod);
        mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));

        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            设玩家(harness);
            mod.onClientTick(tick事件(TickEvent.Phase.END));
            assertNotNull(session.networkFeature(), "就绪后应完成握手装配");
            int clearCountAfterHandshake = transport.clearCount;

            mod.onClientTick(tick事件(TickEvent.Phase.END));
            assertEquals(clearCountAfterHandshake, transport.clearCount, "同一次连接不应重复装配会话");
        }

        assertTrue((Boolean) 取字段(mod, "joinStarted"));
    }

    @Test
    @DisplayName("tick：断线后重新连接可再次握手")
    void 重连后可再次握手() throws Exception {
        MpmtForgeMod mod = new MpmtForgeMod();
        装真实session(mod);

        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            设玩家(harness);
            mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));
            mod.onClientTick(tick事件(TickEvent.Phase.END));
            int first = transport.clearCount;

            mod.onDisconnected(事件(FMLNetworkEvent.ClientDisconnectionFromServerEvent.class));
            mod.onConnected(事件(FMLNetworkEvent.ClientConnectedToServerEvent.class));
            mod.onClientTick(tick事件(TickEvent.Phase.END));

            assertTrue(transport.clearCount > first, "重连后应再次装配会话");
        }
    }

    @Test
    @DisplayName("会话接缝：未初始化时失败快，装配后返回同一实例")
    void 会话接缝() throws Exception {
        设静态("activeSession", null);
        IllegalStateException error =
                assertThrows(IllegalStateException.class, MpmtForgeMod::session);
        assertTrue(error.getMessage().contains("尚未初始化"));

        MpmtForgeMod mod = new MpmtForgeMod();
        ForgeClientSession session = 装真实session(mod);
        设静态("activeSession", session);

        assertSame(session, MpmtForgeMod.session());
    }

    @Test
    @DisplayName("只读接缝：初始态全部为否")
    void 只读接缝初始态() {
        assertFalse(MpmtForgeMod.isConnected());
        assertFalse(MpmtForgeMod.optionalCheckAccepted());
        assertFalse(MpmtForgeMod.remoteForgeProductAbsent());
    }

    /** 造一个真实客户端会话（假传输 / 假 HUD），并塞进 {@code session} 字段。 */
    private ForgeClientSession 装真实session(MpmtForgeMod mod) throws Exception {
        transport = new ForgeTestSupport.FakeClientTransport();
        hud = new ForgeTestSupport.FakeHud();
        设字段(mod, "session", ForgeTestSupport.session(transport, hud));
        设静态("activeSession", 取字段(mod, "session"));
        return (ForgeClientSession) 取字段(mod, "session");
    }

    /** 给假客户端装入一个非空玩家引用，使 tick 的就绪判空通过。 */
    private static void 设玩家(ForgeClientHarness harness) throws Exception {
        设字段(harness.客户端(), "player", 未初始化(net.minecraft.client.entity.EntityPlayerSP.class));
    }

    private static <T> T 事件(Class<T> type) {
        return type.cast(未初始化(type));
    }

    private static TickEvent.ClientTickEvent tick事件(TickEvent.Phase phase) {
        TickEvent.ClientTickEvent event = 事件(TickEvent.ClientTickEvent.class);
        try {
            设继承字段(event, TickEvent.class, "phase", phase);
        } catch (Exception error) {
            throw new IllegalStateException("无法设置 tick 阶段", error);
        }
        return event;
    }

    private static Object 未初始化(Class<?> type) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object unsafe = field.get(null);
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("无法构造测试替身：" + type.getName(), error);
        }
    }

    private static void 设静态(String name, Object value) throws Exception {
        Field field = MpmtForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void 设字段(Object target, String name, Object value) throws Exception {
        Field field = 找字段(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 沿类层次向上查找字段（{@code Minecraft.player} 声明在父类上）。 */
    private static Field 找字段(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void 设继承字段(Object target, Class<?> owner, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object 取字段(Object target, String name) throws Exception {
        Field field = MpmtForgeMod.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
