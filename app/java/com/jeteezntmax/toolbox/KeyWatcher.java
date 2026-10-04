package com.jeteezntmax.toolbox;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 音量键监听：
 *   单击音量上 → 呼出游戏加速菜单
 *   单击音量下 → 收起菜单
 *   双击音量上/下 → 开关"功能提示悬浮窗"（常驻太烦的时候就双击关掉）
 *
 * 这里【不拦】按键（onKeyEvent 一律 return false）—— 音量该加还是加，
 * 只是顺便把菜单叫出来（作者要求"一按就弹"）。
 *
 * 需要无障碍服务的「过滤按键事件」权限（config 里 canRequestFilterKeyEvents + flagRequestFilterKeyEvents），
 * WebUI 里有一个开关，用 root 直接写 settings 打开，不用手点。
 */
public class KeyWatcher extends AccessibilityService {

    private static final long DBL_MS = 450;      // 双击判定窗口（320 太紧，作者说"要卡微妙时机"）
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastDown = 0;
    private boolean dblWait = false, pendingIsUp = false;

    /** 单击：等一个双击窗口再决定（上键=菜单，下键=收起）；双击：开关功能悬浮窗 */
    private final Runnable pending = new Runnable() {
        public void run() {
            if (!dblWait) return;
            dblWait = false;
            if (pendingIsUp) PerfService.showMenu(KeyWatcher.this);
            else PerfService.hideMenu(KeyWatcher.this);
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
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;   // 全部放行：音量照常加减
        if (e.getRepeatCount() > 0) return false;                  // 按住产生的重复事件不理

        long now = System.currentTimeMillis();
        boolean dbl = (now - lastDown) < DBL_MS;
        lastDown = now;
        h.removeCallbacks(pending);
        if (dbl) {
            dblWait = false;
            PerfService.toggleHud(this);               // 双击音量上/下 → 开关功能悬浮窗
        } else {
            dblWait = true;
            pendingIsUp = (code == KeyEvent.KEYCODE_VOLUME_UP);
            h.postDelayed(pending, DBL_MS + 30);       // 等一下看有没有第二下
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        h.removeCallbacks(pending);
        super.onDestroy();
    }
}
