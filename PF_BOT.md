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
| adb server 分端口 | config.json `"adb_server_port": 5038` → pf_env import 时注入 `ANDROID_ADB_SERVER_PORT`，全部 adb 客户端与 MAA 子进程继承，整链独立 server；缺省0=旧版行为(5037)。与 TailShare K70 巡检解绑（事故28），巡检的 kill-server 只影响 5037 |
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
| docs/screenshots/、docs/explore/ | 界面参考／探索报告（2026-09-30 起本地不入库：游戏截图含账号信息） |

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
| 6.0 | api/end／api/stop | 结束场次回IDLE进程保留／quit=True进程退出WebUI关闭(托盘·调度依赖) |
| 6.0 | api/pause／api/stop | 已废：暂停态移除(2026-09-27)，只剩在跑/结束 |
| 6.0 | 达标／ERROR | 结束场次回IDLE、可关MuMu／running=False、人工检查后可重开 |
| 6.0 | IDLE(非运行) | 仍约每2s清弹窗；不代表设备无动作 |
| 6.0 | IDLE截图卡死 | 全路径截图带超时10s＋每分钟告警；停止·开始仍可响应，不随MAA内部重连一起等 |
| 6.0 | api/mumu/game | 先adb connect再monkey；device not found时补连重试一次 |
| 6.0 | MAA僵死(截图自愈穷尽) | LinkDead→自我重生：新进程SGM_PF_RESUME=1自动续跑＋重套场次配置；5min·日20次限流；重生后老进程硬退出(os._exit，不等MAA销毁)；用尽／受限一律彻底退出(code 3)等人工 |
| 6.0 | 重生日志 | 每代进程另写 debug/pf/bot_stdout_年月日-时分秒.log；共用 bot_stdout.log 会被老进程的重定向句柄挡住(cmd报占用→python未启动→静默)；cleanup_debug修剪旧世代 |
| 6.0 | 退出路径 | run()返回后挂25s退出看门狗→MAA销毁卡死则强制 os._exit(0)；修09-27「点停止只留一行日志、端口不还」 |
| 6.1 | step优先级 | 弹窗X→服务器错误→详情→hub→防守队→REFRESH→编队→VS→结算→未知 |
| 6.1 | KEEP STREAK? | 关X；不点CONFIRM／WATCH AD |
| 6.1 | step优先级 | 弹窗X→服务器错误→详情→hub→防守队弹窗→防守编辑器(CONFIRM)→REFRESH→编队→VS→结算→未知 |
| 6.1 | KEEP STREAK? | 关X；不点CONFIRM／WATCH AD |
| 6.1 | 未知界面 | 等待→交替返回／右上X；单按左上可能开OPTIONS |
| 6.1 | 防守队入口 | 弹窗(点OK进)与编辑器(CONFIRM)两个入口共用；编辑器页无OK, 乱点会碰名册 |
| 6.1 | 防守队失败 | 本轮不重复处理（_defense_done）；看截图 run/日期/防守队确认前 |
| 6.2 | 有火框／无火框 | 倍率降序、未知倍率排后／战力升序 |
| 6.2 | OCR战力<100 | 启发式×1000；可能误判真实低值 |
| 6.3 | 首次编队 | 清残留筛选、按喜爱归位；候选列归零 |
| 6.3 | 规则不满足 | FIGHT复验、逐槽补人，最多三个槽 |
| 6.3 | 已保障规则后缺能量 | 槽1先补规则角色；槽2／3纯能量替换 |
| 6.3 | 拖拽失败／池空 | 复读、归零＋微调、最多30页／返回失败 |
| 6.4 | 规则格式 | null／{"type":"element","value":"wind"}／{"type":"class","value":"Cerebella"}；pf_domain规范化 |
| 6.4 | class规则含义 | 角色场**防守队**限定该角色（2026-09-29用户口径；arena_rules defense_char）；出战队不受限——judge_rule对class恒True（旧"恒False"是空烧桩，已废） |
| 6.4 | 防守队左1 | element→对应元素角色；class→该角色（CHARACTER_CHIPS筛选＋拖居中候选＋槽位角色名OCR复验，put_char_in_defense）；拖拽/复验待下次角色场实机验证 |
| 6.4 | 防守编辑器 | 版式与常规编队不同：三槽居中（落点440/640/840）、无能量黄钉、右上CONFIRM（pf/defense_confirm.png）；不合规=红禁止标＋CONFIRM灰点不动 |
| 6.4 | 锁定弹窗 | 角色场FIGHT!后一次性"locked into the Diamond"确认（无X，CANCEL/CONTINUE）：正文OCR两探针判定＋CONTINUE(748,576)；离线冒烟正命中/三类帧零误报，实机路径未验证 |
| 6.4 | FIGHT亮色≥15% | 当前合规代理信号；灰色也可能缺能量，非元素违规的独立证据 |
| 6.4 | 筛选 | 清空→规则＋喜爱→关闭验证→元素复验→拖入→恢复喜爱（防守队无能量/元素复验，改角色名复验） |
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
| 6.7 | ADB连接设置 | GET/POST /api/adb_config 读写 config.json 四件套(adb_path/address/adb_server_port/mumu_dir)；校验+告警；pf_env是import期读→**重启生效**；前端「模拟器 & ADB」面板 |
| 6.7 | 托盘启动≠开跑 | 「启动服务」只起进程(2026-09-30用户口径)；开跑=托盘「开跑当前场次」或WebUI「开始」；托盘自启即默认启动服务+Windows气泡(_notify→icon.notify)；图标=tools/static/icons/tray_icon.png(Filia头像裁剪, 缺文件回退圆角方块)+右下角状态点；启动器=启动托盘.vbs(ASCII-only：wscript按ANSI解析, UTF-8中文注释静默失败；pythonw需全路径, cscript PATH无anaconda)；重生auto-resume不受此限(接管死前场次) |
| 6.7 | 每日任务 | 保存编排；未接入执行 |
| 6.7 | 目标ETA | 速率=本场记分点活跃段(相邻≤180s)增量÷时长；ETA=(目标−当前)÷速率；未设目标只显速率；页面前端现算, /api/summary只供速率 |
| 6.8 | AUTO／3x | 每实例首战检查；亮度阈值75、速度模板0.85；失败告警继续 |
| 6.9 | goto／explore／center | 冷启动导航／扫卡并恢复居中／目标居中；bot只点居中PLAY! |
| 6.9 | 标题／无SCORE行 | 常规＋备用ROI、候选择优／已知标题命中可判0 |
| 6.9 | 角色场卡标题 | OCR整卡失败（读'O'/噪声，HIGH WIRE HIJINKS 两次实测）→center()不可用；hub不循环、最右=角色周场，按位置滑 |
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
| 2026-09-28 | 20:55 重生空枪现场(僵尸占端口＋日志句柄)；修复后回归6/6绿 | tests/test_respawn_exit.py；未在真实僵死MAA上复跑重生成功 |

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
| 21 | adb kill-server死等→截图永不返回，停止·开始全失效 | 全路径截图超时＋断链自愈(连续失败补adb connect，2026-09-27掉线实测)＋卡住告警；§6.0 |
| 22 | 模拟器报就绪≠adb有该设备→拉起游戏device not found | monkey前补connect＋重试一次；§6.0 |
| 23 | MuMu 16384 TCP桥间歇假死(TCP可连·adbd协议不应答；emulator-5554通道全程健在, 12:54实测) | config用5555通道；adb_connect自动映射emu→127.0.0.1:N+1补连。**2026-09-29修**: emulator-N别名只在"adb server启动时MuMu已在跑"才被console探测发现——server先起/MuMu后起就永远不补（MuMu重启+巡检换server实测别名消失, MAA三连失败）→ config 改 `127.0.0.1:5555`（同一通道）, 断线后普通 adb connect 即自愈 |
| 24 | 重启后场次绑定丢失→托盘"无可用场次"；MuMu未就绪窗口→bot首连失败即退 | sessions.json持久化active；setup补连+重试3次；tray读status=ERROR报真实原因 |
| 25 | MAA内部作业僵死(kill-server死等)→进程内补连救不回, 只能换进程 | LinkDead→自我重生带RESUME续跑(限流5min·日20次)；5554通道夜间也会掉, 别当稳态 |
| 26 | 重生空枪：老进程"退出"其实卡在MaaControllerDestroy(2026-09-28 20:55现场，进程10min+不退) | 老进程硬退出＋每代独立stdout日志＋端口等10s重试；见 §6.0 三行 |
| 27 | 僵尸进程占端口/日志 → 新进程静默死(页面 Failed to fetch) | 日志只有「自我重生/本进程退出」而无新一代启动三连即此症；netstat找pid后 taskkill /F |
| 28 | adb server 换代：**TailShare 服务的 K70 巡检**（LocalSystem/session 0，每 30min 一轮、重连前必跑 kill-server+start-server）与 bot 抢 5037 | 21:26 风暴与巡检自己的 `tailshare\data\k70pro\watch.log` 逐条对上（21:26:05 掉线→21:26:42/55、21:27:07/11 重试，每步都 spawn 一个 session-0 adb）；00:28/20:54:54 同源。旧 server 被摘监听却不退 → MAA kill-server 死等 19min（杀该 client 即解冻）；5037 server 本体别杀；session 0 进程非管理员杀不掉。**2026-09-28 处置**：bot 的 adb 从 MuMu v36 换成与巡检同一份 platform-tools v37（config.json adb_path，resolve_adb 加了"文件不存在就回退自动探测"）；实测两者协议号相同(41)**不会**互踢。**残留**：巡检的 kill-server 仍会踢掉共享 5037 → 根治要分端口（5041-5045 空闲，`ANDROID_ADB_SERVER_PORT`）。**2026-09-29 复现**: 11:52-12:01 巡检 adb 风暴(父链 services.exe, 每~15s 一个)→12:05 bot 截图卡死→12:11 ERROR(49.6M 处)；巡检 kill-server 仍在 `watch_k70pro.py:200`。**2026-09-30 根治**：按残留方案分端口落地 —— config.json `"adb_server_port": 5038`，pf_env import 时注入 `ANDROID_ADB_SERVER_PORT`（所有模块都先 import pf_env，adb 客户端与 MAA 子进程全继承；WMI 脱离的下一代 pf_bot 自行重读 config，spawn 点无需传 env）；实测 5038 server 独立 connect/MAA 截图正常，巡检 kill-server 从此只影响 5037 |
| 29 | 大厅促销弹窗加宽款(30-DAY DAILY PASS)X中心(1012,157)在 ROI_MODAL_X 右界1000之外(40px模板框不下, 同帧实测分0.201)→三路关窗全盲, wait_hall 150s超时 | ROI_MODAL_X 1000→1060（仍排除 OPTIONS X@1164）；同帧放宽后 0.963/次峰0.209 |
| 30 | pf_scene 自带清理器把**待命中 bot 进程**的 run_dir 当旧目录整删（protected 只护调用者自己）→ bot 恢复运行写帧 FileNotFoundError 直接 ERROR 停摆（01:17 实测） | cleanup_debug 加"还在写"窗口 LIVE_WINDOW_S=30min 不删活动目录；snap() 补建目录+存档容错（帧是调试产物, 不许带崩主循环） |
| 31 | 防守队编辑器**无能量黄钉**（黄钉是出战队概念）→ 常规编队把能量全读 0 空烧到「无可用能量角色」停摆；且确认钮是 CONFIRM 不是 FIGHT/CONTINUE, 老代码报「都找不到」 | step() 加 CONFIRM 分支（排在 DRAGHINT 前）+ setup_defense_team 双入口 + 新模板 pf/defense_confirm.png；见 §6.1/§6.4 |
| 32 | adb_path 指向别项目 platform-tools 后, "adb 同目录"找 MuMuManager 必然落空 → 达标关机失败(09-28 23:54)、「MuMu 未运行」假报(01:17/06:04)、冷启动废 | config.json 加 `"mumu_dir"`（mumu_paths 同目录探测失败后回落）；实测恢复 start_finished 探测与关机 |

## 9. 常用操作

| 任务 | 命令／定位 |
|---|---|
| 安装／启动 | [README快速开始](README.md#快速开始)；启动PF.bat |
| 导航 | python tools/pf_scene.py goto |
| 扫描／居中 | python tools/pf_scene.py explore；python tools/pf_scene.py center EYE |
| 调度 | python tools/pf_schedule.py；--list；--fire 任务名 |
| 截图 | python tools/screencap.py |
| 日程 | python tools/jjc_store.py fetch；show；days；versions（子命令分别执行） |
| 排障 | debug/debug/maafw.log；debug/pf/bot_stdout.log 与重生世代 debug/pf/bot_stdout_*.log；debug/pf/run/；端口占用 netstat -ano findstr 8790；adb 侧 Get-CimInstance Win32_Process -Filter "Name='adb.exe'" 看 SessionId/创建时间（session 0=别的服务拉起的，5555=guest adbd 连接）；**谁在动 adb**：K70 巡检日志 D:\Downloads\shared_file\tailshare\data\k70pro\watch.log（与 bot 共享 5037）；清僵尸 adb 要**管理员** Stop-Process -Id <pid> -Force（普通权限报"拒绝访问"），但 5037 上 LISTENING 的那个是活 server，别杀 |
| 回归测试 | anaconda python -m pytest tests/test_respawn_exit.py（重生/彻底退出/看门狗/端口交接；需 bot 停着） |
| 观测 adb server 换代 | python tools/_probe_adb_watch.py --detach；--report 看事件清单(父链/监听者/session/bot日志)；--stop 停。只旁观不调 adb（调 adb 会自己拉起 server 污染观测） |
| 30分钟巡检 | anaconda python debug/pf/_watch30.py [次数=16] [间隔秒=1800]；追加 debug/pf/watch_30min.log（RUNNING无进展／截图卡住告警／DOWN 直接标注，2026-09-29 抓到 12:05 掉线全程） |

## 10. 文档维护

| 主题 | 唯一维护入口 |
|---|---|
| 通用规则、提示词模板、研究依据、语言token计数 | [agent-context-guidelines](https://github.com/zhiyaunhe-ops/agent-context-guidelines) |
| 本项目压缩与事实校正记录 | [f24de46](https://github.com/zhiyaunhe-ops/MAAskullgirls/commit/f24de4636785f85fdec29264912c7905e31b74bf)；实机／浏览器未重跑 |
