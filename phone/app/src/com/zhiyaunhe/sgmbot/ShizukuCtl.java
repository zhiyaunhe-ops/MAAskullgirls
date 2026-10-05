package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/**
 * Shizuku 通路 — 与 autojs 版同一条权限链 (shell uid):
 *   截屏: shizuku exec "screencap -p /sdcard/sgm_settle/frame.png" → BitmapFactory
 *   点击: shizuku exec "input tap x y" / "input swipe ..."
 * Shizuku API (rikka.shizuku) AAR 待 M1 接入 vendor; 这里先隔离成单类,
 * 上层 (NavChain/SettleLoop) 只认 capture()/tap() 两个原语。
 *
 * 坐标口径: 模板/决策一律 1280x576 (WORK_H), 这里负责 work→实机换算 (scale = H/576)。
 */
public final class ShizukuCtl {
    public static final int WORK_H = 576;
    public static final String SHOT = "/sdcard/sgm_settle/frame.png";

    /** 截屏并返回实机分辨率 Bitmap; 失败返回 null (上层按帧丢失处理, 不中断循环) */
    public Bitmap capture() {
        exec("screencap -p " + SHOT);
        File f = new File(SHOT);
        if (!f.exists() || f.length() == 0) return null;
        Bitmap b = BitmapFactory.decodeFile(SHOT);
        f.delete();
        return b;
    }

    /** work 坐标点击 (1280x576 基准, 与 autojs 版 tapWork 同口径) */
    public void tapWork(Bitmap frame, float wx, float wy) {
        float s = frame.getHeight() / (float) WORK_H;
        exec("input tap " + Math.round(wx * s) + " " + Math.round(wy * s));
    }

    /** work 坐标滑动 (x1,y1 → x2,y2, ms) */
    public void swipeWork(Bitmap frame, float x1, float y1, float x2, float y2, long ms) {
        float s = frame.getHeight() / (float) WORK_H;
        exec("input swipe " + Math.round(x1 * s) + " " + Math.round(y1 * s)
                + " " + Math.round(x2 * s) + " " + Math.round(y2 * s) + " " + ms);
    }

    public boolean alive() {
        return "ok".equals(exec("echo ok").trim());
    }

    /** M1: 经 rikka.shizuku.Shizuku 用户态 binder 转发 shell 命令 (与 autojs shizuku() 同效) */
    static String exec(String cmd) {
        // TODO(M1): Shizuku.newProcess("sh", "-c", cmd) 读 stdout; 超时 5s
        return "";
    }
}
