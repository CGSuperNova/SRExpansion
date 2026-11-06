package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 段位系统：读取 ranks.yml，负责晋升判定、奖励发放、提示。
 * 玩家的当前段位与自动升级开关存放在 data.yml（见 {@link ScoreDataManager.PlayerData}）。
 */
public class RankManager {

    private final SRExpansion plugin;
    private final List<Rank> ranks = new ArrayList<>();
    private final Map<String, Rank> byId = new LinkedHashMap<>();
    /** 所有段位里出现过的属性 id，用于把没加成的属性也同步成 0。 */
    private final java.util.Set<String> attributeIds = new java.util.LinkedHashSet<>();

    private boolean autoDefault = true;
    private int checkInterval = 60;
    private String moneyCommand = "";
    private String broadcastTemplate = "";
    private String titleTemplate = "";
    private String subtitleTemplate = "";
    private List<String> chatTemplate = new ArrayList<>();
    private String soundName = "";
    private float volume = 1.0f;
    private float pitch = 1.0f;

    // 权限授予（其他插件用 rank.<id>.achieved 判断玩家是否达到某段位）
    private boolean permissionEnabled = true;
    private List<String> permissionNodes = new ArrayList<>(List.of("rank.%rank%.achieved"));
    private String permissionCommand = "";

    // 升级悬浮字
    private boolean displayEnabled = true;
    private double displayDistance = 3.0;
    private double displayHeight = 0.4;
    private double displayStartAngle = 90;
    private double displayReadAngle = 30;
    private int displayRotateTicks = 6;
    private int displayHoldTicks = 30;
    private int displayGapTicks = 4;
    private boolean displaySeeThrough = false;
    private int displayMoveIntervalTicks = 1;
    private List<String> displayStage1 = new ArrayList<>();
    private List<String> displayStage2 = new ArrayList<>();
    private List<String> displayStage3 = new ArrayList<>();

    private Object economy;
    private boolean economyChecked;

    public RankManager(SRExpansion plugin) {
        this.plugin = plugin;
    }

    // ==================== 配置 ====================

    public void load() {
        File file = ConfigFiles.ensure(plugin, "ranks.yml", "ranks");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        migrateAttributes(config, file);

        autoDefault = config.getBoolean("settings.auto-rankup-default", true);
        checkInterval = Math.max(5, config.getInt("settings.check-interval-seconds", 60));
        moneyCommand = config.getString("settings.money.command", "");
        broadcastTemplate = config.getString("settings.messages.broadcast", "");
        titleTemplate = config.getString("settings.messages.title", "");
        subtitleTemplate = config.getString("settings.messages.subtitle", "");
        chatTemplate = config.getStringList("settings.messages.chat");
        soundName = config.getString("settings.messages.sound", "");
        volume = (float) config.getDouble("settings.messages.volume", 1.0);
        pitch = (float) config.getDouble("settings.messages.pitch", 1.0);

        permissionEnabled = config.getBoolean("settings.permission.enabled", true);
        List<String> nodes = config.getStringList("settings.permission.nodes");
        permissionNodes = nodes.isEmpty() ? new ArrayList<>(List.of("rank.%rank%.achieved")) : new ArrayList<>(nodes);
        permissionCommand = config.getString("settings.permission.command", "");

        displayEnabled = config.getBoolean("settings.display.enabled", true);
        displayDistance = config.getDouble("settings.display.distance", 3.0);
        displayHeight = config.getDouble("settings.display.height", 0.4);
        displayStartAngle = config.getDouble("settings.display.start-angle", 90);
        displayReadAngle = config.getDouble("settings.display.read-angle", 30);
        displayRotateTicks = Math.max(1, config.getInt("settings.display.rotate-ticks", 6));
        displayHoldTicks = Math.max(1, config.getInt("settings.display.hold-ticks", 30));
        displayGapTicks = Math.max(0, config.getInt("settings.display.gap-ticks", 4));
        displaySeeThrough = config.getBoolean("settings.display.see-through", false);
        displayMoveIntervalTicks = Math.max(1, config.getInt("settings.display.move-interval-ticks", 1));
        displayStage1 = config.getStringList("settings.display.stages.stage1");
        displayStage2 = config.getStringList("settings.display.stages.stage2");
        displayStage3 = config.getStringList("settings.display.stages.stage3");
        if (displayStage1.isEmpty()) displayStage1 = List.of("&a&l生存段位提升！", "&f你升级到了 %rank%");
        if (displayStage2.isEmpty()) displayStage2 = List.of("&e&l获得奖励", "&6+%money% 金币");
        if (displayStage3.isEmpty()) displayStage3 = List.of("&b&l下一段位", "&f%next_rank% &7需要 %next_score% 分");

        List<Rank> loaded = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("ranks");
        if (section == null) {
            plugin.getLogger().severe("ranks.yml 里没有 ranks 段，段位系统不可用！");
        } else {
            for (String id : section.getKeys(false)) {
                ConfigurationSection rank = section.getConfigurationSection(id);
                if (rank == null) continue;
                String display = rank.getString("display", id);
                double score = rank.getDouble("score", 0);
                double money = rank.getDouble("money", 0);
                boolean auto = rank.getBoolean("auto", true);
                Map<String, Double> attributes = new LinkedHashMap<>();
                ConfigurationSection attributeSection = rank.getConfigurationSection("attributes");
                if (attributeSection != null) {
                    for (String key : attributeSection.getKeys(false)) {
                        double value = attributeSection.getDouble(key);
                        if (value != 0) attributes.put(key.toLowerCase(Locale.ROOT), value);
                    }
                }
                loaded.add(new Rank(0, id, display, score, money, auto, attributes,
                        rank.getStringList("extra")));
            }
        }
        loaded.sort(Comparator.comparingDouble(Rank::getScore));

        ranks.clear();
        byId.clear();
        attributeIds.clear();
        int index = 0;
        for (Rank rank : loaded) {
            index++;
            Rank numbered = new Rank(index, rank.getId(), rank.getDisplay(), rank.getScore(), rank.getMoney(),
                    rank.isAuto(), rank.getAttributes(), rank.getExtraDisplay());
            ranks.add(numbered);
            byId.put(numbered.getId().toLowerCase(Locale.ROOT), numbered);
            attributeIds.addAll(numbered.getAttributes().keySet());
        }
        plugin.getLogger().info("已加载 " + ranks.size() + " 个段位");
    }

    /**
     * 一次性迁移：把 jar 内置的 attributes 补进服务器上已存在的段位条目。
     *
     * <p>配置合并对 {@code ranks} 是「只补整条新增、不改已有条目」，所以老服务器的
     * ranks.yml 不会自动获得 v1.10.0 新增的 attributes。这里用一个开关做一次性迁移：
     * 只给「还没有 attributes 字段」的段位补上默认值，之后管理员删改都不会被还原。</p>
     */
    private void migrateAttributes(YamlConfiguration config, File file) {
        if (config.getBoolean("settings.attributes-migrated", false)) return;
        java.io.InputStream in = plugin.getResource("ranks.yml");
        if (in == null) return;
        YamlConfiguration defaults;
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException e) {
            return;
        }

        ConfigurationSection ranks = config.getConfigurationSection("ranks");
        ConfigurationSection defaultRanks = defaults.getConfigurationSection("ranks");
        int migrated = 0;
        if (ranks != null && defaultRanks != null) {
            for (String id : ranks.getKeys(false)) {
                ConfigurationSection entry = ranks.getConfigurationSection(id);
                ConfigurationSection defaultEntry = defaultRanks.getConfigurationSection(id);
                if (entry == null || defaultEntry == null) continue;
                if (entry.contains("attributes")) continue;
                ConfigurationSection defaultAttributes = defaultEntry.getConfigurationSection("attributes");
                if (defaultAttributes == null) continue;
                ConfigurationSection created = entry.createSection("attributes");
                for (String key : defaultAttributes.getKeys(false)) {
                    created.set(key, defaultAttributes.get(key));
                }
                migrated++;
            }
        }
        config.set("settings.attributes-migrated", true);
        try {
            config.save(file);
            if (migrated > 0) {
                plugin.getLogger().info("已为 " + migrated + " 个段位补上默认的属性奖励（attributes）");
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("写回 ranks.yml 失败: " + e.getMessage());
        }
    }

    /**
     * 把「当前段位之前所有段位的属性加成之和」同步给 AdvancedPlayerAttribute。
     * 是幂等的：重复调用不会叠加，只会把该来源的加成设为最新值。
     */
    public void syncAttributes(Player player) {
        if (player == null) return;
        AttributeBridge bridge = plugin.getAttributeBridge();
        if (bridge == null || !bridge.isAvailable() || attributeIds.isEmpty()) return;
        ScoreDataManager dataManager = plugin.getDataManager();
        if (dataManager == null) return;
        ScoreDataManager.PlayerData data = dataManager.getPlayerData(player.getUniqueId());
        if (data == null) return;

        Rank current = data.getRankId() == null ? null : getById(data.getRankId());
        if (current == null) current = getByScore(data.getScore());
        if (current == null) return;

        Map<String, Double> totals = new LinkedHashMap<>();
        for (Rank rank : ranks) {
            if (rank.getIndex() > current.getIndex()) break;
            rank.getAttributes().forEach((key, value) -> totals.merge(key, value, Double::sum));
        }
        for (String attributeId : attributeIds) {
            bridge.set(player, attributeId, totals.getOrDefault(attributeId, 0.0));
        }
    }

    public int getCheckInterval() { return checkInterval; }    public boolean isAutoDefault() { return autoDefault; }
    public List<Rank> getRanks() { return ranks; }
    public int size() { return ranks.size(); }

    public Rank getFirst() { return ranks.isEmpty() ? null : ranks.get(0); }
    public Rank getLast() { return ranks.isEmpty() ? null : ranks.get(ranks.size() - 1); }

    /** 按注册名查段位（忽略大小写）。 */
    public Rank getById(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** 从玩家输入解析段位：注册名、显示名（去色）或序号都可以。 */
    public Rank resolve(String input) {
        if (input == null || input.isBlank()) return null;
        Rank byName = getById(input.trim());
        if (byName != null) return byName;
        String plain = input.trim();
        for (Rank rank : ranks) {
            if (rank.getDisplayPlain().equalsIgnoreCase(plain) || rank.getId().equalsIgnoreCase(plain)) return rank;
        }
        try {
            int index = Integer.parseInt(plain);
            if (index >= 1 && index <= ranks.size()) return ranks.get(index - 1);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    /** 按分数推出应有的段位（不超过当前分数的最高段位）。 */
    public Rank getByScore(double score) {
        Rank result = getFirst();
        for (Rank rank : ranks) {
            if (rank.getScore() <= score) result = rank;
            else break;
        }
        return result;
    }

    /** 下一段位；已是最高段位返回 null。 */
    public Rank next(Rank rank) {
        if (rank == null) return getFirst();
        int index = rank.getIndex();
        if (index >= ranks.size()) return null;
        return ranks.get(index);
    }

    // ==================== 晋升 ====================

    /**
     * 尝试晋升，可能一次连升多级。
     *
     * @param manual true 表示玩家手动执行 /srank rankup，可以越过 auto: false 的限制
     * @return 实际提升的段位数，0 表示没有可升级的段位
     */
    public int rankup(Player player, boolean manual) {
        if (player == null || ranks.isEmpty()) return 0;
        ScoreDataManager dataManager = plugin.getDataManager();
        if (dataManager == null) return 0;
        ScoreDataManager.PlayerData data = dataManager.getPlayerData(player.getUniqueId());
        if (data == null) return 0;

        double score = data.getScore();
        Rank current = data.getRankId() == null ? null : getById(data.getRankId());
        if (current == null) current = getByScore(score);

        Rank target = current;
        double money = 0;
        Map<String, Double> gainedAttributes = new LinkedHashMap<>();
        int steps = 0;
        while (true) {
            Rank next = next(target);
            if (next == null) break;
            if (score < next.getScore()) break;
            if (!manual && !next.isAuto()) break;
            target = next;
            money += next.getMoney();
            next.getAttributes().forEach((key, value) -> gainedAttributes.merge(key, value, Double::sum));
            steps++;
        }
        if (steps == 0) return 0;

        dataManager.setRank(player.getUniqueId(), target.getId());
        grantPermissions(player, target);
        syncAttributes(player);
        announce(player, target, money, steps);
        if (displayEnabled && plugin.getRankupDisplay() != null) {
            plugin.getRankupDisplay().play(player, target, money, gainedAttributes);
        }
        return steps;
    }

    /**
     * 给玩家补上“已达到的所有段位”的权限节点（其他插件用它判断段位）。
     * 已经记录在 data.yml 的节点不会重复执行命令。
     */
    public void grantPermissions(Player player, Rank target) {
        if (!permissionEnabled || target == null) return;
        if (permissionCommand == null || permissionCommand.isBlank()) return;
        ScoreDataManager dataManager = plugin.getDataManager();
        if (dataManager == null) return;
        ScoreDataManager.PlayerData data = dataManager.getPlayerData(player.getUniqueId());
        if (data == null) return;

        java.util.Set<String> granted = data.getGrantedPermissions();
        List<String> toGrant = new ArrayList<>();
        for (Rank rank : ranks) {
            if (rank.getIndex() > target.getIndex()) break;
            if (rank.getScore() <= 0) continue; // 初始段位（Newbie）不授予权限节点
            for (String template : permissionNodes) {
                String node = template.replace("%rank%", rank.getId().toLowerCase(Locale.ROOT));
                if (granted.add(node)) toGrant.add(node);
            }
        }
        if (toGrant.isEmpty()) return;
        dataManager.touch(player.getUniqueId());
        for (String node : toGrant) {
            String command = permissionCommand
                    .replace("%player%", player.getName())
                    .replace("%node%", node);
            Bukkit.getScheduler().runTask(plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        }
    }

    /** 登录/初始化时用：不发命令，直接把“已达到的段位节点”记为已授予，避免老玩家迁移时刷一堆命令。 */
    public void prefillPermissions(ScoreDataManager.PlayerData data, Rank rank) {
        if (!permissionEnabled || data == null || rank == null) return;
        java.util.Set<String> granted = data.getGrantedPermissions();
        for (Rank item : ranks) {
            if (item.getIndex() > rank.getIndex()) break;
            if (item.getScore() <= 0) continue; // 初始段位不授予权限节点
            for (String template : permissionNodes) {
                granted.add(template.replace("%rank%", item.getId().toLowerCase(Locale.ROOT)));
            }
        }
    }

    /** 自动升级检查：只在玩家开启自动升级时生效。 */
    public void tryAutoRankup(Player player) {
        if (player == null) return;
        ScoreDataManager dataManager = plugin.getDataManager();
        if (dataManager == null) return;
        ScoreDataManager.PlayerData data = dataManager.getPlayerData(player.getUniqueId());
        if (data == null || !data.isAutoRankup()) return;
        rankup(player, false);
    }

    /** 管理员强制设置段位（不发奖励、不广播，但会补上对应段位的权限与属性）。 */
    public void setRank(Player player, Rank rank) {
        plugin.getDataManager().setRank(player.getUniqueId(), rank.getId());
        grantPermissions(player, rank);
        syncAttributes(player);
    }

    // ==================== 悬浮字设置（给 RankupDisplay 用） ====================

    public boolean isDisplayEnabled() { return displayEnabled; }
    public double getDisplayDistance() { return displayDistance; }
    public double getDisplayHeight() { return displayHeight; }
    public double getDisplayStartAngle() { return displayStartAngle; }
    public double getDisplayReadAngle() { return displayReadAngle; }
    public int getDisplayRotateTicks() { return displayRotateTicks; }
    public int getDisplayHoldTicks() { return displayHoldTicks; }
    public int getDisplayGapTicks() { return displayGapTicks; }
    public boolean isDisplaySeeThrough() { return displaySeeThrough; }
    /** 悬浮字跟随玩家的位置更新间隔（tick），1 = 每 tick 都跟随。 */
    public int getDisplayMoveIntervalTicks() { return displayMoveIntervalTicks; }

    /**
     * 第 stage 阶段（0 起）要显示的原始文本行。
     * 前三幕固定，第 4 幕是段位自己的 {@code extra} 内容（只有配置了才存在）。
     */
    public List<String> getDisplayStage(Rank rank, int stage) {
        return switch (stage) {
            case 0 -> displayStage1;
            case 1 -> displayStage2;
            case 2 -> displayStage3;
            case 3 -> rank == null ? List.of() : rank.getExtraDisplay();
            default -> List.of();
        };
    }

    /** 某个段位总共要播几幕：基础 3 幕 + 有额外内容时再 +1。 */
    public int getDisplayStageCount(Rank rank) {
        return rank != null && !rank.getExtraDisplay().isEmpty() ? 4 : 3;
    }

    /** 属性 id → 中文名，用于悬浮字展示。 */
    public static String attributeDisplayName(String id) {
        return switch (id == null ? "" : id.toLowerCase(Locale.ROOT)) {
            case "attack" -> "攻击";
            case "armor_pierce" -> "破甲攻击";
            case "absolute" -> "绝对攻击";
            case "defense" -> "防御";
            case "max_health" -> "血量上限";
            case "dodge" -> "闪避率";
            case "shield" -> "护盾";
            case "shield_regen" -> "护盾恢复";
            default -> id;
        };
    }

    /**
     * 把属性加成描述成多行文本（每行一个属性），用于悬浮字的 {@code %attributes%} 占位符。
     * 没有加成时返回空字符串。
     */
    public static String describeAttributes(Map<String, Double> attributes) {
        if (attributes == null || attributes.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Double> entry : attributes.entrySet()) {
            double value = entry.getValue();
            if (value == 0) continue;
            if (builder.length() > 0) builder.append('\n');
            String name = attributeDisplayName(entry.getKey());
            String suffix = "dodge".equalsIgnoreCase(entry.getKey()) ? "%" : "";
            builder.append("&a").append(name).append(" &f+")
                    .append(RankManager.formatMoney(value)).append(suffix);
        }
        return builder.toString();
    }

    public void startTask() {
        long ticks = Math.max(5, checkInterval) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                tryAutoRankup(player);
            }
        }, ticks, ticks);
    }

    // ==================== 提示与奖励 ====================

    private void announce(Player player, Rank rank, double money, int steps) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("%player%", player.getName());
        // 这里用未经解析的原始写法（含 {#RRGGBB>} 与 & 代码），整条消息再统一解析一次
        vars.put("%rank%", rank.getDisplay());
        vars.put("%rank_plain%", rank.getDisplayPlain());
        vars.put("%rank_id%", rank.getId());
        vars.put("%money%", formatMoney(money));
        vars.put("%steps%", String.valueOf(steps));

        if (!broadcastTemplate.isBlank()) {
            Bukkit.broadcast(format(broadcastTemplate, vars));
        }
        if (!titleTemplate.isBlank() || !subtitleTemplate.isBlank()) {
            Component title = format(titleTemplate, vars);
            Component subtitle = format(subtitleTemplate, vars);
            player.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofMillis(500))));
        }
        for (String line : chatTemplate) {
            player.sendMessage(format(line, vars));
        }
        playSound(player);
        if (money > 0) giveMoney(player, money);
    }

    private void playSound(Player player) {
        if (soundName == null || soundName.isBlank()) return;
        String key = normalizeSound(soundName);
        try {
            player.playSound(player.getLocation(), key, volume, pitch);
        } catch (Throwable ignored) {
            // 音效名写错不影响升级
        }
    }

    /** 把 BLOCK_BEACON_POWER_SELECT 这种旧写法转成 block.beacon.power_select。 */
    static String normalizeSound(String name) {
        if (name.contains(":") || name.contains(".")) return name;
        return name.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private void giveMoney(Player player, double amount) {
        if (deposit(player, amount)) return;
        if (moneyCommand == null || moneyCommand.isBlank()) return;
        String command = moneyCommand
                .replace("%player%", player.getName())
                .replace("%amount%", formatMoney(amount));
        // 下一 tick 执行，避免在统计事件里做重活
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    private void ensureEconomy() {
        if (economyChecked) return;
        economyChecked = true;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object registration = Bukkit.getServicesManager().getRegistration((Class) economyClass);
            if (registration != null) {
                Method getProvider = registration.getClass().getMethod("getProvider");
                economy = getProvider.invoke(registration);
            }
        } catch (Throwable ignored) {
            economy = null;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean deposit(Player player, double amount) {
        ensureEconomy();
        if (economy == null) return false;
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Method deposit = economyClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            deposit.invoke(economy, player, amount);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Component format(String template, Map<String, String> vars) {
        String text = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return TextFormatter.parse(text);
    }

    /** 金币显示：整数不带小数，否则保留两位。 */
    public static String formatMoney(double amount) {
        if (Math.abs(amount - Math.rint(amount)) < 1e-6) return String.valueOf((long) Math.rint(amount));
        return String.format("%.2f", amount);
    }
}
