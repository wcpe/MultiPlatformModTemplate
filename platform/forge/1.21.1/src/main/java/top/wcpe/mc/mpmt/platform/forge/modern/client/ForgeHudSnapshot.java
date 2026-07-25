package top.wcpe.mc.mpmt.platform.forge.modern.client;

import top.wcpe.mc.mpmt.protocol.packet.HudKind;

/** 最近一次产品 HUD 的不可变快照。 */
public record ForgeHudSnapshot(HudKind kind, String text, String subtitle, long durationMillis) {
}
