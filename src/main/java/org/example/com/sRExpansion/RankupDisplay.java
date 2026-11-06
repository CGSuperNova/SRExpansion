package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Display;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 段位升级时的悬浮字演出。
 *
 * 流程（每个阶段）：
 * <ol>
 *   <li>在玩家左侧 90°、距离 3 格处出现；</li>
 *   <li>快速转到左侧 30°（正对视野边缘，方便阅读）；</li>
 *   <li>停留 1~2 秒；</li>
 *   <li>转回 90°；</li>
 *   <li>切换下一阶段文本，重复。</li>
 * </ol>
 * 每一 tick 都按玩家当前朝向重新计算位置，所以玩家转视角时悬浮字会跟着保持在“左侧”。
 * 悬浮字只对触发的玩家可见（{@code setVisibleByDefault(false)} + {@code showEntity}）。
 */
public class RankupDisplay {

    private final SRExpansion plugin;
    private final Map<UUID, Animation> active = new HashMap<>();

    public RankupDisplay(SRExpansion plugin) {
        this.plugin = plugin;
    }

    public boolean isPlaying(UUID uuid) {
        return active.containsKey(uuid);
    }

    /** 播放一段升级演出；重复调用会先取消上一次。 */
    public void play(Player player, Rank rank, double money, Map<String, Double> gainedAttributes) {
        RankManager rankManager = plugin.getRankManager();
        if (rankManager == null || !rankManager.isDisplayEnabled() || rank == null) return;
        ScoreDataManager.PlayerData data = plugin.getDataManager() == null ? null
                : plugin.getDataManager().getPlayerData(player.getUniqueId());
        // 玩家自己关掉了悬浮字演出
        if (data != null && !data.isDisplayEnabled()) return;
        cancel(player.getUniqueId());

        Location spawn = location(player, rankManager.getDisplayStartAngle());
        TextDisplay display;
        try {
            display = player.getWorld().spawn(spawn, TextDisplay.class, entity -> configure(entity, rankManager, player));
        } catch (Throwable t) {
            plugin.getLogger().warning("生成段位升级悬浮字失败: " + t.getMessage());
            return;
        }
        player.showEntity(plugin, display);

        Animation animation = new Animation(player.getUniqueId(), display, rank, money, gainedAttributes);
        active.put(player.getUniqueId(), animation);
        animation.start();
    }

    public void cancel(UUID uuid) {
        Animation animation = active.get(uuid);
        if (animation != null) animation.finish();
    }

    public void shutdown() {
        for (Animation animation : List.copyOf(active.values())) animation.finish();
        active.clear();
    }

    private void configure(TextDisplay display, RankManager rankManager, Player player) {
        display.setBillboard(Display.Billboard.CENTER);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setSeeThrough(rankManager.isDisplaySeeThrough());
        display.setShadowed(true);
        display.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
        display.setLineWidth(240);
        display.setViewRange(1.5f);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.setVisibleByDefault(false);
        display.setTransformation(new Transformation(
                new Vector3f(0f, 0f, 0f),
                new AxisAngle4f(),
                new Vector3f(1f, 1f, 1f),
                new AxisAngle4f()));
        display.text(Component.empty());
    }

    /** 计算玩家“左侧 angleDegrees 度、距离 distance 格”的位置。 */
    private Location location(Player player, double angleDegrees) {
        RankManager rankManager = plugin.getRankManager();
        Location base = player.getLocation();
        double yaw = base.getYaw() - angleDegrees;              // 视野左侧为正
        double radians = Math.toRadians(yaw);
        double x = base.getX() - Math.sin(radians) * rankManager.getDisplayDistance();
        double z = base.getZ() + Math.cos(radians) * rankManager.getDisplayDistance();
        double y = base.getY() + player.getEyeHeight() + rankManager.getDisplayHeight();
        return new Location(base.getWorld(), x, y, z, (float) yaw, 0f);
    }

    private static double ease(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        // ease-out：起步快、收尾稳，看起来更像“快速转过来”
        return 1 - Math.pow(1 - clamped, 3);
    }

    private final class Animation implements Runnable {

        private static final int PHASE_IN = 0;
        private static final int PHASE_HOLD = 1;
        private static final int PHASE_OUT = 2;
        private static final int PHASE_GAP = 3;

        private final UUID uuid;
        private final TextDisplay display;
        private final Rank rank;
        private final double money;
        private final Map<String, Double> gainedAttributes;

        private BukkitTask task;
        private int stage = 0;
        private int phase = PHASE_IN;
        private int tick = 0;
        private int moveTick = 0;
        private boolean done = false;

        Animation(UUID uuid, TextDisplay display, Rank rank, double money, Map<String, Double> gainedAttributes) {
            this.uuid = uuid;
            this.display = display;
            this.rank = rank;
            this.money = money;
            this.gainedAttributes = gainedAttributes == null ? Map.of() : gainedAttributes;
        }

        void start() {
            applyText();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 0L, 1L);
        }

        @Override
        public void run() {
            if (done) return;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline() || !display.isValid()
                    || !display.getWorld().equals(player.getWorld())) {
                finish();
                return;
            }

            RankManager rankManager = plugin.getRankManager();
            int rotateTicks = rankManager.getDisplayRotateTicks();
            int holdTicks = rankManager.getDisplayHoldTicks();
            int gapTicks = rankManager.getDisplayGapTicks();
            double startAngle = rankManager.getDisplayStartAngle();
            double readAngle = rankManager.getDisplayReadAngle();

            double angle;
            switch (phase) {
                case PHASE_IN -> {
                    angle = lerp(startAngle, readAngle, ease((tick + 1) / (double) rotateTicks));
                    if (++tick >= rotateTicks) { phase = PHASE_HOLD; tick = 0; }
                }
                case PHASE_HOLD -> {
                    angle = readAngle;
                    if (++tick >= holdTicks) { phase = PHASE_OUT; tick = 0; }
                }
                case PHASE_OUT -> {
                    angle = lerp(readAngle, startAngle, ease((tick + 1) / (double) rotateTicks));
                    if (++tick >= rotateTicks) { phase = PHASE_GAP; tick = 0; }
                }
                default -> {
                    angle = startAngle;
                    if (++tick >= gapTicks) {
                        tick = 0;
                        phase = PHASE_IN;
                        stage++;
                        if (stage >= rankManager.getDisplayStageCount(rank)) {
                            finish();
                            return;
                        }
                        applyText();
                    }
                }
            }
            // 位置跟随的刷新频率可配；默认每 tick 都跟随，玩家跑动/转视角时不会掉队
            if (++moveTick >= Math.max(1, rankManager.getDisplayMoveIntervalTicks())) {
                moveTick = 0;
                display.teleport(location(player, angle));
            }
        }

        private void applyText() {
            RankManager rankManager = plugin.getRankManager();
            List<String> lines = rankManager.getDisplayStage(rank, stage);
            if (lines.isEmpty()) return;

            Player player = Bukkit.getPlayer(uuid);
            ScoreDataManager.PlayerData data = player == null ? null
                    : plugin.getDataManager().getPlayerData(uuid);
            Rank next = rankManager.next(rank);

            Map<String, String> vars = new HashMap<>();
            vars.put("%player%", player == null ? "" : player.getName());
            vars.put("%rank%", rank.getDisplay());
            vars.put("%rank_plain%", rank.getDisplayPlain());
            vars.put("%money%", RankManager.formatMoney(money));
            vars.put("%next_rank%", next == null ? "已满级" : next.getDisplay());
            vars.put("%next_score%", next == null ? "-" : RankManager.formatMoney(next.getScore()));
            vars.put("%next_rank_plain%", next == null ? "已满级" : next.getDisplayPlain());
            vars.put("%remaining%", next == null || data == null
                    ? "0" : RankManager.formatMoney(Math.max(0, next.getScore() - data.getScore())));
            // 本次升级获得的属性（连升多段时是各段之和）
            vars.put("%attributes%", RankManager.describeAttributes(gainedAttributes));
            // 当前段位自身提供的属性
            vars.put("%rank_attributes%", RankManager.describeAttributes(rank.getAttributes()));

            Component text = Component.empty();
            boolean first = true;
            for (String line : lines) {
                String replaced = line;
                for (Map.Entry<String, String> entry : vars.entrySet()) {
                    replaced = replaced.replace(entry.getKey(), entry.getValue());
                }
                // 占位符展开后可能是多行（例如 %attributes%），逐行拼接
                for (String part : replaced.split("\n", -1)) {
                    if (part.isEmpty()) continue;
                    if (!first) text = text.append(Component.newline());
                    first = false;
                    text = text.append(TextFormatter.parse(part));
                }
            }
            display.text(text);
        }

        void finish() {
            if (done) return;
            done = true;
            if (task != null) task.cancel();
            active.remove(uuid);
            if (display.isValid()) display.remove();
        }
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
