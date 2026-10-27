# SRExpansion

Paper 插件：读取玩家原版统计数据，计算「生存段位分」，并通过 PlaceholderAPI / 命令对外提供，
供段位插件作为升级依据。

- 目标版本：**Paper 1.21.11**（`paper-api 1.21.11-R0.1-SNAPSHOT`，Java 21）
- 软依赖：PlaceholderAPI（未安装时插件照常运行，只是没有变量）
- 数据文件：`plugins/SRExpansion/data.yml`

## 构建

```powershell
$env:JAVA_HOME = 'D:\jdk_collection\jdk-21.0.3+9'
& 'D:\IntelliJ IDEA 2026.2.2\plugins\maven-plugin\libs\maven3\bin\mvn.cmd' -o -B package
# 产物：target\SRExpansion-<version>.jar
```

没有 Maven 时也可以直接用 JDK 编译（依赖取 `..\webersrpgitem\libs` 里的 paper-api 与 adventure）：

```powershell
javac --release 21 -encoding UTF-8 -d out -cp "..\webersrpgitem\libs\*" src\main\java\org\example\com\sRExpansion\*.java
jar --create --file dist\SRExpansion.jar -C out .
```

## 计分模型

```
分数 = Σ 折扣后统计分 + 进度分 + 管理员赠与分 − 累计死亡扣除
```

- **统计分**：`数量 × 权重(base.yml)`，特殊方块/生物再叠加额外分，然后按 `config.yml` 的
  `diminishing_returns` 分段打折。
- **进度分**：`advancements.yml` 里每个进度一个分值，不参与打折。
- **赠与分**：`/srank give` 写入 `data.yml` 的 `give_records`，与统计分分开记账，`recalc` 不会抹掉。
- **死亡扣除**：每次死亡扣 `min(当前分 × percent, max)`，累计值记在 `total_penalty`。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/srank query [玩家]` | `srank.user` | 查段位分 |
| `/srank list [玩家] [base\|blocks\|mobs\|deaths\|achievements]` | `srank.user` | 查分项明细（支持离线） |
| `/srank leaderboard [页]` | `srank.user` | 排行榜 |
| `/srank give <玩家> <分数> [原因]` | `srank.admin` | 赠与分数（可 `undo` / `edit` / `list`） |
| `/srank recalc` | `srank.admin` | 重算在线玩家（保留触发记录与赠与分） |
| `/srank reload` | `srank.admin` | 重载配置 |

## 配置文件

| 文件 | 说明 |
|---|---|
| `config.yml` | 死亡惩罚、PVP 冷却、分段递减、100 条阈值命令 |
| `base.yml` | 13 项统计的权重 |
| `blocks.yml` | 特殊方块额外分（`log` / `wood` 按后缀生效，其余按材料名） |
| `mobs.yml` | 特殊生物额外分 |
| `advancements.yml` | 每个进度的分值 |

> 插件启动时会把 jar 里**新增的键**补进服务器已有配置，已有值不会被覆盖；因此升级插件后
> 新方块/新生物会自动生效，不需要手改文件。

## PlaceholderAPI 变量

`%srank_score%`、`%srank_rank%`、`%srank_give_total%`、`%srank_give_last_score%`、
`%srank_threshold_next_score%`、`%srank_threshold_progress%`（0–100）、
`%srank_advancements_count%`、`%srank_advancement_completed_story_mine_stone%`（第一个下划线是分类分隔符）、
`%srank_stat_block_break_value%`、`%srank_special_mob_breeze_score%`、`%srank_top1_name%` 等。

## 目录

```
src/main/java/org/example/com/sRExpansion/
  SRExpansion.java        插件入口
  ScoreDataManager.java   计分核心（缓存、折扣、惩罚、赠与、持久化）
  StatisticsListener.java 统计事件 / 死亡 / 进出服
  BlockEventListener.java 反刷矿（记住玩家放置的特殊方块）
  AdvancementListener.java 进度完成
  SRCommand.java          /srank
  SRPlaceholder.java      PAPI 变量
  Threshold.java          阈值命令值对象
```

更多背景见 [HANDOVER.md](HANDOVER.md)。
