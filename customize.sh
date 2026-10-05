#!/system/bin/sh
# ============================================================
#  Cometa · 安装脚本
#  内含三部分：
#    1. 工具箱 WebUI（本模块自己）
#    2. Device Faker 引擎（GPL-3.0，见 NOTICE-device_faker.txt）
# ============================================================

ui_print "=========================================="
ui_print " Cometa"
ui_print " 含 Device Faker 引擎"
ui_print "=========================================="

# ---------- 1. Device Faker 数据目录 ----------
DF_DATA=/data/adb/device_faker
DF_CFG=$DF_DATA/config/config.toml

ui_print "- 准备 Device Faker 数据目录"
mkdir -p "$DF_DATA/config" "$DF_DATA/logs"
chmod 755 "$DF_DATA" "$DF_DATA/config" "$DF_DATA/logs"

if [ -f "$DF_CFG" ]; then
    ui_print "- 已有配置，保留不动"
else
    if [ -f "$MODPATH/df-default-config.toml" ]; then
        cp -f "$MODPATH/df-default-config.toml" "$DF_CFG"
        chmod 644 "$DF_CFG"
        chcon u:object_r:system_file:s0 "$DF_CFG" 2>/dev/null
        ui_print "- 已写入默认配置：$DF_CFG"
    fi
fi
rm -f "$MODPATH/df-default-config.toml"

# ---------- 2. 数据目录 ----------
DATA_DIR=/data/adb/ksu_toolbox
mkdir -p "$DATA_DIR"
chmod 755 "$DATA_DIR"

# 铺一份默认任务表 —— 这样浏览器模式一装完就有数据，
# 不需要先在 KernelSU 里打开一次 WebView。页面打开后会用最新的覆盖它。
if [ -f "$MODPATH/tasks.default.txt" ]; then
    cp -f "$MODPATH/tasks.default.txt" "$DATA_DIR/tasks.txt"
    chmod 644 "$DATA_DIR/tasks.txt"
    ui_print "- 已铺设离线数据任务表"
fi

# ---------- 4. Zygisk 检查（不中断） ----------
HAS_ZYGISK=0
if find /data/adb/modules /data/adb/modules_update /data/adb/ksu/lib \
        \( -name "libzygisk.so" -o -name "libzygisk64.so" \) 2>/dev/null | grep -q .; then
    HAS_ZYGISK=1
fi
if [ "$HAS_ZYGISK" = "0" ]; then
    ui_print "! 没检测到 Zygisk 实现，Device Faker 不会生效"
    ui_print "! 需要另装 ZygiskNext（Magisk 自带的那个不行）"
fi

# ---------- 5. 权限 ----------
ui_print "- 设置权限"
set_perm_recursive "$MODPATH/webroot" 0 0 0755 0644
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 0 0755 0755
set_perm "$MODPATH/service.sh" 0 0 0755
[ -d "$MODPATH/app" ] && set_perm_recursive "$MODPATH/app" 0 0 0755 0644
[ -f "$MODPATH/bin/ksu_guard" ] && set_perm "$MODPATH/bin/ksu_guard" 0 0 0755

# XML / json / txt 覆盖件给正确的 SELinux 上下文
for d in odm my_product vendor product system; do
    [ -d "$MODPATH/$d" ] && set_perm_recursive "$MODPATH/$d" 0 0 0755 0644 u:object_r:vendor_configs_file:s0
done

ui_print "=========================================="
ui_print " 装好了，重启后在模块列表点「打开」"
ui_print "=========================================="

if [ -d /data/adb/modules/device_faker ] || [ -d /data/adb/modules_update/device_faker ]; then
    ui_print "! 检测到独立安装的 device_faker，请把它停用或卸载"
fi

# ============================================================
#  自动装桌面 App + 免手动授权
#  App 本来就有 root —— 「显示在其他应用上层」用 appops 直接给，
#  没必要让用户再手点一遍权限页。
# ============================================================
PKG=com.jeteezntmax.toolbox
APP="$MODPATH/app/JeTeeZnTmax.apk"
if [ -f "$APP" ]; then
    ui_print "- 安装桌面 App"
    pm install -r "$APP" >/dev/null 2>&1 || pm install -r -d "$APP" >/dev/null 2>&1
    sleep 2
    for op in SYSTEM_ALERT_WINDOW POST_NOTIFICATION RUN_IN_BACKGROUND \
              RUN_ANY_IN_BACKGROUND START_FOREGROUND; do
        appops set $PKG $op allow >/dev/null 2>&1
    done
    dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1
    if pm path $PKG >/dev/null 2>&1; then
        ui_print "- 桌面 App 已装好，悬浮窗权限已自动给"
    else
        ui_print "! 桌面 App 没装上，去设置页手动装一下"
    fi
fi

# ── 关于作者 ──────────────────────────────────────────────
ui_print " "
ui_print "  ────────── 关于作者 ──────────"
ui_print "   作者    JeTeeZnTmax"
ui_print "   GitHub  github.com/jeteezntmax/Kernelsu-Toolbox-JeTeeZnTmax"
ui_print "   酷安    JeTeeZnTmaxQwQ"
ui_print "   QQ      3892039309"
ui_print "   QQ 群   1102902791"
ui_print "   协议    GPL-3.0（含 Device Faker，见 NOTICE-*.txt）"
ui_print "  ─────────────────────────────"
ui_print " "

# ---------- 清理 Extreme GT 残留 ----------
# EG 已经从模块里删掉了，但它当年写过的两个 persist 温控属性会留在设备上，
# 这里按记录的原值还原；没有记录就把它们清空（回到 ROM 默认）。
if [ -f /data/adb/ksu_toolbox/eg-orig.props ]; then
    while IFS='=' read -r k v; do
        [ -n "$k" ] || continue
        if [ -n "$v" ]; then setprop "$k" "$v" 2>/dev/null; else setprop "$k" "" 2>/dev/null; fi
    done < /data/adb/ksu_toolbox/eg-orig.props
    rm -f /data/adb/ksu_toolbox/eg-orig.props
    ui_print "- 已还原 Extreme GT 改过的温控属性"
else
    for k in persist.sys.oplus.wifi.sla.game_high_temperature persist.sys.environment.temp; do
        v=$(getprop "$k" 2>/dev/null)
        case "$v" in 50|25) setprop "$k" "" 2>/dev/null; ui_print "- 清掉 EG 残留属性 $k";; esac
    done
fi

# ---------- 温度伪装的安全迁移 ----------
# 历史上这个功能的"开机自动应用"进过 service.sh，而本机写热区节点会让
# 热控卡死 → 系统重启 → 【开机就写 → 崩 → 重启】的死循环 ✗（作者实测）。
# 所以安装/升级时：
#   ① 从配置里【删掉 enabled/allow_write】→ 开机不会再自动写任何热区节点 ✓
#   ② 清掉陈旧的还原记录与 pid
TC=/data/adb/ksu_toolbox/therm.conf
if [ -f "$TC" ]; then
    grep -v '^enabled=' "$TC" | grep -v '^allow_write=' > "$TC.t" 2>/dev/null && mv "$TC.t" "$TC"
    ui_print "- 已从温度伪装配置里移除开机自动应用（防止热控卡死引起重启循环）"
fi
rm -f /data/adb/ksu_toolbox/therm-orig /data/adb/ksu_toolbox/therm.pid /data/adb/ksu_toolbox/therm-reverts 2>/dev/null
