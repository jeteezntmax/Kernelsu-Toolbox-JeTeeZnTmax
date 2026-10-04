#!/system/bin/sh
# ============================================================
#  refresh.sh — 刷新率锁定（v3.4.0）
#
#  子命令：
#    scan                  扫描本机可用档位 + 当前值 + 每个档位对应的 modeId
#    lock <hz> [modeId]    锁定并【保活】（第一次锁会先存下原值）
#    restore               停止保活并还原到锁定前的值
#    status                当前状态（k-v）
#    keepalive start|stop  只开关保活（不动当前锁定值）
#    loop                  保活循环本体（start 会 nohup 它）
#    log [n]               保活日志
#
#  怎么"锁"：
#    ① settings put system peak_refresh_rate / min_refresh_rate   ← 标准做法
#    ② cmd display set-user-preferred-display-mode <modeId>       ← 有些 ROM 认这个
#  保活：每 8 秒复查这两处，被别的组件改回去就重新写一遍，并计数（WebUI 会提示）
#
#  干跑：REFRESH_DRY=1（只打印要写什么，不动系统）
# ============================================================
BASE=${REFRESH_BASE:-/data/adb/ksu_toolbox/refresh}
CONF=$BASE/refresh.conf
PIDF=$BASE/keep.pid
LOG=$BASE/keep.log
REVERTS=$BASE/reverts
IV=${REFRESH_IV:-8}
DRY=${REFRESH_DRY:-0}

mdirs(){ mkdir -p "$BASE" 2>/dev/null; }
now(){ date '+%m-%d %H:%M:%S'; }
log(){ echo "[$(now)] $*" >> "$LOG"; }
cfg(){ [ -f "$CONF" ] && sed -n "s/^$1=//p" "$CONF" | head -n1; }
setcfg(){   # setcfg <键> <值>
    mdirs
    [ -f "$CONF" ] && grep -v "^$1=" "$CONF" > "$CONF.t" 2>/dev/null || : > "$CONF.t"
    [ -n "$2" ] && echo "$1=$2" >> "$CONF.t"
    mv "$CONF.t" "$CONF" 2>/dev/null
}
sget(){ settings get system "$1" 2>/dev/null; }
swr(){  # swr <键> <值>
    [ "$DRY" = "1" ] && { echo "[dry] settings put system $1 $2"; return 0; }
    settings put system "$1" "$2" >/dev/null 2>&1
}

# ── 扫描：档位 / modeId / 当前值 ──
do_scan(){
    # 模式表来自 dumpsys display；格式各 ROM 不一样，尽量宽容地抠
    d=$(timeout 12 dumpsys display 2>/dev/null)
    [ -z "$d" ] && d=$(timeout 12 dumpsys SurfaceFlinger --display-id 2>/dev/null)
    # 注意：一行里可能塞着好几个模式（mSupportedModes=[{...}, {...}]），
    # 所以先在每个 { 前面插换行，否则贪婪匹配只会抓到最后一个
    flat=$(echo "$d" | sed 's/{/\n{/g')
    pairs=$(echo "$flat" | sed -n 's/.*[mM]ode[Ii]d*=\([0-9][0-9]*\).*fps=\([0-9.][0-9.]*\).*/\2 \1/p')
    [ -z "$pairs" ] && pairs=$(echo "$flat" | sed -n 's/.*[^a-zA-Z]id=\([0-9][0-9]*\).*fps=\([0-9.][0-9.]*\).*/\2 \1/p')
    [ -z "$pairs" ] && pairs=$(echo "$flat" | sed -n 's/.*fps=\([0-9.][0-9.]*\).*[mM]ode[Ii]d*=\([0-9][0-9]*\).*/\1 \2/p')
    rates=""
    [ -n "$pairs" ] && rates=$(echo "$pairs" | awk '{printf "%d\n", $1+0.5}' | sort -rn | uniq | tr '\n' ',')
    [ -z "$rates" ] && rates=$(echo "$d" | grep -oE 'fps=[0-9.]+' | sed 's/fps=//' | awk '{printf "%d\n", $1+0.5}' | sort -rn | uniq | tr '\n' ',')
    echo "rates=${rates%,}"
    # fps→modeId 对照（第一个出现的优先）
    if [ -n "$pairs" ]; then
        m=$(echo "$pairs" | awk '{r=int($1+0.5); if (!(r in seen)) { seen[r]=1; printf "%d=%s ", r, $2 } }')
        echo "modes=${m% }"
    else
        echo "modes="
    fi
    # 把档位存一份给桌面 App 的悬浮窗面板用（它读不了 dumpsys，读这个文件就行）
    mdirs
    if [ -n "$rates" ]; then echo "${rates%,}" > "$BASE/rates" 2>/dev/null; fi
    echo "rates_file=${rates%,}"
    echo "cur_peak=$(sget peak_refresh_rate)"
    echo "cur_min=$(sget min_refresh_rate)"
    echo "cur_mode=$(timeout 6 cmd display get-user-preferred-display-mode 2>/dev/null | sed -n 's/.*[Ii]d*=\([0-9][0-9]*\).*/\1/p' | head -n1)"
    echo "dumpsys_ok=$([ -n "$d" ] && echo 1 || echo 0)"
}

apply_lock(){
    HZ=$(cfg hz); MODE=$(cfg mode)
    [ -z "$HZ" ] && return 1
    swr peak_refresh_rate "$HZ"
    swr min_refresh_rate "$HZ"
    if [ -n "$MODE" ]; then
        [ "$DRY" = "1" ] && echo "[dry] cmd display set-user-preferred-display-mode $MODE" \
                         || timeout 8 cmd display set-user-preferred-display-mode "$MODE" >/dev/null 2>&1
    fi
    return 0
}

keep_running(){ [ -f "$PIDF" ] || return 1; p=$(cat "$PIDF" 2>/dev/null); [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }
keep_start(){
    mdirs
    keep_running && return 0
    if [ "$DRY" = "1" ]; then echo "[dry] 会起：setsid sh $0 loop >/dev/null 2>&1 &"; return 0; fi
    setsid sh "$0" loop </dev/null >>"$LOG" 2>&1 &
    echo $! > "$PIDF"
    sleep 1
    keep_running && { log "保活已启动 (pid $(cat $PIDF))"; return 0; }
    log "!! 保活起不来"; return 1
}
keep_stop(){
    if keep_running; then kill "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null; fi
    rm -f "$PIDF"
}

case "$1" in
scan)
    do_scan
    ;;
lock)
    HZ=$2; MODE=$3
    [ -z "$HZ" ] && { echo "用法: refresh.sh lock <hz> [modeId]"; exit 2; }
    mdirs
    # 第一次锁：先把原值记下来，恢复时好还回去
    if [ -z "$(cfg orig_peak)" ]; then
        setcfg orig_peak "$(sget peak_refresh_rate)"
        setcfg orig_min "$(sget min_refresh_rate)"
        setcfg orig_mode "$(timeout 6 cmd display get-user-preferred-display-mode 2>/dev/null | sed -n 's/.*[Ii]d*=\([0-9][0-9]*\).*/\1/p' | head -n1)"
    fi
    [ -z "$MODE" ] && MODE=$(do_scan | sed -n "s/^modes=.*\b$HZ=\([0-9]*\).*/\1/p")
    setcfg enabled 1
    setcfg hz "$HZ"
    setcfg mode "$MODE"
    [ -n "$MODE" ] && setcfg did_mode 1     # 记下来：这次确实动过 preferred-mode
    apply_lock
    keep_start >/dev/null 2>&1
    echo "已锁定 ${HZ}Hz${MODE:+（modeId $MODE）}，保活=$(keep_running && echo 开 || echo 关)"
    log "lock $HZ Hz mode=$MODE"
    ;;
restore)
    HZ=$(cfg hz)
    keep_stop
    OP=$(cfg orig_peak); OM=$(cfg orig_min); OMD=$(cfg orig_mode)
    if [ -n "$OP" ]; then swr peak_refresh_rate "$OP"; else
        [ "$DRY" = "1" ] && echo "[dry] settings delete system peak_refresh_rate" || settings delete system peak_refresh_rate >/dev/null 2>&1
    fi
    if [ -n "$OM" ]; then swr min_refresh_rate "$OM"; else
        [ "$DRY" = "1" ] && echo "[dry] settings delete system min_refresh_rate" || settings delete system min_refresh_rate >/dev/null 2>&1
    fi
    ok_mode=1
    if [ -n "$OMD" ]; then
        [ "$DRY" = "1" ] && echo "[dry] cmd display set-user-preferred-display-mode $OMD" \
                         || timeout 8 cmd display set-user-preferred-display-mode "$OMD" >/dev/null 2>&1
    elif [ "$(cfg did_mode)" = "1" ]; then
        # 锁定时确实改过 preferred-mode，但原始值当时读不到 → 用"原 peak 对应的 mode"兜底
        want=${OP%%.*}
        rm2=$(do_scan 2>/dev/null | sed -n "s/^modes=.*\b$want=\([0-9]*\).*/\1/p")
        if [ -n "$rm2" ]; then
            [ "$DRY" = "1" ] && echo "[dry] cmd display set-user-preferred-display-mode $rm2（按原 peak ${want}Hz 推断）" \
                             || timeout 8 cmd display set-user-preferred-display-mode "$rm2" >/dev/null 2>&1
            log "restore：原模式未知，按原 peak=${want}Hz 推断为 modeId=$rm2 还原"
        else
            ok_mode=0
        fi
    fi
    log "restore（原值 peak=$OP min=$OM mode=$OMD，从 $HZ 还原，ok_mode=$ok_mode）"
    if [ "$ok_mode" = "1" ]; then
        rm -f "$CONF" "$REVERTS"
    else
        # 审查报告说得对：恢复没做完就别把记录删了 ✗
        echo "pending" > "$BASE/restore.pending" 2>/dev/null
        echo "⚠ 原始显示模式读不到、也推断不出来 —— 记录已保留在 $BASE/（含 restore.pending）"
        echo "   请在系统显示设置里确认一下刷新率，需要的话手动改回。"
        log "!! 恢复不完整：preferred-mode 没能还原（记录保留，peek 配置文件）"
    fi
    echo "已还原到锁定前（peak=${OP:-默认} min=${OM:-默认}）"
    ;;
status)
    echo "enabled=$(cfg enabled)"
    echo "hz=$(cfg hz)"
    echo "mode=$(cfg mode)"
    echo "orig_peak=$(cfg orig_peak)"
    echo "orig_min=$(cfg orig_min)"
    echo "orig_mode=$(cfg orig_mode)"
    echo "keep=$(keep_running && echo 1 || echo 0)"
    echo "reverts=$(cat "$REVERTS" 2>/dev/null)"
    echo "cur_peak=$(sget peak_refresh_rate)"
    echo "cur_min=$(sget min_refresh_rate)"
    echo "dry=$DRY"
    ;;
keepalive)
    case "$2" in
    start) keep_start ;;
    stop)  keep_stop; echo "保活已停（当前锁定值保持不动）" ;;
    *) echo "用法: refresh.sh keepalive start|stop" ;;
    esac
    ;;
loop)
    mdirs
    echo $$ > "$PIDF"
    trap 'rm -f "$PIDF"; exit 0' INT TERM HUP
    log "保活循环启动（间隔 ${IV}s）"
    while :; do
        [ "$(cfg enabled)" = "1" ] || { log "配置里 enabled 不是 1，退出"; break; }
        HZ=$(cfg hz); MODE=$(cfg mode)
        cp=$(sget peak_refresh_rate); cm=$(sget min_refresh_rate)
        if [ "$cp" != "$HZ" ] || [ "$cm" != "$HZ" ]; then
            n=$(cat "$REVERTS" 2>/dev/null); n=$(( ${n:-0} + 1 ))
            echo "$n" > "$REVERTS" 2>/dev/null
            log "被改回：peak=$cp min=$cm（我们要 $HZ）→ 重写（第 $n 次）"
            apply_lock
        fi
        # 保活也要看 preferred-mode（原来只看 peak/min，被别人改了不会发现 ✗）
        if [ -n "$MODE" ] && [ "$(cfg did_mode)" = "1" ]; then
            cm=$(timeout 6 cmd display get-user-preferred-display-mode 2>/dev/null | sed -n 's/.*[Ii]d*=\([0-9][0-9]*\).*/\1/p' | head -n1)
            if [ -n "$cm" ] && [ "$cm" != "$MODE" ]; then
                n=$(cat "$REVERTS" 2>/dev/null); n=$(( ${n:-0} + 1 ))
                echo "$n" > "$REVERTS" 2>/dev/null
                log "preferred-mode 被改：$cm → 重新写 $MODE（第 $n 次）"
                [ "$DRY" = "1" ] || timeout 8 cmd display set-user-preferred-display-mode "$MODE" >/dev/null 2>&1
            fi
        fi
        sleep "$IV"
    done
    ;;
log)
    tail -n "${2:-60}" "$LOG" 2>/dev/null || echo "(还没日志)"
    ;;
clearlog)
    : > "$LOG"; echo 已清空
    ;;
*)
    echo "用法: refresh.sh {scan|lock <hz> [modeId]|restore|status|keepalive start|stop|loop|log [n]|clearlog}"
    exit 2
    ;;
esac
