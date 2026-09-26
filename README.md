# MAAskullgirls

Skullgirls Mobile Prize Fight自动化；MAAFramework驱动MuMu 12。

<img width="1024" height="576" alt="游戏截图" src="https://github.com/user-attachments/assets/60ed957d-4bea-40a4-b417-fb80cda5cbcd" />
<img width="1910" height="931" alt="月场运行展示" src="https://github.com/user-attachments/assets/1e04e6b0-d2bb-4939-933e-62d72b35922b" />

## 它会做什么

| 能力 | 当前行为／限制 |
|---|---|
| PF循环 | 选对手→编队→AUTO战斗→结算→循环 |
| 选人 | 火框优先、倍率排序；无火框按战力 |
| 能量 | 黄钉达门槛可出战；默认4、可配置；不足换人 |
| 元素规则 | 按场次配置筛选；class判定未实现 |
| 目标／休息 | 达标暂停；达标关MuMu默认开、可取消；每N场休M分钟 |
| WebUI | 日志、截图、战绩、场次设置、六主题、MuMu控制 |
| 每日任务 | 保存编排配置；未接入自动执行 |

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
| 一键入口 | 启动PF.bat |

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

## 免责声明

个人学习与自用；与游戏官方无关；自动化存在封号风险，使用后果自行承担。
