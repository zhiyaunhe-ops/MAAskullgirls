# SGM Prize Fight 自动化脚本（基于 MAAFramework）

Skullgirls Mobile 的 Prize Fight（流浪防区）自动刷本脚本。基于 MAAFramework v5.12.3
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
MAA_again/
├── PF_BOT.md                      # 本文档
├── docs/screenshots/              # 关键界面截图存档（筛选面板/hub/错误弹窗等）
├── tools/
│   ├── pf_env.py                  # CRT 预载 + 连接参数 + BotState(含设置与历史)
│   ├── pf_vision.py               # 纯 cv2 视觉分析（可离线测试）
│   ├── pf_bot.py                  # 主程序：监督循环 + 状态机 + 规则/计分/拖拽
│   ├── pf_webui.py                # WebUI（运行/图表页签、设置、起停）
│   ├── static/chart.umd.min.js    # Chart.js 本地副本
│   ├── connect_mumu.py / screencap.py   # 连通性/截图小工具
├── assets/
│   ├── interface.json             # PI V2 骨架（后续接 MaaPiCli 用，当前未走此链路）
│   └── resource/base/
│       ├── pipeline/sample.json
│       ├── image/pf/*.png         # 模板 14 张（见 §5.4）
│       └── model/ocr/             # det.onnx(v4 zh) + rec.onnx(v4 en_us) + keys.txt
├── sgm/                           # SGM 图鉴数据（元素定义、变体→元素/角色映射）
├── vendor/                        # MAAFramework 官方包
└── debug/
    ├── maa/debug/maafw.log        # MAA 框架日志（排障第一入口）
    ├── pf/run/<时间戳>/           # 每次运行全程截图 NNNN_标签.jpg
    └── pf/score_log.csv           # 每场计分记录
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

### 6.3 编队修正（fix_team）
1. （规则未启用时）本次运行清一次残留筛选
2. 归零滚动（右滑 ×4）
3. **规则判定**（见 §6.4；`_rule_done_fight` 锚定场次号，每场只判/替换一次）
4. 能量替换循环：槽位 <门槛 → 候选区从左（战力优先）找达标者拖入 → 复读验证；
   未生效 → 归零候选列重试 + 按失败次数微调落点（4 档偏移）；翻页上限 30
5. 全翻完仍无 → 停止报错（能量随时间恢复，重启/等待后继续）

### 6.4 PF 规则筛选系统（仅配置了规则才运行，未配置零开销）
- 设置：WebUI **PF规则 按钮组**（nav 行右侧）：关(默认) / 六元素按钮（sgm 元素图标）/
  12 角色金圈徽记按钮（= 类别 c1-c12，sgm MasteryIcon），点选即生效、再点当前规则取消；
  另有 **喜爱筛选**开关（顶栏）。素材由 `/sgm/...` 路由直接托管 `sgm/image/official/`
- 元素定义来自 `sgm/fighter.js`：`["neutral","fire","water","wind","dark","light"]`
  （索引 0-5，卡框颜色：火红/水蓝/风绿/光金/暗紫/中性银灰）
- 流程（用户定义）：
  1. **判定**当前三槽是否满足（元素：OCR 槽位变体名 → `sgm/data/variants.json` 查 element；
     类别：sgm 无类别数据，视为不满足走筛选替换；每场只判一次）
  2. 不满足 → 打开筛选面板（清空 → 保留喜爱 → 规则芯片）→ 关闭 → **拖最左达标者进 1 号槽**
  3. 替换完成 → **取消筛选但保留喜爱**（清空 → 爱心）→ 归零
  4. 剩余槽位（2/3 号）做能量替换（不动 1 号槽的规则角色）
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
- 每场追加 `debug/pf/score_log.csv`（time, fight_no, score, delta, streak）；启动时预载做图表

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
- 设置项：目标总分（达到自动暂停）、能量门槛、PF规则（类型+值）、喜爱筛选开关
- 接口：`/api/state`、`/api/history`、`/api/start`、`/api/stop`、`/api/settings`、`/static/*`

---

## 7. 实测记录（2026-09-02）

- 全流程循环实机跑通：选对手(火框倍率) → 编队(能量/规则) → 战斗 → 结算链 → 循环
- 单次会话 12 场 11 胜、18 次拖拽全中；修复后拖拽 0 失败；倍率识别离线回归 9/9
- 能量消耗验证 10→6→2（-4/场）；总分/连胜计分、CSV、图表联动验证
- 服务器错误三种变体、OPTIONS 卡死、PF 主页面误退出均已实测恢复
- 已知遗留：
  1. 战力 OCR 偶有数字误读（只影响"无火框比战力"场景）
  2. 类别规则无 sgm 数据支撑，判定恒走筛选替换（每场多 ~5s）
  3. 能量全员耗尽即停止（可加"休眠等恢复"模式）
  4. `wait_battle_end` 收到停止时会误报"超时 300s"（仅日志文案）
  5. 当前直驱 `post_recognition/post_action` API；`interface.json + pipeline` 骨架已备，
     后续可迁移为标准 pipeline 任务库接入 MaaPiCli/MFW-Pi

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

---

## 9. 常用操作

```bash
python tools/setup_env.py         # 一键配置环境（依赖/OCR模型/Chart.js/自检，已存在自动跳过）
python tools/pf_bot.py            # 启动（WebUI 点"开始"开跑；停止=暂停，可恢复）
python tools/screencap.py [out]   # 手动截图
```

setup_env.py 可选参数：`--with-vendor`（下载 MAAFramework 官方包到 vendor/，运行非必需）、
`--mirror <前缀>`（GitHub 下载加速，如 https://ghproxy.net/ ）。项目迁移到新机器时
拷贝 `assets/ tools/ sgm/ requirements.txt` 后执行一次即可。

- WebUI 设置：目标总分（自动暂停）/ 能量门槛 / PF规则按钮组（关|元素|角色徽记）/ 喜爱筛选
- 出战能量门槛默认 4；类别芯片对照 `docs/screenshots/filter_panel.png`
- 调试：`debug/maa/debug/maafw.log`（框架）、`debug/pf/bot_stdout.log`（脚本）、
  `debug/pf/run/<ts>/`（全程截图）
