# MAAskullgirls — SGM Prize Fight 自动化脚本（基于 MAAFramework）

Skullgirls Mobile 的 Prize Fight（竞技场）自动刷本脚本。基于 MAAFramework v5.12.3
的 Python 绑定（MaaFw）驱动 MuMu 模拟器，实现 **选对手 → 编队 → 自动战斗 → 结算领奖** 的无人循环，
带 WebUI（实时日志 / 截图 / 交互图表 / 设置 / 起停控制）。

- 启动：`python tools/pf_bot.py`（进程常驻，WebUI 里点 **开始** 才开跑）
- WebUI：http://127.0.0.1:8790 —— **运行**页签（日志+截图）、**图表**页签（总分/收益/连胜）
- 分辨率约定：**1280x720 横屏**（所有坐标/ROI 都基于此）
- 关键界面截图存档：`docs/screenshots/`（filter_panel / pf_hub / server_error_* / options_menu 等）

---

## 1. 环境与依赖

| 项 | 值 |
|---|---|
| 模拟器 | MuMu 12，ADB `127.0.0.1:16384`（实例1默认端口） |
| MuMu 自带 adb | 安装目录下 `nx_main\adb.exe`（本机实际路径写在 `config.json`，该文件不入仓库，见 `config.example.json`） |
| 设备信息 | NTH-AN00（MuMu 默认伪装型号），Android 12，1280x720 横屏运行 |
| 游戏语言 | ⚠️ **必须英文界面（English）**：OCR 判据（SERVER ERROR/PLAY!/战力数字等）全部基于英文 UI，其他语言识别不到 |
| 框架 | MAAFramework v5.12.3 官方包解压在 `vendor/`（参考文档与 sample；运行用 pip 包二进制） |
| Python 绑定 | `pip install MaaFw==5.12.3`，另需 numpy / opencv-python |
| 图表库 | Chart.js 4.4.3 本地托管 `tools/static/chart.umd.min.js`（不走 CDN） |
| 推理设备 | `Resource.use_cpu()` 强制 CPU（双显卡机器 DirectML 有枚举坑，rec-only OCR 开销极低） |
| WebUI 端口 | **8790**（唯一来源 `pf_env.WEBUI_PORT`；本机 `config.json` 加 `"webui_port"` 可覆盖）。此前是 8787，本机该段被别的服务抢占后迁走，见 §8-19 |
| 游戏数据 | `tools/data/variants.json`（306 变体：element 0-5、base 角色，内置副本）；`sgm/` 为可选本地克隆（`github.com/Krazete/sgm`），存在时提供 WebUI 元素/角色图标并优先读取其数据 |

### ⚠️ 本机必踩的坑：anaconda 旧版 CRT

anaconda 根目录带 2020 年的 `msvcp140/vcruntime140`（14.27），Windows 解析 DLL 依赖时
**优先搜索 python.exe 所在目录**，MAA 的 `opencv_world4_maa.dll` 初始化失败
（WinError 1114，仅 Python 进程内失败，PowerShell 宿主正常，极难排查）。
修复：入口脚本在 `import cv2/maa` 前调用 `pf_env.preload_msvcrt()`（显式加载 System32 新版 CRT）。

---

## 2. 目录结构

```
MAAskullgirls/
├── PF_BOT.md                      # 本文档
├── docs/screenshots/              # 关键界面截图存档（筛选面板/hub/错误弹窗等）
├── tools/
│   ├── pf_env.py                  # CRT 预载 + 连接参数 + BotState(含设置与历史)
│   ├── pf_vision.py               # 纯 cv2 视觉分析（可离线测试）
│   ├── pf_bot.py                  # 主程序：监督循环 + 状态机 + 规则/计分/拖拽
│   ├── pf_webui.py                # WebUI（运行/图表页签、设置、起停）
│   ├── pf_store.py                # 场次/计分数据层（sessions.json + score_log.csv）
│   ├── jjc_store.py               # JJC 日程数据层 + 场次×规则版本账本（见 §6.11）
│   ├── pf_scene.py                # 场景导航：MuMu→游戏→大厅→PF hub / explore 扫分 / center 居中
│   ├── pf_schedule.py             # 定时调度：按时间触发 run_pf/stop_pf/explore（实验版）
│   ├── static/chart.umd.min.js    # Chart.js 本地副本
│   ├── connect_mumu.py / screencap.py   # 连通性/截图小工具
├── assets/
│   ├── interface.json             # PI V2 骨架（后续接 MaaPiCli 用，当前未走此链路）
│   └── resource/base/
│       ├── pipeline/sample.json
│       ├── image/pf/*.png         # 模板 19 张（见 §5.4）
│       └── model/ocr/             # det.onnx(v4 zh) + rec.onnx(v4 en_us) + keys.txt
├── sgm/                           # SGM 图鉴数据（元素定义、变体→元素/角色映射）
├── vendor/                        # MAAFramework 官方包
└── debug/
    ├── maa/debug/maafw.log        # MAA 框架日志（排障第一入口；自按 16MB 轮转 .bak）
    ├── debug/maafw.bak.*.log      # 轮转备份（清理线程只删这些最旧的）
    ├── pf/run/<时间戳>/           # 每次运行全程截图 NNNN_标签.jpg
    ├── pf/score_log.csv           # 每场计分记录
    ├── pf/sessions.json           # 场次配置（含分数上界/休息/总分）
    ├── pf/jjc/                    # JJC 日程数据与版本账本（见 §6.11）
    │   ├── snapshots/<YYYY-MM-DD>.json  # 每游戏日一份快照（含 raw 证据链）
    │   ├── latest.json                  # 指向最近一次快照
    │   └── versions.json                # 场次×规则版本账本
    ├── pf/schedule.json           # 定时任务配置（jobs 空=未启用）
    └── pf/schedule.log / schedule_state.json   # 调度日志 / 触发去重记录

**体积控制**（pf_env.cleanup_debug，2026-09-03）：bot 启动即清一次 + 每 10 分钟守护
线程。图片 `debug/pf/run` 总量 ≤150MB（按目录从旧到新整删、保护当前目录；仍超则删
当前目录内最旧帧）；全部 .log ≤50MB（只删最旧的 maafw.bak.*，活动日志不碰）。
```

---

## 3. 游戏界面与坐标（1280x720）

### 3.1 对手选择页（界面指纹：绿色 REFRESH 按钮）
- 右侧竖排 3 张对手卡：战力左上（白字深底）、带火倍率框右上（红火焰徽章 x1.5/x2/x2.5）
- 左面板：STREAK / MULTIPLIER / SCORE / NEXT REWARD
  - 总分 ROI `SCORE_ROI=(132,336,275,370)`，连胜 ROI `STREAK_ROI=(280,200,352,238)`
- 点击坐标：三张卡中心 `(1000, 235/412/590)`；点卡 → CONTINUE → **等对手页消失**（防重复选人）

### 3.2 VS 页
- 右上橙 **FIGHT!** 按钮 `(1075,38,155,54)`；中下 TEAM 菱形中心 `(637,555)`
- 点 TEAM 进编队；队伍有人能量不足会先弹 ENERGY REFILL，关掉弹窗自动进编队页

### 3.3 编队页（指纹："DRAG DESIRED FIGHTER TO A SLOT" 横幅 `(430,405,850,465)`）
- 3 个出战槽：**卡面扇形**（铭牌中心 x≈[130,355,557]，拖拽落点，y=240）；
  **能量钉条是独立等距层**（起点 x=65，层间距 192，钉距 13.5，行带 y 349-369）
- 底部候选横列：卡距 **198px**，卡1钉条中心 x≈116，行带 y 679-701，按战力降序，不含槽内角色
- **筛选按钮**：右侧中部蓝色六边形 `FILTER_BTN=(1215,395)`，详见 §6.4

### 3.4 弹窗（多种，X/按钮尺寸位置各异，均有对应模板）
| 弹窗 | 触发 | 处理 |
|---|---|---|
| ENERGY REFILL | 能量不足点 TEAM/FIGHT | 点 X（大号 54px） |
| KEEP STREAK? | 战败后询问恢复连胜 | 点 X（小号 46px），**绝不点 CONFIRM/WATCH AD** |
| 服务器错误（紫 OK） | "difficulty reaching our servers" | 点 OK |
| 服务器错误（绿 RETRY） | 同上，CANCEL+RETRY 双按钮 | 点 **RETRY** |
| SERVER ERROR（红 OK） | 服务器故障 | OCR 兜底检测 → 点标题下方 OK |
| OPTIONS 菜单 | 误触左上齿轮 | 点右上 X（盲目按返回的后果，见 §8-10） |
| 角色详情页 | 拖拽误触 | 按左上返回键（STATS 标签模板检测） |
| 结算链 2-3 页 | 每场胜利 | 循环点紫色 CONTINUE |
| 筛选面板 | 规则筛选/清除 | 驻留式点击 + X 模板验证重试（见 §8-13） |

### 3.5 PF 主页面（hub，指纹：PLAY! 按钮 `(500,400,780,530)`）
Fight 选择轮播页（每张卡 PLAY!/REWARDS/CLAIM! + 总分显示）。延迟导致误退出会落到这里，
状态机检测到后**点中心 PLAY! 重新进入**。

---

## 4. 能量规则（用户确认）

- 卡底黄色闪电 = 当前能量；**≥ 出战门槛（默认 4，WebUI 可调）即可出战**
- 红钉只是"不够下一场"的视觉标记，不参与判定；实测每场 -4（10 → 6 → 2）
- VS 页烧瓶变暗 = 同状态的表现

---

## 5. 视觉方案（pf_vision.py）

### 5.1 对手页：火框 + 数字
- 火框：红色像素占比 ≥0.04（实测有火 0.17-0.26 / 无火 0.03）
- **倍率**：火框徽章**动态定位**（右上区最大红色连通域 + 角部约束，排除橙红头发/红心徽章）
  → 裁下方 70%（去火焰尾巴）→ OCR `expected=["x1","x1.5","x2","x2.5",...]` → 正则取数
- **战力**：固定 ROI OCR + 正则解析（`'34.C'→34`、`'41.2k 3N'→41200`、允许 "3.6 k" 带空格）

### 5.2 编队页：能量钉
- 严格高亮黄掩码 `R≥240 & G≥200 & B≤105`（金卡淡黄底 (231,231,132) 不通过，对底色免疫）
- 列黄色占比 → 游程计数（阈值 0.08，游程 2-9px）；峰值 <0.12 判 0

### 5.3 OCR 模型
- `model/ocr/`：det.onnx **必须存在**（only_rec 也依赖），rec 用 ppocr_v4 en_us，det 用 v4 zh_cn

### 5.4 模板清单（assets/resource/base/image/pf/）
| 文件 | 用途 |
|---|---|
| pf_refresh_btn / pf_continue_btn | 对手页指纹 / 继续按钮 |
| vs_fight_btn | VS 页指纹 + 点击 |
| drag_hint | 编队页指纹 |
| energy_x / streak_x / options_x | 三种弹窗关闭（尺寸各不同：54/46/48px） |
| result_continue | 紫色结算 CONTINUE |
| hub_play | PF 主页面 PLAY! |
| detail_stats | 角色详情页 STATS 标签 |
| srv_ok / srv_retry | 服务器错误两种按钮 |
| pip_red / pip_yellow / pip_roster / pip_slot | 钉模板（调研产物，备查） |
| hall_prize_fights | 大厅 PRIZE FIGHTS 菱形（pf_scene 全屏搜，吸附位不固定） |
| scene_popup_x | 大厅限时促销弹窗 X @`(950,30,1240,180)`（options_x 不匹配促销款） |
| battle_spd_1x / 2x / 3x | 战斗速度泡 @`(600,565,690,645)`，th=0.85（同位≥0.95、1x↔3x 串扰≤0.76、无泡≤0.14） |

---

## 6. 机器人流程（pf_bot.py）

### 6.0 进程模型
`run()` 是**常驻监督循环**：WebUI 可随时 开始/暂停，进程不退出。
- 暂停期间仍每 2s 清理阻塞弹窗（服务器错误 / X 类），防止屏幕卡死在错误弹窗
- 状态机主体在 `step()`：截图一次 → 按优先级处理

### 6.1 状态机（step，优先级从高到低）
1. **弹窗 X**（能量/streak/OPTIONS；遮罩压暗背景但模板匹配对亮度不敏感，必须最先判）
2. **服务器错误**（紫 OK / 绿 RETRY / 红 OK-OCR 兜底）
3. 角色详情页 → 按返回键
4. PF 主页面（hub）→ 点 PLAY!
5. REFRESH → 对手选择页 → `track_score` 计分 → `pick_opponent`
6. DRAG 提示 → 编队页 → `fight_flow`
7. FIGHT! → VS 页 → `fight_flow`
8. 紫色/橙色 CONTINUE → `handle_results`
9. 未知界面 → 等 3s → **交替按 左上返回键 / 右上关闭 X**（某些界面左上是设置齿轮，单按返回会开 OPTIONS 卡死）；已知界面时恢复计数清零

### 6.2 选对手
有火框 → 火框组内倍率最大（倍率读不出排后按战力）；无火框 → 战力最低。全程 OCR 结果进日志。
战力 OCR 值 < 100 时视为 k 后缀被丢，自动 ×1000（如 87 → 87,000）。

### 6.3 编队修正（fix_team）
1. **首次编队归位筛选**（2026-09-10）：每次开始运行后的首次 `fix_team` 按 WebUI
   「喜爱筛选」开关 `set_filter`（开=点亮爱心芯片，关=清空），一并清掉上次运行
   残留的游戏内筛选（游戏内筛选状态跨运行持久）；**规则有无均执行**（此前只在
   无规则时清空，规则模式下首次筛选既不清残留也不吃喜爱设置）。每次(重)开始
   运行时复位该标记——出错/暂停后再开始会重新归位，中途改设置下一局生效
2. 归零滚动（右滑 ×4）
3. **规则判定**（见 §6.4；`_rule_done_fight` 锚定场次号，每场只判/替换一次）
4. 能量替换循环：**规则槽(1号)能量不足 → 直接优先补合规角色**（`refill_rule_slot`，
   不等能量弹窗，也先于普通槽——2026-09-03 按用户要求改；此前绕道"点 FIGHT 等弹窗
   再补"多花 ~15s 且让非规则槽插队）。随后槽位 <门槛 → 候选区从左（战力优先）找
   达标者拖入 → 复读验证；未生效 → 归零候选列重试 + 按失败次数微调落点（4 档偏移）；
   翻页上限 30
5. 全翻完仍无 → 停止报错（能量随时间恢复，重启/等待后继续）
6. 能量弹窗分支保留为兜底：正常流程全 slots ≥门槛后点 FIGHT 不应再弹窗

### 6.4 PF 规则筛选系统（仅配置了规则才运行，未配置零开销）
- **规则与 PF 场次绑定**（见 §6.5a）：WebUI **PF规则 按钮组**（nav 行右侧）编辑的是
  当前选中场次绑定的规则，未选场次/运行中时整组禁用；点选即生效、再点当前规则取消。
  按钮组：关 / 六元素按钮（sgm 元素图标）/ 12 角色金圈徽记按钮（= 类别 c1-c12，
  sgm MasteryIcon）。素材由 `/sgm/...` 路由直接托管 `sgm/image/official/`
- **合规判定 = FIGHT 按钮颜色**（2026-09-03 实测定稿）：游戏自身以 FIGHT 置灰提示
  不满足——按钮中心 ±(65,18) 区域高饱和亮色(S≥100,V≥120)占比 ≥15% 判满足
  （橙实测 ~55%/S中位251，灰 ~0%）。注意灰色同时covers"场上有人能量<门槛"，
  因此能量弹窗的自愈也复用该信号（见 §6.3）。类别规则 sgm 无数据，恒走筛选替换
  —— **而且游戏里压根没这条限制：PF 只限定元素，角色场/金币场/月场都不限定**
  （2026-09-16 用户确认，见 §6.12.7）。填 `{"type":"class"}` 只会让 bot
  每场都空转筛选替换循环，**自动路径已硬拦，别手工绑**。
  ~~废弃方案~~：OCR 铭牌名查 variants.json **结构性不可行**（键是内部代号 `fTrap`
  非显示名；显示名在条目 `fandom` 字段）；纯框色识别**不可靠**（钻石稀有度粉框
  覆盖元素色，如实测 BELLARINA；光/中性图标同为白色）
- 流程（用户定义）：
  1. **判定** FIGHT 颜色 → 灰则进入替换；每场只判一次（`_rule_done_fight` 锚定场次号）
  2. 不满足 → `refill_rule_slot`：筛选面板（清空 → 规则芯片 → 喜爱）→ 关闭(带验证) →
     **拖最左达标者进槽1** → 再筛选(仅喜爱)还原 → 复验 FIGHT，仍灰则继续拖槽2、槽3。
     **筛选结果用候选列复验**（2026-09-03）：关闭+归零后检查首张候选卡左缘框条的
     元素色占比（ELEMENT_HUE，阈值 15%；light/neutral 不可分辨不验证）——不符=清空/
     点亮被面板吃掉（芯片是开关，点亮会变切换），自动重开面板再筛一次。
     ⚠️ 阈值别贴着实测值画：初版 30% 恰好卡在实测 537px（阈值 540）差 3px 误杀
     满池风角色、误报"无能量停止"；实测绿 ~43%、错元素 0%，15% 两侧余量都足。
     离线回归：火框首卡火命中 86%、风/水/暗 0%
  3. **只有规则槽(1号)补合规角色，2/3 号槽做纯能量替换（元素不限，喜爱筛选、战力
     优先）**——2026-09-03 用户纠正：此前弹窗自愈对所有缺槽都补风，队伍慢慢变成
     三个风，加速耗干"风∩喜爱"小池子。fix_team 直补路径见 §6.3-4；
     FIGHT 后弹窗分支兜底时同样只对槽1走 refill_rule_slot
  4. fight 前、点 FIGHT 前各复验一次 FIGHT 颜色；能量替换破坏规则则重做（限 3 次）
- 筛选面板芯片坐标（见 docs/screenshots/filter_panel.png）：
  `FILTER_BTN=(1215,395)` 清空`(272,126)` 关闭`(1228,85)` 爱心`(616,237)`；
  元素芯片 x=[197,302,407,512,616,721] y=370（火/水/风/光/暗/中性）；
  类别芯片 12 个 = 同 x 列 × y 505（类别1-6）/ y 594（类别7-12）
  （c1-c12 按游戏面板顺序：Annie/Beowulf/BigBand/BlackDahlia/Cerebella/Double/
   Eliza/Filia/Fukua/Marie/MsFortune/Painwheel，与 sgm MasteryIcon 一一对应）

### 6.5 计分与连胜
- 每次回到对手页：读总分 + 连胜；**采样锚定场次号**（fight_no 变了必采样，
  **失败场收益 0 也照记**——连胜终结表现为 0 收益柱 + 连胜阶梯跌落）
- 达到 **目标总分**（WebUI 设置）→ 自动暂停（状态 PAUSED，可再点开始恢复）
- 每场追加 `debug/pf/score_log.csv`（time, fight_no, score, delta, streak, session）；
  启动时按列位置预载（旧文件表头缺 streak 列/旧行缺 session 列也能正确解析）

### 6.5a PF 场次系统（tools/pf_store.py，数据层与操作逻辑解耦）
- 点 WebUI **开始**：已选场次则**直接以当前场次开始**；未选过才弹场次弹窗
  （选既有场次 / 新建：名称+规则+分数上界+每N场休M分 / 重命名 / 两段式删除，
  Default 不可删）。弹窗另有**仅选择**：只绑定不开始，回主页改规则/上界/休息后再开
- 顶栏 **目标总分 / 每N场休息** 随场次（编辑即写入当前选中场次并同步运行状态）；
  未选场次或运行中禁改；**能量门槛随场次**（同上界/休息），喜爱为全局设置
- **子场次**：周期性分类的每一期建一个子场次——弹窗选中父场次 → **建子场次** →
  自动命名"父名 MM-DD"（重名加 -N 后缀），继承父场次规则/上界/能量/休息；
  采样/CSV 归子场次；弹窗列表中**子场次紧跟父场次**（缩进+左侧蓝线），父场次带「N期」徽章；删父不删子（子变顶级场次）
- 场次持久化于 `debug/pf/sessions.json`（id/名称/绑定规则/休息配置 rest_every·rest_minutes/
  **总分 score**）；总分随每次采样滚动更新，老场次启动时从各自最后一个采样**倒推**回填；
  **历史数据全部归
  id=default 的场次（无规则）**，该场次可改名不可删
- **运行中锁定**：不允许切换/修改/删除当前场次，规则按钮组禁用；暂停（同一场次）
  再续跑不重置基线，换场次开局则 fight_no/采样基线/规则替换标记全部重置
- 计分按场次分流：`ScoreTracker` 管采样基线（换场次自动重置），`ScoreStore.record`
  按场次入内存历史（每场次上限 3000 点），CSV 追加 session 列
- 图表页：顶部场次 chips，点名称=只看该场次（默认当前场次），点＋/－=加入/移出对比；
  **对比模式**下总分图变各场次收益累计曲线（以各自首个采样为 0 起点）+ 指标对比表，
  单场收益柱状图隐藏
- 已知取舍：删除场次后其内存曲线移除，CSV 原始行保留（重建同名 id 不可恢复，属预期）

### 6.6 拖拽与滚动
- 拖拽用触点原语：按下 → 分段移动 → **落点停顿 350ms** → 抬起
  （`post_swipe` 连续滑动+立即释放会在窄判定槽位脱靶）
- 拖后复读槽位验证：未生效 → 归零候选列（失败拖拽会滚动列表）+ 落点微调重试
- 候选列滚动：分 5 步移动 + 末段停顿再抬起（防惯性甩动过冲）

### 6.7 WebUI
- **运行页签**：实时日志 + 最新截图 + 状态徽章 + 总分/连胜 + 开始/停止按钮 + 设置项
- **图表页签**（Chart.js 本地托管）：
  - 总分曲线（渐变面积+平滑）、单场收益柱（绿/红）、连胜阶梯；全部可悬浮查看采样点详情
  - 统计卡：结算场次 / 胜率 / 总收益 / 场均收益 / **每分钟收益** / 当前连胜 / 最高连胜 / 平均连胜
  - 每分钟收益口径：只累计相邻采样间隔 ≤3 分钟的**活跃时段**（排除停机空档），秒→分 ÷60
- 设置项：目标总分（达到自动暂停）、能量门槛、喜爱筛选开关（全局；每次开始运行
  后的首次编队据此在游戏内勾选/清除喜爱筛选，见 §6.3-1）；
  **休息配置（每 N 场休 M 分钟）跟随场次**（2026-09-03）：设置条的
  "每 N 场休 M 分钟"输入框编辑的是当前选中场次（与 PF 规则同模式，运行中禁改），
  开始时载入，换场次重置休息计数与倒计时；场次弹窗列表每行显示"休N场×M分"。
  注意：**休息计数在进程重启时归零**（fights_since_rest 是内存态），频繁重启则
  一直数不满 N 场
- 接口：`/api/state`、`/api/history`、`/api/start`、`/api/stop`、`/api/settings`、`/static/*`
- **模拟器控制**（2026-09-21）：头部「启动 MuMu / 启动游戏 / 关机」+ 状态灯
  - `GET /api/mumu`、`POST /api/mumu/launch|game|shutdown`；启停都是秒级动作，
    后端一律丢后台线程（幂等锁 `_MUMU_BUSY`），进度只回写到运行日志，不卡 HTTP
  - 唯一实现在 `pf_env.py` 的 `mumu_*`，`pf_scene.ensure_mumu` 也转发过去 ——
    **关模拟器只能走 `MuMuManager control -v 0 shutdown`**（强杀 MuMuNxMain.exe
    会被 MuMuNxService 以 --from-oem 拉回；该实例还挂着用户另一个 MAA 的自启）
  - 状态判据要兼容两种字段集：MuMu 12.0 未启动时 `info -v 0` 只有
    `is_android_started` 布尔位，没有 `player_state`（见 `mumu_state`）
- **达标自动关模拟器**（2026-09-21）：头部「达标关模拟器」开关
  （`STATE.close_mumu_on_goal`，**默认关** —— 关模拟器是有副作用的外界动作）。
  总分达 `score_target` 时照旧暂停，开关打开则再关 MuMu；每次运行只做一次
  （`_goal_closed`），且只有「当前分 < 目标」才复位，避免恢复运行后立刻又关一次
- **界面主题**（2026-09-21）：6 套（午夜蓝 / 极光绿 / 熔岩橙 / 霓虹紫 / 樱花白 / 素纸米），
  头部「◑ 主题」切换，存 localStorage，首屏由 `<head>` 内脚本预置 `data-theme` 防闪。
  配色全部走 `[data-theme]` 的 CSS 变量（加主题 = 加一个变量块，不动选择器）；
  **canvas 不吃 CSS 变量**，Chart.js 由 `applyChartTheme()` 读变量后 `update()`

### 6.8 首场战斗 AUTO/3x 自检（ensure_battle_auto，2026-09-06）

用户指路：战斗界面底部中间有**脑子图标**，点一下=自动战斗；开启后上方出现**速度泡**，
点一下升一档（1x→2x→3x）。两项设置游戏内**跨场持久**，故每进程只在首场检查一次
（`_battle_auto_checked`，fight_flow 点 FIGHT 后、wait_battle_end 前触发）。

- 时机：点 FIGHT 后 `sleep 1.8s`——开场介绍画面 ~2.5s 人物不动，是唯一安全点击窗口
- 脑子亮灭：`(620,670)-(660,710)` HSV V 均值，亮 ~101 / 灭 ~49，阈值 75；灭则先点脑子
- 速度泡：三模板匹配 @`(600,565,690,645)` th=0.85，非 3x 则点 `(640,605)` 升档并复验
- 失败只告警不停跑；识别不到泡但脑子亮 → 记"跳过提速"

### 6.9 场景导航（tools/pf_scene.py，2026-09-06）

把"MuMu→游戏→大厅→PF hub"冷启动链脚本化，复用 PfBot 的 controller/tasker/match_tpl/ocr：

- `goto`：ensure_mumu（MuMuManager 轮询 `player_state=start_finished`，**必须先于
  PfScene 构建**，否则 adb 连接先炸）→ monkey 起 `com.autumn.skullgirls` → wait_hall → hub
- `explore`：左滑逐卡读居中场地的（名称，SCORE），报告 **score=0 = 新开的场**，
  结束恢复初始居中卡
- `center <关键词>`：按名把场地转到居中——**bot 只点居中卡的 PLAY!，居中错=跑错场**
- wait_hall 逃逸链：促销弹窗 X（scene_popup_x）→ 结算残局（result_continue /
  pf_continue_btn）→ 房子 `(115,37)` 回大厅 → 全屏搜 hall_prize_fights（吸附位不固定）
- 场地名 OCR 噪声大（EYE→"E OF IH"）：difflib 对 `KNOWN_TITLES` 模糊匹配（cutoff 0.55）
- 每日例行：`python tools/pf_scene.py explore --skip-mumu` → center 目标场 → 开跑

### 6.10 定时调度（tools/pf_schedule.py，实验版）

场次绑定"怎么跑"（sessions.json），schedule 绑定"什么时候跑"（`debug/pf/schedule.json`）：

```json
{"jobs": [{"name": "凌晨跑元素场", "time": "01:00", "days": "daily",
   "action": "run_pf",
   "params": {"arena": "EYE", "parent_session": "s1788366023203", "restart": true}},
  {"name": "早上5点停(仅09-06)", "time": "05:00", "date": "2026-09-06",
   "action": "stop_pf"}]}
```

- 一次性任务：加 `"date": "YYYY-MM-DD"` 仅该日触发，过后自然失效（job 条目留存不用删）
- action：`run_pf`（全链：restart=true 时先停旧 bot → MuMu → 导航 → center →
  parent_session 经 pf_store 建子场（bot 停着时独占写安全；session_id 直用亦可）→
  Popen 起 pf_bot → /api/start → 验证 RUNNING）/ `stop_pf` / `explore`
- 20s 扫描；错过窗口（宿主睡眠）`grace_minutes`（默认 90）内补跑；触发记录
  `schedule_state.json` 按天去重，**先记账再执行**防长任务重触发；日志 `schedule.log`
- bot 子进程带 `CREATE_BREAKAWAY_FROM_JOB`：调度器被整树强杀时 bot 不陪葬
  （job 不允许 breakaway 则降级普通启动并告警）
- 用法：`python tools/pf_schedule.py` 常驻 / `--fire 任务名` 立即触发测试 / `--list`
- 现状：2026-09-06 05:00 一次性任务「早上5点停」准点触发成功（stop_pf 6s 进程干净退出，
  schedule.log），事后 jobs 已清空；调度器需常驻进程（anaconda python），
  **不会开机自启**，重启后手动拉起

---

## 6.11 JJC 日程数据层 + 场次×规则版本管理（tools/jjc_store.py，2026-09-15）

把 sgmnow 的「当天台上开的到底是谁」拉进来，并给「场次配置」建立可追溯的版本链。
两件事都在同一个模块里，但职责是分开的。

### 6.11.1 数据源

[Krazete/sgmnow](https://krazete.github.io/sgmnow/) 只是个导航页，真数据在它背后的
**SGM Score Cutoffs 表** 的 `now` 页 A 列，走 gviz CSV 导出即可公开读取：

```
https://docs.google.com/spreadsheets/d/<SHEET_ID>/gviz/tq?sheet=now&tqx=out:csv&range=A1:A90
```

A 列是一个紧凑的「当前活动」快照，八个段落按顺序排列：

| 标签（段落首行） | 下一行=名字 | 再下一行=开放旗标 |
|---|---|---|
| `Current Daily Events:` | `Filia;Marie;Squigly`（分号分隔） | — |
| `Current Rift Element:` | `Dark` | `1` |
| `Current Character PF:` | `Eliza` | `1` |
| `Last Elemental PF:` | `Light` | `0` |
| `Last Medici PF:` | `Shakedown - Hemofilia` | `0` |
| `SMYM PF:` | `Inactive` | `0` |
| `Seeing Stars PF:` | `Inactive` | `0` |
| `Current Monthly PF:` | `A Class of Ones Own` | `1` |

### 6.11.2 三个必须知道的数据坑

1. **行号不固定。** gviz `range=a:a` 会跳过空单元格，Krazete 又随时改版，实测两次解析
   行号能对得上，但**下一版不一定**。所以一律用标签正则定位（`re.search`），绝不写死
   行号 —— 线上 sgmnow/index.js 也是这么干的。找不到某个条目就报 SourceError，
   **不返回半成品**（宁可给不出，也不给一条看起来合理的名字）。
2. **`Loading...` 和 `Inactive` 是两种东西，别混为一谈。** `Inactive` 是「本类 PF 现在
   没开」的正常回答。而 `Loading...` 是公式没算完，**但它可能长期驻留在表里**：实测它
   常驻在裂缝旗标下方那个杂单元格里，永远不动。所以 volatile 判定只针对**名字位**，
   不能全局扫。(第一版就是全局扫的，结果每次抓取都判定"仍在计算"，直接废掉。)
3. **游戏日 ≠ 本机日期。** SGM 每日 reset 在 Pacific 10:00（sgmnow 的 resetOffset：
   夏令时 61200000ms / 标准时 64800000ms），折合北京时间凌晨 1-2 点。本机日历日往前
   推不得 —— 你凌晨一点开跑，本机已经 15 号，但游戏日还是 14 号。`sgm_day()` 把 Pacific
   墙上时间往前推 10h 再取日期。
   Windows 常缺 `tzdata`，`zoneinfo` 会失败，代码里有**手算美国夏令时**的兜底
   （2007 年后规则：3 月第二个周日 02:00 起，11 月第一个周日 02:00 止）。

### 6.11.3 每日快照

`debug/pf/jjc/snapshots/<YYYY-MM-DD>.json`，一个游戏日一份：

- **携带 raw 证据**：`raw_rows` 存原始 A 列，任何 parse 结论都能回查到原始单元格，
  不必信我写的解析器。
- **内容指纹 fp**：`daily_events + entries` 的 sha1 前 12 位。
- **`revision` vs `fetches`（别搞混）**：`revision` 只在**内容变化**时递增，`fetches`
  记录抓取次数。同一天重复抓十次，revision 仍是 1 —— 否则版本号就退化成计数器，
  失去"这天变过"的信号意义。
- 每次抓取追加一条 `revisions[]`（ts/fp/last_edit），构成当日变更审计线索。

### 6.11.4 场次 × 规则 版本账本

`debug/pf/jjc/versions.json`。回答的是：**某一天把某一场的配置改成了什么、当天台上
是谁**。所以每条版本记录都带上当时的 JJC 快照引用 `{day, fp, char, elem, ...}`。

记账规则（pf_store.create / create_child / update / delete 四处挂钩）：

- 只记**配置**：`name / rule / parent / energy_cost / score_target / rest_every /
  rest_minutes`。**`score` 是运行状态，不是配置** —— 每场采样都变的东西不进版本，
  否则账本会被扫成废纸。
- **只有指纹真的变了才写记录**。已验证：连续两次保存同一个 `wind` 规则 → 第二次返回
  None，不产生版本（WebUI 拖一次滑块就得一条版本的账本没人会看）。
- 历史场次在启动时补 v1 基线（`op=import`），老数据同样可追溯，不是"新功能之后才有版本"。
- 删除场次**保留**账本并追加 `op=delete` 条目 —— 删了配置不代表那次改动没发生过。
- 记账失败只告警不阻断主流程（拖滑块写文件失败不该让 bot 停摆）。

### 6.11.5 用法

```bash
python tools/jjc_store.py fetch              # 立即抓一次并落盘
python tools/jjc_store.py show               # 显示当天快照（没有就抓）
python tools/jjc_store.py days               # 列出已有快照日
python tools/jjc_store.py diff 2026-09-14    # 某天 -> 最新，看变了什么
python tools/jjc_store.py versions [sid]     # 场次的规则版本历史
```

WebUI **JJC 日程** 页签：今日 daily 名单 chips + 各类 PF 卡片（名字/开放徽章/元素与
角色图标走 `/sgm/` 路由）+ 版本账本时间线（版本号/变更字段/指纹/当日 JJC）。

- **`GET /api/jjc` 不触网**（`peek()` 读本地快照）—— 切页签发外部请求是坏设计，那样
  断网时页面会卡住。只有显式点「刷新快照」才走 `POST /api/jjc/refresh`。
- 快照不是当天时前端标 **stale**，明说是历史数据，不冒充今日实况。

---

## 6.12 PF 轮换规律（网上查证，2026-09-16）

凌晨路线原本靠「扫 hub 找 score=0」判断新场，是视觉盲扫。**PF 排期其实是周期性的、
可离线推算的**，盲扫应该降级为「定向确认」。

### 6.12.1 各类别的周期（A 级 —— 我自己用日期算术核验）

核验方法：从 SGM Score Cutoffs 表 `now` 页各区块取**第 2 列日期**（那是历史场次日期），
看星期几与相邻间隔。**下表不依赖任何人的说法，是算出来的**：

| 类别 | 星期 | 间隔 | 近 10 个数据点 |
|---|---|---|---|
| 角色 PF (Character) | 周一 | **63 天**（9 周）回到同一角色 | 24-12-23 → 23-06-15 |
| 元素 PF (Elemental) | 周六 | **35 天**（5 周） | 24-12-21 → 24-02-10 |
| Medici PF (金币) | 周三 | **14 天**（2 周） | 25-01-15 → 24-08-21 |
| SMYM PF (招式) | 周六 | **14 天**（2 周） | 25-01-18 → 24-09-14 |
| Seeing Stars (星) | 周五 | **7 天**（每周） | 25-01-17 → 24-11-22 |
| 月常 PF (Monthly) | — | 每月 1 号 | 24-09-01, 23-09-01 … |

注：2023 年那两个角色 PF 日期（23-06-15 / 23-10-12）是**周四**，间隔 59/60 天 ——
说明 Mon–Wed 这套排法在当时还没成型，只有 2024 年起的点才是整齐的 63 天。
另外官方 5.4.1 补丁**取消了周末的 Medici 场**，只保留周三的（官方论坛公告）。

### 6.12.2 角色 PF 的九对循环（B 级 —— 社区来源）

来源：官方论坛帖 [Prize Fight Calendar](https://forum.skullgirlsmobile.com/threads/prize-fight-calendar.22233)
（BallotBoxer 2024-06-06 首发；Lunix Vandal 做的周表）。

18 个角色固定成 **9 对**，每周换一对，一周内两个角色各占半周：

```
Double/Squigly → Valentine/Fukua → Eliza/Marie → Annie/Big Band
→ Cerebella/Robo-Fortune → Ms. Fortune/Umbrella → Filia/Parasoul
→ Beowulf/Peacock → Painwheel/Black Dahlia → (回到第一对)
```

一对之内：**周一–周三 = 前一个，周四–周六 = 后一个**。

**这是唯一一处社区说法被我方数据独立证实的地方**：2026-09-14 是周一，
距 2024-12-23（社区表记 Eliza/Marie 那一周）正好 **630 天 = 63×10 周**；
而 09-15 快照的 `Current Character PF` 正是 **Eliza**。两条独立线索对上。

**2026-09-18 二次实证（A 级）**：游戏日 09-17（周四）快照 char 由 Eliza → Marie，
实机轮播新出现 `DEATH METTLE`（BRONZE，无 SCORE 行，剩余 02D:23H），
卡面立绘女仆 Marie ⇒ **「周四–周六 = 后一个」成立，且 Marie 的角色场名 = DEATH METTLE**
（截图 `debug/pf/run/0918_010435/0019_scene.jpg`；已入 arena_rules.json，rule=null）。
半周切换这条从 B 级升为「B 级 + 两次独立实测」。

**2026-09-22 三次实证（游戏日 09-21 周一）**：上周（09-14）= Eliza/Marie 对，
按 9 对循环本周应为 **Annie/Big Band**、周一-三 = 前一个 = Annie ——
快照 `Current Character PF` 当日由 Marie(收) → **Annie(开)**，命中。
实机同步扫到新场 `INFINITY AND BEYOND`（BRONZE，无 SCORE 行，剩 02D:23H:55M，
立绘绿发星饰 = Annie）⇒ **Annie 的角色场名 = INFINITY AND BEYOND**，
已入 arena_rules.json（rule=null，parent=角色周场，A 级）。
按口径只做记录对齐，9 对循环本身维持 B 级不升级。

### 6.12.3 元素 PF 顺序（B 级）

社区表：**Water → Fire → Wind → Light → Dark**，5 周一轮。与快照
`Last Elemental PF: Light`（24-12-21）一致（社区的这张表在 24-12-16 那周标的正是 Light）。

### 6.12.4 四条限制 —— 比上面的规律更重要

1. **靠「每天重新对锚」，不要靠死算。** 所有周期都锚在 2024 年的数据上；
   官方**只要加一个新角色，9 对循环的长度就变了**。唯一可靠的锚是快照里的
   `Current Character PF` / `Current Monthly PF`（A 级）—— 每天 fetch 一次自动对齐。
2. **月场有两个，别只认一张（2026-09-16 用户确认，两者都是最近才更新的）**：
   **角色月场 = `A CLASS OF ONE'S OWN`**（parent `default`）、
   **元素月场 = `AGAINST THE WIND`**（parent `s1788366023203`，本月 wind）。
   09-15 实测两张卡剩余都是 `14D:23H` → 同为月量级。
   ⚠️ 快照只把前者记为 `Current Monthly PF`；**元素月场不在快照的 6 类里** ——
   它的近亲是 `Last Elemental PF`，但那是**周末两日场**，不是月场，别混。
   另外 8.7 / 8.8 补丁还加了**季节性 PF**（Hot Mess、Sink or Swim），
   官方原话「和常规排期并行、额外存在」，同样不在快照覆盖内。
3. **「名字 → 类别」的映射仍未建立（C 级）。** `arena_rules.json` 里
   `NIGHT'S GHOUL` / `SEEING STARS` / `ROSHAMBOH` / `BELLE OF THE BRAWL` /
   `TRIAL BY FIRE` / `THE BIG THAW` / `GOLD RUSH` 至今只是猜的。
   轮换规律只能告诉你**某天该有哪些类别在台上**，
   把具体名字绑到类别上仍然只能靠实机看着确认 —— 别拿 6.12.2 的表去反推名字。
4. **09-15「3 张卡 vs 快照 2 类开放」的疑点已结清 —— 而且我原来的判断是错的。**
   三张卡 = 角色场 `BLOOD SPORT`（艳后 Eliza，角色场无规则）+ 月场两张（见 6.12.4.2）。
   我上一版把它当成「对不上快照的季节性 PF」，属**误判**：真相是
   `DIAMOND` 是难度层级而不是名字的一部分，我把标题看错了（见 6.12.6）。

### 6.12.5 建议的判据顺序

判「今天有没有新 PF」应当：

1. `jjc_store.py fetch` → 看 `Current Character PF` / `Current Monthly PF` 变了没（A 级）；
2. 变了 → 进 hub **定向**确认那个类别，再建子场次；
3. 没变 → 大概率不用扫全轮播；
4. hub 上出现快照里没有的类别 → 先排掉 6.12.4.2 的那两个月场，
   再按季节性 PF 处理，并如实记「未映射」。

### 6.12.6 命名约定：DIAMOND 是难度层级，不是名字（A 级，裁图实证）

卡片的名字框 `ROI_CARD_TITLE=(500,290,780,378)` 会**同时框到难度层级的小字和
场地名的大字**。用 `debug/pf/hub_scan/02.png` 裁出来看得很清楚：一张卡上是
小字 `DIAMOND` + 大字 `BLOOD SPORT`。所以 OCR 会吐出 `DIAMOND BLOOD SPORT`
这种串；历史条目 `DIAMOND NIGHT'S GHOUL` 就是这么来的 —— **DIAMOND 是层级**，
`BLOOD SPORT` 才是艳后 Eliza 的角色场名（用户 2026-09-16 确认；注意名字里的
角色**不构成约束**，角色场无规则 —— 见 §6.12.7）。

**不能靠改 ROI 来躲**：`A CLASS OF ONE'S OWN` 是两行名、占满整个框，
把 y 起点往下抬会把它切掉。所以在**代码**里解：

- `pf_scene.read_center_card()` 现在把「原串 / 去层级前缀串」都当候选，
  谁跟 `KNOWN_TITLES` 更接近用谁；
- **不能无脑删前缀** —— `GOLD RUSH` 本身就以 `GOLD` 开头。择优法对它安全
  （离线自测：`GOLDRUSH` → `GOLD RUSH`，没有变成 `RUSH`）；
- 命中去前缀候选时会打一条 warn 记录 OCR 原文，便于回查。


### 6.12.7 限定只有「元素」一类 —— 角色场不限定角色（A 级，用户 2026-09-16 确认）

**游戏侧只有元素 PF 限定队伍元素。** 角色 PF、金币 PF、星助手 PF、月场
**都不限定任何角色或类别** —— 带什么队都能打。

佐证不需要外部资料，看自己的 `debug/pf/sessions.json` 就够：全场次里只有
**两个**非 null 规则，且都是 `element`

| 场次 | rule |
|---|---|
| `202609元素月场` (s1788366023203) | `{"element":"wind"}` |
| `光元素场` (s1789262584700) | `{"element":"light"}` |
| `202609角色月场` (default) | `null` |
| `角色周场` (s1788423741550) **及全部 09-03/04/08/11/15 子场次** | `null` |
| `金币场` (s1788423643718) **及全部子场次** | `null` |

**这就推翻了我之前的一个提法**：我在 `arena_rules.json` 里把 `BLOOD SPORT`
写成 kind `角色·艳后 Eliza`，读起来像「该场限定 Eliza」。事实上「艳后 Eliza」
只是**这个名字的由来**，不构成任何约束 —— 已改成 `角色周场(艳后 Eliza)`。

**两个直接推论：**

1. **名字绑错的代价是不对称的。** 绑错角色名 → 零后果（角色场无规则，
   只是日志难读、父场次可能挑错）。但把元素场误判成非元素场（或反过来）→
   该过滤的没过滤 / 不该过滤的瞎过滤，**直接影响能源消耗与队伍构成**。
   ⇒ 所以 `arena_rules.json` 里 `依据` 的分量要按这个不对称来读：
   `TRIAL BY FIRE` / `THE BIG THAW` / `EYE OF THE STORM` 那几条是 B 级语义猜的，
   **它们才是真正要实机确认的**；而 7 个 C 级名字里只要不是元素场，猜错无所谓。
2. **`{"type":"class"}` 不但没数据，连游戏依据都没有。** `pf_bot.judge_rule()`
   第 438 行对 class 是 `return False` 的**桩**（恒判不满足），WebUI 那 12 个
   金圈按钮点下去会让 bot **每场战斗都进筛选替换循环**，空烧能量且永远满足不了。
   文档 §6.4 里「类别规则 sgm 无数据」这句现在要再加半句：**不只是没数据，
   是游戏里压根没这条限制。别自动绑。**

**因此代码里加了一道硬约束**（`pf_schedule.resolve_arena`）：
自动绑定路径**只接受 element 规则**，其它类型一律丢弃 + 打 warn；
人工在 WebUI 上设的规则走 `sessions.json`，**不经过这个校验、不受限制**。
离线验证过：临时注入 `{"type":"class","value":"c7"}` 会被拦成 `rule=None`，
`element/wind` 正常通过。

### 6.12.8 金币场（Medici PF）排期：每周三，Jinx / Hemofilia 交替（A 级 —— 2026-09-16 实测命中）

`now` 页只能看到「最近一次」，但同一份表的 **`medici` 页**是**预填到未来**的
（实测拉到 2027-11），而且带 `Type` 列 = 场地名。2026 年 8–10 月窗口：

```
2026-08-05 Wed  Shakedown - Jinx        2026-09-16 Wed  Shakedown - Jinx
2026-08-12 Wed  Shakedown - Hemofilia   2026-09-23 Wed  Shakedown - Hemofilia
2026-08-19 Wed  Shakedown - Jinx        2026-09-30 Wed  Shakedown - Jinx
2026-08-26 Wed  Shakedown - Hemofilia
2026-09-02 Wed  Shakedown - Jinx
2026-09-09 Wed  Shakedown - Hemofilia
```

**全部落在周三，无一例外**，且 Jinx / Hemofilia 严格交替。

与本地实测兼容：本地 `sessions.json` 的金币子场次是 09-05 / 09-10 / 09-12 / 09-13
（本机日期，凌晨跑 → 游戏日各减一天）。它们不是四场，而是**两场各自跨了几天**
（09-02 那场 → 09-05；09-09 那场 → 09-10/12/13），和「每周三开一场、持续数天」吻合。

**2026-09-16 实测命中，A 级。** Pacific 10:00 reset 后 fetch：游戏日 `2026-09-16`、
`action=new`、`entries.medi = {active: true, name: "Shakedown - Jinx", title: "Current Medici PF:"}`，
且与 09-15 的 diff 为 `Shakedown - Hemofilia (未开) → Shakedown - Jinx (开放)`（源表确实刷新）。
原判据「明早 fetch 后看 `Current Medici PF` 是不是 `Shakedown - Jinx`」成立，
等级由 B 升 A。下一步可验点：2026-09-23 周三应为 `Shakedown - Hemofilia`。

⚠️ 另一处坑：`character2` / `element` 两页在同一窗口内**完全无数据**（最后条目停在
2024 年底 / 2025 年初），角色场与元素场**不能**用这张表预知，只能靠 `now` 页 + 轮换规律。

## 6.13 远程访问（Tailscale serve，2026-09-16 加）

本机 tailnet 域名 `https://he-pc.tail016ba5.ts.net`，本机 tailnet IP `100.90.189.32`。
一个 HTTPS 端口映射一个后端：

| 对外 | 后端 | 服务 |
|---|---|---|
| `:443` | `127.0.0.1:8788` | TailShare 分享站 |
| `:8443` | `127.0.0.1:8791` | Message-cli |
| **`:8444`** | **`127.0.0.1:8790`** | **PF bot WebUI（本项目的）** |

**别的机子怎么访问，有两条路**（2026-09-19 实测均 200）：

| 路径 | 地址 | 说明 |
|---|---|---|
| 域名（经 serve，HTTPS） | `https://he-pc.tail016ba5.ts.net:8444` | 推荐。走 MagicDNS，IP 变了也不用改 |
| **IP 直连（绕过 serve，HTTP）** | `http://100.90.189.32:8790` | 8790 监听在 `0.0.0.0`，直连监听端口即可，**不走 serve**；pfwidget 默认就是这个 |

两者都必须满足的前提：**对方设备在同一个 tailnet 且在线**（`serve status` 显示
`tailnet only`，公网不可达 —— 那是 funnel 的事，没开）。设备离线时任何地址都连不上，
先 `tailscale status` 看它是不是 online。

加端口：

```bash
"C:/Program Files/Tailscale/tailscale.exe" serve --https=8444 --bg http://127.0.0.1:8790
"C:/Program Files/Tailscale/tailscale.exe" serve status           # 核对
"C:/Program Files/Tailscale/tailscale.exe" serve --https=8444 off # 撤销
```

⚠️ 8790 只有 bot 起来时才有后端；没起时访问 `:8444` 会返回 **502**（通道是通的），
这不是配置坏了。

### 6.13.1 域名访问返回 `403 Host 头不允许` —— 不是 tailscale 的锅

**现象**：`:8444` 配好、后端也确认 RUNNING，浏览器打开却只回一行 `Host 头不允许`。

**根因在本项目，不在 tailscale。** `tools/pf_webui.py` 的 `_host_ok()` 是一道
**DNS-rebinding 防护**（见该文件头第 25 行）：白名单只放行
`localhost` / IP 字面量 / 无点主机名 / `.local`·`.lan`；
`he-pc.tail016ba5.ts.net` 带点、又不是那两个后缀 → 被当成公网域名拒绝。
bot 日志里会同时出现形如 `已拒绝 GET / <- 127.0.0.1 (Host 头不允许)` 的行
（源 IP 显示 `127.0.0.1`，因为 tailscale serve 就在本机转发）。

**修法（2026-09-17 已改）**：把 `.ts.net` 并入 `_host_ok()` 的 `TAILNET_SUFFIXES`。
理由：`.ts.net` 是 Tailscale MagicDNS 的固定后缀，**只有 tailnet 成员能解析、也只在
tailnet 内可达**，威胁模型与 `.local` / `.lan` 同一档，不属于「公网域名」。
离线验证：`he-pc.tail016ba5.ts.net:8444` 放行，`evil.com` 仍拒绝。

三条**走不通**的绕路，别再试：
- `tailscale serve --set-header` —— 本机 tailscale **1.102.3** 的 `serve` 不支持设置
  请求头（只有 `--set-path`），改不了 Host。
- 用 tailnet IP 访问 **serve 端口**（`100.x.y.z:8444`）—— 返回 **400**（curl 实测，
  不是此前记的 `000`），TS 的 serve 只认域名 Host。注意这条**只针对 8444 这类 serve 端口**；
  直连后端监听端口 `100.90.189.32:8790` 是完全可以的，见 §6.13 的路径表。
- 改了 `_host_ok` 但**不重启 bot** —— 校验在进程内，无热加载，改完必须重启才生效。

---

## 7. 实测记录（2026-09-02）

- 全流程循环实机跑通：选对手(火框倍率) → 编队(能量/规则) → 战斗 → 结算链 → 循环
- 单次会话 12 场 11 胜、18 次拖拽全中；修复后拖拽 0 失败；倍率识别离线回归 9/9
- 能量消耗验证 10→6→2（-4/场）；总分/连胜计分、CSV、图表联动验证
- 服务器错误三种变体、OPTIONS 卡死、PF 主页面误退出均已实测恢复

### 7.1 规则场实机联调（2026-09-03，风元素月场）

- 连跑 32+ 场（连胜 17+），期间修复并验证：
  1. 筛选面板吃零时长点击 → 驻留式点击 + 关闭验证重试（§8-13）
  2. 规则判定改 FIGHT 按钮颜色（§6.4；OCR 查 variants.json 结构性不可行）
  3. 复验阈值 30%→15%（差 3px 误杀，§6.4-2）
  4. 弹窗自愈收窄为仅规则槽补风（§6.4-3）
  5. 规则槽能量不足在 fix_team 内直补，不再绕道弹窗（§6.3-4）
  6. run() ERROR 真停止（原翻回 RUNNING 死循环）
- 已知遗留：风∩喜爱池能量偏低时规则补人可能失败停机（可加"等待回能"模式）；
  休息计数在进程重启时归零（频繁重启则一直数不满 20 场）
- 已知遗留：
  1. 战力 OCR 偶有数字误读（只影响"无火框比战力"场景）
  2. 类别规则无 sgm 数据支撑，判定恒走筛选替换（每场多 ~5s）
     —— **2026-09-16 更正：不只是没数据，游戏里也没有「角色/类别限定」这条规则，
     所以永远不该自动绑 class 规则。见 §6.12.7**
  3. 能量全员耗尽即停止（可加"休眠等恢复"模式）
  4. `wait_battle_end` 收到停止时会误报"超时 300s"（仅日志文案）
  5. 当前直驱 `post_recognition/post_action` API；`interface.json + pipeline` 骨架已备，
     后续可迁移为标准 pipeline 任务库接入 MaaPiCli/MFW-Pi

### 7.2 冷启动链 / scene / 定时（2026-09-06，EYE OF THE STORM 两日风特场）

- **全链手工→脚本化**：MuMu 启动→游戏→大厅点 PRIZE FIGHTS（单点直进，无需先居中）→
  hub 轮播左滑 2 次到目标场居中（PLAY! 交由 bot 自点）→ 子场次「202609元素月场 09-06」
  （父场已 150M 达默认上界，直接复用会秒触发达标暂停）→ /api/start。首场验证：
  选火框倍率最大对手、槽位能量[10,10,10]、FIGHT 36% 满足风规则、AUTO/3x 自检通过
- **pf_scene explore 全通**：5 张场地卡逐一扫分（CLASS 150.3M / WIND 150.6M /
  STARS 25.7M / GHOUL 7.97M / EYE 7.77M），标题 difflib 全部命中，恢复初始居中卡 OK；
  scene 读数与 bot 采样分一致（同源 OCR 交叉验证）
- **pf_schedule 实验**：02:20 测试任务准点触发，restart 停旧 bot→导航→center→
  起 bot→RUNNING 全链 ~35s（游戏已在前台热链路）；场次总分跨重启连续（CSV 预载）；
  实验后 schedule.json 已清空（jobs 留空 + _example）
- **AUTO/3x 验证手法**：盯「战斗已开始」日志 → sleep 2s → 动作 → 截图，单条 bash
  闭环；3x+高战力下战斗 10s 内结束，观察战斗画面要在日志后 0.3-2s 截图
- **能量经济实测**：~13 场+实验把全池打空（候选区 0 能量）；回填靠挂机自然回能，
  池空时新 bot 会卡在编队等能量（或报"无可用能量角色"停机），等几分钟重启即可

---

## 8. 踩坑实录（重要度排序）

1. **anaconda 旧 CRT** → 见 §1，入口先 `preload_msvcrt()`
2. **MAA roi 是 (x,y,w,h)** 不是 (x0,y0,x1,y1) —— 统一 `to_maa_roi()`
3. **`post_bundle` 指向 `resource/base`**：框架只认给定路径直接子级的 pipeline/model/image
4. **OCR det.onnx 必须存在**（only_rec 也依赖）
5. **cv2.imwrite/imread 非 ASCII 路径静默失败** → `imencode`+`write_bytes`、`np.fromfile`+`imdecode`
6. **模板匹配对遮罩压暗不敏感**（TM_CCOEFF 亮度不变性）→ 弹窗检测必须最优先
7. **弹窗按钮尺寸/位置随弹窗不同**（能量 54px X / streak 46px X / OPTIONS 48px X @右上角）
8. **VS 页站位是入场动画**，判断队伍以烧瓶/钉条为准，别对比站位
9. **双显卡 DirectML 枚举坑** → 统一 `use_cpu()`
10. **延迟导致动作落到已切换界面**：未知界面恢复必须交替按 返回键/右上X，并专门识别 hub
11. **游戏内筛选状态持久**：跨运行残留会悄悄收窄候选列——每次开始运行后的首次
    编队按 WebUI 喜爱筛选开关归位（清残留+按需点亮爱心，见 §6.3-1）
12. **残留进程双实例**：旧 bot 没死透会抢 WebUI 端口、同时操作模拟器——启动前确认单实例
13. **筛选面板吃零时长点击**（2026-09-03）：MAA maatouch 的 post_click 是瞬点，面板上
    爱心/关闭 X 大概率不响应（风力芯片响应了，行为不一致）。统一用驻留式点击
    （down → 0.18s → up）+ 关闭后 X 模板验证（pf_filter_x.png，关闭/打开相关度
    0.44/1.00）失败自动重试。**面板不关 = 底部候选列被压暗 = 能量全读 0**，
    曾借此死循环（选人30页→未知界面→重进→再筛选），run() 现遇 ERROR 真停止
14. **variants.json 键是内部代号**：`fTrap`/`pThread`，显示名在条目 `fandom` 字段；
    拿 OCR 文本直接查键永远 miss。卡框颜色≠元素真源（钻石稀有度粉色覆盖）
15. **bot 运行中严禁向模拟器注入任何点击/滑动**（2026-09-06 事故）：战斗中途"想一下
    点一下"点歪到对手身上→弹角色详情→返回键打乱 bot 节奏→筛选面板被吃→能量耗尽停机。
    要动先 /api/pause|stop；确需战斗中注入用确定性延时脚本（盯日志→sleep→动作→截图，
    单条 bash 闭环），模型往返延迟 3-10s 必错过 3s 开场窗口
16. **MAA post_swipe 会被吸附轮播弹回原卡**（2026-09-06）：PF 场地轮播要用 adb
    `input swipe 900 400 560 400 600`（340px+600ms）一次进 1 张；520px+450ms 惯性
    跳 2 张，中间场地被漏扫
17. **大厅限时促销弹窗吃点击**（2026-09-06）：回大厅自动弹（BACK TO SCHOOL 等），
    其 X 不在 bot 弹窗 ROI 且 options_x 不匹配（相关度 0.295）——scene_popup_x.png
    专模板 @(950,30,1240,180)，pf_scene 已内置处理
18. **后台任务 Stop 是整树杀**（2026-09-06）：强杀调度器时其 Popen 的 bot 连带死亡
    （WebUI 端口无响应）——pf_schedule 起 bot 加 CREATE_BREAKAWAY_FROM_JOB 脱离作业对象；
    反过来也说明 bot 必须在 scene 导航**之后**启动（IDLE bot 的弹窗清理会抢点击）
19. **端口有响应 ≠ bot 在跑**（2026-09-15）：本机 87xx 段挤了一堆别的服务，实测
    8787 上跑的是一个银企直连需求服务，`/api/state` 照样返回 200 —— 旧版
    `bot_alive()` 只判"端口通不通"，于是把别人的服务认成 bot：`run_pf` 以为
    "bot 已在运行"直接拒绝启动，或向别的服务发 `/api/start`。两处修：
    ① WebUI 端口从 8787 挪到 **8790**，唯一来源 `pf_env.WEBUI_PORT`
    （config.json 加 `"webui_port"` 可覆盖），本机消费方（pf_bot / pf_schedule /
    启动PF.bat / 文档）都取这一个值。
    ⚠️ **唯一例外是 pfwidget**：它是 Android 工程（Java），
    `PfWidgetProvider.DEFAULT_URL` 与 `PfSettingsActivity` 里**硬编码**
    `http://100.90.189.32:8790`，读不到 `pf_env`。改端口那一刻说的"所有消费方同步"
    对它不成立 —— 端口再变必须手改这两处再重新打包 APK（2026-09-19 核）；
    ② `/api/state` 带 `svc` 身份标签，`bot_alive()` 必须校验它。
    端口被占时 `start_webui` **直接抛错**，不偷偷换端口 —— 换了等于骗过所有写死 URL 的调用方

---

## 9. 常用操作

双击 `启动PF.bat` 一键启动（自动用 anaconda python，端口就绪后自动打开 WebUI 页面）。
或手动：

```bash
python tools/setup_env.py         # 一键配置环境（依赖/OCR模型/Chart.js/自检，已存在自动跳过）
python tools/pf_bot.py            # 启动（WebUI 点"开始"开跑；停止=暂停，可恢复）
python tools/pf_scene.py goto|explore|center X   # 场景导航 / 扫分找 score=0 / 场地居中（见 §6.9）
python tools/pf_schedule.py       # 定时任务常驻（--fire 任务名 / --list，见 §6.10）
python tools/screencap.py [out]   # 手动截图
python tools/jjc_store.py show    # JJC 今日日程快照（见 §6.11）
```

setup_env.py 可选参数：`--with-vendor`（下载 MAAFramework 官方包到 vendor/，运行非必需）、
`--mirror <前缀>`（GitHub 下载加速，如 https://ghproxy.net/ ）。项目迁移到新机器时
拷贝 `assets/ tools/ sgm/ requirements.txt` 后执行一次即可。

- WebUI 设置：场次（开始=当前场次直开，未选才弹窗；运行中锁定）/ 分数上界·休息（随场次，
  顶栏编辑当前场次）/ 能量门槛（随场次）/ PF规则按钮组（关|元素|角色徽记，绑定场次；
  **运行中折叠为已生效徽章**）/ 喜爱筛选
- 出战能量门槛默认 4；类别芯片对照 `docs/screenshots/filter_panel.png`
- 调试：`debug/maa/debug/maafw.log`（框架）、`debug/pf/bot_stdout.log`（脚本）、
  `debug/pf/run/<ts>/`（全程截图）
