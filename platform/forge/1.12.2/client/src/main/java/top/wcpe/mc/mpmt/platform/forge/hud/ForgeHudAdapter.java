package top.wcpe.mc.mpmt.platform.forge.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import top.wcpe.mc.mpmt.protocol.PacketDispatcher;
import top.wcpe.mc.mpmt.protocol.PacketIds;
import top.wcpe.mc.mpmt.protocol.packet.ServerHudMessagePacket;

/** Forge 1.12.2 最小 HUD 渲染与验收快照。 */
public final class ForgeHudAdapter implements ForgeHudPort {

    private static final int DEFAULT_STAY_TICKS = 60;

    private volatile ForgeHudSnapshot lastSnapshot;

    @Override
    public void register(PacketDispatcher dispatcher) {
        dispatcher.on(
                PacketIds.SERVER_HUD_MESSAGE,
                (connection, packet) -> receive((ServerHudMessagePacket) packet));
    }

    @Override
    public ForgeHudSnapshot snapshot() {
        return lastSnapshot;
    }

    @Override
    public void clear() {
        lastSnapshot = null;
    }

    private void receive(ServerHudMessagePacket packet) {
        lastSnapshot =
                new ForgeHudSnapshot(
                        packet.getKind(),
                        packet.getText(),
                        packet.getSubtitle(),
                        packet.getDurationMillis());
        Minecraft.getMinecraft().addScheduledTask(() -> render(packet));
    }

    private static void render(ServerHudMessagePacket packet) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null) {
            return;
        }
        TextComponentString text = new TextComponentString(packet.getText());
        switch (packet.getKind()) {
            case TITLE:
                minecraft.ingameGUI.displayTitle(
                        packet.getText(),
                        packet.getSubtitle(),
                        10,
                        stayTicks(packet.getDurationMillis()),
                        10);
                break;
            case CHAT:
                minecraft.player.sendMessage(text);
                break;
            case ACTIONBAR:
            case TOAST:
            default:
                minecraft.player.sendStatusMessage(text, true);
                break;
        }
    }

    private static int stayTicks(long durationMillis) {
        if (durationMillis <= 0L) {
            return DEFAULT_STAY_TICKS;
        }
        long ticks = Math.max(1L, durationMillis / 50L);
        return ticks > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }
}
