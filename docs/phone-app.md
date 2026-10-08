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

页面：SVG 按 config 的 x/y 画节点与边（缺省 grid 自动排），**高亮当前节点**，
节点下方是该节点最近一次 NCC 峰值（差 0.02 卡住一眼可见），右侧是最近判定流水。
数据口 `GET /graph` 返回 `{nodes,edges,cursor,byNode,trace}`。

⚠️ 页面全靠 JS 拉 `/graph` 再画 SVG ⇒ WebView **必须开 JavaScript**（默认是关的），
否则一片空白且不报错——那种"白屏"最容易误判成接口坏了。

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
