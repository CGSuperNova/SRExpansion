# Changelog

本文件记录 SRExpansion 的重要变更。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.10.0] - 2026-11-03

### Added

- 段位属性奖励：`ranks.yml` 里每个段位可以配置 `attributes:`（`max_health`、`attack`、`defense`、
  `shield`、`dodge` 等），升级后会把「该段位及之前所有段位」的加成之和同步给
  **AdvancedPlayerAttribute** 插件；登录、升级、`rankset`、重载时都会重算（幂等，不会叠加）。
- 与 AdvancedPlayerAttribute 的连接层 `AttributeBridge`：通过反射调用对方 API，两个插件没有编译依赖；
  对方未安装时全部空操作，并在启动日志里提示是否连接成功。
- `plugin.yml` 增加 `softdepend: AdvancedPlayerAttribute`，保证加载顺序。
- 默认段位属性阶梯：Dirt / Coal / Iron / Gold / Diamond / Netherite / Wither / Beacon / Ender_Dragon /
  Minecraft / Minecraft_Max 以及 5 个 Extra 段位共 16 个里程碑提供属性，满段位累计
  血量上限 +32、防御 +13、攻击 +18、护盾 +15、闪避 +7%。

### Changed

- 版本号升到 1.10.0；构建改为 Maven 打包（bStats 通过 shade 插件重定位进 jar）。

## [1.9.1] - 2026-11-02

### Added

- 段位权限：晋升或 `/srank rankset` 时，按 `settings.permission.nodes`（默认 `rank.%rank%.achieved`）
  通过配置的命令授予玩家「已达到的所有段位」的权限节点，供其他插件判断段位。
  已授予节点记录在 `data.yml` 的 `granted_permissions`，不会重复执行；老玩家迁移只记账不发命令。
- 升级悬浮字演出（`RankupDisplay`）：在玩家左侧 90°、3 格处生成只对本人可见的 TextDisplay，
  快速转到左前方 30° 停留约 1.5 秒再转回，依次播放「段位提升 / 获得奖励 / 下一段位」三幕，
  每 tick 按玩家当前朝向重算位置，转视角时始终保持在左侧。角度、距离、时长、文本见 `settings.display`。

### Fixed

- 配置合并会把管理员删掉的字段补回来（例如 `Command_block` 的 `auto: false`）。
  `ranks`、`specific_block_scores`、`specific_mob_scores`、`advancement_points` 现在只「整条补新增」，
  不再修正已有条目内部的缺失键。

## [1.9.0] - 2026-10-31

### Added

- 内置完整段位系统：`ranks.yml` 收录 101 个段位（`Newbie` + 100 个计分段位），
  含显示名、所需分数、金币奖励与 `auto: false` 标记。
- 十六进制颜色解析（`TextFormatter`）：`&` 颜色码、`{#RRGGBB}` 单色、
  `{#RRGGBB>}…{#RRGGBB<}` 渐变，以及多色标 `{#RRGGBB<>}`。
- 段位命令：`/srank list`、`/srank info`、`/srank rankup`、`/srank rankset`、`/srank autorankup`。
- 段位 PAPI 变量：`%srank_rank_name%`、`%srank_rank_plain%`、`%srank_rank_id%`、`%srank_rank_index%`、
  `%srank_next_rank_name%`、`%srank_next_rank_score%`、`%srank_rank_remaining%`、
  `%srank_rank_progress%`、`%srank_autorankup%` 等。
- 晋升奖励：Vault 经济优先，其次执行 `settings.money.command`；支持广播 / 标题 / 聊天 / 音效提示。

### Changed

- 生存段位分相关命令统一收进 `/srank score ...`（`query`、`list`、`top`、`recalc`、`give`）。
- 玩家可自行开关自动升级，开关状态保存在 `data.yml`。
- `config.yml` 移除 100 条 `rank.*.achieved` 的 LuckPerms 阈值（改由 `ranks.yml` 管理段位），
  `thresholds` 保留为空列表的通用机制。

### Removed

- 旧命令 `/srank query|give|recalc|leaderboard`（迁移到 `/srank score` 下，仍会提示新位置）。

## [1.8.1] - 2026-10-27

### Changed

- 内置默认值与服务器实际配置对齐：`blowback` 改为 40，`HAPPY_GHAST` 0.01、`COPPER_GOLEM` 0.02、
  `NAUTILUS` 0.15、`ZOMBIE_NAUTILUS` 0.15。

### Documentation

- 更新 Wiki 页面源码：新增 1.21 方块 / 生物 / 成就条目，修正死亡惩罚与总分公式说明，
  新增「查询方式与计分板变量」一节。

## [1.8.0] - 2026-10-27

### Added

- 1.20.5 ~ 1.21.11 新增的 14 个成就及其分值。
- 9 种 1.21 生物计分：犰狳、沼骸、旋风人、快乐恶魂、铜傀儡、干尸、骆驼尸壳、鹦鹉螺、僵尸鹦鹉螺。
- 1.21.6 / 1.21.11 新增的 `HAPPY_GHAST_ONE_CM`、`NAUTILUS_ONE_CM` 并入移动距离计分。
- 补齐 27 条 1.20.4 之前漏配的成就，`advancements.yml` 现已覆盖全部 125 个原版成就。
- `README.md`，并初始化 Git 仓库。

### Fixed

- 删除 6 条原版不存在的「假进度」，其中 `ride_a_strider`、`play_jukebox_in_meadows` 改为 1.21 的真实归属。
- 段位分口径：PVP 击杀改用插件自己的计数重算，`recalc` 不再绕过冷却；
  `USE_ITEM` 只把放置方块计入 `block_place`；阈值命令检查覆盖进度、PVP 与 `give`；
  反刷矿的放置记忆由 30 秒放宽到 10 分钟；死亡惩罚不再按历史死亡次数回溯扣分；
  补齐 `srank.user` 权限校验。

## [1.7.0] - 2026-10-26

### Added

- 补齐 `ScoreDataManager` 缺失的 API：赠与记录（`GiveRecord`、`addGiveRecord` 等）、阈值进度、
  玩家排名、特殊方块 / 生物数值、`savePlayerDataIfDirty`、`recalcAllPlayers`。
- `/srank give undo|edit|list`，以及离线玩家数据读取。
- 配置文件自动补全：启动时把 jar 内新增的键合并进服务器已有配置，已有值不覆盖。
- `blocks.yml` 改为通用解析：`log` / `wood` 按后缀生效，其余按材料名解析，矿石自动兼容深板岩变体。

### Changed

- 目标版本升级到 **Paper 1.21.11**（Java 21）。
- 玩家进服初始化从异步线程改为主线程。
- 数据落盘改为「脏标记 + 每分钟批量 + 退服/关服刷盘」，写盘走异步。
- `/srank recalc`、`/srank reload` 保留阈值触发记录与管理员赠与分。
- 死亡惩罚改为「分数 = 统计重算值 + 赠与分 − 累计扣除」。

### Fixed

- 1.21 下 `getStatistic(Statistic.USE_ITEM, Material)` 对「是方块但没有物品形态」的材料抛异常，
  导致初始化中断、所有玩家分数为 0、`/srank list` 无数据。
- 异步线程访问原版统计与进度造成的不稳定。
- `playerDataCache` 从不清理导致的缓存泄漏。
- 增量计分与全量重算对「玩家自己放置的方块」口径不一致（反刷矿失效）。

## [1.7.0] 之前

项目早期（1.0 ~ 1.6）没有留下可靠的变更记录，此 CHANGELOG 从 1.7.0 开始记录。

> 注：仓库目前还没有配置远程地址，因此上面的版本号暂未链接到 compare / release 页面。
> 配置好远程仓库后，把 `[Unreleased]` 与各版本标题改成参考链接即可。

