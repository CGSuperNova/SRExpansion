package org.example.com.sRExpansion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SRExpansion extends JavaPlugin {
    private static SRExpansion instance;
    private ScoreDataManager dataManager;
    private RankManager rankManager;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        // base.yml / blocks.yml / mobs.yml / advancements.yml / ranks.yml 按需释放：
        // 不存在就写一份默认的，已存在则只补齐新增键，不覆盖管理员配置。

        rankManager = new RankManager(this);
        rankManager.load();

        dataManager = new ScoreDataManager(this);
        dataManager.loadConfig();

        Bukkit.getPluginManager().registerEvents(new StatisticsListener(this), this);
        Bukkit.getPluginManager().registerEvents(new BlockEventListener(this), this);
        Bukkit.getPluginManager().registerEvents(new AdvancementListener(this), this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new SRPlaceholder(this).register();
            getLogger().info("已注册 PlaceholderAPI 变量");
        } else {
            getLogger().warning("未找到 PlaceholderAPI，变量功能不可用");
        }

        Objects.requireNonNull(getCommand("srank")).setExecutor(new SRCommand(this));

        // 定时检查自动升级
        rankManager.startTask();

        // /reload 或热载入时已在线玩家也要初始化；同样放主线程，避免异步访问统计/进度
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) dataManager.initPlayer(player, false);
        });
    }

    @Override
    public void onDisable() {
        if (dataManager != null) dataManager.saveAllData();
    }

    public static SRExpansion getInstance() { return instance; }
    public ScoreDataManager getDataManager() { return dataManager; }
    public RankManager getRankManager() { return rankManager; }
}
