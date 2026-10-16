package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PF WebUI 控制台 — 2026-10-16 用户点名「把 webui 搬到 app 里」。
 *
 * <p>页面本体在 {@link DebugHttp} 的 {@code /pf} (同一份 HTML, PC adb forward 过来看
 * 也是它), 这里只是给手机一个**壳**: 全屏 WebView 打开 127.0.0.1:8791/pf。
 * 数据不走网络 —— 服务就绑在本机回环, WebView 与它是同进程主机上的两个端点。
 *
 * <p>⚠️ 2026-10-11 用户曾嫌「别再跳转什么网页了」—— 那次说的是判定图页 (px 字号小、
 * 满页外链)。/pf 这页是**手机优先排版** (大字/大按钮/深色/无外链跳走), 用户本人又点名
 * 要 WebUI, 所以网页壳这次是正解; 若后续再被嫌丑, 优先改 /pf 的 CSS 而不是换实现。
 *
 * <p>⚠️ DebugHttp 在 {@code App.onCreate} 就启动 (与引擎无关), 所以这个页随时能开,
 * 不需要先起服务 —— 只是引擎没跑时状态是「未运行」、截图占位。
 */
public class WebUiActivity extends Activity {

    private static final String URL = "http://127.0.0.1:8791/pf";
    private WebView wv;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF14161A);          // 与 /pf 页面同底色, 加载时不闪白

        TextView bar = new TextView(this);
        bar.setText("PF 控制台 (数据来自本机调试口)");
        bar.setTextColor(0xFF9CA3AF);
        bar.setTextSize(12f);
        int pad = Ui.dp(this, 12);
        bar.setPadding(pad, pad / 2, pad, pad / 2);
        page.addView(bar);

        wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.setWebViewClient(new WebViewClient());      // 本机链接就地打开, 别跳系统浏览器
        wv.setBackgroundColor(0xFF14161A);
        page.addView(wv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(page);
        wv.loadUrl(URL);
    }

    @Override
    protected void onDestroy() {
        /* WebView 挂着定时轮询 (3s state + 4s 截图), 离开页面必须停 —
         * 否则后台一直打 /screencap 抢 Shizuku 截屏, 拖慢正在跑的循环。 */
        if (wv != null) {
            wv.loadUrl("about:blank");
            wv.onPause();
            wv.destroy();
            wv = null;
        }
        super.onDestroy();
    }

    /** 返回键交给网页历史 (以后页面内加了子页不会一点返回就退出)。 */
    @Override
    public void onBackPressed() {
        if (wv != null && wv.canGoBack()) wv.goBack();
        else finish();
    }
}
