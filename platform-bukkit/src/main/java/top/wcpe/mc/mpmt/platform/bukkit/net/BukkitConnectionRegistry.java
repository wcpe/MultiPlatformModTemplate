package top.wcpe.mc.mpmt.platform.bukkit.net;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;

/** Bukkit 物理连接登记表：按玩家对象身份关联句柄，并按 UUID 标记当前连接代际。 */
public final class BukkitConnectionRegistry {

    private final Map<Player, BukkitConnectionHandle> byPlayer = new IdentityHashMap<>();
    private final Map<UUID, BukkitConnectionHandle> current = new ConcurrentHashMap<>();

    /** 登记新物理连接；同 UUID 的旧连接立即失去当前资格。 */
    public synchronized BukkitConnectionHandle connected(Player player) {
        BukkitConnectionHandle handle = new BukkitConnectionHandle(player.getUniqueId());
        byPlayer.put(player, handle);
        current.put(handle.playerId(), handle);
        return handle;
    }

    /** 取得该原生玩家对象对应的物理句柄；缺失时补登记。 */
    public synchronized BukkitConnectionHandle handleOf(Player player) {
        BukkitConnectionHandle handle = byPlayer.get(player);
        return handle == null ? connected(player) : handle;
    }

    /** 移除该原生玩家对象；旧连接迟到退出不会移除同 UUID 的新连接。 */
    public synchronized BukkitConnectionHandle disconnected(Player player) {
        BukkitConnectionHandle handle = byPlayer.remove(player);
        if (handle != null) {
            removeIfCurrent(handle);
        }
        return handle;
    }

    /** 判断句柄是否仍是该 UUID 的当前物理连接。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public boolean isCurrent(BukkitConnectionHandle handle) {
        return current.get(handle.playerId()) == handle;
    }

    /** 判断重查到的在线玩家是否仍对应指定物理句柄。 */
    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    public synchronized boolean isCurrent(BukkitConnectionHandle handle, Player player) {
        return current.get(handle.playerId()) == handle && byPlayer.get(player) == handle;
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private void removeIfCurrent(BukkitConnectionHandle expected) {
        current.computeIfPresent(
                expected.playerId(),
                (playerId, actual) -> actual == expected ? null : actual);
    }
}
