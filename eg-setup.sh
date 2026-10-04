#!/system/bin/sh
# ============================================================
#  Extreme GT 安装期配置生成  ·  KSU 系统工具箱内嵌版
# ------------------------------------------------------------
#  来源：Extreme GT vAB-1.3.0（作者 嘟嘟ski & AB，二改版）
#  由本模块的 customize.sh 在安装时调用，$MODPATH 需已设置。
#
#  做什么：扫描设备上真实存在的各类温控 XML / json / txt，
#          生成"去温控"版本放进 $MODPATH 下同名路径，
#          开机由 bin/eg.sh 逐个 bind-mount 盖回去。
# ============================================================
# Magisk / KernelSU 的安装器是 **source** customize.sh 的，MODPATH 是它作用域里的
# 普通变量，不是环境变量 —— 用 `sh 脚本` 起子进程时拿不到。
# 所以这里自己按脚本位置推：脚本就在模块根目录下。
[ -n "$MODPATH" ] || MODPATH=${0%/*}
[ -d "$MODPATH" ] || { echo "! 找不到模块目录（MODPATH=$MODPATH）"; exit 1; }

ui_print "- Extreme GT：扫描设备温控配置"

mkdir -p /data/adb/ksu_toolbox 2>/dev/null
echo "persist.sys.oplus.wifi.sla.game_high_temperature=$(getprop persist.sys.oplus.wifi.sla.game_high_temperature)" >> /data/adb/ksu_toolbox/eg-orig.props 2>/dev/null
setprop persist.sys.oplus.wifi.sla.game_high_temperature 50
echo "persist.sys.environment.temp=$(getprop persist.sys.environment.temp)" >> /data/adb/ksu_toolbox/eg-orig.props 2>/dev/null
setprop persist.sys.environment.temp 25

mkdir -p "$MODPATH/system/vendor/etc"
touch "$MODPATH/ab_certified"
rm -f "$MODPATH/disable"

dirs="/odm /my_product /vendor /system/vendor /product /system"

xml_override() {
    mkdir -p "$(dirname "$MODPATH$1")"
    for file in $(find $dirs -name "$1" 2>/dev/null); do
        mkdir -p "$(dirname "$MODPATH$file")"
        rows=$(cat "$file")
        # 注意：$2 是多行列表，必须不引号让它按行拆开。
        # 原版写成 "$2"，等于整个列表当一个词，循环只跑一次，
        # key 会变成多行字符串让 sed 报 unterminated —— 结果是写出空文件。
        for override in $2; do
            key=$(echo "$override" | cut -f1 -d '=')
            value=$(echo "$override" | cut -f2 -d '=')
            rows=$(echo "$rows" | sed "s/<$key>.*</<$key>$value</")
        done
        echo "$rows" > "$MODPATH$file"
    done
}

# ---------- sys_thermal_control_config.xml ----------
boolValues="feature_enable_item feature_safety_test_enable_item aging_thermal_control_enable_item"
intValues="aging_cpu_level_item high_temp_safety_level_item game_high_perf_mode_item normal_mode_item ota_mode_item racing_mode_item"
today_version=$(date "+%Y%m%d01")
for file in $(find $dirs -name "sys_thermal_control_config.xml" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    if head -c 32 "$file" | grep -q '<?xml\|<sys_thermal'; then
        rows=$(cat "$file" | grep -v -E '(<gear_config|cpu=|fps=|<scene_|</scene_|<category_|</category_|<subitem|<level|\.)')
        for key in $boolValues; do
            rows=$(echo "$rows" | sed "s/<$key.*\/>/<$key booleanVal=\"false\" \/>/")
        done
        for key in $intValues; do
            rows=$(echo "$rows" | sed "s/<$key.*\/>/<$key intVal=\"-1\" \/>/")
        done
        echo "$rows" | tr -s '\n' > "$MODPATH$file"
    else
        sed "s/<version>.*<\/version>/<version>${today_version}<\/version>/" "$MODPATH/sys_thermal_control_config_default.xml" > "$MODPATH$file"
    fi
done

# ---------- sys_thermal_control_config_*.xml ----------
for file in $(find $dirs -name "sys_thermal_control_config_*.xml" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    if head -c 32 "$file" | grep -q '<?xml\|<sys_thermal'; then
        rows=$(cat "$file" | grep -v -E '(<gear_config|cpu=|fps=|<scene_|</scene_|<category_|</category_|<subitem|<level|\.)')
        for key in $boolValues; do
            rows=$(echo "$rows" | sed "s/<$key.*\/>/<$key booleanVal=\"false\" \/>/")
        done
        for key in $intValues; do
            rows=$(echo "$rows" | sed "s/<$key.*\/>/<$key intVal=\"-1\" \/>/")
        done
        echo "$rows" | tr -s '\n' > "$MODPATH$file"
    fi
done

# ---------- sys_thermal_config.xml ----------
xml_override 'sys_thermal_config.xml' "isOpen=0
more_heat_threshold=550
heat_threshold=530
less_heat_threshold=500
preheat_threshold=480
preheat_dex_oat_threshold=460
thermal_battery_temp=0
is_feature_on=0
is_upload_log=0
is_upload_errlog=0"

# ---------- sys_high_temp_protect_*.xml ----------
xml_override 'sys_high_temp_protect*xml' "isOpen=0
HighTemperatureProtectSwitch=false
HighTemperatureShutdownSwitch=false
HighTemperatureFirstStepSwitch=false
HighTemperatureProtectFirstStepIn=550
HighTemperatureProtectFirstStepOut=530
HighTemperatureProtectThresholdIn=570
HighTemperatureProtectThresholdOut=550
HighTemperatureProtectShutDown=750
MediumTemperatureProtectThreshold=10000
HighTemperatureDisableFlashSwitch=false
HighTemperatureDisableFlashLimit=480
HighTemperatureEnableFlashLimit=470
HighTemperatureDisableFlashChargeSwitch=false
HighTemperatureDisableFlashChargeLimit=480
HighTemperatureEnableFlashChargeLimit=470
camera_temperature_limit=520
HighTemperatureControlVideoRecordSwitch=false
HighTemperatureDisableVideoRecordLimit=550
HighTemperatureEnableVideoRecordLimit=520
ToleranceThreshold=50
ToleranceStart=480
ToleranceStop=460"

# ---------- thermallevel_to_fps.xml ----------
# 注意：这里写 $MODPATH$file（保留真实路径），不是 $MODPATH/system$file。
# 原版写的是后者 —— 那样 /odm/x.xml 会变成 /system/odm/x.xml，挂载目标就错了。
# 我们的 bin/eg.sh 会遍历全部六个根目录，按真实路径 bind-mount。
for file in $(find $dirs -name "thermallevel_to_fps.xml" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    cat "$file" | sed 's/fps="[^"]*"/fps="144"/g' > "$MODPATH$file"
done

# ---------- oppo_display_perf_list.xml ----------
for file in $(find $dirs -name "oppo_display_perf_list.xml" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    echo -n '' > "$MODPATH$file"
    skip=0
    while read line; do
        case "$line" in
         *"<name>"*)
           skip=0
           case "$line" in
            *"sf.dps.feature"*|*"com.android"*|*"system_server"*|*"/system"*|*"com.color"*|*"com.oppo"*|*"com.oplus"**"SmartVolume"*)
              skip=0
              echo "  $line" >> "$MODPATH$file"
            ;;
            *)
              skip=1
            ;;
           esac
         ;;
         '<?xml version="1.0" encoding="UTF-8"?>'|'<filter-conf>'|'</filter-conf>')
             echo "$line" >> "$MODPATH$file"
         ;;
         *)
           if [ $skip = 0 ]; then
             echo "  $line" >> "$MODPATH$file"
           fi
         ;;
        esac
    done < "$file"
done

# ---------- game_thermal_config.xml ----------
for file in $(find $dirs -name "game_thermal_config.xml" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    echo -n '' > "$MODPATH$file"
    if [ "$(grep cluster3 "$file")" != '' ]; then
        echo '<?xml version="1.0" encoding="utf-8"?>
<game_thermal_config>
    <version>20230829</version>
    <filter-name>game_thermal_config</filter-name>
    <heavy_policy>
        <game_control temp="520" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="60"/>
    </heavy_policy>
    <default_policy>
        <game_control temp="430" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="440" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="450" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="460" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="470" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="480" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="490" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
        <game_control temp="510" cluster0="-1" cluster1="-1" cluster2="-1" cluster3="-1" fps="0"/>
    </default_policy>
</game_thermal_config>' > "$MODPATH$file"
    else
        echo '<?xml version="1.0" encoding="utf-8"?>
<game_thermal_config>
    <version>20230829</version>
    <filter-name>game_thermal_config</filter-name>
    <heavy_policy>
        <game_control temp="520" cluster0="-1" cluster1="-1" cluster2="-1" fps="60"/>
    </heavy_policy>
    <default_policy>
        <game_control temp="430" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="440" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="450" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="460" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="470" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="480" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="490" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
        <game_control temp="510" cluster0="-1" cluster1="-1" cluster2="-1" fps="0"/>
    </default_policy>
</game_thermal_config>' > "$MODPATH$file"
    fi
done

# ---------- QEGA_Config.txt ----------
for file in $(find $dirs -name "QEGA_Config.txt" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    echo "SkinTemperatureNode:   xo-therm
SkinNodeThrottleTemp:  42000
#GameID   GameAPK    MaxTemperature  MaxCurrent  AvgCurrent
100001    hok         42000          1200        900
0         adaptive    42000          1200        900" > "$MODPATH$file"
done

# ---------- devices_config.json ----------
json_line_suffix() {
    case "$1" in *,*) printf ',' ;; esac
}

for file in $(find $dirs -name "devices_config.json" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    echo -n '' > "$MODPATH$file"
    while read line; do
        case "$line" in
         *'"high.capacity.threshold": 100'*)
           echo "$line" >> "$MODPATH$file"
         ;;
         *'"battery.temperate.range":'*)
           echo '"battery.temperate.range": "[100,500]"'"$(json_line_suffix "$line")" >> "$MODPATH$file"
         ;;
         *'"high.capacity.threshold":'*)
           echo '"high.capacity.threshold": 85'"$(json_line_suffix "$line")" >> "$MODPATH$file"
         ;;
         *)
           echo "$line" >> "$MODPATH$file"
         ;;
        esac
    done < "$file"
done

# ---------- qapegameconfig.txt ----------
for file in $(find $dirs -name "qapegameconfig.txt" 2>/dev/null); do
    mkdir -p "$(dirname "$MODPATH$file")"
    echo "#GameID   GameAPK          MaxTemperature  MaxCurrent  AvgCurrent //Current here means device consuming current (1000 means device is consuming 1000 mA)
100001    hok                 42000          1150        900
100002    codm                42000          1150        900
100010    hok_oversea         42000          1150        900
100100    GP                  42000          1150        900
120000    com.netease.allstar 42000          1150        900
120100    Infinity_Nikki      42000          1150        900
120200    NARAKA_BLADEPOINT   42000          1150        900
120300    JusticeOnline       42000          1150        900
120400    Seasun_JXOnline3    42000          1150        900
120500    Tencent_DFM         42000          1150        900
120600    Tencent_PRacing     42000          1150        900
120700    WutheringWaves      42000          1150        900
120800    PerfectWorld_P5X    42000          1150        900
120900    Netease_Diablo      42000          1150        900
121000    Racing_Master       42000          1150        900
121100    Tarisland           42000          1150        900
121200    Arena_Breakout      42000          1150        900
121300    Tencent_DNF         42000          1150        900
121400    Tencent_LOL         42000          1150        900
121500    Tencent_Spatula     42000          1150        900
121600    Genshin             42000          1150        900
122700    StarRail            42000          1150        900
122800    ZenlessZoneZero     42000          1150        900
0         Default             42000          1150        100" > "$MODPATH$file"
done

CNT=0
for d in odm my_product vendor product system; do
    if [ -d "$MODPATH/$d" ]; then
        c=$(find "$MODPATH/$d" -type f 2>/dev/null | wc -l)
        CNT=$((CNT + c))
    fi
done
ui_print "- Extreme GT：共生成 $CNT 个去温控配置文件"
if [ "$CNT" = "0" ]; then
    ui_print "! 一个都没扫到 —— 本机可能不是欧加系，"
    ui_print "! 或者温控 XML 不在 /odm /my_product /vendor /system 这些标准路径下。"
    ui_print "! 装完后在工具箱「自检」页能看到实际扫描结果。"
fi
