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
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * 游戏加速服务（v3.4.0）
 *
 * 三件事：
 *   ① 功能提示列表（FeatureHudView）：贴在屏幕右上角，一行一个"已打开的功能"，
 *      按字数从多到少排；整块可拖；WebUI/菜单里能关掉
 *   ② 长按音量上键呼出的菜单（VolumeMenuView）：总开关 + 各项参数 + 应用/还原
 *   ③ 自动应用：**按 uid 绑定** —— 每隔 2 秒问一次模块脚本 `perfmode.sh check`，
 *      里面会告诉我们"这个 uid 的进程现在在不在前台"（看 oom_score_adj==0）。
 *      是就 apply、不是就 restore。不做任何"猜前台"的事。
 *
 * 所有落盘/写 sysfs 都交给 bin/perfmode.sh 做（一次 su 调用），App 只做决策和显示。
 */
public class PerfService extends Service {

    public static final String ACTION_STOP = "com.jeteezntmax.toolbox.STOP_PERF";
    public static final String ACTION_MENU = "com.jeteezntmax.toolbox.SHOW_PERF_MENU";
    public static final String ACTION_HIDE_MENU = "com.jeteezntmax.toolbox.HIDE_PERF_MENU";
    public static final String ACTION_TOGGLE_HUD = "com.jeteezntmax.toolbox.TOGGLE_PERF_HUD";
    public static final String ACTION_RESET_HUD = "com.jeteezntmax.toolbox.RESET_PERF_HUD";
    private static final int NOTI_ID = 0x4A57;
    private static final String CH_ID = "jeteez_perf";
    private static final String PREF = "perfhud";
    private static final int PERIOD_MS = 1000;   // 之前 2 秒，作者嫌慢

    private WindowManager wm;
    private Handler ui;
    private FeatureHudView hud;
    private WindowManager.LayoutParams hudLp;
    private VolumeMenuView menu;
    private WindowManager.LayoutParams menuLp;
    private boolean menuShown = false;
    private Thread worker;
    private volatile boolean running = false;

    /* 从脚本读回来的状态 */
    private static String confVal(String out, String key) {
        for (String L : out.split("\n")) {
            int i = L.indexOf('=');
            if (i > 0 && L.substring(0, i).trim().equals(key)) return L.substring(i + 1).trim();
        }
        return "";
    }

    private String app = "", apps = "", fapp = "", uid = "", enabled = "0", menuOn = "1", hudOn = "1";
    private String hudItems = "", cpuKhz = "", cpuMax = "", battUa = "", battUv = "", tempC = "";
    private String hudTitle = "";      // 最上面那行自定义标题（%s = 启用/停用）
    private float fontSp = 0f;
    private boolean hudMuted = false;   // 双击音量键临时关掉提示悬浮窗
    private String hudErr = null;       // 加不上窗口的原因（排错用，会显示在提示里）
    private String hudQuad = "", hudDesc = "";
    private boolean alignRightNow = true;
    private HudTitleView grip;           // 拖动把手（文字那层是触摸穿透的，拖不动）
    private WindowManager.LayoutParams gripLp;         // WebUI 里选的字号（0 = 还没读到，用视图默认）
    private int drift = 0;
    private double fps2 = -1;
    private long frameN = 0, fpsBaseMs = 0;
    private final android.view.Choreographer.FrameCallback frameCb = new android.view.Choreographer.FrameCallback() {
        public void doFrame(long t) {
            frameN++;
            long now = System.currentTimeMillis();
            if (fpsBaseMs == 0) fpsBaseMs = now;
            else if (now - fpsBaseMs >= 1000) { fps2 = frameN * 1000.0 / (now - fpsBaseMs); frameN = 0; fpsBaseMs = now; }
            try { android.view.Choreographer.getInstance().postFrameCallback(this); } catch (Exception ignored) { }
        }
    };
    private int fg = 0, applied = 0;
    private ArrayList<String> lines = new ArrayList<String>();       // 已应用的
    private ArrayList<String> cfgLines = new ArrayList<String>();    // 配置里勾了的（HUD 常驻用）
    private ArrayList<String[]> rows = new ArrayList<String[]>();
    private final HashMap<String, HashMap<String, String>> dispToVal = new HashMap<String, HashMap<String, String>>();
    private String curFreq = "", curGov = "", curAff = "", curRr = "";
    private boolean optLoaded = false;

    public static void start(Context c) {
        Intent i = new Intent(c, PerfService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }
    public static void showMenu(Context c) {
        Intent i = new Intent(c, PerfService.class);
        i.setAction(ACTION_MENU);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }
    /** 双击音量键：临时开关"功能提示悬浮窗"（不动配置，服务重启恢复） */
    public static void toggleHud(Context c) {
        Intent i = new Intent(c, PerfService.class);
        i.setAction(ACTION_TOGGLE_HUD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    /** 音量下键：收起菜单 */
    public static void hideMenu(Context c) {
        Intent i = new Intent(c, PerfService.class);
        i.setAction(ACTION_HIDE_MENU);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    /**
     * 注意：**不能**用 Java 的 File.exists() 去找脚本！
     * App 是 untrusted_app，连 stat /data/adb 都会被 SELinux 拒掉，永远 false ——
     * 之前菜单里"只有关闭有反应"就是这个原因（其余全是静默失败）。
     * 所以"找脚本"这步也塞进 root shell 里做。
     */
    private static final String FIND =
            "B=\"\"; for c in /data/adb/modules_update/ksu_toolbox/bin/perfmode.sh " +
            "/data/adb/modules/ksu_toolbox/bin/perfmode.sh " +
            "/data/adb/modules/ksu_toolbox-update/bin/perfmode.sh; do " +
            "[ -f \"$c\" ] && { B=\"$c\"; break; }; done; ";

    /** 跑 perfmode.sh 的子命令（找不到脚本会回 @@NOBIN） */
    private String shPerf(String args) {
        return su(FIND + "[ -n \"$B\" ] && sh \"$B\" " + args + " || echo @@NOBIN");
    }

    /** 跑一次 su；返回 stdout（合并 stderr），最多 200 行 */
    private String su(String cmd) {
        Process p = null;
        StringBuilder sb = new StringBuilder();
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process fp = p;
            Thread drain = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(fp.getInputStream()));
                        String l; int n = 0;
                        while ((l = r.readLine()) != null) { if (++n <= 200) sb.append(l).append('\n'); }
                    } catch (Exception ignored) { }
                }
            });
            drain.start();
            p.waitFor();
            drain.join(500);
        } catch (Exception ignored) {
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        ui = new Handler(Looper.getMainLooper());
        try { startForeground(NOTI_ID, noti()); } catch (Exception ignored) { }
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try { android.view.Choreographer.getInstance().postFrameCallback(frameCb); } catch (Exception ignored) { }
        ui.postDelayed(hudTick, 1000);
        startLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_MENU.equals(intent.getAction())) {
            // 每次现读一次 conf 里的 menu（服务刚起来时缓存还没填）：
            // menu=1 → 确保显示（已经开着就只刷新，不重复 addView —— 之前就是重复 add 卡没的）
            // menu=0 → 作者把菜单关了，只提示一下
            new Thread(new Runnable() { public void run() {
                String out = shPerf("conf");
                // 注意：conf 里没有 menu 这个键 = 默认开（只有显式写 menu=0 才算关）
                // 之前写的是 "1".equals(...) → 没配过就被当成关，一按就提示"已关闭"
                final boolean on = out.indexOf("@@NOBIN") >= 0 || !"0".equals(confVal(out, "menu"));
                ui.post(new Runnable() { public void run() {
                    if (on) showMenuNow();
                    else toastMsg("音量键菜单已关闭（WebUI 里可以重开）");
                }});
            }}).start();
            return START_STICKY;
        }
        if (intent != null && ACTION_RESET_HUD.equals(intent.getAction())) {
            ui.post(new Runnable() { public void run() {
                // 真的把位置挪回右上角（光删 prefs 文件没用 —— SharedPreferences 有内存缓存）
                if (hudLp != null) {
                    hudLp.x = dp(6);
                    hudLp.y = dp(40);
                    try { if (hud != null && wm != null) wm.updateViewLayout(hud, hudLp); } catch (Exception ignored) { }
                }
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("hx", dp(6)).putInt("hy", dp(40)).apply();
                hudMuted = false;
                refreshHud();
                toastMsg("提示悬浮窗位置已重置");
            }});
            return START_STICKY;
        }
        if (intent != null && ACTION_TOGGLE_HUD.equals(intent.getAction())) {
            ui.post(new Runnable() { public void run() {
                hudMuted = !hudMuted;
                refreshHud();
                String tip;
                if (hudMuted) tip = "功能悬浮窗：关（再双击开回来）";
                else if ("0".equals(hudOn)) tip = "功能悬浮窗：开，但设置里把它关了";
                else if (!"1".equals(enabled)) tip = "功能悬浮窗：开，但总开关没开（所以看不到）";
                else if (hud == null || hud.getVisibility() != View.VISIBLE) {
                    ensureHud();
                    if (hud != null) refreshHud();
                    if (hud == null || hud.getVisibility() != View.VISIBLE)
                        tip = "功能悬浮窗：开，但没显示出来 —— " + (hudErr == null ? "原因不明" : hudErr);
                    else tip = "功能悬浮窗：开";
                } else tip = "功能悬浮窗：开" + (hudQuad.isEmpty() ? "" : ("（" + hudQuad + " · " + hudDesc + "）"));
                toastMsg(tip.length() > 90 ? tip.substring(0, 90) : tip);
            }});
            return START_STICKY;
        }
        if (intent != null && ACTION_HIDE_MENU.equals(intent.getAction())) {
            ui.post(new Runnable() { public void run() { hideMenu(); } });
            return START_STICKY;
        }
        if (hud == null) ui.post(new Runnable() { public void run() { ensureHud(); } });
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) { worker.interrupt(); worker = null; }
        ui.removeCallbacks(hudTick);
        ui.removeCallbacksAndMessages(null);
        hideMenu();
        removeGrip();
        if (hud != null && wm != null) { try { wm.removeView(hud); } catch (Exception ignored) { } }
        hud = null;
        try { stopForeground(true); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private Notification noti() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(CH_ID, "游戏加速", NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false); ch.setSound(null, null); ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, PerfService.class); stop.setAction(ACTION_STOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getService(this, 2, stop, fl);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CH_ID) : new Notification.Builder(this);
        b.setContentTitle("游戏加速运行中")
         .setContentText("长按音量上键呼出菜单 · 点这里是关掉")
         .setSmallIcon(android.R.drawable.stat_notify_sync)
         .setShowWhen(false).setOngoing(true);
        b.addAction(new Notification.Action.Builder(null, "停止", pi).build());
        return b.build();
    }

    /* ---------- 采集循环 ---------- */
    private void startLoop() {
        running = true;
        worker = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try {
                        String out = shPerf("check");
                        if (out.indexOf("@@NOBIN") < 0) {
                            parse(out);
                            decide();
                        }
                    } catch (Throwable ignored) { }
                    try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
                }
            }
        });
        worker.start();
    }

    private void parse(String out) {
        final float fsp = fontSp;
        if (fsp >= 8f) ui.post(new Runnable() { public void run() {
            if (hud != null) hud.setFontSp(fsp);
            if (grip != null) grip.setFontSp(fsp);
            if (menu != null) menu.setFontSp(fsp);
        } });
        final ArrayList<String> ls = new ArrayList<String>();
        String listRaw = "", cfgRaw = "";
        for (String L : out.split("\n")) {
            int i = L.indexOf('=');
            if (i <= 0) continue;
            String k = L.substring(0, i).trim(), v = L.substring(i + 1).trim();
            if (k.equals("app")) app = v;
            else if (k.equals("apps")) apps = v;
            else if (k.equals("fapp")) fapp = v;
            else if (k.equals("uid")) uid = v;
            else if (k.equals("enabled")) enabled = v;
            else if (k.equals("menu")) menuOn = v;
            else if (k.equals("hud")) hudOn = v;
            else if (k.equals("fg")) fg = safeInt(v);
            else if (k.equals("applied")) applied = safeInt(v);
            else if (k.equals("freq")) curFreq = v;
            else if (k.equals("gov")) curGov = v;
            else if (k.equals("affinity")) curAff = v;
            else if (k.equals("refresh")) curRr = v;
            else if (k.equals("list")) listRaw = v;
            else if (k.equals("cfglist")) cfgRaw = v;
            else if (k.equals("hud_items")) hudItems = v;
            else if (k.equals("hud_title")) hudTitle = v;
            else if (k.equals("font")) {
                try { fontSp = Float.parseFloat(v.trim()); } catch (Exception ignored) { }
            }
            else if (k.equals("cpu_khz")) cpuKhz = v;
            else if (k.equals("cpu_max")) cpuMax = v;
            else if (k.equals("batt_ua")) battUa = v;
            else if (k.equals("batt_uv")) battUv = v;
            else if (k.equals("temp")) tempC = v;
            else if (k.equals("drift")) drift = safeInt(v);
        }
        if (!listRaw.isEmpty()) for (String x : listRaw.split("\\|")) if (!x.trim().isEmpty()) ls.add(x);
        lines = ls;
        ArrayList<String> cl = new ArrayList<String>();
        if (!cfgRaw.isEmpty()) for (String x : cfgRaw.split("\\|")) if (!x.trim().isEmpty()) cl.add(x);
        cfgLines = cl;
        ui.post(new Runnable() { public void run() { ensureHud(); refreshHud(); buildRows(); } });
    }

    private void toastMsg(String s) {
        try { android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Exception ignored) { }
    }

    /** 立刻刷一次状态（不用等下一个 2 秒周期） */
    private void refreshSoon() {
        new Thread(new Runnable() { public void run() {
            try {
                String out = shPerf("check");
                if (out.indexOf("@@NOBIN") < 0) parse(out);
            } catch (Throwable ignored) { }
        }}).start();
    }

    private static String labelOf(String key) {
        if (key.equals("freq")) return "CPU 锁频";
        if (key.equals("gov")) return "调速器";
        if (key.equals("affinity")) return "线程绑定";
        if (key.equals("refresh")) return "刷新率";
        if (key.equals("enabled")) return "总开关";
        if (key.equals("hud")) return "功能提示";
        return key;
    }

    private static int safeInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    /** 该应用就应用、不该应用就还原 —— 全靠 uid + fg，不做任何猜测 */
    private void decide() {
        boolean want = "1".equals(enabled) && fg == 1;
        if (want && applied != 1) { act("应用"); applied = 1; }
        else if (!want && applied == 1) { act("还原"); applied = 0; }
        else if (want && applied == 1 && drift > 0) {
            // 保活：锁频/调速器/刷新率被别人改回去了 → 按配置重写一遍（脚本里计数）
            final String out = shPerf("apply");
            logPerf("保活重应用", "被改回 " + drift + " 项 · " + (out.trim().isEmpty() ? "OK" : out.trim().replace('\n', ' ')));
        }
    }

    /** 跑一次动作并给反馈 + 记日志（失败不能是静默的） */
    private void act(final String what) {
        final String out = shPerf(what.equals("应用") ? "apply" : "restore");
        final String msg = what + (out.indexOf("@@NOBIN") >= 0 ? "失败：找不到 bin/perfmode.sh（模块没装全？）"
                : (out.trim().isEmpty() ? "完成" : out.trim().replace('\n', ' ')));
        logPerf(what, msg);
        ui.post(new Runnable() { public void run() { toastMsg(msg.length() > 80 ? msg.substring(0, 80) : msg); } });
    }

    /** 动作记到 refresh/keep.log（WebUI 的「保活日志」看得到） */
    private void logPerf(String what, String res) {
        try {
            String line = "[$(date '+%m-%d %H:%M:%S')] 游戏加速：" + what + " → " + (res == null ? "" : res);
            line = line.replace("'", "").replace("\"", "");
            su("mkdir -p /data/adb/ksu_toolbox/refresh; echo \"" + line + "\" >> /data/adb/ksu_toolbox/refresh/keep.log");
        } catch (Exception ignored) { }
    }

    /* ---------- 功能提示列表 ---------- */
    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void ensureHud() {
        if (hud != null || wm == null) return;
        // ① 有没有"显示在其他应用上层"权限
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
                hudErr = "没有悬浮窗权限（去应用设置里打开「显示在其他应用上层」）";
                return;
            }
        } catch (Exception ignored) { }
        hud = new FeatureHudView(this);
        if (fontSp >= 8f) hud.setFontSp(fontSp);
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        hudLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        hudLp.gravity = Gravity.TOP | Gravity.END;      // 默认贴右上角
        applyHudPos();
        try {
            wm.addView(hud, hudLp);
            hudErr = null;
            ui.postDelayed(new Runnable() { public void run() { ensureGrip(); updateHudLayout(); } }, 60);
        } catch (Exception e) {
            hudErr = "加不上窗口：" + e;
            hud = null;
        }
    }

    /**
     * HUD 跟着【开关】常驻，不再跟"是否已应用"走：
     *   · 正在生效 → 显示已应用的那份列表（带进程数那种更详细）
     *   · 没生效   → 显示配置里勾了哪些（所以不点"应用"也一直看得到）
     * 只有 WebUI 里把「功能提示列表」关掉，它才消失。
     */
    /** WebUI 勾了哪些系统参数；没配过就全显示 */
    private boolean wantItem(String k) {
        if (hudItems == null || hudItems.trim().isEmpty()) return true;
        return ("," + hudItems.trim() + ",").indexOf("," + k + ",") >= 0;
    }

    /** 系统参数行（和功能行混一起，最后统一按字数从多到少排） */
    private ArrayList<String> sysLines() {
        ArrayList<String> out = new ArrayList<String>();
        if (wantItem("time")) {
            java.util.Calendar c = java.util.Calendar.getInstance();
            out.add(String.format("时间 %02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                    c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)));
        }
        if (wantItem("cpu") && cpuKhz != null && !cpuKhz.isEmpty()) {
            try { out.add(String.format("CPU %.2fG", Long.parseLong(cpuKhz.trim()) / 1e6)); } catch (Exception ignored) { }
        }
        if (wantItem("fps")) {
            double f = fps2;
            if (f > 0) out.add(String.format("FPS %d", (int) Math.round(f)));
        }
        if (wantItem("power") && battUa != null && !battUa.isEmpty() && battUv != null && !battUv.isEmpty()) {
            try {
                double w = Math.abs(Long.parseLong(battUa.trim())) / 1e6 * Math.abs(Long.parseLong(battUv.trim())) / 1e6;
                if (w > 0.05 && w < 60) out.add(String.format("功耗 %.2fW", w));
            } catch (Exception ignored) { }
        }
        if (wantItem("temp") && tempC != null && !tempC.isEmpty()) out.add("温 " + tempC + "℃");
        return out;
    }

    /** 最上面那行标题：默认 KSU工具箱-游戏加速：启用/停用；自己填了就用自己的（%s 会替换成状态） */
    private String titleLine() {
        boolean run = "1".equals(enabled) && fg == 1;
        String st = run ? "启用" : "停用";
        if (hudTitle == null || hudTitle.trim().isEmpty()) return "KSU工具箱-游戏加速：" + st;
        return hudTitle.trim().replace("%s", st);
    }

    /**
     * 位置**按屏幕比例**存。
     * 为什么：横屏玩游戏时把它拖到最边上，存下来的是横屏的像素值；
     * 转回竖屏宽高互换，那个像素值就跑到屏幕外了 —— 表现就是"显示开却看不到"。
     * 用比例存，转屏后按新尺寸重算，永远在看得见的地方。
     */
    private void saveHudPos() {
        if (hudLp == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = Math.max(1, dm.widthPixels), sh = Math.max(1, dm.heightPixels);
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
                .putInt("hx", hudLp.x).putInt("hy", hudLp.y)
                .putInt("sw", sw).putInt("sh", sh)
                .putFloat("hxp", hudLp.x / (float) sw)
                .putFloat("hyp", hudLp.y / (float) sh)
                .apply();
    }

    private void applyHudPos() {
        if (hudLp == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = dm.widthPixels, sh = dm.heightPixels;
        SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
        int hx, hy;
        float hxp = sp.getFloat("hxp", -1f), hyp = sp.getFloat("hyp", -1f);
        if (hxp >= 0f) {
            hx = (int) (hxp * sw);
            hy = (int) (hyp * sh);
        } else {
            hx = sp.getInt("hx", dp(6));
            hy = sp.getInt("hy", dp(40));
            int osw = sp.getInt("sw", 0), osh = sp.getInt("sh", 0);
            if (osw > 0 && osh > 0 && (osw != sw || osh != sh)) {   // 老像素数据换算一次
                hx = (int) (hx * (float) sw / osw);
                hy = (int) (hy * (float) sh / osh);
            }
        }
        if (hx < 0) hx = dp(6);
        if (hx > sw - dp(60)) hx = Math.max(dp(6), sw - dp(60));
        if (hy < 0) hy = dp(40);
        if (hy > sh - dp(60)) hy = Math.max(dp(40), sh - dp(60));
        hudLp.x = hx;
        hudLp.y = hy;
    }

    /** 转屏：按新屏幕尺寸把提示窗挪回看得见的地方 */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        try {
            if (hud != null && hudLp != null && wm != null) {
                applyHudPos();
                wm.updateViewLayout(hud, hudLp);
                refreshHud();
                syncGripPos();
                if (grip != null && gripLp != null) { try { wm.updateViewLayout(grip, gripLp); } catch (Exception ignored) { } }
                updateHudLayout();
            }
        } catch (Exception ignored) { }
    }

    /* ---------- 拖动把手 + 象限排版 ---------- */

    private void ensureGrip() {
        if (grip != null || wm == null || hudLp == null) return;
        grip = new HudTitleView(this);
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        gripLp = new WindowManager.LayoutParams(dp(24), dp(24), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        gripLp.gravity = Gravity.TOP | Gravity.END;
        syncGripPos();
        grip.setDragHost(new HudTitleView.DragHost() {
            private long lastSave = 0;
            public void onDrag(int dx, int dy) {
                hudLp.x -= dx;                 // Gravity.END：往右拖 = x 变小
                hudLp.y += dy;
                if (hudLp.x < 0) hudLp.x = 0;
                if (hudLp.x > screenSize()[0] - dp(40)) hudLp.x = Math.max(0, screenSize()[0] - dp(40));
                if (hudLp.y < 0) hudLp.y = 0;
                if (hudLp.y > screenSize()[1] - dp(40)) hudLp.y = Math.max(0, screenSize()[1] - dp(40));
                syncGripPos();
                try { if (hud != null) wm.updateViewLayout(hud, hudLp); } catch (Exception ignored) { }
                try { wm.updateViewLayout(grip, gripLp); } catch (Exception ignored) { }
                updateHudLayout();
                long now = System.currentTimeMillis();
                if (now - lastSave > 600) { lastSave = now; saveHudPos(); }
            }
        });
        try { wm.addView(grip, gripLp); } catch (Exception e) { grip = null; }
    }

    private void removeGrip() {
        if (grip != null && wm != null) { try { wm.removeView(grip); } catch (Exception ignored) { } }
        grip = null;
    }

    private void syncGripPos() {
        if (gripLp == null || hudLp == null) return;
        int[] sz = screenSize();
        int listW = (hud != null && hud.getWidth() > 0) ? hud.getWidth() : dp(90);
        int th = (grip != null && grip.getHeight() > 0) ? grip.getHeight() : dp(26);
        if (alignRightNow) {
            gripLp.x = Math.max(0, hudLp.x);                       // 右对齐：右边缘对齐
        } else {
            int leftOnScreen = sz[0] - hudLp.x - listW;            // 列表左边缘
            gripLp.x = Math.max(0, sz[0] - leftOnScreen - (grip != null && grip.getWidth() > 0 ? grip.getWidth() : dp(120)));
        }
        int ty = hudLp.y - th - dp(2);                             // 放在列表上面
        if (ty < 0) ty = hudLp.y + ((hud != null && hud.getHeight() > 0) ? hud.getHeight() : dp(60)) + dp(2);
        gripLp.y = Math.max(0, ty);
    }

    /** 当前屏幕的宽高：优先 WindowMetrics（跟着朝向走），退路 DisplayMetrics */
    private int[] screenSize() {
        try {
            if (Build.VERSION.SDK_INT >= 30 && wm != null) {
                android.graphics.Rect b = wm.getCurrentWindowMetrics().getBounds();
                if (b.width() > 0 && b.height() > 0) return new int[]{b.width(), b.height()};
            }
        } catch (Exception ignored) { }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    /** 按【当前屏幕】所在象限决定排版（横竖屏都会重算） */
    private void updateHudLayout() {
        if (hud == null || hudLp == null) return;
        int[] sz = screenSize();
        int sw = sz[0], sh = sz[1];
        int w = (hud.getWidth() > 0) ? hud.getWidth() : dp(90);
        int leftPx = sw - hudLp.x - w;                 // 列表左边缘到屏幕左边
        boolean alignRight = (leftPx + w / 2) > sw / 2;   // 只看左右半边（上下那套撤了）
        alignRightNow = alignRight;
        hudQuad = alignRight ? "右" : "左";
        hudDesc = "靠" + hudQuad + "对齐";
        hud.setLayoutMode(alignRight);
    }

    private void refreshHud() {
        if (hud == null) return;
        boolean on = !"0".equals(hudOn) && "1".equals(enabled) && !hudMuted;
        ArrayList<String> use = new ArrayList<String>();
        if (applied == 1 && !lines.isEmpty()) use.addAll(lines); else use.addAll(cfgLines);
        use.addAll(sysLines());
        java.util.Collections.sort(use, new java.util.Comparator<String>() {
            public int compare(String a, String b) { return b.length() - a.length(); }
        });
        try {
            if (on) {
                hud.setVisibility(View.VISIBLE);
                hud.setLines(use);
                ensureGrip();
                if (grip != null) {
                    grip.setTitle(titleLine());
                    if (fontSp >= 8f) grip.setFontSp(fontSp);
                }
                updateHudLayout();
            } else {
                hud.setVisibility(View.GONE);
                removeGrip();
            }
        } catch (Exception ignored) { }
    }

    /** 每秒本地重画一次（时间要精确到秒，不能等 1 秒的 su 轮询） */
    private final Runnable hudTick = new Runnable() {
        public void run() {
            refreshHud();
            ui.postDelayed(this, 1000);
        }
    };

    private boolean targetEmpty() { return app.trim().isEmpty() && uid.trim().isEmpty(); }

    /* ---------- 菜单 ---------- */
    /** 确保菜单在显示（已经开着就只刷新内容） */
    private void showMenuNow() {
        if (wm == null) return;
        loadOptions();
        if (menuShown && menu != null) { buildRows(); return; }
        if (menu == null) {
            menu = new VolumeMenuView(this);
            if (fontSp >= 8f) menu.setFontSp(fontSp);
            menu.setOnAction(new VolumeMenuView.OnAction() {
                public void onCycle(String key, String value) { setAndApply(key, value); }
                public void onToggle(String key, String value) { setAndApply(key, value); }
                public void onApply() { new Thread(new Runnable() { public void run() { act("应用"); refreshSoon(); } }).start(); }
                public void onRestore() { new Thread(new Runnable() { public void run() { act("还原"); refreshSoon(); } }).start(); }
                public void onBoost() {
                    new Thread(new Runnable() { public void run() {
                        final String out = shPerf("boost");
                        final String tip = out.indexOf("@@NOBIN") >= 0 ? "清后台失败：找不到脚本" : out.trim().replace('\n', ' ');
                        logPerf("清后台", tip);
                        ui.post(new Runnable() { public void run() { toastMsg(tip.length() > 80 ? tip.substring(0, 80) : tip); } });
                    }}).start();
                }
                public void onClose() {
                    // 这个按钮的语义是"关掉这个功能"：写 menu=0，之后按音量键不再呼出
                    new Thread(new Runnable() { public void run() {
                        shPerf("set menu 0");
                        logPerf("关闭菜单", "menu=0（再按音量键不呼出；WebUI 里「音量键菜单」可重开）");
                    }}).start();
                    menuOn = "0";
                    hideMenu();
                    ui.post(new Runnable() { public void run() { toastMsg("菜单已关闭 · 想再用去 WebUI 点「音量键菜单」"); } });
                }
            });
        }
        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        menuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        menuLp.gravity = Gravity.CENTER;
        buildRows();
        try { wm.addView(menu, menuLp); menuShown = true; } catch (Exception e) { menu = null; menuShown = false; }
    }

    private void hideMenu() {
        final VolumeMenuView v = menu;
        final WindowManager w = wm;
        menu = null;
        menuShown = false;
        if (v == null || w == null) return;
        try {
            w.removeView(v);
        } catch (Exception e) {
            // 极端情况（正在触摸派发/布局里）→ 下一帧再删，避免留个幽灵窗
            ui.post(new Runnable() { public void run() { try { w.removeViewImmediate(v); } catch (Exception ignored) { } } });
        }
    }

    /** 从脚本读档位表（只读一次，之后缓存） */
    private void loadOptions() {
        if (optLoaded) return;
        optLoaded = true;
        String fq = shPerf("freqs");
        String gv = shPerf("govs");
        HashMap<String, String> f = new HashMap<String, String>(), g = new HashMap<String, String>();
        ArrayList<String> fo = new ArrayList<String>(), go = new ArrayList<String>();
        fo.add("不动");
        for (String L : fq.split("\n")) {
            if (L.startsWith("freqs=")) {
                String v = L.substring(6).trim();
                int n = 0;
                for (String x : v.split(",")) {
                    if (x.isEmpty() || n >= 6) continue;
                    double ghz = safeLong(x) / 1e6;
                    String d = String.format("%.2fG", ghz);
                    if (!f.containsKey(d)) { f.put(d, x); fo.add(d); n++; }
                }
            }
        }
        go.add("不动");
        for (String L : gv.split("\n")) {
            if (L.startsWith("govs=")) for (String x : L.substring(5).trim().split(",")) if (!x.isEmpty() && !g.containsKey(x)) { g.put(x, x); go.add(x); }
        }
        HashMap<String, String> a = new HashMap<String, String>();
        a.put("不动", ""); a.put("超大核", "big"); a.put("全核", "all");
        dispToVal.put("freq", f); dispToVal.put("gov", g); dispToVal.put("aff", a);
        rowOpts.put("freq", fo); rowOpts.put("gov", go);
        rowOpts.put("aff", new ArrayList<String>(a.keySet()));
        // 刷新率档位从 WebUI 扫描的那份文件来
        String rr = "";
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new InputStreamReader(
                    new java.io.FileInputStream(new java.io.File("/data/adb/ksu_toolbox/refresh/rates"))));
            rr = r.readLine(); r.close();
        } catch (Exception ignored) { }
        ArrayList<String> ro = new ArrayList<String>();
        HashMap<String, String> rmap = new HashMap<String, String>();
        ro.add("不动"); rmap.put("不动", "");
        if (rr != null) for (String x : rr.trim().split(",")) if (!x.isEmpty() && !rmap.containsKey(x + "Hz")) { rmap.put(x + "Hz", x); ro.add(x + "Hz"); }
        dispToVal.put("rr", rmap);
        rowOpts.put("rr", ro);
    }

    private static long safeLong(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }

    private final HashMap<String, ArrayList<String>> rowOpts = new HashMap<String, ArrayList<String>>();

    /** 把当前值/候选值拼成菜单要的 rows */
    /** 菜单里显示的目标（多目标时显示"第一个 等 N 个"） */
    private String appsLabel() {
        String list = (apps == null || apps.trim().isEmpty()) ? fapp : apps.trim();
        if (list == null || list.isEmpty()) return "";
        String[] a = list.split(",");
        if (a.length == 1) return a[0].trim();
        return a[0].trim() + " 等 " + a.length + " 个";
    }

    private void buildRows() {
        rows = new ArrayList<String[]>();
        rows.add(new String[]{"freq", "CPU 锁频", disp("freq", curFreq), join(rowOpts.get("freq"))});
        rows.add(new String[]{"gov", "调速器", curGov == null || curGov.isEmpty() ? "不动" : curGov, join(rowOpts.get("gov"))});
        rows.add(new String[]{"affinity", "线程绑定", affDisp(curAff), join(rowOpts.get("aff"))});
        rows.add(new String[]{"refresh", "刷新率", curRr == null || curRr.isEmpty() || "0".equals(curRr) ? "不动" : curRr + "Hz", join(rowOpts.get("rr"))});
        if (menu != null) {
            menu.setApp(appsLabel());
            menu.setEnabled("1".equals(enabled));
            menu.setRows(rows);
        }
    }

    private String join(ArrayList<String> a) {
        if (a == null || a.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String s : a) { if (b.length() > 0) b.append(','); b.append(s); }
        return b.toString();
    }

    /** 内部值 → 显示值（频率用 GHz） */
    private String disp(String key, String v) {
        if (v == null || v.isEmpty() || "0".equals(v)) return "不动";
        HashMap<String, String> m = dispToVal.get(key);
        if (m != null) for (String d : m.keySet()) if (v.equals(m.get(d))) return d;
        return v;
    }

    private String affDisp(String v) {
        if (v == null || v.isEmpty()) return "不动";
        if (v.equals("big")) return "超大核";
        if (v.equals("all")) return "全核";
        return v;
    }

    /** 点菜单里的档位：写配置 + （如果正在生效）立刻重应用 */
    private void setAndApply(final String key, final String dispVal) {
        final HashMap<String, String> m = dispToVal.get(key.equals("affinity") ? "aff" : (key.equals("refresh") ? "rr" : key));
        final String val = (m != null && m.containsKey(dispVal)) ? m.get(dispVal) : dispVal;
        if (key.equals("enabled")) { enabled = dispVal; }
        else if (key.equals("freq")) curFreq = val;
        else if (key.equals("gov")) curGov = val;
        else if (key.equals("affinity")) curAff = val;
        else if (key.equals("refresh")) curRr = val;
        ui.post(new Runnable() { public void run() { buildRows(); } });
        new Thread(new Runnable() {
            public void run() {
                String out = shPerf("set " + key + " " + val);
                if (applied == 1) out += shPerf("apply");
                final String msg = labelOf(key) + " → " + dispVal + (out.indexOf("@@NOBIN") >= 0 ? "（失败：找不到脚本）" : "");
                logPerf("设置", msg);
                ui.post(new Runnable() { public void run() { toastMsg(msg); } });
                refreshSoon();
            }
        }).start();
    }
}
