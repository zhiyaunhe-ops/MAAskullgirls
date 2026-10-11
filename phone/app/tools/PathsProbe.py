#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Paths 回归 —— 纯 JVM, 跑**真的** Paths.java (桩掉 android.content.Context / android.os.*)。

为什么值得单独做一个 (2026-10-11 用户报障):
    「我早就授权了所有文件访问, 为啥他还自己安装不了啊? 说 shell 读不到」
    这句话出自 Update.installApk 的旧闸门 `if (!Paths.legacy()) { … 跳过 APK }`。

    根因在 Paths.init:
        private static String root = LEGACY;   // ← 静态初值就是目标值
        private static boolean legacy;         // ← 初值 false
        …
        if (r.equals(root)) return;            // ← 提前返回
        legacy = m;                            // ← 永远轮不到执行

    于是「冷启动 + 早已授权」这条**最平常**的路径上:
        r = LEGACY == root ⇒ return ⇒ legacy 停在 false
    ⇒ root() 正确 (/sdcard/sgm_settle, 文件确实写在那儿),
      但 legacy() 恒 false ⇒ 自更新一口咬定"未授所有文件访问", APK 永远装不上。

    为什么只有这类用户踩到: 只有「先授权、之后 App 被重启过」才会走这条路;
    授权当下 (r 从 priv 变成 LEGACY, 不相等) 是好的 —— 所以开发时"刚授完权试一下"
    永远测不出来, 而长期用户的常态恰恰是"早授权 + 已重启"。

    教训 (通用): 拿「新旧值相等」当「无需赋值」的判据时, 必须确认初值不等于任一目标值,
    否则第一跳就是错误状态。**赋值和打日志是两件事, 别合并。**

断言清单:
  A. 冷启动时已授权      ⇒ legacy()==true, root=/sdcard/sgm_settle (复现的那条)
  B. 跑着跑着才授权      ⇒ 第二次 init 后 legacy()==true (原来就是好的, 别改坏)
  C. 一直没授权          ⇒ legacy()==false, root=App 私有目录
  D. 授权被撤销          ⇒ 回到私有目录 (双向往返都得对)
  E. 幂等: 反复 init 不改变状态 / 不重复改变 root
  F. updateDir() 跟着 root 走 (自更新的落点必须和授权状态一致)

用法: python3 phone/app/tools/PathsProbe.py
"""
import io, os, shutil, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # phone/app
SRC = os.path.join(ROOT, 'src')
OUT = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), 'sgmbot-paths')
JAVA = r'C:\Program Files\Java\jdk-1.8\bin\javac.exe'
JAVA_RUN = r'C:\Program Files\Java\jdk-1.8\bin\java.exe'

# 只够 Paths 编译过的最小 android 面 —— 不抄大桩集, 免得把无关的东西拖进来
STUBS = {
    'android/content/Context.java': '''\
package android.content;
import java.io.File;
/** 私有外部目录由 probe.priv 给 (真机上是 /sdcard/Android/data/<pkg>/files) */
public class Context {
    public File getExternalFilesDir(String t) {
        return new File(System.getProperty("probe.priv",
                "/sdcard/Android/data/com.zhiyaunhe.sgmbot/files"));
    }
}
''',
    'android/os/Build.java': '''\
package android.os;
public class Build { public static class VERSION { public static int SDK_INT = 34; } }
''',
    'android/os/Environment.java': '''\
package android.os;
/** 「所有文件访问」(MANAGE_EXTERNAL_STORAGE) 授没授 —— 由 probe.manager 控, 可中途改 */
public class Environment {
    public static boolean isExternalStorageManager() {
        return Boolean.parseBoolean(System.getProperty("probe.manager", "false"));
    }
}
''',
    'com/zhiyaunhe/sgmbot/SgmLog.java': '''\
package com.zhiyaunhe.sgmbot;
public class SgmLog {
    public static int n = 0;
    public static void i(String t, String m) { n++; System.out.println("    [" + t + "] " + m); }
}
''',
}

PROBE = r'''
package com.zhiyaunhe.sgmbot;

import android.content.Context;

/**
 * 跑**真的** Paths.java。⚠️ Paths 全是 static 状态, 一个 JVM 里没法重来 ——
 * 每个场景各起一个进程 (见 PathsProbe.py 的 SCENES)。
 */
public class Probe {
    static int pass = 0, fail = 0;

    static void ck(String name, boolean ok, Object got) {
        if (ok) { pass++; System.out.println("  [ok] " + name); }
        else { fail++; System.out.println("  [XX] " + name + "  got=" + got); }
    }

    public static void main(String[] a) {
        String scen = a.length > 0 ? a[0] : "A";
        Context ctx = new Context();

        if ("A".equals(scen)) {
            System.out.println("A 冷启动时早已授权 (用户报障的场景)");
            System.setProperty("probe.manager", "true");
            Paths.init(ctx);
            ck("legacy()==true (根因: 原来是 false)", Paths.legacy(), Paths.legacy());
            ck("root()==/sdcard/sgm_settle", Paths.LEGACY.equals(Paths.root()), Paths.root());
            ck("落盘日志至少一条 (别静默)", SgmLog.n > 0, SgmLog.n);
        } else if ("B".equals(scen)) {
            System.out.println("B 跑着跑着才授权 (App 里点完设置回来)");
            System.setProperty("probe.manager", "false");
            Paths.init(ctx);
            ck("先: 未授权 ⇒ 私有目录", !Paths.legacy(), Paths.root());
            System.setProperty("probe.manager", "true");
            Paths.init(ctx);
            ck("后: 已授权 ⇒ legacy=true", Paths.legacy(), Paths.legacy());
            ck("后: root 换成共享目录", Paths.LEGACY.equals(Paths.root()), Paths.root());
        } else if ("C".equals(scen)) {
            System.out.println("C 一直没授权");
            System.setProperty("probe.manager", "false");
            Paths.init(ctx);
            ck("legacy()==false", !Paths.legacy(), Paths.legacy());
            ck("root 落私有目录", !Paths.LEGACY.equals(Paths.root()), Paths.root());
            /* ⚠️ 归一分隔符再断言 —— 探针跑在 Windows 上, 私有目录是桩给的
             *    "/sdcard/Android/data/…", 经 File.getAbsolutePath() 会变成
             *    "D:\sdcard\Android\data\…" (盘符相对路径)。断言不该依赖宿主平台。 */
            ck("root 是 App 私有外部目录",
                    Paths.root().replace('\\', '/').indexOf("Android/data") >= 0, Paths.root());
        } else if ("D".equals(scen)) {
            System.out.println("D 授权先给后撤 (双向都要对)");
            System.setProperty("probe.manager", "true");
            Paths.init(ctx);
            ck("授: legacy=true", Paths.legacy(), Paths.legacy());
            System.setProperty("probe.manager", "false");
            Paths.init(ctx);
            ck("撤: 回到私有目录", !Paths.legacy() && !Paths.LEGACY.equals(Paths.root()), Paths.root());
        } else if ("E".equals(scen)) {
            System.out.println("E 幂等 (主页 1s 轮询会反复调)");
            System.setProperty("probe.manager", "true");
            Paths.init(ctx);
            String r1 = Paths.root();
            boolean l1 = Paths.legacy();
            for (int i = 0; i < 10; i++) Paths.init(ctx);
            ck("反复 init 不改状态", Paths.legacy() == l1 && r1.equals(Paths.root()), Paths.root());
            ck("反复 init 只落一条日志", SgmLog.n == 1, SgmLog.n);
        } else {
            System.out.println("F updateDir 跟着 root 走 (自更新落点)");
            System.setProperty("probe.manager", "true");
            Paths.init(ctx);
            String u1 = Paths.updateDir();
            System.setProperty("probe.manager", "false");
            Paths.init(ctx);
            String u2 = Paths.updateDir();
            ck("已授权 ⇒ 落在共享目录 (shell 读得到)", u1.startsWith(Paths.LEGACY), u1);
            ck("未授权 ⇒ 落在私有目录 (shell 读不到, 自知之明)", !u2.startsWith(Paths.LEGACY), u2);
            ck("两者不同 (别让授权状态对落点无影响)", !u1.equals(u2), u1 + " / " + u2);
        }

        System.out.println("  " + (fail == 0 ? "PASS" : "FAIL")
                + "  " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
'''

# 每个场景必须单起 JVM —— Paths 的 static 状态没法在一个进程里重置
SCENES = [
    ('A', '冷启动时早已授权 (用户报障)'),
    ('B', '跑着跑着才授权'),
    ('C', '一直没授权'),
    ('D', '授权先给后撤'),
    ('E', '幂等 (反复 init)'),
    ('F', 'updateDir 跟着 root 走'),
]


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    shutil.rmtree(OUT, ignore_errors=True)
    for rel, body in STUBS.items():
        p = os.path.join(OUT, 'stubs', rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(p), exist_ok=True)
        io.open(p, 'w', encoding='utf-8').write(body)
    p = os.path.join(OUT, 'src', 'com', 'zhiyaunhe', 'sgmbot', 'Probe.java')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    io.open(p, 'w', encoding='utf-8').write(PROBE)

    srcs = [p, os.path.join(SRC, 'com', 'zhiyaunhe', 'sgmbot', 'Paths.java')]   # ★ 被测
    for r, _, fs in os.walk(os.path.join(OUT, 'stubs')):
        for f in fs:
            if f.endswith('.java'):
                srcs.append(os.path.join(r, f))

    classes = os.path.join(OUT, 'classes')
    os.makedirs(classes, exist_ok=True)
    r = subprocess.run([JAVA, '-J-Dfile.encoding=UTF-8', '-encoding', 'UTF-8', '-nowarn',
                        '-d', classes] + srcs, capture_output=True)
    if r.returncode != 0:
        sys.stdout.write(r.stderr.decode('utf-8', 'replace'))
        sys.exit(1)

    bad = 0
    for scen, title in SCENES:
        print('== 场景 %s  %s ==' % (scen, title))
        r = subprocess.run([JAVA_RUN, '-Dfile.encoding=UTF-8',
                            '-cp', classes, 'com.zhiyaunhe.sgmbot.Probe', scen],
                           capture_output=True)
        sys.stdout.write((r.stdout + r.stderr).decode('utf-8', 'replace'))
        if r.returncode != 0:
            bad += 1
    print('== %d/%d 场景通过 ==' % (len(SCENES) - bad, len(SCENES)))
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
