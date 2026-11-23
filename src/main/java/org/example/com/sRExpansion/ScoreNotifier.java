package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 生存段位分变动时的 actionbar 提示。
 *
 * <p>每次分数变动都会在 actionbar 显示 {@code &c+X 生存段位分}，持续 3 秒；
 * 3 秒内再次变动会累加到同一个数值上并重新计时，而不是覆盖成新的增量。</p>
 */
public class ScoreNotifier {

    /** 提示持续时间（tick）。 */
    private static final long HOLD_TICKS = 60L;

    private final SRExpansion plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    private static final class Pending {
        private double total;
        private BukkitTask task;
    }

    public ScoreNotifier(SRExpansion plugin) {
        this.plugin = plugin;
    }

    /** 分数变动时调用；同一窗口内的多次变动会叠加。 */
    public void notify(UUID uuid, double delta) {
        if (uuid == null || delta == 0) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        Pending state = pending.computeIfAbsent(uuid, key -> new Pending());
        state.total += delta;
        if (state.task != null) state.task.cancel();

        show(player, state.total);

        state.task = Bukkit.getScheduler().runTaskLater(plugin, () -> pending.remove(uuid), HOLD_TICKS);
    }

    /** 玩家退出时清掉状态，避免残留任务。 */
    public void cancel(UUID uuid) {
        Pending state = pending.remove(uuid);
        if (state != null && state.task != null) state.task.cancel();
    }

    private void show(Player player, double total) {
        String text = (total >= 0 ? "&c+" : "&c") + format(total) + " 生存段位分";
        Component component = TextFormatter.parse(text);
        player.sendActionBar(component);
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format("%.2f", value);
    }
}
