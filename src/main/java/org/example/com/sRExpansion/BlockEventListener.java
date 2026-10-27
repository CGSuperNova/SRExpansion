package org.example.com.sRExpansion;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockEventListener implements Listener {
    private final SRExpansion plugin;
    private final Map<String, Long> placedBlocks = new ConcurrentHashMap<>();
    /**
     * 记住“这个位置的特殊方块是玩家放的”多久。
     * 原来只有 30 秒，放下矿等一会儿再挖就会被当成自然生成白拿额外分；这里放宽到 10 分钟。
     * 反刷分的判定在 ScoreDataManager.consumeArtificialBreak(3 秒内有效) 里做最终确认。
     */
    private static final long CACHE_DURATION = 10 * 60 * 1000L;
    private static final int MAX_TRACKED_BLOCKS = 100_000;

    public BlockEventListener(SRExpansion plugin) {
        this.plugin = plugin;
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                placedBlocks.entrySet().removeIf(entry -> entry.getValue() < now);
            }
        }.runTaskTimer(plugin, 100L, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (plugin.getDataManager().getExtraBlockScore(type) > 0) {
            if (placedBlocks.size() >= MAX_TRACKED_BLOCKS) placedBlocks.clear();
            String key = getLocationKey(block.getLocation());
            placedBlocks.put(key, System.currentTimeMillis() + CACHE_DURATION);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        if (plugin.getDataManager().getExtraBlockScore(type) <= 0) return;

        String key = getLocationKey(block.getLocation());
        boolean isPlaced = placedBlocks.containsKey(key);
        if (isPlaced) {
            plugin.getDataManager().addArtificialBlockBreak(event.getPlayer(), type);
        }
        placedBlocks.remove(key);
    }

    private String getLocationKey(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }
}