# phone/app — 手机端挂机 App（Shizuku）

Android 原生 App：Shizuku 拿 shell uid 截屏/点击，NCC 模板匹配认结算页，跑与
`phone/autojs` 同口径的结算循环。无 Gradle，走 `aapt2 → javac → d8 → apksigner`
（`phone/app/build-ci.sh`），APK 由 GitHub Actions 产出（release tag `latest`）。

| 想做什么 | 去哪 |
|---|---|
| 装起来/跑起来 | 本文「跑起来」 |
| 改参数（扫描间隔/阈值/时长…） | 本文「可配置项」 |
| 看判定路径图 | 本文「判定路径图」 |
| 换分辨率/别的比例屏 | 本文「多分辨率」 |
| 拉模板/配置/新 APK | 本文「GitHub 拉取更新」 |
| Shizuku 连不上、截屏失败 | [phone-app-shizuku.md](phone-app-shizuku.md) |

## 跑起来

1. 装 APK（release `latest` 的 `sgmbot.apk`）；
2. 打开 App 依次授权：Shizuku → 悬浮窗 → **所有文件访问**（第三个不授也能跑，但
   `adb push` 改配置要写私有长路径，且 APK 自更新用不了，见「更新」）；
3. 点「启动服务」；悬浮条：开始／导航／停止／结束／◀折叠。

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

改配置不用 push 也行：

```bash
curl -X POST --data-binary @config.json http://127.0.0.1:8791/config
```

## 判定路径图

**节点和边全在 `config.graph` 里，代码零硬编码** —— 加一个判定点就是加一条
`nodes`，不必发版。循环每走到一个判定点就 `Graph.cursor(id)` + `Trace.put(...)`
（记模板/ROI/峰值/命中与否）。

```bash
adb forward tcp:8791 tcp:8791 && start http://127.0.0.1:8791/
```

页面：SVG 按 config 的 x/y 画节点与边（缺省 grid 自动排），**高亮当前节点**，
节点下方是该节点最近一次 NCC 峰值（差 0.02 卡住一眼可见），右侧是最近判定流水。
数据口 `GET /graph` 返回 `{nodes,edges,cursor,byNode,trace}`。

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
