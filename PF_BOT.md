# MAAskullgirls — SGM Prize Fight 自动化脚本（基于 MAAFramework）

Skullgirls Mobile 的 Prize Fight（竞技场）自动刷本脚本。基于 MAAFramework v5.12.3
的 Python 绑定（MaaFw）驱动 MuMu 模拟器，实现 **选对手 → 编队 → 自动战斗 → 结算领奖** 的无人循环，
带 WebUI（实时日志 / 截图 / 交互图表 / 设置 / 起停控制）。

- 启动：`python tools/pf_bot.py`（进程常驻，WebUI 里点 **开始** 才开跑）
- WebUI：http://127.0.0.1:8787 —— **运行**页签（日志+截图）、**图表**页签（总分/收益/连胜）
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
1. （规则未启用时）本次运行清一次残留筛选
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
  因此能量弹窗的自愈也复用该信号（见 §6.3）。类别规则 sgm 无数据，恒走筛选替换。
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
- 设置项：目标总分（达到自动暂停）、能量门槛、喜爱筛选开关；
  **休息配置（每 N 场休 M 分钟）跟随场次**（2026-09-03）：设置条的
  "每 N 场休 M 分钟"输入框编辑的是当前选中场次（与 PF 规则同模式，运行中禁改），
  开始时载入，换场次重置休息计数与倒计时；场次弹窗列表每行显示"休N场×M分"。
  注意：**休息计数在进程重启时归零**（fights_since_rest 是内存态），频繁重启则
  一直数不满 N 场
- 接口：`/api/state`、`/api/history`、`/api/start`、`/api/stop`、`/api/settings`、`/static/*`

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
   "params": {"arena": "EYE", "parent_session": "s1788366023203", "restart": true}}]}
```

- action：`run_pf`（全链：restart=true 时先停旧 bot → MuMu → 导航 → center →
  parent_session 经 pf_store 建子场（bot 停着时独占写安全；session_id 直用亦可）→
  Popen 起 pf_bot → /api/start → 验证 RUNNING）/ `stop_pf` / `explore`
- 20s 扫描；错过窗口（宿主睡眠）`grace_minutes`（默认 90）内补跑；触发记录
  `schedule_state.json` 按天去重，**先记账再执行**防长任务重触发；日志 `schedule.log`
- bot 子进程带 `CREATE_BREAKAWAY_FROM_JOB`：调度器被整树强杀时 bot 不陪葬
  （job 不允许 breakaway 则降级普通启动并告警）
- 用法：`python tools/pf_schedule.py` 常驻 / `--fire 任务名` 立即触发测试 / `--list`
- 现状：**jobs 留空未启用**（2026-09-06 实验，用户明确"不真正配"），文件里有 `_example`

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
11. **游戏内筛选状态持久**：规则关闭后的首次编队要清一次残留筛选，否则候选列被悄悄收窄
12. **残留进程双实例**：旧 bot 没死透会抢 8787 端口、同时操作模拟器——启动前确认单实例
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
    （8787 无响应）——pf_schedule 起 bot 加 CREATE_BREAKAWAY_FROM_JOB 脱离作业对象；
    反过来也说明 bot 必须在 scene 导航**之后**启动（IDLE bot 的弹窗清理会抢点击）

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
