#!/system/bin/sh
# ============================================================
#  本地 HTTP 服务  ·  把 WebUI 挂到 127.0.0.1:<port>
# ------------------------------------------------------------
#  用 KernelSU 自带的 busybox httpd，只监听回环地址。
#  · 浏览器打开 http://127.0.0.1:<port>/ 就能看到界面
#  · 真实数据来自 /api/<任务名>.txt —— 由 bin/collect.sh 后台循环生成
#  · httpd 不做 CGI：它只能读静态文件，执行不了任何东西
#
#  用法：webui-server.sh start|stop|status|restart|token
# ============================================================
DIR=/data/adb/ksu_toolbox
WWW=$DIR/www
CONF=$DIR/webui.conf
TOKEN_FILE=$DIR/webui.token
PIDFILE=$DIR/webui.pid
LOG=$DIR/webui.log
MODDIR=${0%/*}/..

mkdir -p "$DIR"

bb() {
    # 各 root 方案的 busybox 路径都不一样，全试一遍 ✓
    for b in /data/adb/ksu/bin/busybox /data/adb/ksud/bin/busybox \
             /data/adb/magisk/busybox /data/adb/ap/bin/busybox \
             /data/adb/apd/bin/busybox /system/bin/busybox /system/xbin/busybox \
             /debug_ramdisk/busybox; do
        [ -x "$b" ] && { echo "$b"; return 0; }
    done
    b=$(command -v busybox 2>/dev/null)
    [ -n "$b" ] && [ -x "$b" ] && { echo "$b"; return 0; }
    # 最后兜底：在 /data/adb 下找（不要太深，几十毫秒）
    b=$(find /data/adb -maxdepth 4 -name busybox -type f 2>/dev/null | head -n 1)
    [ -n "$b" ] && [ -x "$b" ] && { echo "$b"; return 0; }
    return 1
}

# ★ 判断 busybox 有没有 httpd 这个 applet ★
# 以前用 `$BB httpd --help` 看退出码 —— busybox 的 applet 帮助打到 stderr 且退出码非 0 ✗
# 结果：明明有 httpd，却被判成"不可用" ✓（作者真机踩到的就是这个）
have_httpd() {
    _bb=$1
    [ -n "$_bb" ] && [ -x "$_bb" ] || return 1
    # 正规做法：列出 applet 清单（老 busybox 没有 --list → 退化为直接看帮助文本）
    if "$_bb" --list >/dev/null 2>&1; then
        "$_bb" --list 2>/dev/null | grep -qx httpd && return 0
        return 1
    fi
    "$_bb" httpd --help 2>&1 | grep -qi "httpd\|usage" && return 0
    return 1
}

getport() {
    p=""
    [ -f "$CONF" ] && p=$(grep -m1 '^port=' "$CONF" 2>/dev/null | cut -d= -f2)
    case "$p" in ''|*[!0-9]*) p=8765 ;; esac
    echo "$p"
}

gen_token() {
    if [ ! -s "$TOKEN_FILE" ]; then
        T=$(od -An -N16 -tx1 < /dev/urandom 2>/dev/null | tr -d ' \n')
        [ -z "$T" ] && T=$(date +%s)$(date +%N)
        printf '%s' "$T" > "$TOKEN_FILE"
        chmod 600 "$TOKEN_FILE"
    fi
    cat "$TOKEN_FILE"
}

# busybox httpd 会守护化，pidof/pgrep 不一定抓得到 —— 直接扫 /proc。
# 注意（第三方审查提的）：不能只按"命令行里有 httpd -p 127.0.0.1"就认，
# 那会把【别人】的 busybox httpd 也当成自己的杀掉 ✗。
# 所以：① 优先用 PID 文件（并核对 exe 与启动时间，防 PID 复用）
#       ② 退回扫 /proc 时，必须同时匹配【我们自己的 serve 目录】
serve_dir() {
    d=""
    [ -f "$CONF" ] && d=$(grep -m1 '^dir=' "$CONF" 2>/dev/null | cut -d= -f2)
    [ -n "$d" ] && { echo "$d"; return; }
    echo "/data/adb/ksu_toolbox/webroot"
}

find_httpd() {
    d0=$(serve_dir 2>/dev/null)
    for d in /proc/[0-9]*; do
        [ -r "$d/cmdline" ] || continue
        cl=$(tr '\0' ' ' < "$d/cmdline" 2>/dev/null)
        case "$cl" in
            *httpd*"127.0.0.1"*)
                # 命令里必须带我们的目录才认（否则可能是别人的服务）
                [ -n "$d0" ] && case "$cl" in *"$d0"*) echo "${d#/proc/}"; return 0 ;; esac
                ;;
        esac
    done
    return 1
}

# 我们自己的 busybox 路径 + 进程启动时刻（/proc/pid/stat 第 22 栏）
proc_ident() {   # proc_ident <pid>  →  "<exe> <starttime>"
    [ -n "$1" ] || return 1
    exe=$(readlink "/proc/$1/exe" 2>/dev/null)
    st=$(awk '{print $22}' "/proc/$1/stat" 2>/dev/null)
    [ -n "$exe" ] && echo "$exe $st"
}
pid_ours() {     # pid_ours <pid> <exe> <starttime>  → 是不是我们记下的那个进程
    [ -n "$1" ] && [ -d "/proc/$1" ] || return 1
    cur=$(proc_ident "$1") || return 1
    [ "$cur" = "$2 $3" ]
}

# 别用 wget 探活 —— 那会把 180KB 的首页整个拉一遍，
# 而 status 是被 collect.sh 每几秒调一次的。直接查内核的监听表。
probe_port() {
    P=$(getport)
    h=$(printf '%04X' "$P" 2>/dev/null)
    [ -n "$h" ] || return 1
    grep -qi ":$h " /proc/net/tcp 2>/dev/null
}

is_running() {
    if [ -s "$PIDFILE" ]; then
        p=$(cat "$PIDFILE" 2>/dev/null | tr -d ' \r\n')
        case "$p" in ''|*[!0-9]*) ;; *) kill -0 "$p" 2>/dev/null && return 0 ;; esac
    fi
    probe_port
}

stop_it() {
    # 只动"确认是自己"的进程：PID 文件里的 exe + 启动时间要对得上
    if [ -s "$PIDFILE" ]; then
        set -- $(cat "$PIDFILE" 2>/dev/null)
        if pid_ours "$1" "$2" "$3"; then
            kill "$1" 2>/dev/null; sleep 1
            pid_ours "$1" "$2" "$3" && kill -9 "$1" 2>/dev/null
        fi
        rm -f "$PIDFILE"
    fi
    # 兜底：扫 /proc 时也必须带我们自己的目录（见 find_httpd）
    for p in $(find_httpd); do
        kill "$p" 2>/dev/null
    done
    sleep 1
    for p in $(find_httpd); do
        kill -9 "$p" 2>/dev/null
    done
    echo "stopped"
}

publish() {
    rm -rf "$WWW" 2>/dev/null
    mkdir -p "$WWW"
    cp -rf "$MODDIR/webroot/." "$WWW/" 2>/dev/null
    find "$WWW" -type d -exec chmod 755 {} \; 2>/dev/null
    find "$WWW" -type f -exec chmod 644 {} \; 2>/dev/null
    gen_token > /dev/null
}

collector_loop() {
    while true; do
        if [ -f "$DIR/webui.conf" ]; then
            e=$(grep -m1 '^enabled=' "$DIR/webui.conf" 2>/dev/null | cut -d= -f2)
            [ "$e" = "1" ] || { sleep 20; continue; }
        else
            break
        fi
        sh "$MODDIR/bin/collect.sh" "$WWW/api" >/dev/null 2>&1
        P=$(getport)
        if [ -d "$WWW/api" ]; then
            T=$(date +%s)
            printf '{"ok":1,"t":%s}\n' "$T" > "$WWW/api/_alive.json" 2>/dev/null
        fi
        sleep 6
    done
}

start_it() {
    BB=$(bb)
    if [ -z "$BB" ] || [ ! -x "$BB" ]; then
        echo "error=no-busybox"
        return 1
    fi
    if ! have_httpd "$BB"; then
        echo "error=busybox-no-httpd"
        echo "busybox=$BB"
        return 1
    fi
    stop_it
    publish
    # 先把快照生成一次，再去起服务
    sh "$MODDIR/bin/collect.sh" "$WWW/api" >/dev/null 2>&1
    ( collector_loop ) >/dev/null 2>&1 &
    P=$(getport)
    printf '[%s] start on 127.0.0.1:%s\n' "$(date '+%F %T')" "$P" >> "$LOG"
    $BB httpd -p "127.0.0.1:$P" -h "$WWW" >> "$LOG" 2>&1
    sleep 1
    pid=$(find_httpd 2>/dev/null | head -n 1)
    [ -z "$pid" ] && pid=$($BB pidof httpd 2>/dev/null | awk '{print $1}')
    if [ -n "$pid" ]; then printf '%s' "$pid" > "$PIDFILE"; fi
    if is_running; then
        echo "started=1"
        echo "port=$P"
        echo "pid=${pid:-?}"
    else
        echo "error=not-running"
        tail -n 3 "$LOG" 2>/dev/null
        return 1
    fi
}

status_it() {
    P=$(getport)
    echo "http_port=$P"
    echo "http_token=$(cat "$TOKEN_FILE" 2>/dev/null | tr -d ' \r\n')"
    if is_running; then
        echo "http_running=1"
        echo "http_pid=$(cat "$PIDFILE" 2>/dev/null | tr -d ' \r\n')"
    else
        echo "http_running=0"
        echo "http_pid="
    fi
    BB=$(bb)
    if have_httpd "$BB"; then
        echo "http_busybox=1"
    else
        echo "http_busybox=0"
    fi
    echo "http_bb=${BB:-}"
    echo "http_root=$WWW"
}

case "$1" in
    start|restart) start_it ;;
    stop)    stop_it; echo "stopped=1" ;;
    status)  status_it ;;
    token)   gen_token; echo ;;
    publish) publish; echo "published=1" ;;
    *)
        echo "usage: $0 start|stop|restart|status|token|publish"
        exit 1
        ;;
esac
