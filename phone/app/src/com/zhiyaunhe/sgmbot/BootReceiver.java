package com.zhiyaunhe.sgmbot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent i) {
        SgmLog.i("boot", "boot completed — 自启恢复待命 (M4: 断点续跑)");
    }
}
