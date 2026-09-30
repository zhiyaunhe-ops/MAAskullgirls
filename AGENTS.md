# MAAskullgirls：Agent入口

| 阅读策略 | 约定 |
|---|---|
| 默认 | 本文件→任务相关代码／PF_BOT章节；历史证据按症状读取 |
| 文档职责 | README：使用；PF_BOT：实现／排障；事故／探索报告：带日期证据 |
| 维护 | 更新已有主题；局部更新条目；不新增单次任务总结或重复规则 |
| 记录字段 | 条件／行为或失败／原因／代码或提交证据；未验证项明确标注 |

## 任务阅读地图

| 任务 | 代码 | 文档 |
|---|---|---|
| 环境／连接 | pf_native、pf_env | README；PF_BOT §1 |
| 战斗／能量／选人 | pf_bot、pf_vision | PF_BOT §§3–6 |
| 纯规则／计分 | pf_domain | PF_BOT §§2.1、6.5 |
| 存储／快照 | pf_storage、pf_store、jjc_store | PF_BOT §§6.5a、6.11 |
| 导航／调度／托盘 | pf_scene、pf_schedule、pf_tray | PF_BOT §§6.9–6.10、8 |
| 重生／退出路径 | pf_bot（重生、硬退出、退出看门狗）、pf_webui（端口交接） | PF_BOT §§6.0、8-26~27；tests/test_respawn_exit.py |
| WebUI／API | pf_webui、static/webui.*、themes.* | README；PF_BOT §6.7 |
| 每日任务清单 | static/webui.js | docs/explore/2026-09-05/REPORT.md（历史；explore/screenshots 2026-09-30 起本地不入库） |
| 全页按钮失效 | static/、lint_webui.py | docs/incident-2026-09-24-webui-js-dead.md |
| 手机脚本 | phone/autojs/ | phone/autojs/README.md |
| 文档／记忆维护 | 相关主题及Git历史 | PF_BOT §10 |

代码路径默认相对tools/；phone/路径相对仓库根。

## 必须保留的边界

| 对象 | 约束 |
|---|---|
| 原生导入 | preload_msvcrt()先于cv2／MAA；pf_env兼容导出保留 |
| 纯工具 | pf_domain无I/O／运行时依赖；导入pf_store会初始化STORE |
| 隔离存储 | pf_storage.ScoreStore显式依赖；目录与账本同时隔离 |
| 设备连接 | config.json／resolve_adb()；同一地址；UTF-8子进程解码；adb 用与 K70 巡检同一份 platform-tools（版本统一，别再换回 MuMu 的）；adb server 分端口 5038（config adb_server_port→pf_env 注入 env，巡检 kill-server 踢不到） |
| 视觉 | CPU OCR、标定阈值、ROI语义、检测优先级；变更须任务依据 |
| MuMu／进程 | MuMuManager关机；保留WMI脱离宿主与HTTP服务身份校验 |
| 调度 | 串行锁仅单进程；不得描述为跨进程租约 |
| 对外兼容 | CLI、HTTP、JSON／CSV、旧领域导入名 |
| 访问门卫 | 保留真实bot的IP／Host／Origin检查 |
| JJC | UI普通读取不触网；刷新显式；CLI show缺缓存可触网 |
| 外部点击 | 导航前停bot；非运行态仍可能清弹窗 |
| 验证 | 预览为模拟数据；按用户范围验证；仅报告实际执行结果 |
