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
| 主页 | 启动 App | 状态总览 + 三项授权 + 服务/悬浮条控制 + 更新 + 各页入口 |
| 使用指引 | 主页「❓ 使用指引」 | Shizuku 下载直链、**6 步开启流程**、「点了没反应」速查表、adb 入口 |
| 编辑配置 | 主页「编辑配置」 | 改运行时参数（安全子集，带范围校验） |
| 判定路径图 | 主页「查看判定路径图」 | WebView 实时看走到哪一步、各判定点峰值 |
| 每日战绩 | 主页「每日战绩」 | GitHub 风格热力图 + 每日明细 + 口径说明 |

**每个按钮点了都会弹提示**（`Ui.act`）：说清"要做什么"；前置条件不满足时给**人话
原因**并尽量把你送到对应设置页（`Ui.gate`），不再静默丢弃。三类最容易"看着像坏了"
的情况现在都有话：

| 你点的 | 实际缺什么 | 提示 |
|---|---|---|
| 启动服务 | Shizuku 服务没跑 | "先去「使用指引」按步骤启动 Shizuku" |
| 启动服务 | 没给本 App Shizuku 授权 | "点主页「① 授权 Shizuku」允许一下" |
| 启动服务 | 授权了但通道没接上 | "稍等 2~3 秒再点" |
| 单独显示悬浮窗 | 没授"显示在应用上层" | 直接弹提示 + 跳设置页 |

## 跑起来

1. 装 APK（release `latest` 的 `sgmbot.apk`）；
2. 打开 App 依次授权：Shizuku → 悬浮窗 → **所有文件访问**（第三个不授也能跑，但
   `adb push` 改配置要写私有长路径，且 APK 自更新用不了，见「更新」）；
3. 点「启动服务」；悬浮条：开始／导航／停止／结束／◀折叠。
   悬浮条也可以**单独显示**（主页「单独显示悬浮窗」），不需要先起服务 —— 先摆好
   位置，想跑时点悬浮条上的「开始」。悬浮条是谁启的决定了服务退出时收不收它：
   用户单独开的会留着，服务开的会跟着服务一起收。

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
| `matcher` | `step` / `refine` | 2 / 2 | 粗搜步距 / 精定位邻域；卡就调大 step |
| `matcher` | `work_h` | 576 | 帧缩放基准高 |
| `matcher.pyramid` | `level/cost/relax/floor/top` | 2 / 2e8 / 0.3 / 0.5 / 16 | 全屏大模板的金字塔粗搜（见「匹配性能」） |
| `screen` | `roi_mode` | `abs` | 无 profile 命中时的 ROI 映射模式 |
| `screen.profiles` | `name/ar_min/ar_max/roi_mode` | 见文件 | 按宽高比落档选模式 |
| `run` | `max_minutes` | 0 | 单次最长时长（0=不限） |
| `run` | `pause_from` / `pause_to` | `""` | 暂停时段（支持跨夜，如 23:30~07:00） |
| `log` | `trace_size` | 200 | 判定流水环形缓冲容量 |
| `graph` | `layout/nodes/edges` | 见文件 | 判定路径图结构（见下） |
| `update` | `repo/bundle_asset/apk_asset/auto_check_h/allow_apk` | 见文件 | GitHub 更新（见下） |
| `shizuku` | `download_url/release_api/homepage/play_url` | 见文件 | 使用指引页的 Shizuku 链接（换版本不必发 APK） |
| `stats` | `weeks` / `levels` | 26 / `[1,3,6,11]` | 热力图默认周数 / 颜色分档上界 |
| `store` | `keep_days` | 400 | 战绩归档保留天数（≈13 个月），超期丢最旧 |
| `popup` | `guard` / `after_sec` / `every_sec` | true / 5 / 5 | 弹窗守卫：无动作超 5s 后每 5s 剥一次（见「弹窗处理」） |

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

两种看法：

- **手机上**：主页「查看判定路径图」。内置 WebView 开 `http://127.0.0.1:8791/`
  —— 服务只绑回环且由 App 进程自己起，同进程 WebView 直接可达，不用 adb。服务没起
  时页内能直接点「启动服务」。
- **PC 上**：`adb forward tcp:8791 tcp:8791` 后开 `http://127.0.0.1:8791/`。

页面每个节点画三行：**标签 / 最近实测 NCC 峰值 / 当前阈值**。阈值来自节点声明的
`cfg` 路径（`Graph.load` 时从 config 回填到 `val`），所以**改了 config 图跟着变**，
不会出现"图上写 0.72 / 实际是 0.75"。**点任意节点**，顶部黄条给出它的完整条件、
阈值来源路径与实测分数。右侧是最近判定流水。数据口 `GET /graph` 返回
`{nodes,edges,cursor,byNode,trace}`。

**引擎没跑也要能看图**：`GET /graph` 取数前会调 `Graph.ensureLoaded(cfg())` 做一次
**静态装载**（幂等）。此前 `Graph.load` 只在 BotService/SettleLoop 起跑时被调用 ⇒
没跑就没图，页面只剩「图还没装载」——那恰恰违背"判定图指的是整个程序的，不是运行到哪"。
配置的写入路径统一收口在 `BotService.reload()`，那里会 `Graph.invalidate()`，
于是改完配置下一次取图按新配置重建（不变量：**界面上印的阈值 = 当前 config**）。

⚠️ 页面全靠 JS 拉 `/graph` 再画 SVG ⇒ WebView **必须开 JavaScript**（默认是关的），
否则一片空白且不报错——那种"白屏"最容易误判成接口坏了。

⚠️ 这坨 HTML/JS 是 Java 字符串拼的，而浏览器对 `<script>` 是**原子解析**：一处语法错
= 全页失效，且手机上没控制台可看。所以硬规则是**只用单引号、不含任何反斜杠**
（Java 层不吞字符，浏览器侧也不会因转义炸掉整块）。`tools/GraphPageProbe.py` 兜底：
提取 → `node --check` 语法 → **用 stub DOM 真跑 `draw()`** 并断言阈值/条件/点击都生效。

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
  `popup.after_sec`（默认 5s），之后每 `popup.every_sec`（默认 5s）剥一次。
  用"无动作"当判据，是因为**弹窗必然挡住按钮 ⇒ 必然无动作**。
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

## GitHub 拉取更新

数据源 = repo 的 **latest release** 资产，CI 每次 push 自动发：

| 资产 | 内容 | 落点 |
|---|---|---|
| `bundle.zip` | `templates/*.png` + `config.json` | 解到 `Paths.root()`，热更（下轮生效，不必停引擎） |
| `sgmbot.apk` | 整包 | `pm install -r`（需 Shizuku 就绪 + **所有文件访问**） |

版本判据用资产的 `updated_at`（CI 是固定 tag `latest` 反复 `--clobber` 覆盖同名
资产，tag 恒不变，只有 `updated_at` 会动），本地水位记在 config 的
`meta.bundle_version` / `meta.apk_version`。

入口：主页「检查更新」「拉取更新」／广播 `CMD?action=check_update|update|
update_bundle`／HTTP `GET /update`、`POST /update?apk=0|1`。开跑时还会按
`update.auto_check_h` 后台自查（默认只热更 bundle，不自动装 APK）。

- bundle 里的 `config.json` **只补新键**，不覆盖本地手改；想强制覆盖就放
  `config_patch.json`。
- 未授所有文件访问时 shell 读不到 App 私有目录 ⇒ APK 跳过（bundle 照常热更）。

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

`tools/GraphProbe.py`（纯 JVM）管的是图**结构**：25 个静态节点 + escape 展开 6 项、
阈值从 config 回填、布局无重叠、边不悬空、运行时 `Graph.cursor` 锚点全在图上，
以及**静态装载 / 幂等 / 作废后重建**这三条 `ensureLoaded` 行为。
