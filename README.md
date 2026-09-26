# MAAskullgirls

<img width="1024" height="576" alt="image" src="https://github.com/user-attachments/assets/60ed957d-4bea-40a4-b417-fb80cda5cbcd" />

> 为 **Skullgirls Mobile** —— 这款小众宝藏格斗游戏 —— 打造的 **Prize Fight 全自动循环刷本脚本**（高手专用）

Skullgirls Mobile 是一款被严重低估的 2D 格斗 + RPG 手游：手绘动画帧帧到肉、无限连段系统深不见底，
而 **Prize Fight** 是它的核心常驻玩法——用你毕生练出的芯片阵容去冲击排行榜。
问题是：每天几十场重复的"选人 → 编队 → 战斗 → 领奖"会把任何高手的手指磨平。

**MAAskullgirls 就是为此而生的**：基于 [MAAFramework](https://github.com/MaaXYZ/MaaFramework)
的 Python 绑定驱动 MuMu 12 模拟器，把整个 PF 循环变成真正的无人值守流水线。
<img width="1910" height="931" alt="image" src="https://github.com/user-attachments/assets/1e04e6b0-d2bb-4939-933e-62d72b35922b" />
挂机月场展示
## 它会做什么

```
选对手 ──► 编队 ──► FIGHT ──► 结算领奖 ──► 循环，直到你设定的目标分数
(火框倍率)  (能量判据)  (自动战斗)    (Continue 链)
```

- 🎯 **选人策略**：优先挑战火框（加倍率）对手，无火框时选战力最低的软柿子
- 🔋 **能量判据**：只让能量钉 ≥4 的角色出战，能量不足的槽位自动替换重编
- 🧩 **规则适配**：按当期 PF 规则（元素/职业限制）自动筛选替换芯片
- 📊 **WebUI 控制台**（`http://127.0.0.1:8790`）：实时日志 + 模拟器截图、总分/单场收益/连胜曲线
  （Chart.js 本地托管）、分数上限/能量门槛/连打休息等设置、一键起停
- 💤 **自动休息**：连续 N 场后强制休息，防止过热；达到目标总分自动暂停

**高手专用**：坐标、判据、选人策略、规则替换逻辑全部代码化，欢迎按自己的打法魔改——
这不是给萌新的保姆工具，而是给已经吃透游戏机制的老玩家省时间的生产力工具。

## 环境要求

| 项 | 说明 |
|---|---|
| 模拟器 | MuMu 12（ADB 默认端口 `127.0.0.1:16384`） |
| 分辨率 | **1280x720 横屏**（所有视觉判据基于此） |
| 游戏语言 | ⚠️ **必须英文界面（English）**——所有 OCR 文字识别与模板判据均基于英文 UI，其他语言会直接识别失败 |
| Python | 3.10+，依赖见 `requirements.txt`（MaaFw / numpy / opencv-python） |
| 系统 | Windows（CRT 预载逻辑针对 Windows DLL 解析特性） |

## 快速开始

```bash
pip install -r requirements.txt
python tools/setup_env.py          # 自动下载 OCR 模型 / Chart.js 并自检环境
copy config.example.json config.json   # 按本机 MuMu 安装路径修改 adb_path
python tools/pf_bot.py             # 启动后打开 WebUI 点「开始」
```

启动 MuMu 12 并进入游戏主界面，浏览器打开 <http://127.0.0.1:8790> 即可接管。

> ⚠️ **再次强调：游戏语言必须是英文（English）**。脚本依赖 OCR 识别英文界面文字
> （SERVER ERROR / PLAY! / 战力数字等），中文或其他语言界面无法工作。

> 本机参数（adb 路径/端口）只放在 `config.json`，**该文件不入仓库**（已在 `.gitignore`），见 `config.example.json`。

## WebUI 主题与预览

新版工作台提供侧栏导航、运行概览、折叠设置、日志搜索 / 级别筛选 / 跟随开关、
截图空态与更新时间、目标进度，以及重新排版的战绩分析、每日任务和 JJC 日程。
每日任务模块目前保存任务编排配置，尚未接入自动执行逻辑。

| 主题 | 风格 |
|---|---|
| Canopy Noir · 冠层黑金 | Art Deco、黄铜边线、深墨与衬线标题 |
| Emerald Glass · 翡翠之境 | 绿意柔光、圆润面板 |
| Foundry · 铸造工坊 | 工业铜橙、硬边、压印阴影 |
| Neon Arcade · 霓虹街机 | 紫电网格、等宽标题 |
| Sakura Studio · 樱花画室 | 浅色花粉、柔和曲线 |
| Atelier Paper · 纸上工作室 | 暖纸油墨、编辑式双线 |

顶部主题选择器自动保存选择，支持键盘操作，图表与原生输入控件同步切换。
原有主题 ID 保留，因此旧浏览器偏好仍然有效。前端文件位于 `tools/static/`，
HTML、CSS、主题逻辑与业务 JS 分开维护，避免 Python 字符串转义破坏整页脚本。

无需模拟器或第三方依赖，即可预览真实界面：

```bash
python tools/preview_webui.py --port 8800
# 打开 http://127.0.0.1:8800/
```

预览使用示例分数和存档截图；操作仅保存在内存，不执行 ADB / MuMu 指令。
部署到子目录时，静态资源、API 与截图请求自动跟随当前挂载路径。

后续需要自测时可运行 `python tools/lint_webui.py`；浏览器回归脚本为
`tests/webui-smoke.mjs`（需要 Playwright 与 Chromium，支持环境变量指定路径）。

## 可选增强：sgm 图鉴仓库

把 [Krazete/sgm](https://github.com/Krazete/sgm)（Skullgirls Mobile 图鉴）克隆到项目根目录命名为 `sgm/`，
可优先使用其变体数据。WebUI 所需的原版元素 / 角色图标已内置于
`tools/static/icons/sgm/`，不再需要额外克隆才能显示（来源见该目录的 `ATTRIBUTION.md`）。
不克隆也能跑——`tools/data/variants.json`
已内置 306 个变体的数据副本。

## JJC 日程与版本追溯

`tools/jjc_store.py` 每日从 [sgmnow](https://krazete.github.io/sgmnow/) 背后的
SGM Score Cutoffs 表拉一次「当天台上开的是谁」（各 PF 名字 + 开放状态 / 裂缝元素 /
daily 名单），按 SGM 游戏日归档快照；同时给「场次 × 规则」建版本账本——每次改配置
记一笔并带上当天 JJC 快照，所以「哪天把哪场的规则改成了什么、当天台上是谁」可以回查。
WebUI 有 **JJC 日程** 页签。详见 [PF_BOT.md §6.11](PF_BOT.md)。

```bash
python tools/jjc_store.py fetch      # 抓一次并落盘
python tools/jjc_store.py versions   # 看场次的规则版本历史
```

## 文档

开发者 / AI 从 [AGENTS.md](AGENTS.md) 的任务阅读表找到对应代码与文档章节。
当前模块边界、界面几何、结算链、弹窗处理与运行约束统一维护在
**[PF_BOT.md](PF_BOT.md)**；其中 §7–8 是历史实测与故障证据，按排障需要查阅。
不必每次完整读取设计手册，也不为每次修改另建总结文件。

## 目录结构

```
MAAskullgirls/
├── AGENTS.md              # 开发约束与按任务查阅的阅读地图
├── PF_BOT.md              # 完整设计文档
├── config.example.json    # 本机参数模板（复制为 config.json 使用）
├── tools/                 # 脚本主体；模块边界见 PF_BOT.md §2.1
├── tools/data/            # 内置游戏数据（variants.json）
├── assets/                # MAA 资源：模板图、OCR 模型、pipeline
└── docs/screenshots/      # 关键界面截图存档
```

## 免责声明

本项目仅供个人学习与自用，与游戏官方无关；自动化脚本存在封号风险，使用产生的一切后果自负。
