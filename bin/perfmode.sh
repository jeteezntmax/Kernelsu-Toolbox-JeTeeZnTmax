#!/system/bin/sh
# ============================================================
#  perfmode.sh — 游戏加速（按应用 uid 应用 / 还原）
#
#  和之前那版废稿最大的区别：
#    · 不做"猜前台" —— 由桌面 App 在 Java 里看"这个 uid 的进程里有没有
#      oom_score_adj == 0 的"（零 fork，微秒级），是就调 apply、不是就调 restore
#    · 这里只负责"按 uid 把设置写进去 / 还回来"，一次调用干完，逻辑单一
#
#  配置：/data/adb/ksu_toolbox/perf/profile.conf（WebUI 和音量键菜单都写它）
#    enabled=1          总开关
#    app=com.x.y        目标应用
#    uid=10234          目标 uid（空的话自动从 app 解析）
#    freq=0             锁频：0=不动 / 0 以外的 kHz 值
#    gov=               调速器：空=不动
#    affinity=          亲和性：big=只用超大核 / all=不绑(全核) / 空=不动
#    refresh=0          刷新率：0=不动 / 60/90/120...
#    menu=1             长按音量上键呼出菜单
#    hud=1              功能提示悬浮窗
#    hud_items=time,cpu,fps,power,temp   提示里显示哪些系统参数（空=只显示已打开的功能）
#    whitelist=com.a,com.b               清后台白名单（这些应用的后台不动）
#
#  子命令：apply | restore | status | list | freqs | govs | conf | set <键> <值>
#  干跑：PERF_DRY=1
# ============================================================
BASE=${PERF_BASE:-/data/adb/ksu_toolbox/perf}
CONF=$BASE/profile.conf
ORIG=$BASE/orig.state
APPLIED=$BASE/applied.state
CPU=/sys/devices/system/cpu
DRY=${PERF_DRY:-0}

mdirs(){ mkdir -p "$BASE" 2>/dev/null; }
PLOG="$BASE/perf.log"
logline(){ echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$PLOG" 2>/dev/null; }
rd(){ cat "$1" 2>/dev/null; }
cfg(){ [ -f "$CONF" ] && sed -n "s/^$1=//p" "$CONF" | head -n1; }
setcfg(){   # setcfg <键> <值>
    mdirs
    if [ -f "$CONF" ]; then grep -v "^$1=" "$CONF" > "$CONF.t" 2>/dev/null; else : > "$CONF.t"; fi
    [ -n "$2" ] && echo "$1=$2" >> "$CONF.t"
    mv "$CONF.t" "$CONF" 2>/dev/null
}
wr(){ [ -e "$1" ] || return 1; [ -z "$2" ] && return 1
      if [ "$DRY" = "1" ]; then echo "[dry] echo $2 > $1"; return 0; fi
      echo "$2" > "$1" 2>/dev/null; }

# ── 核 / 频率 / 权限 ──
all_cpus(){ n=0; for d in $CPU/cpu[0-9]*; do n=$((n+1)); done; echo "$n"; }
big_cores(){   # cpuinfo_max_freq 最大的那几颗
    mx=0
    for f in $CPU/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
        [ -r "$f" ] || continue; v=$(rd "$f"); [ -n "$v" ] && [ "$v" -gt "$mx" ] 2>/dev/null && mx=$v
    done
    out=""
    for f in $CPU/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
        [ -r "$f" ] || continue; v=$(rd "$f")
        [ "$v" = "$mx" ] && { n=${f#$CPU/cpu}; out="$out ${n%%/*}"; }
    done
    echo "$out"
}
mask_of(){ m=0; for n in $1; do m=$(( m | (1 << n) )); done; printf '%x' $m; }
all_mask(){ n=$(all_cpus); m=0; i=0; while [ "$i" -lt "$n" ]; do m=$(( m | (1 << i) )); i=$((i+1)); done; printf '%x' $m; }
uid_of_pkg(){ u=$(sed -n "s|^$1 ||p" "$BASE/uids" 2>/dev/null | head -n1)
    [ -n "$u" ] && { echo "$u"; return; }
    for b in /data/data /data/user/0 /data/user_de/0; do
        [ -d "$b/$1" ] && { u=$(stat -c %u "$b/$1" 2>/dev/null); [ -n "$u" ] && [ "$u" != "0" ] && { echo "$1 $u" >> "$BASE/uids"; echo "$u"; return; }; }
    done }
# 按 uid 找进程：优先 ps（toybox 的 C 实现，一次 fork；awk 扫 500 个 status 文件要几百毫秒）
pids_of_uid(){
    [ -z "$1" ] && return
    p=$(ps -A -o PID,UID 2>/dev/null | awk -v w="$1" 'NR > 1 && $2 == w { print $1 }')
    [ -n "$p" ] && { echo "$p"; return; }
    awk -v w="$1" '/^Uid:/{ if ($2 == w) print FILENAME }' /proc/[0-9]*/status 2>/dev/null | sed 's|/proc/||; s|/status||'
}
# 目标 uid 现在在不在前台：它的进程里有没有 oom_score_adj == 0 的
# （顶层应用的 adj 就是 0；可见 100、前台服务 200~500、缓存 700+、系统/常驻 -700~-1000）
fg_of_uid(){
    [ -z "$1" ] && { echo 0; return; }
    for p in $(pids_of_uid "$1"); do
        a=$(rd /proc/$p/oom_score_adj)
        [ "$a" = "0" ] && { echo 1; return; }
    done
    echo 0
}

# ── 存原值 / 还原 ──
save_orig(){
    [ -f "$ORIG" ] && return 0
    mdirs
    { echo "big=$(big_cores)"
      for c in $(big_cores); do
          echo "gov_$c=$(rd $CPU/cpu$c/cpufreq/scaling_governor)"
          echo "min_$c=$(rd $CPU/cpu$c/cpufreq/scaling_min_freq)"
          echo "max_$c=$(rd $CPU/cpu$c/cpufreq/scaling_max_freq)"
      done
      echo "peak=$(settings get system peak_refresh_rate 2>/dev/null)"
      echo "minr=$(settings get system min_refresh_rate 2>/dev/null)"
    } > "$ORIG" 2>/dev/null
}
do_restore(){
    [ -f "$ORIG" ] || { rm -f "$APPLIED"; return 0; }
    say=""
    for c in $(sed -n 's/^big=//p' "$ORIG" | head -n1); do
        g=$(sed -n "s/^gov_$c=//p" "$ORIG" | head -n1)
        mn=$(sed -n "s/^min_$c=//p" "$ORIG" | head -n1)
        mx=$(sed -n "s/^max_$c=//p" "$ORIG" | head -n1)
        [ -n "$mx" ] && wr "$CPU/cpu$c/cpufreq/scaling_max_freq" "$mx"
        [ -n "$mn" ] && wr "$CPU/cpu$c/cpufreq/scaling_min_freq" "$mn"
        [ -n "$g" ]  && wr "$CPU/cpu$c/cpufreq/scaling_governor" "$g"
    done
    rm -f "$ORIG" "$APPLIED"
    echo "已还原"
}

case "$1" in
conf)
    [ -f "$CONF" ] && cat "$CONF"
    ;;
set)
    [ -z "$2" ] && { echo "用法: perfmode.sh set <键> <值>"; exit 2; }
    setcfg "$2" "$3"
    echo "$2=$3"
    ;;
freqs)
    f=$(ls $CPU/cpu[0-9]*/cpufreq/cpuinfo_max_freq 2>/dev/null | tail -n1)
    [ -z "$f" ] && { echo "freqs="; exit 0; }
    d=${f%/cpuinfo_max_freq}
    list=$(cat $d/scaling_available_frequencies 2>/dev/null | tr ' ' '\n' | sort -rn | uniq | tr '\n' ',' | sed 's/,$//')
    mx=$(rd "$f")
    case ",$list," in *",$mx,"*) ;; *) list="$mx${list:+,$list}" ;; esac
    echo "freqs=$list"
    echo "max=$mx"
    echo "big=$(big_cores | tr -s ' ' | sed 's/^ //; s/ /,/g')"
    ;;
govs)
    f=$(ls $CPU/cpu[0-9]*/cpufreq/scaling_available_governors 2>/dev/null | tail -n1)
    echo "govs=$([ -n "$f" ] && cat "$f" 2>/dev/null | tr ' ' ',')"
    echo "cur=$([ -n "$f" ] && cat "${f%scaling_available_governors}scaling_governor" 2>/dev/null)"
    ;;
apply)
    mdirs
    app=$(cfg app); uid=$(cfg uid)
    [ -z "$uid" ] && [ -n "$app" ] && uid=$(uid_of_pkg "$app")
    freq=$(cfg freq); gov=$(cfg gov); aff=$(cfg affinity); rr=$(cfg refresh)
    [ -z "$freq" ] && freq=0
    [ -z "$rr" ] && rr=0
    save_orig
    if [ -f "$APPLIED" ]; then
        n=$(cat "$BASE/reverts" 2>/dev/null); n=$(( ${n:-0} + 1 ))
        echo "$n" > "$BASE/reverts" 2>/dev/null
        logline "重新应用（第 $n 次）"
    fi
    : > "$APPLIED"
    cores=$(big_cores)
    # ① CPU 锁频（超大核 min=max）
    if [ "$freq" != "0" ] && [ -n "$freq" ]; then
        for c in $cores; do
            d=$CPU/cpu$c/cpufreq
            hi=$(rd $d/cpuinfo_max_freq); lo=$(rd $d/cpuinfo_min_freq)
            f=$freq
            [ -n "$hi" ] && [ "$f" -gt "$hi" ] 2>/dev/null && f=$hi
            [ -n "$lo" ] && [ "$f" -lt "$lo" ] 2>/dev/null && f=$lo
            wr $d/scaling_min_freq "$f"; wr $d/scaling_max_freq "$f"
        done
        echo "CPU 锁频 $(awk -v f="$freq" 'BEGIN{printf "%.2fG", f/1000000}')" >> "$APPLIED"
    fi
    # ② 调速器
    if [ -n "$gov" ]; then
        for c in $cores; do wr $CPU/cpu$c/cpufreq/scaling_governor "$gov"; done
        echo "调速器 $gov" >> "$APPLIED"
    fi
    # ②b 关掉的项 → 从原值还原（用户点了"不动"就该真的不动）
    if [ -z "$gov" ]; then
        for c in $cores; do
            og=$(sed -n "s/^gov_$c=//p" "$ORIG" 2>/dev/null | head -n1)
            [ -n "$og" ] && wr $CPU/cpu$c/cpufreq/scaling_governor "$og"
        done
    fi
    if [ -z "$freq" ] || [ "$freq" = "0" ]; then
        for c in $cores; do
            omx=$(sed -n "s/^max_$c=//p" "$ORIG" 2>/dev/null | head -n1)
            omn=$(sed -n "s/^min_$c=//p" "$ORIG" 2>/dev/null | head -n1)
            [ -n "$omx" ] && wr $CPU/cpu$c/cpufreq/scaling_max_freq "$omx"
            [ -n "$omn" ] && wr $CPU/cpu$c/cpufreq/scaling_min_freq "$omn"
        done
    fi
    # ③ 线程亲和性（按 uid 找进程，全部绑到超大核）
    if [ -n "$aff" ] && [ -n "$uid" ]; then
        case "$aff" in
        big) mh=$(mask_of "$cores"); lbl="线程绑定 超大核";;
        all) mh=$(all_mask);        lbl="线程恢复 全核";;
        *)   mh=$(all_mask);        lbl="";;   # 关掉 = 恢复全核
        esac
        if [ -n "$mh" ]; then
            n=0
            for p in $(pids_of_uid "$uid"); do
                n=$((n+1))
                if [ "$DRY" = "1" ]; then echo "[dry] taskset -p $mh $p"
                else
                    taskset -p "$mh" "$p" >/dev/null 2>&1
                    for t in /proc/$p/task/[0-9]*; do [ -d "$t" ] && taskset -p "$mh" "${t##*/}" >/dev/null 2>&1; done
                fi
            done
            [ -n "$lbl" ] && echo "$lbl（$n 个进程）" >> "$APPLIED"
        fi
    fi
    # ④ 刷新率（复用 refresh.sh，保活也一起）
    if [ "$rr" != "0" ] && [ -n "$rr" ]; then
        R=/data/adb/modules/ksu_toolbox/bin/refresh.sh
        [ -f "$R" ] || R=/data/adb/modules_update/ksu_toolbox/bin/refresh.sh
        if [ -f "$R" ]; then
            sh "$R" lock "$rr" >/dev/null 2>&1
        else
            settings put system peak_refresh_rate "$rr" >/dev/null 2>&1
            settings put system min_refresh_rate "$rr" >/dev/null 2>&1
        fi
        echo "刷新率 ${rr}Hz" >> "$APPLIED"
    fi
    if [ -z "$rr" ] || [ "$rr" = "0" ]; then
        RR=/data/adb/modules/ksu_toolbox/bin/refresh.sh
        [ -f "$RR" ] || RR=/data/adb/modules_update/ksu_toolbox/bin/refresh.sh
        [ -f "$RR" ] && sh "$RR" restore >/dev/null 2>&1
    fi
    echo "state=on" >> "$APPLIED"
    logline "apply uid=${uid:-?} freq=${freq:-0} gov=${gov:-未设} aff=${aff:-未设} rr=${rr:-0}"
    echo "已应用（uid=${uid:-?}）"
    ;;
restore)
    do_restore
    R=/data/adb/modules/ksu_toolbox/bin/refresh.sh
    [ -f "$R" ] || R=/data/adb/modules_update/ksu_toolbox/bin/refresh.sh
    [ -f "$R" ] && sh "$R" restore >/dev/null 2>&1
    ;;
check)
    # 给桌面 App 用：一条命令拿到全部状态（App 每 2 秒问一次）
    app=$(cfg app); uid=$(cfg uid)
    [ -z "$uid" ] && [ -n "$app" ] && uid=$(uid_of_pkg "$app")
    echo "enabled=$(cfg enabled)"
    echo "app=${app:-}"
    echo "uid=${uid:-}"
    echo "freq=$(cfg freq)"
    echo "gov=$(cfg gov)"
    echo "affinity=$(cfg affinity)"
    echo "refresh=$(cfg refresh)"
    echo "menu=$(cfg menu)"
    echo "hud=$(cfg hud)"
    echo "fg=$(fg_of_uid "$uid")"
    echo "applied=$([ -f "$APPLIED" ] && echo 1 || echo 0)"
    ls=$(grep -v '^state=' "$APPLIED" 2>/dev/null | awk '{ print length($0), $0 }' | sort -rn | cut -d' ' -f2- | tr '\n' '|')
    echo "list=${ls%|}"
    # 配置里勾了哪些（HUD 常驻就靠这份 —— 不点"应用"也有东西显示）
    cl=""
    cf=$(cfg freq); [ -n "$cf" ] && [ "$cf" != "0" ] && cl="$cl|CPU 锁频 $(awk -v v="$cf" 'BEGIN{printf "%.2fG", v/1000000}')"
    cg=$(cfg gov); [ -n "$cg" ] && cl="$cl|调速器 $cg"
    ca=$(cfg affinity)
    [ "$ca" = "big" ] && cl="$cl|线程绑定 超大核"
    [ "$ca" = "all" ] && cl="$cl|线程绑定 全核"
    cr=$(cfg refresh); [ -n "$cr" ] && [ "$cr" != "0" ] && cl="$cl|刷新率 ${cr}Hz"
    cl2=$(echo "${cl#|}" | tr '|' '\n' | awk 'NF{print length($0), $0}' | sort -rn | cut -d' ' -f2- | tr '\n' '|')
    echo "cfglist=${cl2%|}"
    echo "hud_items=$(cfg hud_items)"
    echo "hud_title=$(cfg hud_title)"
    echo "font=$(cfg font)"
    echo "whitelist=$(cfg whitelist)"
    # ── 系统参数（给提示悬浮窗用，一条命令一起读回来）──
    big=$(big_cores | awk '{print $1}')
    [ -n "$big" ] && echo "cpu_khz=$(rd $CPU/cpu$big/cpufreq/scaling_cur_freq)"
    bmax=$(rd $CPU/cpu$big/cpufreq/cpuinfo_max_freq)
    [ -n "$bmax" ] && echo "cpu_max=$bmax"
    f=$(ls /sys/class/power_supply/*/current_now 2>/dev/null | head -n1)
    [ -n "$f" ] && echo "batt_ua=$(rd $f)"
    v=$(ls /sys/class/power_supply/*/voltage_now 2>/dev/null | head -n1)
    [ -n "$v" ] && echo "batt_uv=$(rd $v)"
    for z in /sys/class/thermal/thermal_zone*/temp; do
        t=$(rd "$z"); [ -n "$t" ] && [ "$t" -gt 1000 ] 2>/dev/null && { echo "temp=$(awk -v v="$t" 'BEGIN{printf "%.1f", v/1000}')"; break; }
    done
    # ── 保活：有几项被改回去了 ──
    df=0
    if [ -n "$freq" ] && [ "$freq" != "0" ]; then
        for c in $(big_cores); do cur=$(rd $CPU/cpu$c/cpufreq/scaling_max_freq); [ "$cur" != "$freq" ] && df=$((df+1)); done
    fi
    if [ -n "$gov" ]; then
        c0=$(big_cores | awk '{print $1}')
        [ -n "$c0" ] && { cur=$(rd $CPU/cpu$c0/cpufreq/scaling_governor); [ "$cur" != "$gov" ] && df=$((df+1)); }
    fi
    if [ -n "$rr" ] && [ "$rr" != "0" ]; then
        cur=$(settings get system peak_refresh_rate 2>/dev/null | sed 's/\..*//')
        [ "$cur" != "$rr" ] && df=$((df+1))
    fi
    echo "drift=$df"
    echo "reverts=$(cat "$BASE/reverts" 2>/dev/null | head -n1)"
    ;;
boost)
    # 清后台：只清【第三方 + 缓存态(oom_score_adj>=700)】的进程，
    # 不动前台、不动带服务的、不动白名单、不动目标应用。
    # 用的是系统自己的接口：ps / pm list packages / /proc —— 不需要什么特殊权限（root 下）。
    mdirs
    wl=",$(cfg whitelist),"
    app=$(cfg app); [ -n "$app" ] && wl="$wl$app,"
    before=$(awk '/MemAvailable/{print $2}' /proc/meminfo 2>/dev/null)
    n=0; killed=""
    for p in $(ps -A -o PID 2>/dev/null | awk 'NR > 1 {print $1}'); do
        adj=$(rd /proc/$p/oom_score_adj); [ -z "$adj" ] && continue
        [ "$adj" -lt 700 ] 2>/dev/null && continue          # 只清缓存态的后台
        u=$(sed -n 's/^Uid:[[:space:]]*//p' /proc/$p/status 2>/dev/null | awk '{print $1}')
        [ -z "$u" ] && continue
        pk=$(pm list packages -3 --uid "$u" 2>/dev/null | head -n1 | sed 's/^package://')
        [ -z "$pk" ] && continue                            # 不是第三方就跳过
        case "$wl" in *",$pk,"*) continue;; esac            # 白名单/目标不动
        if [ "$DRY" = "1" ]; then echo "[dry] kill -9 $p（$pk）"
        else kill -9 "$p" 2>/dev/null; fi
        n=$((n+1)); killed="$killed $pk"
    done
    after=$(awk '/MemAvailable/{print $2}' /proc/meminfo 2>/dev/null)
    freed=$(( (${after:-0} - ${before:-0}) / 1024 ))
    ks=$(echo $killed | tr ' ' '\n' | sort -u | grep . | head -6 | tr '\n' ' ')
    echo "清掉 $n 个后台进程，可用内存 +${freed}MB"
    [ -n "$ks" ] && echo "涉及：$ks"
    logline "boost 清掉 $n 个（+${freed}MB）涉及：$ks"
    ;;
log)
    tail -n "${2:-80}" "$PLOG" 2>/dev/null || echo "(还没有日志)"
    ;;
clearlog)
    : > "$PLOG"; echo 已清空
    ;;
status)
    app=$(cfg app); uid=$(cfg uid)
    echo "enabled=$(cfg enabled)"
    echo "app=${app:-}"
    echo "uid=${uid:-}"
    echo "freq=$(cfg freq)"
    echo "gov=$(cfg gov)"
    echo "affinity=$(cfg affinity)"
    echo "refresh=$(cfg refresh)"
    echo "menu=$(cfg menu)"
    echo "hud=$(cfg hud)"
    echo "applied=$([ -f "$APPLIED" ] && echo 1 || echo 0)"
    echo "procs=$(pids_of_uid "$uid" | grep -c .)"
    ;;
list)
    # 给 App 的"功能提示列表"用：已打开的功能，一条一行（字多的在前）
    [ -f "$APPLIED" ] || exit 0
    grep -v '^state=' "$APPLIED" | awk '{ print length($0), $0 }' | sort -rn | cut -d' ' -f2-
    ;;
*)
    echo "用法: perfmode.sh {apply|restore|status|check|list|freqs|govs|conf|set <键> <值>|boost|log [n]|clearlog}"
    exit 2
    ;;
esac
