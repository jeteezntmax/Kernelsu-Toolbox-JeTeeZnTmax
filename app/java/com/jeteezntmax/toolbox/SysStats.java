package com.jeteezntmax.toolbox;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 迷你监视器的数据源。
 *
 * 分工：
 *   · CPU 利用率 / 温度 / 电流 / 电压 / 内存 / 电池 / GPU —— 一次 `su -c` 捞回来
 *     （App 跑在 u0_aXXX，不是 root，SELinux 不让它直接读 power_supply/*）
 *   · 帧率 —— 由 MonitorService 用 Choreographer 数帧，不在这里
 *   · 时间 —— 本地算，不占采样
 *
 * v3.1.0 新增：内存占用率、电池电量、GPU 占用/频率（候选路径探测，读不到就 --）。
 */
public class SysStats {

    public int cores = 8;
    public double cpuUsage = -1;     // 0~100
    public double fps = -1;          // 由外面塞进来
    public double powerW = -1;
    public double tempC = -1;
    public double ramPct = -1;       // 内存占用率
    public double batPct = -1;       // 电池电量
    public double gpuPct = -1;       // GPU 占用率（能拿到就用它）
    public double gpuMhz = -1;       // GPU 频率（占用率拿不到就退而显示频率）
    public String cfg = "";          // 用户在 WebUI 里设的配色/字号/显示项
    public String rates = "";        // 扫描到的刷新率档位（"144,120,90,60"）—— 悬浮窗面板用
    public String lockHz = "";       // 当前锁定的刷新率（空 = 没锁）

    /* ---------- 一次性采集脚本（不含帧率、不含时间） ---------- */
    private static final String SCRIPT =
        "S=/sys/class/power_supply; " +
        "echo STAT $(head -n1 /proc/stat); " +
        "T=$(cat $S/battery/temp 2>/dev/null); [ -z \"$T\" ] && T=$(cat $S/bms/temp 2>/dev/null); " +
        "echo TEMP $T; " +
        "C=$(cat $S/battery/current_now 2>/dev/null); [ -z \"$C\" ] && C=$(cat $S/bms/current_now 2>/dev/null); " +
        /* 双电芯：内核报的电流是【单芯值】→ 在这里统一 ×2（电池页 / 监视器 / HUD 共用同一份设置，
           都读 /data/adb/ksu_toolbox/batt.conf。这样就不会出现"页面对了、监视器少一半"✗）*/
        "D=$(cut -d= -f2 /data/adb/ksu_toolbox/batt.conf 2>/dev/null | head -n1); " +
        "[ \"$D\" = \"1\" ] && [ -n \"$C\" ] && C=$((C * 2)); " +
        "echo CUR $C; " +
        "V=$(cat $S/battery/voltage_now 2>/dev/null); [ -z \"$V\" ] && V=$(cat $S/bms/voltage_now 2>/dev/null); " +
        "echo VOLT $V; " +
        /* 内存：纯内建循环，一个外部命令都不 fork */
        "M=; A=; while IFS= read -r L; do case \"$L\" in MemTotal:*) set -- ${L#MemTotal:}; M=$1;; MemAvailable:*) set -- ${L#MemAvailable:}; A=$1;; esac; done < /proc/meminfo; " +
        "echo MEM $M $A; " +
        "B=$(cat $S/battery/capacity 2>/dev/null); [ -z \"$B\" ] && B=$(cat $S/bms/capacity 2>/dev/null); " +
        "echo BAT $B; " +
        /* GPU：先找占用率（高通 kgsl → MTK → Mali），单位统一成 % */
        "G=; for f in /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/kernel/gpu/gpu_utilization /sys/class/misc/mali0/device/utilization /sys/class/kgsl/kgsl-3d0/gpu_busy; do " +
        "  if [ -z \"$G\" ] && [ -r \"$f\" ]; then IFS= read -r G < \"$f\"; fi; done; " +
        "G=${G%% *}; G=${G%%%}; G=${G%%.*}; " +
        "if [ -z \"$G\" ] && [ -r /sys/class/kgsl/kgsl-3d0/gpubusy ]; then set -- $(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null); " +
        "  [ -n \"$2\" ] && [ \"$2\" -gt 0 ] 2>/dev/null && G=$(( $1 * 100 / $2 )); fi; " +
        "echo GPU $G; " +
        /* GPU 频率（Hz → MHz），占用率拿不到时显示它 */
        "U=; for f in /sys/class/kgsl/kgsl-3d0/gpuclk /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/devfreq/gpufreq/cur_freq /sys/kernel/gpu/gpu_clock; do " +
        "  if [ -z \"$U\" ] && [ -r \"$f\" ]; then IFS= read -r U < \"$f\"; fi; done; " +
        "U=${U%% *}; [ -n \"$U\" ] && U=$(( U / 1000000 )) 2>/dev/null; " +
        "echo GPUM $U; " +
        "echo CFG $(cat /data/adb/ksu_toolbox/monitor.conf 2>/dev/null | tr \"\\n\" \";\"); " +
        /* 刷新率档位 + 当前锁定档（点悬浮窗的 FPS 会用到，读文件就行不用跑 dumpsys） */
        "echo RATES $(cat /data/adb/ksu_toolbox/refresh/rates 2>/dev/null | tr -d \"\\r\\n\"); " +
        "echo RRHZ $(sed -n 's/^hz=//p' /data/adb/ksu_toolbox/refresh/refresh.conf 2>/dev/null | head -n1)";

    /* ---------- CPU 利用率：两次差值 ---------- */
    private long prevTotal = -1, prevIdle = -1;

    private void parseStat(String line) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 6) return;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i < f.length && i < 9; i++) total += Long.parseLong(f[i]);
            idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);   // idle + iowait
        } catch (Exception e) { return; }
        if (prevTotal > 0 && total > prevTotal) {
            long dt = total - prevTotal, di = idle - prevIdle;
            if (dt > 0) {
                double u = (dt - di) * 100.0 / dt;
                if (u < 0) u = 0;
                if (u > 100) u = 100;
                cpuUsage = u;
            }
        }
        prevTotal = total;
        prevIdle = idle;
    }

    /* ---------- 一次采完 ---------- */
    private long curUA = Long.MIN_VALUE, voltUV = Long.MIN_VALUE;

    public void sample() {
        Process p = null;
        try {
            p = new ProcessBuilder(new String[]{"su", "-c", SCRIPT})
                    .redirectErrorStream(true).start();

            final Process fp = p;
            Thread et = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(new InputStreamReader(fp.getErrorStream()), 256);
                        while (r.readLine() != null) { /* 排空 stderr，免得管道堵住 */ }
                    } catch (Exception ignored) { }
                }
            });
            et.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 512);
            String line;
            long t0 = System.currentTimeMillis();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("STAT ")) parseStat(line.substring(5));
                else if (line.startsWith("TEMP ")) {
                    double c = parseTemp(line.substring(5).trim());
                    if (c >= 0) tempC = c;
                }
                else if (line.startsWith("CUR ")) curUA = parseLong(line.substring(4).trim(), Long.MIN_VALUE);
                else if (line.startsWith("VOLT ")) voltUV = parseLong(line.substring(5).trim(), Long.MIN_VALUE);
                else if (line.startsWith("MEM ")) parseMem(line.substring(4).trim());
                else if (line.startsWith("BAT ")) {
                    double b = parseNum(line.substring(4).trim(), -1);
                    if (b >= 0 && b <= 100) batPct = b;
                }
                else if (line.startsWith("GPUM ")) {
                    double u = parseNum(line.substring(5).trim(), -1);
                    if (u > 0) gpuMhz = u;
                }
                else if (line.startsWith("GPU ")) {
                    double g = parseNum(line.substring(4).trim(), -1);
                    if (g >= 0 && g <= 100) gpuPct = g;
                }
                else if (line.startsWith("CFG ")) cfg = line.substring(4).trim();
                else if (line.startsWith("RATES ")) rates = line.substring(6).trim();
                else if (line.startsWith("RRHZ ")) lockHz = line.substring(5).trim();
                if (System.currentTimeMillis() - t0 > 6000) break;
            }
            p.waitFor();
            et.join(400);
            computePower();
        } catch (Exception e) {
            // 读不到就保持上一次的值，不清零
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
    }

    /** "MemTotal MemAvailable"（kB）→ 占用率 */
    private void parseMem(String s) {
        String[] f = s.split("\\s+");
        if (f.length < 2) return;
        long tot = parseLong(f[0], -1), avail = parseLong(f[1], -1);
        if (tot <= 0) return;
        double used = tot - (avail > 0 ? avail : 0);
        if (used < 0) used = 0;
        double pc = used * 100.0 / tot;
        if (pc >= 0 && pc <= 100) ramPct = pc;
    }

    private void computePower() {
        if (curUA == Long.MIN_VALUE || voltUV == Long.MIN_VALUE) return;
        double a = Math.abs(curUA) > 20000 ? Math.abs(curUA) / 1e6 : Math.abs(curUA) / 1e3;
        double v = voltUV > 100000 ? voltUV / 1e6 : voltUV / 1e3;
        double w = a * v;
        if (w >= 0 && w < 200) powerW = w;
    }

    private static double parseTemp(String s) {
        long v = parseLong(s, Long.MIN_VALUE);
        if (v == Long.MIN_VALUE) return -1;
        double c = v > 100 ? v / 10.0 : v;      // 常见是十分之一度
        return (c > -20 && c < 120) ? c : -1;
    }
    private static double parseNum(String s, double def) {
        if (s == null) return def;
        try {
            int i = 0, n = s.length();
            while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-' && s.charAt(i) != '.') i++;
            int j = i;
            if (j < n && s.charAt(j) == '-') j++;
            while (j < n && (Character.isDigit(s.charAt(j)) || s.charAt(j) == '.')) j++;
            if (j == i) return def;
            double d = Double.parseDouble(s.substring(i, j));
            return d;
        } catch (Exception e) { return def; }
    }
    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            int i = 0, n = s.length();
            while (i < n && !Character.isDigit(s.charAt(i)) && s.charAt(i) != '-') i++;
            int j = i;
            if (j < n && s.charAt(j) == '-') j++;
            while (j < n && Character.isDigit(s.charAt(j))) j++;
            if (j == i) return def;
            return Long.parseLong(s.substring(i, j));
        } catch (Exception e) { return def; }
    }
}
