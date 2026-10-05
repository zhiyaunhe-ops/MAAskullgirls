package com.zhiyaunhe.sgmbot;

import android.graphics.Bitmap;
import android.view.InputDevice;
import android.view.MotionEvent;

/**
 * 触控层 — 单指 v1 + 多指 v2 (捏合缩放)。
 *
 * v1 (与 autojs shizuku("input ...") 同口径): 走 `input` 命令行, 只能单指。
 * 单指局限是 input CLI 的 API 面, 不是 shell 权限的限制 —
 * shell uid 本身有 INJECT_EVENTS 权限, 底层 binder 支持完整多指 MotionEvent
 * (实证: scrcpy 以 shell 权限经 InputManager.injectInputEvent 注入多指触摸)。
 *
 * v2 多指方案 (绕开 input CLI): 经 ShizukuBinderWrapper 包装 InputManager 服务,
 * 以 shell 身份事务调用隐藏接口 IInputManager.injectInputEvent(event, mode):
 *   1. vendor 一个 compile-only 的 IInputManager.aidl stub (运行时走 binder, 不受
 *      hidden-API 反射拦截; binder 事务层面无 greylist 检查);
 *   2. 构造 MotionEvent.obtain(downTime, eventTime, ACTION_MOVE,
 *        pointerCount, PointerProperties[](id, TOOL_TYPE_FINGER),
 *        PointerCoords[](x,y,pressure=1,size=1), metaState=0, buttonState=0,
 *        precision(1,1), deviceId=0, edgeFlags=0, source=SOURCE_TOUCHSCREEN, flags=0)
 *      — 两指各自沿路径逐帧 obtain+inject, 帧间 sleep 8-16ms;
 *   3. 捏合缩放 = 两指从 (640,288)±d 沿水平相向; d 从 400 递减到 120, ~0.6s。
 * 用途: 王关地图开场捏合缩小一次 → 全图固定视野 → 锚点定位更稳 + 顺带解锁
 * 未来任何需要多指的界面。
 */
public final class TouchCtl {
    private final ShizukuCtl sh;

    public TouchCtl(ShizukuCtl sh) { this.sh = sh; }

    /* ---- v1: input CLI 单指 ---- */

    public void tap(Bitmap frame, float wx, float wy) {
        sh.tapWork(frame, wx, wy);
    }

    public void swipe(Bitmap frame, float x1, float y1, float x2, float y2, long ms) {
        sh.swipeWork(frame, x1, y1, x2, y2, ms);
    }

    /* ---- v2: injectInputEvent 多指 (M2 接入, 见类注释) ---- */

    public boolean multiTouchAvailable() {
        return false;   // TODO(M2): IInputManager stub 就绪后置 true
    }

    /** 捏合缩放: 屏幕中心 (wx,wy) 处两指相向/相背, from→to 为两指半开距 (work 基准) */
    public void pinch(Bitmap frame, float wx, float wy, float fromHalf, float toHalf, long ms) {
        throw new UnsupportedOperationException("v2: injectInputEvent 多指注入待 M2 接入");
    }

    /** MotionEvent 构造参考 (v2 实现时直接取用):
    static MotionEvent twoFingerMove(long down, long t, int action,
            float ax, float ay, float bx, float by) {
        PointerProperties[] pp = new PointerProperties[2];
        PointerCoords[] pc = new PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            pp[i] = new PointerProperties();
            pp[i].id = i; pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new PointerCoords();
            pc[i].pressure = 1; pc[i].size = 1;
        }
        pc[0].x = ax; pc[0].y = ay; pc[1].x = bx; pc[1].y = by;
        return MotionEvent.obtain(down, t, action, 2, pp, pc,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    */
}
