package org.example.com.sRExpansion;

import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class SRExpansion extends JavaPlugin {
    private static SRExpansion instance;
    private ScoreDataManager dataManager;
    private RankManager rankManager;
    private RankupDisplay rankupDisplay;
    private AttributeBridge attributeBridge;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        // base.yml / blocks.yml / mobs.yml / advancements.yml / ranks.yml 按需释放：
        // 不存在就写一份默认的，已存在则只补齐新增键，不覆盖管理员配置。

        // 属性插件是可选依赖：装了就把段位属性奖励同步过去，没装则全部空操作
        attributeBridge = new AttributeBridge();
        attributeBridge.init();
        if (attributeBridge.isAvailable()) {
            getLogger().info("已连接 AdvancedPlayerAttribute，段位属性奖励生效");
        }

        rankManager = new RankManager(this);
        rankManager.load();

        dataManager = new ScoreDataManager(this);
        dataManager.loadConfig();

        rankupDisplay = new RankupDisplay(this);

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

        // 初始化bStats服务
        int pluginId = 35105;
        Metrics metrics = new Metrics(this, pluginId);

    }

    @Override
    public void onDisable() {
        if (rankupDisplay != null) rankupDisplay.shutdown();
        if (dataManager != null) dataManager.saveAllData();
    }

    public static SRExpansion getInstance() { return instance; }
    public ScoreDataManager getDataManager() { return dataManager; }
    public RankManager getRankManager() { return rankManager; }
    public RankupDisplay getRankupDisplay() { return rankupDisplay; }
    public AttributeBridge getAttributeBridge() { return attributeBridge; }
}
