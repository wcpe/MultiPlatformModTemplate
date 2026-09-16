package top.wcpe.mc.mpmt.platform.forge.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.core.domain.port.ConnectionHandle;
import top.wcpe.mc.mpmt.core.domain.port.TransportPort;
import top.wcpe.mc.mpmt.platform.forge.ForgeClientHarness;
import top.wcpe.mc.mpmt.protocol.Packet;
import top.wcpe.mc.mpmt.protocol.PacketCodec;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;
import top.wcpe.mc.mpmt.protocol.packet.ServerMessagePacket;

/**
 * {@link ForgeHudSnapshot} 的取值与 {@link ForgeHudAdapter} 的收包 → 快照 → 调度渲染全链路。
 *
 * <p>收包用真 {@link PacketDispatcher} + 假transport端口驱动（把包字节投进收包回调）；调度与渲染用
 * {@link ForgeClientHarness} 装入的假 {@code Minecraft}。玩家缺席时渲染安全跳过（真实scenario是"包早于世界就绪"），
 * 该降级路径正是单元测试要钉住的行为；具体 title / chat 落点需真实客户端运行态，属 realserver 验收维度。
 */
class ForgeHudAdapterTest {

    @Test
    @DisplayName("快照：四个字段原样暴露")
    void 快照原样暴露字段() {
        ForgeHudSnapshot snapshot = new ForgeHudSnapshot(HudKind.TITLE, "主标题", "副标题", 2500L);

        assertEquals(HudKind.TITLE, snapshot.kind());
        assertEquals("主标题", snapshot.text());
        assertEquals("副标题", snapshot.subtitle());
        assertEquals(2500L, snapshot.durationMillis());
    }

    @Test
    @DisplayName("快照：零时长与空副标题可表达，字段不做边界裁剪")
    void 快照接受零时长() {
        ForgeHudSnapshot snapshot = new ForgeHudSnapshot(HudKind.CHAT, "文本", "", 0L);

        assertEquals(0L, snapshot.durationMillis());
        assertEquals("", snapshot.subtitle());
    }

    @Test
    @DisplayName("快照：类型 / 文本 / 副标题为空即拒绝")
    void 快照拒绝空字段() {
        assertThrows(
                NullPointerException.class,
                () -> new ForgeHudSnapshot(null, "文本", "副标题", 1L));
        assertThrows(
                NullPointerException.class,
                () -> new ForgeHudSnapshot(HudKind.CHAT, null, "副标题", 1L));
        assertThrows(
                NullPointerException.class,
                () -> new ForgeHudSnapshot(HudKind.CHAT, "文本", null, 1L));
    }

    @Test
    @DisplayName("adapter：初始快照为空")
    void adapter初始快照为空() {
        assertEquals(null, new ForgeHudAdapter().snapshot());
        assertEquals(null, new ForgeHudAdapter().snapshot(), "重复读取不应产生快照");
    }

    @Test
    @DisplayName("adapter：收包写入快照并调度一次客户端渲染任务，clear 后清空")
    void adapter收包写入快照并可清空() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertNotNull(harness.客户端(), "测试替身应先装入假客户端");
            Case scenario = new Case();

            scenario.deliver(new ServerHudMessagePacket(HudKind.ACTIONBAR, "在线心跳", "", 1000L));

            ForgeHudSnapshot snapshot = scenario.adapter().snapshot();
            assertEquals(HudKind.ACTIONBAR, snapshot.kind());
            assertEquals("在线心跳", snapshot.text());
            assertEquals("", snapshot.subtitle());
            assertEquals(1000L, snapshot.durationMillis());
            assertEquals(1, harness.待处理任务数(), "收包应调度一次客户端渲染任务");

            scenario.adapter().clear();
            assertEquals(null, scenario.adapter().snapshot(), "clear 后快照应被释放");
        }
    }

    @Test
    @DisplayName("adapter：连续收包后快照为最后一次，且每次各调度一次渲染")
    void adapter快照为最后一次收包() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            Case scenario = new Case();

            scenario.deliver(new ServerHudMessagePacket(HudKind.TITLE, "第一次", "副一", 500L));
            scenario.deliver(new ServerHudMessagePacket(HudKind.CHAT, "第二次", "副二", 700L));

            ForgeHudSnapshot snapshot = scenario.adapter().snapshot();
            assertEquals(HudKind.CHAT, snapshot.kind());
            assertEquals("第二次", snapshot.text());
            assertEquals("副二", snapshot.subtitle());
            assertEquals(700L, snapshot.durationMillis());
            assertEquals(2, harness.待处理任务数());
        }
    }

    @Test
    @DisplayName("adapter：收包重建快照实例，不复用旧对象")
    void adapter快照实例独立() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertNotNull(harness.客户端(), "测试替身应先装入假客户端");
            Case scenario = new Case();

            scenario.deliver(new ServerHudMessagePacket(HudKind.CHAT, "甲", "", 1L));
            ForgeHudSnapshot first = scenario.adapter().snapshot();
            scenario.deliver(new ServerHudMessagePacket(HudKind.CHAT, "甲", "", 1L));
            ForgeHudSnapshot second = scenario.adapter().snapshot();

            assertNotSame(first, second, "快照应为不可变值对象、每次重建");
            assertEquals(first.text(), second.text());
            assertEquals(first.durationMillis(), second.durationMillis());
        }
    }

    @Test
    @DisplayName("adapter：只认 SERVER_HUD_MESSAGE，其它包型不影响快照也不调度渲染")
    void adapter忽略异型包() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            Case scenario = new Case();

            scenario.deliver(new ServerMessagePacket("普通消息"));

            assertEquals(null, scenario.adapter().snapshot(), "非 HUD 包不应产生快照");
            assertEquals(0, harness.待处理任务数(), "非 HUD 包不应调度渲染");
        }
    }

    @Test
    @DisplayName("渲染：玩家缺席时安静跳过，不抛异常且快照仍在收包时写入")
    void 渲染玩家缺席时降级() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertEquals(null, harness.客户端().player, "假客户端不带玩家，模拟世界未就绪");
            Case scenario = new Case();

            scenario.deliver(new ServerHudMessagePacket(HudKind.CHAT, "无玩家", "", 1000L));
            harness.跑完客户端任务();

            assertEquals("无玩家", scenario.adapter().snapshot().text(), "快照在收包时写入");
            assertEquals(0, harness.待处理任务数(), "渲染任务应已被消费且安静结束");
        }
    }

    @Test
    @DisplayName("渲染：各 HUD 类型在玩家缺席下均安静跳过（不按类型抛异常）")
    void 各类型渲染在玩家缺席下均降级() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            Case scenario = new Case();

            for (HudKind kind : HudKind.values()) {
                scenario.deliver(new ServerHudMessagePacket(kind, "文本-" + kind, "副", 1000L));
                harness.跑完客户端任务();
                assertEquals(
                        "文本-" + kind,
                        scenario.adapter().snapshot().text(),
                        "每类型都应写入快照且渲染安静跳过：" + kind);
            }
            assertEquals(0, harness.待处理任务数(), "全部渲染任务应已被消费");
        }
    }

    @Test
    @DisplayName("adapter：注册空dispatcher即拒绝")
    void adapter拒绝空dispatcher() {
        assertThrows(NullPointerException.class, () -> new ForgeHudAdapter().register(null));
    }

    @Test
    @DisplayName("adapter：注册两次均以最新dispatcher受理收包")
    void adapter可重复注册() {
        try (ForgeClientHarness harness = ForgeClientHarness.install()) {
            assertNotNull(harness.客户端(), "测试替身应先装入假客户端");
            ForgeHudAdapter adapter = new ForgeHudAdapter();
            RecordingTransport first = new RecordingTransport();
            RecordingTransport second = new RecordingTransport();
            adapter.register(new PacketDispatcher(first, new PacketCodec()));
            adapter.register(new PacketDispatcher(second, new PacketCodec()));

            first.deliver(new ServerHudMessagePacket(HudKind.CHAT, "旧通道", "", 1L));
            second.deliver(new ServerHudMessagePacket(HudKind.TOAST, "新通道", "", 2L));

            assertEquals(HudKind.TOAST, adapter.snapshot().kind());
            assertEquals("新通道", adapter.snapshot().text());
        }
    }

    /** 一条Case的装配：真dispatcher + 假transport端口 + 被测adapter。 */
    private static final class Case {
        private final RecordingTransport transport = new RecordingTransport();
        private final PacketDispatcher dispatcher = new PacketDispatcher(transport, new PacketCodec());
        private final ForgeHudAdapter adapter = new ForgeHudAdapter();

        Case() {
            adapter.register(dispatcher);
        }

        ForgeHudAdapter adapter() {
            return adapter;
        }

        void deliver(Packet packet) {
            transport.deliver(packet);
        }
    }

    /** 记录收发的最小transport端口替身：把编码后的包字节回灌进收包回调。 */
    private static final class RecordingTransport implements TransportPort {
        private final List<byte[]> sent = new ArrayList<byte[]>();
        private BiConsumer<ConnectionHandle, byte[]> receiver;

        @Override
        public void send(ConnectionHandle connection, byte[] data) {
            sent.add(data);
        }

        @Override
        public void send(byte[] data) {
            sent.add(data);
        }

        @Override
        public void onReceive(BiConsumer<ConnectionHandle, byte[]> handler) {
            this.receiver = handler;
        }

        @Override
        public int maxPayloadSize() {
            return 32767;
        }

        /** 把包编码成字节后投进收包回调（模拟一次真实入站）。 */
        void deliver(Packet packet) {
            receiver.accept(null, new PacketCodec().encode(packet));
        }
    }
}
