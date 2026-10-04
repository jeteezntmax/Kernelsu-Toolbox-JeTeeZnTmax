package com.jeteezntmax.toolbox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.view.Choreographer;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 迷你监视器悬浮窗的后台服务。
 *
 * 用 TYPE_APPLICATION_OVERLAY 盖在所有应用上面（就像状态条那样），
 * 每 2 秒采一次数据推给 MonitorView。
 *
 * 现在是对着 API 34 的 android.jar 编译的，所以 NotificationChannel
 * 这些可以直接写。manifest 里 targetSdk 仍然保持 30 —— 这样就不用被
 * Android 14 的 foregroundServiceType 和 Android 13 的通知运行时权限折腾。
 */
public class MonitorService extends Service {

    public static final String ACTION_STOP = "com.jeteezntmax.toolbox.STOP_MONITOR";
    /** 弹一条打字机提示条：--es text / --ei ms / --es pos(tr|tc|br|bl) */
/** 挪动悬浮窗：--ei dx/--ei dy 相对移动，--ei ax/--ei ay 绝对定位（-1 = 不改） */
    public static final String ACTION_NUDGE = "com.jeteezntmax.toolbox.NUDGE_MONITOR";
    public static final String ACTION_RESET = "com.jeteezntmax.toolbox.RESET_MONITOR";
    private static final int NOTI_ID = 0x4A54;
    private static final String CH_ID = "jeteez_monitor";
    private static final int PERIOD_MS = 2000;
    private static final String PREF = "monitor";

    private WindowManager wm;
    private MonitorView view;
    private WindowManager.LayoutParams lp;
    /* 点悬浮窗里的「FPS」弹出来的刷新率面板 */
    private RefreshPanelView panel;
    private WindowManager.LayoutParams panelLp;
    private boolean panelShown;
    private final Runnable panelTimeout = new Runnable() {
        public void run() { hideRefreshPanel(); }
    };
    private Handler ui;
    private Thread worker;
    private volatile boolean running;
    private final java.util.concurrent.atomic.AtomicInteger frameN =
            new java.util.concurrent.atomic.AtomicInteger();
    private long fpsBase = 0;
    private double fps = -1;
    private final String suBin = "su";

    public static boolean canOverlay(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try { return Settings.canDrawOverlays(c); } catch (Exception e) { return false; }
        }
        return true;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        try { startForeground(NOTI_ID, buildNotification()); } catch (Exception ignored) { }
        if (!canOverlay(this)) { stopSelf(); return; }
        addOverlay();
        startFpsCounter();
        startLoop();
        startClock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // WebUI / 桌面 App 隔着 Intent 挪悬浮窗：这边只改 lp 再 updateViewLayout
        if (intent != null && ACTION_NUDGE.equals(intent.getAction())) {
            int dx = intent.getIntExtra("dx", 0);
            int dy = intent.getIntExtra("dy", 0);
            int ax = intent.getIntExtra("ax", -1);
            int ay = intent.getIntExtra("ay", -1);
            if (intent.getBooleanExtra("center", false)) {
                ax = centeredX();
                ay = -1;
            }
            nudge(dx, dy, ax, ay);
            return START_NOT_STICKY;   /* 别让系统自己把它拉回来 —— 会「莫名其妙打开悬浮窗」 */
        }
        if (intent != null && ACTION_RESET.equals(intent.getAction())) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().clear().apply();
            nudge(0, 0, dp(2), statusBarH() + dp(1));
            return START_NOT_STICKY;   /* 别让系统自己把它拉回来 —— 会「莫名其妙打开悬浮窗」 */
        }
        return START_NOT_STICKY;   /* 别让系统自己把它拉回来 —— 会「莫名其妙打开悬浮窗」 */
    }

    /** 挪悬浮窗（并记到 SharedPreferences） */
    private void nudge(int dx, int dy, int ax, int ay) {
        if (lp == null || view == null || wm == null) return;
        int x = (ax >= 0) ? ax : lp.x + dx;
        int y = (ay >= 0) ? ay : lp.y + dy;
        if (x < 0) x = 0;
        if (y < 0) y = 0;      // 别挪到状态栏上方：那条的触摸会被通知栏吃掉，拖不回来
        lp.x = x;
        lp.y = y;
        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("mx", x).putInt("my", y).apply();
    }

    @Override
    public void onDestroy() {
        if (lp != null) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit()
                    .putInt("mx", lp.x).putInt("my", lp.y).apply();
        }
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (worker != null) { worker.interrupt(); worker = null; }
        hideRefreshPanel();
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) { }
        }
        view = null;
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    /* ---------- 通知 ---------- */
    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "迷你监视器", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent stop = new Intent(this, MonitorService.class);
        stop.setAction(ACTION_STOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, 1, stop, fl);

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID)
                : new Notification.Builder(this);
        b.setContentTitle("迷你监视器运行中")
         .setContentText("点「停止」关闭悬浮窗（双击悬浮窗也可以）")
         .setSmallIcon(android.R.drawable.stat_notify_sync)
         .setShowWhen(false)
         .setOngoing(true);
        b.addAction(new Notification.Action.Builder(null, "停止", pi).build());
        return b.build();
    }

    /* ---------- 悬浮窗 ---------- */
    private void addOverlay() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        view = new MonitorView(this);
        view.setDragHost(new MonitorView.DragHost() {
            private long lastSave = 0;
            public void onDrag(int dx, int dy) {
                lp.x += dx;
                // 不限制位置了 —— 有些人就喜欢塞在挖孔和上屏的夹角。
                // 拖进状态栏那条之后就收不到触摸了，但 WebUI 里有
                //「重置悬浮窗位置」可以救回来。
                lp.y += dy;
                if (lp.y < 0) lp.y = 0;
                if (lp.x < 0) lp.x = 0;
                try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                if (panelShown) hideRefreshPanel();
                long now = System.currentTimeMillis();
                if (now - lastSave > 600) {          // 别每移动一像素就写一次盘
                    lastSave = now;
                    getSharedPreferences(PREF, MODE_PRIVATE).edit()
                            .putInt("mx", lp.x).putInt("my", lp.y).apply();
                }
            }
        });
        // 点「FPS」那一项 → 弹刷新率面板
        view.setTapHost(new MonitorView.TapHost() {
            public void onTap(int key) {
                if (key == 2) toggleRefreshPanel();
            }
        });
        // 双击关闭 —— 原来是长按，但长按会和"按住拖动"抢手势，所以换了
        view.setOnTouchListener(new View.OnTouchListener() {
            private long lastUp = 0;
            public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                    long now = System.currentTimeMillis();
                    if (now - lastUp < 300) { stopSelf(); return true; }
                    lastUp = now;
                }
                return false;      // 交给 onTouchEvent 去处理拖动
            }
        });

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // 不挡住别处的触摸
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        // ⚠ 很多机器是【居中挖孔】摄像头 —— 放正中间正好被镜头挡掉。
        //   所以默认靠左上，用户拖到哪就记到哪。
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("mx", dp(2));
        // 默认放到【状态栏下面】——
        //   屏幕最顶上那条的触摸会被 TYPE_NOTIFICATION_SHADE(2040) 吃掉，
        //   而我们的悬浮窗是 2038，比它低一层，放 y=0 会收不到手指（拖不动）。
        lp.y = Math.max(statusBarH(), sp.getInt("my", statusBarH() + dp(1)));

        try { wm.addView(view, lp); }
        catch (Exception e) { stopSelf(); }
    }

    /* ---------- 刷新率面板（点 FPS 弹出来的那个） ---------- */
    private void toggleRefreshPanel() {
        if (panelShown) { hideRefreshPanel(); return; }
        if (view == null || wm == null || lp == null) return;
        final MonitorView v = view;
        RefreshPanelView p = new RefreshPanelView(this, v.stats().rates, v.stats().lockHz);
        p.setOnPick(new RefreshPanelView.OnPick() {
            public void pick(String hz) {
                applyRefresh(hz);
                hideRefreshPanel();
            }
        });
        p.setOnOutside(new RefreshPanelView.OnOutside() {
            public void outside() { hideRefreshPanel(); }
        });
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // FLAG_WATCH_OUTSIDE_TOUCH：点到面板外面会收到 ACTION_OUTSIDE → 自动关
        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        try {
            p.measure(View.MeasureSpec.makeMeasureSpec(screenW - dp(8), View.MeasureSpec.AT_MOST),
                      View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        } catch (Exception ignored) { }
        int pw = p.getMeasuredWidth(), ph = p.getMeasuredHeight();
        if (pw <= 0) pw = screenW / 2;
        if (ph <= 0) ph = dp(30);
        int x = Math.max(dp(2), Math.min(lp.x, screenW - pw - dp(2)));
        int y = lp.y + v.getHeight() + dp(4);
        if (y + ph > screenH - dp(8)) y = Math.max(dp(2), lp.y - ph - dp(4));   // 下面放不下就放上面
        panelLp.x = x;
        panelLp.y = y;
        try {
            wm.addView(p, panelLp);
            panel = p;
            panelShown = true;
        } catch (Exception e) {
            panel = null;
            panelShown = false;
            return;
        }
        ui.removeCallbacks(panelTimeout);
        ui.postDelayed(panelTimeout, 8000);        // 兜底：8 秒没人理也关
    }

    private void hideRefreshPanel() {
        ui.removeCallbacks(panelTimeout);
        if (panel != null && wm != null) {
            try { wm.removeViewImmediate(panel); } catch (Exception ignored) { }
        }
        panel = null;
        panelShown = false;
    }

    /** 找模块里的 bin/refresh.sh（装完没重启时在 modules_update 下） */
    /** 同上：App stat 不到 /data/adb，找脚本必须交给 root shell */
    private static final String RFIND =
            "B=\"\"; for c in /data/adb/modules_update/ksu_toolbox/bin/refresh.sh " +
            "/data/adb/modules/ksu_toolbox/bin/refresh.sh " +
            "/data/adb/modules/ksu_toolbox-update/bin/refresh.sh; do " +
            "[ -f \"$c\" ] && { B=\"$c\"; break; }; done; ";

    private void toastMsg(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    /** 把这一次的动作记到保活日志里（WebUI 的「保活日志」能看到） */
    private void logPanel(String what, String res) {
        try {
            String line = "[$(date '+%m-%d %H:%M:%S')] 悬浮窗面板：" + what + " → " + (res == null || res.isEmpty() ? "OK" : res);
            line = line.replace("'", "").replace("\"", "");
            new ProcessBuilder("su", "-c",
                    "mkdir -p /data/adb/ksu_toolbox/refresh; echo \"" + line + "\" >> /data/adb/ksu_toolbox/refresh/keep.log")
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) { }
    }

    /**
     * 点面板上的档位：走模块的 bin/refresh.sh（lock = 应用 + 存原值 + 起保活；空 = restore）。
     * 找不到脚本就直接写 settings 兜底（老版本模块也能用）。
     * 全程给反馈 + 写日志 —— 不然失败了什么都看不到。
     */
    private void applyRefresh(final String hz) {
        final String what = hz.isEmpty() ? "恢复原值" : ("锁定 " + hz + "Hz");
        toastMsg("正在" + what + "…");
        new Thread(new Runnable() {
            public void run() {
                String out = "", err = "";
                try {
                    String cmd = null;
                    // 交给 root shell 找脚本（App 自己 stat 不到 /data/adb）
                    if (!hz.isEmpty()) {
                        cmd = RFIND + "if [ -n \"$B\" ]; then sh \"$B\" lock " + hz + "; else settings put system peak_refresh_rate " + hz +
                              "; settings put system min_refresh_rate " + hz + "; echo 兜底:没找到refresh.sh,直接写了settings; fi";
                    } else if (false) {
                        cmd = "settings put system peak_refresh_rate " + hz +
                              "; settings put system min_refresh_rate " + hz +
                              "; echo 兜底:没找到refresh.sh,直接写了settings";
                    } else {
                        cmd = RFIND + "if [ -n \"$B\" ]; then sh \"$B\" restore; else settings delete system peak_refresh_rate; settings delete system min_refresh_rate; fi";
                    }
                    Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String l;
                    while ((l = r.readLine()) != null) sb.append(l).append(" ");
                    p.waitFor();
                    out = sb.toString().trim();
                } catch (Exception e) {
                    err = "执行失败：" + e;
                }
                final String res = err.isEmpty() ? out : err;
                logPanel(what, res.isEmpty() ? "OK" : res);
                final String tip = err.isEmpty()
                        ? (what + "（" + (res.isEmpty() ? "完成" : res) + "）")
                        : (what + " " + err);
                ui.post(new Runnable() { public void run() { toastMsg(tip.length() > 90 ? tip.substring(0, 90) : tip); } });
            }
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度（拿不到就给个 28dp 的估计值） */
    private int statusBarH() {
        try {
            int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                int h = getResources().getDimensionPixelSize(id);
                if (h > 0) return h;
            }
        } catch (Exception ignored) { }
        return dp(28);
    }

    /** 左右居中：用自己量出来的宽度算，比 WebUI 瞎猜准 */
    private int centeredX() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int vw = (view != null) ? view.getWidth() : 0;
        if (vw <= 0) vw = sw / 3;                 // 还没量过就先按三分之一估
        int x = (sw - vw) / 2;
        return x > 0 ? x : 0;
    }

    /* ---------- 每秒一次：只为了让「时间」那一项秒数会跳 ---------- */
    private void startClock() {
        ui.postDelayed(new Runnable() {
            public void run() {
                if (!running) return;
                if (view != null) view.tick();
                ui.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /* ---------- 帧率：用 Choreographer 数帧 ----------
       思路参考了 Scene 的做法（它 dex 里只有 Choreographer，没有 SurfaceFlinger
       也没有 dumpsys）—— 但完全是自己写的：
         · 不用 root，不用 su，不卡
         · 每一帧回调一次，按秒统计
       注意：数的是【屏幕的刷新回调率】，开了可变刷新率(VVR)之后
       它正好跟着画面走；但静止画面时系统会降到 1~10Hz，这是正常的。
    */
    private void startFpsCounter() {
        try {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                public void doFrame(long nanos) {
                    frameN.incrementAndGet();
                    long now = System.currentTimeMillis();
                    if (fpsBase == 0) fpsBase = now;
                    long dt = now - fpsBase;
                    if (dt >= 1000) {
                        fps = frameN.get() * 1000.0 / dt;
                        frameN.set(0);
                        fpsBase = now;
                        if (view != null) view.setFps(fps);
                    }
                    Choreographer.getInstance().postFrameCallback(this);
                }
            });
        } catch (Throwable ignored) { }
    }

    /* ---------- 采集循环 ---------- */
    private void startLoop() {
        running = true;
        worker = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    final MonitorView v = view;
                    if (v != null) {
                        try {
                            v.stats().sample();            // CPU/功耗/温度走 su；帧率是 Choreographer 数的
                            final double f = fps;
                            final String c = v.stats().cfg;
                            ui.post(new Runnable() {
                                public void run() {
                                    if (view != null) {
                                        view.applyConfig(c);
                                        view.setFps(f);
                                        view.pushSample();
                                    }
                                }
                            });
                        } catch (Throwable ignored) { }
                    }
                    try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
                }
            }
        });
        worker.start();
    }
}
