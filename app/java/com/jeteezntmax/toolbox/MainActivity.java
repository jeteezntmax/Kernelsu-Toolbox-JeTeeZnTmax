package com.jeteezntmax.toolbox;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * JeTeezNtmax · 独立 WebUI 壳
 * ---------------------------------------------------------------
 * KernelSU 的 WebView 里有 window.ksu，这个 App 自己实现一个同名接口，
 * 所以打开就是完整功能，不只是只读预览。
 *
 *   1. 要一次 root，把模块的 webroot 拷到 App 私有目录（/data/adb 普通 App 读不了）
 *   2. 用 shouldInterceptRequest 把私有目录挂到一个虚拟 https 域名下
 *      —— 这样 localStorage / fetch 都正常，也不会踩 file:// 的限制
 *   3. addJavascriptInterface(this, "ksu") 提供 exec / toast / moduleInfo …
 *
 * 注意：源码里刻意不用 lambda / 方法引用 —— 我们是对着 API 25 的
 * android.jar 编译的，那里没有 java.lang.invoke.MethodHandles，
 * 用 lambda 会让 javac 报 CompletionFailure。
 */
public class MainActivity extends Activity {

    private static final String ORIGIN_HOST = "appassets.androidplatform.net";
    private static final String ORIGIN = "https://" + ORIGIN_HOST;
    private static final String[] MOD_DIRS = {
            "/data/adb/modules/ksu_toolbox",
            "/data/adb/modules_update/ksu_toolbox"
    };

    private WebView web;
    private File webRoot;
    private TextView splash;
    private String modProp = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        webRoot = new File(getFilesDir(), "webroot");

        FrameLayout box = new FrameLayout(this);
        box.setBackgroundColor(Color.parseColor("#07070a"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#07070a"));
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        splash = new TextView(this);
        splash.setText("正在准备…");
        splash.setTextColor(Color.parseColor("#8a8d98"));
        splash.setTextSize(13f);
        splash.setGravity(Gravity.CENTER);
        splash.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        box.addView(web);
        box.addView(splash);
        setContentView(box);

        web.addJavascriptInterface(new KsuBridge(), "ksu");
        web.setWebViewClient(new LocalClient());

        new Thread(new Runnable() {
            public void run() { boot(); }
        }).start();
    }

    /* ================= 启动流程 ================= */
    private void boot() {
        // 1. 要 root
        Result r = su("id -u");
        if (!"0".equals(r.out.trim())) {
            say("没有拿到 root 权限。\n\n请在 KernelSU 管理器里给 JeTeeZnTmax 授权，然后重新打开。");
            return;
        }

        // 2. 找模块目录（准备版也认）
        String dir = null;
        for (int i = 0; i < MOD_DIRS.length; i++) {
            if (su("[ -f " + MOD_DIRS[i] + "/module.prop ] && echo ok").out.indexOf("ok") >= 0) {
                dir = MOD_DIRS[i];
                break;
            }
        }
        if (dir == null) {
            say("没找到模块。\n\n请先安装 JeTeezNtmax 模块（/data/adb/modules/ksu_toolbox）。");
            return;
        }

        // 3. 读 module.prop，给页面里的 moduleInfo() 用
        modProp = su("cat " + dir + "/module.prop").out;

        // 4. 版本没变就不重复拷
        String ver = prop(modProp, "version") + "|" + prop(modProp, "versionCode");
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        boolean need = !ver.equals(sp.getString("synced", ""))
                || !new File(webRoot, "index.html").exists();

        if (need) {
            say("正在同步界面资源…");
            String uid = String.valueOf(android.os.Process.myUid());
            String c = webRoot.getAbsolutePath();
            // 拷完必须把属主改成 App 自己 —— su 是 root，拷出来是 root:root，WebView 读不到
            String cmd = "rm -rf " + c
                    + " && cp -r " + dir + "/webroot " + c
                    + " && chown -R " + uid + ":" + uid + " " + c
                    + " && chmod -R 700 " + c
                    + " && echo __DONE__";
            Result cp = su(cmd);
            if (cp.out.indexOf("__DONE__") < 0) {
                say("拷贝界面失败：\n" + cp.out + "\n" + cp.err);
                return;
            }
            sp.edit().putString("synced", ver).apply();
        }

        if (!new File(webRoot, "index.html").exists()) {
            say("界面资源是空的，试试卸载重装模块。");
            return;
        }

        runOnUiThread(new Runnable() {
            public void run() {
                splash.setVisibility(View.GONE);
                web.loadUrl(ORIGIN + "/index.html");
            }
        });
    }

    private void say(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() { splash.setText(msg); }
        });
    }

    /* ================= 虚拟域名 → 私有目录 ================= */
    private class LocalClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u == null || !ORIGIN_HOST.equals(u.getHost())) return null;

            String path = u.getPath();
            if (path == null || path.length() == 0 || "/".equals(path)) path = "/index.html";

            try {
                File f = new File(webRoot, path);
                String canon = f.getCanonicalPath();
                if (!canon.startsWith(webRoot.getCanonicalPath())) return notFound();  // 防目录穿越
                if (!f.exists() || f.isDirectory()) return notFound();
                return new WebResourceResponse(mime(path), "utf-8", 200, "OK",
                        new HashMap<String, String>(), new FileInputStream(f));
            } catch (Exception e) {
                return notFound();
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (u != null && ORIGIN_HOST.equals(u.getHost())) return false;
            // 页面里点外链走系统浏览器，别在 App 里开
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }
    }

    private WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String p) {
        String l = p.toLowerCase();
        if (l.endsWith(".html") || l.endsWith(".htm")) return "text/html";
        if (l.endsWith(".js") || l.endsWith(".mjs")) return "application/javascript";
        if (l.endsWith(".css")) return "text/css";
        if (l.endsWith(".json")) return "application/json";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".jpg") || l.endsWith(".jpeg")) return "image/jpeg";
        if (l.endsWith(".webp")) return "image/webp";
        if (l.endsWith(".ico")) return "image/x-icon";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".woff")) return "font/woff";
        if (l.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    /* ================= ksu 桥 ================= */
    private class KsuBridge {

        @JavascriptInterface
        public void exec(final String cmd, final String options, final String callback) {
            new Thread(new Runnable() {
                public void run() {
                    Result r = su(cmd);
                    final String js = "window[" + JSONObject.quote(callback) + "]("
                            + r.code + "," + JSONObject.quote(r.out) + "," + JSONObject.quote(r.err) + ")";
                    web.post(new Runnable() {
                        public void run() {
                            try { web.evaluateJavascript(js, null); } catch (Exception ignored) { }
                        }
                    });
                }
            }).start();
        }

        @JavascriptInterface
        public String moduleInfo() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", prop(modProp, "id"));
                o.put("name", prop(modProp, "name"));
                o.put("version", prop(modProp, "version"));
                o.put("versionCode", prop(modProp, "versionCode"));
                o.put("author", prop(modProp, "author"));
                o.put("description", prop(modProp, "description"));
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** 系统栏交回系统管，这里不折腾 —— 页面用 env(safe-area-inset-*) 自适应 */
        @JavascriptInterface
        public void fullScreen(boolean on) { }

        @JavascriptInterface
        public void enableEdgeToEdge(boolean on) { }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(new Runnable() {
                public void run() { finish(); }
            });
        }

        @JavascriptInterface
        public String listPackages(String type) {
            Result r = su("pm list packages " + (type == null ? "" : type));
            JSONArray a = new JSONArray();
            String[] lines = r.out.split("\n");
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.startsWith("package:")) a.put(line.substring(8));
            }
            return a.toString();
        }

        /** 应用名 / 版本 / 是否系统应用 —— 直接用 PackageManager，比 shell 快得多 */
        @JavascriptInterface
        public String getPackagesInfo(String packages) {
            JSONArray out = new JSONArray();
            PackageManager pm = getPackageManager();
            List<String> names = parseNames(packages);
            for (int i = 0; i < names.size(); i++) {
                String p = names.get(i);
                JSONObject o = new JSONObject();
                try {
                    o.put("packageName", p);
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    o.put("appLabel", String.valueOf(pm.getApplicationLabel(ai)));
                    o.put("isSystem", (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    o.put("uid", ai.uid);
                    PackageInfo pi = pm.getPackageInfo(p, 0);
                    o.put("versionName", pi.versionName == null ? "" : pi.versionName);
                    o.put("versionCode", pi.versionCode);
                } catch (Exception e) {
                    try {
                        o.put("packageName", p);
                        o.put("appLabel", "");
                        o.put("versionName", "");
                        o.put("versionCode", 0);
                        o.put("isSystem", false);
                        o.put("uid", 0);
                    } catch (Exception ignored) { }
                }
                out.put(o);
            }
            return out.toString();
        }

        /**
         * 应用图标。页面上一次要几十个，分批来 —— 一次全要数据量太大。
         * 返回 {包名: "data:image/png;base64,…"}
         */
        @JavascriptInterface
        public String getIcons(String packages) {
            JSONObject out = new JSONObject();
            PackageManager pm = getPackageManager();
            List<String> names = parseNames(packages);
            for (int i = 0; i < names.size(); i++) {
                String p = names.get(i);
                try {
                    Drawable d = pm.getApplicationIcon(p);
                    int SZ = 72;
                    Bitmap bmp = Bitmap.createBitmap(SZ, SZ, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    d.setBounds(0, 0, SZ, SZ);
                    d.draw(c);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    bmp.recycle();
                    out.put(p, "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                } catch (Exception ignored) { }
            }
            return out.toString();
        }

        /** 给自检页用的：看看 PackageManager 这条路通不通 */
        @JavascriptInterface
        public String probe() {
            JSONObject o = new JSONObject();
            try {
                PackageManager pm = getPackageManager();
                o.put("api", android.os.Build.VERSION.SDK_INT);
                o.put("installed", pm.getInstalledPackages(0).size());
                ApplicationInfo ai = pm.getApplicationInfo("com.android.settings", 0);
                o.put("sampleLabel", String.valueOf(pm.getApplicationLabel(ai)));
                o.put("hasIcons", true);
            } catch (Exception e) {
                try { o.put("err", String.valueOf(e)); } catch (Exception ignored) { }
            }
            return o.toString();
        }
    }

    /** 前端传的是 JSON 数组；万一格式不对，退化成按逗号/空白拆 */
    private static List<String> parseNames(String packages) {
        List<String> out = new ArrayList<String>();
        if (packages == null) return out;
        try {
            JSONArray in = new JSONArray(packages);
            for (int i = 0; i < in.length(); i++) {
                String s = in.optString(i);
                if (s != null && s.length() > 0) out.add(s);
            }
            return out;
        } catch (Exception ignored) { }
        String[] parts = packages.split("[,\\s\\[\\]\"]+");
        for (int i = 0; i < parts.length; i++) {
            String s = parts[i].trim();
            if (s.length() > 0 && s.indexOf('/') < 0) out.add(s);
        }
        return out;
    }

    private static String prop(String prop, String key) {
        String[] lines = prop.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return "";
    }

    /* ================= root 执行 ================= */
    private static class Result {

        boolean timedOut;
        int code = -1;
        String out = "";
        String err = "";
    }

    /** 不同 root 方案 su 的位置不一定一样，探一次缓存下来 */
    private String suPath = null;

    private synchronized String suBin() {
        if (suPath != null) return suPath;
        String[] cands = {"su", "/system/bin/su", "/system/xbin/su", "/data/adb/ksu/bin/su"};
        for (int i = 0; i < cands.length; i++) {
            try {
                Process p = new ProcessBuilder(new String[]{cands[i], "-c", "id -u"}).start();
                String o = readAll(p.getInputStream());
                int c = p.waitFor();
                if (c == 0 && "0".equals(o.trim())) { suPath = cands[i]; return suPath; }
            } catch (Exception ignored) { }
        }
        suPath = "su";
        return suPath;
    }

    private Result su(String cmd) {
        Result r = new Result();
        Process p = null;
        try {
            p = new ProcessBuilder(new String[]{suBin(), "-c", cmd}).start();
            final Process fp = p;
            final StringBuilder eb = new StringBuilder();
            Thread et = new Thread(new Runnable() {
                public void run() {
                    try { eb.append(readAll(fp.getErrorStream())); } catch (Exception ignored) { }
                }
            });
            et.start();
            final StringBuilder ob = new StringBuilder();
            Thread ot = new Thread(new Runnable() {
                public void run() {
                    try { ob.append(readAll(fp.getInputStream())); } catch (Exception ignored) { }
                }
            });
            ot.start();
            boolean done = false;
            try { done = p.waitFor(SU_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception ignored) { }
            if (!done) {                       // 超时：杀掉，别让界面永远等下去
                try { p.destroyForcibly(); } catch (Exception ignored) { }
                r.timedOut = true;
            }
            ot.join(1500);
            et.join(1500);
            r.out = ob.toString();
            r.code = done ? p.exitValue() : -1;
            r.err = eb.toString();
        } catch (Exception e) {
            r.err = String.valueOf(e.getMessage());
        } finally {
            if (p != null) { try { p.destroy(); } catch (Exception ignored) { } }
        }
        return r;
    }

    /**
     * 读干一个流。
     * 关键：超过上限【也要继续读】——以前是 break 掉，子进程写满管道就永远阻塞，
     * 而这边在 waitFor() 等它退出 → 双方互等，界面假死 ✗（审查报告点出的 P0，核实成立）。
     */
    private static final long SU_TIMEOUT_SEC = 25;   // 单条命令最长等 25 秒（超时杀掉）

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        long total = 0;
        int k;
        while ((k = in.read(buf)) > 0) {
            total += k;
            if (sb.length() < 4 * 1024 * 1024) {          // 只保留前 4 MiB
                sb.append(new String(buf, 0, k, "UTF-8"));
            }
            // 超出部分照读不误（丢掉就行），保证子进程不卡在写管道上
            if (total > 64L * 1024 * 1024) break;         // 极端情况才真放弃
        }
        return sb.toString();
    }

    /* ================= 生命周期 ================= */
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try {
                web.loadUrl("about:blank");
                web.destroy();
            } catch (Exception ignored) { }
            web = null;
        }
        super.onDestroy();
    }
}
