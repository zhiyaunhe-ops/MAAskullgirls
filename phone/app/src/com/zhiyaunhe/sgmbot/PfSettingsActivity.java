package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * 「PF 设置」页 —— 打哪一场 / 能量上限 / 分数上限 / 出战规则 / 场次指纹。
 *
 * <h3>为什么要有这一页 (2026-10-17 用户原话)</h3>
 * 「WebUI 的那些选场次什么能量上限等等等等这些条件应该都在 APP 里面配置才对呀,
 *  你现在完全没有地方配置, 然后你也没有读取场次的那个逻辑没有搬过来」。
 *
 * <p>确实如此: 在此之前这些条件只能改 {@code /sdcard/sgm_settle/config.json} 或者
 * 用电脑开 WebUI; 而场次表 ({@code assets/pf_arena.json}, 22 个场次的条件) 虽然打进了
 * APK, 却**从来没有任何地方查询过** —— 这一页是它的第一个消费者。</p>
 *
 * <h3>落地口径 (与 PC 一致, 别自己发明)</h3>
 * <ul>
 *   <li>选一个场次 ⇒ 用 {@link PfArenaTable#spec(String)} 把它的
 *       {@code tag/能量/分数上限/规则} 带出来, 显示给用户看, 再写进 config:
 *       {@code pf.arena} (场次名) + {@code pf.energy_cost} + {@code pf.score_target}
 *       + {@code pf.rule}。</li>
 *   <li>⚠️ 写的是**override 覆盖键** (与主页的模式切换同一条落地路径
 *       {@link ConfigActivity#setOverrides}), 不碰 {@code assets/config.json} 默认值。</li>
 *   <li>⚠️ {@code score_target = -1} 是<b>真的没有上限</b> (月场), 不是漏填 —— 界面上要写明,
 *       否则下一个人会"顺手补一个数"进去 (项目 MEMORY 里专门记过这条)。</li>
 * </ul>
 *
 * <h3>还没做的部分 (说清楚, 不假装)</h3>
 * 「读 hub 卡名自动认出这是哪一场」还没实装 (App 内没有 OCR; PC 那套 PaddleOCR 移植
 * 代价另议)。所以现在的口径是 <b>用户选场次 + 指纹锁定</b>: 选完在 hub 上点一次
 * 「记住当前场次指纹」, 之后 bot 每轮都会核对居中卡是不是这一场, 不是就滑轮播找回来。
 */
public class PfSettingsActivity extends Activity {

    private TextView feed;
    private LinearLayout listBox;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** 当前目标场次 (config 的 pf.arena); 没设过返回 null。 */
    static String targetArena(android.content.Context ctx) {
        try {
            JSONObject all = BotService.cfgOr(ctx).read();
            JSONObject p = all.optJSONObject("pf");
            String a = p == null ? null : p.optString("arena", "");
            return a == null || a.isEmpty() ? null : a;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        View[] pg = Ui.page(this);
        content = (LinearLayout) pg[1];
        setContentView(pg[0]);
        Ui.begin(content);
        feed = Ui.statusBar(this);
        build();
        Ui.end();
        rebuild();
    }

    private TextView arenaInfo;
    private EditText energyEdit, scoreEdit;

    private void build() {
        Ui.add(this, Ui.title(this, "PF 设置"));
        Ui.add(this, Ui.hint(this,
                "这一页管的是「PF 刷」模式下的**场次与条件**。改完立即热重载, 引擎在跑时"
                + "到悬浮窗点「暂停」再「继续」生效。\n"
                + "⚠️ 场次条件都是从 assets/pf_arena.json 的场次表里带出来的 (与电脑端口径一致),"
                + "选完可以在这里微调。"));
        Ui.add(this, feed);

        Ui.add(this, Ui.section(this, "当前状态"));
        arenaInfo = Ui.hint(this, "");
        Ui.add(this, arenaInfo);
        Ui.button(this, "刷新", "重读 config + 指纹状态", feed, this::rebuild);

        Ui.add(this, Ui.section(this, "场次指纹 (锁定打哪一场)"));
        Ui.add(this, Ui.hint(this,
                "bot 每轮上线都会拿「居中卡」和指纹比对: 不像就左右滑轮播找回, 4 次还找不回就"
                + "**不进场**(宁可漏跑不错跑)。\n"
                + "换场次: 先在游戏里把目标场次滑到 hub 中间, 再点下面的「记住当前场次指纹」。"));
        Ui.button(this, "📷 记住当前场次指纹 (游戏停在 hub)", "截当前帧的居中卡存成指纹", feed,
                this::rememberNow);
        Ui.button(this, "🗑 清除指纹 (= 解除场次锁定)", "下次到 hub 会自动重学一次", feed,
                this::forgetNow);

        Ui.add(this, Ui.section(this, "选择场次 (从场次表带出条件)"));
        Ui.add(this, Ui.hint(this,
                "点一个场次 = 写 config 的 pf.arena / pf.energy_cost / pf.score_target / pf.rule 四项。"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        Ui.add(this, listBox);
        Ui.button(this, "✖ 不指定场次 (用按卡位的保守口径)", "4kw 分数 / 4 能量 / 无规则", feed,
                this::clearArena);

        Ui.add(this, Ui.section(this, "手动微调 (覆盖上面带出的值)"));
        Ui.add(this, Ui.hint(this,
                "能量上限 = 编队时每个槽位要凑够的钉数 (角色场是 5, 其余 4; 不够就会拉人换)。\n"
                + "分数上限 = 本场次想刷到的分数 (5kw / 4kw; -1 = **真的没有上限**, 月场就是这种)。"));
        energyEdit = numField("pf.energy_cost", "能量上限 (钉)", "4");
        scoreEdit = numField("pf.score_target", "分数上限 (分数; -1 = 无上限)", "40000000");
        Ui.button(this, "💾 保存这两项并热重载", "", feed, this::saveManual);
    }

    /**
     * 在场次列表里补按钮用的容器。
     *
     * <p>⚠️ 2026-10-17 实栽: onCreate 里 {@code Ui.end()} 之后再调 {@link #rebuild()} (它造
     * 按钮), 直接抛 "先 Ui.begin(content)" 把整页 onCreate 打崩 —— 进程都没了, 现象是
     * 「点进 PF 设置就闪退」。所以列表按钮一律用**两参**版 {@code Ui.button} 自己 addView,
     * 不依赖 Ui 的 pending 容器。</p>
     */
    private LinearLayout content;
    private final java.util.List<Button> arenaBtns = new java.util.ArrayList<>();

    /** 一个数字输入行 (与 ConfigActivity 的 row() 同款外观, 但只在这里用得到)。 */
    private EditText numField(String key, String label, String defVal) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14f);
        t.setTextColor(0xFFCCCCCC);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(t, tp);
        EditText e = new EditText(this);
        e.setTextSize(15f);
        e.setTag(key);
        e.setHint(defVal);
        r.addView(e, new LinearLayout.LayoutParams(280, LinearLayout.LayoutParams.WRAP_CONTENT));
        Ui.add(this, r);
        return e;
    }

    /** 重画整页 (改完配置后调用)。 */
    private void rebuild() {
        String arena = targetArena(this);
        File f = PfArena.targetFile();
        StringBuilder sb = new StringBuilder();
        sb.append("目标场次: ").append(arena == null ? "未指定" : arena).append('\n');
        PfArenaTable.load(this);
        PfArenaTable.Spec sp = arena == null ? null : PfArenaTable.spec(arena);
        if (sp != null) {
            sb.append("类别 ").append(sp.tag)
              .append("  |  能量 ").append(sp.energy()).append(" 钉")
              .append("  |  分数上限 ")
              .append(sp.noLimit() ? "无上限 (月场, 判据跳过)" : String.valueOf(sp.scoreTarget()))
              .append('\n').append("出战规则 ").append(sp.rule() == null ? "无" : sp.rule());
            if (!sp.matched) sb.append("\n⚠️ 场次表里没这条 (未命中, 取保守值)");
        }
        sb.append('\n').append("场次指纹: ")
          .append(f.exists() ? ("已锁定 (" + (f.length() / 1024) + " KB)") : "未锁定");
        arenaInfo.setText(sb.toString());

        /* 数字框回填当前生效值 (读合并后的配置, 不是 override 文件 —— 否则会显示成空)。 */
        try {
            JSONObject all = BotService.cfgOr(this).read();
            JSONObject p = all.optJSONObject("pf");
            if (p != null) {
                if (energyEdit != null && energyEdit.getText().length() == 0)
                    energyEdit.setText(String.valueOf(p.optInt("energy_cost", 4)));
                if (scoreEdit != null && scoreEdit.getText().length() == 0)
                    scoreEdit.setText(String.valueOf(p.optLong("score_target", 40_000_000L)));
            }
        } catch (Throwable ignored) { }

        /* 场次列表 (首次建, 之后只改选中态) */
        if (arenaBtns.isEmpty()) {
            List<String> names = PfArenaTable.names();
            for (final String n : names) {
                /* ⚠️ 用**两参**版 Ui.button: 它只造控件不 add (三参版内部会调 Ui.add ⇒
                 *    需要 Ui.begin, 而这里我们直接往自己的 listBox 里塞)。 */
                Button btn = Ui.button(this, n);
                btn.setTextSize(14f);
                btn.setOnClickListener(v -> pickArena(n));
                arenaBtns.add(btn);
                listBox.addView(btn);
            }
            SgmLog.i("pf", "PF 设置页: 场次表 " + names.size() + " 条已列出");
        }
        for (int i = 0; i < arenaBtns.size(); i++) {
            String n = PfArenaTable.names().get(i);
            arenaBtns.get(i).setText((n.equalsIgnoreCase(arena == null ? "" : arena) ? "● " : "○ ") + n);
        }
    }

    /** 选一个场次: 用场次表把条件带出来, 一次写 4 个 override 键。 */
    private void pickArena(String name) {
        PfArenaTable.Spec sp = PfArenaTable.spec(name);
        if (sp == null) {
            toast("✗ 场次表里没有 " + name);
            return;
        }
        java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
        kv.put("pf.arena", name);
        kv.put("pf.energy_cost", sp.energy());
        kv.put("pf.score_target", sp.scoreTarget());
        if (sp.rule() == null) {
            kv.put("pf.rule", null);                       // 显式清掉旧规则, 否则会残留上一场的
        } else {
            String[] r = sp.rule().split(":", 2);
            kv.put("pf.rule.type", r[0]);
            kv.put("pf.rule.value", r.length > 1 ? r[1] : "");
        }
        String err = ConfigActivity.setOverrides(this, kv);
        if (err != null) { toast("✗ 写入失败: " + err); return; }
        if (energyEdit != null) energyEdit.setText("");
        if (scoreEdit != null) scoreEdit.setText("");
        toast("✓ 已选 " + name + " → " + sp.toString());
        rebuild();
    }

    /** 清掉场次指定 —— ⚠️ 必须把条件也一起回落到**保守默认**, 否则会留下"没有场次 + 上一场的
     *  5 钉/5kw"这种谁也没指定过的组合 (按钮上写的就是"用按卡位的保守口径", 得说到做到)。 */
    private void clearArena() {
        java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
        kv.put("pf.arena", "");
        kv.put("pf.rule", null);
        kv.put("pf.energy_cost", 4);
        kv.put("pf.score_target", 40_000_000L);
        String err = ConfigActivity.setOverrides(this, kv);
        toast(err == null ? "✓ 已清除场次指定 (回按卡位的保守口径: 4kw / 4 钉 / 无规则)" : "✗ " + err);
        if (energyEdit != null) energyEdit.setText("");
        if (scoreEdit != null) scoreEdit.setText("");
        rebuild();
    }

    private void saveManual() {
        java.util.Map<String, Object> kv = new java.util.LinkedHashMap<>();
        kv.put("pf.energy_cost", Integer.parseInt(energyEdit.getText().toString().trim()));
        kv.put("pf.score_target", Long.parseLong(scoreEdit.getText().toString().trim()));
        String err = ConfigActivity.setOverrides(this, kv);
        toast(err == null ? "✓ 已保存并热重载" : "✗ " + err);
    }

    /** 后台线程截一帧存指纹 —— 截屏是 shell IO, 绝不能放 UI 线程。 */
    private void rememberNow() {
        toast("› 正在截屏…");
        new Thread(() -> {
            String msg;
            try {
                ShizukuCtl s = BotService.sh();
                if (s == null) {
                    s = new ShizukuCtl();
                    ShizukuCtl.tryBind(this);
                    Thread.sleep(2000);                    // 让 UserService 接上
                }
                Bitmap b = s.capture();
                if (b == null) msg = "✗ 截屏失败 (Shizuku 就绪了吗? 服务里看状态)";
                else msg = PfArena.remember(b) ? "✓ 指纹已存 (当前居中卡)"
                        : "✗ 存指纹失败 (当前帧尺寸不对? 游戏在哪一页?)";
            } catch (Throwable t) {
                msg = "✗ " + t;
            }
            final String m = msg;
            h.post(() -> { toast(m); rebuild(); });
        }, "pf-remember").start();
    }

    private void forgetNow() {
        toast(PfArena.forgetTarget() ? "✓ 指纹已清除" : "✗ 删除失败");
        rebuild();
    }

    private void toast(String s) {
        if (feed == null) return;
        feed.setTextColor(s.startsWith("✗") ? Ui.ERR : Ui.OK);
        feed.setText(s);
    }
}
