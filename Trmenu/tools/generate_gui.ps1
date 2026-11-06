# ============================================================
# 生存段位系统 TrMenu 菜单生成器
#
# 读取：
#   src/main/resources/ranks.yml            （段位、分数、金币、属性、extra）
#   Trmenu/SRExpansion_config_in_server/*   （服务器实际计分配置）
#   wiki.txt                                （中文名表）
#   Trmenu/ai_rank.yml                      （原有菜单的材质映射，保持原生外观）
# 输出：
#   Trmenu/rank_gui.yml
#
# 用法： powershell -ExecutionPolicy Bypass -File Trmenu\tools\generate_gui.ps1
# ============================================================
param(
    [string]$Root = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
)

$ErrorActionPreference = 'Stop'
$ranksFile = Join-Path $Root 'src\main\resources\ranks.yml'
$serverCfg = Join-Path $Root 'Trmenu\SRExpansion_config_in_server'
$wikiFile  = Join-Path $Root 'wiki.txt'
$oldMenu   = Join-Path $Root 'Trmenu\ai_rank.yml'
$outFile   = Join-Path $Root 'Trmenu\rank_gui.yml'

foreach ($f in @($ranksFile, $wikiFile, $oldMenu)) {
    if (-not (Test-Path $f)) { throw "缺少输入文件: $f" }
}

# ------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------

# {#RRGGBB>} / {#RRGGBB<>} / {#RRGGBB<} / &a  ->  TrMenu 渐变写法
# 规则（客户端实测）：
#   - 颜色码用传统 & 写法，<yellow> 这类命名标签不生效
#   - <gradient:#A:#B> 后面不要 </gradient>，渐变会一直作用到下一个颜色标记
#   - 没有 <reset>，需要复位颜色就用 &r
# 例：&e[{#FFD700>}Extra_{#FF4500<}&r{#A0522D>}Dirt{#F5DEB3<}&e]
#  ->  &e[<gradient:#FFD700:#FF4500>Extra_&r<gradient:#A0522D:#F5DEB3>Dirt&e]
function ConvertTo-TrMenu([string]$text) {
    if ([string]::IsNullOrEmpty($text)) { return $text }
    $sb = New-Object System.Text.StringBuilder
    $buffer = New-Object System.Text.StringBuilder
    $prev = $null
    $inGradient = $false
    $i = 0
    $n = $text.Length

    while ($i -lt $n) {
        if ($text[$i] -eq '{' -and ($i + 7) -lt $n -and $text[$i + 1] -eq '#') {
            $close = $text.IndexOf('}', $i)
            if ($close -gt $i) {
                $inner = $text.Substring($i + 2, $close - $i - 2)
                if ($inner -match '^([0-9A-Fa-f]{6})(<>|>|<)?$') {
                    $color = '#' + $Matches[1]
                    $kind = [string]$Matches[2]
                    if ($kind -eq '>') {
                        $prev = $color
                        $inGradient = $true
                        [void]$buffer.Clear()
                    }
                    elseif ($kind -eq '<>') {
                        if ($inGradient) {
                            [void]$sb.Append('<gradient:' + $prev + ':' + $color + '>')
                            [void]$sb.Append($buffer.ToString())
                            [void]$buffer.Clear()
                            $prev = $color
                        }
                    }
                    elseif ($kind -eq '<') {
                        if ($inGradient) {
                            [void]$sb.Append('<gradient:' + $prev + ':' + $color + '>')
                            [void]$sb.Append($buffer.ToString())
                            [void]$buffer.Clear()
                            $inGradient = $false
                            $prev = $null
                        }
                    }
                    else {
                        # 单色：退化成首尾同色的渐变
                        [void]$sb.Append('<gradient:' + $color + ':' + $color + '>')
                    }
                    $i = $close + 1
                    continue
                }
            }
        }
        if ($inGradient) { [void]$buffer.Append($text[$i]) } else { [void]$sb.Append($text[$i]) }
        $i++
    }
    if ($inGradient -and $buffer.Length -gt 0) {
        [void]$sb.Append('<gradient:' + $prev + ':' + $prev + '>')
        [void]$sb.Append($buffer.ToString())
    }
    return $sb.ToString()
}

function Y([string]$s) { return "'" + ($s -replace "'", "''") + "'" }

function Fmt([double]$v) {
    if ([math]::Abs($v - [math]::Round($v)) -lt 0.0000001) { return ([long][math]::Round($v)).ToString() }
    return $v.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture)
}

function Make-Row([string[]]$units) {
    $row = ($units -join '')
    $missing = 9 - $units.Count
    if ($missing -gt 0) { $row += (' ' * $missing) }
    return $row
}

$script:out = New-Object System.Collections.Generic.List[string]
function Add([string]$line) { $script:out.Add($line) }

# content: 内容行（最多 5 行），nav: 最后一行（返回按钮），会自动补空行
function AddPage([string]$comment, [object[]]$content, [string[]]$nav) {
    Add("  # $comment")
    $rows = New-Object System.Collections.Generic.List[object]
    foreach ($r in $content) { $rows.Add($r) }
    while ($rows.Count -lt 5) { $rows.Add(@(' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ', ' ')) }
    if ($nav -ne $null) { $rows.Add($nav) }
    if ($rows.Count -ne 6) { throw ("页面【" + $comment + "】行数不是 6: " + $rows.Count) }
    $first = $true
    foreach ($units in $rows) {
        $row = Make-Row $units
        if ($first) { Add ('  - - ' + (Y $row)); $first = $false }
        else { Add ('    - ' + (Y $row)) }
    }
}

# ------------------------------------------------------------
# 1. 中文名（wiki）
# ------------------------------------------------------------
$advName = @{}; $blkName = @{}; $mobName = @{}; $baseName = @{}
# 注意：PowerShell 的 -match 默认不区分大小写，这里必须用 -cmatch，
# 否则 [A-Z_]+ 会把小写的方块/基础项也吞进去
foreach ($line in Get-Content $wikiFile -Encoding UTF8) {
    if ($line -cmatch '^\| <code>minecraft:([a-z_]+)/([a-z_0-9]+)</code> \|\| (.+?) \|\| ([0-9.]+)\s*$') {
        $advName["$($Matches[1])/$($Matches[2])"] = $Matches[3].Trim()
    }
    elseif ($line -cmatch '^\| <code>([A-Z_]+)</code> \|\| (.+?) \|\| ([0-9.]+)\s*$') {
        $mobName[$Matches[1]] = $Matches[2].Trim()
    }
    elseif ($line -cmatch '^\| <code>([a-z_]+)</code> \|\| (.+?) \|\| ([0-9.]+)\s*$') {
        $blkName[$Matches[1]] = $Matches[2].Trim()
    }
    elseif ($line -cmatch '^\| <code>([a-z_]+)</code> \|\| (.+?) \|\| (.+)$') {
        $baseName[$Matches[1]] = $Matches[2].Trim()
    }
}

# wiki 表格里的名称偶尔带 <code> 标记，统一清掉
foreach ($map in @($advName, $blkName, $mobName, $baseName)) {
    foreach ($k in @($map.Keys)) {
        $map[$k] = ($map[$k] -replace '</?code>', '').Trim()
    }
}
$advI18n = @{}
$advFile = Join-Path $Root 'Trmenu\advancements.txt'
if (Test-Path $advFile) {
    foreach ($line in Get-Content $advFile -Encoding UTF8) {
        if ($line -match '"advancements\.([a-z_]+)\.([a-z_0-9]+)\.(title|description)":\s*"(.*)"\s*,?\s*$') {
            $key = "$($Matches[1])/$($Matches[2])"
            if (-not $advI18n.ContainsKey($key)) {
                $advI18n[$key] = @{ Title = ''; Description = '' }
            }
            if ($Matches[3] -eq 'title') { $advI18n[$key].Title = $Matches[4] }
            else { $advI18n[$key].Description = $Matches[4] }
        }
    }
} else {
    Write-Output '警告: 未找到 Trmenu\advancements.txt，成就名将回退到 wiki 译名'
}

# Trmenu\advancements.txt 里缺的条目，译名取自 1.21.11 官方 zh_cn 语言文件
$advOverride = @{
    'adventure/who_needs_rockets'                  = @('还要啥火箭啊？', '使用风弹将自己向上弹射8格')
    'adventure/lighten_up'                         = @('铜光焕发', '用斧刮削铜灯来让它变得更亮')
    'adventure/read_power_of_chiseled_bookshelf'   = @('知识就是力量', '使用红石比较器获取雕纹书架的信号强度')
    'adventure/minecraft_trials_edition'           = @('Minecraft：试炼版', '踏入试炼密室')
    'adventure/under_lock_and_key'                 = @('珍藏密敛', '用试炼钥匙解锁宝库')
    'adventure/revaulting'                         = @('宝经磨炼', '用不祥试炼钥匙解锁不祥宝库')
    'adventure/blowback'                           = @('逆风翻盘', '反弹旋风人的风弹来击杀旋风人')
    'adventure/overoverkill'                       = @('天赐良击', '使用重锤一击造成50颗心的伤害')
    'adventure/crafters_crafting_crafters'         = @('合成器合成合成器', '靠近一个正在合成合成器的合成器')
    'adventure/brush_armadillo'                    = @('这不是鳞甲么？', '用刷子从犰狳身上获得犰狳鳞甲')
    'adventure/use_lodestone'                      = @('天涯共此石', '对着磁石使用指南针')
    'adventure/heart_transplanter'                 = @('移心接木', '将嘎枝之心以正确朝向放在两个苍白橡木原木方块中间')
    'adventure/spear_many_mobs'                    = @('生物串串香', '用矛在一次冲锋攻击内命中五只生物')
    'husbandry/bred_all_animals'                   = @('成双成对', '繁殖每种动物！')
    'husbandry/obtain_netherite_hoe'               = @('终极奉献', '用下界合金锭升级一把锄，然后重新考虑你的人生抉择')
    'husbandry/place_dried_ghast_in_water'         = @('补水保湿！', '将失水恶魂方块放在水中')
    'husbandry/remove_wolf_armor'                  = @('华丽一剪', '用剪刀移除狼身上的狼铠')
    'husbandry/repair_wolf_armor'                  = @('完好如初', '用犰狳鳞甲修复损坏的狼铠')
    'husbandry/whole_pack'                         = @('群狼聚首', '驯服所有种类的狼')
}

# ------------------------------------------------------------
# 2. 段位
# ------------------------------------------------------------
$ranks = New-Object System.Collections.Generic.List[object]
foreach ($line in Get-Content $ranksFile -Encoding UTF8) {
    if ($line -notmatch "^  ([A-Za-z_0-9+]+): \{display: '(.+?)', score: ([0-9.]+)(.*)\}\s*$") { continue }
    $id = $Matches[1]; $display = $Matches[2]; $score = [double]$Matches[3]; $rest = $Matches[4]
    $money = 0.0
    $m = [regex]::Match($rest, 'money: ([0-9.]+)'); if ($m.Success) { $money = [double]$m.Groups[1].Value }
    $attrs = [ordered]@{}
    $m = [regex]::Match($rest, 'attributes: \{([^}]*)\}')
    if ($m.Success) {
        foreach ($pair in ($m.Groups[1].Value -split ',')) {
            $kv = $pair -split ':\s*'
            if ($kv.Count -eq 2) { $attrs[$kv[0].Trim()] = [double]$kv[1].Trim() }
        }
    }
    $extra = @()
    $m = [regex]::Match($rest, 'extra: \[(.*)\]')
    if ($m.Success) { foreach ($e in [regex]::Matches($m.Groups[1].Value, "'([^']*)'")) { $extra += $e.Groups[1].Value } }
    $ranks.Add([pscustomobject]@{ Id = $id; Display = $display; Score = $score; Money = $money; Attributes = $attrs; Extra = $extra })
}

# ------------------------------------------------------------
# 3. 旧菜单的段位材质
# ------------------------------------------------------------
$rankMaterial = @{}
$oldLines = Get-Content $oldMenu -Encoding UTF8
for ($i = 0; $i -lt $oldLines.Count; $i++) {
    if ($oldLines[$i] -match '^  ([A-Za-z_0-9+]+):\s*$') {
        $id = $Matches[1]
        for ($j = $i + 1; $j -lt [math]::Min($i + 8, $oldLines.Count); $j++) {
            if ($oldLines[$j] -match '^\s+material:\s*(\S+)') { $rankMaterial[$id] = $Matches[1]; break }
            if ($oldLines[$j] -match '^  \S+:\s*$') { break }
        }
    }
}

# ------------------------------------------------------------
# 4. 服务器计分配置
# ------------------------------------------------------------
function Read-SimpleMap([string]$file, [string]$section) {
    $map = [ordered]@{}
    $inSection = $false
    foreach ($line in Get-Content $file -Encoding UTF8) {
        if ($line -match '^([A-Za-z_]+):\s*$') { $inSection = ($Matches[1] -eq $section); continue }
        if (-not $inSection) { continue }
        if ($line -match "^\s+'?([A-Za-z_0-9:/]+)'?:\s*([0-9.]+)") { $map[$Matches[1]] = [double]$Matches[2] }
    }
    return $map
}

$weights = Read-SimpleMap (Join-Path $serverCfg 'base.yml') 'weights'
$blocks  = Read-SimpleMap (Join-Path $serverCfg 'blocks.yml') 'specific_block_scores'
$mobs    = Read-SimpleMap (Join-Path $serverCfg 'mobs.yml') 'specific_mob_scores'

$advOrder = @('story', 'adventure', 'husbandry', 'nether', 'end')
$advData = @{}
foreach ($p in $advOrder) { $advData[$p] = [ordered]@{} }
foreach ($line in Get-Content (Join-Path $serverCfg 'advancements.yml') -Encoding UTF8) {
    if ($line -match "^\s+'minecraft:([a-z_]+)/([a-z_0-9]+)':\s*([0-9.]+)") {
        $prefix = $Matches[1]; $path = $Matches[2]; $score = [double]$Matches[3]
        if ($advData.ContainsKey($prefix)) { $advData[$prefix]["$prefix/$path"] = $score }
    }
}
$advTotal = 0
foreach ($p in $advOrder) { $advTotal += $advData[$p].Count }

# 成就分页：每页最多 45 个（最后一行留给返回按钮）
$advChunks = New-Object System.Collections.Generic.List[object]
$advFirstPage = @{}
foreach ($prefix in $advOrder) {
    $keys = @($advData[$prefix].Keys)
    $count = $keys.Count
    if ($count -eq 0) { continue }
    $pageCount = [math]::Ceiling($count / 45.0)
    $size = [math]::Ceiling($count / $pageCount)   # 尽量平均分配，避免最后一页只剩一两个
    for ($i = 0; $i -lt $count; $i += $size) {
        $end = [math]::Min($i + $size - 1, $count - 1)
        $chunk = @($keys[$i..$end])
        if ($advFirstPage.ContainsKey($prefix) -eq $false) { $advFirstPage[$prefix] = 10 + $advChunks.Count }
        $advChunks.Add([pscustomobject]@{ Prefix = $prefix; Keys = $chunk })
    }
}

# ------------------------------------------------------------
# 5. 材质映射
# ------------------------------------------------------------
$baseMaterial = @{
    block_break = 'IRON_PICKAXE'; block_place = 'BRICKS'; craft_item = 'CRAFTING_TABLE'
    mob_kill = 'IRON_SWORD'; player_kill = 'DIAMOND_SWORD'; damage_dealt = 'BOW'
    trade = 'EMERALD'; enchant = 'ENCHANTING_TABLE'; jump = 'SLIME_BALL'
    move_cm = 'LEATHER_BOOTS'; play_tick = 'CLOCK'; fishing = 'FISHING_ROD'; raid_win = 'BELL'
}

$blockMaterial = @{
    log = 'OAK_LOG'; wood = 'OAK_WOOD'; coal_ore = 'COAL_ORE'; copper_ore = 'COPPER_ORE'
    iron_ore = 'IRON_ORE'; gold_ore = 'GOLD_ORE'; diamond_ore = 'DIAMOND_ORE'
    emerald_ore = 'EMERALD_ORE'; lapis_ore = 'LAPIS_ORE'; redstone_ore = 'REDSTONE_ORE'
    nether_quartz_ore = 'NETHER_QUARTZ_ORE'; ancient_debris = 'ANCIENT_DEBRIS'
    nether_gold_ore = 'NETHER_GOLD_ORE'; obsidian = 'OBSIDIAN'
}

$mobMaterialSpecial = @{
    WARDEN = 'SCULK_CATALYST'; WITHER = 'WITHER_SKELETON_SKULL'; ENDER_DRAGON = 'DRAGON_HEAD'
    ELDER_GUARDIAN = 'PRISMARINE_CRYSTALS'; RAVAGER = 'SADDLE'; EVOKER = 'TOTEM_OF_UNDYING'
    PIGLIN_BRUTE = 'GOLDEN_AXE'; HOGLIN = 'COOKED_PORKCHOP'; GHAST = 'GHAST_TEAR'
    BLAZE = 'BLAZE_ROD'; MAGMA_CUBE = 'MAGMA_CREAM'; SLIME = 'SLIME_BALL'
    BREEZE = 'WIND_CHARGE'; CAMEL_HUSK = 'CAMEL_SPAWN_EGG'; PARCHED = 'SAND'
    NAUTILUS = 'NAUTILUS_SHELL'; ZOMBIE_NAUTILUS = 'NAUTILUS_SHELL'; HAPPY_GHAST = 'WHITE_HARNESS'
    COPPER_GOLEM = 'COPPER_BLOCK'; ARMADILLO = 'ARMADILLO_SCUTE'; BOGGED = 'MOSS_BLOCK'
}

$advMaterialRules = @(
    @('netherite', 'NETHERITE_INGOT'), @('ancient_debris', 'ANCIENT_DEBRIS'),
    @('dragon_breath', 'DRAGON_BREATH'), @('dragon', 'DRAGON_HEAD'), @('wither', 'WITHER_SKELETON_SKULL'),
    @('beacon', 'BEACON'), @('elytra', 'ELYTRA'), @('trident', 'TRIDENT'), @('totem', 'TOTEM_OF_UNDYING'),
    @('gold', 'GOLD_INGOT'), @('diamond', 'DIAMOND'), @('emerald', 'EMERALD'), @('iron', 'IRON_INGOT'),
    @('copper', 'COPPER_INGOT'), @('quartz', 'QUARTZ'), @('honey', 'HONEYCOMB'), @('bee', 'HONEY_BOTTLE'),
    @('potion', 'POTION'), @('brew', 'BREWING_STAND'), @('enchant', 'ENCHANTED_BOOK'),
    @('bookshelf', 'CHISELED_BOOKSHELF'), @('book', 'BOOK'), @('bed', 'RED_BED'),
    @('obsidian', 'OBSIDIAN'), @('crying', 'CRYING_OBSIDIAN'), @('lava', 'LAVA_BUCKET'),
    @('water', 'WATER_BUCKET'), @('bucket', 'BUCKET'), @('blaze', 'BLAZE_ROD'), @('ghast', 'GHAST_TEAR'),
    @('piglin', 'GOLD_INGOT'), @('strider', 'WARPED_FUNGUS_ON_A_STICK'), @('skull', 'SKELETON_SKULL'),
    @('creeper', 'CREEPER_HEAD'), @('skeleton', 'BONE'), @('zombie', 'ROTTEN_FLESH'),
    @('spider', 'SPIDER_EYE'), @('enderman', 'ENDER_PEARL'), @('shulker', 'SHULKER_SHELL'),
    @('chorus', 'CHORUS_FRUIT'), @('arrow', 'ARROW'), @('crossbow', 'CROSSBOW'), @('bow', 'BOW'),
    @('shield', 'SHIELD'), @('axe', 'IRON_AXE'), @('pickaxe', 'IRON_PICKAXE'), @('hoe', 'IRON_HOE'),
    @('sword', 'IRON_SWORD'), @('fishing', 'FISHING_ROD'), @('wheat', 'WHEAT'), @('carrot', 'CARROT'),
    @('potato', 'POTATO'), @('beetroot', 'BEETROOT'), @('melon', 'MELON_SLICE'), @('pumpkin', 'PUMPKIN'),
    @('sweet_berries', 'SWEET_BERRIES'), @('cake', 'CAKE'), @('milk', 'MILK_BUCKET'), @('egg', 'EGG'),
    @('chicken', 'CHICKEN'), @('cow', 'BEEF'), @('pig', 'PORKCHOP'), @('sheep', 'MUTTON'),
    @('rabbit', 'RABBIT'), @('fish', 'COD'), @('turtle', 'TURTLE_HELMET'), @('axolotl', 'AXOLOTL_BUCKET'),
    @('frog', 'FROGSPAWN'), @('goat', 'GOAT_HORN'), @('villager', 'EMERALD'), @('trade', 'EMERALD'),
    @('raid', 'BELL'), @('sleep', 'RED_BED'), @('ride', 'SADDLE'), @('boat', 'OAK_BOAT'),
    @('minecart', 'MINECART'), @('nether', 'NETHERRACK'), @('end', 'END_STONE'),
    @('stone', 'STONE'), @('root', 'BOOK')
)

function Get-AdvMaterial([string]$path, [string]$prefix) {
    foreach ($rule in $advMaterialRules) {
        if ($path.Contains($rule[0])) { return $rule[1] }
    }
    switch ($prefix) {
        'story' { return 'BOOK' }
        'adventure' { return 'COMPASS' }
        'husbandry' { return 'WHEAT' }
        'nether' { return 'NETHERRACK' }
        'end' { return 'END_STONE' }
    }
    return 'PAPER'
}

function Get-MobMaterial([string]$id) {
    if ($mobMaterialSpecial.ContainsKey($id)) { return $mobMaterialSpecial[$id] }
    return "${id}_SPAWN_EGG"
}

$attrName = @{
    attack = '攻击'; armor_pierce = '破甲攻击'; absolute = '绝对攻击'; defense = '防御'
    max_health = '血量上限'; dodge = '闪避率'; shield = '护盾'; shield_regen = '护盾恢复'
}

# ------------------------------------------------------------
# 6. 头部
# ------------------------------------------------------------
$regular = @($ranks | Where-Object { $_.Id -notlike 'Extra_*' -and $_.Id -ne 'Newbie' })
$extraRanks = @($ranks | Where-Object { $_.Id -like 'Extra_*' })

Add '# ============================================================'
Add '# 生存段位系统 GUI —— 由 Trmenu/tools/generate_gui.ps1 自动生成，请勿手改'
Add '# 菜单 ID：rank_gui     打开方式：/trmenu open rank_gui 或 /rankgui'
Add '# 依赖：TrMenu, PlaceholderAPI, SRExpansion（可选 AdvancedPlayerAttribute）'
Add '# 页面：0 主页面 / 1 设置操作 / 2 段位选择 / 3 常规段位 / 4 额外段位'
Add '#       5 算法总览 / 6 一般类 / 7 方块 / 8 生物 / 9 成就分类'
Add '#       10+ 成就明细（故事 / 冒险 / 农牧 / 下界 / 末地，超过 45 个自动分页）'
Add '# ============================================================'
Add ''
Add 'Title:'
Add ('  - ' + (Y '&a&l生存&6&l段位&e&l系统 &7| &d%srank_rank_name% &7| &e%srank_score% 分'))
Add 'Title-Update: 60'
Add ''
Add '# ---------------- 布局 ----------------'
Add 'Layout:'

# 0 主页面
AddPage '0 主页面' @(
    @('#', '#', '#', '#', '#', '#', '#', '#', '#'),
    @('#', 'I', ' ', ' ', ' ', ' ', ' ', 'R', '#'),
    @('#', ' ', ' ', ' ', 'T', ' ', ' ', ' ', '#'),
    @('#', 'F', ' ', ' ', ' ', ' ', ' ', 'C', '#'),
    @('#', 'S', ' ', ' ', ' ', ' ', ' ', 'A', '#'),
    @('#', '#', '#', '#', '#', '#', '#', '#', '#')
) $null

# 1 设置
AddPage '1 个人设置 / 操作' @(
    @('#', '#', '#', '#', '#', '#', '#', '#', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', ' ', 'X', ' ', ' ', ' ', 'Y', ' ', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', ' ', ' ', ' ', 'Z', ' ', ' ', ' ', '#')
) @('K', '#', '#', '#', '#', '#', '#', '#', '#')

# 2 段位选择
AddPage '2 全段位展示 - 选择' @(
    @('#', '#', '#', '#', '#', '#', '#', '#', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', ' ', '`regular`', ' ', ' ', ' ', '`extra`', ' ', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#')
) @('K', '#', '#', '#', '#', '#', '#', '#', '#')

# 3 常规段位（50 个：5 行 + 最后一行 5 个 + 返回）
$rows = New-Object System.Collections.Generic.List[object]
for ($r = 0; $r -lt 5; $r++) {
    $units = @()
    for ($j = 0; $j -lt 9; $j++) { $units += ('`' + $regular[$r * 9 + $j].Id + '`') }
    $rows.Add($units)
}
$last = @()
for ($j = 0; $j -lt 9; $j++) {
    if (($r * 9 + $j) -lt $regular.Count -and $j -lt 5) { $last += ('`' + $regular[45 + $j].Id + '`') }
    elseif ($j -eq 8) { $last += '`back2`' }
    else { $last += ' ' }
}
$rows.Add($last)
AddPage '3 常规段位（1-50）' $rows $null

# 4 额外段位（50 个）
$rows = New-Object System.Collections.Generic.List[object]
for ($r = 0; $r -lt 5; $r++) {
    $units = @()
    for ($j = 0; $j -lt 9; $j++) { $units += ('`' + $extraRanks[$r * 9 + $j].Id + '`') }
    $rows.Add($units)
}
$last = @()
for ($j = 0; $j -lt 9; $j++) {
    if ($j -lt 5) { $last += ('`' + $extraRanks[45 + $j].Id + '`') }
    elseif ($j -eq 8) { $last += '`back2`' }
    else { $last += ' ' }
}
$rows.Add($last)
AddPage '4 额外段位（51-100）' $rows $null

# 5 算法总览
AddPage '5 全段位分计算算法 - 总览' @(
    @('#', '#', '#', '#', '#', '#', '#', '#', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', ' ', '`calc_base`', ' ', '`calc_block`', ' ', '`calc_mob`', ' ', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', ' ', ' ', ' ', '`calc_adv`', ' ', ' ', ' ', '#')
) @('K', '#', '#', '#', '#', '#', '#', '#', '#')

# 6 一般类
$rows = New-Object System.Collections.Generic.List[object]
$keys = @($weights.Keys)
for ($r = 0; $r -lt 2; $r++) {
    $units = @()
    for ($j = 0; $j -lt 9; $j++) {
        $k = $r * 9 + $j
        if ($k -lt $keys.Count) { $units += ('`base_' + $keys[$k] + '`') } else { $units += ' ' }
    }
    $rows.Add($units)
}
$rows.Add(@('#', '#', '#', '#', '#', '#', '#', '#', '#'))
AddPage '6 一般类加分' $rows @('`back_calc`', '#', '#', '#', '#', '#', '#', '#', '#')

# 7 方块
$rows = New-Object System.Collections.Generic.List[object]
$keys = @($blocks.Keys)
for ($r = 0; $r -lt 2; $r++) {
    $units = @()
    for ($j = 0; $j -lt 9; $j++) {
        $k = $r * 9 + $j
        if ($k -lt $keys.Count) { $units += ('`blk_' + $keys[$k] + '`') } else { $units += ' ' }
    }
    $rows.Add($units)
}
$rows.Add(@('#', '#', '#', '#', '#', '#', '#', '#', '#'))
AddPage '7 特殊方块加分' $rows @('`back_calc`', '#', '#', '#', '#', '#', '#', '#', '#')

# 8 生物
$rows = New-Object System.Collections.Generic.List[object]
$keys = @($mobs.Keys)
for ($r = 0; $r -lt 5; $r++) {
    $units = @()
    for ($j = 0; $j -lt 9; $j++) {
        $k = $r * 9 + $j
        if ($k -lt $keys.Count) { $units += ('`mob_' + $keys[$k] + '`') } else { $units += ' ' }
    }
    $rows.Add($units)
}
AddPage '8 特殊生物加分' $rows @('`back_calc`', '#', '#', '#', '#', '#', '#', '#', '#')

# 9 成就分类
AddPage '9 成就分类' @(
    @('#', '#', '#', '#', '#', '#', '#', '#', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', '`adv_story`', '`adv_adventure`', '`adv_husbandry`', '`adv_nether`', '`adv_end`', ' ', ' ', '#'),
    @('#', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '#'),
    @('#', '#', '#', '#', '#', '#', '#', '#', '#')
) @('`back_calc`', '#', '#', '#', '#', '#', '#', '#', '#')

# 10+ 成就明细
$advTitles = @{ story = '主世界·故事'; adventure = '主世界·冒险'; husbandry = '主世界·农牧'; nether = '下界'; end = '末地' }
$pageNo = 10
foreach ($chunk in $advChunks) {
    $rows = New-Object System.Collections.Generic.List[object]
    $keys = @($chunk.Keys)
    $rowCount = [math]::Ceiling($keys.Count / 9.0)
    for ($r = 0; $r -lt $rowCount; $r++) {
        $units = @()
        for ($j = 0; $j -lt 9; $j++) {
            $k = $r * 9 + $j
            if ($k -lt $keys.Count) { $units += ('`adv_' + ($keys[$k] -replace '/', '_') + '`') } else { $units += ' ' }
        }
        $rows.Add($units)
    }
    AddPage ($pageNo.ToString() + ' 成就-' + $advTitles[$chunk.Prefix]) $rows @('`back_adv`', '#', '#', '#', '#', '#', '#', '#', '#')
    $pageNo++
}

# ---------------- 选项 / 绑定 / 事件 ----------------
Add ''
Add 'PlayerInventory:'
Add ('  - ' + (Y '         '))
Add ('  - ' + (Y '         '))
Add ('  - ' + (Y '         '))
Add ('  - ' + (Y '         '))
Add ''
Add 'Options:'
Add '  Arguments: false'
Add '  Default-Arguments: [ ]'
Add '  Default-Layout: 0'
Add '  Hide-Player-Inventory: true'
Add '  Min-Click-Delay: 200'
Add "  Depend-Expansions: [ 'server', 'player', 'progress', 'animations' ]"
Add ''
Add 'Bindings:'
Add '  Commands:'
Add ('    - ' + (Y '(?i)(rank|srank)?gui'))
Add '  Items:'
Add ('    - ' + (Y 'material:clock,lore:生存段位'))
Add ''
Add 'Events:'
Add '  Open:'
Add '    - actions:'
Add ('        - ' + (Y 'sound: BLOCK_CHEST_OPEN-1-0'))
Add '  Close:'
Add ('    - ' + (Y 'sound: BLOCK_CHEST_CLOSE-1-0'))
Add ''
Add '# ============================================================'
Add '# 图标'
Add '# ============================================================'
Add 'Icons:'

Add "  '#':"
Add '    update: [ -1, 10, 15, -1 ]'
Add '    display:'
Add '      material: White Stained Glass Pane'
Add ('      name: [ ' + (Y '&e---&b琉璃之塔&e---') + ', ' + (Y '&b---&e琉璃之塔&b---') + ' ]')
Add '      lore:'
Add ('        - ' + (Y '&7生存段位系统'))
Add '    actions:'
Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''

Add '  # ---------- 导航按钮 ----------'
Add '  back2:'
Add '    update: 20'
Add '    display:'
Add '      material: Light Blue Stained Glass Pane'
Add ('      name: ' + (Y '&b返回段位选择'))
Add '      lore:'
Add ('        - ' + (Y '&7点击返回'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 2 &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''
Add '  back_calc:'
Add '    update: 20'
Add '    display:'
Add '      material: Light Blue Stained Glass Pane'
Add ('      name: ' + (Y '&b返回算法总览'))
Add '      lore:'
Add ('        - ' + (Y '&7点击返回'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 5 &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''
Add '  back_adv:'
Add '    update: 20'
Add '    display:'
Add '      material: Light Blue Stained Glass Pane'
Add ('      name: ' + (Y '&b返回成就分类'))
Add '      lore:'
Add ('        - ' + (Y '&7点击返回'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 9 &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''
Add '  K:'
Add '    update: 20'
Add '    display:'
Add '      material: ARROW'
Add ('      name: ' + (Y '&b返回主页面'))
Add '      lore:'
Add ('        - ' + (Y '&7点击返回'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 0 &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''

# 主页面部件
Add '  # ---------- 主页面 ----------'
Add '  I:'
Add '    update: 20'
Add '    display:'
Add ('      material: ' + (Y 'head:%player_name%'))
Add ('      name: ' + (Y '&6&l%player_name% &7的个人档案'))
Add '      lore:'
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&f当前段位: %srank_rank_name%'))
Add ('        - ' + (Y '&f下一段位: %srank_next_rank_name%'))
Add ('        - ' + (Y '&f生存段位分: &6&l%srank_score% &7(#%srank_rank%)'))
Add ('        - ' + (Y '&f升级进度: &e%srank_rank_progress%% &7(还差 %srank_rank_remaining% 分)'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&6&l属性加成'))
Add ('        - ' + (Y '&f· 攻击 &7%apa_attack% &f· 防御 &7%apa_defense%'))
Add ('        - ' + (Y '&f· 血量上限 &7%apa_max_health% &8(总计 %apa_max_health_total%)'))
Add ('        - ' + (Y '&f· 闪避率 &7%apa_dodge%% &f· 护盾 &7%apa_shield%/%apa_shield_max%'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&7自动升级: &f%srank_autorankup% &7| 悬浮字: &f%srank_display%'))
Add ('        - ' + (Y '&e点击刷新'))
Add '    actions:'
Add ('      all: ' + (Y 'refresh: * &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''
Add '  R:'
Add '    update: 60'
Add '    display:'
Add '      material: NETHER_STAR'
Add ('      name: ' + (Y '&d&l生存段位分排行榜'))
Add '      lore:'
Add ('        - ' + (Y '&7我的排名: &e#%srank_rank%'))
Add ('        - ' + (Y '&8&m------------------------'))
for ($i = 1; $i -le 10; $i++) {
    Add ('        - ' + (Y ("&e{0}. &f%srank_top{0}_name% &7- &6%srank_top{0}_score%" -f $i)))
}
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&7分数来自原版统计，越高代表越肝'))
Add '    actions:'
Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
Add ''
Add '  T:'
Add '    update: 20'
Add '    display:'
Add '      material: BOOK'
Add ('      name: ' + (Y '&e&l生存段位 · 指导'))
Add '      lore:'
Add ('        - ' + (Y '&f生存段位是服务器特色玩法之一，'))
Add ('        - ' + (Y '&f通过积累「生存段位分」来晋升。'))
Add ('        - ' + (Y '&7分数来源: 挖掘 / 击杀 / 合成 / 交易 /'))
Add ('        - ' + (Y '&7钓鱼 / 成就 / 在线时长等原版统计。'))
Add ('        - ' + (Y '&7达到分数要求后会自动升级，'))
Add ('        - ' + (Y '&7也可以在「设置」里关闭自动升级或手动升级。'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&7左上: &f个人档案 &7| 右上: &f排行榜'))
Add ('        - ' + (Y '&7左下: &f段位列表 &7| 右下: &f计分规则'))
Add ('        - ' + (Y '&7左侧: &f属性图鉴 &7| 右侧: &f设置'))
Add '    actions:'
Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
Add ''
Add '  F:'
Add '    update: 20'
Add '    display:'
Add '      material: EXPERIENCE_BOTTLE'
Add ('      name: ' + (Y '&a&l段位属性 · 图鉴'))
Add '      lore:'
Add ('        - ' + (Y '&7段位会累计提供以下属性'))
Add ('        - ' + (Y '&7（需要安装 AdvancedPlayerAttribute）'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&f· 攻击 &7- 命中时额外造成的伤害'))
Add ('        - ' + (Y '&f· 破甲攻击 &7- 无视护甲的额外伤害'))
Add ('        - ' + (Y '&f· 绝对攻击 &7- 无视一切防御与 Buff 的伤害'))
Add ('        - ' + (Y '&f· 防御 &7- 受到的伤害固定减少'))
Add ('        - ' + (Y '&f· 血量上限 &7- 最大生命值提升'))
Add ('        - ' + (Y '&f· 闪避率 &7- 按百分比完全躲开一次攻击'))
Add ('        - ' + (Y '&f· 护盾 &7- 黄心，优先于生命值被消耗，'))
Add ('        - ' + (Y '&7  被击破后需满血才会自然回复'))
Add ('        - ' + (Y '&f· 护盾恢复 &7- 每 N 秒回复 1 点护盾'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&7我当前的属性:'))
Add ('        - ' + (Y '&f攻击 &e%apa_attack% &f防御 &e%apa_defense% &f闪避 &e%apa_dodge%%'))
Add ('        - ' + (Y '&f护盾 &e%apa_shield%&7/&e%apa_shield_max% &f恢复 &e%apa_shield_regen% 秒/点'))
Add '    actions:'
Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
Add ''
Add '  S:'
Add '    update: 20'
Add '    display:'
Add '      material: COMPARATOR'
Add ('      name: ' + (Y '&b&l设置 / 操作'))
Add '      lore:'
Add ('        - ' + (Y '&7自动升级、悬浮字开关、手动升级'))
Add ('        - ' + (Y '&7点击进入'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 1 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  A:'
Add '    update: 20'
Add '    display:'
Add '      material: GRASS_BLOCK'
Add ('      name: ' + (Y '&e&l生存段位列表'))
Add '      lore:'
Add ('        - ' + (Y '&7查看全部 100 个段位'))
Add ('        - ' + (Y '&7含所需分数、金币与属性奖励'))
Add ('        - ' + (Y '&7点击进入'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 2 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  C:'
Add '    update: 20'
Add '    display:'
Add '      material: WRITABLE_BOOK'
Add ('      name: ' + (Y '&6&l生存段位分计算算法'))
Add '      lore:'
Add ('        - ' + (Y '&7一般统计 / 特殊方块 / 特殊生物 / 成就'))
Add ('        - ' + (Y '&7每一项加分的来源'))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 5 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''

# 设置页
Add '  # ---------- 设置 / 操作 ----------'
Add '  X:'
Add '    update: 20'
Add '    display:'
Add '      material: CLOCK'
Add ('      name: ' + (Y '&e&l自动升级 &7: &f%srank_autorankup%'))
Add '      lore:'
Add ('        - ' + (Y '&7开启后，分数达到要求会自动升段'))
Add ('        - ' + (Y '&7关闭后需要手动点击升级'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&f当前状态: &a%srank_autorankup%'))
Add ('        - ' + (Y '&7点击切换'))
Add '    actions:'
Add ('      all: ' + (Y 'command: srank autorankup &&& delay: 2 &&& update: X &&& sound: BLOCK_NOTE_BLOCK_PLING-1-1'))
Add ''
Add '  Y:'
Add '    update: 20'
Add '    display:'
Add '      material: ENDER_EYE'
Add ('      name: ' + (Y '&d&l升级悬浮字 &7: &f%srank_display%'))
Add '      lore:'
Add ('        - ' + (Y '&7升级时在视野左侧播放的悬浮字演出'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&f当前状态: &a%srank_display%'))
Add ('        - ' + (Y '&7点击切换'))
Add '    actions:'
Add ('      all: ' + (Y 'command: srank display &&& delay: 2 &&& update: Y &&& sound: BLOCK_NOTE_BLOCK_PLING-1-1'))
Add ''
Add '  Z:'
Add '    update: 20'
Add '    display:'
Add '      material: NETHER_STAR'
Add ('      name: ' + (Y '&6&l手动升级段位'))
Add '      lore:'
Add ('        - ' + (Y '&7分数已达到下一段位要求时点击升级'))
Add ('        - ' + (Y '&8&m------------------------'))
Add ('        - ' + (Y '&f当前段位: %srank_rank_name%'))
Add ('        - ' + (Y '&f下一段位: %srank_next_rank_name% &7(需 %srank_next_rank_score% 分)'))
Add ('        - ' + (Y '&f还差: &e%srank_rank_remaining% &7分'))
Add ('        - ' + (Y '&7点击升级'))
Add '    actions:'
Add ('      all: ' + (Y 'command: srank rankup &&& sound: BLOCK_NOTE_BLOCK_PLING-1-2'))
Add ''

# 段位选择
Add '  # ---------- 段位选择 ----------'
Add '  regular:'
Add '    update: 20'
Add '    display:'
Add '      material: IRON_BLOCK'
Add ('      name: ' + (Y '&a生存段位列表 &f- &a常规段位'))
Add '      lore:'
Add ('        - ' + (Y '&7第 1 ~ 50 个段位'))
Add ('        - ' + (Y ('&7共 ' + $regular.Count + ' 个')))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 3 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  extra:'
Add '    update: 20'
Add '    display:'
Add '      material: DIAMOND_BLOCK'
Add ('      name: ' + (Y '&a生存段位列表 &f- &d额外段位'))
Add '      lore:'
Add ('        - ' + (Y '&7第 51 ~ 100 个段位（Extra 前缀）'))
Add ('        - ' + (Y ('&7共 ' + $extraRanks.Count + ' 个')))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 4 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''

# ---------------- 段位图标 ----------------
Add '  # ============================================================'
Add '  # 段位图标（常规 + 额外）'
Add '  # ============================================================'
foreach ($rank in $ranks) {
    if ($rank.Id -eq 'Newbie') { continue }
    $material = 'STONE'
    if ($rankMaterial.ContainsKey($rank.Id)) { $material = $rankMaterial[$rank.Id] }
    $mini = ConvertTo-TrMenu $rank.Display

    $lore = New-Object System.Collections.Generic.List[string]
    $lore.Add('&7需要段位分: &e' + (Fmt $rank.Score))
    if ($rank.Money -gt 0) { $lore.Add('&7升级奖励: &a' + (Fmt $rank.Money) + ' 金币') }
    if ($rank.Attributes.Count -gt 0) {
        $parts = @()
        foreach ($k in $rank.Attributes.Keys) {
            $nm = $attrName[$k]; if (-not $nm) { $nm = $k }
            $part = $nm + ' +' + (Fmt $rank.Attributes[$k])
            if ($k -eq 'dodge') { $part += '%' }
            $parts += $part
        }
        $lore.Add('&7属性奖励: &b' + ($parts -join ' &7/ &b'))
    }
    if ($rank.Extra.Count -gt 0) { $lore.Add((ConvertTo-TrMenu ('&7专属内容: &d' + $rank.Extra[0]))) }
    $lore.Add('')

    Add ('  ' + $rank.Id + ':')
    Add '    update: [ -1, 20, 30, -1 ]'
    Add '    display:'
    Add ('      material: ' + $material)
    Add ('      name: ' + (Y $mini))
    Add '      lore:'
    foreach ($l in $lore) { Add ('        - ' + (Y $l)) }
    Add '    actions:'
    Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_PLING-1-1'))
    Add '    icons:'
    Add ('      - condition: ' + (Y ('perm *rank.' + $rank.Id.ToLower() + '.achieved')))
    Add '        priority: 10'
    Add '        display:'
    Add '          shiny: true'
    Add ('          name: ' + (Y $mini))
    Add '          lore:'
    foreach ($l in $lore) { if ($l -ne '') { Add ('            - ' + (Y $l)) } }
    Add ('            - ' + (Y '&a✔ 已达到'))
    Add ''
}

# ---------------- 算法总览 ----------------
Add '  # ---------- 算法总览 ----------'
Add '  calc_base:'
Add '    update: 20'
Add '    display:'
Add '      material: IRON_PICKAXE'
Add ('      name: ' + (Y '&e&l一般类加分'))
Add '      lore:'
Add ('        - ' + (Y '&7方块破坏 / 放置、击杀、合成等基础统计'))
Add ('        - ' + (Y ('&7共 ' + $weights.Count + ' 项')))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 6 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  calc_block:'
Add '    update: 20'
Add '    display:'
Add '      material: DIAMOND_ORE'
Add ('      name: ' + (Y '&e&l特殊方块加分'))
Add '      lore:'
Add ('        - ' + (Y '&7挖掘特定方块时的额外分'))
Add ('        - ' + (Y ('&7共 ' + $blocks.Count + ' 类')))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 7 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  calc_mob:'
Add '    update: 20'
Add '    display:'
Add '      material: IRON_SWORD'
Add ('      name: ' + (Y '&e&l特殊生物加分'))
Add '      lore:'
Add ('        - ' + (Y '&7击杀特定生物的额外分'))
Add ('        - ' + (Y ('&7共 ' + $mobs.Count + ' 种')))
Add ('        - ' + (Y '&7点击查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 8 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''
Add '  calc_adv:'
Add '    update: 20'
Add '    display:'
Add '      material: ENCHANTED_BOOK'
Add ('      name: ' + (Y '&e&l成就（进度）加分'))
Add '      lore:'
Add ('        - ' + (Y '&7完成原版成就获得一次性分数'))
Add ('        - ' + (Y ('&7共 ' + $advTotal + ' 个成就')))
Add ('        - ' + (Y '&7点击按分类查看'))
Add '    actions:'
Add ('      all: ' + (Y 'page: 9 &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1'))
Add ''

# ---------------- 一般类 ----------------
Add '  # ---------- 一般类加分项 ----------'
foreach ($key in $weights.Keys) {
    $name = $baseName[$key]; if (-not $name) { $name = $key }
    $mat = $baseMaterial[$key]; if (-not $mat) { $mat = 'PAPER' }
    Add ('  base_' + $key + ':')
    Add '    display:'
    Add ('      material: ' + $mat)
    Add ('      name: ' + (Y ('&e' + $name)))
    Add '      lore:'
    Add ('        - ' + (Y ('&7权重: &a+' + (Fmt $weights[$key]) + ' 分')))
    Add ('        - ' + (Y ('&7统计项: &8' + $key)))
    Add ('        - ' + (Y '&8&m------------------------'))
    Add ('        - ' + (Y '&7按原版统计次数累加，参与分段折扣'))
    Add '    actions:'
    Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
    Add ''
}

# ---------------- 方块 ----------------
Add '  # ---------- 特殊方块加分项 ----------'
foreach ($key in $blocks.Keys) {
    $name = $blkName[$key]; if (-not $name) { $name = $key }
    $mat = $blockMaterial[$key]; if (-not $mat) { $mat = $key.ToUpper() }
    Add ('  blk_' + $key + ':')
    Add '    display:'
    Add ('      material: ' + $mat)
    Add ('      name: ' + (Y ('&e' + $name)))
    Add '      lore:'
    Add ('        - ' + (Y ('&7额外分: &a+' + (Fmt $blocks[$key]) + ' /个')))
    Add ('        - ' + (Y ('&7配置键: &8' + $key)))
    Add ('        - ' + (Y '&8&m------------------------'))
    Add ('        - ' + (Y '&7与基础「方块破坏」权重叠加计算'))
    if ($key -eq 'log' -or $key -eq 'wood') {
        Add ('        - ' + (Y '&7按后缀生效，包含所有原木/菌柄变种'))
    }
    Add '    actions:'
    Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
    Add ''
}

# ---------------- 生物 ----------------
Add '  # ---------- 特殊生物加分项 ----------'
foreach ($key in $mobs.Keys) {
    $name = $mobName[$key]; if (-not $name) { $name = $key }
    $mat = Get-MobMaterial $key
    Add ('  mob_' + $key + ':')
    Add '    display:'
    Add ('      material: ' + $mat)
    Add ('      name: ' + (Y ('&e' + $name)))
    Add '      lore:'
    Add ('        - ' + (Y ('&7额外分: &a+' + (Fmt $mobs[$key]) + ' /只')))
    Add ('        - ' + (Y ('&7实体 ID: &8' + $key)))
    Add ('        - ' + (Y '&8&m------------------------'))
    Add ('        - ' + (Y '&7与基础「击杀生物」权重叠加计算'))
    Add '    actions:'
    Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
    Add ''
}

# ---------------- 成就分类 ----------------
$advMeta = @{
    story = @{ name = '主世界 · 故事'; material = 'BOOK'; desc = '主线剧情类成就' }
    adventure = @{ name = '主世界 · 冒险'; material = 'COMPASS'; desc = '探索与战斗类成就' }
    husbandry = @{ name = '主世界 · 农牧'; material = 'WHEAT'; desc = '种植与养殖类成就' }
    nether = @{ name = '下界'; material = 'NETHERRACK'; desc = '下界维度成就' }
    end = @{ name = '末地'; material = 'END_STONE'; desc = '末地维度成就' }
}
Add '  # ---------- 成就分类 ----------'
foreach ($prefix in $advOrder) {
    $meta = $advMeta[$prefix]
    Add ('  adv_' + $prefix + ':')
    Add '    update: 20'
    Add '    display:'
    Add ('      material: ' + $meta.material)
    Add ('      name: ' + (Y ('&e&l' + $meta.name)))
    Add '      lore:'
    Add ('        - ' + (Y ('&7' + $meta.desc)))
    Add ('        - ' + (Y ('&7共 ' + $advData[$prefix].Count + ' 个成就')))
    Add ('        - ' + (Y '&7点击查看'))
    Add '    actions:'
    Add ('      all: ' + (Y ('page: ' + $advFirstPage[$prefix] + ' &&& sound: BLOCK_NOTE_BLOCK_BIT-1-1')))
    Add ''
}

# ---------------- 成就明细 ----------------
Add '  # ---------- 成就明细 ----------'
foreach ($prefix in $advOrder) {
    $meta = $advMeta[$prefix]
    foreach ($path in $advData[$prefix].Keys) {
        $score = $advData[$prefix][$path]
        # 名称/简介优先用官方简中语言文件（advancements.txt），缺失时回退到 wiki 译名
        $name = ''
        $desc = ''
        if ($advI18n.ContainsKey($path)) {
            $name = $advI18n[$path].Title
            $desc = $advI18n[$path].Description
        }
        if (-not $name -and $advOverride.ContainsKey($path)) {
            $name = $advOverride[$path][0]
            $desc = $advOverride[$path][1]
        }
        if (-not $name) { $name = $advName[$path] }
        if (-not $name) { $name = ($path -split '/')[-1] }
        $mat = Get-AdvMaterial ($path -split '/')[-1] $prefix
        Add ('  adv_' + ($path -replace '/', '_') + ':')
        Add '    display:'
        Add ('      material: ' + $mat)
        Add ('      name: ' + (Y ('&e&l' + $name)))
        Add '      lore:'
        if ($desc) { Add ('        - ' + (Y ('&7' + $desc))) }
        Add ('        - ' + (Y ('&7分类: &f' + $meta.name)))
        Add ('        - ' + (Y ('&7完成奖励: &a+' + (Fmt $score) + ' 分')))
        Add ('        - ' + (Y ('&7进度 ID: &8minecraft:' + $path)))
        Add ('        - ' + (Y '&8&m------------------------'))
        Add ('        - ' + (Y '&7一次性加分，不参与分段折扣'))
        Add '    actions:'
        Add ('      all: ' + (Y 'sound: BLOCK_NOTE_BLOCK_BIT-1-0'))
        Add ''
    }
}

# ------------------------------------------------------------
# 输出
# ------------------------------------------------------------
$text = $script:out -join "`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($outFile, $text, $utf8)
Write-Output ('已生成: ' + $outFile)
Write-Output ('行数: ' + $script:out.Count)
Write-Output ('段位: ' + $ranks.Count + '（常规 ' + $regular.Count + ' / 额外 ' + $extraRanks.Count + '）')
Write-Output ('一般: ' + $weights.Count + ' / 方块: ' + $blocks.Count + ' / 生物: ' + $mobs.Count + ' / 成就: ' + $advTotal)
Write-Output ('成就分页数: ' + $advChunks.Count + ' / 总页数: ' + ($pageNo))
