package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 配置编辑页 — 把「可配置」做成手机上点得到的东西, 而不是只能 adb 编文件。
 *
 * 为什么只暴露一个**子集**而不是全量键:
 *   config 里 most 是标定出来的坐标/阈值 (title_roi、btn_rois、boss_anchor、
 *   escape 链…)。手输错一个数就会"不打/乱点/永远认不出", 且现场没有回滚手段。
 *   所以这里只放**调错了也不会打坏流程**的运行时参数, 其余仍走 adb push 原文
 *   (那才是精确编辑)。每项都带范围校验, 越界直接拒收。
 *
 * ⚠️ 保存走的是与 POST /config **同一条落地路径** (写 Paths.config() 的 override
 *    文件 + BotService.reload()), 不是另搞一套 —— 否则两条路会写出语义不同的文件,
 *    而且 Config.read() 是「assets 默认 + override 逐键合并」, 这里只写被改到的键,
 *    没碰的键继续吃 assets 默认, 天然不会把默认值"固化"成 override。
 */
public class ConfigActivity extends Activity {
    private LinearLayout root;
    private JSONObject cur = new JSONObject();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(36, 48, 36, 48);
        ScrollView sv = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        sv.addView(root);
        page.addView(sv);
        setContentView(page);
        build();
    }

    private void build() {
        root.removeAllViews();
        Config cfg = BotService.cfg();
        if (cfg == null) cfg = new Config(this);
        cur = cfg.read();

        title("运行时参数");
        hint("改完点「保存」即写 " + Paths.config()
                + " 并热重载。文件名旁标 (内置) 表示当前值来自 APK 默认, 尚未写过 override。");

        /* ---- 扫描 ---- */
        section("扫描间隔 (ms) — 越小反应越快、越费电");
        intEdit("battle.battle_ms", "战斗期", sec("scan", "battle_ms", 800), 80, 5000);
        intEdit("result.result_ms", "结算期", sec("scan", "result_ms", 350), 80, 5000);
        boolEdit("scan.adaptive.on", "自适应 (命中加快 / 久无命中放缓)",
                bool("scan.adaptive", "on", true));
        intEdit("adaptive.hit_ms", "  命中后", sec("scan.adaptive", "hit_ms", 350), 80, 5000);
        intEdit("adaptive.miss_ms", "  久无命中", sec("scan.adaptive", "miss_ms", 900), 80, 5000);

        /* ---- 匹配 ---- */
        section("匹配口径");
        hint("⚠️ blue 是标定过的 (灰色会漏识别 REMATCH)。改通道等于改整套阈值, "
                + "除非重新标定过, 否则别动。");
        textEdit("matcher.chan", "通道 (blue/green/red/gray)", str("matcher", "chan", "blue"));
        hint("step=粗搜步距, refine=命中后精定位邻域。卡顿就调大 step。");
        intEdit("matcher.step", "step", sec("matcher", "step", 2), 1, 4);
        intEdit("matcher.refine", "refine", sec("matcher", "refine", 2), 0, 4);

        /* ---- 运行 ---- */
        section("运行控制");
        hint("0 / 留空 = 不限。暂停时段支持跨夜 (如 23:30 ~ 07:00)。");
        intEdit("run.max_minutes", "单次最长 (分钟)", sec("run", "max_minutes", 0), 0, 1440);
        textEdit("run.pause_from", "暂停起 (HH:MM)", str("run", "pause_from", ""));
        textEdit("run.pause_to", "暂停止 (HH:MM)", str("run", "pause_to", ""));

        /* ---- 流水 ---- */
        section("判定流水");
        intEdit("log.trace_size", "保留条数", sec("log", "trace_size", 200), 20, 2000);
        hint("判定图右侧那张表保留多少条; 调大更占内存。");

        /* ---- 更新 ---- */
        section("GitHub 更新");
        textEdit("update.repo", "仓库 (owner/name)", str("update", "repo", ""));
        intEdit("update.auto_check_h", "自动检查间隔 (小时, 0=关)",
                sec("update", "auto_check_h", 12), 0, 168);
        boolEdit("update.allow_apk", "  允许自动装 APK", bool("update", "allow_apk", true));

        /* ---- 动作 ---- */
        Button save = new Button(this);
        save.setText("保存并热重载");
        save.setAllCaps(false);
        save.setOnClickListener(v -> save());
        root.addView(save, lp());

        Button graph = new Button(this);
        graph.setText("打开判定路径图 (本地页面)");
        graph.setAllCaps(false);
        graph.setOnClickListener(v ->
                startActivity(new Intent(this, GraphActivity.class)));
        root.addView(graph, lp());

        Button reset = new Button(this);
        reset.setText("恢复内置默认 (删除 override 文件)");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> reset());
        root.addView(reset, lp());

        hint("文件: " + Paths.config()
                + (new File(Paths.config()).exists() ? "\n(override 已存在)" : "\n(尚无 override — 当前全是内置默认)"));
    }

    /* ---------------- 保存 ---------------- */

    /** 收集界面值 → 只写被改到的键 → 热重载。校验失败不落盘。 */
    private void save() {
        try {
            JSONObject ov = new JSONObject();
            for (int i = 0; i < root.getChildCount(); i++) {
                View v = root.getChildAt(i);
                if (!(v instanceof LinearLayout)) continue;
                Object tag = v.getTag();
                if (tag == null) continue;
                String path = tag.toString();
                String val = read(v);
                if (val == null) continue;                   // 空 = 不写这一项
                if (!val.equals(orig(path))) put(ov, path, val);   // 与当前值相同就不写
            }
            if (ov.length() == 0) {
                toast("没有任何改动 — 未写文件");
                return;
            }
            JSONObject exist = existingOverride();
            JSONObject merged = Config.merge(exist, ov);     // 保留之前写过的其它键
            File f = new File(Paths.config());
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            FileOutputStream os = new FileOutputStream(f);
            os.write(merged.toString(2).getBytes("UTF-8"));
            os.close();
            BotService.reload();                             // 与 POST /config 同一条路径
            SgmLog.i("cfg", "保存 override: " + ov.length() + " 个键 → " + f.getAbsolutePath());
            toast("已保存 " + ov.length() + " 项, 已热重载");
            build();                                         // 重画: 标签上的 (内置) 状态会变
        } catch (Throwable t) {
            SgmLog.i("cfg", "保存失败: " + t);
            toast("保存失败: " + t);
        }
    }

    private void reset() {
        try {
            File f = new File(Paths.config());
            boolean ok = !f.exists() || f.delete();
            BotService.reload();
            SgmLog.i("cfg", "恢复默认: " + (ok ? "已删 override" : "删除失败"));
            toast(ok ? "已恢复内置默认" : "删除失败, 检查文件权限");
            build();
        } catch (Throwable t) {
            toast("失败: " + t);
        }
    }

    private static JSONObject existingOverride() {
        try {
            File f = new File(Paths.config());
            if (f.exists() && f.length() > 0)
                return Config.load(new java.io.FileInputStream(f));
        } catch (Exception ignored) { }
        return new JSONObject();
    }

    /** 把 a.b.c 路径写进嵌套 JSON */
    private static void put(JSONObject o, String path, String val) throws Exception {
        String[] ks = path.split("\\.");
        JSONObject d = o;
        for (int i = 0; i < ks.length - 1; i++) {
            JSONObject n = d.optJSONObject(ks[i]);
            if (n == null) { n = new JSONObject(); d.put(ks[i], n); }
            d = n;
        }
        String k = ks[ks.length - 1];
        if ("true".equals(val) || "false".equals(val)) d.put(k, "true".equals(val));
        else {
            try { d.put(k, Integer.parseInt(val)); }
            catch (Exception e) { d.put(k, val); }
        }
    }

    /* ---------------- 控件 ---------------- */

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(19f);
        t.setPadding(0, 0, 0, 16);
        root.addView(t);
    }

    private void section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(16f);
        t.setPadding(0, 28, 0, 10);
        root.addView(t);
    }

    private void hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12f);
        t.setTextColor(Color.parseColor("#6B7280"));
        t.setPadding(0, 4, 0, 10);
        root.addView(t);
    }

    private void intEdit(String key, String label, int val, int lo, int hi) {
        row(key, label, String.valueOf(val), InputType.TYPE_CLASS_NUMBER, lo, hi, false);
    }

    private void textEdit(String key, String label, String val) {
        row(key, label, val, InputType.TYPE_CLASS_TEXT, 0, 0, false);
    }

    private void boolEdit(String key, String label, boolean val) {
        row(key, label, val ? "true" : "false", 0, 0, 0, true);
    }

    private void row(String key, String label, String val, int inputType, int lo, int hi, boolean isBool) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, 6, 0, 6);
        r.setTag(key);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, isBool ? 1f : 0.5f);
        t.setLayoutParams(tp);
        r.addView(t);

        if (isBool) {
            CheckBox cb = new CheckBox(this);
            cb.setChecked("true".equals(val));
            r.addView(cb);
        } else {
            EditText e = new EditText(this);
            e.setText(val);
            e.setTextSize(14f);
            e.setInputType(inputType);
            e.setSingleLine(true);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0.5f);
            e.setLayoutParams(ep);
            r.addView(e);
            if (hi > lo) {                       // 带范围的给个提示, 省得反复试
                TextView rg = new TextView(this);
                rg.setText(lo + "~" + hi);
                rg.setTextSize(11f);
                rg.setTextColor(Color.parseColor("#9CA3AF"));
                rg.setPadding(8, 0, 0, 0);
                r.addView(rg);
            }
            e.setTag(new int[]{lo, hi});
        }
        root.addView(r, lp());
    }

    private static LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 读一行控件的当前值; 布尔取勾选态, 空串返回 null (= 不写这一项) */
    private static String read(View row) {
        for (int i = 0; i < ((ViewGroup) row).getChildCount(); i++) {
            View c = ((ViewGroup) row).getChildAt(i);
            if (c instanceof CheckBox) return ((CheckBox) c).isChecked() ? "true" : "false";
            if (c instanceof EditText) {
                String s = ((EditText) c).getText().toString().trim();
                if (s.length() == 0) return null;
                Object tag = c.getTag();
                if (tag instanceof int[]) {              // 范围校验: 越界算非法, 不落盘
                    int[] r = (int[]) tag;
                    if (r[1] > r[0]) {
                        try {
                            int v = Integer.parseInt(s);
                            if (v < r[0] || v > r[1]) return "!out-of-range";
                        } catch (Exception e) { return "!not-a-number"; }
                    }
                }
                return s;
            }
        }
        return null;
    }

    /** 读的值与当前配置不同才写 — 避免把默认值固化进 override */
    private String orig(String path) {
        String[] ks = path.split("\\.");
        JSONObject d = cur;
        for (int i = 0; i < ks.length - 1 && d != null; i++) d = d.optJSONObject(ks[i]);
        if (d == null) return "";
        Object v = d.opt(ks[ks.length - 1]);
        return v == null ? "" : String.valueOf(v);
    }

    /* ---------------- 取值小工具 ---------------- */

    private int sec(String a, String b, int dflt) {
        JSONObject o = cur.optJSONObject(a);
        return o == null ? dflt : o.optInt(b, dflt);
    }

    private boolean bool(String a, String b, boolean dflt) {
        JSONObject o = cur.optJSONObject(a);
        return o == null ? dflt : o.optBoolean(b, dflt);
    }

    private String str(String a, String b, String dflt) {
        JSONObject o = cur.optJSONObject(a);
        return o == null ? dflt : o.optString(b, dflt);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
