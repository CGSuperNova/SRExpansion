# SRExpansion

Paper 插件：完整的**生存段位系统**。读取玩家原版统计数据算出「生存段位分」，
再按分数自动/手动晋升段位（101 个段位），并通过 PlaceholderAPI 对外提供段位名、分数、进度等变量。

- 目标版本：**Paper 1.21.11**（`paper-api 1.21.11-R0.1-SNAPSHOT`，Java 21）
- 软依赖：PlaceholderAPI（变量）、Vault（金币奖励；没有 Vault 时退回配置里的发钱命令）
- 数据文件：`plugins/SRExpansion/data.yml`（分数 / 段位 / 自动升级开关 / 赠与记录）

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
- **赠与分**：`/srank score give` 写入 `data.yml` 的 `give_records`，与统计分分开记账，`recalc` 不会抹掉。
- **死亡扣除**：每次死亡扣 `min(当前分 × percent, max)`，累计值记在 `total_penalty`。

## 段位系统

- 段位定义在 `ranks.yml`，共 101 个（`Newbie` + `Dirt` … `Extra_Minecraft_max`），按 `score` 从小到大排列。
- 玩家分数达到下一段位的 `score` 即自动晋升（可在 `ranks.yml` 里用 `auto: false` 让某段位必须手动升）。
- 晋升时按 `settings.messages` 广播 / 标题 / 聊天提示，并按 `money` 发放金币（Vault 优先，其次执行
  `settings.money.command`）。
- 玩家可用 `/srank autorankup` 自行关闭自动升级，之后靠 `/srank rankup` 手动升。
- **段位权限**：晋升时按 `settings.permission` 里的节点模板（默认 `rank.%rank%.achieved`）通过
  LuckPerms 命令授予玩家「已达到的所有段位」的权限，其他插件可以直接拿这些节点做判断。
  已授予的节点记录在 `data.yml` 的 `granted_permissions`，不会重复执行。
- **升级悬浮字**：`settings.display` 控制一段三幕演出 —— 在玩家左侧 90°、3 格处生成悬浮字，
  快速转到左前方 30° 停留约 1.5 秒再转回去，依次展示「段位提升」「获得奖励（含本次获得的属性）」
  「下一段位」，全程跟随玩家视角（`move-interval-ticks` 控制刷新间隔，默认每 tick 跟随），且只对本人可见。
  段位还可以用 `extra:` 追加第 4 幕内容（例如最高段位的「恭喜达到最高段位！」），玩家可用
  `/srank display` 自行关闭这段演出。
- **段位属性奖励**：装了一同附带的 [AdvancedPlayerAttribute](../AdvancedPlayerAttribute) 插件后，
  可以在 `ranks.yml` 里给段位配 `attributes:`
  （`max_health` / `attack` / `defense` / `shield` / `dodge` / `armor_pierce` / `absolute`）。
  加成按「该段位及之前所有段位」累加，登录、升级、`rankset`、重载时都会重算一次；
  两个插件通过反射连接，没有编译依赖，没装属性插件时这项功能自动跳过。
- 段位名支持 `&a` 传统颜色码、`{#RRGGBB}` 单色、`{#RRGGBB>}文字{#RRGGBB<}` 渐变，
  以及多色标 `{#RRGGBB<>}`（解析器见 `TextFormatter`）。

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/srank` | — | 帮助 |
| `/srank info [玩家]` | `srank.user` | 段位、分数、升级进度 |
| `/srank list [页]` | `srank.user` | 全部段位列表 |
| `/srank rankup` | `srank.user` | 手动升级 |
| `/srank autorankup [true\|false]` | `srank.user` | 开关自动升级（不带参数则取反） |
| `/srank display [true\|false]` | `srank.user` | 开关升级时的悬浮字演出（不带参数则取反） |
| `/srank score` | `srank.user` | 查看自己的段位分 |
| `/srank score query [玩家]` | `srank.user` | 查段位分 |
| `/srank score list [玩家] [base\|blocks\|mobs\|deaths\|achievements]` | `srank.user` | 分项明细（支持离线） |
| `/srank score top [页]` | `srank.user` | 排行榜 |
| `/srank score recalc` | `srank.admin` | 重算在线玩家 |
| `/srank score give <玩家> <分数> [原因]` | `srank.admin` | 赠与分数（`undo` / `edit` / `list`） |
| `/srank rankset <玩家> <段位>` | `srank.admin` | 强制设置段位 |
| `/srank reload` | `srank.admin` | 重载全部配置 |

## 配置文件

| 文件 | 说明 |
|---|---|
| `ranks.yml` | 段位定义、晋升奖励、提示消息、经济设置 |
| `config.yml` | 死亡惩罚、PVP 冷却、分段递减、通用阈值命令 |
| `base.yml` | 13 项统计的权重 |
| `blocks.yml` | 特殊方块额外分（`log` / `wood` 按后缀生效，其余按材料名） |
| `mobs.yml` | 特殊生物额外分 |
| `advancements.yml` | 每个进度的分值 |

> 插件启动时会把 jar 里**新增的键**补进服务器已有配置，已有值不会被覆盖；因此升级插件后
> 新方块/新生物/新段位会自动生效，不需要手改文件。

## 常用变量

`%srank_rank_name%`（段位名，带颜色）、`%srank_rank_plain%`、`%srank_next_rank_name%`、
`%srank_rank_remaining%`、`%srank_rank_progress%`、`%srank_score%`、`%srank_rank%`（段位分排名）、
`%srank_autorankup%`、`%srank_display%` 等。

查**指定玩家**（支持离线）：`%srank_rank_name_<玩家名>%`、`%srank_rank_name_plain_<玩家名>%`、
`%srank_score_<玩家名>%`，例如 `%srank_rank_name_Steve%`。完整列表见 [wiki.txt](wiki.txt)。

## 目录

```
src/main/java/org/example/com/sRExpansion/
  SRExpansion.java        插件入口
  ScoreDataManager.java   计分核心（缓存、折扣、惩罚、赠与、持久化）
  RankManager.java        段位核心（晋升、奖励、提示）
  Rank.java               段位定义
  TextFormatter.java      & 颜色码 + 十六进制/渐变解析
  ConfigFiles.java        配置释放与新增键合并
  StatisticsListener.java 统计事件 / 死亡 / 进出服
  BlockEventListener.java 反刷矿（记住玩家放置的特殊方块）
  AdvancementListener.java 进度完成
  SRCommand.java          /srank
  SRPlaceholder.java      PAPI 变量
  Threshold.java          阈值命令值对象
```
