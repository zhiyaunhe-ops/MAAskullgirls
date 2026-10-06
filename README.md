# MAAskullgirls

Skullgirls Mobile Prize Fight自动化；MAAFramework驱动MuMu 12。

<img width="1024" height="576" alt="游戏截图" src="https://github.com/user-attachments/assets/60ed957d-4bea-40a4-b417-fb80cda5cbcd" />
<img width="1910" height="931" alt="月场运行展示" src="https://github.com/user-attachments/assets/1e04e6b0-d2bb-4939-933e-62d72b35922b" />

## 它会做什么

| 能力 | 当前行为／限制 |
|---|---|
| PF循环 | 选对手→编队→AUTO战斗→结算→循环 |
| 选人 | 火框优先、倍率排序；无火框按战力 |
| 能量 | 黄钉达门槛可出战；**按场次tag 取（角色场5、其余4）**；不足换人 |
| 元素规则 | 按场次配置筛选；**仅元素场限定队伍元素**（对应元素放左1） |
| 角色规则 | 角色场**防守阵容须含该期角色**；**出战队不受限** |
| 目标／休息 | 达标暂停；达标关MuMu默认开、可取消；每N场休M分钟 |
| 场次条件 | **按tag 归类取**（见下）；不搞父子继承 |
| WebUI | 日志、截图、战绩、场次设置、六主题、MuMu控制 |
| 日程来源 | sgmnow 官方分类；每日先更新场次再做比对 |
| 每日任务 | 保存编排配置；未接入自动执行 |

## 场次条件：按 tag 归类，不搞父子

场次的`score_target`／`energy_cost`／`rule` **一律按 tag 从条件表取**，
不继承任何父场次。tag 来自 **sgmnow 官方分类**（`jjc_store` 抓 Score Cutoffs
表 now 页，按官方口径给出当日各类场次名与是否开放）——**不靠读场地名猜**。

| tag | 分数上限 | 能量 | 队伍规则 |
|---|---|---|---|
| `character` 角色场 | 5000万 | 5 | `class=<当期角色>`，**仅约束防守队** |
| `element` / `rift` 元素场 | 5000万 | 4 | `element=<对应元素>` |
| `monthly_character` 角色月场 | **无上限** | 4 | 无 |
| `monthly_element` 元素月场 | **无上限** | 4 | `element=<对应元素>` |
| 其余（金币／星／招式／未识别） | 4000万 | 4 | 无 |

> **「无上限」= `score_target: null`**，不是漏填 —— 表示一直打到手动停。
> 实测月场总分 1.5～1.9 亿量级，给具体上限必然提前截断。

**每日流程**：先更新 sgmnow 场次 → 扫hub 场地 → 名称相似度（阈值 0.75）比对
定 tag → 按 tag 取条件建场次。**过阈值才认**，判不出就取最保守条件并留痕，
不猜。

> ⚠️ **月场需人工补**：sgmnow 的 Monthly PF 只有一个条目、只给场次名，
> **不区分角色月场／元素月场，也不给元素名**。该信息在
> `tools/data/monthly_manual.json` 人工维护（带 `valid_from`／`valid_until`，
> 每月1 日换新条目）。**每月只需加一条并给上条填 `valid_until`。**

## 环境要求

| 项 | 要求 |
|---|---|
| 系统／模拟器 | Windows／MuMu 12 |
| 游戏 | 英文界面；1280×720横屏；其它语言／分辨率未适配 |
| Python | 3.10+；[requirements.txt](requirements.txt) |
| ADB | config.json的adb_path、address；默认实例地址127.0.0.1:16384 |
| WebUI | 默认http://127.0.0.1:8790；config.json的webui_port可覆盖 |

## 快速开始

~~~bat
pip install -r requirements.txt
python tools/setup_env.py
copy config.example.json config.json
python tools/pf_bot.py
~~~

| 步骤 | 操作 |
|---|---|
| 配置 | 按[config.example.json](config.example.json)修改本机ADB路径／地址；config.json不入仓库 |
| 游戏 | 启动MuMu、进入游戏主界面 |
| 开始 | 打开WebUI；选择／创建场次→开始 |
| 暂停／停止 | 暂停可继续／停止结束进程及WebUI |
| 常驻 | `pythonw tools/pf_tray.py`（托盘遥控器，图标即状态） |
| 一键入口 | 启动PF.bat |

> ⚠️ **改`debug/pf/sessions.json` 前必须先停 bot**：bot 每次采样都会重写整个
> 文件，运行中写入会被下一场战斗静默覆盖。

### 场次条件维护

~~~bash
# 手动触发一次新场流程：先更新 sgmnow → 扫 hub → 定 tag → 建场次 → 起 bot
python tools/pf_schedule.py --action run_new_pf

# 只看会归成什么 tag（不建场次、不起 bot）
python tools/pf_schedule.py --action run_new_pf --params '{"dry_run":true}'

# 存量场次迁移到 tag 口径（幂等，每次先自动备份）
python tools/migrate_sessions_to_tag.py --dry-run   # 先看逐条 before/after
python tools/migrate_sessions_to_tag.py --apply
~~~

可用 action：`run_new_pf` / `run_pf` / `stop_pf` / `explore`；
`--fire <任务名>` 触发 `debug/pf/schedule.json` 里的定时任务。

> ⚠️ **`run_new_pf` / `run_pf` 会先停掉正在跑的 bot**（`restart` 默认开，
> 新场次要独占模拟器）。队列里正在跑的那场会被出队，不再自动接续；
> 分数已落盘不丢，之后手动 `/api/start` 即可续跑。
> 迁移脚本同样会在 bot 运行时拒绝执行（除非 `--force`）。

## WebUI 主题与预览

~~~bash
python tools/preview_webui.py --port 8800
~~~

| 项 | 值 |
|---|---|
| 预览地址 | http://127.0.0.1:8800/ |
| 数据／动作 | 示例分数、存档截图／仅内存更新，不操作模拟器 |
| 依赖／验证边界 | 无需模拟器或第三方Python包；预览成功≠实机验证 |
| 前端 | tools/static/webui.*、themes.*；主题选择保存在浏览器 |
| 检查入口 | python tools/lint_webui.py（需Node）；tests/webui-smoke.mjs（需Playwright／Chromium） |

## 可选增强：sgm 图鉴仓库

| 项 | 位置／作用 |
|---|---|
| 图鉴数据 | 可选克隆[Krazete/sgm](https://github.com/Krazete/sgm)为sgm/，存在时优先读取 |
| 内置数据 | tools/data/variants.json；无需克隆即可运行 |
| 图标 | tools/static/icons/sgm/；[来源声明](tools/static/icons/sgm/ATTRIBUTION.md) |

## JJC 日程与版本追溯

| 入口／数据 | 行为 |
|---|---|
| WebUI查看日程 | 只读本地快照；旧数据标stale |
| 刷新快照／CLI fetch | 显式抓取Score Cutoffs的now页 |
| CLI show | 当天缓存缺失时也会抓取 |
| 配置版本 | 配置指纹变化才记；附已有快照引用，不保证为当天 |
| 详情 | [PF_BOT.md §6.11](PF_BOT.md) |

~~~bash
python tools/jjc_store.py fetch
python tools/jjc_store.py versions
~~~

## 文档

| 任务 | 入口 |
|---|---|
| Agent约束／阅读地图 | [AGENTS.md](AGENTS.md) |
| 实现／排障／历史证据 | [PF_BOT.md](PF_BOT.md)；按章节查阅 |
| 前端全页失效 | [2026-09-24事故](docs/incident-2026-09-24-webui-js-dead.md) |
| 手机脚本 | [phone/autojs/README.md](phone/autojs/README.md) |
| 场次 tag 归类取值 | `tools/pf_artag.py`（条件表+ 归类）；`tests/test_artag.py` |
| 场次条件迁移 | `tools/migrate_sessions_to_tag.py`（幂等，先 `--dry-run`） |
| 新场次流程入口 | `tools/pf_schedule.py` 的 `classify_and_create()` |

## 免责声明

个人学习与自用；与游戏官方无关；自动化存在封号风险，使用后果自行承担。
