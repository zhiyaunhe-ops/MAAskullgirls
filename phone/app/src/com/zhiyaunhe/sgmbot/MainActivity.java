package com.zhiyaunhe.sgmbot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import rikka.shizuku.Shizuku;

/**
 * 主页: Shizuku 连接/授权引导 + 悬浮窗授权 + 引擎状态。一次性设置都在这里完成。
 */
public class MainActivity extends Activity {
    private TextView status;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (ShizukuCtl.serverUp() && ShizukuCtl.granted() && !ShizukuCtl.ready()) {
                ShizukuCtl.tryBind(MainActivity.this);   // 授权弹窗点完允许后, 轮询自动接上
            }
            Paths.init(MainActivity.this);   // 授权"所有文件访问"回来后自动换根, 不必重启
            status.setText(statusText());
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);

        status = new TextView(this);
        status.setTextSize(15f);
        status.setPadding(0, 20, 0, 40);
        root.addView(status);

        root.addView(btn("① 授权 Shizuku (弹出授权框点允许)", v -> connectShizuku()));
        root.addView(btn("② 授权悬浮窗 (显示在应用上层)", v -> {
            if (!Settings.canDrawOverlays(this))
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
        }));
        root.addView(btn("③ 授权所有文件访问 (adb push 改配置/换模板)", v -> {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            }
        }));
        root.addView(btn("④ 启动服务 (悬浮条 + 通路自证)", v ->
                BotService.start(this, BotService.MODE_NAV)));
        root.addView(btn("停止引擎", v -> BotService.trigger("stop")));
        root.addView(btn("重载配置 (config.json)", v -> BotService.trigger("reload")));

        setContentView(root);

        /* Shizuku binder 获取走 provider (manifest 已注册): server 通过
         * ShizukuProvider.call(SEND_BINDER) 把 binder 推给本进程 → Shizuku.onBinderReceived
         * → attachApplication → server 回调 bindApplication 置 serverUp/granted。
         *
         * 不用 addBinderReceivedListenerSticky 是因为回调需 Activity 存活; 这里改由
         * 1s 轮询驱动: serverUp && granted && 未绑定 → tryBind。
         * (注: 早期版本 uninstall sticky 是因为触碰 Shizuku 类就 NoClassDefFoundError —
         *  那是本机 classpath 漏编 moe.shizuku.server.* 桩所致, 已随 libs/aidl-src 编入,
         *  不是 HyperOS 的限制。此处保留轮询仅为生命周期简单。) */
        h.postDelayed(refresh, 500);
    }

    private Button btn(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(l);
        return b;
    }

    private void connectShizuku() {
        if (!ShizukuCtl.serverUp()) {
            SgmLog.i("main", "Shizuku binder 不可用 — 确认 Shizuku App 服务运行中");
            return;
        }
        if (ShizukuCtl.granted()) {
            ShizukuCtl.tryBind(this);
        } else {
            Shizuku.requestPermission(ShizukuCtl.REQ_PERMISSION);
        }
    }

    private String statusText() {
        StringBuilder sb = new StringBuilder("== SGM挂机 ==\n\n");
        sb.append("Shizuku 服务: ").append(ShizukuCtl.serverUp() ? "在线" : "未连接").append('\n');
        sb.append("Shizuku 授权: ").append(ShizukuCtl.granted() ? "已授" : "未授").append('\n');
        sb.append("执行通道: ").append(ShizukuCtl.ready() ? "就绪 (可截屏/点击)" : "未绑定").append('\n');
        sb.append("悬浮窗: ").append(Settings.canDrawOverlays(this) ? "已授" : "未授").append('\n');
        sb.append("所有文件访问: ").append(Paths.legacy() ? "已授" : "未授(退私有目录)").append('\n');
        sb.append("引擎: ").append(BotService.isRunning()
                ? "运行中 (" + BotService.currentMode() + ")" : "停止").append('\n');
        sb.append("外部根: ").append(Paths.root()).append('\n');
        sb.append("\n adb 调试: adb forward tcp:8791 tcp:8791\n 然后开 http://127.0.0.1:8791/status");
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(refresh);
    }
}
