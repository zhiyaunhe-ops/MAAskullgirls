# MAAskullgirls：实现与排障索引

| 字段 | 值 |
|---|---|
| 当前行为 | §§1–6.11、6.13；实现定位见各表 |
| 历史观察 | §6.12、§7；本次未重跑、不保证当前排期 |
| 开发／使用 | [AGENTS.md](AGENTS.md)／[README.md](README.md) |
| 历史全文 | git show 3689d56:PF_BOT.md |
| debug证据 | 可能轮转、未入仓库；记录路径不代表本次已核验 |

## 1. 环境与依赖

| 项 | 值／约束 |
|---|---|
| 运行 | Windows；MuMu 12；英文游戏界面；1280×720横屏 |
| 依赖／安装 | [requirements.txt](requirements.txt)／[README.md](README.md#快速开始) |
| ADB／端口 | config.json → pf_env.resolve_adb()／WEBUI_PORT；端口默认8790 |
| OCR | CPU；det.onnx、rec.onnx、keys.txt均须保留 |
| 原生导入 | pf_native.preload_msvcrt() → cv2／maa；非Windows直接返回 |
| CRT风险 | Python目录旧CRT→可能WinError 1114；历史环境条件，非必现 |

## 2. 目录结构

| 文件／目录 | 职责 |
|---|---|
| tools/pf_native.py／pf_env.py | CRT／配置、BotState、MuMu、进程、清理 |
| tools/pf_domain.py／pf_storage.py／pf_store.py | 纯规则／显式依赖存储／运行时装配与兼容 |
| tools/pf_vision.py／pf_bot.py | cv2识别与几何／监督循环、选人、编队、战斗 |
| tools/pf_webui.py／static/ | HTTP、API／HTML、CSS、JS、主题、图标 |
| tools/pf_scene.py／pf_schedule.py／pf_tray.py | 导航／调度／托盘 |
| tools/jjc_store.py | 日程快照、配置版本 |
| assets/resource/base/ | post_bundle资源根；pipeline、image、model |
| [docs/screenshots/](docs/screenshots/) | 界面参考 |

| 数据（debug/pf/相对路径） | 内容 |
|---|---|
| sessions.json／score_log.csv | 场次配置／逐场采样 |
| jjc/snapshots/YYYY-MM-DD.json | 游戏日快照 |
| jjc/latest.json／versions.json | 最近快照引用／配置账本 |
| schedule.json／schedule_state.json／schedule.log | 任务／去重／日志 |
| run/时间戳/／bot_stdout.log | 截图／输出 |
| cleanup_debug() | 每10分钟；图片目标150MB、日志目标50MB；活动日志保留，容量非硬保证 |

## 2.1 模块边界与演进方向

| 边界 | 约束 |
|---|---|
| Domain | 不依赖MAA、cv2、运行时、存储、HTTP；无I/O |
| Storage | 显式data_dir、logger、version_ledger、jjc_ref；实例化会加载／迁移 |
| Runtime | 导入pf_store初始化STORE；纯工具用pf_domain／pf_storage |
| 隔离 | 换数据目录时同步隔离账本依赖 |
| 兼容 | CLI、HTTP、JSON／CSV、旧领域名称导出 |

| 未解决项 | 风险／候选方向（未实现） |
|---|---|
| WebUI直接协调STATE／STORE／MuMu | 状态边界→SessionService、原子快照 |
| Store字典外泄、部分更新先于锁 | 并发一致性→事务隔离 |
| 调度锁仅进程内 | 多进程争设备→租约 |
| PfScene复用PfBot.setup() | 重复清理线程→可关闭会话 |
| 卡名／分数可能不同帧 | 动画混读→同帧观察 |

## 3. 游戏界面与坐标（1280x720）

| 对象 | 指纹／定位 |
|---|---|
| 对手页 | REFRESH；pf_vision.OPPONENT_CARDS、SCORE_ROI、STREAK_ROI |
| VS／编队 | FIGHT!／DRAG提示；pf_bot.fight_flow() |
| 出战／候选几何 | pf_vision.SLOT_DROP_X、SLOT_PIP_CENTERS、ROSTER_CENTERS |
| hub／筛选 | pf_scene.ROI_HUB_PLAY／pf_bot.FILTER_*、ELEMENT_CHIPS、CLASS_CHIPS |

| 约束 | 原因／处理 |
|---|---|
| 项目ROI=(x0,y0,x1,y1) | MAA=(x,y,w,h)；PfBot.to_maa_roi()转换 |
| 落点≠能量条中心 | 卡面扇形；能量条等距 |
| X按弹窗分别定位 | 能量／连胜／OPTIONS／促销尺寸不同 |
| 分辨率变化 | 重新标定坐标／模板 |

## 4. 能量规则（用户确认）

| 字段 | 值 |
|---|---|
| 出战 | 黄钉数≥STATE.energy_cost；默认4、随场次 |
| 红钉 | 不计入能量 |
| 历史消耗 | 2026-09-02：10→6→2；非所有模式保证 |
| 池耗尽 | 失败停机；未实现自动等待回能 |

## 5. 视觉方案（pf_vision.py）

| 对象 | 实现／限制 |
|---|---|
| 火框 | find_fire_badge()：红连通域＋角部约束；0.04的has_fire_box()非当前选人路径 |
| 倍率 | 动态定位→裁图→候选OCR→parse_mult()；固定ROI可能截数字 |
| 战力／总分 | OCR→parse_power()；仍可能错读 |
| 能量 | 高亮黄掩码→列占比→游程；非任意底色保证 |
| 元素复验 | pf_bot._candidate_is_element()；光／中性不按颜色区分 |
| 模板定位（原§5.4） | assets/resource/base/image/pf/；引用以pf_bot／pf_scene的TPL_*为准 |
| pip_*模板 | 历史调研；能量主路径用颜色计数 |

## 6. 机器人流程（pf_bot.py）

| ID | 条件／对象 | 动作／边界 |
|---|---|---|
| 6.0 | 进程启动／api/start | IDLE等待／开始或同场恢复；必要时起MuMu＋adb connect |
| 6.0 | api/pause／api/stop | PAUSED保进程／quit=True退出、WebUI关闭 |
| 6.0 | 达标／ERROR | PAUSED、可关MuMu／running=False、人工检查后可重开 |
| 6.0 | IDLE／暂停 | 仍约每2s清弹窗；不代表设备无动作 |
| 6.1 | step优先级 | 弹窗X→服务器错误→详情→hub→防守队→REFRESH→编队→VS→结算→未知 |
| 6.1 | KEEP STREAK? | 关X；不点CONFIRM／WATCH AD |
| 6.1 | 未知界面 | 等待→交替返回／右上X；单按左上可能开OPTIONS |
| 6.1 | 防守队失败 | 本轮不重复处理；检查截图 |
| 6.2 | 有火框／无火框 | 倍率降序、未知倍率排后／战力升序 |
| 6.2 | OCR战力<100 | 启发式×1000；可能误判真实低值 |
| 6.3 | 首次编队 | 清残留筛选、按喜爱归位；候选列归零 |
| 6.3 | 规则不满足 | FIGHT复验、逐槽补人，最多三个槽 |
| 6.3 | 已保障规则后缺能量 | 槽1先补规则角色；槽2／3纯能量替换 |
| 6.3 | 拖拽失败／池空 | 复读、归零＋微调、最多30页／返回失败 |
| 6.4 | 规则格式 | null或{"type":"element","value":"wind"}；pf_domain规范化 |
| 6.4 | 自动规则／人工class | resolve_arena()仅收element／WebUI可配class，但judge_rule()恒False |
| 6.4 | FIGHT亮色≥15% | 当前合规代理信号；灰色也可能缺能量，非元素违规的独立证据 |
| 6.4 | 筛选 | 清空→规则＋喜爱→关闭验证→元素复验→拖入→恢复喜爱 |
| 6.4 | 无规则 | 跳过规则判断；首次筛选归位仍执行 |
| 6.5 | 采样 | 对手页、fight_no锚定；零收益保留；同场续跑保留基线 |
| 6.5 | CSV | time,fight_no,score,delta,streak,session；兼容解析：pf_storage |
| 6.5 | 活跃收益率 | 相邻采样间隔≤3分钟；tools/static/webui.js |
| 6.5a | 场次／全局配置 | 名称、规则、目标分、能量、休息／喜爱开关 |
| 6.5a | 子场／删除父场 | 继承配置、独立采样／子场保留转顶级 |
| 6.5a | 运行中／default | 限制切换、编辑、删除／default不可删 |
| 6.5a | 删除场次 | 内存曲线移除；CSV、账本保留；历史每场内存最多3000点 |
| 6.6 | 拖拽／滚动／筛选 | 落点驻留350ms／分段停顿／驻留点击＋关闭复验 |
| 6.7 | 前端／API | tools/static/webui.*、themes.*／pf_webui._Handler |
| 6.7 | MuMu动作／关机 | 后台线程＋锁、HTTP返回非完成信号／MuMuManager shutdown |
| 6.7 | 状态探测 | player_state／state／is_android_started；失败unknown |
| 6.7 | 达标关机 | 默认开、可取消；_goal_closed防重复 |
| 6.7 | 每日任务 | 保存编排；未接入执行 |
| 6.8 | AUTO／3x | 每实例首战检查；亮度阈值75、速度模板0.85；失败告警继续 |
| 6.9 | goto／explore／center | 冷启动导航／扫卡并恢复居中／目标居中；bot只点居中PLAY! |
| 6.9 | 标题／无SCORE行 | 常规＋备用ROI、候选择优／已知标题命中可判0 |
| 6.9 | score=0／外部导航 | 新场候选，非确证／先停bot；暂停仍清弹窗 |
| 6.10 | schedule.json／ACTIONS | 配置与完整动作列表：pf_schedule.py |
| 6.10 | 扫描／去重 | 20s、默认宽限90分钟／按日先记触发再执行；失败不保证重试 |
| 6.10 | 串行 | _ACTION_LOCK仅单进程；取锁后重查状态；不保证FIFO |
| 6.10 | --fire／--action | 等完成／硬超时；_timeout默认480s |
| 6.10 | bot启动 | breakaway被拒→WMI；HTTP核验svc；占端口报错、不自动换端口 |
| 6.10 | MuMu启动 | 优先WMI；普通Popen回退仍有宿主连带终止风险 |

## 6.11 JJC 日程与配置版本

| 入口 | 触网／缓存行为 |
|---|---|
| GET /api/jjc、peek()、配置记账 | 不触网；可引用旧快照，核对day／stale |
| POST /api/jjc/refresh、CLI fetch | 显式触网 |
| CLI show、today() | 缺当天缓存时抓取；失败可返回旧快照＋stale |

| 数据规则 | 实现／限制 |
|---|---|
| 来源／定位 | Score Cutoffs的now页；SOURCE_URL；按标签正则；必需PF标签缺失报错 |
| Loading／Inactive | 名字位计算中／正常未活动；严格重试后可留空＋volatile |
| 游戏日 | Pacific 10:00；sgm_day()；tzdata缺失用DST算法兜底 |
| raw_rows／revisions[] | 当前抓取原行／各次ts、fp、last_edit；非完整旧原文归档 |
| revision／fetches | 内容变化次数／抓取次数 |
| 配置账本 | CFG_FIELDS变化才记；运行分数不入账 |
| 导入／删除／失败 | import基线／delete保留历史／告警不阻断主流程 |

## 6.12 PF 轮换规律（历史观察，2026-09-16起）

| ID | 旧观察／来源 | 范围／限制 |
|---|---|---|
| 6.12.1 | 角色同名63天、周一换对；元素35天、周六 | 2024年后日期样本；2023角色样本不符 |
| 6.12.1 | Medici周三、SMYM周六，同类型14天；Stars周五、Monthly每月1日 | 旧表观察；本次未抓取 |
| 6.12.2 | 角色九对循环；前者周一–三、后者周四–六 | [社区日历](https://forum.skullgirlsmobile.com/threads/prize-fight-calendar.22233)；整轮未验证 |
| 6.12.2 | 游戏日2026-09-17／21／24：Marie／Annie／BigBand | 对应日期支持；截图引用见arena_rules.json |
| 6.12.3 | Water→Fire→Wind→Light→Dark | 社区候选；整轮未验证 |
| 6.12.4 | 快照覆盖不完整；2026-09含角色月场＋元素月场 | 不能按快照数量推hub卡数 |
| 6.12.4 | 名字≠类别／规则；旧A／B／C混用不同证据 | 查逐条依据；错父场可能继承错配置 |
| 6.12.5 | 显式刷新→核对day／stale／volatile→比较→hub确认→核对配置→建子场 | 快照未变≠无新活动；未映射需确认 |
| 6.12.6 | DIAMOND等混入标题；下移ROI截断两行名、硬删GOLD破坏GOLD RUSH | read_center_card()保留原串、候选择优 |
| 6.12.7 | 用户约定2026-09-16／20：元素筛选、防守左1对应元素 | 角色名不生成class规则；元素月场可有element |
| 6.12.8 | medici预填表2026-08–10：周三Jinx／Hemofilia交替 | 预填非开放保证；09-16／23两次快照支持 |
| 6.12.8 | character2／element页当时停更 | 不推导当前仍未更新 |

~~~text
角色旧候选循环：
Double/Squigly → Valentine/Fukua → Eliza/Marie → Annie/Big Band
→ Cerebella/Robo-Fortune → Ms.Fortune/Umbrella → Filia/Parasoul
→ Beowulf/Peacock → Painwheel/BlackDahlia → 循环
~~~

| 证据入口 | 内容 |
|---|---|
| [arena_rules.json](tools/data/arena_rules.json) | 场名映射、规则、父场、逐条依据 |
| git show 3689d56:PF_BOT.md | 原日期推算、用户口径、快照／截图路径 |

## 6.13 远程访问

| 路径／症状 | 检查 |
|---|---|
| HTTPS代理／HTTP直连 | tailnet主机名＋serve端口／设备IP＋WEBUI_PORT |
| 502／403 | bot与后端端口／_gate()拒绝原因：IP、Host、POST Origin |
| Host允许 | IP、localhost、无点主机名、.local／.lan／.ts.net；不等于身份认证 |
| 本机配置 | 主机名、IP、端口以本机为准；下列默认后端为8790 |
| 原§6.13.1 | 2026-09-17代理域名被拒：当前允许.ts.net；改校验后重启 |
| 原§6.13.1 | 2026-09-19：IP＋serve端口400；区别于IP＋后端端口，非通则 |

~~~bash
tailscale serve --https=8444 --bg http://127.0.0.1:8790
tailscale serve status
tailscale serve --https=8444 off
~~~

## 7. 实测记录（历史；本次未重跑）

| 日期 | 原记录结果 | 范围 |
|---|---|---|
| 2026-09-02 | 12场11胜；18次拖拽成功；倍率离线9/9 | 当时样本，非长期成功率 |
| 2026-09-03（原§7.1） | 风规则32+场、连胜17+；筛选／阈值／规则槽修正 | 当时规则场 |
| 2026-09-06（原§7.2） | explore五卡；热启动约35s；02:20调度触发 | 当时场景，非冷启动性能保证 |
| 2026-09-06 05:00 | 一次stop_pf完成 | 不代表当前jobs为空 |
| 2026-09-24 | WebUI转义修复 | [事故证据](docs/incident-2026-09-24-webui-js-dead.md) |

## 8. 踩坑实录（按触发条件检索）

| ID | 触发／失败 | 规避／定位 |
|---|---|---|
| 1 | Python目录旧CRT→初始化失败 | 原生导入前preload_msvcrt；§1 |
| 2 | 端点ROI直接传MAA | to_maa_roi；§3 |
| 3 | post_bundle根错误 | resource/base；§2 |
| 4 | only_rec缺det.onnx | det／rec／keys；§1 |
| 5 | 非ASCII路径cv2 I/O失败 | imencode＋write_bytes／fromfile＋imdecode |
| 6 | 遮罩下背景仍命中 | 弹窗优先；§6.1 |
| 7 | 共用X尺寸／ROI | 按弹窗模板；§3 |
| 8 | VS入场站位变化 | 按能量条；§4 |
| 9 | 多显卡DirectML旧故障 | CPU OCR；§1 |
| 10 | 延迟动作落入新界面 | 交替返回／X；§6.1 |
| 11 | 跨运行筛选残留 | 首次编队归位；§6.3 |
| 12 | 双实例争端口／设备 | svc校验、占端口报错；§6.10 |
| 13 | 瞬点未生效／遮罩未关→能量0 | 驻留＋关闭／元素复验；§6.4 |
| 14 | 显示名查内部键；框色误作元素 | variants字段映射；FIGHT／筛选复验 |
| 15 | bot与外部点击竞争 | 导航前停进程；暂停仍清弹窗 |
| 16 | hub滑动吸附／跨卡 | 场景ADB滑动；pf_scene |
| 17 | 促销X≠OPTIONS X | scene_popup_x；pf_scene |
| 18 | 宿主整树结束→bot／MuMu退出 | breakaway被拒用WMI；普通回退仍有风险 |
| 19 | /api/state 200属于其它服务 | svc=sgm-pf-bot身份校验 |
| 20 | Python吞JS转义→全页按钮无响应 | 独立JS＋语法检查；[事故](docs/incident-2026-09-24-webui-js-dead.md) |

## 9. 常用操作

| 任务 | 命令／定位 |
|---|---|
| 安装／启动 | [README快速开始](README.md#快速开始)；启动PF.bat |
| 导航 | python tools/pf_scene.py goto |
| 扫描／居中 | python tools/pf_scene.py explore；python tools/pf_scene.py center EYE |
| 调度 | python tools/pf_schedule.py；--list；--fire 任务名 |
| 截图 | python tools/screencap.py |
| 日程 | python tools/jjc_store.py fetch；show；days；versions（子命令分别执行） |
| 排障 | debug/maa/debug/maafw.log；debug/pf/bot_stdout.log；debug/pf/run/ |

## 10. 文档维护

| 主题 | 唯一维护入口 |
|---|---|
| 通用规则、提示词模板、研究依据、语言token计数 | [agent-context-guidelines](https://github.com/zhiyaunhe-ops/agent-context-guidelines) |
| 本项目压缩与事实校正记录 | [f24de46](https://github.com/zhiyaunhe-ops/MAAskullgirls/commit/f24de4636785f85fdec29264912c7905e31b74bf)；实机／浏览器未重跑 |
