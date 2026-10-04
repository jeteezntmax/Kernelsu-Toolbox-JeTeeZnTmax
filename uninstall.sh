#!/system/bin/sh
# ============================================================
#  卸载收尾：把模块动过的东西尽量还原，别留半截状态
#  （第三方审查报告提的"卸载时的恢复逻辑"）
#
#  KernelSU 在删除模块目录【之前】会调用这个脚本，所以此刻 bin/ 还在。
#  这里只做"能确认、能回滚"的事；回滚不了的会明确提示，不装作没事。
# ============================================================
MODDIR=${0%/*}
PKG=com.jeteezntmax.toolbox
B=/data/adb/ksu_toolbox

say(){ echo "$*"; }

say "  ────────── 卸载收尾 ──────────"

# ① 刷新率：在锁定中就还原（脚本自己会判断有没有锁过）
if [ -f "$MODDIR/bin/refresh.sh" ]; then
    sh "$MODDIR/bin/refresh.sh" restore >/dev/null 2>&1
    say "   刷新率：已尝试还原"
fi
[ -f "$B/refresh/restore.pending" ] && \
    say "   ⚠ 刷新率的原始显示模式当时读不到，没能自动还原 —— 请到系统显示设置里确认一下"

# ② 充电节点：写回安装/使用前记录的原值
if [ -f "$MODDIR/bin/chg.sh" ]; then
    r=$(sh "$MODDIR/bin/chg.sh" restore 2>/dev/null)
    say "   充电节点：$r"
fi

# ③ Extreme GT 动过的 persist 属性：写回原值
if [ -f "$B/eg-orig.props" ]; then
    n=0
    while IFS='=' read -r k v; do
        [ -n "$k" ] || continue
        if [ -n "$v" ]; then
            setprop "$k" "$v" 2>/dev/null && n=$((n + 1))
        fi
    done < "$B/eg-orig.props"
    say "   persist 属性：还原 $n 项（原值备份在 $B/eg-orig.props）"
else
    say "   ⚠ 没找到 persist 原值备份 —— 如果用过 Extreme GT，建议手动核对温控属性"
fi

# ④ 停掉所有后台
[ -f "$MODDIR/bin/refresh.sh" ] && sh "$MODDIR/bin/refresh.sh" keepalive stop >/dev/null 2>&1
[ -f "$MODDIR/bin/webui-server.sh" ] && sh "$MODDIR/bin/webui-server.sh" stop >/dev/null 2>&1
am stopservice -n $PKG/.MonitorService >/dev/null 2>&1
am stopservice -n $PKG/.PerfService >/dev/null 2>&1
say "   后台服务：保活 / HTTP / 监视器 / 游戏加速 已停"

# ⑤ 把音量键那个无障碍服务从系统设置里摘掉
#    （不摘的话设置里会一直挂着一条"服务已停用"，看着很脏）
S=$PKG/$PKG.KeyWatcher
c=$(settings get secure enabled_accessibility_services 2>/dev/null)
[ "$c" = "null" ] && c=""
nc=$(echo "$c" | tr ':' '\n' | grep -v -F "$S" | tr '\n' ':' | sed 's/:$//')
if [ "$nc" != "$c" ]; then
    settings put secure enabled_accessibility_services "$nc" >/dev/null 2>&1
    say "   无障碍服务：已从系统里摘除"
fi

# ⑥ 数据目录保留（下次装上配置/日志还在）
say "   ✓ 数据目录 $B 保留（配置、日志、白名单都在）"
say "     想清干净：rm -rf $B"
say "  ────────── 收尾完成 ──────────"
