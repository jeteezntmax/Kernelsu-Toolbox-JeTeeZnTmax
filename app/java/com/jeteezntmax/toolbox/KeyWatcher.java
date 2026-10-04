package com.jeteezntmax.toolbox;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 音量键监听：**按一下音量上键**呼出游戏加速菜单，**按音量下键**收起来。
 *
 * 这里【不拦】按键（onKeyEvent 一律 return false）—— 音量该加还是加，
 * 只是顺便把菜单叫出来（作者要求"一按就弹"）。
 *
 * 需要无障碍服务的「过滤按键事件」权限（config 里 canRequestFilterKeyEvents + flagRequestFilterKeyEvents），
 * WebUI 里有一个开关，用 root 直接写 settings 打开，不用手点。
 */
public class KeyWatcher extends AccessibilityService {

    private static final long HOLD_MS = 20;      // 一按就弹（留一点防抖）
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean down = false;
    private long downAt = 0;
    private boolean fired = false;

    private final Runnable fire = new Runnable() {
        public void run() {
            if (!down || fired) return;
            if (System.currentTimeMillis() - downAt >= HOLD_MS) {
                fired = true;
                PerfService.showMenu(KeyWatcher.this);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        try {
            AccessibilityServiceInfo info = new AccessibilityServiceInfo();
            info.eventTypes = 0;                       // 不要界面事件，只要按键
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 0;
            info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        } catch (Exception ignored) { }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e == null) return false;
        // 音量下键：把菜单收起来
        if (e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) PerfService.hideMenu(this);
            return false;
        }
        if (e.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP) return false;
        int a = e.getAction();
        if (a == KeyEvent.ACTION_DOWN) {
            if (!down) {
                down = true;
                fired = false;
                downAt = System.currentTimeMillis();
                h.postDelayed(fire, HOLD_MS);          // 一按就弹（会 toggle：再按收起来）
            }
            return false;                              // 放行：音量照常加
        }
        if (a == KeyEvent.ACTION_UP) {
            down = false;
            h.removeCallbacks(fire);
            return false;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        h.removeCallbacks(fire);
        super.onDestroy();
    }
}
