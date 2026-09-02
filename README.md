# MAAskullgirls

<img width="1024" height="576" alt="image" src="https://github.com/user-attachments/assets/60ed957d-4bea-40a4-b417-fb80cda5cbcd" />

> 为 **Skullgirls Mobile** —— 这款小众宝藏格斗游戏 —— 打造的 **Prize Fight 全自动循环刷本脚本**（高手专用）

Skullgirls Mobile 是一款被严重低估的 2D 格斗 + RPG 手游：手绘动画帧帧到肉、无限连段系统深不见底，
而 **Prize Fight（流浪防区）** 是它的核心常驻玩法——用你毕生练出的芯片阵容去冲击排行榜。
问题是：每天几十场重复的"选人 → 编队 → 战斗 → 领奖"会把任何高手的手指磨平。

**MAAskullgirls 就是为此而生的**：基于 [MAAFramework](https://github.com/MaaXYZ/MaaFramework)
的 Python 绑定驱动 MuMu 12 模拟器，把整个 PF 循环变成真正的无人值守流水线。

## 它会做什么

```
选对手 ──► 编队 ──► FIGHT ──► 结算领奖 ──► 循环，直到你设定的目标分数
(火框倍率)  (能量判据)  (自动战斗)    (Continue 链)
```

- 🎯 **选人策略**：优先挑战火框（加倍率）对手，无火框时选战力最低的软柿子
- 🔋 **能量判据**：只让能量钉 ≥4 的角色出战，能量不足的槽位自动替换重编
- 🧩 **规则适配**：按当期 PF 规则（元素/职业限制）自动筛选替换芯片
- 📊 **WebUI 控制台**（`http://127.0.0.1:8787`）：实时日志 + 模拟器截图、总分/单场收益/连胜曲线
  （Chart.js 本地托管）、分数上限/能量门槛/连打休息等设置、一键起停
- 💤 **自动休息**：连续 N 场后强制休息，防止过热；达到目标总分自动暂停

**高手专用**：坐标、判据、选人策略、规则替换逻辑全部代码化，欢迎按自己的打法魔改——
这不是给萌新的保姆工具，而是给已经吃透游戏机制的老玩家省时间的生产力工具。

## 环境要求

| 项 | 说明 |
|---|---|
| 模拟器 | MuMu 12（ADB 默认端口 `127.0.0.1:16384`） |
| 分辨率 | **1280x720 横屏**（所有视觉判据基于此） |
| Python | 3.8+，依赖见 `requirements.txt`（MaaFw / numpy / opencv-python） |
| 系统 | Windows（CRT 预载逻辑针对 Windows DLL 解析特性） |

## 快速开始

```bash
pip install -r requirements.txt
python tools/setup_env.py          # 自动下载 OCR 模型 / Chart.js 并自检环境
copy config.example.json config.json   # 按本机 MuMu 安装路径修改 adb_path
python tools/pf_bot.py             # 启动后打开 WebUI 点「开始」
```

启动 MuMu 12 并进入游戏主界面，浏览器打开 <http://127.0.0.1:8787> 即可接管。

> 本机参数（adb 路径/端口）只放在 `config.json`，**该文件不入仓库**（已在 `.gitignore`），见 `config.example.json`。

## 可选增强：sgm 图鉴仓库

把 [Krazete/sgm](https://github.com/Krazete/sgm)（Skullgirls Mobile 图鉴）克隆到项目根目录命名为 `sgm/`，
可解锁 WebUI 的元素/职业图标并优先使用其变体数据。不克隆也能跑——`tools/data/variants.json`
已内置 306 个变体的数据副本。

## 文档

完整的设计文档（界面几何、结算链、弹窗处理、anaconda CRT 坑等踩坑实录）见 **[PF_BOT.md](PF_BOT.md)**。

## 目录结构

```
MAAskullgirls/
├── PF_BOT.md              # 完整设计文档
├── config.example.json    # 本机参数模板（复制为 config.json 使用）
├── tools/                 # 脚本主体（pf_bot / pf_env / pf_vision / pf_webui / pf_store）
├── tools/data/            # 内置游戏数据（variants.json）
├── assets/                # MAA 资源：模板图、OCR 模型、pipeline
└── docs/screenshots/      # 关键界面截图存档
```

## 免责声明

本项目仅供个人学习与自用，与游戏官方无关；自动化脚本存在封号风险，使用产生的一切后果自负。
