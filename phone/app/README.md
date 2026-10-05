# SGM 挂机 App（原生 Android，脱离 AutoJs6）

目标：把 `phone/autojs/` 的挂机机器人做成独立 App —— 自有图标/入口、前台服务常驻、
开机自启、无需 AutoJs6 与悬浮窗脚本运行时；后续承载 角色资料库/扫仓库/按角色选人。

## 为什么走 pfwidget 同款构建链

`pfwidget/` 已验证：`vendor/android-build`（本地、git-ignored）提供 aapt2 + javac + dx +
apksigner + android-34 platform，**无 Gradle / 无 Android Studio**，`build.sh` 一键出签名 APK。
本项目沿用（Java 而非 Kotlin，minSdk 21 / targetSdk 34 同款）。

## 四条设计原则 (2026-10-05 用户拍板)

1. **可配置** — 引擎零硬编码: 全部阈值/ROI/坐标/链路步骤在 `config.json`
   (assets 内置默认 → `/sdcard/sgm_settle/config.json` 覆盖, adb push 即改即生效,
   无需重装 APK)。FIGHT_STEPS 的 taps/tpl/swipe/anchor 四种步骤类型即数据协议。
2. **可拓展** — 链路即数据: 导航链/战斗链/逃生链都是 config 里的步骤表, 新活动
   = 新配置文件 (新模板 + 新坐标), 引擎不改; Vision 匹配器按 `matcher` 字段分发,
   可加 OCR/新锚点类型。
3. **可更新** — APK 只装引擎; `bundle/` (templates + config + characters.json)
   走版本化更新: App 内检查 GitHub raw / TailShare 拉新包 → 校验 → 热加载。
   模板重裁不用发版。
4. **adb 可调试** — 三通道控制面:
   - 文件: `/sdcard/sgm_settle/logs/app.log` (轮转) + `state.json` (当前状态机/计数);
   - 广播: `adb shell am broadcast -a com.zhiyaunhe.sgmbot.CMD -e action start_nav|stop|reload`;
   - HTTP: 本地调试服务 (127.0.0.1:8791, `adb forward tcp:8791 tcp:8791` 后 PC 可
     GET /status /screencap, POST /reload /trigger) — PC 侧可完全接管调试。

## 架构

```
phone/app/
├── build.sh                  # aapt2 link + javac + dx + apksigner (抄 pfwidget/build.sh)
├── AndroidManifest.xml       # 前台服务 + 悬浮窗 + 开机自启, 不申请无障碍/录屏
└── src/com/zhiyaunhe/sgmbot/
    ├── MainActivity.java     # 权限引导(Shizuku/悬浮窗) + 状态页 + 「开始/导航/停止」
    ├── BotService.java       # 前台服务: 主循环线程 (settle/导航状态机), 常驻通知
    ├── ShizukuCtl.java       # Shizuku binder: screencap → Bitmap / input tap|swipe
    ├── Vision.java           # 纯 Java NCC 模板匹配 + 色锚点 (移植 phone/settle match.py 口径)
    ├── NavChain.java         # 移植 pf_nav: waitHall→EVENTS→目标卡→王关锚点→FIGHT→AUTO/3x
    ├── SettleLoop.java       # 移植 settle_bot: 两阶段状态机 + stall 逃生 + 自愈导航
    ├── OverlayBar.java       # 悬浮控制条 (WindowManager TYPE_APPLICATION_OVERLAY)
    ├── Config.java           # config.json 装载/热重载 (assets 默认 + /sdcard 覆盖)
    ├── DebugHttp.java        # 本地调试服务 (状态/截屏/重载/触发; adb forward 用)
    ├── CtlReceiver.java      # 广播控制入口 (start_nav/stop/reload)
    ├── BundleUpdater.java    # bundle 版本检查/拉取/校验/热加载 (GitHub raw / TailShare)
    ├── Store.java            # 今日场次 (对齐 pf_store: 日历天滚动/DAILY_CAP)
    ├── CharacterData.java    # 角色资料库: assets/characters.json, GitHub raw 拉取更新
    ├── RosterScan.java       # 扫仓库: 橱柜逐屏截图 → 头像模板/名字 OCR → owned 表
    └── TeamPick.java         # 按角色选人: 出战页图标匹配 → 精准拖拽 (pf_select GEOM 真机标定)
```

### 与 AutoJs6 版的能力对齐

| 能力 | AutoJs6 版 | App 版 |
|---|---|---|
| 截屏 | shizuku("screencap") + images.read | Shizuku shell screencap → Bitmap |
| 点击/滑动 | shizuku("input tap/swipe") | Shizuku shell input tap/swipe |
| 找图 | images.findImage (OpenCV) | Vision.java 纯 Java NCC (同模板 PNG, 同 1280×576 基准) |
| 悬浮条 | floaty XML | WindowManager overlay (同布局: 三键 + 三行统计) |
| 常驻 | AutoJs6 前台服务 | 自有前台服务 (通知栏状态, 可开机自启) |
| 模板 | templates/*.png | assets/templates/*.png (直接复用, 不重裁) |

阈值/坐标口径全部沿用：WORK_H=576、EV_PLAY_TH .78、TARGET_TH .75、SPD_TH .95、
MIN_SIM .72、王关✓锚点 (0xFF7AC241, tol 50, offset -72,+2)、地图右移 [960,288]→[320,288]。

## 里程碑

- **M1 通路**：build.sh 出包 + ShizukuCtl 截屏/点击 + OverlayBar — 手机上装上就能点。
- **M2 结算循环**：SettleLoop 移植（VICTORY/DEFEAT → CONTINUE/REMATCH，计数=REMATCH 驱动，
  stall 逃生: srv_retry/scene_x/modal_x/三槽按钮 → 3 轮无解重跑导航）。
- **M3 导航链**：NavChain 移植（目标卡 Pillow Talk、地图右移×2、绿✓锚点、FIGHT!、
  开场脑子+速度判档）。到这步功能即与 autojs 版打平。
- **M4 稳定性**：前台服务保活、开机自启、通知栏控制、store.json 兼容迁移。
- **M5 角色资料**：`data/sgm/characters.json`（本仓库维护，名字/图标/属性/出处），
  App 从 GitHub raw 拉取或随包更新；扫仓库 = 橱柜逐屏截图 + 名字区 OCR（ML Kit，离线）
  或头像小图 NCC → owned 表落盘。
- **M6 按角色精准选人**：出战页候选区图标 NCC 匹配目标角色 → 复用 pf_select 的
  planFixTeam 状态机 + 真机标定 GEOM（黄钉能量/拖拽落点，phone/autojs 5.4 待标定项）
  → 指定角色开打（如 Pillow Talk 的 REQUIRED FIGHTER）。

## 角色资料来源（M5 前置调研结论）

- SGM fandom wiki 有全角色页（名字/立绘/属性），可写一个 Python 脚本抓成
  `data/sgm/characters.json` + 头像 PNG 包，随本仓库 git 管理（脱敏口径同现有规则）。
- 头像小图同时充当扫仓库的匹配模板，一份数据两处用。

## 已知风险

- 纯 Java NCC 性能：1280×576 全帧 + 小模板实测 ~50-150ms/次（复用 autojs 版的小 ROI
  策略后够用）；不够再引入 OpenCV Android AAR（vendor 目录加 ~12MB）。
- Shizuku 仍是权限源头（无线调试激活），App 内做「Shizuku 未连接」引导页。
- HyperOS 杀后台：前台服务 + 省电白名单 + 自启动（用户侧一次性设置，App 内引导）。
