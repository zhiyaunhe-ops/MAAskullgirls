package com.zhiyaunhe.sgmbot;

import android.app.Application;

/** 进程级单例入口 — Receiver/HTTP 侧取 Context 用 */
public class App extends Application {
    public static App inst;
    public static BotService service;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        SgmLog.init();
        new DebugHttp().start();
    }
}
