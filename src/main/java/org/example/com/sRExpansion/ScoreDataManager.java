package org.example.com.sRExpansion;

import org.bukkit.*;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ScoreDataManager {
    private final SRExpansion plugin;
    private File dataFile;
    private YamlConfiguration dataConfig;

    // 权重
    public double weightBlockBreak, weightBlockPlace, weightMobKill, weightPlayerKill;
    public double weightDamage, weightTrade, weightEnchant, weightJump, weightMove, weightCraft, weightPlayTick;
    public double weightFishing, weightRaidWin;
    public double advancementScore;

    // 特殊方块和生物额外分
    private final Map<Material, Double> specificBlockScores = new HashMap<>();
    private final Map<EntityType, Double> specificMobScores = new HashMap<>();

    // 死亡惩罚
    private double deathPenaltyPercent, deathPenaltyMax;

    // PVP冷却
    private int pvpCooldownSeconds;
    private final Map<UUID, Map<UUID, Long>> lastKillTime = new ConcurrentHashMap<>();

    // 进度（成就）
    private Map<String, Double> advancementPointsMap = new HashMap<>(); // 存储每个进度ID的分值
    private double fallbackAdvancementScore; // 默认分值，当进度未单独配置时使用

    // 分段惩罚规则
    private final Map<String, List<DiscountTier>> discountRules = new HashMap<>();
    private static final Set<String> STAT_KEYS = Set.of(
            "block_break", "block_place", "craft_item", "mob_kill", "player_kill",
            "damage_dealt", "trade", "enchant", "jump", "move_cm", "play_tick",
            "fishing", "raid_win"
    );

    // 移动统计集合
    private static final Set<Statistic> MOVE_STATS = Set.of(
            Statistic.WALK_ONE_CM, Statistic.WALK_ON_WATER_ONE_CM, Statistic.WALK_UNDER_WATER_ONE_CM,
            Statistic.SWIM_ONE_CM, Statistic.FLY_ONE_CM, Statistic.SPRINT_ONE_CM, Statistic.CROUCH_ONE_CM,
            Statistic.FALL_ONE_CM, Statistic.CLIMB_ONE_CM, Statistic.HORSE_ONE_CM, Statistic.BOAT_ONE_CM,
            Statistic.PIG_ONE_CM, Statistic.STRIDER_ONE_CM, Statistic.MINECART_ONE_CM, Statistic.AVIATE_ONE_CM,
            // 1.21.6 / 1.21.11 新增的骑乘移动统计
            Statistic.HAPPY_GHAST_ONE_CM, Statistic.NAUTILUS_ONE_CM
    );

    private final Map<UUID, PlayerData> playerDataCache = new ConcurrentHashMap<>();
    private List<Map.Entry<String, Double>> cachedLeaderboard = new ArrayList<>();
    private long lastLeaderboardUpdate = 0;
    private static final long LEADERBOARD_CACHE_MS = 30_000L;

    // 脏数据批量落盘：避免每次统计事件都整文件写盘（data.yml 可能上 MB）
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean savePending = new AtomicBoolean(false);
    private static final long AUTOSAVE_INTERVAL_TICKS = 20L * 60L; // 1 分钟

    // 反刷矿：记录"刚刚被玩家自己放置、即将被破坏"的方块类型，供统计事件消费
    private final Map<UUID, PendingArtificial> pendingArtificialBreaks = new ConcurrentHashMap<>();
    private static final long ARTIFICIAL_MARK_TTL_MS = 3_000L;

    /** 一次待消费的"人工放置方块被破坏"标记；存活 3 秒，避免误伤后面的正常挖掘。 */
    private static final class PendingArtificial {
        final Material material;
        final long expiresAt;
        PendingArtificial(Material material, long expiresAt) {
            this.material = material;
            this.expiresAt = expiresAt;
        }
    }

    // 内部类
    public static class DiscountTier {
        public double limit, multiplier;
        public DiscountTier(double limit, double multiplier) {
            this.limit = limit;
            this.multiplier = multiplier;
        }
    }

    public static class PlayerStatsSnapshot {
        public long blockBreak, blockPlace, craftItem, mobKills, playerKills;
        public long damageDealt, trades, enchants, jumps, totalMove, playTicks;
        public long deaths, fishingCaught, raidWins;
        public Map<Material, Long> specificBlockCounts = new HashMap<>();
        public Map<EntityType, Long> specificMobCounts = new HashMap<>();
        public Map<Material, Long> artificialBlockBreaks = new HashMap<>();
        public Set<String> advancements = new HashSet<>();

        // 用于折扣计算的原始总分（数量×权重+额外分）
        public double rawTotalBlockBreakScore, rawTotalMobKillScore;
        // 折扣后分值
        public double discountedBlockBreak, discountedBlockPlace, discountedCraft, discountedMobKill, discountedPlayerKill;
        public double discountedDamage, discountedTrade, discountedEnchant, discountedJump, discountedMove, discountedPlayTick;
        public double discountedFishing, discountedRaidWin, discountedAdvancements;

        public PlayerStatsSnapshot(
                long blockBreak, long blockPlace, long craftItem, long mobKills, long playerKills,
                long damageDealt, long trades, long enchants, long jumps, long totalMove, long playTicks,
                Map<Material, Long> specificBlockCounts, Map<EntityType, Long> specificMobCounts,
                long deaths, long fishingCaught, long raidWins, Map<Material, Long> artificialBlockBreaks,
                Set<String> advancements,
                double rawTotalBlockBreak, double rawTotalMobKill,
                double db, double dp, double dc, double dmk, double dpk, double dd, double dt, double de, double dj,
                double dmv, double dpt, double df, double drw, double dav
        ) {
            this.blockBreak = blockBreak; this.blockPlace = blockPlace; this.craftItem = craftItem;
            this.mobKills = mobKills; this.playerKills = playerKills; this.damageDealt = damageDealt;
            this.trades = trades; this.enchants = enchants; this.jumps = jumps;
            this.totalMove = totalMove; this.playTicks = playTicks;
            this.specificBlockCounts = new HashMap<>(specificBlockCounts);
            this.specificMobCounts = new HashMap<>(specificMobCounts);
            this.deaths = deaths; this.fishingCaught = fishingCaught; this.raidWins = raidWins;
            this.artificialBlockBreaks = new HashMap<>(artificialBlockBreaks);
            this.advancements = new HashSet<>(advancements);
            this.rawTotalBlockBreakScore = rawTotalBlockBreak;
            this.rawTotalMobKillScore = rawTotalMobKill;
            this.discountedBlockBreak = db; this.discountedBlockPlace = dp; this.discountedCraft = dc;
            this.discountedMobKill = dmk; this.discountedPlayerKill = dpk; this.discountedDamage = dd;
            this.discountedTrade = dt; this.discountedEnchant = de; this.discountedJump = dj;
            this.discountedMove = dmv; this.discountedPlayTick = dpt; this.discountedFishing = df;
            this.discountedRaidWin = drw; this.discountedAdvancements = dav;
        }
    }

    /** 一条管理员赠与记录（/srank give），持久化在 data.yml 的 give_records 下。 */
    public static class GiveRecord {
        public int id;
        public double amount;
        public String reason;
        public String operator;
        public long timestamp;

        public GiveRecord(int id, double amount, String reason, String operator, long timestamp) {
            this.id = id;
            this.amount = amount;
            this.reason = reason == null ? "管理员操作" : reason;
            this.operator = operator == null ? "CONSOLE" : operator;
            this.timestamp = timestamp;
        }
    }

    public static class PlayerData {
        private double score;
        private final PlayerStatsSnapshot stats;
        private final Set<Double> triggeredScores;
        private double totalPenalty;
        private final List<GiveRecord> giveRecords;
        private int nextGiveId;
        /** 当前段位注册名（ranks.yml 里的键），null 表示还没初始化。 */
        private String rankId;
        /** 是否开启自动升级。 */
        private boolean autoRankup = true;

        public PlayerData(double score, PlayerStatsSnapshot stats, Set<Double> triggeredScores, double totalPenalty) {
            this(score, stats, triggeredScores, totalPenalty, new ArrayList<>(), 1);
        }

        public PlayerData(double score, PlayerStatsSnapshot stats, Set<Double> triggeredScores, double totalPenalty,
                          List<GiveRecord> giveRecords, int nextGiveId) {
            this.score = score;
            this.stats = stats;
            this.triggeredScores = new HashSet<>(triggeredScores);
            this.totalPenalty = totalPenalty;
            this.giveRecords = new ArrayList<>(giveRecords);
            this.nextGiveId = Math.max(1, nextGiveId);
        }

        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        public PlayerStatsSnapshot getStats() { return stats; }
        public Set<Double> getTriggeredScores() { return triggeredScores; }
        public double getTotalPenalty() { return totalPenalty; }
        public void setTotalPenalty(double totalPenalty) { this.totalPenalty = totalPenalty; }
        public List<GiveRecord> getGiveRecords() { return giveRecords; }
        public int getNextGiveId() { return nextGiveId; }
        public void setNextGiveId(int nextGiveId) { this.nextGiveId = Math.max(1, nextGiveId); }
        public double getGiveTotal() {
            double total = 0;
            for (GiveRecord record : giveRecords) total += record.amount;
            return total;
        }
        public String getRankId() { return rankId; }
        public void setRankId(String rankId) { this.rankId = rankId; }
        public boolean isAutoRankup() { return autoRankup; }
        public void setAutoRankup(boolean autoRankup) { this.autoRankup = autoRankup; }
    }

    // 构造函数与初始化
    public ScoreDataManager(SRExpansion plugin) {
        this.plugin = plugin;
        initDataFile();
        startPeriodicSync();
    }

    private void initDataFile() {
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void startPeriodicSync() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) syncMoveAndPlayTime(player);
            }
        }.runTaskTimer(plugin, 20 * 60 * 5, 20 * 60 * 5);

        // 定时把脏数据刷到磁盘：统计事件里只打脏标记，不再每个事件都写整个 data.yml
        new BukkitRunnable() {
            @Override
            public void run() {
                flushDirty();
            }
        }.runTaskTimer(plugin, AUTOSAVE_INTERVAL_TICKS, AUTOSAVE_INTERVAL_TICKS);
    }

    private void syncMoveAndPlayTime(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        long currentPlayTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long currentTotalMove = 0;
        for (Statistic stat : MOVE_STATS) currentTotalMove += player.getStatistic(stat);
        PlayerStatsSnapshot snapshot = data.getStats();
        long playTickDiff = currentPlayTicks - snapshot.playTicks;
        long moveDiff = currentTotalMove - snapshot.totalMove;
        double added = 0;
        if (playTickDiff > 0) {
            snapshot.playTicks = currentPlayTicks;
            double rawNew = currentPlayTicks * weightPlayTick;
            double rawOld = (currentPlayTicks - playTickDiff) * weightPlayTick;
            double newDisc = getDiscountedScore("play_tick", rawNew);
            double oldDisc = getDiscountedScore("play_tick", rawOld);
            added += newDisc - oldDisc;
            snapshot.discountedPlayTick = newDisc;
        }
        if (moveDiff > 0) {
            snapshot.totalMove = currentTotalMove;
            double rawNew = currentTotalMove * weightMove;
            double rawOld = (currentTotalMove - moveDiff) * weightMove;
            double newDisc = getDiscountedScore("move_cm", rawNew);
            double oldDisc = getDiscountedScore("move_cm", rawOld);
            added += newDisc - oldDisc;
            snapshot.discountedMove = newDisc;
        }
        if (added != 0) {
            data.setScore(data.getScore() + added);
            markDirty(uuid);
        }
    }

    // 配置加载
    public void loadConfig() {
        reloadMainConfig();          // 主 config.yml
        loadBaseConfig();            // base.yml
        loadBlocksConfig();          // blocks.yml
        loadMobsConfig();            // mobs.yml
        loadAdvancementsConfig();    // advancements.yml
    }

    /** 取配置文件：不存在就释放，已存在则补全新增键。 */
    private File ensureConfig(String fileName) {
        return ConfigFiles.ensure(plugin, fileName);
    }

    private void mergeMissingDefaults(String fileName, File file) {
        ConfigFiles.mergeMissing(plugin, fileName, file);
    }

    private void loadBaseConfig() {
        YamlConfiguration base = YamlConfiguration.loadConfiguration(ensureConfig("base.yml"));

        weightBlockBreak = base.getDouble("weights.block_break", 0.01);
        weightBlockPlace = base.getDouble("weights.block_place", 0.02);
        weightMobKill = base.getDouble("weights.mob_kill", 0.05);
        weightPlayerKill = base.getDouble("weights.player_kill", 1.0);
        weightDamage = base.getDouble("weights.damage_dealt", 0.01);
        weightTrade = base.getDouble("weights.trade", 0.1);
        weightEnchant = base.getDouble("weights.enchant", 0.5);
        weightJump = base.getDouble("weights.jump", 0.01);
        weightMove = base.getDouble("weights.move_cm", 0.00003);
        weightCraft = base.getDouble("weights.craft_item", 0.02);
        weightPlayTick = base.getDouble("weights.play_tick", 1.0 / 12000.0);
        weightFishing = base.getDouble("weights.fishing", 0.2);
        weightRaidWin = base.getDouble("weights.raid_win", 5);
    }

    private void loadBlocksConfig() {
        YamlConfiguration blocks = YamlConfiguration.loadConfiguration(ensureConfig("blocks.yml"));

        specificBlockScores.clear();
        ConfigurationSection section = blocks.getConfigurationSection("specific_block_scores");
        if (section == null) {
            plugin.getLogger().warning("blocks.yml 缺少 specific_block_scores 段，特殊方块额外分全部按 0 处理");
            return;
        }

        // 按后缀生效的两组分值：log 覆盖原木/菌柄（含 1.21.4 的苍白橡木），wood 覆盖 6 面树皮方块
        double logScore = section.getDouble("log", 0.01);
        double woodScore = section.getDouble("wood", logScore);
        for (Material mat : Material.values()) {
            if (!mat.isBlock()) continue;
            String name = mat.name();
            if (name.endsWith("_LOG") || name.endsWith("_STEM")) {
                specificBlockScores.put(mat, logScore);
            } else if (name.endsWith("_WOOD") || name.endsWith("_HYPHAE")) {
                specificBlockScores.put(mat, woodScore);
            }
        }

        // 逐个材料的分值（写在 yml 里的键名 = 材料名小写，可以带命名空间）
        // 显式配置优先于上面的 log/wood 分组，写成 0 就是明确不给额外分
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("log") || key.equalsIgnoreCase("wood")) continue;
            Material material = Material.matchMaterial(key.toUpperCase(Locale.ROOT));
            if (material == null || !material.isBlock()) {
                plugin.getLogger().warning("blocks.yml 中无效的方块名: " + key);
                continue;
            }
            double value = section.getDouble(key);
            specificBlockScores.put(material, value);
            // 矿石自动兼容深板岩变体（diamond_ore -> deepslate_diamond_ore）
            String name = material.name();
            if (name.endsWith("_ORE") && !name.startsWith("DEEPSLATE_")) {
                Material deepslate = Material.matchMaterial("DEEPSLATE_" + name);
                if (deepslate != null) specificBlockScores.put(deepslate, value);
            }
        }
    }

    private void loadMobsConfig() {
        YamlConfiguration mobs = YamlConfiguration.loadConfiguration(ensureConfig("mobs.yml"));

        specificMobScores.clear();
        ConfigurationSection section = mobs.getConfigurationSection("specific_mob_scores");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            EntityType type;
            try {
                type = EntityType.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无效的生物类型: " + key);
                continue;
            }
            if (!type.isAlive()) {
                plugin.getLogger().warning("该实体类型不是生物，已忽略: " + key);
                continue;
            }
            specificMobScores.put(type, section.getDouble(key));
        }
    }

    private void loadAdvancementsConfig() {
        YamlConfiguration adv = YamlConfiguration.loadConfiguration(ensureConfig("advancements.yml"));
        // 获取默认分值和每个进度的单独分值对应关系
        fallbackAdvancementScore = adv.getDouble("advancement_score_default", 1.0);
        advancementPointsMap.clear();
        ConfigurationSection pointsSection = adv.getConfigurationSection("advancement_points");
        if (pointsSection == null) {
            plugin.getLogger().warning("advancements.yml 中没有配置任何进度分值！将使用默认分值: " + fallbackAdvancementScore);
            return;
        }
        Map<String, Object> points = pointsSection.getValues(false);
        if (points.isEmpty()) {
            plugin.getLogger().warning("advancements.yml 中没有配置任何进度分值！将使用默认分值: " + fallbackAdvancementScore);
        }
        for (Map.Entry<String, Object> entry : points.entrySet()) {
            String key = entry.getKey();
            double pointValue;
            if (entry.getValue() instanceof Number) {
                pointValue = ((Number) entry.getValue()).doubleValue();
            } else if (entry.getValue() instanceof String) {
                try {
                    pointValue = Double.parseDouble((String) entry.getValue());
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("advancements.yml 中 " + key + " 的分值配置无效，使用默认分值");
                    pointValue = fallbackAdvancementScore;
                }
            } else {
                plugin.getLogger().warning("advancements.yml 中 " + key + " 的分值类型无效，使用默认分值");
                pointValue = fallbackAdvancementScore;
            }
            // 如果 ID 不带命名空间，自动补全 minecraft:
            String advancementId = key;
            if (!advancementId.contains(":")) {
                advancementId = "minecraft:" + advancementId;
            }
            advancementPointsMap.put(advancementId, pointValue);
        }
        plugin.getLogger().info("已加载 " + advancementPointsMap.size() + " 个进度的自定义分值，默认分值为 " + fallbackAdvancementScore);
    }

    private void reloadMainConfig() {
        mergeMissingDefaults("config.yml", new File(plugin.getDataFolder(), "config.yml"));
        plugin.reloadConfig();
        deathPenaltyPercent = plugin.getConfig().getDouble("death_penalty.percent", 0.05);
        deathPenaltyMax = plugin.getConfig().getDouble("death_penalty.max", 2.0);
        pvpCooldownSeconds = plugin.getConfig().getInt("pvp.kill_cooldown_seconds", 300);

        // 加载分段惩罚规则
        discountRules.clear();
        if (plugin.getConfig().isConfigurationSection("diminishing_returns")) {
            for (String key : STAT_KEYS) {
                List<Map<?, ?>> tierList = plugin.getConfig().getMapList("diminishing_returns." + key);
                List<DiscountTier> tiers = new ArrayList<>();
                for (Map<?, ?> tier : tierList) {
                    double limit = ((Number) tier.get("limit")).doubleValue();
                    double multiplier = ((Number) tier.get("multiplier")).doubleValue();
                    tiers.add(new DiscountTier(limit, multiplier));
                }
                tiers.sort(Comparator.comparingDouble(t -> t.limit));
                discountRules.put(key, tiers);
            }
        }

        // 加载阈值触发命令
        thresholds.clear();
        List<Map<?, ?>> thresholdList = plugin.getConfig().getMapList("thresholds");
        for (Map<?, ?> map : thresholdList) {
            double score = ((Number) map.get("score")).doubleValue();
            String command = (String) map.get("command");
            boolean console = false;
            Object consoleObj = map.get("console");
            if (consoleObj instanceof Boolean) console = (Boolean) consoleObj;
            thresholds.add(new Threshold(score, command, console));
        }
    }

    private double getDiscountedScore(String statKey, double rawScore) {
        List<DiscountTier> tiers = discountRules.get(statKey);
        if (tiers == null || tiers.isEmpty()) return rawScore;
        double remaining = rawScore;
        double discounted = 0;
        double lastLimit = 0;
        for (DiscountTier tier : tiers) {
            double tierAmount = Math.min(remaining, tier.limit - lastLimit);
            if (tierAmount > 0) {
                discounted += tierAmount * tier.multiplier;
                remaining -= tierAmount;
                lastLimit = tier.limit;
            }
            if (remaining <= 1e-9) break;
        }
        if (remaining > 1e-9) {
            double lastMultiplier = tiers.get(tiers.size() - 1).multiplier;
            discounted += remaining * lastMultiplier;
        }
        return discounted;
    }

    public double getAdvancementPoint(String advancementId) {
        return advancementPointsMap.getOrDefault(advancementId, fallbackAdvancementScore);
    }

    public boolean isAdvancementCompleted(UUID uuid, String advancementId) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return false;
        return data.getStats().advancements.contains(advancementId);
    }

    /**
     * 把 PlaceholderAPI 里的标识符还原成进度 ID。
     * PAPI 标识符里带 / 和 : 很容易被解析器截断，所以约定：第一个下划线是分类分隔符，例如
     * <pre>
     *   story_mine_stone                       -> minecraft:story/mine_stone
     *   husbandry_place_dried_ghast_in_water   -> minecraft:husbandry/place_dried_ghast_in_water
     * </pre>
     * 已经带命名空间的（含 ':'）原样返回。
     */
    public String resolveAdvancementId(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.contains(":")) return raw;
        String id = raw;
        if (!id.contains("/")) {
            int underscore = id.indexOf('_');
            if (underscore > 0) id = id.substring(0, underscore) + "/" + id.substring(underscore + 1);
        }
        return "minecraft:" + id;
    }

    // 公共访问
    public double getExtraBlockScore(Material mat) { return specificBlockScores.getOrDefault(mat, 0.0); }
    public double getExtraMobScore(EntityType type) { return specificMobScores.getOrDefault(type, 0.0); }

    public void initPlayer(Player player, boolean force) {
        UUID uuid = player.getUniqueId();
        if (!force && playerDataCache.containsKey(uuid)) return;

        // 触发器与赠与记录：即使 force=true 也要保留，否则 recalc 会重复触发阈值命令、并抹掉管理员给的分
        Set<Double> triggered;
        List<GiveRecord> giveRecords;
        int nextGiveId;
        double giveTotal = 0;
        boolean isNewOrForce;
        synchronized (dataConfig) {
            String base = "players." + uuid;
            triggered = new HashSet<>(dataConfig.getDoubleList(base + ".triggered"));
            giveRecords = readGiveRecords(uuid);
            nextGiveId = dataConfig.getInt(base + ".give_next_id", 1);
            isNewOrForce = force || !dataConfig.contains(base + ".score");
        }
        for (GiveRecord record : giveRecords) {
            giveTotal += record.amount;
            nextGiveId = Math.max(nextGiveId, record.id + 1);
        }
        double totalPenalty = 0;

        // 1. 读取原始统计
        // getStatistic(stat, material) 对材料类型有严格要求：MINE_BLOCK 只接受方块，USE_ITEM / CRAFT_ITEM 只接受物品。
        // 1.21 里存在“是方块但没有物品形态”的材料（例如 ACACIA_WALL_HANGING_SIGN），乱传会抛
        // IllegalArgumentException，导致整个初始化失败、玩家永远没有数据。
        long totalBreak = 0, totalPlace = 0, totalCraft = 0;
        for (Material mat : Material.values()) {
            try {
                if (mat.isBlock()) {
                    totalBreak += player.getStatistic(Statistic.MINE_BLOCK, mat);
                    if (mat.isItem()) totalPlace += player.getStatistic(Statistic.USE_ITEM, mat);
                }
                if (mat.isItem()) totalCraft += player.getStatistic(Statistic.CRAFT_ITEM, mat);
            } catch (IllegalArgumentException ignored) {
                // 个别材料仍不被统计系统支持，跳过即可
            }
        }
        long mobKills = player.getStatistic(Statistic.MOB_KILLS);
        // 玩家击杀用插件自己维护的计数：原版 PLAYER_KILLS 不受 PVP 冷却限制，
        // 用它参与重算会把冷却期内的击杀补发成分，等于让 /srank recalc 绕过冷却。
        long playerKills;
        synchronized (dataConfig) {
            String killsPath = "players." + uuid + ".stats.player_kills";
            playerKills = dataConfig.contains(killsPath)
                    ? dataConfig.getLong(killsPath)
                    : player.getStatistic(Statistic.PLAYER_KILLS);
        }
        long damageDealt = player.getStatistic(Statistic.DAMAGE_DEALT);
        long trades = player.getStatistic(Statistic.TRADED_WITH_VILLAGER);
        long enchants = player.getStatistic(Statistic.ITEM_ENCHANTED);
        long jumps = player.getStatistic(Statistic.JUMP);
        long playTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalMove = 0;
        for (Statistic stat : MOVE_STATS) totalMove += player.getStatistic(stat);
        long deaths = player.getStatistic(Statistic.DEATHS);
        long fishingCaught = player.getStatistic(Statistic.FISH_CAUGHT);
        long raidWins = player.getStatistic(Statistic.RAID_WIN);

        // 2. 人工破坏记录（重算时也要读，否则“自己放的矿”会被当成自然生成再给一次额外分）
        Map<Material, Long> artificialBreaks = new HashMap<>();
        if (dataConfig.contains("players." + uuid + ".stats.artificial_block_breaks")) {
            var sec = dataConfig.getConfigurationSection("players." + uuid + ".stats.artificial_block_breaks");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        artificialBreaks.put(Material.valueOf(key), sec.getLong(key));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }

        // 3. 特殊方块额外分（自然生成 = 总挖取 - 人工破坏）
        Map<Material, Long> specificBlockCounts = new HashMap<>();
        double extraBlockScore = 0;
        for (Material mat : specificBlockScores.keySet()) {
            long totalMined;
            try {
                totalMined = player.getStatistic(Statistic.MINE_BLOCK, mat);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            long artificial = artificialBreaks.getOrDefault(mat, 0L);
            long natural = totalMined - artificial;
            if (natural > 0) {
                specificBlockCounts.put(mat, natural);
                extraBlockScore += natural * specificBlockScores.get(mat);
            }
        }

        // 4. 特殊生物额外分
        Map<EntityType, Long> specificMobCounts = new HashMap<>();
        double extraMobScore = 0;
        for (EntityType type : specificMobScores.keySet()) {
            long count;
            try {
                count = player.getStatistic(Statistic.KILL_ENTITY, type);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (count > 0) {
                specificMobCounts.put(type, count);
                extraMobScore += count * specificMobScores.get(type);
            }
        }

        // 5. 进度完成（读取历史成就）
        Set<String> advancements = new HashSet<>();
        if (!force && dataConfig.contains("players." + uuid + ".stats.advancements")) {
            advancements = new HashSet<>(dataConfig.getStringList("players." + uuid + ".stats.advancements"));
        } else {
            // 首次加载：遍历所有进度并检查是否完成
            Iterator<Advancement> it = Bukkit.advancementIterator();
            while (it.hasNext()) {
                Advancement adv = it.next();
                if (player.getAdvancementProgress(adv).isDone()) {
                    advancements.add(adv.getKey().toString());
                }
            }
        }
        // 根据配置计算进度总分
        double rawAdvancementScore = 0;
        for (String advId : advancements) {
            double point = advancementPointsMap.getOrDefault(advId, fallbackAdvancementScore);
            rawAdvancementScore += point;
        }
        double discAdvancementScore = rawAdvancementScore; // 不折扣

        // 6. 基础原始分（数量×权重）
        double rawBlockBreak = totalBreak * weightBlockBreak;
        double rawBlockPlace = totalPlace * weightBlockPlace;
        double rawCraft = totalCraft * weightCraft;
        double rawMobKill = mobKills * weightMobKill;
        double rawPlayerKill = playerKills * weightPlayerKill;
        double rawDamage = damageDealt * weightDamage;
        double rawTrade = trades * weightTrade;
        double rawEnchant = enchants * weightEnchant;
        double rawJump = jumps * weightJump;
        double rawMove = totalMove * weightMove;
        double rawPlayTick = playTicks * weightPlayTick;
        double rawFishing = fishingCaught * weightFishing;
        double rawRaidWin = raidWins * weightRaidWin;

        // 7. 合并额外分后的原始总分（用于折扣）
        double totalRawBlockBreak = rawBlockBreak + extraBlockScore;
        double totalRawMobKill = rawMobKill + extraMobScore;

        // 8. 折扣后分值
        double discBlockBreak = getDiscountedScore("block_break", totalRawBlockBreak);
        double discBlockPlace = getDiscountedScore("block_place", rawBlockPlace);
        double discCraft = getDiscountedScore("craft_item", rawCraft);
        double discMobKill = getDiscountedScore("mob_kill", totalRawMobKill);
        double discPlayerKill = getDiscountedScore("player_kill", rawPlayerKill);
        double discDamage = getDiscountedScore("damage_dealt", rawDamage);
        double discTrade = getDiscountedScore("trade", rawTrade);
        double discEnchant = getDiscountedScore("enchant", rawEnchant);
        double discJump = getDiscountedScore("jump", rawJump);
        double discMove = getDiscountedScore("move_cm", rawMove);
        double discPlayTick = getDiscountedScore("play_tick", rawPlayTick);
        double discFishing = getDiscountedScore("fishing", rawFishing);
        double discRaidWin = getDiscountedScore("raid_win", rawRaidWin);

        double discountedBase = discBlockBreak + discBlockPlace + discCraft + discMobKill + discPlayerKill
                + discDamage + discTrade + discEnchant + discJump + discMove + discPlayTick + discFishing + discRaidWin;
        double rawScore = discountedBase + discAdvancementScore;

        // 9. 死亡惩罚
        // 不再按“历史死亡次数”回溯扣分（老玩家第一次接入会被一次性扣到很低，而且每次 recalc 结果还不一样）。
        // 改为只认持久化的累计扣除额：分数 = 统计重算值 + 赠与分 − 累计扣除。
        // 新玩家 total_penalty = 0，因此接入插件前的死亡不会被惩罚；每次真实死亡由 applyDeathPenalty 累加。
        double finalScore;
        double accumulatedPenalty;
        if (isNewOrForce) {
            synchronized (dataConfig) {
                accumulatedPenalty = dataConfig.getDouble("players." + uuid + ".total_penalty", 0.0);
            }
            finalScore = rawScore + giveTotal - accumulatedPenalty;
        } else {
            synchronized (dataConfig) {
                finalScore = dataConfig.getDouble("players." + uuid + ".score");
                accumulatedPenalty = dataConfig.getDouble("players." + uuid + ".total_penalty");
            }
        }

        // 10. 构建快照和玩家数据对象
        PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(
                totalBreak, totalPlace, totalCraft, mobKills, playerKills,
                damageDealt, trades, enchants, jumps, totalMove, playTicks,
                specificBlockCounts, specificMobCounts, deaths, fishingCaught, raidWins, artificialBreaks,
                advancements,
                totalRawBlockBreak, totalRawMobKill,
                discBlockBreak, discBlockPlace, discCraft, discMobKill, discPlayerKill,
                discDamage, discTrade, discEnchant, discJump, discMove, discPlayTick,
                discFishing, discRaidWin, discAdvancementScore
        );
        PlayerData data = new PlayerData(finalScore, snapshot, triggered, accumulatedPenalty, giveRecords, nextGiveId);
        // 段位：优先读存档；没有就按当前分数推导，老玩家迁移时不会一次性刷出上百条升级公告
        String savedRank;
        boolean autoRankup;
        synchronized (dataConfig) {
            String base = "players." + uuid;
            savedRank = dataConfig.getString(base + ".rank");
            autoRankup = dataConfig.getBoolean(base + ".auto_rankup",
                    plugin.getRankManager() != null && plugin.getRankManager().isAutoDefault());
        }
        RankManager rankManager = plugin.getRankManager();
        if (rankManager != null) {
            Rank rank = savedRank == null ? null : rankManager.getById(savedRank);
            if (rank == null) rank = rankManager.getByScore(data.getScore());
            data.setRankId(rank == null ? null : rank.getId());
        } else {
            data.setRankId(savedRank);
        }
        data.setAutoRankup(autoRankup);
        playerDataCache.put(uuid, data);
        savePlayerData(uuid, data);
    }

    public void updateStatistic(UUID uuid, Statistic stat, int increment, Material material, EntityType entityType) {
        if (increment <= 0) return;
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        double added = 0;
        PlayerStatsSnapshot snap = data.getStats();

        // 辅助函数：更新折扣
        java.util.function.BiFunction<String, Double, Double> updateDiscount = (key, rawDelta) -> {
            double rawOld = getRawScoreForStat(snap, key);
            double rawNew = rawOld + rawDelta;
            double discOld = getDiscountedScore(key, rawOld);
            double discNew = getDiscountedScore(key, rawNew);
            setDiscountedScore(snap, key, discNew);
            return discNew - discOld;
        };

        if (stat == Statistic.MINE_BLOCK) {
            double rawDeltaBase = increment * weightBlockBreak;
            double extraDelta = 0;
            if (material != null && specificBlockScores.containsKey(material)) {
                if (consumeArtificialBreak(uuid, material)) {
                    // 挖的是玩家自己刚放的方块：只给基础分，不给特殊方块额外分（与全量重算口径一致）
                    snap.artificialBlockBreaks.put(material,
                            snap.artificialBlockBreaks.getOrDefault(material, 0L) + increment);
                } else {
                    extraDelta = specificBlockScores.get(material) * increment;
                    snap.specificBlockCounts.put(material, snap.specificBlockCounts.getOrDefault(material, 0L) + increment);
                }
            }
            snap.blockBreak += increment;
            double rawOld = snap.rawTotalBlockBreakScore;
            double rawNew = rawOld + rawDeltaBase + extraDelta;
            snap.rawTotalBlockBreakScore = rawNew;
            double discOld = snap.discountedBlockBreak;
            double discNew = getDiscountedScore("block_break", rawNew);
            snap.discountedBlockBreak = discNew;
            added = discNew - discOld;
        } else if (stat == Statistic.USE_ITEM) {
            // USE_ITEM 也会被锄头、骨粉、水桶等使用动作触发；只把“放置方块”计入 block_place，
            // 否则会和全量重算（只统计方块材料）口径不一致。
            if (material == null || !material.isBlock()) return;
            double rawDelta = increment * weightBlockPlace;
            snap.blockPlace += increment;
            added = updateDiscount.apply("block_place", rawDelta);
        } else if (stat == Statistic.CRAFT_ITEM) {
            if (material == null || !material.isItem()) return;
            double rawDelta = increment * weightCraft;
            snap.craftItem += increment;
            added = updateDiscount.apply("craft_item", rawDelta);
        } else if (stat == Statistic.MOB_KILLS) {
            // 不要在这里处理，因为使用 KILL_ENTITY 代替
            return;
        } else if (stat == Statistic.DAMAGE_DEALT) {
            double rawDelta = increment * weightDamage;
            snap.damageDealt += increment;
            added = updateDiscount.apply("damage_dealt", rawDelta);
        } else if (stat == Statistic.TRADED_WITH_VILLAGER) {
            double rawDelta = increment * weightTrade;
            snap.trades += increment;
            added = updateDiscount.apply("trade", rawDelta);
        } else if (stat == Statistic.ITEM_ENCHANTED) {
            double rawDelta = increment * weightEnchant;
            snap.enchants += increment;
            added = updateDiscount.apply("enchant", rawDelta);
        } else if (stat == Statistic.JUMP) {
            double rawDelta = increment * weightJump;
            snap.jumps += increment;
            added = updateDiscount.apply("jump", rawDelta);
        } else if (stat == Statistic.PLAY_ONE_MINUTE) {
            double rawDelta = increment * weightPlayTick;
            snap.playTicks += increment;
            added = updateDiscount.apply("play_tick", rawDelta);
        } else if (MOVE_STATS.contains(stat)) {
            double rawDelta = increment * weightMove;
            snap.totalMove += increment;
            added = updateDiscount.apply("move_cm", rawDelta);
        } else if (stat == Statistic.KILL_ENTITY && entityType != null) {
            double rawDeltaBase = increment * weightMobKill;
            double extraDelta = 0;
            if (specificMobScores.containsKey(entityType)) {
                extraDelta = specificMobScores.get(entityType) * increment;
                snap.specificMobCounts.put(entityType, snap.specificMobCounts.getOrDefault(entityType, 0L) + increment);
            }
            snap.mobKills += increment;
            double rawOld = snap.rawTotalMobKillScore;
            double rawNew = rawOld + rawDeltaBase + extraDelta;
            snap.rawTotalMobKillScore = rawNew;
            double discOld = snap.discountedMobKill;
            double discNew = getDiscountedScore("mob_kill", rawNew);
            snap.discountedMobKill = discNew;
            added = discNew - discOld;
        } else if (stat == Statistic.FISH_CAUGHT) {
            double rawDelta = increment * weightFishing;
            snap.fishingCaught += increment;
            added = updateDiscount.apply("fishing", rawDelta);
        } else if (stat == Statistic.RAID_WIN) {
            double rawDelta = increment * weightRaidWin;
            snap.raidWins += increment;
            added = updateDiscount.apply("raid_win", rawDelta);
        } else if (stat == Statistic.DEATHS) {
            snap.deaths += increment;
            markDirty(uuid);
            return;
        } else if (stat == Statistic.PLAYER_KILLS) {
            return; // 由死亡事件处理冷却
        } else {
            return;
        }

        if (added != 0) {
            data.setScore(data.getScore() + added);
        }
        markDirty(uuid);
    }

    private double getRawScoreForStat(PlayerStatsSnapshot snap, String key) {
        switch (key) {
            case "block_break": return snap.rawTotalBlockBreakScore;
            case "block_place": return snap.blockPlace * weightBlockPlace;
            case "craft_item": return snap.craftItem * weightCraft;
            case "mob_kill": return snap.rawTotalMobKillScore;
            case "player_kill": return snap.playerKills * weightPlayerKill;
            case "damage_dealt": return snap.damageDealt * weightDamage;
            case "trade": return snap.trades * weightTrade;
            case "enchant": return snap.enchants * weightEnchant;
            case "jump": return snap.jumps * weightJump;
            case "move_cm": return snap.totalMove * weightMove;
            case "play_tick": return snap.playTicks * weightPlayTick;
            case "fishing": return snap.fishingCaught * weightFishing;
            case "raid_win": return snap.raidWins * weightRaidWin;
            default: return 0;
        }
    }

    private void setDiscountedScore(PlayerStatsSnapshot snap, String key, double value) {
        switch (key) {
            case "block_break": snap.discountedBlockBreak = value; break;
            case "block_place": snap.discountedBlockPlace = value; break;
            case "craft_item": snap.discountedCraft = value; break;
            case "mob_kill": snap.discountedMobKill = value; break;
            case "player_kill": snap.discountedPlayerKill = value; break;
            case "damage_dealt": snap.discountedDamage = value; break;
            case "trade": snap.discountedTrade = value; break;
            case "enchant": snap.discountedEnchant = value; break;
            case "jump": snap.discountedJump = value; break;
            case "move_cm": snap.discountedMove = value; break;
            case "play_tick": snap.discountedPlayTick = value; break;
            case "fishing": snap.discountedFishing = value; break;
            case "raid_win": snap.discountedRaidWin = value; break;
        }
    }

    public void addAdvancementScore(Player player, String advancementKey) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) {
            initPlayer(player, false);
            data = playerDataCache.get(uuid);
            if (data == null) return;
        }
        Set<String> advancements = data.getStats().advancements;
        if (advancements.contains(advancementKey)) return;
        advancements.add(advancementKey);
        // 获取新完成的进度所对应的分值
        double pointValue = advancementPointsMap.getOrDefault(advancementKey, fallbackAdvancementScore);
        double added = pointValue;
        double oldScore = data.getStats().discountedAdvancements;
        data.getStats().discountedAdvancements = oldScore + added;
        data.setScore(data.getScore() + added);
        markDirty(uuid);
        checkThresholds(player);
    }

    public void addPlayerKill(Player killer, Player victim) {
        UUID killerId = killer.getUniqueId();
        UUID victimId = victim.getUniqueId();
        long now = System.currentTimeMillis();
        Map<UUID, Long> victimMap = lastKillTime.computeIfAbsent(killerId, k -> new ConcurrentHashMap<>());
        Long lastTime = victimMap.get(victimId);
        if (lastTime != null && (now - lastTime) < pvpCooldownSeconds * 1000L) {
            killer.sendMessage(ChatColor.RED + "你击杀了 " + victim.getName() + "，但冷却中，不获得生存段位分。");
            return;
        }
        victimMap.put(victimId, now);
        PlayerData data = playerDataCache.get(killerId);
        if (data == null) {
            initPlayer(killer, false);
            data = playerDataCache.get(killerId);
            if (data == null) return;
        }
        double rawDelta = weightPlayerKill;
        double rawOld = data.getStats().playerKills * weightPlayerKill;
        double rawNew = rawOld + rawDelta;
        double discOld = data.getStats().discountedPlayerKill;
        double discNew = getDiscountedScore("player_kill", rawNew);
        double added = discNew - discOld;
        data.getStats().playerKills++;
        data.getStats().discountedPlayerKill = discNew;
        data.setScore(data.getScore() + added);
        markDirty(killerId);
        checkThresholds(killer);
        killer.sendMessage(ChatColor.GREEN + "你击杀了 " + victim.getName() + "，获得 " + String.format("%.2f", added) + " 生存段位分！");
    }

    /** 记录一次「玩家自己放置的方块被破坏」，由 MINE_BLOCK 统计事件消费，避免刷矿得分。 */
    public void addArtificialBlockBreak(Player player, Material material) {
        if (player == null || material == null) return;
        pendingArtificialBreaks.put(player.getUniqueId(),
                new PendingArtificial(material, System.currentTimeMillis() + ARTIFICIAL_MARK_TTL_MS));
    }

    public void applyDeathPenalty(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) {
            initPlayer(player, false);
            data = playerDataCache.get(uuid);
            if (data == null) return;
        }
        long currentDeaths = player.getStatistic(Statistic.DEATHS);
        data.getStats().deaths = currentDeaths;
        double currentScore = data.getScore();
        double penalty = currentScore * deathPenaltyPercent;
        if (penalty > deathPenaltyMax) penalty = deathPenaltyMax;
        if (penalty <= 0) return;
        double newScore = currentScore - penalty;
        data.setScore(newScore);
        data.setTotalPenalty(data.getTotalPenalty() + penalty);
        markDirty(uuid);
        player.sendMessage(ChatColor.RED + "你因死亡被扣除 " + String.format("%.2f", penalty) +
                " 生存段位分！当前分数: " + String.format("%.2f", newScore));
    }

    /** 立即把某玩家数据写进内存并排队落盘（首次加载、退服时用）。 */
    public void savePlayerData(UUID uuid, PlayerData data) {
        writePlayerData(uuid, data);
        persistAsync();
    }

    /** 把一名玩家的完整数据写进内存中的 dataConfig（不落盘）。 */
    private void writePlayerData(UUID uuid, PlayerData data) {
        synchronized (dataConfig) {
            String path = "players." + uuid;
            dataConfig.set(path + ".score", data.getScore());
            dataConfig.set(path + ".total_penalty", data.getTotalPenalty());
            dataConfig.set(path + ".triggered", new ArrayList<>(data.getTriggeredScores()));
            dataConfig.set(path + ".give_next_id", data.getNextGiveId());
            dataConfig.set(path + ".rank", data.getRankId());
            dataConfig.set(path + ".auto_rankup", data.isAutoRankup());

            List<Map<String, Object>> records = new ArrayList<>();
            for (GiveRecord record : data.getGiveRecords()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", record.id);
                map.put("amount", record.amount);
                map.put("reason", record.reason);
                map.put("operator", record.operator);
                map.put("timestamp", record.timestamp);
                records.add(map);
            }
            dataConfig.set(path + ".give_records", records);

            PlayerStatsSnapshot s = data.getStats();
            dataConfig.set(path + ".stats.block_break", s.blockBreak);
            dataConfig.set(path + ".stats.block_place", s.blockPlace);
            dataConfig.set(path + ".stats.craft_item", s.craftItem);
            dataConfig.set(path + ".stats.mob_kills", s.mobKills);
            dataConfig.set(path + ".stats.player_kills", s.playerKills);
            dataConfig.set(path + ".stats.damage_dealt", s.damageDealt);
            dataConfig.set(path + ".stats.trades", s.trades);
            dataConfig.set(path + ".stats.enchants", s.enchants);
            dataConfig.set(path + ".stats.jumps", s.jumps);
            dataConfig.set(path + ".stats.total_move", s.totalMove);
            dataConfig.set(path + ".stats.play_ticks", s.playTicks);
            dataConfig.set(path + ".stats.deaths", s.deaths);
            dataConfig.set(path + ".stats.fishing_caught", s.fishingCaught);
            dataConfig.set(path + ".stats.raid_wins", s.raidWins);
            dataConfig.set(path + ".stats.raw_total_block_break", s.rawTotalBlockBreakScore);
            dataConfig.set(path + ".stats.raw_total_mob_kill", s.rawTotalMobKillScore);

            dataConfig.set(path + ".stats.discounted_block_break", s.discountedBlockBreak);
            dataConfig.set(path + ".stats.discounted_block_place", s.discountedBlockPlace);
            dataConfig.set(path + ".stats.discounted_craft", s.discountedCraft);
            dataConfig.set(path + ".stats.discounted_mob_kill", s.discountedMobKill);
            dataConfig.set(path + ".stats.discounted_player_kill", s.discountedPlayerKill);
            dataConfig.set(path + ".stats.discounted_damage", s.discountedDamage);
            dataConfig.set(path + ".stats.discounted_trade", s.discountedTrade);
            dataConfig.set(path + ".stats.discounted_enchant", s.discountedEnchant);
            dataConfig.set(path + ".stats.discounted_jump", s.discountedJump);
            dataConfig.set(path + ".stats.discounted_move", s.discountedMove);
            dataConfig.set(path + ".stats.discounted_play_tick", s.discountedPlayTick);
            dataConfig.set(path + ".stats.discounted_fishing", s.discountedFishing);
            dataConfig.set(path + ".stats.discounted_raid_win", s.discountedRaidWin);
            dataConfig.set(path + ".stats.discounted_advancements", s.discountedAdvancements);

            Map<String, Long> saveBlock = new HashMap<>();
            s.specificBlockCounts.forEach((m, c) -> saveBlock.put(m.name(), c));
            dataConfig.set(path + ".stats.specific_block_counts", saveBlock);
            Map<String, Long> saveMob = new HashMap<>();
            s.specificMobCounts.forEach((e, c) -> saveMob.put(e.name(), c));
            dataConfig.set(path + ".stats.specific_mob_counts", saveMob);
            Map<String, Long> saveArtificial = new HashMap<>();
            s.artificialBlockBreaks.forEach((m, c) -> saveArtificial.put(m.name(), c));
            dataConfig.set(path + ".stats.artificial_block_breaks", saveArtificial);
            dataConfig.set(path + ".stats.advancements", new ArrayList<>(s.advancements));

            String name = Bukkit.getOfflinePlayer(uuid).getName();
            if (name != null) dataConfig.set(path + ".name", name);
        }
    }

    /** 标记该玩家的数据需要落盘（事件线程只做这一步，真正的写文件由定时任务/退服时完成）。 */
    private void markDirty(UUID uuid) {
        dirtyPlayers.add(uuid);
    }

    /** 只在该玩家确实有改动时才写内存 + 排队落盘。 */
    public void savePlayerDataIfDirty(UUID uuid) {
        if (!dirtyPlayers.remove(uuid)) return;
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        writePlayerData(uuid, data);
        persistAsync();
    }

    /** 把所有脏数据刷入内存并排队落盘（定时任务调用）。 */
    public void flushDirty() {
        if (dirtyPlayers.isEmpty()) return;
        List<UUID> pending = new ArrayList<>(dirtyPlayers);
        dirtyPlayers.removeAll(pending);
        for (UUID uuid : pending) {
            PlayerData data = playerDataCache.get(uuid);
            if (data != null) writePlayerData(uuid, data);
        }
        persistAsync();
    }

    /** 序列化整个 data.yml 并异步写盘；同一时间只排队一个写任务，避免堆积。 */
    private void persistAsync() {
        if (!savePending.compareAndSet(false, true)) return;
        if (!plugin.isEnabled()) {
            savePending.set(false);
            saveNow();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            savePending.set(false);
            synchronized (dataConfig) {
                try {
                    dataConfig.save(dataFile);
                } catch (IOException e) {
                    plugin.getLogger().severe("保存 data.yml 失败: " + e.getMessage());
                }
            }
        });
    }

    /** 同步写盘（关服时使用，此时调度器已经不能调度异步任务）。 */
    private void saveNow() {
        synchronized (dataConfig) {
            try {
                dataConfig.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().severe("保存 data.yml 失败: " + e.getMessage());
            }
        }
    }

    public PlayerData getPlayerData(UUID uuid) { return playerDataCache.get(uuid); }

    /** 按名字查玩家（在线玩家的数据同样已经写进 data.yml，所以统一走离线读取）。 */
    public PlayerData getPlayerDataOffline(String playerName) {
        if (playerName == null) return null;
        synchronized (dataConfig) {
            ConfigurationSection players = dataConfig.getConfigurationSection("players");
            if (players == null) return null;
            for (String uuidStr : players.getKeys(false)) {
                String name = dataConfig.getString("players." + uuidStr + ".name");
                if (playerName.equalsIgnoreCase(name)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        return loadPlayerDataFromConfig(uuid);
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return null;
    }

    /** 从 data.yml 反序列化一名玩家（离线查询用）。 */
    private PlayerData loadPlayerDataFromConfig(UUID uuid) {
        String path = "players." + uuid;
        synchronized (dataConfig) {
            if (!dataConfig.contains(path)) return null;
            String s = path + ".stats.";
            PlayerStatsSnapshot snapshot = new PlayerStatsSnapshot(
                    dataConfig.getLong(s + "block_break"),
                    dataConfig.getLong(s + "block_place"),
                    dataConfig.getLong(s + "craft_item"),
                    dataConfig.getLong(s + "mob_kills"),
                    dataConfig.getLong(s + "player_kills"),
                    dataConfig.getLong(s + "damage_dealt"),
                    dataConfig.getLong(s + "trades"),
                    dataConfig.getLong(s + "enchants"),
                    dataConfig.getLong(s + "jumps"),
                    dataConfig.getLong(s + "total_move"),
                    dataConfig.getLong(s + "play_ticks"),
                    readMaterialLongMap(s + "specific_block_counts"),
                    readEntityLongMap(s + "specific_mob_counts"),
                    dataConfig.getLong(s + "deaths"),
                    dataConfig.getLong(s + "fishing_caught"),
                    dataConfig.getLong(s + "raid_wins"),
                    readMaterialLongMap(s + "artificial_block_breaks"),
                    new HashSet<>(dataConfig.getStringList(s + "advancements")),
                    dataConfig.getDouble(s + "raw_total_block_break"),
                    dataConfig.getDouble(s + "raw_total_mob_kill"),
                    dataConfig.getDouble(s + "discounted_block_break"),
                    dataConfig.getDouble(s + "discounted_block_place"),
                    dataConfig.getDouble(s + "discounted_craft"),
                    dataConfig.getDouble(s + "discounted_mob_kill"),
                    dataConfig.getDouble(s + "discounted_player_kill"),
                    dataConfig.getDouble(s + "discounted_damage"),
                    dataConfig.getDouble(s + "discounted_trade"),
                    dataConfig.getDouble(s + "discounted_enchant"),
                    dataConfig.getDouble(s + "discounted_jump"),
                    dataConfig.getDouble(s + "discounted_move"),
                    dataConfig.getDouble(s + "discounted_play_tick"),
                    dataConfig.getDouble(s + "discounted_fishing"),
                    dataConfig.getDouble(s + "discounted_raid_win"),
                    dataConfig.getDouble(s + "discounted_advancements")
            );
            List<GiveRecord> records = readGiveRecords(uuid);
            int nextGiveId = dataConfig.getInt(path + ".give_next_id", 1);
            for (GiveRecord record : records) nextGiveId = Math.max(nextGiveId, record.id + 1);
            PlayerData data = new PlayerData(
                    dataConfig.getDouble(path + ".score"),
                    snapshot,
                    new HashSet<>(dataConfig.getDoubleList(path + ".triggered")),
                    dataConfig.getDouble(path + ".total_penalty"),
                    records,
                    nextGiveId);
            String rankId = dataConfig.getString(path + ".rank");
            if (rankId == null && plugin.getRankManager() != null) {
                Rank derived = plugin.getRankManager().getByScore(data.getScore());
                rankId = derived == null ? null : derived.getId();
            }
            data.setRankId(rankId);
            data.setAutoRankup(dataConfig.getBoolean(path + ".auto_rankup",
                    plugin.getRankManager() != null && plugin.getRankManager().isAutoDefault()));
            return data;
        }
    }

    private Map<Material, Long> readMaterialLongMap(String path) {
        Map<Material, Long> map = new HashMap<>();
        ConfigurationSection section = dataConfig.getConfigurationSection(path);
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material != null) map.put(material, section.getLong(key));
        }
        return map;
    }

    private Map<EntityType, Long> readEntityLongMap(String path) {
        Map<EntityType, Long> map = new HashMap<>();
        ConfigurationSection section = dataConfig.getConfigurationSection(path);
        if (section == null) return map;
        for (String key : section.getKeys(false)) {
            try {
                map.put(EntityType.valueOf(key), section.getLong(key));
            } catch (IllegalArgumentException ignored) {}
        }
        return map;
    }

    private List<GiveRecord> readGiveRecords(UUID uuid) {
        List<GiveRecord> records = new ArrayList<>();
        for (Map<?, ?> raw : dataConfig.getMapList("players." + uuid + ".give_records")) {
            Object idValue = raw.get("id");
            if (!(idValue instanceof Number)) continue;
            Object amountValue = raw.get("amount");
            Object timeValue = raw.get("timestamp");
            records.add(new GiveRecord(
                    ((Number) idValue).intValue(),
                    amountValue instanceof Number ? ((Number) amountValue).doubleValue() : 0.0,
                    raw.get("reason") == null ? "管理员操作" : String.valueOf(raw.get("reason")),
                    raw.get("operator") == null ? "CONSOLE" : String.valueOf(raw.get("operator")),
                    timeValue instanceof Number ? ((Number) timeValue).longValue() : 0L));
        }
        records.sort(Comparator.comparingInt(record -> record.id));
        return records;
    }

    // ==================== 赠与记录 ====================

    /** 追加一条赠与记录并把分数加上去，返回记录 id；玩家没有数据时返回 -1。 */
    public int addGiveRecord(UUID uuid, double amount, String reason, String operator) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return -1;
        int id = data.getNextGiveId();
        data.setNextGiveId(id + 1);
        data.getGiveRecords().add(new GiveRecord(id, amount, reason, operator, System.currentTimeMillis()));
        data.setScore(data.getScore() + amount);
        markDirty(uuid);
        checkThresholds(Bukkit.getPlayer(uuid));
        return id;
    }

    public boolean undoGiveRecord(UUID uuid, int id) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return false;
        GiveRecord target = findGiveRecord(data, id);
        if (target == null) return false;
        data.getGiveRecords().remove(target);
        data.setScore(data.getScore() - target.amount);
        markDirty(uuid);
        return true;
    }

    public boolean editGiveRecord(UUID uuid, int id, double newAmount, String newReason) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return false;
        GiveRecord target = findGiveRecord(data, id);
        if (target == null) return false;
        data.setScore(data.getScore() - target.amount + newAmount);
        target.amount = newAmount;
        if (newReason != null && !newReason.isEmpty()) target.reason = newReason;
        markDirty(uuid);
        checkThresholds(Bukkit.getPlayer(uuid));
        return true;
    }

    private GiveRecord findGiveRecord(PlayerData data, int id) {
        for (GiveRecord record : data.getGiveRecords()) {
            if (record.id == id) return record;
        }
        return null;
    }

    public List<GiveRecord> getGiveRecords(UUID uuid) {
        PlayerData data = resolve(uuid);
        return data == null ? new ArrayList<>() : new ArrayList<>(data.getGiveRecords());
    }

    public GiveRecord getGiveRecord(UUID uuid, int id) {
        PlayerData data = resolve(uuid);
        return data == null ? null : findGiveRecord(data, id);
    }

    public GiveRecord getLastGiveRecord(UUID uuid) {
        PlayerData data = resolve(uuid);
        if (data == null || data.getGiveRecords().isEmpty()) return null;
        List<GiveRecord> records = data.getGiveRecords();
        return records.get(records.size() - 1);
    }

    public double getGiveTotal(UUID uuid) {
        PlayerData data = resolve(uuid);
        return data == null ? 0 : data.getGiveTotal();
    }

    public int getGiveCount(UUID uuid) {
        PlayerData data = resolve(uuid);
        return data == null ? 0 : data.getGiveRecords().size();
    }

    /** 优先用内存里的实时数据，没有就退回 data.yml（离线玩家）。 */
    private PlayerData resolve(UUID uuid) {
        PlayerData data = playerDataCache.get(uuid);
        return data != null ? data : loadPlayerDataFromConfig(uuid);
    }

    // ==================== 排名 / 阈值进度 / 特殊方块与生物 ====================

    /** 按段位分排名，1 为第一名；该玩家没有任何数据时返回 -1。 */
    public int getPlayerRank(UUID uuid) {
        Map<UUID, Double> scores = new HashMap<>();
        synchronized (dataConfig) {
            ConfigurationSection section = dataConfig.getConfigurationSection("players");
            if (section == null) return -1;
            for (String key : section.getKeys(false)) {
                try {
                    scores.put(UUID.fromString(key), dataConfig.getDouble("players." + key + ".score"));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        Double own = scores.get(uuid);
        if (own == null) return -1;
        int rank = 1;
        for (double other : scores.values()) {
            if (other > own) rank++;
        }
        return rank;
    }

    /** 已达成过的最高阈值；一个都没有返回 -1。 */
    public double getCurrentThresholdScore(UUID uuid) {
        PlayerData data = resolve(uuid);
        if (data == null) return -1;
        double best = -1;
        for (Threshold threshold : thresholds) {
            if (threshold.getScore() <= data.getScore() && threshold.getScore() > best) best = threshold.getScore();
        }
        return best;
    }

    /** 下一个还没达到的阈值；已经全部达成返回 -1。 */
    public double getNextThresholdScore(UUID uuid) {
        PlayerData data = resolve(uuid);
        if (data == null) return -1;
        double best = -1;
        for (Threshold threshold : thresholds) {
            if (threshold.getScore() > data.getScore() && (best < 0 || threshold.getScore() < best)) best = threshold.getScore();
        }
        return best;
    }

    /** 当前档位到下一档位的进度百分比（0-100），已满级返回 100。 */
    public double getThresholdProgress(UUID uuid) {
        PlayerData data = resolve(uuid);
        if (data == null) return 0;
        double next = getNextThresholdScore(uuid);
        double current = getCurrentThresholdScore(uuid);
        if (next < 0) return 100;
        double from = Math.max(current, 0);
        double span = next - from;
        if (span <= 1e-9) return 100;
        double progress = (data.getScore() - from) / span * 100.0;
        return Math.max(0, Math.min(100, progress));
    }

    public long getSpecialBlockValue(UUID uuid, Material material) {
        PlayerData data = resolve(uuid);
        if (data == null || material == null) return 0;
        return data.getStats().specificBlockCounts.getOrDefault(material, 0L);
    }

    public double getSpecialBlockScore(UUID uuid, Material material) {
        return getSpecialBlockValue(uuid, material) * getExtraBlockScore(material);
    }

    public long getSpecialMobValue(UUID uuid, EntityType type) {
        PlayerData data = resolve(uuid);
        if (data == null || type == null) return 0;
        return data.getStats().specificMobCounts.getOrDefault(type, 0L);
    }

    public double getSpecialMobScore(UUID uuid, EntityType type) {
        return getSpecialMobValue(uuid, type) * getExtraMobScore(type);
    }

    /** 消费一条“刚被玩家自己放置的方块被破坏”的标记，返回本次破坏是否属于人工放置。 */
    private boolean consumeArtificialBreak(UUID uuid, Material material) {
        PendingArtificial pending = pendingArtificialBreaks.get(uuid);
        if (pending == null) return false;
        if (pending.expiresAt < System.currentTimeMillis()) {
            pendingArtificialBreaks.remove(uuid);
            return false;
        }
        if (pending.material != material) return false;
        pendingArtificialBreaks.remove(uuid);
        return true;
    }

    public boolean hasData(UUID uuid) { return dataConfig.contains("players." + uuid); }

    public List<Map.Entry<String, Double>> getTopPlayers() {
        long now = System.currentTimeMillis();
        if (now - lastLeaderboardUpdate < LEADERBOARD_CACHE_MS && !cachedLeaderboard.isEmpty())
            return cachedLeaderboard;
        Map<String, Double> scoreMap = new HashMap<>();
        synchronized (dataConfig) {
            ConfigurationSection playersSection = dataConfig.getConfigurationSection("players");
            if (playersSection != null) {
                for (String uuidStr : playersSection.getKeys(false)) {
                    String path = "players." + uuidStr;
                    double s = dataConfig.getDouble(path + ".score");
                    String name = dataConfig.getString(path + ".name", uuidStr.substring(0, Math.min(8, uuidStr.length())));
                    scoreMap.put(name, s);
                }
            }
        }
        cachedLeaderboard = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toList());
        lastLeaderboardUpdate = now;
        return cachedLeaderboard;
    }

    public String getTopPlayerName(int rank) {
        List<Map.Entry<String, Double>> top = getTopPlayers();
        if (rank < 1 || rank > top.size()) return "";
        return top.get(rank - 1).getKey();
    }
    public double getTopPlayerScore(int rank) {
        List<Map.Entry<String, Double>> top = getTopPlayers();
        if (rank < 1 || rank > top.size()) return 0;
        return top.get(rank - 1).getValue();
    }
    public long getStatValue(UUID uuid, String statKey) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return 0;
        switch (statKey) {
            case "block_break": return data.getStats().blockBreak;
            case "block_place": return data.getStats().blockPlace;
            case "craft_item": return data.getStats().craftItem;
            case "mob_kill": return data.getStats().mobKills;
            case "player_kill": return data.getStats().playerKills;
            case "damage_dealt": return data.getStats().damageDealt;
            case "trade": return data.getStats().trades;
            case "enchant": return data.getStats().enchants;
            case "jump": return data.getStats().jumps;
            case "move_cm": return data.getStats().totalMove;
            case "play_tick": return data.getStats().playTicks;
            case "fishing": return data.getStats().fishingCaught;
            case "raid_win": return data.getStats().raidWins;
            default: return 0;
        }
    }
    public double getStatScore(UUID uuid, String statKey) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return 0;
        switch (statKey) {
            case "block_break": return data.getStats().discountedBlockBreak;
            case "block_place": return data.getStats().discountedBlockPlace;
            case "craft_item": return data.getStats().discountedCraft;
            case "mob_kill": return data.getStats().discountedMobKill;
            case "player_kill": return data.getStats().discountedPlayerKill;
            case "damage_dealt": return data.getStats().discountedDamage;
            case "trade": return data.getStats().discountedTrade;
            case "enchant": return data.getStats().discountedEnchant;
            case "jump": return data.getStats().discountedJump;
            case "move_cm": return data.getStats().discountedMove;
            case "play_tick": return data.getStats().discountedPlayTick;
            case "fishing": return data.getStats().discountedFishing;
            case "raid_win": return data.getStats().discountedRaidWin;
            default: return 0;
        }
    }
    public int getAdvancementCount(UUID uuid) {
        PlayerData data = playerDataCache.get(uuid);
        return data == null ? 0 : data.getStats().advancements.size();
    }
    public double getAdvancementScore(UUID uuid) {
        PlayerData data = playerDataCache.get(uuid);
        return data == null ? 0 : data.getStats().discountedAdvancements;
    }

    /** 关服/重载时把所有缓存写回并同步落盘。 */
    public void saveAllData() {
        for (Map.Entry<UUID, PlayerData> entry : playerDataCache.entrySet()) {
            writePlayerData(entry.getKey(), entry.getValue());
        }
        dirtyPlayers.clear();
        saveNow();
    }

    /** 玩家退服：有改动才落盘，然后从缓存里移除（避免缓存无限增长）。 */
    public void unloadPlayer(UUID uuid) {
        savePlayerDataIfDirty(uuid);
        pendingArtificialBreaks.remove(uuid);
        playerDataCache.remove(uuid);
    }

    /** 设置玩家段位（段位系统调用）。 */
    public void setRank(UUID uuid, String rankId) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        data.setRankId(rankId);
        markDirty(uuid);
        savePlayerDataIfDirty(uuid);
    }

    /** 开关自动升级。 */
    public void setAutoRankup(UUID uuid, boolean value) {
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        data.setAutoRankup(value);
        markDirty(uuid);
        savePlayerDataIfDirty(uuid);
    }

    /** 重载配置并重算所有在线玩家（触发记录与赠与分会保留）。 */
    public void reload() {
        mergeMissingDefaults("config.yml", new File(plugin.getDataFolder(), "config.yml"));
        plugin.reloadConfig();
        loadConfig();
        for (Player player : Bukkit.getOnlinePlayers()) initPlayer(player, true);
    }

    /** 重算所有在线玩家（保留触发记录与赠与分）。 */
    public void recalcAllPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) initPlayer(player, true);
    }

    /**
     * 检查并执行阈值命令。所有会加分的地方都应该调用它（统计事件、进度完成、PVP 击杀、管理员 give），
     * 否则玩家可能“分数到了但段位没发”。
     */
    public void checkThresholds(Player player) {
        if (player == null || thresholds.isEmpty()) return;
        UUID uuid = player.getUniqueId();
        PlayerData data = playerDataCache.get(uuid);
        if (data == null) return;
        double score = data.getScore();
        for (Threshold threshold : thresholds) {
            if (score < threshold.getScore()) continue;
            if (!data.getTriggeredScores().add(threshold.getScore())) continue;
            // 立刻落盘，避免崩服后重复执行同一条阈值命令
            markDirty(uuid);
            savePlayerDataIfDirty(uuid);
            String command = threshold.getCommand() == null ? "" : threshold.getCommand().replace("%player%", player.getName());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (threshold.isConsole()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                else player.performCommand(command);
            });
        }
        // 分数变化后顺便检查段位
        if (plugin.getRankManager() != null) plugin.getRankManager().tryAutoRankup(player);
    }

    private List<Threshold> thresholds = new ArrayList<>();
    public List<Threshold> getThresholds() { return thresholds; }
}