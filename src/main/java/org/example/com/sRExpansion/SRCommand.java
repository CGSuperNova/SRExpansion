package org.example.com.sRExpansion;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.*;

public class SRCommand implements CommandExecutor, TabCompleter {
    private final SRExpansion plugin;
    public SRCommand(SRExpansion plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "用法: /srank <reload|recalc|give|query|list|leaderboard>");
            sender.sendMessage(ChatColor.YELLOW + "  give 子命令: /srank give <玩家> <分数> [原因]");
            sender.sendMessage(ChatColor.YELLOW + "  give 管理: /srank give undo <玩家> <id>");
            sender.sendMessage(ChatColor.YELLOW + "  give 管理: /srank give edit <玩家> <id> <新分数> [新原因]");
            sender.sendMessage(ChatColor.YELLOW + "  give 管理: /srank give list <玩家>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("srank.admin")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                plugin.getDataManager().reload();
                sender.sendMessage(ChatColor.GREEN + "配置已重载（分数未变）。如需重算请使用 /srank recalc");
                break;

            case "recalc":
                if (!sender.hasPermission("srank.admin")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                plugin.getDataManager().recalcAllPlayers();
                sender.sendMessage(ChatColor.GREEN + "已重新计算所有在线玩家分数（触发记录保留）");
                break;

            case "give":
                if (!sender.hasPermission("srank.admin")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                // 检查是否为子命令
                if (args.length >= 2 && (args[1].equalsIgnoreCase("undo") || args[1].equalsIgnoreCase("edit") || args[1].equalsIgnoreCase("list"))) {
                    // 子命令处理
                    switch (args[1].toLowerCase()) {
                        case "undo":
                            if (args.length < 4) { sender.sendMessage(ChatColor.RED + "用法: /srank give undo <玩家> <id>"); return true; }
                            Player pUndo = Bukkit.getPlayer(args[2]);
                            if (pUndo == null) { sender.sendMessage(ChatColor.RED + "玩家不在线"); return true; }
                            int idUndo;
                            try { idUndo = Integer.parseInt(args[3]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "ID必须为数字"); return true; }
                            if (plugin.getDataManager().undoGiveRecord(pUndo.getUniqueId(), idUndo)) {
                                sender.sendMessage(ChatColor.GREEN + "已撤销记录 " + idUndo);
                            } else {
                                sender.sendMessage(ChatColor.RED + "记录不存在");
                            }
                            break;
                        case "edit":
                            if (args.length < 5) { sender.sendMessage(ChatColor.RED + "用法: /srank give edit <玩家> <id> <新分数> [新原因]"); return true; }
                            Player pEdit = Bukkit.getPlayer(args[2]);
                            if (pEdit == null) { sender.sendMessage(ChatColor.RED + "玩家不在线"); return true; }
                            int idEdit;
                            try { idEdit = Integer.parseInt(args[3]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "ID必须为数字"); return true; }
                            double newAmount;
                            try { newAmount = Double.parseDouble(args[4]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "分数必须为数字"); return true; }
                            String newReason = args.length >= 6 ? args[5] : null;
                            if (plugin.getDataManager().editGiveRecord(pEdit.getUniqueId(), idEdit, newAmount, newReason)) {
                                sender.sendMessage(ChatColor.GREEN + "已修改记录 " + idEdit);
                            } else {
                                sender.sendMessage(ChatColor.RED + "记录不存在");
                            }
                            break;
                        case "list":
                            if (args.length < 3) { sender.sendMessage(ChatColor.RED + "用法: /srank give list <玩家>"); return true; }
                            Player pList = Bukkit.getPlayer(args[2]);
                            if (pList == null) { sender.sendMessage(ChatColor.RED + "玩家不在线"); return true; }
                            List<ScoreDataManager.GiveRecord> records = plugin.getDataManager().getGiveRecords(pList.getUniqueId());
                            if (records.isEmpty()) { sender.sendMessage(ChatColor.YELLOW + "该玩家无记录"); return true; }
                            sender.sendMessage(ChatColor.GOLD + "---- " + pList.getName() + " 的 Give 记录 ----");
                            for (ScoreDataManager.GiveRecord r : records) {
                                sender.sendMessage(String.format(ChatColor.YELLOW + "#%d: %.2f 分, 原因: %s, 操作者: %s", r.id, r.amount, r.reason, r.operator));
                            }
                            break;
                        default:
                            sender.sendMessage(ChatColor.RED + "未知子命令");
                    }
                } else {
                    // 基础 give 命令：给予分数
                    if (args.length < 3) {
                        sender.sendMessage(ChatColor.RED + "用法: /srank give <玩家> <分数> [原因]");
                        return true;
                    }
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) { sender.sendMessage(ChatColor.RED + "玩家不在线"); return true; }
                    double amount;
                    try { amount = Double.parseDouble(args[2]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "分数必须为数字"); return true; }
                    String reason = args.length >= 4 ? args[3] : "管理员操作";
                    if (plugin.getDataManager().getPlayerData(target.getUniqueId()) == null) {
                        plugin.getDataManager().initPlayer(target, false);
                    }
                    // 加分与记录在 addGiveRecord 内部一起完成，避免重复加
                    int recordId = plugin.getDataManager().addGiveRecord(target.getUniqueId(), amount, reason, sender.getName());
                    if (recordId < 0) { sender.sendMessage(ChatColor.RED + "该玩家数据尚未初始化，请稍后再试"); return true; }
                    plugin.getDataManager().savePlayerDataIfDirty(target.getUniqueId());
                    sender.sendMessage(ChatColor.GREEN + "已给予 " + String.format("%.2f", amount) + " 分，记录ID=" + recordId);
                }
                break;

            case "query":
                if (!sender.hasPermission("srank.user")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                Player qp;
                if (args.length >= 2) { qp = Bukkit.getPlayer(args[1]); if (qp == null) { sender.sendMessage(ChatColor.RED + "玩家不在线"); return true; } }
                else if (sender instanceof Player) qp = (Player) sender;
                else { sender.sendMessage(ChatColor.RED + "请指定玩家名"); return true; }
                ScoreDataManager.PlayerData qd = plugin.getDataManager().getPlayerData(qp.getUniqueId());
                if (qd == null) { sender.sendMessage(ChatColor.RED + "该玩家尚无数据"); return true; }
                sender.sendMessage(ChatColor.GOLD + qp.getName() + " 的段位分: " + ChatColor.GREEN + String.format("%.2f", qd.getScore()));
                break;

            case "list":
                if (!sender.hasPermission("srank.user")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                OfflinePlayer off = null; Player on = null; String cat = "all";
                if (args.length >= 2) {
                    Player p = Bukkit.getPlayer(args[1]);
                    if (p != null) { on = p; if (args.length>=3) cat = args[2].toLowerCase(); }
                    else { off = Bukkit.getOfflinePlayer(args[1]); if (off.hasPlayedBefore() || plugin.getDataManager().hasData(off.getUniqueId())) { if (args.length>=3) cat = args[2].toLowerCase(); } else { sender.sendMessage(ChatColor.RED + "玩家无数据"); return true; } }
                } else if (sender instanceof Player) on = (Player) sender;
                else { sender.sendMessage(ChatColor.RED + "请指定玩家名"); return true; }
                if (on != null) showStats(sender, on, cat);
                else if (off != null) showStatsOffline(sender, off, cat);
                break;

            case "leaderboard":
                if (!sender.hasPermission("srank.user")) { sender.sendMessage(ChatColor.RED + "无权限"); return true; }
                int page = 1;
                if (args.length >= 2) try { page = Integer.parseInt(args[1]); } catch (NumberFormatException e) {}
                showLeaderboard(sender, page);
                break;

            default:
                sender.sendMessage(ChatColor.RED + "未知命令");
        }
        return true;
    }

    // ========== 显示方法 ==========
    private void showStats(CommandSender sender, Player player, String cat) {
        ScoreDataManager dm = plugin.getDataManager();
        ScoreDataManager.PlayerData data = dm.getPlayerData(player.getUniqueId());
        if (data == null) { sender.sendMessage(ChatColor.RED + "无数据"); return; }
        ScoreDataManager.PlayerStatsSnapshot s = data.getStats();
        sender.sendMessage(ChatColor.GOLD + "===== " + player.getName() + " 的段位分详情 =====");
        if (cat.equals("base") || cat.equals("all")) showBase(sender, dm, s);
        if (cat.equals("blocks") || cat.equals("all")) showBlocks(sender, dm, s);
        if (cat.equals("mobs") || cat.equals("all")) showMobs(sender, dm, s);
        if (cat.equals("achievements") || cat.equals("ach") || cat.equals("all")) showAchievements(sender, s);
        if (cat.equals("deaths") || cat.equals("all")) showDeaths(sender, s, data);
        sender.sendMessage(ChatColor.GREEN + "总分: " + String.format("%.2f", data.getScore()));
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    private void showStatsOffline(CommandSender sender, OfflinePlayer player, String cat) {
        ScoreDataManager.PlayerData data = plugin.getDataManager().getPlayerDataOffline(player.getName());
        if (data == null) { sender.sendMessage(ChatColor.RED + "无数据"); return; }
        ScoreDataManager.PlayerStatsSnapshot s = data.getStats();
        sender.sendMessage(ChatColor.GOLD + "===== " + player.getName() + " (离线) 的段位分详情 =====");
        if (cat.equals("base") || cat.equals("all")) showBase(sender, plugin.getDataManager(), s);
        if (cat.equals("blocks") || cat.equals("all")) showBlocks(sender, plugin.getDataManager(), s);
        if (cat.equals("mobs") || cat.equals("all")) showMobs(sender, plugin.getDataManager(), s);
        if (cat.equals("achievements") || cat.equals("ach") || cat.equals("all")) showAchievements(sender, s);
        if (cat.equals("deaths") || cat.equals("all")) showDeaths(sender, s, data);
        sender.sendMessage(ChatColor.GREEN + "总分: " + String.format("%.2f", data.getScore()));
        sender.sendMessage(ChatColor.GOLD + "=============================");
    }

    private void showBase(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        sender.sendMessage(ChatColor.AQUA + "--- 基础统计 ---");
        sender.sendMessage(ChatColor.YELLOW + "挖掘方块: " + s.blockBreak + " × " + dm.weightBlockBreak + " = " + String.format("%.2f", s.discountedBlockBreak));
        sender.sendMessage(ChatColor.YELLOW + "放置方块: " + s.blockPlace + " × " + dm.weightBlockPlace + " = " + String.format("%.2f", s.discountedBlockPlace));
        sender.sendMessage(ChatColor.YELLOW + "合成: " + s.craftItem + " × " + dm.weightCraft + " = " + String.format("%.2f", s.discountedCraft));
        sender.sendMessage(ChatColor.YELLOW + "击杀(总): " + s.mobKills + " → " + String.format("%.2f", s.discountedMobKill));
        sender.sendMessage(ChatColor.YELLOW + "击杀玩家: " + s.playerKills + " × " + dm.weightPlayerKill + " = " + String.format("%.2f", s.discountedPlayerKill));
        sender.sendMessage(ChatColor.YELLOW + "伤害: " + s.damageDealt + " ❤ = " + String.format("%.2f", s.discountedDamage));
        sender.sendMessage(ChatColor.YELLOW + "交易: " + s.trades + " = " + String.format("%.2f", s.discountedTrade));
        sender.sendMessage(ChatColor.YELLOW + "附魔: " + s.enchants + " = " + String.format("%.2f", s.discountedEnchant));
        sender.sendMessage(ChatColor.YELLOW + "跳跃: " + s.jumps + " = " + String.format("%.2f", s.discountedJump));
        sender.sendMessage(ChatColor.YELLOW + "移动: " + s.totalMove + " cm = " + String.format("%.2f", s.discountedMove));
        sender.sendMessage(ChatColor.YELLOW + "在线: " + s.playTicks + " tick = " + String.format("%.2f", s.discountedPlayTick));
        sender.sendMessage(ChatColor.YELLOW + "钓鱼: " + s.fishingCaught + " = " + String.format("%.2f", s.discountedFishing));
        sender.sendMessage(ChatColor.YELLOW + "袭击胜利: " + s.raidWins + " = " + String.format("%.2f", s.discountedRaidWin));
    }

    private void showBlocks(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        if (s.specificBlockCounts.isEmpty()) { sender.sendMessage(ChatColor.AQUA + "--- 特殊方块 (无) ---"); return; }
        sender.sendMessage(ChatColor.AQUA + "--- 特殊方块破坏额外分（合并到挖掘）---");
        for (Map.Entry<Material, Long> e : s.specificBlockCounts.entrySet()) {
            String name = ChineseNames.getBlockName(e.getKey());
            double extra = dm.getExtraBlockScore(e.getKey());
            double contrib = e.getValue() * extra;
            sender.sendMessage(ChatColor.YELLOW + name + ": " + e.getValue() + " × " + extra + " = " + String.format("%.2f", contrib));
        }
    }

    private void showMobs(CommandSender sender, ScoreDataManager dm, ScoreDataManager.PlayerStatsSnapshot s) {
        if (s.specificMobCounts.isEmpty()) { sender.sendMessage(ChatColor.AQUA + "--- 特殊生物 (无) ---"); return; }
        sender.sendMessage(ChatColor.AQUA + "--- 特殊生物击杀额外分（合并到击杀）---");
        for (Map.Entry<EntityType, Long> e : s.specificMobCounts.entrySet()) {
            String name = ChineseNames.getMobName(e.getKey());
            double extra = dm.getExtraMobScore(e.getKey());
            double contrib = e.getValue() * extra;
            sender.sendMessage(ChatColor.YELLOW + name + ": " + e.getValue() + " × " + extra + " = " + String.format("%.2f", contrib));
        }
    }

    private void showAchievements(CommandSender sender, ScoreDataManager.PlayerStatsSnapshot s) {
        sender.sendMessage(ChatColor.AQUA + "--- 进度 ---");
        sender.sendMessage(ChatColor.YELLOW + "完成数量: " + s.advancements.size() + " 总分: " + String.format("%.2f", s.discountedAdvancements));
    }

    private void showDeaths(CommandSender sender, ScoreDataManager.PlayerStatsSnapshot s, ScoreDataManager.PlayerData data) {
        sender.sendMessage(ChatColor.AQUA + "--- 死亡 ---");
        sender.sendMessage(ChatColor.YELLOW + "死亡次数: " + s.deaths);
        sender.sendMessage(ChatColor.YELLOW + "累计扣除: " + String.format("%.2f", data.getTotalPenalty()));
    }

    private void showLeaderboard(CommandSender sender, int page) {
        List<Map.Entry<String, Double>> list = plugin.getDataManager().getTopPlayers();
        if (list.isEmpty()) { sender.sendMessage(ChatColor.RED + "无数据"); return; }
        int total = (int) Math.ceil(list.size() / 10.0);
        if (page < 1) page = 1;
        if (page > total) page = total;
        int start = (page-1)*10, end = Math.min(start+10, list.size());
        sender.sendMessage(ChatColor.GOLD + "===== 段位分排行榜 (第" + page + "/" + total + "页) =====");
        for (int i = start; i < end; i++) {
            Map.Entry<String, Double> e = list.get(i);
            String line = ChatColor.YELLOW + String.valueOf(i+1) + ". " + e.getKey() + ChatColor.WHITE + " → " + ChatColor.GREEN + String.format("%.2f", e.getValue());
            sender.sendMessage(line);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("reload","recalc","give","query","list","leaderboard");
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) return Arrays.asList("undo","edit","list");
            if (args[0].equalsIgnoreCase("query") || args[0].equalsIgnoreCase("list")) return null; // 在线玩家列表
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("list")) {
            return Arrays.asList("all","base","blocks","mobs","deaths","achievements");
        }
        return new ArrayList<>();
    }

    // ========== 中文翻译 ==========
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
            BLOCK_NAMES.put("CRIMSON_STEM", "诡异菌柄");
            BLOCK_NAMES.put("WARPED_STEM", "绯红菌柄");
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
        }

        public static String getBlockName(Material material) {
            return BLOCK_NAMES.getOrDefault(material.name(), material.name().toLowerCase().replace("_", " "));
        }

        public static String getMobName(EntityType type) {
            return MOB_NAMES.getOrDefault(type.name(), type.name().toLowerCase().replace("_", " "));
        }
    }
}