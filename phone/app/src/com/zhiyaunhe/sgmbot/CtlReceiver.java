package com.zhiyaunhe.sgmbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * adb 控制面之广播通道:
 *   adb shell am broadcast -a com.zhiyaunhe.sgmbot.CMD -e action start_nav|stop|reload|status
 * 无权广播 (exported + 权限签名级) — 只在本机/adb shell 可发 (adb shell 以 shell uid 发
 * 需要 Receiver exported=true; 动作均不消耗资源, 风险面=重启导航, 可接受)。
 */
public class CtlReceiver extends BroadcastReceiver {
    public static final String ACTION_CMD = "com.zhiyaunhe.sgmbot.CMD";

    @Override
    public void onReceive(Context ctx, Intent in) {
        String action = in.getStringExtra("action");
        if (action == null) return;
        SgmLog.i("ctl", "broadcast cmd: " + action);
        switch (action) {
            case "start_nav":
                BotService.start(ctx, BotService.MODE_NAV);
                break;
            case "start":
                BotService.start(ctx, BotService.MODE_SETTLE);
                break;
            case "stop":
                BotService.stop(ctx);
                break;
            case "reload":
                new Config(ctx).read();   // 热读配置, 引擎下一帧生效
                SgmLog.i("ctl", "config reloaded");
                break;
            default:
                SgmLog.i("ctl", "unknown action: " + action);
        }
    }
}
