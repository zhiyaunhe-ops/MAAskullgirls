# phone/app Shizuku 集成 — 链路机制与三个坑（2026-10-06）

实机 K70 (HyperOS) + Shizuku manager 13.6.0 (r1086) × api-13.1.5 实测打通。
证据: commit `297db09` / `de6e379a` / `fffc7f34`；本文只留结论与排障命令。

## 现行链路（读 server 源码 BinderSender.java 实锤）

1. server（shell uid，由 manager 启动）挂 ProcessObserver / UidObserver；
2. **目标 app 进程启动或进前台** → server `sendBinder(uid, pid)`；
3. 前提：包声明了 `moe.shizuku.manager.permission.API_V23`，否则直接 continue；
4. binder 经 `ShizukuProvider.call(SEND_BINDER)` 送达 → `Shizuku.onBinderReceived` →
   attachApplication → server 回调 `bindApplication`（带上 permissionGranted）；
5. App 侧 1s 轮询 `serverUp && granted && !ready` → `tryBind` → `bindUserService`
   → shell uid 进程 `<pkg>:sgmbot` → `exec` 原语（截屏/点击）。

## 三个坑（同一链路，三种症状）

| # | 症状 | 根因 | 修法 | 证据 |
|---|------|------|------|------|
| 1 | 启动即闪退；删掉调用后变"永远未连接" | `Shizuku.java:50` 静态字段 `SHIZUKU_APPLICATION = new IShizukuApplication.Stub(){}` —— 缺 `moe.shizuku.server.*` 桩时触碰 Shizuku 任意静态成员即 NoClassDefFoundError；provider 的 `handleSendBinder` 首行 `pingBinder()` 同样崩 → binder 被静默丢弃 | 桩类按**源码**编入 `libs/aidl-src/`（Maven 的 aidl 构件只有 sources.jar，无 classes jar，直接拼 `.jar` URL 下载得到 404 页）+ build-ci.sh find 路径 | `297db09` |
| 2 | 修完 #1 仍"未连接" | Manifest 缺 `API_V23` uses-permission（见链路第 3 步） | Manifest 补权限 | `de6e379a` |
| 3 | 授权通过后 NPE `process name suffix must not be null` | `UserServiceArgs` 必须显式 `.processNameSuffix()`（决定 UserService 进程名 `<pkg>:<suffix>`） | `.processNameSuffix("sgmbot")` | `fffc7f34` |

## 排障命令

```bash
# server 活着吗（应为 shell uid）:      adb shell ps -A | grep shizuku
# binder 到没到（看本包进程的日志）:     adb shell logcat -d -s ShizukuProvider
#   - "binder received" = 送达; "sendBinder is called when already a living binder"
#     出现 = pingBinder 正常（坑 #1 修复后才会打印）
# App 侧绑定:                           adb shell logcat -d -s sgmbot  # 找 "UserService bound"
# UserService 进程（应为 shell uid）:    adb shell ps -A | grep sgmbot
```

## 行为须知

- **重装（PACKAGE_REPLACED）不触发推送** —— server 只在目标进程启动/进前台时推；
  重装后打开 App 即可。
- **别 `kill shizuku_server`** —— manager 不会自动重启，得在手机上重新点启动。
- 授权（"始终允许"）持久化在 server 侧，重装 App 不用重授。
- `/screencap` 在引擎未启动时返回 `capt fail` 属预期（`BotService.sh()==null`），
  不是 Shizuku 故障。
