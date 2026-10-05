#!/system/bin/sh
# ============================================================
#  充电控制后端 —— 探测式，绝不写死某个厂商
#
#  每项能力按【厂商专属 → 通用】的顺序挑第一个"存在且可写"的节点。
#  挑不到就明确回 unsupported，绝不瞎写。
#
#  已实测（欧加 / 一加 / realme）：
#    chg_up_limit          = 0,80,0,80,2   [可写]  充电上限
#    mmi_charging_enable   = 1             [可写]  停充开关（1=允许充）
#    slow_chg_en           = 0,0,0,0       [可写]  慢充
#    adapter_power         = 100000        [可写]  适配器功率(mW)
#    usb/input_current_limit = 2000000     [可写]  输入限流(µA)
# ============================================================

O=/sys/class/oplus_chg
B=/sys/class/power_supply/battery
U=/sys/class/power_supply/usb

try_write(){ { echo "$2" > "$1"; } 2>/dev/null; }
pick() {   # 第一个"真能写"的（sysfs 上 test -w 不可靠）
    for p in "$@"; do
        [ -e "$p" ] || continue
        cur=$(val "$p")
        try_write "$p" "$cur" && { echo "$p"; return 0; }
    done
    return 1
}
val() { cat "$1" 2>/dev/null | head -1 | tr -d '\n'; }

BASE=${CHG_BASE:-/data/adb/ksu_toolbox}
ORIG=$BASE/chg-orig

# 只接受数字（第三方的"统一参数校验"：不合法就直接拒绝，别把未知值当关闭）
is_num(){ case "$1" in ''|*[!0-9]*) return 1;; esac; return 0; }
num_in(){ is_num "$1" && [ "$1" -ge "$2" ] 2>/dev/null && [ "$1" -le "$3" ] 2>/dev/null; }
# 首次动某个节点前，把原值记下来（卸载时还原用）
orig_save(){
    mkdir -p "$BASE" 2>/dev/null
    [ -f "$ORIG" ] && grep -qF "$1 " "$ORIG" 2>/dev/null && return 0
    echo "$1 $(val "$1")" >> "$ORIG" 2>/dev/null
}
# 写 + 回读确认：写成功 ≠ 生效（会被驱动忽略、被别的组件改回）
finish_write(){
    # 注意：这里【不能】要求"纯数字" ✗ —— 有些节点（比如欧加 chg_up_limit）
    # 写的是 "0,80,1,80,2" 这种组合值，之前被当成非法值拦下（用户实测 bad-value ✓）。
    # 用户输入合法性由各子命令自己校验 ✓，这里只做基本安全过滤。
    case "$2" in
        '') echo "bad-value（空值）"; return 1 ;;
        *[!0-9,._-]*) echo "bad-value（含不支持的字符）"; return 1 ;;
    esac
    if [ ${#2} -gt 64 ]; then echo "bad-value（太长）"; return 1; fi
    orig_save "$1"
    echo "$2" > "$1" 2>/dev/null || { echo "write-fail"; return 1; }
    sleep 0.2
    now=$(val "$1")
    if [ "$now" = "$2" ]; then echo "ok"; return 0; fi
    echo "readback-mismatch(now=$now)"
    return 0
}


# ── 停充开关的候选 ──
SUSPEND_PATHS="$O/battery/mmi_charging_enable
/sys/class/power_supply/battery/input_suspend
/sys/class/power_supply/battery/charging_enabled
/sys/class/power_supply/battery/battery_charging_enabled
/sys/class/qcom-battery/input_suspend
/sys/class/cms_class/disable_charge
/sys/class/power_supply/mtk-battery/charging_enabled"

# 这些节点的语义是【1 = 停充】，跟欧加的 mmi_charging_enable(1=允许充) 相反
SUSPEND_INVERTED="/sys/class/power_supply/battery/input_suspend
/sys/class/qcom-battery/input_suspend"

case "$1" in

restore)
    # 把记下的原值写回去（卸载/停用时用）
    [ -f "$ORIG" ] || { echo "no-orig"; exit 0; }
    n=0
    while read -r path v; do
        [ -n "$path" ] && [ -w "$path" ] && echo "$v" > "$path" 2>/dev/null && n=$((n+1))
    done < "$ORIG"
    rm -f "$ORIG"
    echo "restored=$n"
    ;;
status)
    echo "cap=$(val $B/capacity)"
    echo "volt=$(val $B/voltage_now)"
    echo "curr=$(val $B/current_now)"
    echo "temp=$(val $B/temp)"
    echo "stat=$(val $B/status)"
    echo "health=$(val $B/health)"
    echo "limit=$(val $O/common/chg_up_limit)"
    echo "mmi=$(val $O/battery/mmi_charging_enable)"
    echo "slow=$(val $O/battery/slow_chg_en)"
    echo "apwr=$(val $O/common/adapter_power)"
    echo "icl=$(val $U/input_current_limit)"
    echo "icm=$(val $U/current_max)"
    ;;

# ── 看这台支持哪几项 ──
caps)
    if pick $O/common/chg_up_limit >/dev/null; then echo "limit=1"; else echo "limit=0"; fi
    if pick $SUSPEND_PATHS >/dev/null; then echo "suspend=1"; else echo "suspend=0"; fi
    if pick $O/battery/slow_chg_en >/dev/null; then echo "slow=1"; else echo "slow=0"; fi
    if pick $O/common/adapter_power >/dev/null; then echo "power=1"; else echo "power=0"; fi
    if pick $U/input_current_limit >/dev/null; then echo "icl=1"; else echo "icl=0"; fi
    ;;

# ── 充电上限（预定电量停充）  chg.sh limit 80 | chg.sh limit 0 ──
limit)
    P=$(pick $O/common/chg_up_limit) || { echo "unsupported"; exit 1; }
    V="$2"
    case "$V" in ''|*[!0-9]*) echo "bad-arg（要 0-100 的数字）"; exit 2 ;; esac
    { [ "$V" -ge 0 ] && [ "$V" -le 100 ]; } || { echo "bad-arg（要 0-100）"; exit 2; }
    cur=$(val "$P")
    # 欧加格式： <下限开关>,<下限%>,<上限开关>,<上限%>,<模式>
    # 例 0,80,0,80,2  →  开启上限改成 0,80,1,80,2
    new=$(echo "$cur" | awk -F, -v v="$V" '
        BEGIN{ OFS="," }
        NF>=5 { if (v+0 == 0) { $3=0 } else { $3=1; $4=v } }
        { print }')
    if [ -z "$new" ]; then echo "parse-fail"; exit 3; fi
    finish_write "$P" "$new" || exit 4
    sleep 0.3
    echo "ok $(val $P)"
    ;;

# ── 停充 / 恢复   chg.sh suspend 1|0   (1=停, 0=恢复) ──
suspend)
    WANT="$2"       # 1 = 想停充
    P=$(pick $SUSPEND_PATHS) || { echo "unsupported"; exit 1; }
    inverted=0
    echo "$SUSPEND_INVERTED" | grep -qx "$P" && inverted=1
    if [ "$inverted" = "1" ]; then
        echo "$WANT" > "$P"                       # input_suspend: 1=停
    else
        [ "$WANT" = "1" ] && echo 0 > "$P" || echo 1 > "$P"   # 1=允许充
    fi
    sleep 0.3
    echo "ok node=$P val=$(val $P)"
    ;;

# ── 慢充开关   chg.sh slow 1|0 ──
slow)
    P=$(pick $O/battery/slow_chg_en) || { echo "unsupported"; exit 1; }
    cur=$(val "$P")
    new=$(echo "$cur" | awk -F, -v v="$2" '
        BEGIN{ OFS="," }
        { $1=(v+0==1)?1:0 }
        { print }')
    finish_write "$P" "$new" || exit 4
    sleep 0.3
    echo "ok $(val $P)"
    ;;

# ── 充电功率上限（mW）  chg.sh power 30000 ──
power)
    P=$(pick $O/common/adapter_power) || { echo "unsupported"; exit 1; }
    V="$2"
    case "$V" in ''|*[!0-9]*) echo "bad-arg"; exit 2 ;; esac
    finish_write "$P" "$V" || exit 4
    sleep 0.3
    echo "ok $(val $P)"
    ;;

# ── USB 输入限流（µA）  chg.sh icl 1000000 ──
icl)
    P=$(pick $U/input_current_limit) || { echo "unsupported"; exit 1; }
    V="$2"
    case "$V" in ''|*[!0-9]*) echo "bad-arg"; exit 2 ;; esac
    MX=$(val $U/current_max)
    case "$MX" in ''|*[!0-9]*) MX=0 ;; esac
    if [ "$MX" -gt 0 ] 2>/dev/null && [ "$V" -gt "$MX" ]; then V="$MX"; fi
    finish_write "$P" "$V" || exit 4
    sleep 0.3
    echo "ok $(val $P)"
    ;;

*)
    echo "用法: chg.sh {status|caps|limit N|suspend 0/1|slow 0/1|power mW|icl uA}"
    ;;
esac
