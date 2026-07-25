package top.wcpe.mc.mpmt.platform.forge.hud;

import java.util.Objects;
import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** 最近一次处理的不可变 HUD 快照。 */
public final class ForgeHudSnapshot {

    private final HudKind kind;
    private final String text;
    private final String subtitle;
    private final long durationMillis;

    public ForgeHudSnapshot(HudKind kind, String text, String subtitle, long durationMillis) {
        this.kind = Objects.requireNonNull(kind, "kind 不能为空");
        this.text = Objects.requireNonNull(text, "text 不能为空");
        this.subtitle = Objects.requireNonNull(subtitle, "subtitle 不能为空");
        this.durationMillis = durationMillis;
    }

    public HudKind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    public String subtitle() {
        return subtitle;
    }

    public long durationMillis() {
        return durationMillis;
    }
}
