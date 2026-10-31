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

    private Object economy;
    private boolean economyChecked;

    public RankManager(SRExpansion plugin) {
        this.plugin = plugin;
    }

    // ==================== 配置 ====================

    public void load() {
        File file = ConfigFiles.ensure(plugin, "ranks.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

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
                loaded.add(new Rank(0, id, display, score, money, auto));
            }
        }
        loaded.sort(Comparator.comparingDouble(Rank::getScore));

        ranks.clear();
        byId.clear();
        int index = 0;
        for (Rank rank : loaded) {
            index++;
            Rank numbered = new Rank(index, rank.getId(), rank.getDisplay(), rank.getScore(), rank.getMoney(), rank.isAuto());
            ranks.add(numbered);
            byId.put(numbered.getId().toLowerCase(Locale.ROOT), numbered);
        }
        plugin.getLogger().info("已加载 " + ranks.size() + " 个段位");
    }

    public int getCheckInterval() { return checkInterval; }
    public boolean isAutoDefault() { return autoDefault; }
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
        int steps = 0;
        while (true) {
            Rank next = next(target);
            if (next == null) break;
            if (score < next.getScore()) break;
            if (!manual && !next.isAuto()) break;
            target = next;
            money += next.getMoney();
            steps++;
        }
        if (steps == 0) return 0;

        dataManager.setRank(player.getUniqueId(), target.getId());
        announce(player, target, money, steps);
        return steps;
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

    /** 管理员强制设置段位（不发奖励、不广播）。 */
    public void setRank(Player player, Rank rank) {
        plugin.getDataManager().setRank(player.getUniqueId(), rank.getId());
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
