#!/system/bin/sh
# ============================================================
#  KSU 系统工具箱 · 开机服务
#   拉起后台：游戏加速服务 / 刷新率保活 / 本地 HTTP 服务
# ============================================================
MODDIR=${0%/*}
(
    i=0
    while [ "$i" -lt 240 ]; do
        [ "$(getprop sys.boot_completed)" = "1" ] && break
        sleep 5
        i=$((i + 5))
    done
    sleep 5

    # ── 桌面 App 的权限自愈 + 开机自动拉起悬浮窗 ──
    PKG=com.jeteezntmax.toolbox
    if pm path $PKG >/dev/null 2>&1; then
        for op in SYSTEM_ALERT_WINDOW RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND \
                  START_FOREGROUND POST_NOTIFICATION; do
            appops set $PKG $op allow >/dev/null 2>&1
        done
        # 加进省电白名单，不然后台被冻住悬浮窗就断更了
        dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
        # 开机自动把悬浮窗拉起来
        am start -n $PKG/.MonitorActivity >/dev/null 2>&1
    fi

    # 游戏加速：配置里开着就把它拉起来（不然开机后没人管自动应用）
    if [ -f /data/adb/ksu_toolbox/perf/profile.conf ]; then
        pe=$(grep -m1 '^enabled=' /data/adb/ksu_toolbox/perf/profile.conf 2>/dev/null | cut -d= -f2)
        [ "$pe" = "1" ] && am start-foreground-service -n $PKG/.PerfService >/dev/null 2>&1
    fi

    # 刷新率保活（如果之前锁过刷新率）
    RF="$MODDIR/bin/refresh.sh"
    if [ -f "$RF" ]; then
        e=""
        [ -f /data/adb/ksu_toolbox/refresh/refresh.conf ] && e=$(grep -m1 '^enabled=' /data/adb/ksu_toolbox/refresh/refresh.conf 2>/dev/null | cut -d= -f2)
        [ "$e" = "1" ] && sh "$RF" keepalive start >/dev/null 2>&1
    fi

    # 本地 HTTP 服务（设置页里开的）
    W="$MODDIR/bin/webui-server.sh"
    if [ -f "$W" ]; then
        e=""
        [ -f /data/adb/ksu_toolbox/webui.conf ] && e=$(grep -m1 '^enabled=' /data/adb/ksu_toolbox/webui.conf 2>/dev/null | cut -d= -f2)
        [ "$e" = "1" ] && sh "$W" start >/dev/null 2>&1
    fi
) >/dev/null 2>&1 &
