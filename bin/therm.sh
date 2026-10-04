#!/system/bin/sh
# ============================================================
#  therm.sh — 温度伪装（v3.4.18）
#
#  为什么要自己写：社区里那个 AaTempSpoof 是【没有许可证】的 ✗，
#  我们模块现在是 GPL-3.0，不能把它的代码/二进制打进来
#  （那和之前 Device Faker 的坑是同一类问题，只是反过来）。
#  所以这里只参考"往哪些节点写"这种**设备事实**（节点路径不是谁的创作），
#  实现全部自己写：探测式 + 写后回读 + 原值记录 + 还原。
#
#  子命令：
#    caps                本机支持哪些（探测结果）
#    status              当前状态
#    temp on|off [值]    温度伪装：对可写的热区写 emul_temp（跨机型最通用的一招）
#    batt on|off [值]    电池温度伪装
#    cycle <次数>        电池循环次数伪装
#    wall on|off [值]    温度墙（trip_point）
#    restore             全部还原（卸载/关闭时用）
#    conf                看配置
#
#  干跑：THERM_DRY=1
# ============================================================
BASE=${THERM_BASE:-/data/adb/ksu_toolbox}
CONF=$BASE/therm.conf
ORIG=$BASE/therm-orig
PLOG=$BASE/therm.log
DRY=${THERM_DRY:-0}

mdirs(){ mkdir -p "$BASE" 2>/dev/null; }
logline(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$PLOG" 2>/dev/null; }
val(){ cat "$1" 2>/dev/null | head -1 | tr -d '\n'; }
cfg(){ [ -f "$CONF" ] && sed -n "s/^$1=//p" "$CONF" | head -n1; }
setcfg(){
    mdirs
    if [ -f "$CONF" ]; then grep -v "^$1=" "$CONF" > "$CONF.t" 2>/dev/null; else : > "$CONF.t"; fi
    [ -n "$2" ] && echo "$1=$2" >> "$CONF.t"
    mv "$CONF.t" "$CONF" 2>/dev/null
}

# 首次动某个节点前记下原值（还原用）
orig_save(){
    mdirs
    [ -f "$ORIG" ] && grep -qF "$1 " "$ORIG" 2>/dev/null && return 0
    echo "$1 $(val "$1")" >> "$ORIG" 2>/dev/null
}
# 写 + 回读确认（写成功 ≠ 生效）
wr(){
    [ -e "$1" ] || return 1
    [ -z "$2" ] && return 1
    orig_save "$1"
    if [ "$DRY" = "1" ]; then echo "[dry] echo $2 > $1"; return 0; fi
    echo "$2" > "$1" 2>/dev/null || return 1
    now=$(val "$1")
    [ "$now" = "$2" ] && return 0
    logline "回读不一致: $1 写 $2 读到 $now"
    return 2
}
# 找一个能写、而且当前值能读的节点
pick(){ for p in "$@"; do [ -w "$p" ] && echo "$p" && return 0; done; return 1; }

# ---- 热区（emul_temp 那一招，跨机型）----
zones(){ ls -d /sys/class/thermal/thermal_zone* 2>/dev/null; }
zone_writable(){
    for z in $(zones); do [ -w "$z/emul_temp" ] && echo "$z"; done
}
# ---- 电池温度候选（按厂商）----
BATT_TEMP_PATHS="/sys/class/power_supply/battery/emul_temp
/sys/class/oplus_chg/battery/fake_batt_temp
/sys/class/oplus_chg/battery/batt_temp
/sys/class/power_supply/battery/temp
/sys/class/mi_battchg/mi_battchg/thermal_level"
# ---- 循环次数候选 ----
CYCLE_PATHS="/sys/class/oplus_chg/battery/cycle_count
/sys/class/oplus_chg/battery/battery_chargecycles
/sys/class/oplus_chg/battery/charge_cycle
/sys/class/power_supply/battery/cycle_count"
# ---- 温度墙候选（trip_point）----
WALL_PATHS="$(ls /sys/class/thermal/thermal_zone*/trip_point_0_temp 2>/dev/null)"

do_restore(){
    [ -f "$ORIG" ] || { echo "no-orig"; return 0; }
    n=0
    while read -r path v; do
        [ -n "$path" ] || continue
        if [ -w "$path" ] && [ -n "$v" ]; then
            if [ "$DRY" = "1" ]; then echo "[dry] echo $v > $path"
            else echo "$v" > "$path" 2>/dev/null && n=$((n+1)); fi
        fi
    done < "$ORIG"
    rm -f "$ORIG"
    echo "已还原 $n 个节点"
    logline "restore 还原 $n 个节点"
}

case "$1" in
caps)
    echo "=== 温度伪装能力探测 ==="
    n=0
    for z in $(zones); do [ -w "$z/emul_temp" ] && { echo "  热区 $z（emul_temp 可写）✓"; n=$((n+1)); }; done
    [ "$n" = "0" ] && echo "  热区：没有可写 emul_temp 的（很多 ROM 需要先关 thermal mode，或者靠厂商节点）"
    p=$(pick $BATT_TEMP_PATHS) && echo "  电池温度：$p ✓" || echo "  电池温度：无可用节点 ✗"
    p=$(pick $CYCLE_PATHS) && echo "  循环次数：$p ✓" || echo "  循环次数：无可用节点 ✗"
    p=$(pick $WALL_PATHS) && echo "  温度墙：$p ✓" || echo "  温度墙：无可用节点 ✗"
    ;;
conf)
    [ -f "$CONF" ] && cat "$CONF"
    ;;
status)
    echo "enabled=$(cfg enabled)"
    echo "cpu=$(cfg cpu)"
    echo "batt=$(cfg batt)"
    echo "cycle=$(cfg cycle)"
    echo "wall=$(cfg wall)"
    echo "emul_zones=$(zone_writable | grep -c .)"
    echo "batt_path=$(pick $BATT_TEMP_PATHS)"
    echo "cycle_path=$(pick $CYCLE_PATHS)"
    echo "backup=$([ -f "$ORIG" ] && echo 1 || echo 0)"
    echo "dry=$DRY"
    ;;
temp)
    case "$2" in
    on)
        v=${3:-42000}
        setcfg enabled 1; setcfg cpu "$v"
        n=0; bad=0
        for z in $(zone_writable); do
            # 标准做法：先把该热区的 mode 设成 disabled，再写 emul_temp
            [ -w "$z/mode" ] && wr "$z/mode" "disabled" >/dev/null 2>&1
            wr "$z/emul_temp" "$v" && n=$((n+1)) || bad=$((bad+1))
        done
        echo "已伪装 $n 个热区为 $((v/1000))℃${bad:+（$bad 个写不进去/回读不一致）}"
        logline "temp on $v：成功 $n，失败 $bad"
        ;;
    off) do_restore ;;
    *) echo "用法: therm.sh temp on|off [毫摄氏度,如 42000]" ;;
    esac
    ;;
batt)
    P=$(pick $BATT_TEMP_PATHS) || { echo "unsupported"; exit 3; }
    case "$2" in
    on)  v=${3:-30000}; setcfg batt "$v"; wr "$P" "$v" && echo "电池温度 → $((v/1000))℃（$P）" ;;
    off) wr "$P" "" >/dev/null 2>&1; setcfg batt ""; echo "电池温度伪装已关（原值见 $ORIG）" ;;
    *) echo "用法: therm.sh batt on|off [毫摄氏度]" ;;
    esac
    ;;
cycle)
    P=$(pick $CYCLE_PATHS) || { echo "unsupported"; exit 3; }
    [ -z "$2" ] && { echo "用法: therm.sh cycle <次数>"; exit 2; }
    setcfg cycle "$2"
    wr "$P" "$2" && echo "循环次数 → $2（$P）"
    ;;
wall)
    P=$(pick $WALL_PATHS) || { echo "unsupported"; exit 3; }
    case "$2" in
    on)  v=${3:-48000}; setcfg wall "$v"; wr "$P" "$v" && echo "温度墙 → $((v/1000))℃（$P）" ;;
    off) do_restore ;;
    *) echo "用法: therm.sh wall on|off [毫摄氏度]" ;;
    esac
    ;;
restore)
    do_restore
    ;;
log)
    tail -n "${2:-60}" "$PLOG" 2>/dev/null || echo "(还没日志)"
    ;;
*)
    echo "用法: therm.sh {caps|status|temp on|off [值]|batt on|off [值]|cycle <次数>|wall on|off [值]|restore|conf|log}"
    ;;
esac
