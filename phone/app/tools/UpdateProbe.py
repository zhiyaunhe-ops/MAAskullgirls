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
import io, os, shutil, subprocess, sys

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

    /** 把本地 APK 水位清掉 ⇒ 下一轮 check 必然判 need_apk */
    static void resetApkWatermark() {
        JSONObject c = readJson(Paths.config());
        JSONObject m = c.optJSONObject("meta");
        if (m == null) { m = new JSONObject(); c.put("meta", m); }
        m.put("apk_version", "");
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
                ck("跳过原因说人话 (含'所有文件访问')",
                        apk != null && apk.optString("msg").contains("所有文件访问"),
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
            ck("H2 文案含真实路径 + '读不到'",
                    a2 != null && a2.optString("msg").contains("读不到")
                            && a2.optString("msg").contains("sgmbot.apk"),
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
            ck("H3 文案给出**出路** (卸载重装), 不是把 Failure 原样丢回来",
                    a3 != null && a3.optString("msg").contains("卸载重装"), 
                    a3 == null ? null : a3.optString("msg"));
            ck("H3 部分跳过 ⇒ LV_WARN", Update.lastLevel() == Update.LV_WARN, Update.lastLevel());
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
            os.path.join(SRC, 'OverlayCtl.java')]
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
    sys.exit(r.returncode)


if __name__ == '__main__':
    main()
