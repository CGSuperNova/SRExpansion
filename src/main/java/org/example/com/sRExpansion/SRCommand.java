package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SRCommand implements CommandExecutor, TabCompleter {

    private static final int LIST_PAGE_SIZE = 10;
    private static final int STATS_PAGE_SIZE = 10;

    private final SRExpansion plugin;

    public SRCommand(SRExpansion plugin) { this.plugin = plugin; }

    // ==================== 入口 ====================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "score" -> handleScore(sender, rest);
            case "list" -> {
                if (!check(sender, "srank.user")) return true;
                showRankList(sender, parseInt(rest.length > 0 ? rest[0] : "1", 1));
            }
            case "info" -> {
                if (!check(sender, "srank.user")) return true;
                handleInfo(sender, rest);
            }
            case "rankup" -> {
                if (!checkPlayer(sender)) return true;
                if (!check(sender, "srank.user")) return true;
                handleRankup((Player) sender);
            }
            case "rankset" -> {
                if (!check(sender, "srank.admin")) return true;
                handleRankSet(sender, rest);
            }
            case "autorankup" -> {
                if (!checkPlayer(sender)) return true;
                if (!check(sender, "srank.user")) return true;
                handleAutoRankup((Player) sender, rest);
            }
            case "reload" -> {
                if (!check(sender, "srank.admin")) return true;
                plugin.getRankManager().load();
                plugin.getDataManager().reload();
                msg(sender, "&a配置已重载（段位与计分配置均已重新读取）");
            }
            // 兼容旧命令，提示新位置
            case "query", "recalc", "give", "leaderboard", "top" ->
                    msg(sender, "&e该命令已迁移到 &f/srank score " + sub + "&e，请使用新写法");
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        msg(sender, "&6===== 生存段位 =====");
        msg(sender, "&e/srank score [query|list|top|recalc|give] &7- 生存段位分相关");
        msg(sender, "&e/srank list [页码] &7- 查看所有段位");
        msg(sender, "&e/srank info [玩家] &7- 查看段位与进度");
        msg(sender, "&e/srank rankup &7- 手动升级段位");
        msg(sender, "&e/srank autorankup [true|false] &7- 开关自动升级");
        if (sender.hasPermission("srank.admin")) {
            msg(sender, "&e/srank rankset <玩家> <段位> &7- 强制设置段位");
            msg(sender, "&e/srank reload &7- 重载配置");
        }
    }

    // ==================== /srank score ... ====================

    private void handleScore(CommandSender sender, String[] args) {
        if (!check(sender, "srank.user")) return;
        if (args.length == 0) {
            if (!(sender instanceof Player self)) { msg(sender, "&c请指定玩家"); return; }
            showScore(sender, self.getName());
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (sub) {
            case "query" -> {
                if (rest.length >= 1) showScore(sender, rest[0]);
                else if (sender instanceof Player self) showScore(sender, self.getName());
                else msg(sender, "&c请指定玩家名");
            }
            case "list" -> {
                String name = rest.length >= 1 ? rest[0] : (sender instanceof Player self ? self.getName() : null);
                if (name == null) { msg(sender, "&c请指定玩家名"); return; }
                String category = rest.length >= 2 ? rest[1].toLowerCase(Locale.ROOT) : "all";
                showStatsByName(sender, name, category);
            }
            case "top", "leaderboard" -> showLeaderboard(sender, parseInt(rest.length > 0 ? rest[0] : "1", 1));
            case "recalc" -> {
                if (!check(sender, "srank.admin")) return;
                plugin.getDataManager().recalcAllPlayers();
                msg(sender, "&a已重新计算所有在线玩家的生存段位分（触发记录与赠与分保留）");
            }
            case "give" -> {
                if (!check(sender, "srank.admin")) return;
                handleGive(sender, rest);
            }
            default -> msg(sender, "&c未知子命令，可用: &fquery / list / top / recalc / give");
        }
    }

    private void showScore(CommandSender sender, String name) {
        ScoreDataManager.PlayerData data = resolveData(name);
        if (data == null) { msg(sender, "&c没有找到该玩家的数据"); return; }
        Rank rank = resolveRank(data);
        msg(sender, "&6" + name + " &7的生存段位分: &a" + fmt(data.getScore()));
        if (rank != null) {
            sender.sendMessage(Component.text("当前段位: ").color(NamedTextColor.GRAY).append(rank.getDisplayComponent()));
        }
    }

    // ==================== /srank info ====================

    private void handleInfo(CommandSender sender, String[] args) {
        String name = args.length >= 1 ? args[0] : (sender instanceof Player self ? self.getName() : null);
        if (name == null) { msg(sender, "&c请指定玩家名"); return; }
        ScoreDataManager.PlayerData data = resolveData(name);
        if (data == null) { msg(sender, "&c没有找到该玩家的数据"); return; }
        RankManager rankManager = plugin.getRankManager();
        Rank rank = resolveRank(data);
        double score = data.getScore();

        msg(sender, "&6===== " + name + " 的段位 =====");
        if (rank == null) {
            msg(sender, "&c尚未初始化段位");
            return;
        }
        sender.sendMessage(Component.text("当前段位: ").color(NamedTextColor.GRAY)
                .append(rank.getDisplayComponent())
                .append(Component.text(" (#" + rank.getIndex() + "/" + rankManager.size() + ")").color(NamedTextColor.DARK_GRAY)));
        msg(sender, "&7生存段位分: &a" + fmt(score) + " &7/ 本段位要求 &f" + fmt(rank.getScore()));
        Rank next = rankManager.next(rank);
        if (next == null) {
            msg(sender, "&d已达到最高段位");
        } else {
            msg(sender, "&7下一段位: &f" + next.getDisplayPlain() + " &7需要 &a" + fmt(next.getScore())
                    + " &7分（还差 &e" + fmt(Math.max(0, next.getScore() - score)) + "&7）");
            double span = next.getScore() - rank.getScore();
            double progress = span <= 0 ? 100 : Math.max(0, Math.min(100, (score - rank.getScore()) / span * 100));
            msg(sender, "&7升级进度: &b" + String.format("%.1f", progress) + "%");
        }
        msg(sender, "&7自动升级: " + (data.isAutoRankup() ? "&a开启" : "&c关闭") + " &8(/srank autorankup 切换)");
    }

    // ==================== /srank list ====================

    private void showRankList(CommandSender sender, int page) {
        RankManager rankManager = plugin.getRankManager();
        List<Rank> ranks = rankManager.getRanks();
        if (ranks.isEmpty()) { msg(sender, "&c没有加载到任何段位"); return; }
        int totalPages = (int) Math.ceil(ranks.size() / (double) LIST_PAGE_SIZE);
        int current = Math.max(1, Math.min(page, totalPages));
        Rank mine = null;
        if (sender instanceof Player self) {
            ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerData(self.getUniqueId());
            mine = data == null ? null : resolveRank(data);
        }
        msg(sender, "&6===== 生存段位列表 &7(第 " + current + "/" + totalPages + " 页, 共 " + ranks.size() + " 个) &6=====");
        int from = (current - 1) * LIST_PAGE_SIZE;
        int to = Math.min(from + LIST_PAGE_SIZE, ranks.size());
        for (int i = from; i < to; i++) {
            Rank rank = ranks.get(i);
            Component line = Component.text("#" + rank.getIndex() + " ", NamedTextColor.DARK_GRAY)
                    .append(rank.getDisplayComponent())
                    .append(Component.text("  " + fmt(rank.getScore()) + " 分", NamedTextColor.GRAY));
            if (rank.getMoney() > 0) {
                line = line.append(Component.text(" · " + fmt(rank.getMoney()) + " 金币", NamedTextColor.GOLD));
            }
            if (!rank.isAuto()) {
                line = line.append(Component.text(" · 需手动升级", NamedTextColor.RED));
            }
            if (mine != null && mine.getId().equalsIgnoreCase(rank.getId())) {
                line = line.append(Component.text("  ← 当前", NamedTextColor.GREEN));
            }
            sender.sendMessage(line);
        }
    }

    // ==================== /srank rankup ====================

    private void handleRankup(Player player) {
        RankManager rankManager = plugin.getRankManager();
        ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerData(player.getUniqueId());
        if (data == null) { msg(player, "&c你的数据尚未初始化，请稍后再试"); return; }
        Rank before = resolveRank(data);
        int steps = rankManager.rankup(player, true);
        if (steps == 0) {
            Rank next = rankManager.next(before);
            if (next == null) {
                msg(player, "&d你已经是最高段位了");
            } else {
                msg(player, "&c升级失败：需要 &e" + fmt(next.getScore()) + " &c生存段位分（当前 &e"
                        + fmt(data.getScore()) + "&c）");
            }
        }
    }

    // ==================== /srank rankset ====================

    private void handleRankSet(CommandSender sender, String[] args) {
        if (args.length < 2) { msg(sender, "&c用法: /srank rankset <玩家> <段位>"); return; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { msg(sender, "&c玩家不在线"); return; }
        Rank rank = plugin.getRankManager().resolve(args[1]);
        if (rank == null) {
            msg(sender, "&c找不到段位: &f" + args[1] + "&c（可用注册名，或 /srank list 里显示的段位名）");
            return;
        }
        if (plugin.getDataManager().getPlayerData(target.getUniqueId()) == null) {
            plugin.getDataManager().initPlayer(target, false);
        }
        plugin.getRankManager().setRank(target, rank);
        sender.sendMessage(Component.text("已将 " + target.getName() + " 的段位设置为 ").color(NamedTextColor.GREEN)
                .append(rank.getDisplayComponent()));
        target.sendMessage(Component.text("你的生存段位已被设置为 ").color(NamedTextColor.YELLOW)
                .append(rank.getDisplayComponent()));
    }

    // ==================== /srank autorankup ====================

    private void handleAutoRankup(Player player, String[] args) {
        ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerData(player.getUniqueId());
        if (data == null) { msg(player, "&c你的数据尚未初始化"); return; }
        boolean value;
        if (args.length >= 1) {
            if (!args[0].equalsIgnoreCase("true") && !args[0].equalsIgnoreCase("false")) {
                msg(player, "&c用法: /srank autorankup [true|false]");
                return;
            }
            value = Boolean.parseBoolean(args[0]);
        } else {
            value = !data.isAutoRankup();
        }
        plugin.getDataManager().setAutoRankup(player.getUniqueId(), value);
        msg(player, value ? "&a已开启自动升级" : "&c已关闭自动升级（可以随时用 /srank rankup 手动升级）");
    }

    // ==================== 生存段位分详情 ====================

    private void showStatsByName(CommandSender sender, String name, String category) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            showStats(sender, online, category);
        } else {
            showStatsOffline(sender, Bukkit.getOfflinePlayer(name), category);
        }
    }

    private void showStats(CommandSender sender, Player player, String cat) {
        ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerData(player.getUniqueId());
        if (data == null) { msg(sender, "&c无数据"); return; }
        printStats(sender, plugin.getDataManager(), data, player.getName(), false, cat);
    }

    private void showStatsOffline(CommandSender sender, OfflinePlayer player, String cat) {
        ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerDataOffline(player.getName());
        if (data == null) { msg(sender, "&c无数据"); return; }
        printStats(sender, plugin.getDataManager(), data, player.getName(), true, cat);
    }

    private void printStats(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerData data,
                            String name, boolean offline, String cat) {
        ScoreDataManager.PlayerStatsSnapshot s = data.getStats();
        msg(sender, "&6===== " + name + (offline ? " (离线)" : "") + " 的段位分详情 =====");
        if (cat.equals("base") || cat.equals("all")) showBase(sender, dm, s);
        if (cat.equals("blocks") || cat.equals("all")) showBlocks(sender, dm, s);
        if (cat.equals("mobs") || cat.equals("all")) showMobs(sender, dm, s);
        if (cat.equals("achievements") || cat.equals("ach") || cat.equals("all")) showAchievements(sender, s);
        if (cat.equals("deaths") || cat.equals("all")) showDeaths(sender, s, data);
        msg(sender, "&a总分: " + fmt(data.getScore()));
        msg(sender, "&6=============================");
    }

    private void showBase(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        msg(sender, "&b--- 基础统计 ---");
        msg(sender, "&e挖掘方块: " + s.blockBreak + " × " + dm.weightBlockBreak + " = " + fmt(s.discountedBlockBreak));
        msg(sender, "&e放置方块: " + s.blockPlace + " × " + dm.weightBlockPlace + " = " + fmt(s.discountedBlockPlace));
        msg(sender, "&e合成: " + s.craftItem + " × " + dm.weightCraft + " = " + fmt(s.discountedCraft));
        msg(sender, "&e击杀(总): " + s.mobKills + " → " + fmt(s.discountedMobKill));
        msg(sender, "&e击杀玩家: " + s.playerKills + " × " + dm.weightPlayerKill + " = " + fmt(s.discountedPlayerKill));
        msg(sender, "&e伤害: " + s.damageDealt + " = " + fmt(s.discountedDamage));
        msg(sender, "&e交易: " + s.trades + " = " + fmt(s.discountedTrade));
        msg(sender, "&e附魔: " + s.enchants + " = " + fmt(s.discountedEnchant));
        msg(sender, "&e跳跃: " + s.jumps + " = " + fmt(s.discountedJump));
        msg(sender, "&e移动: " + s.totalMove + " cm = " + fmt(s.discountedMove));
        msg(sender, "&e在线: " + s.playTicks + " tick = " + fmt(s.discountedPlayTick));
        msg(sender, "&e钓鱼: " + s.fishingCaught + " = " + fmt(s.discountedFishing));
        msg(sender, "&e袭击胜利: " + s.raidWins + " = " + fmt(s.discountedRaidWin));
    }

    private void showBlocks(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        if (s.specificBlockCounts.isEmpty()) { msg(sender, "&b--- 特殊方块 (无) ---"); return; }
        msg(sender, "&b--- 特殊方块破坏额外分（合并到挖掘）---");
        for (Map.Entry<Material, Long> e : s.specificBlockCounts.entrySet()) {
            double extra = dm.getExtraBlockScore(e.getKey());
            msg(sender, "&e" + ChineseNames.getBlockName(e.getKey()) + ": " + e.getValue() + " × " + extra
                    + " = " + fmt(e.getValue() * extra));
        }
    }

    private void showMobs(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        if (s.specificMobCounts.isEmpty()) { msg(sender, "&b--- 特殊生物 (无) ---"); return; }
        msg(sender, "&b--- 特殊生物击杀额外分（合并到击杀）---");
        for (Map.Entry<EntityType, Long> e : s.specificMobCounts.entrySet()) {
            double extra = dm.getExtraMobScore(e.getKey());
            msg(sender, "&e" + ChineseNames.getMobName(e.getKey()) + ": " + e.getValue() + " × " + extra
                    + " = " + fmt(e.getValue() * extra));
        }
    }

    private void showAchievements(CommandSender sender, ScoreDataManager.PlayerStatsSnapshot s) {
        msg(sender, "&b--- 进度 ---");
        msg(sender, "&e完成数量: " + s.advancements.size() + " 总分: " + fmt(s.discountedAdvancements));
    }

    private void showDeaths(CommandSender sender, ScoreDataManager.PlayerStatsSnapshot s, ScoreDataManager.PlayerData data) {
        msg(sender, "&b--- 死亡 ---");
        msg(sender, "&e死亡次数: " + s.deaths);
        msg(sender, "&e累计扣除: " + fmt(data.getTotalPenalty()));
    }

    private void showLeaderboard(CommandSender sender, int page) {
        List<Map.Entry<String, Double>> list = plugin.getDataManager().getTopPlayers();
        if (list.isEmpty()) { msg(sender, "&c无数据"); return; }
        int total = (int) Math.ceil(list.size() / (double) STATS_PAGE_SIZE);
        int current = Math.max(1, Math.min(page, total));
        int start = (current - 1) * STATS_PAGE_SIZE;
        int end = Math.min(start + STATS_PAGE_SIZE, list.size());
        msg(sender, "&6===== 段位分排行榜 (第" + current + "/" + total + "页) =====");
        for (int i = start; i < end; i++) {
            Map.Entry<String, Double> e = list.get(i);
            msg(sender, "&e" + (i + 1) + ". " + e.getKey() + " &f→ &a" + fmt(e.getValue()));
        }
    }

    // ==================== give ====================

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length == 0) {
            msg(sender, "&c用法: /srank score give <玩家> <分数> [原因]");
            msg(sender, "&c      /srank score give undo|edit|list <玩家> ...");
            return;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        if (first.equals("undo") || first.equals("edit") || first.equals("list")) {
            if (args.length < 2) { msg(sender, "&c缺少玩家名"); return; }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) { msg(sender, "&c玩家不在线"); return; }
            switch (first) {
                case "undo" -> {
                    if (args.length < 3) { msg(sender, "&c用法: /srank score give undo <玩家> <id>"); return; }
                    int id = parseInt(args[2], -1);
                    if (id < 0) { msg(sender, "&cID 必须是数字"); return; }
                    msg(sender, plugin.getDataManager().undoGiveRecord(target.getUniqueId(), id)
                            ? "&a已撤销记录 " + id : "&c记录不存在");
                }
                case "edit" -> {
                    if (args.length < 4) { msg(sender, "&c用法: /srank score give edit <玩家> <id> <新分数> [新原因]"); return; }
                    int id = parseInt(args[2], -1);
                    if (id < 0) { msg(sender, "&cID 必须是数字"); return; }
                    double amount;
                    try {
                        amount = Double.parseDouble(args[3]);
                    } catch (NumberFormatException e) {
                        msg(sender, "&c分数必须是数字");
                        return;
                    }
                    String reason = args.length >= 5 ? args[4] : null;
                    msg(sender, plugin.getDataManager().editGiveRecord(target.getUniqueId(), id, amount, reason)
                            ? "&a已修改记录 " + id : "&c记录不存在");
                }
                default -> {
                    List<ScoreDataManager.GiveRecord> records = plugin.getDataManager().getGiveRecords(target.getUniqueId());
                    if (records.isEmpty()) { msg(sender, "&e该玩家没有赠与记录"); return; }
                    msg(sender, "&6---- " + target.getName() + " 的赠与记录 ----");
                    for (ScoreDataManager.GiveRecord r : records) {
                        msg(sender, "&e#" + r.id + ": " + fmt(r.amount) + " 分, 原因: " + r.reason + ", 操作者: " + r.operator);
                    }
                }
            }
            return;
        }
        if (args.length < 2) { msg(sender, "&c用法: /srank score give <玩家> <分数> [原因]"); return; }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) { msg(sender, "&c玩家不在线"); return; }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            msg(sender, "&c分数必须是数字");
            return;
        }
        String reason = args.length >= 3 ? args[2] : "管理员操作";
        if (plugin.getDataManager().getPlayerData(target.getUniqueId()) == null) {
            plugin.getDataManager().initPlayer(target, false);
        }
        int recordId = plugin.getDataManager().addGiveRecord(target.getUniqueId(), amount, reason, sender.getName());
        if (recordId < 0) { msg(sender, "&c该玩家数据尚未初始化，请稍后再试"); return; }
        plugin.getDataManager().savePlayerDataIfDirty(target.getUniqueId());
        msg(sender, "&a已给予 " + fmt(amount) + " 分，记录ID=" + recordId);
        target.sendMessage(Component.text("你收到了 " + fmt(amount) + " 生存段位分（" + reason + "）").color(NamedTextColor.GREEN));
    }

    // ==================== 工具 ====================

    private ScoreDataManager.PlayerData resolveData(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return plugin.getDataManager().getPlayerData(online.getUniqueId());
        return plugin.getDataManager().getPlayerDataOffline(name);
    }

    private Rank resolveRank(ScoreDataManager.PlayerData data) {
        if (data == null) return null;
        RankManager rankManager = plugin.getRankManager();
        Rank rank = data.getRankId() == null ? null : rankManager.getById(data.getRankId());
        return rank != null ? rank : rankManager.getByScore(data.getScore());
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        msg(sender, "&c无权限");
        return false;
    }

    private boolean checkPlayer(CommandSender sender) {
        if (sender instanceof Player) return true;
        msg(sender, "&c该命令只能由玩家执行");
        return false;
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(TextFormatter.parse(text));
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 1e-6) return String.valueOf((long) Math.rint(value));
        return String.format("%.2f", value);
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }

    // ==================== Tab 补全 ====================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(Arrays.asList("score", "list", "info", "rankup", "autorankup"));
            if (sender.hasPermission("srank.admin")) {
                options.add("rankset");
                options.add("reload");
            }
            return filter(options, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("score")) {
            if (args.length == 2) {
                List<String> options = new ArrayList<>(Arrays.asList("query", "list", "top"));
                if (sender.hasPermission("srank.admin")) {
                    options.add("recalc");
                    options.add("give");
                }
                return filter(options, args[1]);
            }
            if (args.length == 3) {
                String action = args[1].toLowerCase(Locale.ROOT);
                if (action.equals("query") || action.equals("list")) return null;   // 玩家名
                if (action.equals("give") && sender.hasPermission("srank.admin")) {
                    return filter(Arrays.asList("undo", "edit", "list"), args[2]);
                }
            }
            return new ArrayList<>();
        }
        if (sub.equals("rankset")) {
            if (args.length == 2) return null;                                       // 玩家名
            if (args.length == 3) {
                List<String> ids = new ArrayList<>();
                for (Rank rank : plugin.getRankManager().getRanks()) ids.add(rank.getId());
                return filter(ids, args[2]);
            }
            return new ArrayList<>();
        }
        if (sub.equals("autorankup") && args.length == 2) {
            return filter(Arrays.asList("true", "false"), args[1]);
        }
        if (sub.equals("info") && args.length == 2) return null;
        return new ArrayList<>();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) result.add(option);
        }
        return result;
    }

    // ==================== 中文翻译 ====================

    private static class ChineseNames {
        private static final Map<String, String> BLOCK_NAMES = new HashMap<>();
        private static final Map<String, String> MOB_NAMES = new HashMap<>();

        static {
            BLOCK_NAMES.put("OAK_LOG", "橡木原木");
            BLOCK_NAMES.put("SPRUCE_LOG", "云杉原木");
            BLOCK_NAMES.put("BIRCH_LOG", "白桦原木");
            BLOCK_NAMES.put("JUNGLE_LOG", "丛林原木");
            BLOCK_NAMES.put("ACACIA_LOG", "金合欢原木");
            BLOCK_NAMES.put("DARK_OAK_LOG", "深色橡木原木");
            BLOCK_NAMES.put("CRIMSON_STEM", "绯红菌柄");
            BLOCK_NAMES.put("WARPED_STEM", "诡异菌柄");
            BLOCK_NAMES.put("COAL_ORE", "煤矿石");
            BLOCK_NAMES.put("DEEPSLATE_COAL_ORE", "深板岩煤矿石");
            BLOCK_NAMES.put("COPPER_ORE", "铜矿石");
            BLOCK_NAMES.put("DEEPSLATE_COPPER_ORE", "深板岩铜矿石");
            BLOCK_NAMES.put("IRON_ORE", "铁矿石");
            BLOCK_NAMES.put("DEEPSLATE_IRON_ORE", "深板岩铁矿石");
            BLOCK_NAMES.put("GOLD_ORE", "金矿石");
            BLOCK_NAMES.put("DEEPSLATE_GOLD_ORE", "深板岩金矿石");
            BLOCK_NAMES.put("DIAMOND_ORE", "钻石矿石");
            BLOCK_NAMES.put("DEEPSLATE_DIAMOND_ORE", "深板岩钻石矿石");
            BLOCK_NAMES.put("EMERALD_ORE", "绿宝石矿石");
            BLOCK_NAMES.put("DEEPSLATE_EMERALD_ORE", "深板岩绿宝石矿石");
            BLOCK_NAMES.put("LAPIS_ORE", "青金石矿石");
            BLOCK_NAMES.put("DEEPSLATE_LAPIS_ORE", "深板岩青金石矿石");
            BLOCK_NAMES.put("REDSTONE_ORE", "红石矿石");
            BLOCK_NAMES.put("DEEPSLATE_REDSTONE_ORE", "深板岩红石矿石");
            BLOCK_NAMES.put("NETHER_QUARTZ_ORE", "下界石英矿石");
            BLOCK_NAMES.put("ANCIENT_DEBRIS", "远古残骸");
            BLOCK_NAMES.put("NETHER_GOLD_ORE", "下界金矿石");
            BLOCK_NAMES.put("OBSIDIAN", "黑曜石");

            MOB_NAMES.put("COW", "牛");
            MOB_NAMES.put("SHEEP", "羊");
            MOB_NAMES.put("CHICKEN", "鸡");
            MOB_NAMES.put("PIG", "猪");
            MOB_NAMES.put("ZOMBIE", "僵尸");
            MOB_NAMES.put("SKELETON", "骷髅");
            MOB_NAMES.put("CREEPER", "苦力怕");
            MOB_NAMES.put("SPIDER", "蜘蛛");
            MOB_NAMES.put("ENDERMAN", "末影人");
            MOB_NAMES.put("PILLAGER", "掠夺者");
            MOB_NAMES.put("VINDICATOR", "卫道士");
            MOB_NAMES.put("EVOKER", "唤魔者");
            MOB_NAMES.put("VEX", "恼鬼");
            MOB_NAMES.put("WITCH", "女巫");
            MOB_NAMES.put("RAVAGER", "劫掠兽");
            MOB_NAMES.put("GUARDIAN", "守卫者");
            MOB_NAMES.put("ELDER_GUARDIAN", "远古守卫者");
            MOB_NAMES.put("DROWNED", "溺尸");
            MOB_NAMES.put("HUSK", "尸壳");
            MOB_NAMES.put("ZOMBIE_VILLAGER", "僵尸村民");
            MOB_NAMES.put("PHANTOM", "幻翼");
            MOB_NAMES.put("STRAY", "流浪者");
            MOB_NAMES.put("SLIME", "史莱姆");
            MOB_NAMES.put("ZOMBIFIED_PIGLIN", "僵尸猪灵");
            MOB_NAMES.put("PIGLIN", "猪灵");
            MOB_NAMES.put("PIGLIN_BRUTE", "猪灵蛮兵");
            MOB_NAMES.put("GHAST", "恶魂");
            MOB_NAMES.put("BLAZE", "烈焰人");
            MOB_NAMES.put("MAGMA_CUBE", "岩浆怪");
            MOB_NAMES.put("WITHER_SKELETON", "凋零骷髅");
            MOB_NAMES.put("HOGLIN", "疣猪兽");
            MOB_NAMES.put("WARDEN", "坚守者");
            MOB_NAMES.put("WITHER", "凋灵");
            MOB_NAMES.put("ENDER_DRAGON", "末影龙");
            MOB_NAMES.put("ARMADILLO", "犰狳");
            MOB_NAMES.put("BOGGED", "沼骸");
            MOB_NAMES.put("BREEZE", "旋风人");
            MOB_NAMES.put("HAPPY_GHAST", "快乐恶魂");
            MOB_NAMES.put("COPPER_GOLEM", "铜傀儡");
            MOB_NAMES.put("PARCHED", "干尸");
            MOB_NAMES.put("CAMEL_HUSK", "骆驼尸壳");
            MOB_NAMES.put("NAUTILUS", "鹦鹉螺");
            MOB_NAMES.put("ZOMBIE_NAUTILUS", "僵尸鹦鹉螺");
        }

        static String getBlockName(Material material) {
            return BLOCK_NAMES.getOrDefault(material.name(), material.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }

        static String getMobName(EntityType type) {
            return MOB_NAMES.getOrDefault(type.name(), type.name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
    }
}
