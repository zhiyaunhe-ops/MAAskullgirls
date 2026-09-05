# SGM 全游戏探索报告（2026-09-05 凌晨）

> 背景：金币场 09-05 于 02:52 达标自动暂停（25,337,660 / 25,000,000 上限，连胜 15），bot 处于 PAUSED。
> 本报告为跑完后的全游戏界面探索记录：从 PF 编队页回大厅 → Mission 页四页签全记录 → 以任务为指引遍历各模式。
> 全部截图在本目录（`000_*` ~ `116_*`），共 112 张。所有操作仅探索+领取常规免费收益，未花费任何钻石/金币，未开始任何战斗。

---

## 0. 运行收尾状态

- 跑完时停在 PF 的 VS 编队界面（`000_current_state.png`）：左上返回箭头 + **主页(房子)按钮**可直接回大厅，不用逐级返回。
- 本场结算：总分 25,337,660，连胜 15（`000` 图 STREAK: 15）。

## 1. 大厅主页面（两屏横向菱形板 + 右侧竖栏）

大厅是一块**横向吸附翻页**的菱形板（约 2 屏宽，左右划有吸附点，无上下滚动），加上固定右侧竖栏：

- **左屏**（`004`/`005`）：REPLAYS(带!)、VERSUS、TRAINING、PRIZE FIGHTS（01D 倒计时）、GUILDS(6)、RIFT BATTLES(带!, 02D 倒计时)、BELLARINA CEREBELLA 立绘（头像徽章 471）
- **中屏**（`048`/`086`/`115`）：STORY（大菱形居中）、STORE(5)、EVENTS(16)、RELICS(851, 856-开掉的5)、MISSIONS(3, LIMITED! 26D 倒计时)、STASH、BACKSTAGE PASS(2, 26D)
- **右屏**（`002`/`003`）：STORE/EVENTS/RELICS/MISSIONS/STASH/BACKSTAGE PASS + BABA YAGA BEOWULF 立绘（941 钻石标签）——**这是最右端，再划不动**
- **右侧竖栏**（全程固定）：CABINET(1!)、COLLECTION(1!)、SOCIAL(7)、INBOX、REWARDS
- **左上角**：设置齿轮 + 玩家头像（LV79 进度条）；中上 XP+ 按钮
- 钻石美术图会轮换（EVENTS 菱形先是无头女骑士、后变 MARIE 立绘），倒计时实时走。

⚠️ 坑：
1. **home 按钮回大厅后的吸附位置不固定**（回过"中屏"也回过"左屏"），点菱形前必须截图确认当前位置，否则会点错入口（实测点到过 VERSUS/EVENTS，点到空隙则无效果）。
2. 菱形间隙是死区，点击无效果但也不算误触。
3. 回大厅时弹出过一次 **AUTUMN NOIR 秋季头像促销**（三档 HK$60.9/118/238，X 在右上角 `033`）——限时促销弹窗会自动出现。

## 2. MISSIONS 页（重点全记录）

入口：大厅 MISSIONS 菱形（LIMITED! 26D 倒计时挂在上方）。四个页签（`010`）：

### 2.1 DAILY OPS（`010`-`013`）
- 顶部：**积分轨道**——当前 70 分，里程碑箱 20/40/60/80/100；RESETS IN 21:56（每日重置）；右上 CLAIM ALL!。
- 任务列表（全部 11 条，按顺序）：
  1. Win a Prize Fight Match 1/1 — 可领 ×15
  2. Level Up a Guest Star or Reroll any Guest Star Stat 0/1 — GO ×25
  3. Level Up a Move or Reroll any Move Stat 0/1 — GO ×25
  4. Unlock a Skill Tree Node 0/1 — GO ×25
  5. Win a Story Mode Match 0/1 — GO ×15
  6. Complete a Rift Battle 0/1 — GO ×35
  7. Log In 1/1 — 已领 ×10
  8. Open a Relic 1/1 — 已领 ×20
  9. Open the Cabinet of Curiosities 1/1 — 已领 ×10
  10. Send a Gift 1/1 — 已领 ×15
  11. Win a Daily Event Match 1/1 — 已领 ×15
- 奖励统一是"伞币勋章"（顶部积分轨道的计数单位）。

### 2.2 GUILD OPS（`014`-`030`）
- 顶部：段位徽章 **GOLD**（13,160/16,000 进度，下一段 16,000），RESETS IN 21:55，CLAIM ALL!。
- 打开/滚动时会弹 **NEW REWARD TIER!** 段位展示框（GOLD 13160，OK 关闭，`015`/`016`）。
- 列表分四段：
  - **CLAIMABLE OPS**（可领未领）：
    - Participate in a Prize Fight match 1/1 → ×1 能量罐 + ×5 骷髅章
    - Win a match against a team with higher Fighter Score in Prize Fights 1/1 → 10k 金币 + ×10 骷髅章
  - **DAILY OPS**（公会日任务）：
    - Earn 35 GuildOps Points 30/35 → 16k + ×1 绿票
    - Participate in an Undying Battle 0/1 → 2k + ×5 章
    - Level up a character 0/1 → ×5 罐 + ×5 章
    - Win a match against a team with at least 2x higher Fighter Score in PF 0/1 → 15k + ×20
    - Earn 60 GuildOps Points 30/60 → ×1 + ×1
  - **WEEKLY OPS**（公会周任务）：
    - Win 1 Rift Battle Match 0/1 → ×1 稀有条 + ×10 章
    - Win 3 Rift Battle Matches 0/3 → ×1 + ×20
    - Win 5 Rift Battle Matches 0/5 → ×3 + ×40
  - **COMPLETED TASKS ✓**（已完成已领的长阶梯，按奖励梯度排列）：
    Earn 5 GuildOps Points ✓(2k+×1) → Complete or Skip a Daily Event ✓(2k+×5) → Send a Guild Gift ✓(2k+×5) → Reach Level 2 Undying Battle ✓(10k+×10) → Open any 10 Relics 10/10 ✓(10k+×40) → Claim Daily Event Tickets 5 times 5/5 ✓(50钻+×20) → Complete 1 Boss Node in Parallel Realms ✓(×1+×10) → Reach Level 3 Undying ✓(25k+×20) → Complete 2 Boss Nodes ✓(×1+×20) → Complete 100% of Accursed Experiments ✓(10k+×20) → Complete 3 Boss Nodes ✓(×1+×40) → Open any 20 Relics ✓(10k+×40) → Win vs ≥3x higher FS ✓(30k+×40) → Win vs ≥4x higher FS ✓(45k+×40) → Reach Level 5 Undying Battle ✓(50k+×40，列表末尾)
- 注：公会任务里**没有** "Reach Level 4 Undying Battle"（只有 L2/L3/L5）。
- GuildOps 积分来源=参加 PF、打 Undying、完成日常等（本条 30/35、30/60 已接近完成）。

### 2.3 ACCOLADES（成就，`031`）
左侧分类+完成度：COMBAT 17/17、COLLECTION 17/20、ADVANCEMENT 30/30、STORY MODE 13/16、EVENTS 35/37。右侧成就行（战斗行为类：Complete Egret Boot Camp、Benefit from 5 ENRAGE stacks at once、Inflict STUN 20 times、Gain ARMOR 10 times、Use REGEN buffs to heal for 150% total health…均带角色头像、已领勾）。

### 2.4 DEPLOYMENTS（派驻，`032`）
- 顶部：**DEPLOYMENTS LEFT TODAY 5/5**（每日 5 次派驻机会）。
- 三张卡：SOMETHING WICKED THIS WAY COMES（空槽+，15,000 XP，时长 15 分钟）/ DREAM WEAVERS（已派 2 人 1,429+17.7k，40,000 XP，ACCELERATE 01:45:23 进行中）/ DEPENDENT VARIABLES（3 空槽，75,000 XP，时长 16 小时）。
- 每卡右上 X 可取消；BONUS! 标注额外掉落（罐/ relic）。

## 3. 各模式探索（按任务指引顺序）

### 3.1 VERSUS（`034`-`037`）
两种玩法卡：
- **COMPETITIVE VERSUS**：规则=所有玩家用同一套 Fighters/Moves，**无 Signature/Marquee 能力、禁 Modifier**。
- **FREE-FOR-ALL VERSUS**：可用自己收藏的任意角色；随机匹配按所用队伍的 Fighter Score 拉相近对手。
- 卡片左右切换，点一次是居中、再点 PLAY!/RULES 才生效。

### 3.2 STORY（`049`-`056`）
- 两页签：**MAIN STORY** / **ORIGIN STORIES**。
- MAIN STORY 章节卡（难度箭头可展开 BASIC 之外的难度）：EGRET BOOT CAMP(100%) → A FISHBONE TO PICK(100%, ★18/27) → WHO'S THE BOSS?(★70/93) → GOING ALL IN(★13/111) → ASCENT OF A WOMAN(★5/60)。
- ORIGIN STORIES=每角色一篇（MEANER. BETTER. FASTER. STRONGER.、WULFAMANIA: BORN TO HOWL 0% 未打、BEATING THE ODDS、GROWING PAINS、LAST HOPE、MAIN ATTRACTION…），奖励含角色专属 relic。
- 对应任务：Win a Story Mode Match。

### 3.3 EVENTS（`039`-`047`，入口误点发现，即"活动轮播"）
横向轮播 20+ 张活动卡，顶部有活动票 CLAIM（绿骷髅票 ×27 / 橙票 3/4 两态）：
- 长周期：**UNDYING EVENT**（Crinty Kitty boss，总伤害 63,732,957，2D20H，PLAY/REWARDS）、**PARALLEL REALMS**（BASIC 0%，Area 1 敌队 FS 711-1,398）、**ACCURSED EXPERIMENTS**（MASTER 0%，奖励 ×5罐+×25章）
- 日常（每天 3 次 PLAYS REMAINING，21:46 重置，可 PLAY/SKIP!）：SWEATING BULLETS、DOUBLE FEATURE、TICKETS TO THE FUN SHOW、HOLODECK MAYHEM（5X XP 标）
- 需指定角色：BABA YAGA（REQUIRED FIGHTER，26D，奖 40钻+150k）、BELLARINA（EXPERT，26D）
- 按星期排的轮换卡（未解锁态）：SATURDAY: CATURDAY NIGHT FEVER / PIER PRESSURE / SATURDAY MORNING CARTOONS；SUNDAY: COSMIC ENCOUNTERS / A ROYAL AUDIENCE / SANGUINE SOIRÉE；MONDAY: AFTER SCHOOL SPECIAL / GRAVE SITUATION / GHOUL'S NIGHT OUT / HOLODECK HAZARDS / UNDYING EVENT(Undying Warrior / Doomsayer 两张)
- **公会周任务指向的三大玩法入口都在这里**：Undying Battle、Parallel Realms（Boss Node）、Accursed Experiments。

### 3.4 RIFT BATTLES（`058`-`061`）
- 进入要 CONNECTING 加载（`058`）。
- 主页：我方 GOLD 3 / **RIFT RATING 1000**，对手 (Hyok)Crossover LV75 ROOKIE 1000；WIN +30 / LOSS -30；STREAK 0（STREAK BONUS HP+0% ATK+0%，MULTIPLIER 1.0x）；MIN BATTLES COMPLETED 1/5；REWARDS / CLAIM!(绿票) / MY BASE / HISTORY 按钮。
- **SEASON REWARDS**（`060`）：赛季 2D21H 后结束（**每周一 10am PT 结束**，奖励发邮箱）；需打满 5 场且单场 ≥3000 分才有奖励；段位阶梯 TOP 91%-100%=BRONZE 4（钥匙×1+碎×225+金币×30+青罐×100）向上递增。
- **MY BASE**（`061`）：树状防守布阵（"Prepare your defense"），当前 4 组防守队 103.1k（紫三连）/55.2k（绿）/28k/29.4k，SAVE 保存。

### 3.5 GUILDS（`062`-`064`）
- 公会：**(鲜花与哭泣) 希尔加德墓葬花园**，ID 039M726DM，Private，29/30 人 Avg Lv 72，入会要求 LV40；公告含公会 Q 群 470540186 与 dc。
- 左栏：HOME / MEMBERS / LEADERBOARD / REWARDS / GIFTS(6) / SEARCH。
- **REWARDS**：GUILD OPS 与 UNDYING EVENT 两页签（都 2D20H 结束），"Rewards come from your final tier at season end"——段位卡 DIAMOND 16,000+(400k+×5+×5)、**GOLD 12,000+（CURRENT TIER，200k+×3+×3）**、SILVER 8,000+(100k+×2+×2)。
- **GIFTS**：OPEN / SEND ALL / CLAIM ALL(1/5)；会员行显示 SENT 盒数与可领盒数（阿笛、@mankoo!!lol、EvilMealworm…），右上角两个礼盒库存计数。

### 3.6 TRAINING（`067`）
与 PF 编队同款 UI：3 个出战槽 + 候选横列（拖拽换人），对手显示 ???（74.2k），FIGHT! 开打。练手机制。

### 3.7 REPLAYS（`068`）
- "观看最近战斗的录像"，行内 PLAY / SHARE / COPY ID，底部 SEARCH + Enter Replay ID 输入框，红心收藏 0/10。
- 官方注：**"REPLAYS are still in development. Playback may not be accurate."**

### 3.8 RELICS（`069`-`076`）
- 卡池类型（纵向列表）：**GUEST STAR RELIC**（75钻/单抽、750/十连+1 BONUS；10+1 券附带 1 UNIVERSAL RETAKE + 5x SHINY ODDS）、**HEADLINER RELIC**（300/3000钻，保底银/金/钻角色，**10/100 保底进度条**）、**STORMY RELIC**（元素池：金/银/铜 Air Fighter+Air Shards）、**LEGENDARY RELIC**（金/钻池）等。
- 右侧胶片条=最近出货记录（GRIN REAPER MARIE、WIND STALKER MS.FORTUNE、SCALE TIPPER FILIA、SNAKE CHARMER MARIE、RAW TALENT MINETTE、CLAWS OF DEATH BRAIN DRAIN、WILDCARD PEACOCK、FLOWER POWER FUKUA…）。
- **开箱流程**（`074`）：OPEN RELICS(n) → 仪式页"DRAG RELIC HERE"（把奖品拖进光环，或点右下 **OPEN n RELICS** 批量开）→ RESULTS! 结果页（BACK / SHOW STATS / **SELL ALL** / COLLECTION）。
- 实测开了 GUEST STAR 攒的 5 个（实际出 4 件：3★红罐、1★粉、2★蓝罐、2★银卡），RELICS 大厅计数 856→851 同步验证。

### 3.9 STORE（`077`-`085`）
左侧栏：FEATURED(3) / DAILY PASSES / LIMITED OFFERS(1) / DAILY DEALS(1) / BANK VAULT / SHARD EXCHANGE / **WEBHUB**（带外链图标，未点）。
- FEATURED：ALWAYS STRIKES TWICE! 10+1 包（保底钻/金/银；300 钻或 HK$23；限时 2d21h）。
- DAILY PASSES：**HEADLINER DAILY PASS 已激活（剩 23 天，x35 总量，NEXT BONUS IN 21:26）**；PREMIUM GUEST STAR RELIC DAILY PASS（6 天 HK$238）；MEDICI KICKBACK（30 天 200 万金币）。
- DAILY DEALS：EGRET RECRUIT PACK HK$38、EGRET ELITE PACK HK$158、ROYAL RENOIR PACK（限时 2D21H）。
- BANK VAULT：金币/钻石充值位（MINETTE'S TIP JAR 75钻→75k 金币 首购翻倍等）。
- SHARD EXCHANGE：钻/金 RELIC SHARDS 转换（CONVERT）。
- ⚠️ **DAILY PASSES 的 CLAIM! 按钮点了三次像素零变化**（均值完全一致），当前状态下无交互效果——可能当日已被用户领过或纯展示，待用户确认。

### 3.10 STASH（`087`-`089`）
纵向分区：
- CONSUMABLES：XP 12H×119、XP 4H×180、团子×304、鱼×827、茶杯×401
- CURRENCIES：金币 988,212（+45 万后 1,438,212）、钻石 18,647、铜钥匙 245、银钥匙 115、金钥匙 7、粉钥匙 0
- **SKILL POINTS**：六类角色技能点 60,342 / 90,891 / 35,847 / 40,750 / 86,333 / 63,080（=技能树与 TRIBUTES 货币）
- ELEMENTAL ESSENCES：风 370/1,000+精粹×4、火 115/1,000+×1、水 915/1,000+×0
- RELIC SHARDS：银/金/粉碎片

### 3.11 右侧竖栏
- **CABINET OF CURIOSITIES**（`090`-`092`）：金币/资源商店，4 页签。TRINKETS（金币价：银钥匙 80k、RINGLEADER RELIC 100k、XP TREATS×20 40k、**钻石钥匙 800k**、头像框等）；TREASURES（蓝水滴币，余额 501：FENRIR DRIVE 50、STARDUST×100 200、AIR SHARDS×100、银碎片×250…）；TRIBUTES（**用六类技能点币买招式胶片**：EXCELLEBELLA 25、IMPENDING DOOM 75、SEKHMET'S TURN 75…）。刷新周期 4h（NEW OFFERS IN 3h59m），可看广告 REFRESH。对应任务 Open the Cabinet of Curiosities。
- **COLLECTION**（`093`-`097`）：FIGHTERS 1032/1140。卡片网格**横向翻页**（纵向滑不动），按战力排序；底部 TIER LIST(外链)/CATALOG/分类页签(36/6 徽标)/FORTUNE/RECYCLE/筛选。
- **SOCIAL HUB**（`098`/`099`）：好友 37/50；黄礼盒×103、粉礼盒×97；FRIENDS LIST(7)/REQUESTS/ADD FRIENDS/BLOCKS；SEND ALL(0)/**CLAIM ALL(7，已代领成功→0)**/OPEN GIFTS(7，未打开)。对应任务 Send a Gift。
- **INBOX**（`100`-`103`）：INBOX/GUILDS 两子栏；GUILDS 空("No mail currently available"+REFRESH)。INBOX 有一封 **PF 赛季结算**：Medici Shakedown (9/2-9/4)，最终分 23,031,672 → **TOP 11%-25% 档，附件 450,000 金币，29 天过期——已代领**（余额 988,212→1,438,212）。
- **REWARDS**（`104`-`106`）：三页签。**WEB REWARDS**=DAILY WEB BONUS 月历（Day1-5 已领，Day6 待领，按钮 **CLAIM ON WEB!** 需走网页端，未操作）；**LOGIN REWARDS**=每日登录月历（26 天后重置=每月 1 号，Day1-4 已领，NEXT REWARD 21:08:42 后=Day5 25 钻）；**VIEWING PARLOR**=看广告换奖（**ADS LEFT 6 格，30 分钟冷却刷新**，WATCH 按钮，奖池含 50 钻/15k 金币等）。

### 3.12 BACKSTAGE PASS（`108`-`112`）
- 本季 **CIRQUE DU SLAY**（26D21H），等级 44+，双轨道：ALL ACCESS（付费）/FREE!。
- **GOALS** 页签（`109`）：进度 500/1,000 BP XP（领完后）；DAILY GOALS 0/4→**2/4**（Participate in 2 PF Matches 2/2 ✓+250、Open a Relic 1/1 ✓+250——两个都已代领；Collect 100% Rewards in Daily Ops 0/1 +500；Complete Daily Guild Ops 0/1 +500）；WEEKLY GOALS 13/17（Spend 500 Skill Points 500/500 ✓、MONTHLY Spend 500,000 Coins ✓）。
- REWARDS 轨道里程碑：45 级 200 钻、46 级 150,000 金币（付费轨）/50,000（免费轨）、47 级 ×5 场记板、48 级 ×150/×50 碎片。

### 3.13 PROFILE & 设置（`113`-`116`）
- PROFILE：梁文峰 LV79，**GOLD 3**，User ID 0hd1-1230l，公会信息，**Collection Power 2,929,403**，TOP FIGHTERS 网格，SET AVATAR / CHANGE NAME 按钮。
- 设置齿轮（左上）：**点了 3 次无响应**，未打开 OPTIONS——此前 bot 开发时已存档过该菜单（docs/screenshots/options 相关 + options_x.png 模板），不影响本次探索完整性，待用户自查。

## 4. 本次代领/变更清单（全部为常规免费收益）

| 动作 | 结果 |
|---|---|
| 开 GUEST STAR RELIC ×5 | 得 3★红罐/1★粉/2★蓝罐/2★银卡，大厅 RELICS 计数 856→851 |
| SOCIAL 好友礼物 CLAIM ALL(7) | 7 个礼盒入库存（未打开，OPEN GIFTS 7） |
| INBOX PF 赛季奖励 450,000 金币 | 已领，金币 988,212→1,438,212 |
| BACKSTAGE PASS 日常目标 ×2（+250×2） | BP XP 0→500/1,000，DAILY GOALS 2/4 |
| 商店 DAILY PASSES CLAIM | 点击无响应，未领取（见 3.9） |

未动：任何 PLAY/战斗、钻石消耗、OPEN GIFTS、VIEWING PARLOR 广告、WEBHUB/CLAIM ON WEB 外链、RECYCLE、FORTUNE。

## 5. 对 bot/脚本有价值的观察

1. **home 按钮回大厅后吸附位不固定**，任何自动化从大厅点菱形前要先截图定位。
2. 弹窗家族新增：AUTUMN NOIR 促销弹窗（回大厅触发）、NEW REWARD TIER 段位框（GUILD OPS 滚动触发）、REPLAYS 说明框——都属"只关不改流程"类。
3. CONNECTING 加载页出现在进 RIFT/STORE 时，自动化要留加载等待。
4. COLLECTION/编队候选区是横向翻页；MISSIONS/RELICS/STASH/GUILD OPS 是纵向滚动；GUILD OPS 长列表尾部惯性大，小步滚才能不漏行。
5. 日常免费收益点（可脚本化的"每日例行"）：MISSIONS CLAIM ALL、GUILD OPS CLAIMABLE、SOCIAL 礼物、INBOX、BACKSTAGE PASS GOALS、REWARDS-LOGIN、商店 DAILY PASS（若可点）、EVENTS 活动票 CLAIM。

---

# 第二轮探索补录（同日 03:00-04:00，用户纠正与验证后）

> 用户关键规则：**CLAIM/按钮分两色——蓝色=可点可用，灰色=不可用；通用于全游戏，凡偏灰偏暗的元素基本都是禁用态。**（商店 DAILY PASSES 的 CLAIM 是灰的所以点了没反应；UNDYING 奖励页顶部活动票 CLAIM 在领取后由蓝变灰。）

## 6. DAILY RELIC 日常开箱流程（已实机验证）

- 位置：RELICS 页向下滚，**DAILY RELIC** 区块（"Claim a special reward! (including a chance to get the exclusive SILVER VALENTINE OH MAI)"），当时积压 **272** 个。
- 流程：点 **OPEN RELICS(272)** → 开箱仪式页（"DRAG RELIC HERE"，批量按钮显示 **OPEN 10 RELICS**）→ **向上划（把奖品拖入光环）=默认开一个** → RESULTS（BACK/SHOW STATS/SELL ALL/OPEN ANOTHER/COLLECTION）。
- 实测开 1 个：272→271，出银色词条 relic（METER GAIN +22% / ATK 40，SHOW STATS 可看词条）。**对每日任务，开一个即满足。**
- 同区还有 **GOLD FIGHTER RELIC**（集 1000 金碎片免领一次，88% 进度；CLAIM RELIC 蓝色可领）与 **SILVER FIGHTER RELIC**（1000 银碎片，灰色未达标）。
- ⚠️ **误操作记录**：把 GOLD FIGHTER RELIC 当目标误开了一个（上划手势在金池仪式页执行）。结果开出了 **FAVOR**（Fortune's Favor 许愿单保底角色），非白费——见下。

## 7. FORTUNE'S FAVOR（金池许愿单）

- 金角色 relic 开箱页右上角面板："When you open a gold fighter you will be GUARANTEED one of your selections"，5 个指定变体（铅笔可改）。
- 每开一个金角色 relic 必出许愿单之一；底部显示**下一轮保底进度**（实测开完后 21.5%）。
- 误开产出即 FAVOR（金圈头像 + 三骷髅标记）。

## 8. 技能树（任务 Unlock a Skill Tree Node 的所在地）

- 入口：COLLECTION → 点角色卡 → 角色详情 INFO 页签 → **SKILL TREE 按钮**（旁边数字=该角色类型可用的技能点，如 78,346）。
- 结构：**六辐条圆形轮盘**——拳（攻击）/沙漏闪电（资源）/心（HP）/齿轮+（辅助）/准星（暴击）/蓝星+金方块（特殊）。支持**双指捏合缩放**（向中间收=缩小；adb 单指 input 做不了，已用 MAA `post_touch_down/move/up(contact=0/1)` 双指实机成功）。
- 货币：顶部 **粉红小猪图标实为"钻钥匙"**——解锁节点的专用资源（当前 0 个）；旁边大数字是六类技能点；右下角 **100%=该角色树的完成度**（X-BOT 已点满，无解锁风险）。
- 角色详情页其他：INFO（POWER UP、SIGNATURE/MARQUEE/PRESTIGE 三页签、签名能力文案）、STATS、**GUEST STARS**（客串道具，等级+星级+EQUIP——"Level Up a Guest Star"任务在此）、**MOVES**（招式胶片格，等级角标+15心+LOADOUTS/EQUIP——"Level Up a Move"任务在此）。

## 9. 其余页面补录

- **STORY 难度**：章节卡 BASIC ▶ 箭头可切换 **ADVANCED**（独立星数 ★0/27 与奖励表，BASIC/ADVANCED 交替循环）。卡轮播同样"首点居中、再点生效"。
- **EVENTS**：活动票 CLAIM 领取后 27→28 变灰；**PARALLEL REALMS 卡有难度切换**（BASIC↔NIGHTMARE，NIGHTMARE Area 1 敌队 FS 100.2k-149.1k）；**UNDYING EVENT → REWARDS** = MILESTONE REWARDS（个人伤害里程碑，全部 CLAIMED）+ GUILD REWARDS 双页签。
- **GUILDS**：MEMBERS 页显示全员 GuildOps Points（710/690…）与段位徽章，右上按积分排序；**LEADERBOARD**（GUILD OPS/UNDYING EVENT 两榜，均 2D10H 结束）：本会 **#223 / 13,870 分**，榜首 30,565。
- **RIFT HISTORY** = DEFENSE HISTORY（本季防守记录）：当前"还没人打过你的基地"。
- **CABINET THEONITE 页签**：钻石商店（水元素碎片×250=280钻、RETAKE RELICS=60/120钻、钻石碎片×50=400钻、KEY RELIC、头像等）。
- **大厅 XP+ 按钮** = DOUBLE XP BOOST 状态/提示（"Grants Double XP after all fights."，带倒计时），点它只出 tooltip。

## 10. 第二轮期间的账号侧观察（用户自己操作所致，仅记录）

- 金币 1,438,212→20,332、钻石 18,647→17,187（用户自行消耗）；RELICS 计数 851→835→开箱后 853/1036（COLLECTION 计数 1032→1036）。
- MISSIONS 红点 3→无（用户已自行领取）；GUILDS 徽章 6→8；PF 赛季计时 01D:21H→01D:11H（**新一轮 Prize Fight 已开始**，即 Medici Shakedown 结束后滚动了新周期）。
- 大厅 EVENTS 菱形美术图随时间轮换（女骑士→MARIE 立绘等），菱形本身功能不变。
