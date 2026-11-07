package org.example.com.sRExpansion;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * 与 AdvancedPlayerAttribute 的连接桥。
 *
 * 两个插件互相独立，这里用反射调用对方的公开 API，避免编译期依赖：
 * <ul>
 *   <li>{@code setAttributeBonus(Player, String source, String attribute, double value)}</li>
 *   <li>{@code clearAttributeBonus(Player, String source)}</li>
 * </ul>
 * 对方没有安装时所有调用都是空操作。
 */
public class AttributeBridge {

    public static final String PLUGIN_NAME = "AdvancedPlayerAttribute";
    public static final String SOURCE = "srank";

    private Method setBonusMethod;
    private Method clearMethod;
    private Method setPointsMethod;
    private Object api;

    public void init() {
        api = null;
        setBonusMethod = null;
        clearMethod = null;
        setPointsMethod = null;
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null) return;
        try {
            setBonusMethod = plugin.getClass().getMethod("setAttributeBonus",
                    Player.class, String.class, String.class, double.class);
            clearMethod = plugin.getClass().getMethod("clearAttributeBonus", Player.class, String.class);
            try {
                // 属性点系统是 1.1.0 之后加的，旧版本没有这个方法
                setPointsMethod = plugin.getClass().getMethod("setPointGrant",
                        Player.class, String.class, double.class);
            } catch (NoSuchMethodException ignored) {
                setPointsMethod = null;
            }
            api = plugin;
        } catch (NoSuchMethodException e) {
            api = null;
            setBonusMethod = null;
            clearMethod = null;
        }
    }

    public boolean isAvailable() {
        return api != null && setBonusMethod != null;
    }

    /** 对方是否支持属性点发放。 */
    public boolean supportsPoints() {
        return api != null && setPointsMethod != null;
    }

    /** 设置某个属性由段位系统提供的加成；value 为 0 表示清除。 */
    public void set(Player player, String attribute, double value) {
        if (!isAvailable() || player == null) return;
        try {
            setBonusMethod.invoke(api, player, SOURCE, attribute, value);
        } catch (Throwable ignored) {
            // 对方插件内部异常不应该影响段位逻辑
        }
    }

    /** 设置段位系统发放的属性点总数（覆盖式）。 */
    public void setPoints(Player player, double points) {
        if (!supportsPoints() || player == null) return;
        try {
            setPointsMethod.invoke(api, player, SOURCE, points);
        } catch (Throwable ignored) {
        }
    }

    /** 清空段位系统提供的全部属性加成。 */
    public void clear(Player player) {
        if (api == null || clearMethod == null || player == null) return;
        try {
            clearMethod.invoke(api, player, SOURCE);
        } catch (Throwable ignored) {
        }
    }
}
