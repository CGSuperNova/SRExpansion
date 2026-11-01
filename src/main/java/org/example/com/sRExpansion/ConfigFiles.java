package org.example.com.sRExpansion;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** 配置文件工具：释放内置默认文件，并把新增的键补进服务器上已有的文件。 */
final class ConfigFiles {

    private ConfigFiles() {}

    /**
     * 取数据目录下的配置文件：不存在就从 jar 里释放一份；已存在则补全缺失项。
     *
     * @param oneLevelSections 这些段只做“整条新增”，不会修正段内已存在条目的缺失键。
     *                         用于 ranks / specific_block_scores 这类“管理员可以整条删除”的列表，
     *                         否则删掉的字段（比如某个段位的 auto）会在下次加载时被默认值补回来。
     */
    static File ensure(JavaPlugin plugin, String fileName, String... oneLevelSections) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
            return file;
        }
        mergeMissing(plugin, fileName, file, oneLevelSections);
        return file;
    }

    static void mergeMissing(JavaPlugin plugin, String fileName, File file, String... oneLevelSections) {
        if (!file.exists()) return;
        InputStream in = plugin.getResource(fileName);
        if (in == null) return;
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("读取内置 " + fileName + " 失败: " + e.getMessage());
            return;
        }
        YamlConfiguration live = YamlConfiguration.loadConfiguration(file);
        if (!copyMissingKeys(live, defaults, Set.of(oneLevelSections))) return;
        try {
            live.save(file);
            plugin.getLogger().info("已为 " + fileName + " 自动补齐新增配置项");
        } catch (IOException e) {
            plugin.getLogger().warning("写回 " + fileName + " 失败: " + e.getMessage());
        }
    }

    /** 递归把 defaults 里 live 缺失的键补进 live；已存在的键（包括列表）一律不覆盖。 */
    static boolean copyMissingKeys(ConfigurationSection live, ConfigurationSection defaults, Set<String> oneLevel) {
        boolean changed = false;
        for (String key : defaults.getKeys(false)) {
            ConfigurationSection defaultSection = defaults.getConfigurationSection(key);

            if (defaultSection != null && oneLevel.contains(key)) {
                ConfigurationSection liveSection = live.getConfigurationSection(key);
                if (liveSection == null) {
                    if (live.contains(key)) continue; // 用户写成了别的类型，保持原样
                    liveSection = live.createSection(key);
                    changed |= copyMissingKeys(liveSection, defaultSection, Set.of());
                    continue;
                }
                // 已存在的条目整条保留，只补全新的条目
                for (String child : defaultSection.getKeys(false)) {
                    if (liveSection.contains(child)) continue;
                    ConfigurationSection childSection = defaultSection.getConfigurationSection(child);
                    if (childSection != null) {
                        ConfigurationSection created = liveSection.createSection(child);
                        copyMissingKeys(created, childSection, Set.of());
                    } else {
                        liveSection.set(child, defaultSection.get(child));
                    }
                    changed = true;
                }
                continue;
            }

            if (defaultSection != null) {
                ConfigurationSection liveSection = live.getConfigurationSection(key);
                if (liveSection == null) {
                    if (live.contains(key)) continue; // 用户写成了别的类型，保持原样
                    liveSection = live.createSection(key);
                }
                changed |= copyMissingKeys(liveSection, defaultSection, oneLevel);
            } else if (!live.contains(key)) {
                live.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }
}
