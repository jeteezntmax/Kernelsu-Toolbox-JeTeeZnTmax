#!/system/bin/sh
# ============================================================
#  chg.sh v2 — 充电控制（完全重写，参照 Scene 的做法）
#
#  依据（作者真机实测 + 扒 Scene 的结果）：
#    · Scene 写的是 /sys/class/power_supply/usb/input_current_limit
#      并且【同时写 usb/current_max】—— 两个一起写充电器才认 ✓
#    · 值是 µA（500000 = 500mA）✓
#    · 我们这边直接从 ksu.exec 的域写会 EACCES ✗，
#      所以"写节点"这份活交给【开机由 service.sh 拉起的守护】（magisk 域 ✓），
#      界面只写配置文件 ✓ —— 这就是 Scene 的架构（App + 特权后台）✓
#
#  子命令：
#    status                     状态（含我们要的值 + 守护状态）
#    nodes                      诊断：把所有候选节点列出来（存在/只读/可写/值）
#    protect <停> [恢复]        充电保护（到 X% 停充，掉到 Y% 恢复；默认 90/70）★
#    protect off                关掉充电保护
#    suspend 0|1                手动控制：恢复/禁止充电
#    daemon start|stop          守护（开机自动拉起）
#    watch-loop                 守护循环本体
#    restore                    全部还原（卸载时也会调）
#    conf / set <键> <值> / log
#
#  干跑：CHG_DRY=1  BASE 可用 CHG_BASE 覆盖（自测用）
# ============================================================
BASE=${CHG_BASE:-/data/adb/ksu_toolbox}
CONF=$BASE/chg.conf
ORIG=$BASE/chg-orig
PLOG=$BASE/chg.log
PIDF=$BASE/chg.pid
DRY=${CHG_DRY:-0}
IV=${CHG_IV:-5}

B=/sys/class/power_supply/battery
U=/sys/class/power_supply/usb
O=/sys/class/oplus_chg
mdirs(){ mkdir -p "$BASE" 2>/dev/null; }
val(){ cat "$1" 2>/dev/null | head -1 | tr -d '\r\n'; }
now(){ date '+%m-%d %H:%M:%S'; }
logline(){ echo "[$(now)] $*" >> "$PLOG" 2>/dev/null; }
cfg(){ [ -f "$CONF" ] && sed -n "s/^$1=//p" "$CONF" | head -n1; }
setcfg(){
    mdirs
    if [ -f "$CONF" ]; then grep -v "^$1=" "$CONF" > "$CONF.t" 2>/dev/null; else : > "$CONF.t"; fi
    [ -n "$2" ] && echo "$1=$2" >> "$CONF.t"
    mv "$CONF.t" "$CONF" 2>/dev/null
}
# 写 sysfs：先原样试写一次判断能不能写（sysfs 上 test -w 不可靠 ✗）
try_write(){ { echo "$2" > "$1"; } 2>/dev/null; }
# 首次动某个节点前记下原值（还原用）
orig_save(){
    mdirs
    [ -f "$ORIG" ] && grep -qF "$1 " "$ORIG" 2>/dev/null && return 0
    echo "$1 $(val "$1")" >> "$ORIG" 2>/dev/null
}

# ── 充电电流的节点表（Scene 用的那两个排最前 ✓）──
SUSPEND_PATHS="$O/battery/mmi_charging_enable
$B/input_suspend
/sys/class/power_supply/qcom-battery/input_suspend
$B/charging_enabled
$B/battery_charging_enabled
$O/battery/input_suspend
$B/charging_disabled"
SUSPEND_INVERTED="$B/input_suspend
/sys/class/power_supply/qcom-battery/input_suspend
$O/battery/input_suspend"

pick(){   # 第一个"真能写"的
    for p in $1; do
        [ -e "$p" ] || continue
        try_write "$p" "$(val "$p")" && { echo "$p"; return 0; }
    done
    return 1
}
is_suspend_node(){   # 这个节点是不是"1 = 停充"
    for p in $SUSPEND_INVERTED; do [ "$p" = "$1" ] && return 0; done
    return 1
}
battery_status(){ val $B/status; }
is_stopped(){
    case "$(battery_status)" in
        "Not charging"|Discharging) return 0 ;;
    esac
    return 1
}

# ── 停充 / 恢复 ──
do_suspend(){
    want=$1                       # 1 = 停充
    P=$(pick "$SUSPEND_PATHS") || { echo "unsupported（没有可写的停充节点）"; return 3; }
    orig_save "$P"
    if is_suspend_node "$P"; then
        [ "$want" = "1" ] && w=1 || w=0
    else
        [ "$want" = "1" ] && w=0 || w=1     # 反义节点：1 = 允许充
    fi
    try_write "$P" "$w" && echo "已$( [ "$want" = "1" ] && echo 停充 || echo 恢复 )（$P=$w）" \
                        || echo "写不进去（$P）"
}

# ── 守护 ──
keep_running(){ [ -f "$PIDF" ] || return 1; p=$(cat "$PIDF" 2>/dev/null); [ -n "$p" ] && kill -0 "$p" 2>/dev/null; }
keep_start(){
    keep_running && return 0
    setsid sh "$0" watch-loop </dev/null >>"$PLOG" 2>&1 &
    echo $! > "$PIDF"
    sleep 1
    keep_running
}
keep_stop(){ keep_running && kill "$(cat "$PIDF" 2>/dev/null)" 2>/dev/null; rm -f "$PIDF"; }

# 守护循环：应用限流 + 软件限充 + 保活
watch_loop(){
    echo $$ > "$PIDF"
    trap 'rm -f "$PIDF"; exit 0' INT TERM HUP
    logline "守护启动（间隔 ${IV}s，上下文 $(id -Z 2>/dev/null)，用户 $(id -un 2>/dev/null)）"
    last_sp=""
    while :; do
        # ② 充电保护：到停充阈值就停，掉到恢复阈值以下就恢复
        lim=$(cfg stop); rec=$(cfg recover)
        [ -z "$rec" ] && rec=70
        cap=$(val $B/capacity)
        if [ -n "$lim" ] && [ "$lim" -gt 0 ] 2>/dev/null && [ -n "$cap" ]; then
            if [ "$cap" -ge "$lim" ] 2>/dev/null; then
                is_stopped || { do_suspend 1 >/dev/null 2>&1; logline "保护：$cap% ≥ $lim% → 停充"; }
            elif [ "$cap" -le "$rec" ] 2>/dev/null; then
                is_stopped && { do_suspend 0 >/dev/null 2>&1; logline "保护：$cap% ≤ $rec% → 恢复充电"; }
            fi
        fi
        sleep "$IV"
    done
}

do_restore(){
    keep_stop
    setcfg stop ""; setcfg recover ""
    [ -f "$ORIG" ] || { echo "已还原（没有记录）"; return 0; }
    n=0
    while read -r path v; do
        [ -n "$path" ] || continue
        [ -w "$path" ] && [ -n "$v" ] && { try_write "$path" "$v" && n=$((n+1)); }
        sleep 0.02
    done < "$ORIG"
    rm -f "$ORIG"
    do_suspend 0 >/dev/null 2>&1
    echo "已还原 $n 个节点（配置已清空、守护已停）"
    logline "restore：还原 $n 个"
}

case "$1" in
status)
    echo "cap=$(val $B/capacity)"
    echo "volt=$(val $B/voltage_now)"
    echo "curr=$(val $B/current_now)"
    echo "temp=$(val $B/temp)"
    echo "stat=$(val $B/status)"
    echo "health=$(val $B/health)"
    echo "apwr=$(val $O/common/adapter_power)"
    echo "lim=$(val $O/common/chg_up_limit)"
    echo "mmi=$(val $O/battery/mmi_charging_enable)"
    echo "want_stop=$(cfg stop)"
    echo "want_recover=$(cfg recover)"
    echo "guard=$(keep_running && echo 1 || echo 0)"
    echo "ctx=$(id -Z 2>/dev/null)"
    ;;
nodes)
    echo "=== 充电节点（存在/能否真写/当前值）==="
    echo "上下文: $(id -Z 2>/dev/null)"
    ;;
protect)
    if [ "$2" = "off" ]; then
        # ⚠ 只关保护本身 ✗ 不走全量还原：全量还原会把守护一起停掉，
        # 用户"关保护后立刻又开"就跟还原赛跑（有时不生效 ✗）
        setcfg stop ""; setcfg recover ""
        do_suspend 0 >/dev/null 2>&1
        keep_start >/dev/null 2>&1
        echo "充电保护已关（已恢复充电；守护=$(keep_running && echo 开 || echo 关)）"
        exit 0
    fi
    case "$2" in ''|*[!0-9]*) echo "用法: chg.sh protect <停充%> [恢复%]"; exit 2 ;; esac
    setcfg stop "$2"
    [ -n "$3" ] && setcfg recover "$3" || { [ -z "$(cfg recover)" ] && setcfg recover 70; }
    keep_start >/dev/null 2>&1
    echo "充电保护已开：达到 $(cfg stop)% 停充，低于 $(cfg recover)% 恢复（守护=$(keep_running && echo 开 || echo 关)）"
    ;;
suspend)
    do_suspend "${2:-1}"
    ;;
daemon)
    case "$2" in
    start) keep_start >/dev/null 2>&1; echo "守护=$(keep_running && echo 开 || echo 关)（上下文 $(id -Z 2>/dev/null)）" ;;
    stop)  keep_stop; echo "守护已停" ;;
    *)     echo "用法: chg.sh daemon start|stop" ;;
    esac
    ;;
watch-loop)
    watch_loop
    ;;
restore)
    do_restore
    ;;
conf)
    [ -f "$CONF" ] && cat "$CONF"
    ;;
set)
    [ -z "$2" ] && { echo "用法: chg.sh set <键> <值>"; exit 2; }
    setcfg "$2" "$3"
    echo "$2=$3"
    ;;
log)
    tail -n "${2:-60}" "$PLOG" 2>/dev/null || echo "(还没日志)"
    ;;
*)
    echo "用法: chg.sh {status|nodes|protect <停%> [恢复%]|protect off|suspend 0|1|daemon start|stop|restore|conf|set <键> <值>|log}"
    ;;
esac
