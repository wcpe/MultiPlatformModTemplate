package top.wcpe.mc.mpmt.platform.fabric.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** HUD 快照值对象：不可变、逐字段暴露、空值失败快。 */
class HudSnapshotTest {

    @Test
    @DisplayName("逐字段暴露构造入参，时长原样保留")
    void 字段保真() {
        HudSnapshot snapshot = new HudSnapshot(HudKind.TOAST, "正文", "副文", 4321L);

        assertEquals(HudKind.TOAST, snapshot.kind());
        assertEquals("正文", snapshot.text());
        assertEquals("副文", snapshot.subtitle());
        assertEquals(4321L, snapshot.durationMillis());
    }

    @Test
    @DisplayName("空 kind / text / subtitle 失败快，不允许残缺快照")
    void 空值失败快() {
        assertThrows(
                NullPointerException.class,
                () -> new HudSnapshot(null, "正文", "副文", 0L));
        assertThrows(
                NullPointerException.class,
                () -> new HudSnapshot(HudKind.CHAT, null, "副文", 0L));
        assertThrows(
                NullPointerException.class,
                () -> new HudSnapshot(HudKind.CHAT, "正文", null, 0L));
    }

    @Test
    @DisplayName("时长边界：0 表示平台默认、负值表示不定长，均原样保留")
    void 时长边界() {
        assertEquals(0L, new HudSnapshot(HudKind.TITLE, "标题", "", 0L).durationMillis());
        assertEquals(-1L, new HudSnapshot(HudKind.ACTIONBAR, "动作", "", -1L).durationMillis());
        assertEquals(
                Long.MAX_VALUE,
                new HudSnapshot(HudKind.TOAST, "正文", "副文", Long.MAX_VALUE).durationMillis());
        assertNotEquals(
                new HudSnapshot(HudKind.CHAT, "文本", "", 0L),
                new HudSnapshot(HudKind.CHAT, "文本", "", 1L),
                "快照为普通对象身份语义（未重写 equals），不同时长必须彼此可区分");
    }
}
