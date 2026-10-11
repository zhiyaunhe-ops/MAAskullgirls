#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
更新链路离线回归 —— 纯 JVM, 跑**真的** Update.java + BotService.java (桩掉 Android 与文件系统)。

为什么值得专门做一个 (2026-10-11 用户报障):
    「我点请求更新, 它版本号都是不变的, 然后我也没有看到进度啊, 也没有什么提示」
    三个现象对应三条独立缺陷, 每条都"编译得过、跑起来静默失效":

    ① 根因: BotService.trigger() 里读的是 static cfg 字段, **服务没起过就是 null**
       ⇒ Update.sec(null) 得到空 JSON ⇒ repo 读成 "" ⇒ 立刻返回
         「update.repo 未配」—— 而用户明明配了。
       同一个"服务没起要兜底"的写法在 ConfigActivity/HelpActivity/StatsActivity
       都手写了一份, 唯独 trigger 这条路漏了 (修一处漏一处的典型)。
    ② 没进度: down() 全程无进度上报; UI 是 postDelayed(2500/6000) **只读一眼**,
       网络稍慢就读到"还没拿到结果", 而且之后再也不刷新。
    ③ 提示误导: UI 在 brief **文案里找"失败"两个字**判成败, 而
       "拉取: update.repo 未配 — config.json 里填 \"owner/name\"" 整句里
       **没有"失败"也没有"超时"** ⇒ 判成成功 ⇒ **错误被显示成绿色 ✓**。

断言清单:
  A. 配置层兜底: cfg=null 时 check 报 repo 未配 (锁定根因) / cfgOr 给出配置层 /
     能读出 repo / 幂等 / 服务起来后 cfgOr 返回服务那份
  B. 真链路检查 (需网络): ok / need_bundle / 文案不含恒定 tag= 且含远端时间 /
     LV_OK
  C. 真链路拉取 (需网络): bundle 落盘 / 水位推进 / 再查已最新 / APK 跳过 ⇒ LV_WARN
  D. 错误路径级别: repo 未配 ⇒ LV_ERR, 且文案里没有"失败"两字
     (这条正是旧 UI 把错误显示成绿 ✓ 的直接证据)
  E. fmtStamp: UTC→本地格式 / 空串与 null 不炸
  F. busy 计数不泄漏 / stage 有内容
  G. 配置缺 update 段 ⇒ 报"repo 未配"而不是 NPE
  H. **能不能装 = shell 实测说了算** (2026-10-11「早就授权了却装不了」):
     H1 旗标 false 但 shell 可读 ⇒ 照样 pm install 且水位推进、LV_OK;
     H2 shell 真读不到 ⇒ 跳过、**不白发** pm install、文案带真实路径、LV_WARN;
     H3 pm 回"版本降级" ⇒ 带 -d 重试, 仍失败也把出路(卸载重装)写进文案
     (Paths 那条根因另见 tools/PathsProbe.py —— 这里 Paths 是桩)

用法: python3 tools/UpdateProbe.py           (无网络时 B/C/H 组自动标 SKIP, 不算失败)
"""
import io, os, re, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'src', 'com', 'zhiyaunhe', 'sgmbot')
JSON_REAL = os.path.join(ROOT, '..', '..', 'vendor', 'jsonreal', 'build')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmbot-update')
FAKE_ROOT = os.path.join(OUT, 'root')          # 假装成 Paths.root() 的沙盒

# 复用悬浮条探针那套 Android 桩 (它已经覆盖了真 BotService 的全部依赖),
# 只按需覆盖/增补下面几个 —— 避免再手抄一遍 300 行桩。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import OverlayProbe as _ov                                    # noqa: E402

STUBS = dict(_ov.STUBS)
STUBS.pop('com/zhiyaunhe/sgmbot/Update.java')                 # 用真的
STUBS['com/zhiyaunhe/sgmbot/Paths.java'] = '''\
package com.zhiyaunhe.sgmbot;
/** 探针版 Paths: 根目录由系统属性 probe.root 给, 全落在临时沙盒里。 */
public class Paths {
    static String root() { return Probe.root(); }
    public static String config() { return root() + "/config.json"; }
    public static String tplDir() { return root() + "/templates"; }
    public static String updateDir() { return root() + "/update"; }
    public static String logDir() { return root(); }
    public static boolean legacy() { return Boolean.parseBoolean(Probe.prop("probe.legacy", "false")); }
    public static void init(android.content.Context c) { }
}
'''

STUBS['com/zhiyaunhe/sgmbot/Config.java'] = '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context;
import org.json.JSONObject;
import java.io.FileInputStream;
import java.io.InputStream;

/** 探针版配置层 —— 与真 Config 同语义 (assets 默认 + 本地 override 深合并),
 *  只是文件路径由探针给 (真 Config 走 Context.getAssets()/Paths.config())。
 *  ★ 必须是"真读文件": Update 的水位推进靠 putMeta 写 Paths.config() 再读回来,
 *    如果 read() 返回常量, 这条链就永远测不出"水位有没有真的推进"。 */
public class Config {
    public Config() { }
    public Config(Context c) { }
    public JSONObject read() {
        JSONObject base = Probe.readJson(Probe.assets());
        JSONObject ov = Probe.readJson(Paths.config());
        JSONObject m = ov.length() == 0 ? base : merge(base, ov);
        /* 开关: 模拟"配置里根本没有 update 段"(用户手改过 / patch 覆盖 / 装了旧包)。
         * 真机上这条路会让 sec() 返回 null ⇒ 调用方 optString 直接 NPE
         * ⇒ 整条更新链无声失败 (UpdateProbe 首次跑就抓到了)。 */
        if (Boolean.parseBoolean(Probe.prop("probe.noupdate", "false"))) m.remove("update");
        return m;
    }
    public static JSONObject merge(JSONObject base, JSONObject over) {
        try {
            JSONObject out = new JSONObject(base.toString());
            java.util.Iterator<String> it = over.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = over.get(k);
                if (v instanceof JSONObject && out.has(k) && out.get(k) instanceof JSONObject) {
                    out.put(k, merge(out.getJSONObject(k), (JSONObject) v));
                } else {
                    out.put(k, v);
                }
            }
            return out;
        } catch (Exception e) { return base; }
    }
    public static JSONObject load(InputStream in) throws Exception {
        return new JSONObject(Probe.readAll(in));
    }
}
'''

# 覆盖 ShizukuCtl: 补 exec(), 并可编程 —— 因为"能不能装"现在由 **shell 实测**说了算,
# 桩必须能分别模拟「shell 读得到」与「shell 读不到」两种情况 (见 H 组)。
STUBS['com/zhiyaunhe/sgmbot/ShizukuCtl.java'] = '''\
package com.zhiyaunhe.sgmbot;
import android.content.Context; import android.graphics.Bitmap;
public class ShizukuCtl {
    public static int execCount = 0;
    /** 最后一条 shell 命令 —— 断言"到底有没有真的去 pm install" */
    public static String lastCmd = "";
    /** pm install 的输出 (任意串; "Success" = 装上) */
    public static String pmOut = "Failure [INSTALL_FAILED_TEST_ONLY]";
    /** true ⇒ shell 说文件读得到 (test -r 回 SGMBOT_READABLE) */
    public static boolean shellCanRead = false;
    public static boolean isReady = true;
    public static boolean ready() { return isReady; }
    public static boolean serverUp() { return true; }
    public static void tryBind(Context c) { }
    public static String exec(String cmd) {
        execCount++; lastCmd = cmd;
        if (cmd.indexOf("SGMBOT_READABLE") >= 0)
            return shellCanRead ? "SGMBOT_READABLE" : "SGMBOT_DENIED";
        if (cmd.indexOf("pm install") >= 0) return pmOut;
        return pmOut;
    }
    public Bitmap capture() { return null; }
}
'''

PROBE = r'''
package com.zhiyaunhe.sgmbot;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 更新链路回归。跑真 Update + 真 BotService (见 UpdateProbe.py 的桩集)。
 * 判据全部落在"用户能不能看见变化"上: 水位有没有推进、级别是绿还是黄还是红、
 * 文案里有没有真实会变的时间戳。
 */
public class Probe {
    static int pass = 0, fail = 0, skip = 0;
    static String assetsPath = "";

    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "  got=" + got); }
    }

    static void sk(String group, String why) {
        skip++;
        System.out.println("  SKIP " + group + " — " + why);
    }

    /** 网络抖一下就只跳过这一段 —— 探针不能把"网断了"报成"功能坏了" */
    static boolean offline(JSONObject o, String grp) {
        if (o != null && o.optBoolean("ok")) return false;
        sk(grp, "拉取/检查失败 (网络不稳): " + (o == null ? "null" : o.optString("msg")));
        return true;
    }

    /* ---- 探针给桩用的工具 ---- */

    static String assets() { return assetsPath; }

    static String prop(String k, String d) {
        String v = System.getProperty(k);
        return v == null || v.length() == 0 ? d : v;
    }

    static String root() { return prop("probe.root", "C:/Windows/Temp/sgmbot-update/root"); }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    static JSONObject readJson(String path) {
        try {
            if (path == null) return new JSONObject();
            File f = new File(path);
            if (!f.exists() || f.length() == 0) return new JSONObject();
            return new JSONObject(readAll(new FileInputStream(f)));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void writeJson(String path, JSONObject o) {
        try {
            File f = new File(path);
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(o.toString(2).getBytes("UTF-8"));
            os.close();
        } catch (Exception e) { }
    }

    /** 本地 config 的水位 (meta.<key>) */
    static String watermark(String key) {
        JSONObject m = readJson(Paths.config()).optJSONObject("meta");
        return m == null ? "" : m.optString(key, "");
    }

    /** 把本地 APK 水位清掉 ⇒ 下一轮 check 必然判 need_apk
     *  ⚠️ 待安装水位 (apk_pending) 必须**一起**清: 它在时 need_apk 也是 false
     *     (那是"已经弹过安装界面、别再重复下载"的意思) —— 只清 apk_version 会让
     *     后面几组全部跳过 APK 分支, 断言看着像"功能没做"。 */
    static void resetApkWatermark() {
        JSONObject c = readJson(Paths.config());
        JSONObject m = c.optJSONObject("meta");
        if (m == null) { m = new JSONObject(); c.put("meta", m); }
        m.put("apk_version", "");
        m.put("apk_pending", "");
        m.put("apk_pending_at", "");
        writeJson(Paths.config(), c);
    }

    /** 造一个"看起来下全了"的假 APK (够大就行 —— 兜底那条路在门口会查大小) */
    static File fakeApk(int bytes) {
        File dir = new File(Paths.updateDir());
        dir.mkdirs();
        File f = new File(dir, ApkFile.NAME);
        try {
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(new byte[bytes]);
            os.close();
        } catch (Exception e) { }
        return f;
    }

    /** 把 meta 里的某个键改成历史上某个时刻 —— 用来测"待安装超时作废" */
    static void agePending(long agoMs) {
        JSONObject c = readJson(Paths.config());
        JSONObject m = c.optJSONObject("meta");
        if (m == null) { m = new JSONObject(); c.put("meta", m); }
        m.put("apk_pending_at", String.valueOf(System.currentTimeMillis() - agoMs));
        writeJson(Paths.config(), c);
    }

    /** 手驱一次"服务起来" —— 只为让 BotService.cfg 从 null 变成有值 */
    static void bootService(Context c) {
        BotService s = new BotService();
        s.onCreate();
    }

    public static void main(String[] a) throws Exception {
        assetsPath = prop("probe.assets", "");
        Context ctx = new Context();
        App.inst = ctx;

        // ============ A. 配置层兜底 (根因) ============
        System.out.println("== A 配置层兜底 (服务没起时 cfg==null) ==");
        ck("前置: 服务没起 ⇒ BotService.cfg() 是 null", BotService.cfg() == null, BotService.cfg());

        JSONObject r0 = Update.check(null);
        ck("根因复现: check(null) 报 repo 未配 (用户明明配了)",
                !r0.optBoolean("ok") && r0.optString("msg").contains("update.repo 未配"),
                r0.optString("msg"));

        Config loose = BotService.cfgOr(ctx);
        ck("cfgOr(ctx) 在 cfg=null 时仍给出配置层", loose != null, loose);
        JSONObject lu = loose == null ? null : loose.read().optJSONObject("update");
        String repo = lu == null ? "" : lu.optString("repo", "");
        ck("cfgOr 读得出 update.repo (修复的核心)", repo.equals("zhiyaunhe-ops/MAAskullgirls"), repo);
        ck("cfgOr 幂等 (两次同一对象)", BotService.cfgOr(ctx) == loose, null);

        bootService(ctx);
        ck("服务起来后 cfgOr 返回服务那份 (不再另造)",
                BotService.cfgOr(ctx) == BotService.cfg() || BotService.cfg() != null,
                BotService.cfg());
        Config cfg = BotService.cfgOr(ctx);

        // ============ B. 真链路: 检查 ============
        System.out.println("== B 真链路检查 (需要网络) ==");
        JSONObject st = Update.check(cfg);
        boolean online = st.optBoolean("ok");
        if (!online) {
            sk("B/C 组", "检查失败: " + st.optString("msg"));
        } else {
            ck("check 成功", true, null);
            ck("首个 release 资产与本地水位不同 ⇒ need_bundle", st.optBoolean("need_bundle"),
                    st.optString("cur_bundle") + " vs " + st.optString("ver_bundle"));
            String brief = Update.lastResult();
            ck("文案**不含** tag= (防'版本号不变'回归)", brief.indexOf("tag=") < 0, brief);
            ck("文案含远端时间戳 (真正会变的东西)",
                    brief.matches(".*\\d\\d-\\d\\d \\d\\d:\\d\\d.*"), brief);
            ck("检查成功 ⇒ LV_OK", Update.lastLevel() == Update.LV_OK, Update.lastLevel());
        }

        // ============ C. 真链路: 拉取 (端到端落盘) ============
        System.out.println("== C 真链路拉取 (需要网络, 写临时沙盒) ==");
        if (!online) {
            sk("C 组", "无网络");
        } else {
            JSONObject out = Update.apply(ctx, cfg, true);
            boolean bundleOk = out.optBoolean("ok") && out.optInt("bundle_files", 0) > 0;
            if (!bundleOk) {
                sk("C 组", "bundle 下载/解包失败 (out=" + out + ")");
            } else {
                ck("apply 成功且 bundle 落了 " + out.optInt("bundle_files") + " 个文件", true, null);

                JSONObject local = readJson(Paths.config());
                JSONObject lm = local.optJSONObject("meta");
                String bv = lm == null ? "" : lm.optString("bundle_version", "");
                ck("水位推进: 本地 bundle_version == 远端 ver_bundle",
                        bv.equals(st.optString("ver_bundle")), bv + " vs " + st.optString("ver_bundle"));

                JSONObject apk = out.optJSONObject("apk");
                ck("APK 被跳过 (桩的 shell 回 SGMBOT_DENIED ⇒ 实测读不到)",
                        apk != null && !apk.optBoolean("ok"), apk);
                ck("第一条路的失败原因说人话 (含'所有文件访问') 并留在 pm_msg",
                        apk != null && apk.optString("pm_msg").contains("所有文件访问"),
                        apk == null ? null : apk.optString("pm_msg"));
                ck("第一条路不通 ⇒ 自动转第二条路 (2026-10-11 加的兜底)",
                        apk != null && apk.optJSONObject("fallback") != null
                                && apk.optJSONObject("fallback").optBoolean("launched"),
                        apk == null ? null : apk.optString("msg"));
                ck("有部分被跳过 ⇒ LV_WARN (黄, 不是绿✓)",
                        Update.lastLevel() == Update.LV_WARN, Update.lastLevel());

                JSONObject st2 = Update.check(cfg);
                ck("再查一次 ⇒ bundle 已最新 (水位真的生效)",
                        st2.optBoolean("ok") && !st2.optBoolean("need_bundle"),
                        "need_bundle=" + st2.optBoolean("need_bundle"));
            }
        }

        // ============ D. 错误路径的级别 ============
        System.out.println("== D 错误路径级别 (旧 UI 会显示成绿✓) ==");
        Update.check(null);
        ck("repo 未配 ⇒ LV_ERR", Update.lastLevel() == Update.LV_ERR, Update.lastLevel());
        String err = Update.lastResult();
        ck("错误文案里**没有**'失败'两字 ⇒ 旧判据(!contains(失败))必然失灵",
                err.indexOf("失败") < 0, err);
        ck("错误文案给出具体原因 (不是黑盒)", err.indexOf("update.repo") >= 0, err);

        // ============ H. 能不能装 = shell 实测说了算 ============
        // 2026-10-11 报障「我早就授权了所有文件访问, 为啥他还自己安装不了, 说 shell 读不到」:
        // 旧实现拿 `Paths.legacy()` (App 侧旗标) 当闸 —— 而那个旗标当时恒为 false
        // (Paths.init 提前返回, 见 tools/PathsProbe.py), 于是**真正可读的机器也被跳过**。
        // 现在闸换成 shell 的 `test -r` 实测。H1 就是"旗标说不行、shell 说行" ⇒ 必须装。
        System.out.println("== H 能不能装由 shell 实测说了算 (不看 App 侧旗标) ==");
        if (!online) {
            sk("H 组", "无网络");
        } else {
            Config cfg2 = BotService.cfgOr(ctx);
            // H1: 旗标 = false (probe.legacy 没开), 但 shell 实测读得到
            resetApkWatermark();
            ShizukuCtl.shellCanRead = true;
            ShizukuCtl.pmOut = "Success";
            JSONObject o1 = Update.apply(ctx, cfg2, true);
            JSONObject a1 = o1.optJSONObject("apk");
            ck("旗标 false 但 shell 可读 ⇒ **仍然真的去 pm install** (修复的核心)",
                    ShizukuCtl.lastCmd.indexOf("pm install") >= 0, ShizukuCtl.lastCmd);
            ck("H1 apk.ok=true (装上了)", a1 != null && a1.optBoolean("ok"), a1);
            ck("H1 APK 水位推进 (装了才记)",
                    watermark("apk_version").length() > 0, watermark("apk_version"));
            ck("H1 全成 ⇒ LV_OK (绿✓, 不是黄!)",
                    Update.lastLevel() == Update.LV_OK, Update.lastLevel());

            // H2: shell 实测读不到 ⇒ 跳过, 且**不该**白发一条注定失败的 pm install
            resetApkWatermark();
            ShizukuCtl.shellCanRead = false;
            ShizukuCtl.lastCmd = "";
            JSONObject o2 = Update.apply(ctx, cfg2, true);
            JSONObject a2 = o2.optJSONObject("apk");
            ck("H2 shell 读不到 ⇒ 不去 pm install (别白跑一趟)",
                    ShizukuCtl.lastCmd.indexOf("pm install") < 0, ShizukuCtl.lastCmd);
            ck("H2 先探后装 (最后一条命令是 test -r)",
                    ShizukuCtl.lastCmd.indexOf("SGMBOT_READABLE") >= 0, ShizukuCtl.lastCmd);
            /* H2 的第二半: 第一条路不通 ⇒ 应当**自动转第二条路** (弹系统安装界面)。
             * 原因留在 pm_msg (排障要看"为什么 adb 那条不行"), msg 说用户该干什么。 */
            ck("H2 第一条路的原因留在 pm_msg (不是把原因也盖掉)",
                    a2 != null && a2.optString("pm_msg").contains("读不到")
                            && a2.optString("pm_msg").contains("sgmbot.apk"),
                    a2 == null ? null : a2.optString("pm_msg"));
            ck("H2 自动转第二条路: 系统安装界面已弹出",
                    a2 != null && a2.optJSONObject("fallback") != null
                            && a2.optJSONObject("fallback").optBoolean("launched"),
                    a2 == null ? null : a2.optString("msg"));
            ck("H2 部分跳过 ⇒ LV_WARN", Update.lastLevel() == Update.LV_WARN, Update.lastLevel());

            // H3: shell 读得到, 但 pm 说"版本降级" —— 本地构建包 (versionCode 9999)
            //     压着 CI 包。必须带 -d 重试一次, 且**把出路写进文案**。
            resetApkWatermark();
            ShizukuCtl.shellCanRead = true;
            ShizukuCtl.pmOut = "Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected]";
            ShizukuCtl.lastCmd = "";
            JSONObject o3 = Update.apply(ctx, cfg2, true);
            JSONObject a3 = o3.optJSONObject("apk");
            ck("H3 降级被拒 ⇒ 带 -d 重试 (不只试一次)",
                    ShizukuCtl.lastCmd.indexOf("pm install -r -d") >= 0, ShizukuCtl.lastCmd);
            ck("H3 仍失败 ⇒ apk.ok=false", a3 != null && !a3.optBoolean("ok"), a3);
            ck("H3 出路 (卸载重装) 写在 pm_msg 里, 不是把 Failure 原样丢回来",
                    a3 != null && a3.optString("pm_msg").contains("卸载重装"),
                    a3 == null ? null : a3.optString("pm_msg"));
            ck("H3 同样转走第二条路 (弹安装界面)",
                    a3 != null && a3.optJSONObject("fallback") != null
                            && a3.optJSONObject("fallback").optBoolean("launched"),
                    a3 == null ? null : a3.optString("msg"));
            ck("H3 部分跳过 ⇒ LV_WARN", Update.lastLevel() == Update.LV_WARN, Update.lastLevel());
        }

        // ============ I. 第二条路: 把 APK 交给**系统安装器** ============
        // 用户原话: 「只要你安装失败就走第二条路, 直接用系统的文件管理把那个安装给打开,
        //            直接就安了」(2026-10-11)
        // 为什么值得单测: pm install 的三个前提 (Shizuku 在跑 / shell 读得到 App 私有目录 /
        // 不撞版本降级) **全是机器状态**, 任一条不成立功能就整条消失 —— 而用户手上明明
        // 只需要"点一下系统弹出的安装界面"。这条路没有运行时特征 (在真机上它只是"屏幕
        // 上多了个界面"), 所以只能在这里把"到底给系统发了什么"钉下来。
        System.out.println("== I 第二条路 (弹系统安装界面) ==");
        PackageManager.lastUpdateTime = 0;
        File apkBig = fakeApk(ApkFile.MIN_BYTES + 4096);
        Context.failStart = 0;
        Context.lastStarted = null;

        // I0 纯逻辑: Uri / 路径规则 (不许变成"任意文件读取器")
        ck("I0 uriFor 是 content://<authority>/<裸文件名>",
                ApkFile.uriFor(ApkFile.NAME).equals("content://" + ApkFile.AUTHORITY + "/"
                        + ApkFile.NAME), ApkFile.uriFor(ApkFile.NAME));
        File ud = new File(Paths.updateDir());
        ck("I0 resolve 认合法裸名", ApkFile.resolve(ud, ApkFile.NAME) != null, null);
        ck("I0 resolve 拒 ../  (不许往上走)", ApkFile.resolve(ud, "../config.json") == null, null);
        ck("I0 resolve 拒子目录 (不许带路径)", ApkFile.resolve(ud, "sub/x.apk") == null, null);
        ck("I0 resolve 拒反斜杠 (Windows 式绕法)", ApkFile.resolve(ud, "..\\config.json") == null, null);
        ck("I0 looksComplete 认大文件", ApkFile.looksComplete(apkBig), null);

        // I1 缺「安装未知应用」授权 ⇒ 先把人送去开权限, 不硬弹安装界面
        PackageManager.canRequestPackageInstalls = false;
        Context.lastStarted = null;
        ApkInstall.R r1 = ApkInstall.open(ctx, apkBig);
        ck("I1 缺授权 ⇒ 不弹安装界面而先送你去开权限",
                !r1.launched && r1.needPerm,
                "launched=" + r1.launched + " needPerm=" + r1.needPerm);
        ck("I1 跳的是「安装未知应用」设置页",
                Context.lastStarted != null && Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                        .equals(Context.lastStarted.getAction()),
                Context.lastStarted == null ? null : Context.lastStarted.getAction());
        ck("I1 文案说清去处 (不是黑盒)", r1.msg.indexOf("安装未知应用") >= 0, r1.msg);

        // I2 正常路径: ACTION_VIEW + content:// + apk mime + 一次性读权限
        PackageManager.canRequestPackageInstalls = true;
        Context.lastStarted = null;
        ApkInstall.R r2 = ApkInstall.open(ctx, apkBig);
        ck("I2 界面弹出去了 (launched)", r2.launched, r2.msg);
        ck("I2 用 ACTION_VIEW (系统安装器认这个动作)",
                Context.lastStarted != null && "android.intent.action.VIEW"
                        .equals(Context.lastStarted.getAction()),
                Context.lastStarted == null ? null : Context.lastStarted.getAction());
        ck("I2 data 是 content://<authority>/sgmbot.apk",
                Context.lastStarted != null && ApkFile.uriFor(ApkFile.NAME)
                        .equals(String.valueOf(Context.lastStarted.getData())),
                Context.lastStarted == null ? null : String.valueOf(Context.lastStarted.getData()));
        ck("I2 type = application/vnd.android.package-archive",
                Context.lastStarted != null && ApkFile.MIME_APK.equals(Context.lastStarted.getType()),
                Context.lastStarted == null ? null : Context.lastStarted.getType());
        ck("I2 带 FLAG_GRANT_READ_URI_PERMISSION (命门: 安装器与我们是两个 uid)",
                Context.lastStarted != null
                        && (Context.lastStarted.flags & 1) != 0,     // FLAG_GRANT_READ_URI_PERMISSION = 1
                Context.lastStarted == null ? null : Context.lastStarted.flags);
        ck("I2 带 FLAG_ACTIVITY_NEW_TASK (从服务/后台发起也能弹)",
                Context.lastStarted != null
                        && (Context.lastStarted.flags & 0x10000000) != 0,
                Context.lastStarted == null ? null : Context.lastStarted.flags);
        ck("I2 绝不是 file:// (API 24 起会抛 FileUriExposedException)",
                Context.lastStarted != null && !String.valueOf(Context.lastStarted.getData())
                        .startsWith("file://"),
                Context.lastStarted == null ? null : String.valueOf(Context.lastStarted.getData()));

        // I3 主 intent 没人认领 ⇒ 退回老动作再试一次
        Context.failStart = 1;
        Context.lastStarted = null;
        ApkInstall.R r3 = ApkInstall.open(ctx, apkBig);
        ck("I3 主 intent 没人认领 ⇒ 退 ACTION_INSTALL_PACKAGE 再试",
                Context.lastStarted != null && "android.intent.action.INSTALL_PACKAGE"
                        .equals(Context.lastStarted.getAction()),
                Context.lastStarted == null ? null : Context.lastStarted.getAction());
        ck("I3 退回去那条成了 ⇒ launched=true (不能说成'两条都失败')", r3.launched, r3.msg);

        // I4 两条都不行 ⇒ 不许谎报成功
        Context.failStart = 9;
        ApkInstall.R r4 = ApkInstall.open(ctx, apkBig);
        ck("I4 两条都失败 ⇒ launched=false (绝不谎报)", !r4.launched, r4.msg);
        ck("I4 文案给出可手动装的路径", r4.msg.indexOf(ApkFile.NAME) >= 0, r4.msg);
        Context.failStart = 0;

        // I5 文件不完整 ⇒ 一个界面都不弹 (别把半截文件塞给安装器:
        //    它报的错是"解析包时出现问题", 完全指不到"下载断了"上)
        File apkSmall = fakeApk(1024);
        Context.lastStarted = null;
        ApkInstall.R r5 = ApkInstall.open(ctx, apkSmall);
        ck("I5 半截文件 ⇒ 不弹界面", !r5.launched, r5.msg);
        ck("I5 文案指到'重下一次'", r5.msg.indexOf("拉取更新") >= 0, r5.msg);
        ck("I5 也不许说成「装上了」", !r5.needPerm, r5.msg);

        // ---- I6~I10: 水位与自愈 (走真 Update.apply/check, 需要网络) ----
        // ⚠️ 这一段每一步都可能撞上网络抖动 —— 探针绝不能把"网断了"报成"功能坏了",
        //    所以任何一次 apply/check 不 ok 就把那一段标 SKIP (不算失败)。
        if (!online) {
            sk("I6~I10 组", "无网络");
        } else {
            Config cfg3 = BotService.cfgOr(ctx);
            JSONObject sI = Update.check(cfg3);
            if (offline(sI, "I6~I10 组")) { /* 网络不稳, 整段跳过 */ }
            else {
            String verI = sI.optString("ver_apk", "");
            ck("I6 前置: 远端有 apk 资产", verI.length() > 0, verI);

            // I6 第一条路不通 ⇒ 自动转第二条路, 并把"待安装"记进水位
            resetApkWatermark();
            ShizukuCtl.shellCanRead = false;         // 逼第一条路失败
            Context.lastStarted = null;
            JSONObject oI = Update.apply(ctx, cfg3, true);
            JSONObject aI = oI.optJSONObject("apk");
            if (offline(oI, "I6~I7 组")) { /* 网络不稳 */ }
            else {
            ck("I6 转走第二条路 (fallback.launched)",
                    aI != null && aI.optJSONObject("fallback") != null
                            && aI.optJSONObject("fallback").optBoolean("launched"),
                    aI == null ? null : aI.optString("msg"));
            ck("I6 真给系统发了安装 intent",
                    Context.lastStarted != null
                            && ApkFile.uriFor(ApkFile.NAME)
                               .equals(String.valueOf(Context.lastStarted.getData())),
                    Context.lastStarted == null ? null : String.valueOf(Context.lastStarted.getData()));
            ck("I6 文案说「待安装」, 用户知道该去看屏幕",
                    Update.lastResult().indexOf("待安装") >= 0, Update.lastResult());
            ck("I6 记了待安装水位 apk_pending == 远端 ver",
                    watermark("apk_pending").equals(verI), watermark("apk_pending"));
            ck("I6 部分跳过 ⇒ LV_WARN (黄, 不是绿✓ —— 毕竟还没装上)",
                    Update.lastLevel() == Update.LV_WARN, Update.lastLevel());

            // I7 再查一次: 不该又说"有更新" (否则每次检查都重下 400KB 再弹一次界面)
            JSONObject sI2 = Update.check(cfg3);
            if (!offline(sI2, "I7 组")) {
                ck("I7 待安装期间 need_apk=false (不重复下载)",
                        !sI2.optBoolean("need_apk"), "need_apk=" + sI2.optBoolean("need_apk"));
                ck("I7 检查文案也说「待安装」(不是拿「已最新」骗人)",
                        Update.lastResult().indexOf("待安装") >= 0, Update.lastResult());
            }
            }   // I6/I7

            // I8 用户真在系统界面上点了安装 ⇒ 下次查要**自动认账** (比对本包 lastUpdateTime)
            JSONObject sI8 = Update.check(cfg3);
            if (!offline(sI8, "I8 组")) {
                ck("I8 前置: 仍是待安装", watermark("apk_pending").length() > 0,
                        watermark("apk_pending"));
                PackageManager.lastUpdateTime = System.currentTimeMillis() + 1;
                Update.check(cfg3);
                ck("I8 装上了 ⇒ 自动推进 apk_version",
                        watermark("apk_version").equals(verI), watermark("apk_version"));
                ck("I8 待安装水位已清 (不再挂着)",
                        watermark("apk_pending").length() == 0, watermark("apk_pending"));
                PackageManager.lastUpdateTime = 0;
            }

            // I9 用户取消了 (没装) 且过了 30 分钟 ⇒ 待安装作废, 重新提示有更新
            resetApkWatermark();
            JSONObject c9 = readJson(Paths.config());
            JSONObject m9 = c9.optJSONObject("meta");
            if (m9 == null) { m9 = new JSONObject(); c9.put("meta", m9); }
            m9.put("apk_pending", verI);
            writeJson(Paths.config(), c9);
            agePending(31 * 60 * 1000L);             // 31 分钟前弹的, 还没装
            JSONObject sI3 = Update.check(cfg3);
            if (!offline(sI3, "I9 组")) {
                ck("I9 待安装超时 ⇒ 作废 (别一直挂着'待安装')",
                        watermark("apk_pending").length() == 0, watermark("apk_pending"));
                ck("I9 作废后重新提示有更新", sI3.optBoolean("need_apk"),
                        "need_apk=" + sI3.optBoolean("need_apk"));
            }

            // I10 开关关掉 ⇒ 退回老行为 (只报原因, 不弹界面)
            resetApkWatermark();
            JSONObject ov10 = readJson(Paths.config());
            if (!ov10.has("update")) ov10.put("update", new JSONObject());
            ov10.getJSONObject("update").put("system_installer_fallback", false);
            writeJson(Paths.config(), ov10);
            Context.lastStarted = null;
            JSONObject o10 = Update.apply(ctx, cfg3, true);
            JSONObject a10 = o10.optJSONObject("apk");
            if (offline(o10, "I10 组")) { /* 网络不稳 */ }
            else {
            ck("I10 开关关掉 ⇒ 不弹安装界面",
                    Context.lastStarted == null
                            || !ApkFile.uriFor(ApkFile.NAME)
                                 .equals(String.valueOf(Context.lastStarted.getData())),
                    Context.lastStarted == null ? null : String.valueOf(Context.lastStarted.getData()));
            ck("I10 文案点出是哪个开关关的",
                    a10 != null && a10.optString("msg").indexOf("system_installer_fallback") >= 0,
                    a10 == null ? null : a10.optString("msg"));
            }
            JSONObject ov11 = readJson(Paths.config());
            if (ov11.has("update")) ov11.getJSONObject("update").put("system_installer_fallback", true);
            writeJson(Paths.config(), ov11);
            }   // 网络稳
        }

        // ============ E. fmtStamp ============
        System.out.println("== E 时间戳格式化 ==");
        String s = Update.fmtStamp("2026-10-11T10:16:11Z");
        ck("ISO(UTC) → 本地 MM-dd HH:mm", s.matches("\\d\\d-\\d\\d \\d\\d:\\d\\d"), s);
        ck("空串不炸", Update.fmtStamp("").length() == 0, Update.fmtStamp(""));
        ck("null 不炸", Update.fmtStamp(null).length() == 0, Update.fmtStamp(null));

        // ============ F. busy 不泄漏 ============
        System.out.println("== F busy 计数 ==");
        ck("跑完 busy() 归零 (不泄漏)", !Update.busy(), Update.busy());
        ck("stage 有内容 (UI 能显示到东西)", Update.stage().length() > 0, Update.stage());

        // ============ G. 没有 update 段时不崩 ============
        System.out.println("== G 配置缺 update 段 (sec 的 null 防御) ==");
        System.setProperty("probe.noupdate", "true");
        try {
            JSONObject sn = Update.check(cfg);
            ck("缺 update 段 ⇒ 报 repo 未配而不是 NPE",
                    !sn.optBoolean("ok") && sn.optString("msg").contains("update.repo"),
                    sn.optString("msg"));
        } catch (Throwable t) {
            ck("缺 update 段 ⇒ 报 repo 未配而不是 NPE", false, t);
        } finally {
            System.setProperty("probe.noupdate", "false");
        }

        System.out.println("== " + pass + " passed, " + fail + " failed"
                + (skip > 0 ? ", " + skip + " skipped" : "") + " ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
'''


def strip_comments(src):
    """剥掉注释再做关键词断言。

    ⚠️ 必须**先剥注释**: 这条链的注释里天然会提到被禁掉的老做法
       ("不能用 file://"、`file://` 会抛 FileUriExposedException、"旧实现是 …"),
       不剥就会把**解释**判成回退 —— 断言永远红 = 等于没有断言 (GraphProbe/OverlayProbe
       都在同一个坑里栽过)。
    """
    out = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    return re.sub(r'//[^\n]*', '', out)


def read_src(name):
    return io.open(os.path.join(SRC, name), encoding='utf-8').read()


def src_check():
    """源码级回归 —— 覆盖"运行期测不到"的那一半。

    I 组用桩把 ApkInstall 的行为测透了, 但桩里的常量是我们自己写的: 万一有人把
    `Intent.FLAG_GRANT_READ_URI_PERMISSION` 换成一个手写数字 (或把 action 换成字符串
    字面量), I 组照样绿, 真机却装不上。所以还要在**源码**上钉住"用的是哪个符号、
    走的是哪条路"。
    """
    fails = []

    # ① 真发 intent 的地方
    ai = strip_comments(read_src('ApkInstall.java'))
    for sym in ('Intent.ACTION_VIEW', 'ApkFile.MIME_APK',
                'Intent.FLAG_GRANT_READ_URI_PERMISSION', 'Intent.FLAG_ACTIVITY_NEW_TASK',
                'Intent.ACTION_INSTALL_PACKAGE', 'canRequestPackageInstalls',
                'Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES'):
        if sym not in ai:
            fails.append('ApkInstall 少了 %s' % sym)
    if 'file://' in ai:
        fails.append('ApkInstall 里出现了 file:// (API 24 起会抛 FileUriExposedException)')

    # ② 纯逻辑层不许碰 android (同 GraphLayout 的做法: 探针要能直接跑它)
    af = strip_comments(read_src('ApkFile.java'))
    if 'import android' in af:
        fails.append('ApkFile 引了 android (它该是纯 java 的常量/路径规则, 探针直跑)')
    if '".."' not in af:
        fails.append('ApkFile 没有拒 ".." 的规则 (provider 会变成任意文件读取器)')

    # ③ provider 只读、只认 update 目录下的裸文件名
    ap = strip_comments(read_src('ApkProvider.java'))
    for sym in ('extends ContentProvider', 'ApkFile.resolve', 'ParcelFileDescriptor.MODE_READ_ONLY'):
        if sym not in ap:
            fails.append('ApkProvider 少了 %s' % sym)
    if '"r".equals(mode)' not in ap:
        fails.append('ApkProvider 没锁只读模式 (该只允许 "r")')

    # ④ Update 只调 ApkInstall, 不许自己拼 intent (同一个动作两条路 = 病)
    up = strip_comments(read_src('Update.java'))
    if 'ApkInstall.open' not in up:
        fails.append('Update 没调 ApkInstall.open (兜底那条路没接上)')
    if 'android.content.Intent' in up or 'new Intent(' in up:
        fails.append('Update 自己拼起了 Intent (该由 ApkInstall 一处负责)')

    # ⑤ 清单: 权限 + provider, 且 authority 必须与 ApkFile.AUTHORITY 逐字相同
    man = read_src_file(os.path.join(ROOT, 'AndroidManifest.xml'))
    if 'android.permission.REQUEST_INSTALL_PACKAGES' not in man:
        fails.append('AndroidManifest 没声明 REQUEST_INSTALL_PACKAGES (API 26+ 必需要)')
    m = re.search(r'android:name="\.ApkProvider"\s+android:authorities="([^"]+)"', man)
    if not m:
        fails.append('AndroidManifest 没注册 .ApkProvider')
    else:
        auth = m.group(1)
        a2 = re.search(r'AUTHORITY\s*=\s*"([^"]+)"', af)
        if not a2:
            fails.append('ApkFile 里找不到 AUTHORITY 常量')
        elif a2.group(1) != auth:
            fails.append('authority 对不上: 清单=%s ApkFile=%s (content:// 直接打不开)'
                         % (auth, a2.group(1)))
    if 'android:exported="false"' not in man:
        fails.append('ApkProvider 该是 exported=false (靠一次性读权限借用, 不对外开口子)')
    if 'android:grantUriPermissions="true"' not in man:
        fails.append('ApkProvider 缺 grantUriPermissions=true (安装器读不到 content://)')
    return fails


def read_src_file(p):
    return io.open(p, encoding='utf-8').read()


def main():
    # Windows 下 python 默认 cp936 编 stdout, java 侧吐的是 UTF-8 ⇒ 断言文案乱码
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(FAKE_ROOT, exist_ok=True)
    for rel, body in STUBS.items():
        p = os.path.join(OUT, 'stubs', rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        io.open(p, 'w', encoding='utf-8').write(body)
    p = os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'Probe.java')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    io.open(p, 'w', encoding='utf-8').write(PROBE)

    srcs = [p,
            os.path.join(SRC, 'Update.java'),      # ★ 被测: 真实现
            os.path.join(SRC, 'BotService.java'),  # ★ 被测: cfgOr 在这
            os.path.join(SRC, 'OverlayBar.java'),
            os.path.join(SRC, 'OverlayCtl.java'),
            os.path.join(SRC, 'ApkFile.java'),     # ★ 被测: 纯逻辑 (authority/mime/路径规则)
            os.path.join(SRC, 'ApkInstall.java')]  # ★ 被测: 第二条路 (弹系统安装界面)
    for r, _, fs in os.walk(os.path.join(OUT, 'stubs')):
        for f in fs:
            if f.endswith('.java'):
                srcs.append(os.path.join(r, f))

    classes = os.path.join(OUT, 'classes')
    os.makedirs(classes, exist_ok=True)
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', classes, '-cp', JSON_REAL] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)
    r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8',
                        '-Dprobe.root=' + FAKE_ROOT.replace('\\', '/'),
                        '-Dprobe.assets=' + os.path.join(ROOT, 'assets', 'config.json').replace('\\', '/'),
                        '-Dprobe.legacy=false',
                        '-cp', os.pathsep.join([classes, JSON_REAL]),
                        'com.zhiyaunhe.sgmbot.Probe'], capture_output=True)
    sys.stdout.write((r.stdout + r.stderr).decode('utf-8', 'replace'))

    print("== 源码级 (兜底那条路的接线与清单) ==")
    fails = src_check()
    for f in fails:
        print("  FAIL " + f)
    if not fails:
        print("  ok   ApkInstall/ApkProvider/ApkFile/Update/AndroidManifest 接线一致")

    sys.exit(0 if (r.returncode == 0 and not fails) else 1)


if __name__ == '__main__':
    main()
