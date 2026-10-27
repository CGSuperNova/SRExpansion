package org.example.com.sRExpansion;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

public class SRPlaceholder extends PlaceholderExpansion {
    private final SRExpansion plugin;
    public SRPlaceholder(SRExpansion plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "srank"; }
    @Override public @NotNull String getAuthor() { return "YourName"; }
    @Override public @NotNull String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String identifier) {
        if (player == null) return null;
        ScoreDataManager dm = plugin.getDataManager();
        UUID uuid = player.getUniqueId();

        // ---------- 基础分数 ----------
        if (identifier.equals("score")) {
            ScoreDataManager.PlayerData data = dm.getPlayerData(uuid);
            return data == null ? "0.00" : String.format("%.2f", data.getScore());
        }

        // ---------- 排名 ----------
        if (identifier.equals("rank")) {
            int rank = dm.getPlayerRank(uuid);
            return rank == -1 ? "无排名" : String.valueOf(rank);
        }

        // ---------- Give 记录统计 ----------
        if (identifier.equals("give_total")) {
            return String.format("%.2f", dm.getGiveTotal(uuid));
        }
        if (identifier.equals("give_count")) {
            return String.valueOf(dm.getGiveCount(uuid));
        }
        if (identifier.equals("give_last_score")) {
            ScoreDataManager.GiveRecord last = dm.getLastGiveRecord(uuid);
            return last == null ? "0" : String.format("%.2f", last.amount);
        }
        if (identifier.equals("give_last_reason")) {
            ScoreDataManager.GiveRecord last = dm.getLastGiveRecord(uuid);
            return last == null ? "无" : last.reason;
        }
        if (identifier.equals("give_last_operator")) {
            ScoreDataManager.GiveRecord last = dm.getLastGiveRecord(uuid);
            return last == null ? "无" : last.operator;
        }
        if (identifier.equals("give_last_time")) {
            ScoreDataManager.GiveRecord last = dm.getLastGiveRecord(uuid);
            return last == null ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(last.timestamp));
        }

        // ---------- 指定 ID 的 Give 记录 ----------
        // 格式: %srank_give_<id>_score%, %srank_give_<id>_reason%, %srank_give_<id>_operator%, %srank_give_<id>_time%
        if (identifier.startsWith("give_") && identifier.endsWith("_score")) {
            try {
                int id = Integer.parseInt(identifier.substring(5, identifier.length() - 6));
                ScoreDataManager.GiveRecord r = dm.getGiveRecord(uuid, id);
                return r == null ? "0.00" : String.format("%.2f", r.amount);
            } catch (NumberFormatException e) { return "0.00"; }
        }
        if (identifier.startsWith("give_") && identifier.endsWith("_reason")) {
            try {
                int id = Integer.parseInt(identifier.substring(5, identifier.length() - 7));
                ScoreDataManager.GiveRecord r = dm.getGiveRecord(uuid, id);
                return r == null ? "无" : r.reason;
            } catch (NumberFormatException e) { return "无"; }
        }
        if (identifier.startsWith("give_") && identifier.endsWith("_operator")) {
            try {
                int id = Integer.parseInt(identifier.substring(5, identifier.length() - 9));
                ScoreDataManager.GiveRecord r = dm.getGiveRecord(uuid, id);
                return r == null ? "无" : r.operator;
            } catch (NumberFormatException e) { return "无"; }
        }
        if (identifier.startsWith("give_") && identifier.endsWith("_time")) {
            try {
                int id = Integer.parseInt(identifier.substring(5, identifier.length() - 5));
                ScoreDataManager.GiveRecord r = dm.getGiveRecord(uuid, id);
                return r == null ? "无" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(r.timestamp));
            } catch (NumberFormatException e) { return "无"; }
        }

        // ---------- 阈值进度 ----------
        if (identifier.equals("threshold_next_score")) {
            double next = dm.getNextThresholdScore(uuid);
            return next < 0 ? "已满" : String.format("%.2f", next);
        }
        if (identifier.equals("threshold_current_score")) {
            double cur = dm.getCurrentThresholdScore(uuid);
            return cur < 0 ? "无" : String.format("%.2f", cur);
        }
        if (identifier.equals("threshold_progress")) {
            return String.format("%.1f", dm.getThresholdProgress(uuid));
        }
        if (identifier.equals("threshold_remaining")) {
            double next = dm.getNextThresholdScore(uuid);
            if (next < 0) return "0.00";
            ScoreDataManager.PlayerData data = dm.getPlayerData(uuid);
            if (data == null) return "0.00";
            return String.format("%.2f", next - data.getScore());
        }

        // ---------- 成就统计 ----------
        if (identifier.equals("advancements_count")) {
            return String.valueOf(dm.getAdvancementCount(uuid));
        }
        if (identifier.equals("advancements_score")) {
            return String.format("%.2f", dm.getAdvancementScore(uuid));
        }

        // ---------- 单个成就分值/完成状态 ----------
        // 格式: %srank_advancement_point_<id>%, %srank_advancement_completed_<id>%
        if (identifier.startsWith("advancement_point_")) {
            String advId = dm.resolveAdvancementId(identifier.substring("advancement_point_".length()));
            double point = dm.getAdvancementPoint(advId);
            return String.format("%.2f", point);
        }
        if (identifier.startsWith("advancement_completed_")) {
            String advId = dm.resolveAdvancementId(identifier.substring("advancement_completed_".length()));
            boolean completed = dm.isAdvancementCompleted(uuid, advId);
            return String.valueOf(completed);
        }

        // ---------- 特殊方块 ----------
        // 格式: %srank_special_block_<material>_value%, %srank_special_block_<material>_score%
        if (identifier.startsWith("special_block_") && identifier.endsWith("_value")) {
            String matName = identifier.substring(14, identifier.length() - 6);
            Material mat = Material.getMaterial(matName.toUpperCase());
            if (mat == null) return "0";
            return String.valueOf(dm.getSpecialBlockValue(uuid, mat));
        }
        if (identifier.startsWith("special_block_") && identifier.endsWith("_score")) {
            String matName = identifier.substring(14, identifier.length() - 6);
            Material mat = Material.getMaterial(matName.toUpperCase());
            if (mat == null) return "0.00";
            return String.format("%.2f", dm.getSpecialBlockScore(uuid, mat));
        }

        // ---------- 特殊生物 ----------
        // 格式: %srank_special_mob_<entity>_value%, %srank_special_mob_<entity>_score%
        if (identifier.startsWith("special_mob_") && identifier.endsWith("_value")) {
            String typeName = identifier.substring(12, identifier.length() - 6);
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase());
                return String.valueOf(dm.getSpecialMobValue(uuid, type));
            } catch (IllegalArgumentException e) { return "0"; }
        }
        if (identifier.startsWith("special_mob_") && identifier.endsWith("_score")) {
            String typeName = identifier.substring(12, identifier.length() - 6);
            try {
                EntityType type = EntityType.valueOf(typeName.toUpperCase());
                return String.format("%.2f", dm.getSpecialMobScore(uuid, type));
            } catch (IllegalArgumentException e) { return "0.00"; }
        }

        // ---------- 单项统计原始数值和折扣后分值 ----------
        // 格式: %srank_stat_<stat>_value%, %srank_stat_<stat>_score%
        if (identifier.startsWith("stat_") && identifier.endsWith("_value")) {
            String stat = identifier.substring(5, identifier.length() - 6);
            return String.valueOf(dm.getStatValue(uuid, stat));
        }
        if (identifier.startsWith("stat_") && identifier.endsWith("_score")) {
            String stat = identifier.substring(5, identifier.length() - 6);
            return String.format("%.2f", dm.getStatScore(uuid, stat));
        }

        // ---------- 排行榜 ----------
        // 格式: %srank_top<1-10>_name%, %srank_top<1-10>_score%
        if (identifier.startsWith("top") && identifier.endsWith("_name")) {
            try {
                int rank = Integer.parseInt(identifier.substring(3, identifier.length() - 5));
                return dm.getTopPlayerName(rank);
            } catch (NumberFormatException e) { return ""; }
        }
        if (identifier.startsWith("top") && identifier.endsWith("_score")) {
            try {
                int rank = Integer.parseInt(identifier.substring(3, identifier.length() - 6));
                return String.format("%.2f", dm.getTopPlayerScore(rank));
            } catch (NumberFormatException e) { return "0.00"; }
        }

        return null;
    }
}