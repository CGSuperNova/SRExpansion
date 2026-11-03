package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一个段位定义（来自 ranks.yml）。 */
public final class Rank {

    private final int index;        // 从 1 开始的名次
    private final String id;        // 段位注册名，如 Dirt
    private final String display;   // 带颜色标记的显示名，如 {#A0522D>}[Dirt]{#F5DEB3<}
    private final double score;     // 晋升所需生存段位分
    private final double money;     // 晋升奖励金币
    private final boolean auto;     // 是否允许自动升级
    private final Map<String, Double> attributes;   // 段位提供的属性加成（AdvancedPlayerAttribute 的属性 id）

    private Component displayComponent;
    private String displayLegacy;
    private String displayPlain;

    public Rank(int index, String id, String display, double score, double money, boolean auto) {
        this(index, id, display, score, money, auto, Map.of());
    }

    public Rank(int index, String id, String display, double score, double money, boolean auto,
                Map<String, Double> attributes) {
        this.index = index;
        this.id = id;
        this.display = display;
        this.score = score;
        this.money = money;
        this.auto = auto;
        this.attributes = attributes == null ? Map.of() : new LinkedHashMap<>(attributes);
    }

    public int getIndex() { return index; }
    public String getId() { return id; }
    public String getDisplay() { return display; }
    public double getScore() { return score; }
    public double getMoney() { return money; }
    public boolean isAuto() { return auto; }

    /** 这个段位本身提供的属性加成（未累加低段位）。 */
    public Map<String, Double> getAttributes() { return Collections.unmodifiableMap(attributes); }

    /** 带颜色的显示名（Adventure 组件）。 */
    public Component getDisplayComponent() {
        if (displayComponent == null) displayComponent = TextFormatter.parse(display);
        return displayComponent;
    }

    /** 带颜色的显示名（{@code §} 字符串，用于 PlaceholderAPI / 命令）。 */
    public String getDisplayLegacy() {
        if (displayLegacy == null) displayLegacy = TextFormatter.toLegacy(display);
        return displayLegacy;
    }

    /** 去掉颜色后的纯文本显示名（用于比较、日志）。 */
    public String getDisplayPlain() {
        if (displayPlain == null) displayPlain = TextFormatter.strip(display);
        return displayPlain;
    }

    @Override
    public String toString() { return id + "(" + score + ")"; }
}
