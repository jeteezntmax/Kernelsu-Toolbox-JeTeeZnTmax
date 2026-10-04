#!/system/bin/sh
# ============================================================
#  therm.sh — 热区温度【只读】读取（v2）
#
#  ⚠️ 原来这里还有"温度伪装"（往热区写 emul_temp / mode 等），**已整体删除** ✗
#     原因：作者实测本机（OnePlus / SM8845 系）**写热区节点会让热控守护进程卡死
#     → 系统重启**，写 emul_temp 也一样 ✗✗；如果放进开机流程还会变成
#     【开机就写 → 崩 → 重启】的死循环 ✗。
#     结论：这台机器上这条路不可行，功能作废，只保留只读读取 ✓
#
#  子命令：
#    zones     列出所有热区：名字|类型|当前温度（只读，不动任何东西 ✓）
#    status    一句话状态
#    log       看日志（本脚本现在是只读的，日志基本只有历史记录）
# ============================================================
BASE=${THERM_BASE:-/data/adb/ksu_toolbox}
PLOG=$BASE/therm.log

val(){ cat "$1" 2>/dev/null | head -1 | tr -d '\n'; }
zones(){ ls -d /sys/class/thermal/thermal_zone* 2>/dev/null; }

case "$1" in
zones)
    for z in $(zones); do
        t=$(val "$z/type"); tp=$(val "$z/temp")
        [ -n "$tp" ] && echo "$(basename "$z")|${t:-?}|$tp"
    done
    ;;
status)
    n=0; hot=0
    for z in $(zones); do
        tp=$(val "$z/temp"); [ -z "$tp" ] && continue
        n=$((n+1))
        [ "${tp:-0}" -ge 45000 ] 2>/dev/null && hot=$((hot+1))
    done
    echo "zones=$n"
    echo "hot=$hot"
    echo "mode=readonly"
    ;;
log)
    tail -n "${2:-60}" "$PLOG" 2>/dev/null || echo "(没有日志)"
    ;;
*)
    echo "用法: therm.sh {zones|status|log [n]}   ← 只读；温度伪装已删除"
    ;;
esac
