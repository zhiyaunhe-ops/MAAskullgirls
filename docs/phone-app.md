# phone/app — 手机端挂机 App（Shizuku）

Android 原生 App：Shizuku 拿 shell uid 截屏/点击，NCC 模板匹配认结算页，跑与
`phone/autojs` 同口径的结算循环。无 Gradle，走 `aapt2 → javac → d8 → apksigner`
（`phone/app/build-ci.sh`），APK 由 GitHub Actions 产出（release tag `latest`）。

| 想做什么 | 去哪 |
|---|---|
| 装起来/跑起来 | 本文「跑起来」 |
| **装 Shizuku / 开启流程 / 点了没反应** | **App 内「使用指引」页**；本文「界面导览」 |
| 改参数（扫描间隔/阈值/时长…） | 本文「可配置项」 |
| 看判定路径图 | 本文「判定路径图」 |
| **看每日战绩（热力图）** | **App 内「每日战绩」页**；本文「战绩热力图」 |
| 换分辨率/别的比例屏 | 本文「多分辨率」 |
| 拉模板/配置/新 APK | 本文「GitHub 拉取更新」 |
| Shizuku 连不上、截屏失败 | [phone-app-shizuku.md](phone-app-shizuku.md) |

## 界面导览（App 内五个页面）

| 页面 | 入口 | 干什么 |
|---|---|---|
| 主页 | 启动 App | 状态总览 + 三项授权 + **悬浮窗启停** + 各页入口 + 更新 |
| 使用指引 | 主页「❓ 使用指引」 | Shizuku 下载直链、**6 步开启流程**、「点了没反应」速查表、adb 入口 |
| 编辑配置 | 主页「编辑配置」 | 改运行时参数（安全子集，带范围校验） |
| 判定路径图 | 主页「查看判定路径图」 | **App 内原生绘制**（Canvas）：整棵判定树 + 实时高亮 + 各点峰值 |
| 每日战绩 | 主页「每日战绩」 | GitHub 风格热力图 + 每日明细 + 口径说明 |

⚠️ **主页不放任何"跑/停"的键**（2026-10-11 用户要求瘦身：*"这都是在悬浮窗里面可以
单独点的呀, 你就不用在那里再弄一次了"*）。**运行控制只有一处 = 悬浮窗**（开始／导航／
暂停／结束／◀折叠），主页只负责「启动悬浮窗」把它叫出来。理由是"同一个动作两条路"必然
出事：主页那条走 `Ui.gate`、悬浮条那条走 `Ui.blockReason`，两条路的前置检查一旦不同，
就会出现「主页点不了、悬浮条能点」这种最难查的状态。
`tools/GraphProbe.py` 里有源码级断言钉住它（连注释一起剥掉再查关键词），防止有人加回来。

⚠️ **「暂停」以前叫「停止」**（2026-10-11 用户：*"按钮的名字有歧义, 停止应该指的是暂停"*）。
那颗键的行为一直就是暂停（只停循环，服务与窗口都留着，再点可续），名字却叫"停止"，
而旁边还有一颗真正收窗口的「结束」—— 两颗都像"停"，按错就以为程序坏了。现在：
**暂停** = 停循环、窗口留着可续；**结束** = 退出挂机、连服务与窗口一起收。
另外**「开始」那颗键在暂停后会显示成「继续」**（`BotService.paused()`），名字跟着状态走。
⚠️ **动作名 `start`／`start_nav`／`stop`／`end` 一个字都没改** —— 那是 adb 广播
（`am broadcast … -e action stop`）与 HTTP `/trigger?action=stop` 的接口契约，改文案
不能改协议。`tools/OverlayProbe.py` 的 C2 组 + 源码级断言钉住这两条。

**每个按钮点了都会弹提示**（`Ui.act`）：说清"要做什么"；前置条件不满足时给**人话
原因**并尽量把你送到对应设置页（`Ui.gate`），不再静默丢弃。最容易"看着像坏了"的情况：

| 你点的 | 实际缺什么 | 提示 |
|---|---|---|
| 悬浮条上的「开始」 | Shizuku 服务没跑 | "先去「使用指引」按步骤启动 Shizuku" |
| 悬浮条上的「开始」 | 没给本 App Shizuku 授权 | "点主页「① 授权 Shizuku」允许一下" |
| 悬浮条上的「开始」 | 授权了但通道没接上 | "稍等 2~3 秒再点" |
| 主页「启动悬浮窗」 | 没授"显示在应用上层" | 直接弹提示 + 跳设置页 |

## 跑起来

1. 装 APK（release `latest` 的 `sgmbot.apk`）；
2. 打开 App 依次授权：Shizuku → 悬浮窗 → **所有文件访问**（第三个不授也能跑，但
   `adb push` 改配置要写私有长路径，且 APK 自更新的第一条路用不了 —— 会走第二条路，
   见「更新」）；
3. 点主页「启动悬浮窗」→ 在悬浮条上按需点：开始／导航／暂停／结束／◀折叠。
   悬浮窗**不需要先起服务**：先摆好位置（位置会被记住），想跑时点「开始」即可。

**「暂停」与「结束」的区别**（同 AutoJs 版 `settle_bot.js`）：

| 键 | 引擎 | 服务/通知 | 悬浮条 | 对 AutoJs 的对应 |
|---|---|---|---|---|
| 暂停（旧名"停止"） | 停（键名变「继续」，可接着跑） | 留着 | **留着** | 只停循环，窗口留着可再开 |
| 结束 | 停 | 收掉 | **一并收掉** | `endBot()` → `exit()`，悬浮窗随之消失 |

⚠️ 悬浮条**是"谁启的"不再决定「结束」收不收它**：`OverlayCtl` 的 `svcOwned` 只用于
「服务**意外**退出」（系统回收、`stopService` 之外的原因）时保住用户自己摆的那条；
而用户主动点「结束」= 退出脚本同义，**无条件**收窗口（`BotService.end()` 里显式
`OverlayCtl.hide()`）。曾经的写法只靠 `onDestroy` 收「服务名下」的窗口，于是最常见
的顺序「先启动悬浮窗 → 再点开始」下点「结束」只看到服务停、窗口赖着不走
（2026-10-11 用户报障）。回归见 `tools/OverlayProbe.py`。

调试（PC 侧）：

```bash
adb forward tcp:8791 tcp:8791
curl http://127.0.0.1:8791/status      # 引擎状态
curl http://127.0.0.1:8791/log         # app.log
```

## 可配置项

`assets/config.json` 是内置默认；外部 `config.json`（`Paths.config()`）逐键覆盖，
`adb push` 后 `/reload` 或下轮生效。**旧键（thresholds/timing/title_roi/btn_rois…）
全部保留**，新增段如下：

| 段 | 键 | 默认 | 说明 |
|---|---|---|---|
| `scan` | `battle_ms` / `result_ms` | 800 / 350 | 战斗期/结算期扫描间隔（旧 `timing.*` 仍作回落） |
| `scan` | `stall_ms` | 1000 | 卡住时的扫描间隔 |
| `scan.adaptive` | `on/min_ms/max_ms/hit_ms/miss_ms` | true/250/2000/350/900 | 命中后加快、久无命中放缓 |
| `matcher` | `chan` | `blue` | 匹配通道：`blue`/`green`/`red`/`gray`。**默认 blue 是标定过的**，改 gray 会漏识别（见 VisionProbe） |
| `matcher` | `step` / `refine` | **3** / 2 | 粗搜步距 / 精定位邻域。step 是位置数唯一旋钮（∝1/step²），2026-10-14 由 2 升到 3（全匹配 2x、判定零变化）。⚠️ 必须 `refine ≥ (step-1)/2`。escape 链可按条目写 `step` 覆盖（`srv_*` 钉回 2，见「`matcher.step` 2→3」） |
| `matcher` | `work_h` | 576 | 帧缩放基准高 |
| `matcher.pyramid` | `level/cost/relax/floor/top` | 2 / 2e8 / 0.3 / 0.5 / 16 | 全屏大模板的金字塔粗搜（见「匹配性能」） |
| `screen` | `roi_mode` | `abs` | 无 profile 命中时的 ROI 映射模式 |
| `screen.profiles` | `name/ar_min/ar_max/roi_mode` | 见文件 | 按宽高比落档选模式 |
| `run` | `max_minutes` | 0 | 单次最长时长（0=不限） |
| `run` | `pause_from` / `pause_to` | `""` | 暂停时段（支持跨夜，如 23:30~07:00） |
| `log` | `trace_size` | 200 | 判定流水环形缓冲容量 |
| `graph` | `layout/nodes/edges` | 见文件 | 判定路径图结构（见下） |
| `update` | `repo/bundle_asset/apk_asset/auto_check_h/allow_apk` | 见文件 | GitHub 更新（见下） |
| `update` | `system_installer_fallback` | true | `pm install` 走不通时改把 APK 交给**系统安装器**（第二条路，见下）。关掉就只报原因 |
| `shizuku` | `download_url/release_api/homepage/play_url` | 见文件 | 使用指引页的 Shizuku 链接（换版本不必发 APK） |
| `stats` | `weeks` / `levels` | 26 / `[1,3,6,11]` | 热力图默认周数 / 颜色分档上界 |
| `store` | `keep_days` | 400 | 战绩归档保留天数（≈13 个月），超期丢最旧 |
| `popup` | `guard` / `after_sec` / `every_sec` | true / 5 / **15** | 弹窗守卫：无动作超 `after_sec` 后每 `every_sec` 剥一次（见「弹窗处理」）。2026-10-14 `every_sec` 由 5 抬到 15 —— escape 链是每帧匹配最大的一块（约 60%），而触发条件本身就是 5s 尺度的启发式，重试更密没有额外信息量 |
| `fast` | `enable` | false | **快速连点模式**总开关（见「快速连点模式」）。默认关 |
| `fast` | `xy` | null | 盲点落点（work 坐标）。null = `btn_rois` 中槽中心**再绕开 `avoid` 挡块** |
| `fast` | `avoid` / `avoid_gap` | 速度泡 / 24 | 落点要绕开的 HUD 挡块（work `[x,y,w,h]` 列表）+ 安全间距。改这两项见「落点为什么要绕开」 |
| `fast` | `tap_ms` / `observe_ms` | 300 / **3000** | 连点间隔 / 观察线程**周期**（真周期 = max(每帧耗时, 本值) —— 见「每帧成本与恒定 1 核」）。2026-10-14 由 1000 抬到 3000 |
| `fast` | `round_gap_s` | 5 | 计场最小间隔（大字去抖，防换页闪断重复计场） |
| `fast` | `idle_stop_s` | 300 | 一场没计到就停车（0=关） |
| `fast` | `popup_pause_ms` | 2000 | 剥完弹窗后暂停连点多长时间 |
| `fast` | `fight_wait_s` | 15 | 剥弹窗后**找 FIGHT** 的窗口时长（0=关，见「快速连点模式」） |
| `fast` | `fight_scan_ms` | 500 | 窗口内的检测间隔（比 observe_ms 密，怕错过 FIGHT） |
| `fast` | `fight.tpl/th/roi` | vs_fight / 继承 escape 链 | 追踪判据。th/roi 缺省**继承 escape.items 里同名项**（同一素材只标定一次） |
| `fast` | `tap_check` | **false** | 落点体检是否**拦车**。false = 只记一条提示、继续连点（默认，fast 的契约是盲点，只管弹窗+大保底） |
| `fast` | `diag_ms` | 2000 | 三槽峰值（纯诊断，只喂 `[观]` 日志）的计算间隔 ms，0=每帧；`tap_check=true` 时强制每帧 |
| `popup` | `every_sec`（fast 侧） | 5 | **fast 观察线程的剥弹窗重试间隔** —— 原来 guard 一过期就每帧重跑整条 escape 链（见「每帧成本」） |
| `map_check` | `on` / `every_s` | true / 300 | **大保底**：每 5min 检测一次是否已退到大地图（两个模式都挂） |
| `map_check` | `tpl` / `th` / `roi` | home / 0.85 / `[0,0,180,70]` | 判据 = 左上角出现 home 房子图标。每次检测都记峰值日志：误停调高 th、漏报调低 |

### 三条改配置的路

| 方式 | 适合 | 怎么用 |
|---|---|---|
| **App 内「编辑配置」** | 调运行时参数 | 手机上直接改，带范围校验，越界拒收（见下） |
| `adb push` 原文 | 精确编辑 | 推 `config.json` 到 `Paths.config()` 再 `/reload` |
| HTTP | PC 脚本 | `curl -X POST --data-binary @config.json http://127.0.0.1:8791/config` |

**App 内只暴露安全子集**（扫描间隔 / 自适应 / 匹配口径 / 运行控制 / 流水 / 更新）。
坐标与阈值（`title_roi`、`btn_rois`、`boss_anchor`、`escape` 链、`thresholds.*`）
**不进界面**：那些是标定值，手输错一个数就是"不打/乱点/永远认不出"，且现场没有回滚
手段。要改它们走 `adb push` 原文——那才是精确编辑。

两条落地路径**共用同一套语义**：界面只写你**真改过**的键（与当前值相同的不写），
`Config.read()` 是「assets 默认 + override 逐键合并」，所以没碰的键继续吃默认，
不会把默认值固化成 override。「恢复默认」= 删掉 override 文件。

## 判定路径图

**节点和边全在 `config.graph` 里，代码零硬编码** —— 加一个判定点就是加一条
`nodes`，不必发版。循环每走到一个判定点就 `Graph.cursor(id)` + `Trace.put(...)`
（记模板/ROI/峰值/命中与否）。

两种看法（**同一份数据源，只是渲染不同**）：

- **手机上（默认，原生）**：主页「查看判定路径图」→ `GraphActivity` + `GraphView`
  （Canvas 自绘）。**不跳网页、不走 HTTP**：直接读同进程的 `Graph.toJson()`。
  手势一致（拖动平移 / 双指缩放），三个页签 = 路径图 / 判定流水 / 怎么读。
- **PC 上（网页版）**：`adb forward tcp:8791 tcp:8791` 后开 `http://127.0.0.1:8791/`
  （`DebugHttp.GRAPH_HTML`，浏览器里看挺方便，仍在维护）。

> **为什么手机端换掉 WebView**（2026-10-11 用户原话：*"别再跳转什么网页了 真的很丑"*）：
> 那个页面本来是给 PC 的 `adb forward` 看的（白底 + CSS px 字号 + 一排 /status /log
> /config /update 外链），塞进手机后字号小到看不清、手感与 App 其它页不一致、点链接
> 还会跳走回不来，而且每次取数要绕一圈 HTTP。现在原生画，顺带快了。
> `tools/GraphProbe.py` 有源码级断言钉住它（`WebView`/`loadUrl` 不许回来）。

节点画三行：**标签 / 最近实测 NCC 峰值（绿=命中 红=未过阈） / 当前阈值**。阈值来自节点
声明的 `cfg` 路径（`Graph.load` 时从 config 回填到 `val`），所以**改了 config 图跟着变**，
不会出现"图上写 0.72 / 实际是 0.75"。**点任意节点**，下方信息栏给出它的完整条件、阈值
来源路径与实测分数。页签「判定流水」= 最近 40 条 `Trace`。

### 缩放：为什么给了「全图 / 1:1 / 当前」三个档

真图（36 节点 / 8 泳道）在**设计单位**下是 1817 × 950，而手机竖屏视口只有 ~393 dp 宽。
两者差了 4.6 倍 ⇒ **"能看全"和"能看清"不可能同时满足**：

| 档 | 效果 | 用途 |
|---|---|---|
| 全图 | 缩放 ~20%（13sp 的字实际 2.6sp） | 看**形状**：有几条泳道、哪分叉哪汇合 |
| 1:1 | 1 设计单位 = 1dp，字就是 13sp | 看**内容**：读标签、读阈值、读峰值 |
| 当前 | 1:1 并跳到 `cursor` 那个节点 | 问"现在走到哪一步"（最常用） |

打开页面时：若引擎在跑（有 `cursor`）→ 自动用「当前」定位一次，之后用户自己拖过就
不再抢镜头；引擎没跑 → 停在全图（静态全图也有意义）。
这些换算全在 `GraphLayout`（纯 `java.util`，无 `android.*`）：
**加载布局、绘制、命中判定共用同一份几何**，所以不会出现"探针说没事、实机点到别处"。
回归见 `tools/GraphLayoutProbe.py`（37 项，含"换一套视口后还点得准"）。

**引擎没跑也要能看图**：`Graph.ensureLoaded(cfg)` 在取图前做一次**静态装载**（幂等）。
此前 `Graph.load` 只在 BotService/SettleLoop 起跑时被调用 ⇒ 没跑就没图，页面只剩
「图还没装载」——那恰恰违背"判定图指的是整个程序的，不是运行到哪"。
配置的写入路径统一收口在 `BotService.reload()`，那里会 `Graph.invalidate()`，
于是改完配置下一次取图按新配置重建（不变量：**界面上印的阈值 = 当前 config**）。

⚠️ 网页版那坨 HTML/JS 是 Java 字符串拼的，而浏览器对 `<script>` 是**原子解析**：一处
语法错 = 全页失效，且手机上没控制台可看。所以硬规则是**只用单引号、不含任何反斜杠**
（Java 层不吞字符，浏览器侧也不会因转义炸掉整块）。`tools/GraphPageProbe.py` 兜底：
提取 → `node --check` 语法 → **用 stub DOM 真跑 `draw()`** 并断言阈值/条件/点击都生效。
（**原生版没有这个问题**：Canvas 绘制错了不会"整页白屏"，最多画歪——这也是换成原生
的一层收益。几何那部分的正确性由 `GraphLayoutProbe` 管。）

## 多分辨率

素材基准 1280×576（20:9）。work 帧统一缩放到高 576，**宽度随屏幕宽高比变**。
四种 ROI 映射（`screen.roi_mode` / `screen.profiles`）：

| mode | 语义 | 模板 |
|---|---|---|
| `abs` | 原样像素（旧行为） | 不缩放 |
| `center` | 尺寸不变，ROI 中心相对画面中心的偏移不变 | 不缩放 |
| `rel` | 横向拉伸铺满（x 缩 w/1280，y 不变） | 各向异性缩放 |
| `fit` | **letterbox 感知**：内容保 20:9 居中，其余黑边 | 等比缩放 |

20:9 下四者等价（真机零回归）；默认档：4:3 / 16:9 / 21:9 → `fit`，20:9 → `abs`。

`tools/VisionProbe.java` 有离线回归（纯 JVM，可挂 CI）：

```bash
cd phone/app
javac -cp <android.jar> -d .workbuddy/vprobe/classes \
      src/com/zhiyaunhe/sgmbot/Vision.java src/com/zhiyaunhe/sgmbot/Roi.java tools/VisionProbe.java
java -cp .workbuddy/vprobe/classes:<android.jar> VisionProbe
```

它合成 768/1024/1280/1344 四种宽度验证映射数学自洽（当前 3/3 全过），并给出交叉
对照：设备若按 letterbox 排版，`fit` 能救回，`center`/`rel`/`abs` 都不行。

⚠️ **哪种模式对应哪台机器，离线证明不了**：拿一张该分辨率的真机截图，看日志
`屏=WxH 档=… mode=…` 与判定图里的峰值，命中即选对。

⚠️ 缩小走**面积平均**不是最近邻：最近邻点采样在 0.6 倍（4:3）把 REMATCH 峰值从
1.00 打到 0.65，直接漏识别（`Vision.Frame.scaleXY`）。

## 匹配性能（金字塔粗搜）

**背景**：JS 版的 `images.findImage` 是 native OpenCV，无 ROI 全屏搜也是毫秒级；
Java 版是纯逐点 NCC，**照抄同样的"全屏搜"参数会直接卡死**。2026-10-11 离线实测
（`tools/PyramidProbe.java`，1280×576 帧，未命中 = 最坏情况）：

| 模板 | 尺寸 | ROI | 全搜耗时 | 走金字塔后 |
|---|---|---|---|---|
| `victory` | 280×70 | 限定 | 189 ms | —（本就全搜） |
| `ok_btn` | 160×66 | 限定 | 303 ms | —（本就全搜） |
| `srv_retry` | 138×46 | **无（全屏）** | **5711 ms** | **26~34 ms** |
| `srv_ok` | 420×140 | **无（全屏）** | **32724 ms** | **130~144 ms** |

`srv_ok` 全屏搜 32.7 秒 ⇒ escape 链每次要跑 38 秒以上 ⇒ **弹窗逻辑实际等于没做**。
JS 那边同一个模板毫秒级，差别只在引擎。

**做法**：成本（搜索位置数 × 模板面积）超过 `matcher.pyramid.cost` 时才启用 ——
先降到 1/4 分辨率粗搜定位候选，再回**原分辨率**在候选邻域精修取分。
分数与全搜一致（`PyramidProbe` 断言：原样贴图必须 1.000），只省时间。

| 键 | 默认 | 说明 |
|---|---|---|
| `level` | 2 | 0=关；1=1/2；2=1/4 分辨率粗搜 |
| `cost` | 2e8 | 位置数×模板面积超过它才启用（有 ROI 的小图不走这条） |
| `relax` | 0.3 | 粗搜阈值 = 判定阈值 − relax |
| `floor` | 0.5 | 粗搜阈值下限 |
| `top` | 16 | 保留并精修的候选数 |

⚠️ **粗搜阈值必须放宽**：`srv_ok` 降到 1/4 后只保 **0.658** 分（判定阈值 0.75），
若仍按 0.75 筛候选，真弹窗会被筛掉。掉分是高频细节（文字框）被面积平均抹平所致。
**最终判定仍用原分辨率分数** ⇒ 只会漏检，不会误检，不存在"乱点"风险。

### ⚠️ 金字塔只对"全屏搜大按钮"成立——别推广（2026-10-14 真帧实测）

上面那句"分数与全搜一致"**只在厚实按钮/弹窗模板上成立**。用真机结算页素材逐点量
（`tools/VisionBench.java` 的 D 段，模板**真在画面里**时的峰值）：

| 用例 | 全搜 | L1（0.5x） | L2（0.25x） | 耗时（L2 vs 全搜） |
|---|---|---|---|---|
| `victory` 280×70 | **1.0000** | 0.1504 | 0.1691 | 3.4x 快，但**丢检** |
| `defeat` 200×70 | **1.0000** | 0.1825 | 0.1840 | 4.7x 快，但**丢检** |
| `btn_rematch` 188×36（在槽里） | **1.0000** | **−0.0378** | 0.0745 | **反而慢 3~4x** |
| `srv_ok` 420×140（全屏） | 32700 ms | 633 ms | **33 ms** | 1000x 快，✅ 保分 |

三条结论，一条比一条重要：

1. **层数是召回主开关，比 `cost` 危险得多。** 装饰字体/文字类模板降采样后笔画被面积平均
   抹平，粗搜分数掉到粗搜阈值（`max(floor, th−relax)` = 0.50）以下 ⇒ 不产生候选 ⇒
   直接走"无候选"分支返回粗搜峰值。**命中变漏检，静默发生。**
2. **对"大模板 + 紧 ROI"它还是负收益。** `btn_rematch` 在 220×68 槽里：粗搜省下的被精修
   （`top × (2·rad+1)² × 模板面积`，rad 还要 ≥ `1/s`）吃掉，净结果是**慢 3~4 倍**。
   金字塔的收益来自"ROI 远大于模板"，紧 ROI 没有这个前提。
3. **⚠️ 粗搜分数与真相反相，所以"粗搜当门、不确定就回退全搜"这条退路也不成立。**
   同一批用例的粗搜分：**漏检帧 0.33~0.47 反而高于命中帧 0.07~0.18**
   （大体量深色文字压在花哨背景上会虚高）。粗分既不能判命中，也不能判"该不该回退"。

⇒ 因此 `SettleLoop` 里**召回关键**的匹配一律改走 `findExact(...)`（= `cost=1e12, level=0`
强制全搜）：大字计场 / FIGHT 追踪 / 大保底 / 结算三槽。**这些判定错一次就是漏计场次或
漏点按钮，不能用 CPU 换。** 只有 escape 链刻意保留 config 口径（`roi=null` 的
`srv_ok`/`srv_retry` 是全屏搜，只有金字塔能救：3.27e4 ms → 33 ms）。
按调用点覆盖口径用 `Vision.findScored(f, t, roi, th, pyrCost, pyrLevel)`（`<0` = 用全局）。

### 匹配内核：积分图 SAT + 通道缓存/整数内循环（2026-10-14，全匹配点 **7.2x**，零召回损失）

`nccAt` 每判定一个候选位置，旧实现要把模板面积**遍历两趟**：
① Σf 求均值 μ → ② 再遍历一趟算 Σ(f−μ)² 与 Σ(f−μ)(t−μt)。这是每帧最大的一笔纯开销。

恒等变形（**不是近似**）：

```
Σ(f−μ)²      ≡ Σf² − (Σf)²/n
Σ(f−μ)(t−μt) ≡ Σf·t − μ·Σt
```

⇒ 把 Σf / Σf² 预计算成**积分图**（`Vision.Sat`，按帧 lazy 缓存、口径 EPOCH 变了重建）后，
μ 与 fVar 变 **O(1)**，只剩 `Σf·t` 一趟。

| | 旧（两趟） | SAT | SAT + 通道缓存/整数内循环 |
|---|---|---|---|
| 帧窗口遍历 | 2 趟 | 1 趟 | 1 趟 |
| 每像素 | 2 次方法调用 + FP 乘加 | 2 次方法调用 + FP 乘加 | **纯 int 数组乘加** |
| 模板统计 | 每位置重算 | 每位置重算 | **每次搜索算一遍** |
| 附加内存 | 0 | 8.9 MB | 8.9 MB + 2.9 MB（通道 `int[]`）/帧 |

第二层优化（`Frame.cv()` + `Vision.Match`）：同一个像素在一次搜索里要被读**上万次**
（模板 19600 像素 × 上千个位置），而 `val()` 每次都做方法调用 + shift/and，还是 `double`
乘加。把通道值预抽成 `int[]`（模板侧随 `Frame` 缓存、**每次搜索只算一遍**）、把
`Σf·t` 改成 `long` 累加的整数乘加之后，内循环变成两数组乘加 —— 整数运算**精确**，
连 FP 舍入都没了。

**验证（`tools/VisionBench.java`，A/B 两段都是判据，两轮数据都在）**：

| 判据 | SAT | + 通道缓存/整数循环 |
|---|---|---|
| 逐点等价（ROI 内每个位置 vs 旧式两趟，6231~8711 点/用例） | 1.8e-13 | **1.8e-13** |
| 自贴图 | 1.000000000000 | **1.000000000000** |
| 11 个真实调用点计时合计 | 648.9 → 396.6 ms（1.64x） | **759.8 → 105.2 ms（7.22x）** |

回归无变化：`VisionProbe` 全过、`PyramidProbe` 6/6、`StoreProbe` 35-0、
`SlotProbe` 逐位复现标定分（VICTORY 页 REMATCH `1.000 @546,494`、CONTINUE `0.951 @810,494`、
btn_quit 0.623）—— **所有峰值分与改动前一位不差**。

⚠️ **SAT / 通道缓存都不做全局复用** —— 常规结算循环与 fast 观察线程可能并发调 `Vision`，
共享可变缓冲会串数据。旧实现全用局部变量所以线程安全，这条性质必须保住（代价是每帧
约 11.8 MB 短命数组，帧 ≥1 s 一张，可接受）。

⚠️ `Σf` 用 `int[]`（1280×576×255 = 1.88e8 < 2³¹）、`Σf²` 用 `long[]`（4.8e10）；
`Σf·t` 的累加**必须 `long`**（58800×255² = 3.8e9 > 2³¹，`int` 会溢出）。

⚠️ `fVar = Σf² − (Σf)²/n` 在平坦块上可能**舍入成微负** ⇒ 夹到 0 再开方，否则 `sqrt`
出 NaN（旧式是平方和，恒非负，所以这是新引入的边界，别删那行）。

### 真机分段墙钟：帧周期花在哪（2026-10-14，K70 Pro `[观]` 日志自带）

`observeFast` 的分段墙钟（截屏 / 缩放 / 匹配）直接打在 `[观]` 那行上，均值：
`帧用时均值 3330ms (截屏 ~515 / 缩放 ~17 / 匹配 ~2790)`（版本 44，SAT 之后、
通道缓存之前的实测）。

三条结论：

1. **匹配占 84%** —— 截屏只占 15%，`toWork`（createScaledBitmap + getPixels）**几乎免费**。
2. **`screencap` 的"写盘再读回"不是瓶颈**（已量，别再做这条）：
   `screencap → /sdcard/*.raw` 331ms / `screencap > /dev/null`（**完全不落盘**）298ms /
   `cat 10.4MB` 43ms / `rm` 31ms / 换非 FUSE 的 `/data/local/tmp` **346ms（更慢）**；
   纯 sh 基线 `true` 6ms、`input tap` 58ms。⇒ 那 ~300ms 是 `screencap` 本身。
3. **真机/笔记本 ≈ 9 倍**：同一批调用点在笔记本上 105ms，真机上 ~2790/帧。
   ART 的 JIT 对付这种紧 double 内循环远不如 HotSpot ⇒ **离线基准只用来比相对快慢，
   别拿它的绝对值估真机**。

⚠️⚠️ **这条修正了上面那条旧口径**：既然帧周期（2.96~3.33s）≫ `observe_ms`，
**抬 `observe_ms` 几乎不省电**（1000→3000 只削 ~4% 帧率）；真机那个值是 1000，
**不用改**。该削的是每帧墙钟 —— 而它 84% 是匹配。

### 每帧成本与"恒定 1 核"（2026-10-14 实测，K70 Pro）

观察线程尾部是 `if (used < want) sleep_(want - used)` ⇒ **周期 = max(每帧耗时, observe_ms)**，
于是 `CPU/秒 = 每帧耗时 / 周期`：

| 关系 | 周期 | CPU |
|---|---|---|
| 每帧耗时 > `observe_ms`（当前实况） | = 每帧耗时 | **恒 1 核**（调 `observe_ms` 完全无效） |
| 每帧耗时 < `observe_ms` | = `observe_ms` | 每帧耗时 / `observe_ms` |

实测印证（当时的默认）：配置 `observe_ms=1000`，而 `app.log` 里 `[观] 第 N 帧` 实际 **2~3 s 一帧**；
挂机时 App 自身 **0.75 核** + Shizuku 子进程 0.33 核 ≈ 1.1 核 —— 正是"钉死在 1 核"。

每帧 NCC 工作量（= 搜索位置数 × 模板面积，`th=2.0` 的两处**不可能提前退出**，必然穷举）：

| 来源 | 每帧成本 | 频率 | 说明 |
|---|---|---|---|
| escape 链（6 条模板） | ~205M | **曾经每帧** | guard 一过期就重跑整条链；**失败不更新 `lastAct`** ⇒ 稳态每帧白跑 |
| 大字 `victory`/`defeat` | ~126M | 每帧 | `victory 280×70` 在 ROI 480×100 里有 1616 个位置 |
| 三槽峰值 `peakArr` | ~23M | 每帧 | 纯诊断（只喂 `[观]` 日志 + 默认关的 `tap_check`） |

⇒ 每帧 ≈ 2.5 核秒。**四处瘦身（2026-10-14）**：
① escape 重试按 `popup.every_sec`（5s）节流 —— 触发条件本身就是 5s 尺度，比它更密没意义；
② `peakArr` 按 `fast.diag_ms`（默认 2s）节流（`tap_check=true` 时强制每帧 —— 它是停车判据）；
③ 截屏改裸 RGBA（见下），省掉设备侧 PNG 编码 + App 侧 PNG 解码；
④ **匹配内核改积分图 SAT**（见上），所有匹配点 **1.64x**、零召回损失。

⚠️ ④ 之后剩的大头是 **escape 链**（其中 `ok_btn` 49.6M / `scene_x` 39.7M / `modal_x` 28.8M
  / `vs_fight` 17.0M 四条走全搜，只有 `srv_ok`/`srv_retry` 走金字塔）—— 而这四条**不能**
  塞进金字塔（见上节三条结论）。要再往下压只能换算法（如带界早退），不是调参。

⚠️ **关键结论：降每帧耗时本身不降温。** 每帧降到 ~0.9 核秒后，周期会缩到 `observe_ms`，
CPU/秒 仍是 `每帧耗时/周期` —— 想真降温必须**同时**把 `observe_ms` 抬起来，那才是 1/2 ~ 1/3。
两条腿的活，缺一条都白做。**默认已在 2026-10-14 从 1000 抬到 3000**（更灵敏可用 2000）。
⚠️ 抬之前先看**计场数有没有掉**：3 s 采样理论上可能漏掉一闪而过的大字（战斗里
`fight_scan_ms=500` 那条独立窗口不受影响，所以只影响计场，不影响 FIGHT 追踪）。

### 截屏口径：裸 RGBA（2026-10-14）

`screencap` 不带 `-p` 时直接吐 `帧头 + w*h*4 字节 RGBA_8888`（K70 Pro 实测帧头 16B：
`w=2400 h=1080 format=1`），App 侧 `copyPixelsFromBuffer` 直接贴进 ARGB_8888 bitmap
（小端机上 RGBA 字节序 == ARGB 整数布局，不用逐像素搬）。单次子进程 CPU 实测
（20 次均值，走 `/proc/<shell>/stat` 的 `cutime+cstime`）：

| 口径 | 文件 | 单次 CPU |
|---|---|---|
| `screencap -p`（PNG） | 1.43 MB | **0.640 s** |
| `screencap`（裸 RGBA） | 10.37 MB | **0.177 s** |

⇒ 每次省 0.46 s（**3.6x**），代价是写读盘 1.4 → 10.4 MB（UFS 上无感；`input tap` 同口径
实测仅 0.040 s，不是瓶颈）。帧头长度**按文件长度反推**（Android ≤10 = 12B / 11+ = 16B），
尺寸/格式/帧头任一对不上就置 `rawUnsupported` **永久回退 PNG** 并留一行
"裸 RGBA 截屏不适用"。**确认口径**看首帧那条 `[快] 落点就绪 … | 截屏 raw|png` 日志。

### `matcher.step` 2→3：全匹配 **~2x、判定零变化**（2026-10-14 五帧实测，**已落地**）

`step` 是搜索位置数的唯一全局旋钮：位置数 ∝ 1/step²，所以 2→3 理论上少算约 2.25 倍。
`tools/VisionBench.java` 新增 **E 段**扫 step 1/2/3/4，跨 **5 帧 × 11 个真实调用点**（每点
3 次取最快）：

| | step1 | step2 | **step3** | step4 |
|---|---|---|---|---|
| 耗时合计 | 1449.0 ms | 405.5 ms | **197.2 ms** | 126.0 ms |
| 相对 step2 | 0.28x | 1.00x | **2.06x** | 3.22x |
| 命中判定变化 | — | 基准 | **0 处** | 0 处 |

- **走 `findExact()` 钉死全搜的那 8 个调用点**（大字计场 `victory`/`defeat`、FIGHT 追踪、
  大保底 `home`、结算三槽）**在 step3 下峰值分与 step2 逐位相同**。这不是运气：粗网格相对
  真峰值的最大偏移是 `(step-1)/2` = **1.0**，而收尾的 `refineBest` 邻域是 ±`refine`(=2)
  ⇒ 必然覆盖到真峰值，精修后回到同一个 argmax。
- ⚠️ **唯一例外是走金字塔的两条**：`srv_ok`/`srv_retry`（roi=null 全屏）峰值会漂，
  本批最大 **0.1756**。机制不同 —— 金字塔是"粗搜收候选 + 原分辨率精修"，粗网格变稀会让
  候选集变小。本批 5 帧里它们都远低于阈值所以判定没变，但**真弹窗帧的召回没有被验证过**
  （手上没有真弹窗素材）。
- ✅ **落地方式：全局 `matcher.step=3`，`srv_ok`/`srv_retry` 在 `escape.items` 里按条目
  写 `"step": 2` 钉回去** —— 吃到约 95% 的收益，把全部未验证风险隔离在那两个模板上
  （它们只占 11.2 / 405 ms）。
  - 机制：`Vision.findScored(..., int step)`（第 7 个参数，`<0` = 用全局）—— 与
    `pyramid.cost`/`level` 的按调用覆盖是同一套思路（**全局旋钮一刀切会切到不该切的地方**，
    这个教训在金字塔那边已经付过一次学费）。
  - `SettleLoop.escape()` 从每个 item 读 `step`（缺省 `-1` = 全局），代码里**不硬编码模板名**。
  - ⚠️ 这条推导**依赖 `refine ≥ (step-1)/2`**：step=3 要求 `refine≥1`，默认 2 安全。
    别只调其中一个（把 `refine` 调到 0/1 会让 step≥3 立刻变得不安全）。编辑页提示已写明。
- ⚠️ **位置数变少会改变金字塔判据**（`cost = 位置数×面积`）⇒ 有的调用点可能从"走金字塔"
  翻成"全搜"，反而更慢。本批没翻（step=4 时 `srv_retry` 仍有 2.4e8 > 2e8 门槛），
  但**改 `step` 或 `pyramid.cost` 都要重跑这一列**。
- **别用 step=4**：虽然有 3.22x，但 `defeat@title` 0.3758→0.3682、`continue@slot0`
  0.5224→0.4600、`scene_x` 0.3201→0.2864 峰值开始掉 —— 粗网格最大偏移 2.0 已顶到 refine
  邻域边界，掉多少取决于"粗峰值是不是离真峰值最近的那格"。
- ⚠️ 这只是笔记本 JIT 的绝对值；比值可用，但界/分支多的路径在 ART 上可能更亏，**上机复核
  时看 `[观]` 行的峰值有没有整体下移**（尤其 `victory`/`defeat` 那两格）。

### 有界部分相关（ZBPC）：**实测否决**（2026-10-14，K70 Pro 素材离线 + 4 帧真机结算页）

调研结论（`tech-option-research` 那两个子 agent）说 BPC 族是算法侧最对口、且**精确**的路：
ZBPC 论文（Di Stefano/Mattoccia/Tombari 2005, PRL 26(14)）给 **1.31–3.91x、跳过 >72%**。
实测**不成立**——`tools/ZbpcProbe.java`（E0 自检 + E1~E5）把原因钉死了：

| 生产口径（命中即返回，与 `fullSearch` 同行为） | P1 全累加 | ZBPC 精确剪枝 | 加速 |
|---|---|---|---|
| 全部 32 个调用点 | 293.1 ms | 287.5 ms | **1.02x** |
| **稳态（模板不在场）28 例** | 270.9 ms | 269.5 ms | **1.00x** |
| 阈值剪枝（P2t，目标=判定阈值） | 293.1 ms | 91.8 ms | **3.19x** |

**为什么论文的 3.91x 搬不过来 —— 两条，都已量化：**

1. **剪枝强度取决于"当前最优分"，而稳态下没有任何东西匹配。** 目标的 `[观]` 帧实测峰值
   只有 **0.34**（`E2b` 那行 "峰值真值均值 0.341"），而界的初值是 1.0、要随行数递减到
   0.34 以下 ⇒ **平均要累完约 70% 的行**才剪得掉，只省约 30% 乘法。论文的测试集里候选
   中本来就有高分，界很早就咬住 —— 那是另一个数据分布。
   ⚠️ 所以**"跳过率 98%"具有严重误导性**：它把"累到 98% 才丢"也算成跳过了。看真实成本
   要看**平均处理行占比**（`均行%` 列，实测 65–73%）。
2. **界检查是逐行的固定开销，与模板宽度无关 ⇒ 模板越窄越亏**（`E4`）：

| 模板宽 | 每位置界开销 | 少算乘法 | 净收益 |
|---|---|---|---|
| 32 px（`modal_x`） | 109 ns | 27.8% | **0.90x（反亏）** |
| 48 px（`scene_x`） | 190 ns | 33.5% | 1.07x |
| 160 px（`ok_btn`） | 804 ns | 34.9% | 1.05x |
| 165 px（`vs_fight`） | 289 ns | 28.9% | 1.19x |
| 280 px（`victory`） | 1449 ns | 64.2% | **2.11x** |

收益 ∝ 模板宽度（每行要累的乘法数），开销 ∝ 行数。窄模板（`modal_x` 32px、`scene_x` 48px）
本来就便宜，界检查直接吃光收益。**而"不在场"的调用点恰好就是这些窄的小图标**。

**在场时也帮不上**：生产 `fullSearch` 一旦 `ncc >= th` 就 refine 后立即返回，实测在场用例
在**第 859 / 1199 个位置**就命中并退出（P1 只有 5.2–5.7 ms，而非全扫 10 ms）⇒ 本来就不贵。
贵的正是"不在场"那一趟全扫，恰好是剪枝最弱的情形。

**唯一的真收益 = 阈值剪枝（P2t）**：把剪枝目标从"当前最优分"换成"判定阈值"，稳态
**3.28x**。**判定仍然精确**（被剪位置严格低于阈值，不可能丢命中；32/32 用例判定一致），
但它把"当前最优分"这个概念丢掉了，于是：

> ⚠️ **代价已量化：未命中时整帧峰值从 0.341 变成 `-2.000`**（所有位置都被剪空，一个都没累）。
> 判定不受影响，受影响的是 `[观]` 日志与判定图里"**差多少没过阈值**"那个数 —— 0.69 卡住
> 和 0.31 无关本来是两个意思，变成 `-2.000` 之后就都不认识了。
> 要保留一点诊断能力，可以在剪枝处记 `(A_S + sqrt(Vf_R·Vt_R)) / den` —— 那是该位置真分数的
> **上界**，"整帧最高不超过 X < th" 是**可靠**的说法。

**结论：精确 ZBPC 不做**（1.02x，值是负的）。阈值剪枝是唯一值得考虑的形态，但必须接受上面
那个诊断代价，且当前 App 已经只有 0.35 核 ⇒ 换来的绝对收益有限。**别为了"论文说 3.91x"
去实现它。**

工具用法（仓库根）：
```
javac -cp <android.jar> -d <out> phone/app/src/com/zhiyaunhe/sgmbot/Vision.java phone/app/tools/ZbpcProbe.java
java  -cp <out>;<android.jar> ZbpcProbe [素材目录] [模板目录]
```
⚠️ **两个坑都固化在工具里，改它时别删**：① E0 逐点自检 —— 复刻的通道/积分图口径必须与
`Vision.scoreAt` 一位不差（实测 Δ=0，不过这一关后面数字全无意义）；② E1/E3 的**剪枝复核**
列 —— 把每个被剪位置用全累加回算真分数，必须严格小于最终峰值。**第一次实现就是靠它抓到
"部分集套用了全集恒等式"的错**（部分集 `Σ_S(f-μf) ≠ 0`，必须补 `-μt·Σ_S f + n_S·μf·μt`
两个交叉项，否则"上界"不再是上界，静默剪掉真正的 argmax）。**只比 argmax 不够** ——
28k 个被剪位置里只有 1 个违规。

## 结算页判定（REMATCH / CONTINUE）

结算页底部有**三个按钮槽**（`btn_rois`，各 220×68），但**每个页面放什么按钮不一样**：

| 页面 | 左槽 | 中槽 | 右槽 | 该点哪个 |
|---|---|---|---|---|
| 胜局奖励页 | HIDE STATS | SELL ALL | **CONTINUE** | CONTINUE（前进） |
| 胜局奖励页（角色卡那页） | FAVORITE | **REMATCH** | **CONTINUE** | **REMATCH** |
| 败局页 | QUIT | FAVORITE | **REMATCH** | REMATCH |

判定顺序：先在三个槽里找 `btn_rematch`，**过阈值就点**；找不到才找 `btn_continue`。
⚠️ **CONTINUE 的代价是回大地图**（打断循环），所以它只该在"这一页确实没有 REMATCH"时被点。

### 为什么会出现"莫名其妙连点两次"（2026-10-11 修复）

旧实现有一条"先手"捷径：**一见到 VICTORY/DEFEAT 大字就盲点固定坐标 `first_tap`(903,512)**，
省掉一次按钮匹配。问题在于 —— **右槽在"有 REMATCH 的那页"上正好是 CONTINUE**
（离线实测 `btn_continue` 全帧最佳 0.951 @(810,494)，模板 188×36 ⇒ 按钮中心 (904,512)，
与 `first_tap` 严丝合缝），所以那一按 = **直接回大地图**。

而 `seen` 标志在"无大字"的过场帧会被复位（结算有多页、每页都有大字），于是
**同一轮结算会先手两次**：页A 按一下（对，前进）→ 换页时大字闪断 → 页B 又按一下（错，回大地图）。
用户观察到的「**点得慢一点反而能点对**」也吻合：画面稳定不闪断时 `seen` 不复位，就不会重复先手。

**改法**：`first_tap` 与"败局先手即计场"一并退役。大字只负责"进入结算阶段"，
点哪个按钮一律交给 `phase 1` 的按钮匹配（REMATCH 优先，找不到才 CONTINUE）；
败局计场随之挪到 phase 1 点 REMATCH 那一刻（那里本来就有 `countRound`）。
判定图里的 `first_tap` / `lose1` 两个节点同步删掉，`bigword → result` 直连。

**现场取证工具**：`tools/SlotProbe.java` 对**任意帧**（真机 `adb exec-out screencap -p` 抓的
原图也行，会自动转正 + 缩到高 576）输出「模板 × 三槽」NCC 峰值矩阵 + 全帧最佳坐标
（区分"ROI 框错"还是"模板配不上"）+ 三个槽的裁图 + 按 phase1 **真实顺序**的模拟判定
+ **快速连点落点体检**（落点坐标 + 是否落在 HUD 挡块里 + **速度泡在不在落点上** +
  落点归属哪个按钮框），直接说明"代码会点哪个、为什么"：

```bash
cd phone/app
javac -encoding UTF-8 -nowarn -cp ../../vendor/android-build/platforms/android-34/android.jar \
      -d /tmp/slotprobe src/com/zhiyaunhe/sgmbot/Vision.java \
      src/com/zhiyaunhe/sgmbot/WorkPoint.java tools/SlotProbe.java
java -Dfile.encoding=UTF-8 \
     -cp "/tmp/slotprobe;../../vendor/android-build/platforms/android-34/android.jar" \
     SlotProbe <帧.png|jpg> assets/templates/ 0.72
```

（落点由 `WorkPoint.java` 算，**探针与运行时引的是同一个类**，不会再出现
"探针说没事、实机点到别处"。）

`SettleLoop` 每次判定还会往 `logs/app.log` 打一条 `[判]` 行，带**两个模板 × 每个槽的峰值**
—— 出问题时不必再猜"是 REMATCH 漏识别还是别的槽抢跑"。

## 快速连点模式（`fast`，默认关）

用户 2026-10-11 提的"状态切换"。前提是两件事，缺一不可：

1. **一直胜利**（败局页排布不同，`QUIT | FAVORITE | REMATCH`）；
2. **该结算页没有额外奖励按钮** —— 有 `SELL ALL` / `HIDE STATS` 的那版奖励页，
   按钮行中间那颗**正是 SELL ALL**，盲点它会卖道具。

在满足前提的前提下，**第一个界面的 CONTINUE 与第二个界面的 REMATCH 落在同一个点**：
结算按钮行是**整行居中**的，所以"单按钮页"（MATCH REWARDS，只有 CONTINUE）的中那颗
和"三按钮页"（FAVORITE | REMATCH | CONTINUE）的中间那颗都是 `btn_rois` 中槽中心。
⇒ 一直点它就能做到 页A 前进 / 页B 重开，整条结算-再战链**不需要任何模板匹配**。

### 落点为什么要绕开（2026-10-11 用户报障「会点到 x3 那里」）

中槽中心本身**不能直接点**。战斗界面的 `1x / 2x / 3x` **速度泡**与结算按钮行是两套独立
HUD，而它在 K70 真机上的位置**正好压住中槽中心**（都在 x=640）：

| 对象 | work 坐标（K70，2400×1080 → work 1280×576） | 依据 |
|---|---|---|
| 中槽 / 结算按钮面板 | 槽 x530~750；面板 x546~734，y486~528 | `btn_rois` + `btn_continue` 匹配 0.993 |
| **速度泡**（1x/3x） | **x608~672，y450~518**（内圈 610~670 × 452~512） | `battle_spd_1x` 匹配 **1.000 @(610,452)**（`wt3.png`），扫中轴列/横行取实体边界 |

泡的下沿（~518）比按钮面板的上沿（~486）还低 ⇒ y 方向只挤出 10px，太紧；
而面板比泡**宽**（左侧 546~608、右侧 672~734 各约 60px 干净区）⇒ **往右挪**。

所以落点 = `WorkPoint.pick(中槽, config.fast.avoid, avoid_gap)`：默认把中槽中心推到
挡块右沿 + 24px ⇒ **K70 上实测落点 (700,512)**。真机帧复核：

```
结算帧 win_now.png  落点(700,512) 在 btn_continue 框 546,494,188,36 内 ✓  速度泡不在画面
战斗帧 wt3.png      速度泡 1.000 @框 610,452,60,60  落点在里面? 否 ✓
```

`avoid` 是**标定项**：换设备/换分辨率若又撞上别的 HUD，往列表里加一条即可
（`WorkPoint.DEFAULT_AVOID` 是缺省值，配置里写了就以配置为准；两者**必须一致** ——
`GraphProbe.py` 会断言，漂了就红）。`fast.xy` 手填的坐标也会体检，落进挡块会打 `[!!]` 日志。

**分工（这是它快的全部原因）**：

| 谁 | 干什么 | 频率 |
|---|---|---|
| 主线程 = 连点器 | 只 `input tap` 固定点，**不截屏不匹配** | `tap_ms`（默认 300ms） |
| 观察线程（独立） | 剥弹窗 / 大字计场 / 每日上限 / 无进展停车 / 状态快照 | `observe_ms`（默认 3s） |

截屏（`screencap` 一趟几百毫秒）是整条链最贵的一步。常规模式每轮都要截屏 + 匹配 +
`tap_delay_ms` 1200 ⇒ 一个结算周期 ~3s；快速模式省掉匹配、把延迟降到 300ms ⇒ ~0.6s。
**弹窗检测没有砍掉，只是搬出了热路径**（只有观察线程在截屏，避免两路 screencap 互相拖慢）。

**FIGHT 追踪窗口**（2026-10-11 用户提出）：弹窗剥完常常正落在**战斗准备界面**
（右上角 FIGHT!），连点器不知道此刻该按谁，盲点会点在 FIGHT 页的空白处。所以剥弹窗
命中后开一个 `fight_wait_s`（默认 15s）的窗口：

- 窗口内观察线程把采样提到 `fight_scan_ms`（默认 500ms），持续找 `vs_fight`
  （roi/th 缺省继承 escape 链里 vs_fight 那条 —— 同一素材只标定一次）；
- **命中即点**（进战斗）并**立刻**恢复连点（两个闸门一起清，不等 `popup_pause_ms` 走完）；
- **超时未命中也恢复连点** —— 弹窗后的下一站可能不是战斗页，不干等，绝不因"没等到
  FIGHT"停车。

两条兜底（都在观察线程里，任一条触发就停车）：

- **大字去抖**：`latch`（同一屏的大字只算一次 —— 结算页会持续显示 `VICTORY!` 好几秒）
  ＋ 时间下限 `round_gap_s`（1s 采样下换页闪断一般采不到，这条再兜一层）。
- **无进展停车**：`idle_stop_s` 内一场都没计到 ⇒ 连点没把流程推下去，多半已脱离战斗界面。

> ⚠️ **2026-10-14 用户口径：fast 只管「弹窗 + 大保底」。** 落点体检（观察到「结算按钮
> 过阈了、但不在落点那一格」就停车）**默认关**（`fast.tap_check=false`，只记一条提示日志）。
> 原因：结算页有两版布局，带 `FAVORITE` 的那版是三按钮（FAVORITE / REMATCH / CONTINUE），
> CONTINUE 被挤到第 3 格（峰值 0.95），而落点那格其实是 **REMATCH** —— 按钮变窄导致老模板
> 只匹配到 0.39 ⇒ 被判「落点不是结算按钮」⇒ K70 上半分钟内连停 5 次。而**盲点 REMATCH
> 就是再打一场，完全正常的推进**，停车才是错的那个。除非你确认落点真的是 `SELL ALL`
> 那类有害按钮，别开这个开关。

```jsonc
"fast": { "enable": true, "tap_ms": 300, "observe_ms": 3000,
          "round_gap_s": 5, "idle_stop_s": 300, "popup_pause_ms": 2000,
          "fight_wait_s": 15, "fight_scan_ms": 500, "tap_check": false,
          "avoid": [[604, 446, 72, 76]], "avoid_gap": 24 }
```

开/关两条路：主页 **「运行模式」一键切换**（写 override 单键 + 热重载），
或配置编辑页的「快速连点模式」段。**配置在循环开跑时读一次 ⇒ 切完要在悬浮窗上
「暂停」再「继续」才生效**（界面会明说这一句）。

现场看什么：观察线程每 10s（或页面变化时）打一条 `[观]` 行，带**两个模板 × 三槽峰值**
和落点坐标 —— 用它核对"落点那格到底是不是 CONTINUE / REMATCH"：

```
[观] 第 12 帧 → CONTINUE页 | rematch(0.34 0.34 0.15) continue(0.05 0.99 0.05) | 落点(700,512) 已连点 40 次 / 剥弹窗 0 次 / 本轮 3 场
```

`tools/SlotProbe.java` 也能离线核对同一件事：末尾报落点坐标 + **速度泡体检**
（泡在不在落点上）+ 落点归属哪个按钮框。K70 真机帧实测见上表。

⚠️ **手上还没有"第二个界面（有 REMATCH 那页）"的真机帧**（`debug/k70_nav/` 里没抓到）。
所以页B 的 REMATCH 位置目前是从 `btn_rois` 的标定推出来的（中槽，右移 60px 后仍是中槽内），
**没有独立帧证据**。第一次在真机上开这个模式时，请把 `[观]` 行发出来核对；
若页B 的 REMATCH 不在中槽，改 `fast.xy` 即可（坐标是标定项，配置编辑页不暴露，
直接编辑 `config.json`）。

## 弹窗处理

JS 版的弹窗逻辑**分布在三处**，Java 版只搬了其中一处：

| # | JS 位置 | 干什么 | Java 现状 |
|---|---|---|---|
| ① | `pf_nav.js waitHall` | 启动/等大厅**每帧**剥：`srv_retry → ok_btn → scene_x → modal_x` | ❌ 无（导航链未移植） |
| ② | `pf_nav.js dismissPopups` | 导航每步之间剥一遍 | ❌ 无（同上） |
| ③ | `settle_bot.js` stall 逃生 | 卡住 30s 后剥：`srv_ok → ok_btn → srv_retry → scene_x → modal_x → vs_fight` | ✅ `SettleLoop.escape()` |

Java 版**没有导航链**（`BotService` 里 `MODE_NAV` 退化为直接跑结算循环），所以
① ② 无处安放。补偿办法是把"每帧剥"这条语义挂到结算循环上：

- **弹窗守卫**（`popup.guard`）：**任何相位**都跑。判据 = 距上次有效动作超过
  `popup.after_sec`（默认 5s），之后每 `popup.every_sec`（默认 **15s**）剥一次。
  用"无动作"当判据，是因为**弹窗必然挡住按钮 ⇒ 必然无动作**。
  ⚠️ **`every_sec` 2026-10-14 由 5 抬到 15**：fast 模式下这条链是每帧匹配里最大的一块
  （约 60%），而它的触发条件本身（"超 5s 无动作"）就是 5 秒尺度的启发式 ——
  **重试得比触发条件更密没有任何额外信息量，只是把同一条链重复跑**（失败不更新 `lastAct`，
  所以 guard 一过期就每帧重跑）。抬到 15 即 escape 成本 ÷3；最坏情况是弹窗多留 10s，
  而它本身有 `popup_pause_ms` 与三槽兜底，正确性不受影响。
  **想再省就把 escape 链本身做便宜**（给"有没有弹窗"加廉价门卫模板），那是另一个改动了。
- **stall 逃生**：原有逻辑保留（`timing.stall_sec`，默认 30s），作兜底告警。

两条路**复用同一条 `escape.items` 链** —— 加/删/改一项两边同时生效。

链的顺序与阈值（全在 `config.escape`，改配置即可，不必发版）：

| 顺序 | 模板 | 阈值 | 区域 | 点哪 |
|---|---|---|---|---|
| 1 | `srv_ok` | 0.75 | 全屏 | 模板左上 + `ok_offset`（标定偏移，随模板缩放） |
| 2 | `ok_btn` | 0.8 | `480,300,320,180` | 模板中心 |
| 3 | `srv_retry` | 0.75 | 全屏 | 模板中心 |
| 4 | `scene_x` | 0.8 | `600,0,560,180` | 模板中心 |
| 5 | `modal_x` | 0.9 | `480,0,480,280` | 模板中心 |
| 6 | `vs_fight` | 0.75 | `1020,0,260,130` | 模板中心（VS 页开打） |

链全不中 → 用结算三槽位置兜底点一下（`escfb`）。

⚠️ `srv_ok` / `srv_retry` 是"全屏搜"，必须靠金字塔才跑得动（见「匹配性能」）。

⚠️ **精定位死锁 bug（2026-10-11 修）**：粗搜步距 `STEP=2` 只走偶数格，而精定位
原先**只在分数已过阈值时**才调用 —— `srv_retry.png` 原样贴进画面 1x 也只有
**0.642 < 0.75**，永远够不着阈值 ⇒ 这类"差一点"的高频模板**必然漏检**。
修法是**无条件**对粗搜峰值做一次精定位（分数据：0.642 → **1.000**）。

⚠️ 仍未做（M2）：**导航链**本身（回大厅 → 点 EVENTS → 翻卡 → PLAY → VS FIGHT）。
缺它，bot 只能从"已经在结算页/战斗页"开始；启动时停在大厅或断线页，剥完弹窗
也不知道该去哪（`selfHeal()` 目前只记日志，PLAY 拉回同样待 NavChain）。

## 大保底（`map_check`，2026-10-11 用户提出）

挂机最怕的静默失败是：**人被顶回大地图/大厅了，bot 还在原地傻点**。stall 链对这种
"干净的地图页"无计可施 —— 没弹窗可剥（守卫剥不动），`selfHeal()` 只记日志。所以
单独加一层保底，**两个模式都挂**（常规主循环 + fast 观察线程）：

- **判据**：左上角 `map_check.roi`（= `home_roi`，`[0,0,180,70]`）里出现 **home 房子
  图标**（`home.png`，36×36，大地图/大厅左上角必然有它；战斗/结算/VS 页没有）。
  每 `every_s`（默认 **300s = 5 分钟**）查一次。
- **防误停**：单帧命中**先复检一帧**（隔 800ms 重新截屏再判）；连续两帧都过阈才停车。
  36×36 的小图标纹理少，NCC 在别的界面容易虚高 —— 误杀一次 = 整轮挂机白跑，保守
  方向是"漏报"不是"误报"。
- **只停不点**：确认退图就 `run=false` + 一条 `[停]` 日志，**绝不盲点** —— 重进
  EVENTS/PLAY 要等 NavChain（M2）。
- **不命中绝不停车**：每次检测都记一条 `[图]` 峰值日志，现场拿它调 `map_check.th`
  （默认 0.85，比 `min_sim` 严；误停调高、漏报调低）。

```
[图] 大保底检查 → home 峰值 0.93 th=0.85 命中! 复检一帧…
[停] 大保底: 左上角连着两帧都是 home 图标 (峰值 0.93/0.94) — 已退到大地图/大厅, 停车 (关掉改 config.map_check.on=false)
```

## GitHub 拉取更新

数据源 = repo 的 **latest release** 资产，CI 每次 push 自动发：

| 资产 | 内容 | 落点 |
|---|---|---|
| `bundle.zip` | `templates/*.png` + `config.json` | 解到 `Paths.root()`，热更（下轮生效，不必停引擎） |
| `sgmbot.apk` | 整包 | **两条路**：① `pm install -r`（需 Shizuku 就绪 + shell **实测**读得到下载路径）；② 走不通就把 APK **交给系统安装器**让用户点一下「安装」（见下） |

版本判据用资产的 `updated_at`（CI 是固定 tag `latest` 反复 `--clobber` 覆盖同名
资产，tag 恒不变，只有 `updated_at` 会动），本地水位记在 config 的
`meta.bundle_version` / `meta.apk_version`。

入口：主页「检查更新」「拉取更新」／广播 `CMD?action=check_update|update|
update_bundle`／HTTP `GET /update`、`POST /update?apk=0|1`。开跑时还会按
`update.auto_check_h` 后台自查（默认只热更 bundle，不自动装 APK）。

- bundle 里的 `config.json` **只补新键**，不覆盖本地手改；想强制覆盖就放
  `config_patch.json`。
- 未授所有文件访问时 shell 读不到 App 私有目录 ⇒ 第一条路走不通 ⇒ **自动转第二条路**
  （弹系统安装界面），bundle 照常热更。
- `update.allow_apk=false` 管的是「**自动检查时**要不要顺手装整包」，不是"永远不许装"。

### APK 自更新：判据是「shell 实测」，不是 App 侧旗标（2026-10-11）

装 APK 前先让 shell 自己去读一下文件，命令的真假**不依赖任何 App 侧推断**：

```
shell$ test -r <下载路径> && echo SGMBOT_READABLE || echo SGMBOT_DENIED
```

读得到 → `pm install -r`；读不到 → 跳过并报**真实路径**。
`Paths.legacy()`（"App 认为自己有没有授权"）只用来在失败时补一句有信息量的差别文字。

⚠️ 为什么不能拿旗标当闸：旗标曾经因 `Paths.init` 提前返回而**恒为 false**
（冷启动时 `root` 的静态初值恰好等于目标值 ⇒ 直接 return ⇒ 赋值那行永远轮不到 ——
详见 `tools/PathsProbe.py` 头注释）。后果是**明明授过权的机器被一口咬定"未授"，
APK 永远装不上**，文案还主动把人往"去授权"的错方向带。用户报障原话：
「我早就授权了所有文件访问，为啥他还自己安装不了啊？说 shell 读不到」。
现在即使旗标再错，shell 说读得到就照装 —— 这类「状态推断错 ⇒ 功能静默关闭」不会再发生。

⚠️ 另一个**独立**的装不上原因是版本降级：本地构建包（`build-local.sh` 故意把
`versionCode` 抬到 9999，好让本地包能盖住 CI 包）会永远压住 CI 包，`pm` 回
`INSTALL_FAILED_VERSION_DOWNGRADE`。此时会自动带 `-d` 再试一次（平台只对 debuggable
包放行降级，不保证成），并把 `pm` 的**首行原因**写进 `apk.pm_msg` ——
以前这条路径不写 `msg`，`brief()` 取默认值"跳过"，界面上只剩两个字。

### APK 第二条路：交给系统安装器（2026-10-11）

用户原话：*"系统的 ADB 安装…如果你安装失败的话，就走第二条路，就直接用系统的文件管理
把那个安装给打开，直接就安了"*。

第一条路（`pm install`）的前提有三条：**Shizuku 在跑 + shell 读得到 APK 所在目录 +
不撞版本降级**。三条全是**机器状态**，任一条不成立，"装个新版本"这件事就整条消失 ——
而 `sgmbot.apk` 明明已经躺在手机里了，用户唯一需要做的其实只是**点一下系统弹出的
安装界面**。所以第一条路任一步失败就自动转第二条路：

```
ApkFile.java      纯 java: authority / MIME / 最小字节数 / 裸文件名解析 (无 android 依赖)
ApkInstall.java   检查「安装未知应用」→ content:// + ACTION_VIEW(apk mime) 弹系统安装器
ApkProvider.java  只读 provider: 只把 update/ 下的**裸文件名**借给安装器
```

| 关口 | 为什么 |
|---|---|
| `ACTION_VIEW` + `application/vnd.android.package-archive` | 系统安装器按 mime 认领这个 intent —— 这就是"直接把安装打开" |
| `content://`（`ApkProvider`） | **不能用 `file://`**：API 24 起跨进程给 `file://` 直接抛 `FileUriExposedException`。APK 又在 App 私有目录里，安装器是另一个 uid |
| `FLAG_GRANT_READ_URI_PERMISSION` | 这条路的**命门**：不带它安装器读不到 `content://`，而它报的错是"解析包时出现问题"，完全指不到权限上 |
| `REQUEST_INSTALL_PACKAGES` | API 26+ 缺它 `canRequestPackageInstalls()` 恒 false，安装界面被系统直接拒 |
| 缺「安装未知应用」时先跳设置页 | 不硬弹安装界面（那样会被系统再绕一层），直接把人送到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 并说明回来再点一次 |
| 主 intent 没人认领 ⇒ 再试 `ACTION_INSTALL_PACKAGE` | 有的 ROM 只认老动作；两条都试过才敢说"系统里没有能装 APK 的界面" |
| 文件 < 100KB 一个界面都不弹 | 半截文件交给安装器，报的错是"解析包时出现问题"，指不到"下载断了" |
| 开关 `update.system_installer_fallback`（默认开） | 关掉就退回"只报原因"的老行为 |

`ApkProvider` 是个**一直挂在系统里**的接口，所以边界写死三条：只认**裸文件名**
（带 `/`、`\`、`..` 一律拒）、只在 `Paths.updateDir()` 里找、只允许 `"r"` 模式
（`insert`/`delete`/`update` 全空实现）。任何一条放松它就变成"任意文件读取器"。

⚠️ **水位不能瞎猜**。第二条路是**异步**的：界面弹出去之后，装不装成由用户在系统界面里
决定。所以弹出前记 `meta.apk_pending`（= 远端 `ver_apk`）+ `apk_pending_at`，
下次检查时：

| 情况 | 判据 | 结果 |
|---|---|---|
| 用户真装了 | **本包自己的 `lastUpdateTime` ≥ `apk_pending_at`** | 推进 `apk_version`、清 pending ⇒ 说"已最新" |
| 用户没装（取消）且过了 30 分钟 | `now - apk_pending_at > 30min` | pending 作废 ⇒ 重新提示有更新、可重下 |
| 还在等他点 | 其余 | `need_apk=false`（**不重复下载 400KB**）、文案说「待安装 — 系统安装界面已弹出，去点安装」 |

判据用 `lastUpdateTime` 是因为它**是执行方自己说的**（同"shell 实测"那条规矩）：
不这样做就只有两种坏结果 —— 要么每次检查都说有更新、反复下载再弹一次界面（像抽风），
要么每次都说"已安装"（用户明明没点，就是之前报障的「版本号不变」那类谎话）。

⚠️ 后台启动限制（Android 10+）：App 在后台时 `startActivity` 会被系统静默拦掉。所以
这条路只在**用户主动点「拉取更新」**时才有意义；自动检查永远 `withApk=false`
（`autoCheck(ctx, cfg, false)`），不会撞上这条限制。

### 界面反馈（2026-10-11 修「点更新版本号不变 / 没进度 / 没提示」）

| 机制 | 位置 | 说明 |
|---|---|---|
| 进度 | `Update.stage()` / `pct()` / `busy()` | `down()` 按 Content-Length 报百分比；查询/解包/安装报阶段名。主页**轮询**到 `busy()` 归零才落结果 —— 旧实现是 `postDelayed` **只读一眼**，网络慢就显示"还没拿到结果"，且之后再也不刷新 |
| 成败 | `Update.lastLevel()` | `LV_OK` ✓绿 / `LV_WARN` !黄 / `LV_ERR` ✗红。**别再在文案里找"失败"两字**：旧判据 `!r.contains("失败")` 对 `"拉取: update.repo 未配 — …"` 判成成功 ⇒ **错误显示成绿 ✓** |
| 版本 | 状态区 `App 版本` / `bundle 水位` | `versionName` 由 CI 每次构建递增（装上新包必变）；水位显示**本地 → 远端 + 有更新/已最新** |
| 文案 | `brief()` | **不再显示 `tag=`**：CI 固定 tag `latest` 反复覆盖，tag 恒定 ⇒ 用户以为"版本号没变"（`UpdateProbe` 有专门断言防回归） |

⚠️ **更新入口不依赖服务** ⇒ 配置层一律走 `BotService.cfgOr(ctx)`，不要读 `cfg` 字段。
服务没起过时 `cfg == null`，而"打开 App 就点更新"恰恰是最常见顺序 —— 旧代码在那条路上
把 repo 读成空串，永远回「update.repo 未配」，用户明明配了。同一个兜底写法曾经在
`ConfigActivity`/`HelpActivity`/`StatsActivity`/`DebugHttp` 各抄一份，**唯独 `trigger` 漏了**，
现在收口到一处。

⚠️ `Update.sec()` 在配置里**没有 `update` 段**时返回空 JSON（不是 null）—— `optJSONObject`
键不存在时返回 null，而调用方一律 `sec(cfg).optString(...)` ⇒ 一个 NPE 就让整条链在
background 线程里无声失败（UI 只看到"没反应"）。`meta()` 一直这么防御，`sec()` 曾漏掉。

回归：`python3 phone/app/tools/UpdateProbe.py`（桩掉 Android 与文件系统，跑**真的**
`Update.java` + `BotService.java` + `ApkFile.java` + `ApkInstall.java`）。含真下载 +
水位推进 + 端到端落临时沙盒 + **H 组「旗标错也不许挡住安装」** + **I 组「第二条路」**
（缺授权跳设置页 / `ACTION_VIEW` + `content://` + 一次性读权限 / 退老动作 / 两条都不行
也不谎报 / 半截文件不弹 / 待安装水位与 `lastUpdateTime` 自愈 / 超时作废 / 开关关掉）。
最后一节是**源码级断言**：`ApkInstall` 用的必须是 `Intent.FLAG_GRANT_READ_URI_PERMISSION`
这类**符号**（不是手写数字）、`ApkFile` 不许 import android、`ApkProvider` 必须只读、
清单里的 `authorities` 与 `ApkFile.AUTHORITY` 必须**逐字相同**。
无网络（或 GitHub 未认证限流 403）时 B/C/H/I 组自动标 SKIP，不算失败。
`Paths` 那条根因另见 `python3 phone/app/tools/PathsProbe.py`（6/6 场景）。

## 战绩热力图

「每日战绩」页三个页签：**热力图** / **明细** / **口径说明**。

布局同 GitHub contributions：列 = 周（最右是本周），行 = 周一~周日，一格一天，
颜色 = 当天**场数**档次（`stats.levels` 四档上界 → 5 色）。点格子看当天明细，
下面能给 13/26/52 周三档切换。

⚠️ **颜色深 = 打得多，不是打得好**。胜率另看（明细页每天一行 + 胜负条）。这点在
「口径说明」页里也写了一遍 —— 不做的话几乎所有人都会读错。

数据源是 `store.json` 的 `history` 归档（`Store.java`）：

```json
{"day":"2026-10-08","rounds":12,"wins":7,"loses":5,
 "history":{"2026-10-07":{"rounds":30,"wins":18,"loses":12},
            "2026-10-08":{"rounds":12,"wins":7,"loses":5}}}
```

跨天归零只重置 today 三键，**归档不归零**；每场落一次盘，所以崩溃/重启不丢。
`tools/StoreProbe.java` 是这套归档逻辑的离线回归（纯 JVM + 真 org.json）：

```bash
# 真 org.json 一份（android.jar 里的是桩, 跑不了）
# javac -cp <真 org.json>:<android.jar> -d out \
#   phone/app/src/com/zhiyaunhe/sgmbot/{Store,Paths,SgmLog,Config}.java tools/StoreProbe.java
java -cp out:<真 org.json>:<android.jar> StoreProbe     # 35 项断言
```

它专门盯三处易错又不显形的地方：跨天把归档一起清了、旧 `store.json`（没 `history`
键）升级后今天空白、`trim` 把最新几天裁掉。

## 判定图页面回归

页面是**一大坨 Java 字符串拼出来的 HTML/JS**，而浏览器对 `<script>` 是原子解析
——一处语法错就是整页失效，偏偏手机上没控制台。`tools/GraphPageProbe.py` 盖三层：

```bash
python3 phone/app/tools/GraphPageProbe.py
```

1. **提取** — 按 Java 字面量把 `GRAPH_HTML` 拼回来，断言不含反斜杠、`script` 块完整；
2. **语法** — `node --check` 整段内联 JS；
3. **真跑** — stub DOM 里执行并调 `draw()`，断言：不抛异常、每个节点渲染出一个
   `rect`、第 3 行是当前阈值、点击后 info 栏含条件与 `cfg` 路径、每个 `rect` 都绑 click。

`tools/GraphProbe.py`（纯 JVM）管的是图**结构**：30 个静态节点 + escape 展开 6 项、
阈值从 config 回填、布局无重叠、边不悬空、运行时 `Graph.cursor` 锚点全在图上，
以及**静态装载 / 幂等 / 作废后重建**这三条 `ensureLoaded` 行为。
此外它还有一节**源码级断言**（先剥注释再查关键词，否则注释里提到旧实现会被误判成回退）：
判定图不许再出现 `WebView`/`loadUrl`/自己连 8791，主页不许再出现「启动服务／停止引擎／
结束服务」—— 这两条都是**用户明确要求过、又最容易被改回去**的，且没有运行时特征。

```bash
python3 phone/app/tools/GraphProbe.py       # 33 项断言 + 11 条源码检查
```

`tools/GraphLayoutProbe.py`（纯 JVM，只需真 org.json，**不需要任何 Android 桩** ——
被测的 `GraphLayout` 本来就不 import android）管判定图的**几何**：

```bash
python3 phone/app/tools/GraphLayoutProbe.py # 37 项断言
```

它跑的是真的 `GraphLayout.java`：布局常量自洽（列距>节点宽、行距>节点高 ⇒ 不许重叠）、
世界包围盒、`fit` 后每节点都落在内边距里、屏幕↔世界换算互逆、**换一套视口（缩放+平移）
后仍点得准**、边端点贴在两框边界上（箭头才不飘）、反向边对恰有一条画弧、文案口径与
网页版一致、平移夹取。最后一节还用**真 `config.json`** 模拟 layout，把"真图 1817×950、
全图档只有 20% ⇒ 13sp 的字实际 2.6sp"这个事实打成断言 —— 这就是界面上给三个缩放档的
依据，也免得以后有人把「全图」当默认。

`tools/OverlayProbe.py`（纯 JVM + 一套最小 Android 桩）管**悬浮条生命周期**：

```bash
python3 phone/app/tools/OverlayProbe.py     # 36 项断言 + 5 条源码检查
```

它真的编译并运行 `OverlayBar` / `OverlayCtl` / `BotService`，用手驱 `onCreate`/
`onDestroy` 加 `WindowManager` 桩的 add/remove 计数当判据 —— 真机上"悬浮窗还在不在"
看不见摸不着，这里把它变成可数的。断言的正是「暂停≠结束」与所有权那几条：
独立启动 ⇒ `ownedByService()=false`；服务起来复用窗口时**不篡改**所有权；
`end()` ⇒ 窗口必须消失；`stop()` ⇒ 窗口必须留着；服务**意外**退出 ⇒ 用户那条留着；
`hide()` 同步摘引用 + `removeView` 延后一拍且幂等；无权限给人话错误；后台线程挂载不炸
（HTTP `/trigger?action=overlay_show` 走的就是那条线，此前会因缺 Looper 直接失败）。

> 桩是**有代价**的：它只证明我们自己这层的时序与所有权判定对，不证明真 ROM 上
> 窗口真的消失 —— 那一步只能在真机点一次「结束」确认（见「跑起来」那节的语义表）。

它还有一节**源码级断言**（同样先剥注释）：键名必须是「暂停 / 继续」而不是「停止」
（改名之后 javadoc 里天然会留下"「停止」改名「暂停」"这句解释 —— 不剥注释就会把
说明判成回退，断言永远红），且**动作名 `start`/`start_nav`/`stop`/`end` 必须原样**：
那是 adb 广播与 HTTP `/trigger` 的接口契约，改文案不能改协议。

C2 组则是"名字跟着状态走"：没跑过 ⇒ 键名「开始」；在跑 ⇒ 仍「开始」；**暂停后 ⇒
变「继续」**；结束/服务退出 ⇒ 回到「开始」。判据是 `BotService.paused()`
（`!running && everRan`）—— 只看 `running` 分不出"从没开始"和"暂停中"，而这两态该说
不同的话，这正是用户报的"名字有歧义"。

`tools/UpdateProbe.py`（同一套 Android 桩 + 临时沙盒）管**更新链路**，跑的是**真的**
`Update.java`、`BotService.java`、`ApkFile.java`、`ApkInstall.java`：

```bash
python3 phone/app/tools/UpdateProbe.py      # 44 项断言 + 5 条源码检查
#                                             (无网络 / GitHub 403 限流时 B/C/H/I 组标 SKIP)
```

断言分九组：配置层兜底（`cfg=null` 也要读得出 repo）／真链路检查（文案不含恒定 `tag=`）／
真链路拉取（真下载 → 解包 → **水位推进** → 再查已最新 → APK 走第二条路 ⇒ `LV_WARN` 黄不是绿）／
错误路径级别（`repo` 未配 ⇒ `LV_ERR`，且断言该文案里**没有**"失败"两字 ⇒ 证明旧的
字符串判据必然把错误显示成绿 ✓）／时间戳格式化／`busy` 不泄漏／配置缺 `update` 段不 NPE／
**能不能装 = shell 实测说了算**（H 组）／**第二条路 = 交给系统安装器**（I 组，见下）。

I 组（2026-10-11 加的兜底）分段断言：缺「安装未知应用」⇒ 不弹安装界面而先跳设置页；
正常时发的必须是 `ACTION_VIEW` + `content://<authority>/sgmbot.apk` +
`application/vnd.android.package-archive` + **一次性读权限** + `NEW_TASK`，且**绝不是
`file://`**；主 intent 没人认领 ⇒ 退 `ACTION_INSTALL_PACKAGE`；两条都失败 ⇒
`launched=false`（**绝不谎报**）且给出可手动装的路径；半截文件一个界面都不弹；
另有 `ApkFile` 的路径规则（拒 `../`、拒子目录、拒反斜杠）；以及水位三态
（待安装期间 `need_apk=false` 不重复下载 / 用户真装了 ⇒ 用本包 `lastUpdateTime` 自己认账
并推进水位 / 超 30 分钟没装 ⇒ 作废重提）与开关 `system_installer_fallback=false`
时退回老行为。最后一节是**源码级断言**：`ApkInstall` 必须用
`Intent.FLAG_GRANT_READ_URI_PERMISSION` 这类**符号**（有人换成手写数字的话，桩测试照样
绿而真机装不上）、`ApkFile` 不许 import android、`ApkProvider` 必须只读且只认
`ApkFile.resolve`、`Update` 不许自己拼 `Intent`（同一个动作两条路 = 病）、
清单必须有 `REQUEST_INSTALL_PACKAGES` 且 `authorities` 与 `ApkFile.AUTHORITY` **逐字相同**。

`tools/PathsProbe.py` 单独管**外部根与授权状态**（跑真的 `Paths.java`，6 个场景各起一个 JVM
—— 它的状态全是 static，一个进程里没法重来）：

```bash
python3 phone/app/tools/PathsProbe.py       # 6/6 场景
```

覆盖：冷启动已授权 ／ 跑着才授权 ／ 一直没授权 ／ 授权先给后撤 ／ 反复 `init` 幂等 ／
`updateDir()` 跟着 `root` 走。**这条是 2026-10-11「早就授权了却装不了」的根因所在**
（`Paths.init` 提前返回 ⇒ `legacy()` 恒 false，见该文件头注释）。

`tools/MapFightProbe.py`（纯源码级 + config 一致性）管 **FIGHT 追踪窗口**、**大保底**
与**落点体检默认不拦车**：

```bash
python3 phone/app/tools/MapFightProbe.py    # 48 项断言
```

它不跑 JVM（SettleLoop 依赖 Shizuku 截屏，桩不出来），改成在**剥了注释的源码**上钉
运行期测不到的行为：fight 窗口必须在剥弹窗后开启、命中即点并**清两个闸门**立刻恢复
连点、超时/缺模板只恢复**绝不停车**、th/roi 必须继承 escape 链同名项；map_check 必须
两个模式都挂、命中先复检一帧、`run.set(false)` 恰好一次且在复检之后、**只停不点**
（重进等 NavChain）、每次检测记峰值日志；落点体检必须**默认只提示**（`fast.tap_check`
读自 config、`run.set(false)` 恰好一次且在 `fxTapCheck` 分支里、提示带节流）；
config 侧钉 `map_check.roi` 与 `home_roi` 同源、素材存在、判定图节点与边不悬空。

> 2026-10-11 首次跑就抓到一个真 NPE（`sec()` 返回 null）。**探针的价值就在这**: 
> 这三条缺陷在真机上的表现全是"点了没反应", 不看日志根本分不出是哪一条。

⚠️ 跑 `tools/PyramidProbe.java` 时 classpath 里**真 org.json 必须排在 android.jar 前面**
（`-cp out:<真 json>:<android.jar>`）：android.jar 里的 `org.json` 是抛 `Stub!` 的桩，
顺序反了会报 `RuntimeException: Stub!`，看着像探针坏了。
⚠️ `tools/VisionProbe.java` 的素材/模板目录参数是**字符串拼接**用的，已在代码里补尾斜杠
归一化 —— 否则会拼出 `assets/templatesvictory.png`，只报一句"无法解码"。

想在 **PC 上直接看整张图**（不装 APK、不起 8791）：

```bash
python3 phone/app/tools/gen_graph_preview.py
```

它把同一套页面用当前 `assets/config.json` 在纯 JVM 上跑一遍、**内联**成自包含 HTML，
产物落 `debug/graph_preview.html`。⚠️ 产物**不入库**——快照每次现生成，入库就会过期，
又变成"图与实不符"。
