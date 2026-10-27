package org.example.com.sRExpansion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

public class StatisticsListener implements Listener {
    private final SRExpansion plugin;
    public StatisticsListener(SRExpansion plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onStatisticIncrement(PlayerStatisticIncrementEvent event) {
        if (event.isCancelled()) return;
        Player p = event.getPlayer();
        Statistic stat = event.getStatistic();
        int inc = event.getNewValue() - event.getPreviousValue();
        if (inc <= 0) return;
        if (stat == Statistic.MOB_KILLS || stat == Statistic.PLAYER_KILLS) return;

        plugin.getDataManager().updateStatistic(p.getUniqueId(), stat, inc, event.getMaterial(), event.getEntityType());
        plugin.getDataManager().checkThresholds(p);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        plugin.getDataManager().applyDeathPenalty(p);
        Player killer = p.getKiller();
        if (killer != null && !killer.equals(p)) plugin.getDataManager().addPlayerKill(killer, p);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // 必须主线程：initPlayer 会遍历原版统计与进度，Bukkit API 不是线程安全的。
        // 之前放在异步线程里，1.21 下会直接抛异常，导致玩家永远拿不到数据。
        plugin.getDataManager().initPlayer(p, false);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        plugin.getDataManager().unloadPlayer(event.getPlayer().getUniqueId());
    }
}