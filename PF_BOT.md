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
| tools/pf_nav.py | 场景导航/场地扫描/绑定居中 (SceneNav mixin, PfBot 继承; 2026-10-02 自 pf_bot 拆出) |
| tools/pf_webui.py／static/ | HTTP、API／HTML、CSS、JS、主题、图标 |
| tools/pf_scene.py／pf_schedule.py／pf_tray.py | 导航／调度／托盘 |
| tools/jjc_store.py | 日程快照、配置版本 |
| tools/pf_roster.py／pf_fighters.py | 角色仓库全量扫描（只读卡名 + 不重复统计）／卡名→角色类型映射（§6.15） |
| tools/pf_collection.py | 收藏聚合/评分/炫耀页导出（roster.json 之上的展示层，§6.16） |
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
| 6.0 | 接力队列 (2026-10-02) | **只有达标(goal)才自动接续**(manual 结束=用户要停: /api/end 同时暂停连刷模式, 绝不能解读成"换下一场"——首版 manual/scene 也接续, 用户点结束又见认路+扫描, 观感"卡在扫描循环"; scene 场地没找到/nav 认路失败都停等人工)；消费防呆: 队首=当前场次丢弃防重跑(防"已达标场次重跑→开场即达标→再接续"死循环)、已删场次跳过不搁置剩余队列；/api/start 顺带 queue_discard 该场次；达标关机顺延到队列清空那场；/api/stop 进程退出不走队列；队列存 sessions.json "queue" |
| 6.0 | IDLE(非运行) | 仍约每2s清弹窗；不代表设备无动作 |
| 6.0 | IDLE截图卡死 | 全路径截图带超时10s＋每分钟告警；停止·开始仍可响应，不随MAA内部重连一起等 |
| 6.0 | api/mumu/game | 先adb connect再monkey；device not found时补连重试一次 |
| 6.0 | MAA僵死(截图自愈穷尽) | LinkDead→自我重生：新进程SGM_PF_RESUME=1自动续跑＋重套场次配置；5min·日20次限流；重生后老进程硬退出(os._exit，不等MAA销毁)；用尽／受限一律彻底退出(code 3)等人工 |
| 6.0 | 重生日志 | 每代进程另写 debug/pf/bot_stdout_年月日-时分秒.log；共用 bot_stdout.log 会被老进程的重定向句柄挡住(cmd报占用→python未启动→静默)；cleanup_debug修剪旧世代 |
| 6.0 | 退出路径 | run()返回后挂25s退出看门狗→MAA销毁卡死则强制 os._exit(0)；修09-27「点停止只留一行日志、端口不还」 |
| 6.1 | step优先级 | 弹窗X→队伍存档确认→服务器错误→详情→hub→防守队弹窗→防守编辑器(CONFIRM)→REFRESH→编队→VS→结算→未知 |
| 6.1 | KEEP STREAK? | 关X；不点CONFIRM／WATCH AD |
| 6.1 | 文本读取 (2026-10-13 实机标定) | **文字**一律 `PfBot.ocr_text_line`：det 在宽松 ROI 定位行 → 取面积最大行 → 按余量重裁 → rec（找不到行回落 `ocr_text`）。**数值** ROI（分数/倍率）仍走 rec-only 的 `ocr_text`。余量：`TEXT_PAD_X=10`（横向是主杠杆）+ `TEXT_PAD_Y_RATIO=0.2`（相对框高，MAA 框自带 unclip）+ `TEXT_DET_UPSCALE=2`（det 前放大）。⚠️ 离线复刻 `_probe_ocr_offline.DetLocator` **不做 unclip**，那边最优 ratio 为 0.75，**两者勿拉平**。实测 106 passed；口径见 MEMORY.md「读卡 OCR」。**仓库场景**：卡名（装饰大字）经 `tools/data/variants.json` 映射角色类型，比硬读小字号名条更稳 —— 见 `tools/pf_fighters.py`、复跑 `tools/_probe_roster.py`。⚠️ **角色名有两套口径**：`variants.json` 的 `name` 第 2 段是游戏**内部标识**（`MsFortune`/`BigBand`/`BlackDahlia`/`RoboFortune`），与 `CHARACTER_CHIPS` 的**显示键**（`Ms. Fortune`/`Big Band`/`Robo-Fortune`）不一致 —— 混用会让筛选面板**静默 miss**（12 个已覆盖角色里 3 个对不上）。`pf_fighters` 一律输出显示口径（`CHAR_DISPLAY`，18 个角色全给），内部标识另走 `internal_of_base()`。另：元素整数 `0..5` → 元素名的口径**未核实**，只透传 |
| 6.1 | 队伍存档确认弹窗 (2026-10-13) | 「Save changes to your Team?」（无 X，NO/YES）：带未保存改动离开防守队编辑器时弹，压住大厅菱形与编辑器 CONFIRM。正文分带 OCR 单探针（`SAVECHANGES`/`YOURTEAM`，band 400,305-870,375）+ 点 YES(751,441) 保存；**判据+动作收口 `PfBot.confirm_save_team_popup`**，五条链共用（`pf_scene.wait_hall`／`dismiss_popup_once`／`pf_nav.goto_pf_hub`／`pf_bot.step`／`pf_bot.wait_battle_end`），只写 `if self.confirm_save_team_popup(img): continue`；连点 3 次不消失=判据误报/点击失效 → 置 give-up 交回各链逃逸（换场次复位）。离线复刻 MAA rec-only OCR 验证：正样本命中、180 张负样本帧零误报；**实机路径未验证**（用户 2026-10-13 口径：先不入测试） |
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
| 6.5a | 场次／全局配置 | 名称、规则、目标分、能量、休息、场地绑定(scene=PF卡名关键词,可选)／喜爱开关 |
| 6.5a | 场地绑定 scene (2026-10-02) | **扫描一天一次**（用户口径: 场只有本时区凌晨 01:00 刷新; refresh_boundary/arenas_fresh 按 01:00 线判过期 —— 00:30 时仍算前一天, 昨晚 23:00 扫的依旧有效）; 每天首次进 PF 才全扫描录入（写 debug/pf/arenas.json 带 ts, 启动回读, 旧格式按游戏日字符串平滑迁移; /api/state.arenas → WebUI「今日场地」条可点选填入; 「立即扫描场地」按钮强制重扫）；今日已录入则**直接沿用不重扫**（首节清掉 ~20s 等待）；绑定关键词则居中该场再开打；**找不到=结束本场+接续队列**(宁可停不错跑)；未绑定回开局卡就近开打（开局卡没认出来停最左=月场并大字告警） |
| 6.5a | 场地绑定写法 | 名字=字母归一子串(<4字母只认全等)；**#N=位置绑定**(1 起, 用户口径: 轮播最左第一/二位固定是月场) —— 位置法不依赖 OCR, 角色场卡标题整卡失败的既定绕行；#0/越界(对照本次扫描数)拒绝 |
| 6.5a | 子场／删除父场 | 继承配置、独立采样／子场保留转顶级 |
| 6.5a | 运行中／default | 限制切换、编辑、删除／default不可删 |
| 6.5a | 删除场次 | 内存曲线移除；CSV、账本保留；历史每场内存最多3000点 |
| 6.6 | 拖拽／滚动／筛选 | 落点驻留350ms／分段停顿／驻留点击＋关闭复验 |
| 6.7 | 前端／API | tools/static/webui.*、themes.*／pf_webui._Handler |
| 6.7 | MuMu动作／关机 | 后台线程＋锁、HTTP返回非完成信号／MuMuManager shutdown |
| 6.7 | 状态探测 | player_state／state／is_android_started；失败unknown |
| 6.7 | 达标关机 | 默认开、可取消；_goal_closed防重复 |
| 6.7 | ADB连接设置 | GET/POST /api/adb_config 读写 config.json 四件套(adb_path/address/adb_server_port/mumu_dir)；校验+告警；pf_env是import期读→**重启生效**；前端「模拟器 & ADB」面板；POST /api/adb_config/autodetect 自动检测(运行中MuMu进程路径→现有配置→盘符glob, 地址真连接试连5555/16384/7555, 只回填不落盘) |
| 6.7 | MuMu缺席保活 | setup只加载Resource(不依赖模拟器)；连接走ensure_connection(已连=纯内存检查)，主循环没连上就「等待 MuMu」每10s重连**永不退出**(2026-09-30用户口径: 没检测到MuMu不许断)；断链日志节流60s/条；running中场次也挂起等模拟器回来自动续，不再烧重生 |
| 6.7 | 托盘启动≠开跑 | 「启动服务」只起进程(2026-09-30用户口径)；开跑=托盘「开跑当前场次」或WebUI「开始」；托盘自启即默认启动服务+Windows气泡(_notify→icon.notify)；图标=tools/static/icons/tray_icon.png(Filia头像裁剪, 缺文件回退圆角方块)+右下角状态点；启动器=启动托盘.vbs(ASCII-only：wscript按ANSI解析, UTF-8中文注释静默失败；pythonw需全路径, cscript PATH无anaconda)；重生auto-resume不受此限(接管死前场次) |
| 6.7 | 每日任务 | 保存编排；未接入执行 |
| 6.7 | 接力队列 UI/API (2026-10-02) | POST /api/queue/set {ids} 整体重设(去重+丢不存在)；/api/sessions 与 /api/state 带 queue；弹窗「选择 PF 场次」行内 ☰/⛓ 加入·移出＋队列条 ▲▼✕ 排序；主页 queue-chip 显示"接力 N 场: A → B"；场次编辑区"场地绑定"输入(随场次, 运行中锁定)；「今日场地」条=扫描录入, 点场地名直填新场次绑定＋datalist 联想；preview_webui 同构演示 |
| 6.7 | 连刷编排页签 (2026-10-02, 2026-10-08 修订) | PF 页第三子页签: 今日场地**方块**点选接入链条(自上而下连线)+**目标分只读回显**(改自原「每节目标分」输入框)；配置存 debug/pf/chain.json {blocks:[{pos,title,sid}],enabled}（旧格式残留 target 键被忽略）；sync_chain 槽位场次按 sid 复用(被删重建)、scene=#N 位置绑定、**标题随当日扫描自动跟新**(场地轮换槽位不换)、运行中当前场次跳过配置同步；⚠️ **分数上限唯一入口=场次页签**（2026-10-08 用户质疑「场次已有上限为何链条再加一个」→ 采纳：原 sync_chain 会用链条 target **静默覆盖**场次 score_target，同字段双写入口必然分叉，故收敛为单向只读回填，GET /api/chain 的 target 取所绑场次现值）；勾选「连刷模式」=保存+接力队列按链设置+待命时前端 /api/start 首节；取消=清队列(/api/end)；**手动「结束」=暂停连刷**(chain.json enabled=false、接力队列保留, 前端 onEndClick 回读勾选)；POST /api/scan 待命扫描(pf_bot IDLE 分支消费, 运行中 409)；同一队列双入口(弹窗手排/链条编排)后写为准 |
| 6.7 | 目标ETA | 速率=本场记分点活跃段(相邻≤180s)增量÷时长；ETA=(目标−当前)÷速率；未设目标只显速率；页面前端现算, /api/summary只供速率 |
| 6.8 | AUTO／3x | **每次「开始」后的首场都查**(2026-10-02用户口径, run()起始块复位 _battle_auto_checked; 旧版每进程只查首场, 开始之间手动关auto就漏)；亮度阈值75、速度模板0.85；失败告警继续 |
| 6.9 | goto／explore／center | 冷启动导航／扫卡并恢复居中／目标居中；bot只点居中PLAY! |
| 6.9 | 读卡逻辑归属 (2026-10-02) | read_center_card/parse_score_ocr/字库/ROI/扫描/导航整体住 **pf_nav.SceneNav** (PfBot 继承; 用户要求功能独立出 pf_bot.py)；PfScene 只留薄委托, explore/center/goto_index 行为不变；标定注释随代码走(pf_nav) |
| 6.9 | bot 侧导航原语 | goto_pf_hub(弹窗三路X→PLAY就绪→大厅菱形→房子, NAV_TIMEOUT 240s 含冷启动; 第一轮认不出界面补一发 monkey 拉游戏——/api/start 只拉模拟器不拉游戏)＋scan_arenas(右滑到最左→逐卡左扫, 相邻同读=到头; 录入 arenas.json 带 ts[一天一次, 01:00 过期] )＋goto_index(回最左再左滑 N 次)＋center_scene(#N 走 goto_index, 名字先左扫到头再右扫回)；滑动走 adb input swipe(MAA post_swipe 被吸附轮播弹回)；**认路自愈两态**(2026-10-02: 手动结束在战斗中途→游戏自己打完停在 VICTORY 结算页, 认路对结算页按房子 240s 超时→"刷了一场就停"): 速度泡命中=战斗残留只等不点; 结算页(CONTINUE×N 页)逐页剥掉再回 hub; NAV_TIMEOUT 240→300s(含残留战斗 60~120s)；**协作式中止 NavAborted**(导航/扫描中 running 相对进入时翻转即退: 点结束 3s 内响应, 扫描中止不覆盖已录数据; navigate_to_scene 分级返回 ok/scene/nav/stopped)；关键词匹配: 短词全等, ≥4 双向子串, ≥8 字母长名加 0.8 相似度容 OCR 丢字母(18 个已知场名两两零误报) |
| 6.9 | 标题／无SCORE行 | 常规＋备用ROI、候选择优／已知标题命中可判0 |
| 6.9 | 角色场卡标题 | OCR整卡失败（读'O'/噪声，HIGH WIRE HIJINKS 两次实测）→center()不可用；hub不循环、最右=角色周场，按位置滑 |
| 6.9 | score=0／外部导航 | 新场候选，非确证／先停bot；暂停仍清弹窗 |
| 6.10 | schedule.json／ACTIONS | 配置与完整动作列表：pf_schedule.py |
| 6.10 | 扫描／去重 | 20s、默认宽限90分钟／按日先记触发再执行；失败不保证重试 |
| 6.10 | 串行 | _ACTION_LOCK仅单进程；取锁后重查状态；不保证FIFO |
| 6.10 | --fire／--action | 等完成／硬超时；_timeout默认480s |
| 6.10 | run_new_pf.params | fallback_title（无新场兜底）／want_tag（只挑某 tag 的新场，如 character）／dry_run／no_start／legacy_parent／restart；DIAMOND_TAGS 的场次起跑前自动切 Diamond，见 §6.14 |
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

## 6.14 角色场特例：起跑前切 Diamond + 防守队（2026-10-12 用户口径）

| 项 | 内容 |
|---|---|
| 为什么必须切 | hub 卡默认 BRONZE；bot 点 PLAY!→FIGHT! 会弹「locked into the Bronze difficulty」→ 点 CONTINUE 后该 PF **永久锁 Bronze（不可逆）**，5kw 目标永远达不到 |
| 切法 | 难度行是可点切换器，点右箭头一次 +1 档（Bronze→Silver→Gold→Diamond，到顶右箭头消失）；Diamond 判定 = 难度行 OCR 归一后含 `DIAM` |
| 实现 | `pf_difficulty.ensure_diamond(scene)`（右箭头 (720,385)、ROI (540,358,720,402)、最多 3 击 + 首读）；`act_run_new_pf` 在 classify_and_create 之后、start_bot 之前调用 |
| 生效范围 | `pf_artag.DIAMOND_TAGS`（当前**仅 character**）。元素场是否也要切**未经用户确认** —— 锁错档不可逆，别顺手扩大；确认后往集合里加 `"element"` 即自动生效 |
| 安全闸 | 读不到 DIAMOND 时**不起 bot**，留档 `difficulty_fail` 截图；人工在 hub 切好后可用 WebUI 开跑 |
| 防守队 | 角色场 `class` 规则只约束**防守队**（出战队不受限）：`setup_defense_team` 双入口（「先设防守队」弹窗点 OK / 直接落在编辑器认 CONFIRM），左1 放该期角色（CHARACTER_CHIPS 筛选 + `put_char_in_defense` + 槽位角色名/副名 OCR 复验） |
| 编辑器退出弹窗 | 带着未保存改动离开防守队编辑器会弹「Save changes to your Team?」（无 X，NO/YES）→ 由 scene 判据 `confirm_save_team_popup` 点 YES 保存（见 §6.1 同题行）|
| 完整流程（五段） | ① 归类 `classify_and_create`（`arena_manual.json`）认 tag=character → ② 切档 `act_run_new_pf` 对 `DIAMOND_TAGS` 调 `ensure_diamond`，**未确认 Diamond 不起 bot** → ③ 进：起 bot + `/api/start`，`step` 点 PLAY!→FIGHT! → ④ 调防守队 `setup_defense_team`（弹窗点 OK 或直落编辑器认 CONFIRM → 左1 放该期角色 → 点 CONFIRM）→ ⑤ 存档 `confirm_save_team_popup` 点 YES 保存。**第⑤步判据挂五条链**（§6.1 同题行）——`wait_battle_end` 是第④步 CONFIRM 后的下一跳，2026-10-13 复核时补接 |
| ⚠️ 已知缺口 | `CHARACTER_CHIPS` 只覆盖 12 个角色（缺 Parasoul/Peacock/Valentine/Squigly/Robo-Fortune/Umbrella）⇒ 这几类角色场的防守约束**静默跳过**（日志有 warn），需人工在 WebUI 补规则 |
| 专跑角色场 | `--action run_new_pf --params {"want_tag":"character"}`：只从 score=0 的新场里挑角色场（挑不中告警并退回「取第一个」）；月场位 #1/#2 不参与挑场（位置优先于名称） |

## 6.15 角色仓库扫描（`tools/pf_roster.py`，2026-10-14）

用户口径：**只读上方那条卡名**（卡面装饰大字，如 `REGALLY BLONDE`），小字号名条（角色本名）不读；
"到头"判据与既有轮播同款；真正的难点是**滑动后怎么定位同一张卡，做到不重复统计**。

| 项 | 内容 |
|---|---|
| 版式 | 2 行 × N 列**列优先**网格（同一列上下两行 = 列表里相邻两个条目）；列距 165.5；卡名带 y 296..326 / 526..556，名条带 y 322..350 / 552..580 |
| 定位 | 名条带 det **只取框**（小字规整 → 一卡一框，框中心即卡中心），卡名带在 `cx ± 76` 上跑 **rec-only**。每卡只要 1 次识别往返；实测 3 屏 × 14 格 = **42 格全对**（卡名带直接 det+rec 只有 35/42，装饰字太宽会被切碎） |
| 去重（四道） | ① **不按名字** —— 同屏 3 张 `NUNSENSE`、11 张 `UNDERDOG` 都是真副本；② **不写死坐标** —— 拖动量任意（实测连续平移 640~660，不是列距整数倍）；③ **不只靠几何投票** —— 列是周期性的，真平移 ±165.5k 的别名同样成立；④ **卡名内容打分** —— 候选 Δ 按最近邻配对，名字对上 +1 / 冲突 −1，**同分先比冲突数**、再比运动先验（`Store._align`） |
| 到头 | 连续 2 次画面不动（取两次，防 `_adb_shell` 静默失效把"滑不动"当"到头"）**且第二次换一条拖拽几何** —— 列表端点的屏幕是一大片空白 + ADD 槽，主几何的起手点落在空白上，"拖不动"是假象（实测右端换 3 种几何全不动才判真到头） |
| 实机结果 | 同一份仓库跑两遍都是 **255 张**（row1 128 列 / row2 127 列，最后一列的 row2 格是 ADD 槽）；两轮**逐列一致**，`dup_gaps`/`miss_gaps`/`conflicts` 全空、未识别 0 |
| ⚠️ UI 计数对不上 | 右上角 `FIGHTERS 257 / 350` 比网格内容多 2。网格内容经**两条独立方法**确认：图像相关测真实步长累加出的跨度 21019px ÷ 165.5 = 128 列（`_probe_frame_shift.py`），以及跨帧名条框交叉映射 ⇒ **255 是网格真值**，257 是 UI 口径（疑含不在本网格里的项）。**别为了对齐 UI 数字去改扫描** |
| 跑法 | 实机 `python tools/pf_roster.py scan --out debug/pf/roster/roster.json`；离线复跑留档帧 `python tools/pf_roster.py images <帧...>` |
| 💡 加速：游戏内先筛选再扫 | 游戏内 COLLECTION 右下**漏斗**可按稀有度/元素/角色筛选（如只看钻石）——筛选后网格重排、总卡数变少 ⇒ 拖拽次数等比变少。扫描器**自适应列距**（`summary()` 的 dup/miss 自检以观测中位数为参考，兜底 `max(med, 0.6×PITCH)`，2026-10-14），去重/配准本就不依赖固定列距 ⇒ 直接扫即可，无需改代码 |
| 前端 | PF 页第四子页签**「仓库扫描」**：`POST /api/roster/scan` 置 `STATE.roster_scan_requested` → 主循环 IDLE 分支消费；`GET /api/roster` 回进度/结果（前端 2s 轮询，`state`: idle/queued/running/done/error）。**不导航** —— 先把游戏手动停在 FIGHTERS 页（开扫前 `run_scan` 探一帧，0 张卡直接抛错退出，不会对着主界面白拖）。结果落盘同一份 `roster.json`，服务重启后前端仍能看上次那份（响应 `saved=true`）。测试 `tests/test_roster_webui.py`（7 条，纯 HTTP 契约，不连设备） |

## 6.16 收藏统计 + 炫耀页（`tools/pf_collection.py`，2026-10-14）

扫描结果之上的展示层：变体聚合 + 官方介绍 + 筛选 + 评分 + 单文件导出。PF 页第五子页签**「收藏统计」**。

| 项 | 内容 |
|---|---|
| 数据流 | roster.json 的 cards → `pf_fighters.variant_of()`（306 变体闭集，OCR 噪声在此被吸收：`IYHOT`→`Icy Hot` 不会裂成两个变体）→ `pf_collection.aggregate()` 按变体聚合，**同变体多张 = count**（同名卡是真副本，不能按名字去重） |
| 介绍（分层） | 数据源 Krazete/sgm 仓库 `data/{en,zh-cn}.json`（2019 4.1.0 官方本地化，已入库 `tools/data/sgm_text_{en,zh}.json`）：**144/306 变体**有官方 quote + SA 全文（中英双语）；其余 162 条官方表就没有（Krazete 的 variants.json 只含这 144 条，与本地 306 条超集逐字段核对零差异）——SA 名从 key 驼峰分词推导（`RitualSacrifice`→`Ritual Sacrifice`）并标「(推导)」。⚠️ fandom wiki 变体无独立页（`Dark_Lord` 404 实测），别走那条路。desc 里的 `{0}/{0:0%}` 占位符按 SA 三级数值填实（`_fill_desc`，填不了原样返回） |
| 评分（公开口径） | `score()` = 100 × (0.5×完成度 + 0.3×稀有度 + 0.2×角色覆盖)；完成度 = 独特变体/306；稀有度 = Σ权重/全图鉴满配（钻 8/金 4/银 2/铜 1）；覆盖 = 有卡角色/18。**只算独特变体**（重复卡不加分）。等级 S+~E 映射称号。满图鉴 = 100/S 有单测钉住（分母自洽） |
| 稀有度来源 | `variants.json` 的 `tier` 字段：0铜/1银/2金/3钻，与 `name` 第 3 段字母（B/S/G/D，RaidBoss 亦钻）一一对应，实测 306/306 吻合 |
| ⚠️ 元素口径 | 前端元素筛选 chip **只显示整数 id（元0..元5）**——整数→元素名的映射仍未核实（沿用 pf_fighters 的口径），chip hover 给一个代表卡名让用户自己对。**别在前端猜元素名** |
| 路由 | `GET /api/roster/collection` 聚合+评分（按 roster.json mtime 缓存）；`GET /api/roster/export` 单文件炫耀页（数据内嵌零外链，`</` 已转义；`?download=1` 变附件下载） |
| 炫耀页 | 深色游戏风单文件 HTML：等级徽章 + 三分项进度条 + 稀有度计数 + 本地筛选（稀有度/角色）+ 变体卡网格（×N 计数、SA、攻血、quote）+ 底注写明评分公式。发给谁都能直接双击打开 |
| 测试 | `tests/test_pf_collection.py`（11 条纯函数）+ `tests/test_roster_webui.py` 追加 3 条（收藏快照口径、炫耀页转义/附件头）；全合成数据，不碰设备 |
| 复核工具 | `python tools/_probe_frame_shift.py <帧目录>`：**图像相关**测相邻帧真实像素位移，完全绕开 OCR/配准 —— "到底该谁对"的争议一律用它裁（纯离线，不连设备） |
| 测试 | `tests/test_roster_scan.py`（13 条，全合成帧，不碰设备）；跑法 `anaconda python -m pytest tests/test_roster_scan.py -q` |

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
| 33 | 「Save changes to your Team?」存档确认弹窗**无 X**（NO/YES）→ `find_any_popup_x` 三路关闭钮全不认它；它压住大厅菱形, wait_hall 连点 3 次房子后 150s 超时「未回到大厅」（01:47 实测，帧 debug/pf/stuck_1013.png），流水线全链卡死需人工点 YES | 走正文判据而非图形: `PfBot.confirm_save_team_popup`（band 400,305-870,375 探针 `SAVECHANGES`/`YOURTEAM` → 点 YES(751,441) 保存），五条链共用（末条 `wait_battle_end` 为 2026-10-13 复核「角色场→切 Diamond→进→调防守队→存档」完整流程时补接：它是 `setup_defense_team` 点 CONFIRM 后的下一跳，漏掉只能干等 300s 超时）；判据+动作收口一处，避免 step/goto_pf_hub 两表不同步；见 §6.1/§6.14 |
| 34 | `ocr_lines` 按 `getattr(box,'x',0)` 读 det 框，而 MAA 的 **`OCRResult.box` 是 `[x,y,w,h]` 列表** → 所有框退化成 `(0,y0,1,y1)`、余量恒 2px、重裁形同虚设；**文本照样读得出**（rec 是 MAA 自己按 det 框做的）⇒ 不报错、**静默降级**，只有把框打印出来才看得见（2026-10-13 角色仓库验证时发现） | 新增 `PfBot._ocr_box` 按序列解包（兼容对象式绑定）；框恢复 `(141,555,52,15)`。凡"拿框再加工"的代码都要先确认解包对不对 |
| 35 | 文本读取余量写死像素：旧 `TEXT_PAD_X/Y=6/12`。而 MAA 的 det 框**自带 unclip**（`box_h ≈ 1.87×字高`），固定 12px 在字高 8px 的名条上 = crop 4 倍字高 → 归一化到 rec 的 48px 后文字反而变小，末字丢（`PAINWHEEL`→`PAINWHEE`） | 改 `TEXT_PAD_X=10`（横向才是主杠杆: MAA 框横向偏紧，`FILIA` 只框 26px → `ALIA`）+ `TEXT_PAD_Y_RATIO=0.2`（相对框高）+ `TEXT_DET_UPSCALE=2`（1x 93/2x 100/3x 96%）。实测口径见 §6.1「文本读取」与 MEMORY.md「读卡 OCR」 |
| 36 | **角色名两套命名空间**：`variants.json` 的 `name` 第 2 段是游戏内部标识（`MsFortune`/`BigBand`/`BlackDahlia`/`RoboFortune`），而 `CHARACTER_CHIPS`/`CLASS_CHIPS` 的键是显示名（`Ms. Fortune`/`Big Band`/…）——拿内部标识查芯片坐标**静默 miss**（12 个已覆盖角色里 3 个对不上；无异常无日志，只是"芯片没点到"）。同类坑早在 hub 卡名上出现过（`pf_nav._match_known` 把 `FALL OF THE DARK` 吸成 `A SHOT IN THE DARK`） | `pf_fighters.char_of_base()` 统一输出 `CHAR_DISPLAY`（18 个角色全给，缺 chip 的 6 个也按官方写法）；要内部标识走 `internal_of_base()`。2026-10-13 `_probe_roster.py` 42 格实测时发现 |
| 37 | **配准别名 → 漏统计（不是重复统计）**：列是周期性的，长串同名卡里"差一个列距"的别名 Δ 能命中**一样多甚至更多**的卡。run1 frame0033 出现**三档同分**（13920/14085/14251），旧版 `s > best_s` 同分取 Δ 最小的那个 ⇒ 选中 miss=1 的别名，整帧并成 dup、那一列再没人补。症状极具迷惑性：同一份仓库两遍跑出 **255 / 253**，而两轮**左端帧和右端帧一模一样**、`dup_gaps`/`miss_gaps` 全空 | 打分并列时先比**冲突数 `miss`**（真值恒 0 冲突），运动先验 `Store._predict` 只当最后一档（单用它反而过头，把真值顶成差 2 列的 ⇒ 259 张）。**切分真值必须用 `tools/_probe_frame_shift.py`**（图像相关测真实步长，与 OCR/配准完全无关）。回归 `tests/test_roster_scan.py::test_align_prefers_zero_conflict_on_score_tie` |
| 38 | 把分钟级长任务（仓库扫描）接 WebUI 时，第一反应是「在 `/api/roster/scan` 里起个后台线程跑」—— 但**设备（Tasker/controller）是独占资源**，HTTP 线程去碰它就会和主循环抢注入（设备侧的"双实例"，同 §8-12） | 长任务**同步跑在主循环 IDLE 分支**（沿用 `/api/scan` 的既有范式）：HTTP 只置 `STATE.roster_scan_requested`，进度经 `pf_webui._ROSTER`（带锁容器）**单向**传出，前端轮询 `GET /api/roster`。顺带两个防呆：**待命中才有意义**（运行中 409，且拒绝时**不置位**，别留悬空请求）、**不导航**（先把屏幕停在 FIGHTERS 页，开扫前探帧 0 卡即抛错，见 §6.15）。回归 `tests/test_roster_webui.py` |
| 39 | `tools/lint_webui.py` 的资源检查认死前缀 `/static/`，而 `webui.html` 里是**相对路径** `static/…`（为托管预览的 `UI_BASE` 改造服务的，见 webui.js 首行）⇒ lint **一直红**（2026-10-14 发现：HEAD 版同样如此）。红着的 lint 等于没人跑，这个脚本的 id 唯一/顶层重名/语法检查全部形同虚设 | 前缀判断改为两种都收（`startswith(("/static/", "static/"))` + `removeprefix` 两次），全绿 |

## 9. 常用操作

| 任务 | 命令／定位 |
|---|---|
| 安装／启动 | [README快速开始](README.md#快速开始)；启动PF.bat |
| 导航 | python tools/pf_scene.py goto |
| 扫描／居中 | python tools/pf_scene.py explore；python tools/pf_scene.py center EYE |
| 调度 | python tools/pf_schedule.py；--list；--fire 任务名 |
| 轮询分数 | python tools/poll_score.py [目标分=1000000] [分钟数=8]；到线退 0、超时退 2（验证任务用，见 §6.14） |
| 专跑角色场 | python tools/pf_schedule.py --action run_new_pf --params '{"want_tag":"character"}'（只挑 score=0 新场里的角色场；起跑前自动切 Diamond，见 §6.14） |
| 扫描角色仓库 | python tools/pf_roster.py scan --out debug/pf/roster/roster.json（实机）；python tools/pf_roster.py images <帧...> 离线复跑；复核位移用 tools/_probe_frame_shift.py，见 §6.15 |
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

## Related documents

按相关任务跳读：README 供使用者，AGENTS 规定 Agent 工作边界，PF_BOT 是实现细节，事故与探索报告只在排查对应问题时读取。

- [AGENTS.md](AGENTS.md)
- [README.md](README.md)
- [docs/session-tag.md](docs/session-tag.md)
