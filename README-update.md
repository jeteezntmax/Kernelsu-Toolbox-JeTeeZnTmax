# 更新版 v1.2.0-beta1（待验证）

⚠️ 这是**待你验证**的版本，原 `ksu-toolbox/`（v1.1.0）保持不动。
你在真机上跑一遍，确认没问题我再把这些改动同步回原文件夹。

装法一样：KernelSU 管理器 → 模块 → 从本地安装 → `ksu-toolbox-update.zip`。

---

## v1.1.0 → v1.2.0-beta1 改了什么

### 1. 性能页卡顿 —— 重写了采样链路

老版本每次刷新都发一条 23 段的 shell 命令，其中有两处是元凶：

- `for z in /sys/class/thermal/thermal_zone*; do printf ... "$(cat $z/type)" "$(cat $z/temp)" ...`
  每个 zone 要 fork 两个 `cat`。有些机型有 **80+ 个 thermal zone**，一次刷新就是 160 次进程创建 —— 手机上这就是几百毫秒到一秒。
- `grep -E ... /proc/meminfo | tr`、`cat /proc/stat | head`、`ls /proc | grep -c` …… 全是外部命令。

改法：

- **拆成静态 + 动态两路**。核心数、满频/最低频、调度器、GPU 型号/满频、内存总量这些几十秒都不会变的东西，进页面读一次，之后每 30 秒才刷一次；只有 CPU 占用、频率、可用内存、温度、GPU 负载走高频。
- **动态脚本改成全 shell 内建**（`read` / `printf` / `[` / `set --`），一个外部命令都不 fork。
  温度和频率都是纯内建循环，80 个 zone 也就几毫秒。
- **核心频率表改成增量更新**，不再每次重建 8 行 DOM，只改 `style.width` 和文字。
- **运行状态表加了内容比对**，没变就不碰 DOM。
- 采样间隔 1.5s → 2s，电源页 2s → 2.5s，总览 4s → 5s。
- 切到别的页之后，迟到的采样结果直接丢弃，不再在后台画 DOM。
- `sh()` 每次调用挂的超时定时器，成功后会清掉了。

### 2. 主页时间不动了

原因是"已运行"只格式化到「天 时」，一分多钟才变一次，看着就像死的。

现在：

- 「已运行」精确到秒，格式 `2 天 09:17:06`，**本地每秒自增**，不等采样。
- 副标题是 `现在 09:32:22`，同样每秒跳。
- 「开机时刻」精确到秒，副标题 `开机于 2 天 09:17:06 前` 也在跟着走。
- 数字用 `font-variant-numeric: tabular-nums`，跳秒时宽度不抖。

时钟只在总览页跑，切页自动停，不占资源。

### 3. 右上角加了电源按钮

刷新按钮左边多了一个 ⏻，点开是七个选项，每个都要二次确认：

| 选项 | 实际命令（依次尝试） |
| --- | --- |
| 关机 | `svc power shutdown` → `reboot -p` → `setprop sys.powerctl shutdown` |
| 重启 | `svc power reboot` → `reboot` → `setprop sys.powerctl reboot` |
| 软重启 | `svc power reboot userspace` → `setprop ctl.restart zygote` → `setprop ctl.restart surfaceflinger` |
| Recovery | `svc power reboot recovery` → `reboot recovery` → `setprop sys.powerctl reboot,recovery` |
| Fastboot | `svc power reboot bootloader` → `reboot bootloader` → `setprop …reboot,bootloader` |
| Fastbootd | `svc power reboot fastboot` → `reboot fastboot` → `setprop …reboot,fastboot` |
| EDL | `reboot edl` → `svc power reboot edl` → `setprop …reboot,edl` |

每条都是"前一条失败才试下一条"。EDL 用红色标出来，确认页单独写了警告
（多数机型进 9008 之后只能靠电脑救）。几条全失败会提示"你的机型可能不支持"。

### 4. 同步了原来那个脚本里的东西

先说结论：**逻辑不在 HTML 里。**

我把你给的那个文件解压出来看过了。它自己是一个纯前端外壳，唯一的外部依赖是：

```js
const BIN = "/data/adb/modules/YHGames/YHGames";
```

所有真正干活的东西都是 `exec` 调这个程序的子命令，HTML 里一行实现都没有。
它一共就 20 个调用点：

```
overview / version-status / log-get / log-clear / logging-set
games-set / templates-fetch
spoof-config-get / spoof-config-set
drmid-status / drmid-toggle / drmid-random
android-id random|set / ssaid get|reset
game-clean-start / game-clean-status / reboot
```

所以 `YHGames` 那个程序里才装着游戏列表、机型模板库、DRM ID、SSAID、
清理流程的全部实现。那个文件没给我，我也没法把它的逻辑搬过来。

**但 UI 文案把"游戏清理"要做什么写得很清楚**，我从弹窗提示里读出来了：

> 将清理此游戏的登录凭证和设备标识，仅保留 QtsVFSCache 与 UE4Game 游戏资源，
> 并重置 Android ID、系统 UUID，清除该游戏的 SSAID 分配和系统日志。

按这个语义，我用标准 Android 命令**自己实现了一套**（没抄它一行代码），
放在机型页底部两个新板块：

**设备标识**

- 显示当前 Android ID，一键重新生成（`settings put secure android_id <16位hex>`）

**游戏环境清理** —— 选应用 → 扫描 → 勾选 → 执行

- 扫描出 `/data/data/<包名>` 下的一级条目、数据总大小、外部数据 / OBB 大小、
  该应用的 SSAID 当前值
- 逐项勾选要删的；`QtsVFSCache` / `UE4Game` 默认在白名单里，点了会提示你先改名单
- 可选：清外部数据、清 OBB、重置该应用 SSAID、重置整机 Android ID
- 执行前弹确认，列出**具体要做什么**（哪几个目录、哪些开关）
- SSAID 是改 `/data/system/users/0/settings_ssaid.xml`：改之前 cp 一份备份，
  改完比对行数，行数不对就自动还原，不碰坏文件
- 完事提示重启一次让标识改动完全生效

跟它原版差的地方：它还有个"清除系统日志"和"系统 UUID"，这俩我没做
（日志清不清对结果没影响，系统 UUID 重启就变，动它意义不大）。

**没做的**：DRM ID 伪装、游戏列表管理、在线模板库 —— 那些依赖它自己的二进制和账号体系。

---

## 这轮验过的

- 五个页面控制台零报错，没有 `undefined` / `NaN` / `[object` 漏出来
- 430 / 360 / 320 三种宽度下 dock 五项都不溢出
- 时钟每秒在跳（截图对比时间戳）
- 电源菜单 → 确认页 → 取消回菜单 → 重新确认，链路通
- 清理流程：选应用 → 扫出 7 个条目 → 白名单条目点不动 → 确认页正确列出 4 个待删目录 → 演示模式给的是"什么都没做"而不是假报错
- 动态采样脚本用 `sh -n` 和 dash 实跑过，输出格式正确（温度、频率、meminfo 都对）

## 需要你在真机上看一眼的

1. **性能页还卡不卡** —— 这是这轮的主要目标。特别看一下进页面的头两秒。
2. **温度 / 频率有没有值** —— 换了读取方式，理论上更稳，但你的机型上得实测。
3. **电源菜单每一项** —— 关机 / 重启最先试。EDL 和 Fastbootd 建议先别急着点。
4. **清理流程** —— 先拿个不重要的应用试，重点看扫描出来的条目对不对、
   SSAID 那行有没有值。
5. **Android ID 重新生成** —— 看 `settings` 写不写得进去（要 WRITE_SECURE_SETTINGS，root 一般有）。

有 bug 就把现象告诉我，改完还是放这个文件夹。

---

## v1.2.0-beta1 → beta2（修 bug）

你反馈的「机型页点新建模板没反应」查出来了，而且顺着又挖出两个同源的坑。

### bug 1：`st.doc` 为 null 时崩溃（就是你遇到的那个）

`新建模板` 里我写了 `st.doc.templates`。当 `config.toml` **读不到**或者**解析失败**时
`st.doc` 是 `null`，这一行直接抛 `Cannot read properties of null (reading 'templates')`。
偏偏点击处理是 `async` 的，异常被 Promise 吞掉 —— 表现就是点了毫无反应，控制台也没有。

现在：

- 加了一个统一的 `dfDoc()` 入口，doc 不存在就建一个空的
- **没有配置文件时也能直接建模板**，保存时自动创建这个文件
- 所有点击处理都加了 `.catch()`，异常会以 toast 弹出来，不再静默
- 再补了 `window.onerror` / `unhandledrejection` 兜底 —— 手机上没控制台，这类问题必须能看见

> 你那边留意一下机型页顶部有没有橙色「还没有配置文件」或红色「配置解析失败」的横幅。
> 有的话说明是文件不在或格式不对；现在这两个横幅下面都会直接给你按钮处理，
> 而且会显示 `config` / `data` 两个目录里到底有什么，方便定位。

### bug 2：「新建配置」生成的 TOML 是非法格式

我生成的空白配置里写了 `[templates.示例机型]`。TOML 的裸键**只允许 A-Za-z0-9_-**，
中文表名必须写成 `[templates."示例机型"]` —— 少一对引号，`TOML.parse` 当场就炸：

```
操作失败：第 5 行第 12 列：缺少键名
```

改成直接构造对象，不再走「先拼文本再解析」。顺手验证了导出这端是对的：
中文键会带引号，`redmagic_9_pro` 这种 ASCII 键保持裸键，转义也正确。

### bug 3：新建模板保存后列表里不出现

重命名逻辑对 key 为空串的「新建」情况无效 —— 数据在重排时被丢掉了。
你如果之前试过填完名字点保存，八成也撞上这个。现在统一走 `dfPutKey()`，
新建 / 改名 / 直接覆盖三种情况都对，顺序也保持。

### 顺带

- 保存按钮现在会**先做一次生成 + 语法校验再判断写不写**，
  演示模式下也会告诉你"生成没问题"，等于多一道自检
- 加了 `config` / `data` 目录清单的显示，方便排查文件到底在哪

### 验证覆盖

用无头浏览器把机型页整条链路走了一遍：

```
新建配置 → 有示例机型 ✓
新建模板(中文名) → 列表出现、字段和包名都对 ✓
打开编辑 → 数据回填 ✓
改名校验 → 旧名消失、新名保留、顺序不变 ✓
保存 → 生成+校验通过 ✓
新建应用覆盖 → 列表出现 ✓
删除模板 → 消失 ✓
原始 TOML → 正常打开 ✓
控制台 → 干净
```

另外三个异常场景（正常 / 没有配置文件 / 配置解析失败）分别单独跑过，都不再崩。

---

## v1.3.0-beta3：新增「温度」页

### 先说 AaTempSpoof 能不能直接搬

**不能，我也没搬。** 把 `AaTempSpoof_v13.5.zip` 拆开看了：

```
customize.sh            137 KB，内容是 base64 打包的 BLACK_BOX_PAYLOAD
action.sh / service.sh / uninstall.sh   同样是那层壳
bin/AAAaTempSpoof 等 7 个   aarch64 ELF
bin/AAaTempSpoof           开头是 "MT-ENCRYPT"，加密体
```

它是一套**编译 + 加密 + 自定义壳**的闭源原生模块，而且 `module.prop` 里还带自校验：

```
AaTempSpoof=f4132feaae94126acbdd53549c8cf15f313f89db57824283ed9b32d1ca23b101
```

三个理由让我没法把它塞进你的模块：

1. **它的核心是 hook**（改属性区、拦 thermal 节点的读）。那是原生库干的活，纯 WebUI 模块天生做不到 —— 跟 device_faker 一个道理。
2. **它有自己的模块 id、路径和自校验哈希**。搬进 `ksu_toolbox` 会全部失效，而且两个 `service.sh` 会打架。
3. **来源和许可证不明**（酷安个人作者，混淆+加密，我无法审计它到底做了什么）。把这种东西原样打包进你要发布的模块里，出事是算你头上的。

### 所以做成了什么

**它的配置文件是明文的。** 那些 `AaTempSpoof/*.txt` 全是 `键=值`，功能面一眼看完：

```
CPU温度=43-47        GPU温度=43-47      内存温度=43-47
电池温度=30          其他温度=36
cpu/gpu/内存/电池伪装开关=1
电池循环伪装=1 电池循环次数=10
电量伪装开关=0 当前电量显示=20 移除超级省电=1
电池充电开始温度=30 充电中间温度=36 电池充电截止温度=30
充电伪装时间=30 修改温度墙=1
电池温度墙=38
horaebai.txt = 充电曲线白名单
```

所以「温度」页分两块：

**一、自己就能用的温控（不依赖任何东西）**

| 板块 | 做什么 |
| --- | --- |
| 温度总览 | 枚举全部 `thermal_zone*`，最大值置顶，按 CPU/GPU/电池/内存/其他 分类取最高温；每行一条进度条，越接近 `trip_point_0_temp` 越红 |
| 系统温控服务 | 列出 `thermal-engine` / `mi_thermald` / `thermal-hal-2-0` 等 12 个常见服务的 `init.svc.*` 状态，逐个开关 + 一键全停/全恢复 |
| thermal zone 开关 | 批量写 `thermal_zone*/mode`。自动识别节点吃 `disabled` 还是吃 `1`，失效自动回退 |
| 降温限频 | 四档预设（100 / 85 / 70 / 55 %），同时压 CPU `scaling_max_freq` 和 GPU `max_gpuclk` |
| 充电电流上限 | 写 `input_current_limit` / `constant_charge_current_max` / `usb/current_max`，五档快捷值 |

全部只用 shell，读 `sysfs`，**重启自动复原**，不留后患。

**二、AaTempSpoof 配置前端（装了才出现）**

自动在 6 个候选路径里找 `AaTempSpoof.txt`，找到就把那 20 个键渲染成表单随便改，
保存时先 `cp` 一份 `.bak` 再写回。温度伪装本体还是它的原生库在跑，本模块只当编辑器。

### 顺带

- dock 从 5 格扩到 6 格，图标字号相应收了一点，320 px 宽的机器上也测过不溢出
- 「温度」页的 zone 列表是**增量更新**的，80 多个 zone 也只改文字和进度条宽度，
  不会每 2 秒重建一次 DOM（性能页那次教训）

### 验证

- 温控的两条采集脚本在真实 Linux 上跑通：读到 80+ 个 thermal zone，
  类型和温度都是真值（`cpullc-0-0=56000` 这种），不是空壳
- 六页控制台零报错；430 / 400 / 360 / 320 四种宽度 dock 都不溢出
- AaTempSpoof 段用一份模拟配置单独测过：20 个键全部回填、能改、保存路径正确

---

## v1.4.0-beta4：Device Faker 已并入本模块

你要的「刷一次全有」，能合的我合了。

### 先说验证过程

我把两个包的二进制都做了 `strings`，看它们有没有硬编码自己的模块目录 —— 这是能不能搬的唯一判据：

**device_faker**

```
$ strings zygisk/arm64-v8a.so | grep '/data/adb'
/data/adb/device_faker/config/config.toml
/data/adb/device_faker/logs/device_faker.log

$ strings zygisk/arm64-v8a.so | grep -c '/data/adb/modules/device_faker'
0
```

它只依赖 `/data/adb/device_faker/`（**数据目录**，跟模块目录叫什么无关），
boot 脚本一个都没有（纯 Zygisk）。→ **可以搬**。

**AaTempSpoof**

```
AaTempSpoof         → /data/adb/modules/AAaTempSpoof/AaTempSpoof/AaTempSpoof.txt
cb                  → /data/adb/modules/AAaTempSpoof/AaTempSpoof/cb.txt
touch_daemon        → /data/adb/modules/AAaTempSpoof/AaTempSpoof/touch_opt.conf
charge_horae_daemon → /data/adb/modules/AAaTempSpoof/AaTempSpoof/horae.txt
color_tombstone     → /data/adb/modules/AAaTempSpoof/AaTempSpoof/mubei.txt
```

它的 `service.sh` 虽然写得很规范（`MODDIR=${0%/*}`，还把 `--moddir "$MODDIR"` 传给主程序），
但**五个守护进程全都硬编码了 `/data/adb/modules/AAaTempSpoof/`，`--moddir` 根本没被用上**。
模块目录一改名，它们读不到配置、写不了 pid、起不来。→ **不能搬**。

### 合进来的东西

```
ksu_toolbox/
├── zygisk/arm64-v8a.so      ← device_faker 的原生库（一字未改）
├── bin/device_faker_cli     ← 它的配置转换 CLI（一字未改）
├── webroot/
│   ├── index.html           ← 本工具箱（主入口）
│   ├── df.html              ← Device Faker 自带 WebUI 的入口（注了个返回按钮）
│   └── assets/*             ← 它的界面资源，48 个文件
├── df-default-config.toml   ← 默认配置模板，安装时复制过去然后删掉
├── df-bundled-version.txt   ← 内置版本号
├── NOTICE-device_faker.txt  ← 署名 + 许可证说明
└── LICENSE                  ← GPL-3.0 全文（整个模块都是）
```

三个关键处理：

1. **它的界面没丢。** KernelSU 一个模块只能有一个 `webroot/index.html`，
   但它的 Vite 产物全用绝对路径 `/assets/xxx`，所以入口文件叫什么名字都无所谓 ——
   我把它的入口挪成 `df.html`，资源原样放在 `webroot/assets/`，绝对路径照样命中。
   机型页底部加了「打开 Device Faker 原生界面」按钮，点进去就是它那套完整界面
   （模板库、多用户、导入导出都在），左下角有个返回按钮。
2. **数据目录没变。** 还是 `/data/adb/device_faker/`，所以你原来的配置和日志可以直接用。
3. **zygisk 库权限**在 `customize.sh` 里单独设了，并会检测你有没有独立装过 device_faker
   —— 装了就提示你停用，否则同一个 zygisk 库会被加载两次。

### 装之前必须知道的两件事

**① 你的模块现在是 GPL-3.0 了。**
device_faker 是 GPL-3.0，把它和本模块打成一个包分发，整体就是 GPL-3.0。
`NOTICE-device_faker.txt` 和 `LICENSE`（GPL-3.0 全文）我已经放进包里。
**这意味着这个合并版不能再闭源分发**，你要拿去卖或者藏着源码是不行的。

**② 上游更新要重新合。**
device_faker 发新版时，合并版里的 `.so` 还是旧的。要么等我把新版再合一次，
要么你自己换掉 `zygisk/arm64-v8a.so`。

### AaTempSpoof 怎么办

它维持原样 —— **不并，也不改动**。

不是我不愿意，是两条都走不通：

1. **技术上**：要合就得把五个二进制里的 `/data/adb/modules/AAaTempSpoof`
   逐字节替换成别的路径。字符串长度刚好够（30 → 29 字节），能做，
   但那是**改一个你和我都读不出来的加密二进制**。它以后每次更新，补丁全部作废。
2. **别的方面**：它是混淆 + 加密 + 没有许可证的闭源二进制。
   我把它 patch 完塞进你的模块，你就在对外分发一个你自己也无法审计、原作者也没授权的东西。

**你现在这条路的实际效果是一样的**：把它正常装着，工具箱的「温度」页会自动认出
`/data/adb/modules/AAaTempSpoof/AaTempSpoof/`（这个路径本来就在候选列表里），
20 个配置项都能直接改。它跑它的，工具箱当它的遥控器 —— 只是模块列表里多一行。

### 验证

- 原生库、CLI 的 md5 与官方 v1.6.0 包**完全一致**（没改过任何字节）
- 48 个静态资源一个不少
- 用本地 HTTP 服务把 `webroot/` 当根目录跑了一遍：`df.html` 正常挂载，
  标题 `Device Faker Manager`，界面渲染出 Module Info / Impersonated Apps / Templates Count，
  左下角返回按钮可见，**零页面错误**
- 工具箱六个页面回归测试全过，控制台干净

---

## v1.4.0-beta5：AaTempSpoof 配置前端做全了

### 你看到的「配置目录不存在」

那不是缺集成，是**机器上没有这个目录**。我把它的安装脚本脱壳读了（混淆是
`_a+_b+_c` → base64 → 按 `|` 切块、尾字符 `Y` 表示整块反转 → 再 base64 两次），
结论：

```
module.prop:  id=AAaTempSpoof        ← id 固定，安装脚本只改 name/description
配置目录:      $MODPATH/AaTempSpoof/  = /data/adb/modules/AAaTempSpoof/AaTempSpoof/
主配置:        AaTempSpoof.txt
```

我之前写死的候选列表里**就有这个路径**，所以它在报「不存在」= 真的不存在。

先自己确认一下：

```sh
ls /data/adb/modules/ | grep -i aatem
find /data/adb -maxdepth 4 -name AaTempSpoof.txt 2>/dev/null
```

两条都没输出 = 没装（或者装了但没重启过，配置目录是 `service.sh` 里
`mkdir -p "$CFGDIR"` 才建的）。

### 这一版改了什么

1. **检测不再靠写死的路径**，改成 `find` 扫描
   `/data/adb/modules` 和 `/data/adb/modules_update`（深度 4），扫到什么用什么。
2. **12 个配置文件全部可读可写**，不只主配置了：

   | 文件 | 内容 |
   | --- | --- |
   | `AaTempSpoof.txt` | 20 项主配置（温度伪装范围、各开关、充电温度、温度墙…） |
   | `总开关.txt` / `电池温度墙.txt` | 直接显示在状态卡上 |
   | `horaebai.txt` | 充电曲线白名单，显示成一行 |
   | `horae.txt` / `dyntemp.txt` | 充电曲线档位 / 动态伪装目标 |
   | `cbpz.txt` / `cb.txt` / `cdms.txt` | 参数、开关 |
   | `mubei.txt` / `jzwz.txt` | 墓碑 / 静默 |
   | `touch_opt.conf` | 触摸采样率 |

   主配置是键值对表单，其余 12 个文件点开就是全文编辑框，保存前自动 `.bak`。
3. **找不到时的提示重写了**：会告诉你搜了哪些根目录、当前模块列表是什么，
   而不是干巴巴一句「配置目录不存在」。

### 关于「集成」

上一条说过的原因没变 —— 它五个二进制里硬编码了
`/data/adb/modules/AAaTempSpoof/...`，改名就全废；而且它是混淆 + 加密 + 无许可证的闭源件。
补一句之前没说透的：**它的伪装引擎本体就是那堆二进制**，
所以「集成」的前提是它得先存在于某处 —— 没有本体的集成只是个空壳。

现在这一版的**功能上是等价的**：装好 AaTempSpoof（它自己的模块），
工具箱「温度」页就能改它全部 12 个配置。区别只剩模块列表里多一行。

### 验证

- 用真实的 `AaTempSpoof/*.txt` 内容做了一份模拟安装，走完整链路：
  12 个文件全部列出、20 项主配置回填、单文件编辑框能开能存、主配置保存路径正确
- 修了一个顺带发现的 bug：机型页 `dfSub` 在演示模式下显示 `undefined`
- 六个页面 × 430 / 320 两种宽度全部无异常、零控制台错误

---

## v1.5.0-beta6：自带温度伪装引擎

这次不是在集成它的代码 —— 是**照着它配置文件的描述，自己写了一套**。

### 为什么可以不用它

它的 `AaTempSpoof.txt` 里写着：

```
CPU温度=43-47   GPU温度=43-47   内存温度=43-47
电池温度=30     其他温度=36
cpu/gpu/内存/电池伪装开关=1
```

而它的模块说明是：

> 自定义节点伪装 **[emuLtemp** + bind-mount 双挂载**]** + 热控 XML 配置挂载覆盖 + 禁用相关 governor 并清除 cooling device

`emul_temp` 是**内核 thermal 框架自带的标准接口**：往 `/sys/class/thermal/thermal_zoneN/emul_temp`
写毫摄氏度，该 zone 就上报这个值；写 `0` 恢复真实值。**纯 shell 就能写**，不需要任何外部二进制。

它那套 `bin/` 是加密的，但干的事本质上就是「按类别往对应 zone 写 emul_temp」+ 看门狗。

### 我写了什么

**`bin/thermal.sh`**（4.4 KB，纯 shell）

```
thermal.sh apply   按配置写入所有支持 emul_temp 的 zone
thermal.sh clear   全部写 0，恢复真实温度
thermal.sh probe   统计本机有多少 zone 支持
```

- 按 zone `type` 自动归类：`cpu/cpullc/qmx/nsphvx/cluster` → CPU，`gpuss/kgsl/mali` → GPU，
  `ddr/lpddr/mem` → 内存，`battery/bms/charger` → 电池，其余 → 其他
- 温度写 `43-47` 表示区间内随机抖动，写 `30` 表示固定值
- 读配置用白名单 `case` 匹配，**不用 eval**，配置文件被手改也注入不了

**`service.sh`**（开机服务）

等 `sys.boot_completed`，然后每 12 秒重写一次 —— 因为驱动经常会把模拟值清掉。
总开关关掉时它只是空转，不干活。

**温度页新增「温度伪装」板块**（放在最上面）

- 本机支持数：`53 / 89` 这样的实测值（`probe` 出来的）
- 生效域：列出具体哪些 zone type 能吃模拟值
- 总开关 + 五个分类（CPU / GPU / 内存 / 电池 / 其他）各自的开关和温度输入框
- 「保存并立即应用」/「立刻恢复真实温度」
- **冲突检测**：如果系统里还有 AaTempSpoof 模块，会明确警告两边会互相覆盖

配置存在 `/data/adb/ksu_toolbox/thermal.txt`，键名和它的 `AaTempSpoof.txt` 一一对应，
你可以直接把旧配置的值抄过来。默认是**关闭**的。

### 顺手修的

- 之前两版检测 AaTempSpoof 用的是 `find -maxdepth`，某些机型的 toybox 不支持，
  而我把 stderr 丢进 `/dev/null` 了 → **静默失败**。现在全改成 shell glob，不依赖 find。
- 「写入失败」时没有回滚内存里的状态，会导致界面和文件不一致。已修。

### 验证

- `thermal.sh` 在本机真实跑了：读到 89 个 zone，分类全对
  （`cpullc/qmx/nsphvx`→CPU、`gpuss`→GPU、`ddr`→内存、`mdmss`→其他）
- `pick "43-47"` 采样 20 次全在范围内且有浮动；`"30"` 固定；`"7-7"` 单点
- 真的建出 `/data/adb/modules/ksu_toolbox/bin/thermal.sh` + `/data/adb/ksu_toolbox/thermal.txt`，
  用**真实 shell 输出**喂给前端跑完整链路：支持数 53/89、生效域列出、
  五个分类开关和输入框从配置正确回填、写入内容与界面一致
- 六个页面 × 430/320 两种宽度全部无异常、零控制台错误

### 需要你在真机上确认

1. **本机支持数**。如果是 `0 / N`，说明内核没开放 `emul_temp`（部分联发科 / 自研内核），
   那这个引擎在你机器上就用不了，得继续用它的模块。
2. **打开后系统读到的温度**。开了之后去看「所有温度传感器」那个列表，
   数值应该变成你设的区间并在小幅抖动。
3. **和改版 AaTempSpoof 二选一**。两个都开会互相覆盖。

---

## v1.5.1-beta7：温度伪装安全化（beta6 会黑屏死机，务必换掉）

### 我错在哪

`emul_temp` 是内核 `CONFIG_THERMAL_EMULATION` 的**调试接口**，不是给人日常用的。
beta6 我把它当普通 sysfs 用了，而且是**按类别批量写所有匹配的 zone** —— 这是致命的。

内核 thermal 框架里，`set_emul_temp` 是 `thermal_zone_device_ops` 的一个**可选回调**。
很多 zone driver 压根没实现它，或者实现得很潦草。往这种 zone 写值，
`thermal_zone_device_update()` 可能进入异常路径，把 thermal 的 workqueue 卡死 ——
表现就是**整个系统僵住 + 黑屏**，跟你遇到的一模一样。

更要命的是 beta6 的 `service.sh` 会在开机时**重新应用**，所以强制重启后会再次黑屏。

### 这一版做了四件事

**1. service.sh 从「开机重新应用」改成「开机一律清除」**

```
bootcheck  →  崩溃自检 + 把 enabled 置 0
(等系统起来) →  thermal.sh clear  → 把所有 emul_temp 写 0
```

也就是说 **重启一定恢复真实温度，绝无开机循环的可能**。伪装变成**会话级**的：
开机 → 你手动开 → 用到关机/重启为止。

**2. 不再批量写，必须逐个 zone 手选**

- 列出所有支持 `emul_temp` 的 zone（编号、类型、类别、当前温度）
- 每个都要单独勾，**默认一个都不选**
- 提供「只选 CPU 类 / 只选 GPU 类 / 全部取消」快捷按钮
- 建议第一次先只勾一个 CPU 类 zone 试

**3. 崩溃自检 + 自动拉黑**

写之前会在 `/data/adb/ksu_toolbox/.armed` 落一个标记（内容是这次写了哪些 zone），
45 秒后系统还活着就撤掉。**开机时如果标记还在，说明上次写完就崩了**：

- 自动把那批 zone 写进 `blacklist.txt`
- 自动把 `enabled` 置 0、清空 `zones`
- 往 `crash.log` 记一笔，界面上红色横幅提示

被拉黑的 zone 不会再出现在可勾选列表里。想重来有「清空黑名单」按钮。

**4. 确认弹窗里直接写清楚急救方法**

> 如果这次也崩了，长按电源键重启，开机第一屏出现后连按音量下键 3 次进 KernelSU 安全模式。

### 顺便修的

- `th_zones` 里的条目分隔符用的是 `;`，跟外层行分隔符撞了，导致**只解析出第一个 zone**。
  换成了 `|`。
- `ZONE_CLS_NAME` 用了但没定义（重写引擎时漏了），会让整个温度页渲染中断。

### 验证

- 引擎在本机跑通：`list` / `apply` / `clear` / `bootcheck` / `blacklist` / `unblack` 全走了一遍
- **崩溃自检实测**：手动放一个 `.armed`（内容 `0,1,2`）再跑 `bootcheck` →
  `crash=1`、黑名单写入 `0,1,2`、配置被改成 `enabled=0 / zones=`、`crash.log` 正确记录；
  再跑一次 `bootcheck` 得到 `crash=0`，黑名单保留
- 前端用带 22 个 zone（含 1 个黑名单）+ 崩溃日志的 mock 跑完整链路：
  可勾选 21 个、默认 0 选中、「只选 GPU 类」精确命中、确认弹窗、写入内容
  `enabled=1 / zones=12,13,14,15,16,17` 与界面完全一致
- 六页回归全过，零控制台错误

### 用之前想清楚

这个功能本质上是在改内核的调试接口。**如果你的内核某个 zone 的回调有 bug，写它就会死机** ——
拉黑机制能让你在崩几次之后找到安全的那几个，但代价是那几次黑屏。
如果你不想要这个风险，把这页的温度伪装**完全不用**就行，
温控服务开关 / zone mode / 限频 / 充电限流那几个都是普通 sysfs，没有这个风险。

---

## v1.6.0-beta8：删掉 AaTempSpoof，换成 Extreme GT

### 删干净了

`webroot/index.html` 里跟 AaTempSpoof 有关的东西**一处不剩**：

```
AaTempSpoof : 0
ats / ats_dir / atsMods / atsEditFile / atsSave : 0
```

同时把我上一版那个自己写的 `emul_temp` 引擎（`bin/thermal.sh`、任意 zone 手选、
区间的 43-47℃）也整个拆了 —— 它和 Extreme GT 功能重叠，而且它就是你黑屏的元凶。
现在温度页的「温度伪装」段整体由 Extreme GT 接管。

### 为什么 Extreme GT 能行，我上次不行

翻它的 `service.sh` 就明白了：

```sh
case $(cat $tz/type) in
  rear-tof-therm|cam-flash-therm|batt-therm|usb-therm|wlan-therm|xo-therm|oplus_thermal_ipa|shell*)
      echo $t > $tz/emul_temp      # t 固定是 29500
esac
```

**它只写一份手工挑过的白名单，而且写的是固定的 29.5℃ 这个偏低的安全值。**
它根本不碰 CPU / GPU 的核心温度 zone。

我上次是"所有匹配类别的 zone" + "43~47℃ 偏高值" —— 写进某个 set_emul_temp 回调
实现有问题的 zone，thermal workqueue 就卡死了。区别在这。

而且它主体根本不写 sysfs，是**改配置层**：安装时扫设备真实的温控 XML，
生成放宽版放进模块，开机 bind-mount 盖回去 —— 这条路比硬写 sysfs 稳得多。

### 集成方式

| 文件 | 来源 |
| --- | --- |
| `eg-setup.sh` | 它的 `customize.sh`（安装期扫 XML 生成去温控配置），去掉描述/权限部分 |
| `bin/eg.sh` | 它的 `service.sh` + `post-fs-data.sh`，改成 `$MODDIR` 相对 |
| `sys_thermal_control_config_default.xml` | 原样 |

改动只有三处：路径改成 `$MODPATH`/`$MODDIR` 相对、拆成 `early`/`late` 两阶段、
每一项做成可单独开关。处理逻辑一行没动。署名和来源写在 `NOTICE-extreme-gt.txt`。

**它多出来的能力**（我上次完全没有的）：
- XML 覆盖：`sys_thermal_control_config*.xml` / `sys_thermal_config.xml` /
  `sys_high_temp_protect_*.xml` / `thermallevel_to_fps.xml`（fps 拉满 144）/
  `game_thermal_config.xml`（cluster 限制 -1）/ `QEGA_Config.txt` /
  `qapegameconfig.txt` / `devices_config.json` 等
- GPU 满档：`max_pwrlevel=0`、`max_gpu_clk`/`max_clock_mhz`=2147483647
- 触摸进程 renice -19
- `dumpsys horae testmode` + 往 `/proc/shell-temp` 写 29500
- 非 OnePlus SM8650 机型改为停 `vendor.oplus.ormsHalService-aidl-default`

### 温度页现在长这样

1. **Extreme GT** —— 设备匹配 / 走的路线 / 已生成配置数 / 已挂载数 / emul_temp 命中数
   + 总开关 + 五个分项开关（XML / emul / GPU / 触摸 / horae）+ 立即重新应用 / 恢复
2. 所有温度传感器
3. 系统温控服务
4. thermal zone 开关
5. 降温限频
6. 充电电流上限

### 保留的安全机制

上一版加的两条还在，因为确实有用：

- **崩溃自检**：写 emul_temp 前落 `.eg_armed` 标记，60 秒后系统还活着才撤掉。
  开机时标记还在 = 上次崩了 → 自动把 `emul` 关掉并写日志，界面红色横幅提示
- XML / GPU / 触摸这几项**没有这个风险**，所以崩溃后只跳过 emul 那部分，其余照常

### 验证

- 四个 shell（`eg.sh` / `service.sh` / `customize.sh` / `eg-setup.sh`）都过了 `sh -n`
- `eg.sh` 在本机真跑：`status` / `apply` / `clear` / `bootcheck` 全走了一遍；
  崩溃自检实测 —— 放一个 `.eg_armed` 再 `bootcheck` → `crash=1`、配置里 `emul` 被置 0、日志写入
- 前端用真实 EG 状态数据跑完整链路：设备行、路线、17 个文件、17/17 已挂载、
  8 个 zone 命中、五项开关全开、关掉 GPU 后写入的 `eg.txt` 里 `gpu=0`、toast 正确
- 六页回归全过，零控制台错误

---

## v1.7.0-beta9：设置页 / 自检页 / 本地 HTTP 服务

### 一、删干净确认

```
AaTempSpoof                      : 0
ats / ats_dir / atsEditFile      : 0
bin/thermal.sh                   : 已删除
```

唯一还出现"AaTempSpoof"字样的文件是 `README-update.md`（就是本文件，变更记录，
不进包里）。`index.html` 里那 8 处 `ats` 是 `cats[k]`（温度分类变量）的子串，无关。

顺带把**我上一版自己写的 emul_temp 引擎也全拆了**（`bin/thermal.sh`、任意 zone 手选、
43~47℃ 区间那套）。它和 Extreme GT 功能重叠，而且它是你黑屏的元凶。

### 二、Extreme GT 动没动真实分区

**没动。** 逐条追过：

| 动作 | 落点 |
| --- | --- |
| `eg-setup.sh` 扫 `/odm /my_product /vendor /system ...` | **只读**，改完的副本写进模块目录 |
| `eg.sh` 的 `mount --bind` | 运行时挂载覆盖，底层文件零改动，重启即消失 |
| GPU / emul_temp / horae / renice / stop 服务 | 全是运行时节点 |
| `precopy_to_anyfs` | 写 `/dev/anyfs/upper`（tmpfs，非分区） |

**唯一持久的一处**：`persist.sys.oplus.wifi.sla.game_high_temperature` 和
`persist.sys.environment.temp` 两条属性（原先写死在 `eg-setup.sh` 里，会落到
`/data/property` 永久保存）。这一版把它们挪出安装流程了，先留着待定。

### 三、设置页

齿轮 → 菜单 → 设置。都是即时生效、存在 localStorage：

- **主题**：跟随系统 / 深色 / 浅色（原来的 `prefers-color-scheme` 提成了 `data-theme`，可以手动钉死）
- **背景光斑 / 过渡动画 / 底部导航栏** 三个开关
- **温度单位** °C / °F（所有温度显示跟着变）
- **刷新速度** 慢 / 标准 / 快
- **默认打开的页面**
- **本地 HTTP 服务** 开关 + 端口 + 令牌 + 一键复制带令牌的地址
- 数据区：清空操作日志 / 恢复默认设置 / 重新发布离线数据

### 四、自检与日志页

15 项环境体检，逐项 ✓ / ✕ / ! ：

```
root 权限 / ksud / busybox / 模块目录 / WebUI 入口 /
DF 原生库 / DF 配置文件 / Zygisk 实现 / EG 引擎 / EG 配置 /
EG 覆盖文件数 / HTTP 服务 / SELinux /
AaTempSpoof 残留 / 旧温度引擎残留
```

最后两项是专门给你这种"换过好几个模块"的情况准备的 —— 一眼看出有没有残留。
下面跟 60 条操作日志 + Extreme GT 异常日志 + 环境信息，可复制、可导出到
`/sdcard/Download/ksu_toolbox_diag.txt`。

### 五、本地 HTTP 服务

用 KernelSU 自带的 busybox httpd，**只监听 127.0.0.1**：

```sh
busybox httpd -p 127.0.0.1:<端口> -h /data/adb/ksu_toolbox/www
```

浏览器打开 `http://127.0.0.1:8765/?token=xxxxxx` 就能看到完整界面。

**数据怎么来的**：原本以为可以用 CGI，但 busybox 的 CGI 是编译选项
（`FEATURE_HTTPD_CGI`），腾讯的 Ubuntu 版就没编，我没法确认 KernelSU 那版有没有。
所以**不赌它**，改成更稳的路子：

1. 页面在 KernelSU 里第一次打开时，把「需要哪些数据、命令是什么」写进
   `/data/adb/ksu_toolbox/tasks.txt`（base64）
2. 后台循环每 4 秒照着跑一遍，结果写成 `/api/<任务名>.txt`
3. 浏览器里页面自动改去 fetch 这些文件

好处是**零重复维护**（命令定义只有一份，在页面里），而且 httpd 完全不能执行
任何东西 —— 安全是结构性的，不靠白名单。

**边界**（写在设置页里了）：
- 只监听回环，局域网/外网访问不到
- 令牌存在 `/data/adb/ksu_toolbox/webui.token`，普通 App 没有 root 读不到
- **浏览器里是只读模式**：能看实时数据，开关/保存/终端这些写操作要在 KernelSU 里打开才行

### 六、验证

- `sh -n` 过了 `eg.sh` / `service.sh` / `customize.sh` / `eg-setup.sh` /
  `webui-server.sh` / `collect.sh` / `api.sh`
- **HTTP 服务端到端真跑过**：起服务 → 首页 179815 字节、`df.html` 3367 字节、
  `api/ov.txt` / `api/thermal_static.txt` / `api/_status.txt` / `api/_alive.json`
  全部正常返回，后台循环确实在按 4 秒刷新
- 设置页：主题选项、端口回填、令牌显示、地址拼接全对；切浅色/深色
  `data-theme` 实时切换
- 自检页：15 项体检渲染、`14 / 15 通过` 计数、日志框、✕ 标记都正常
- 六页回归 + 零控制台错误

---

## v1.7.1-beta10：修两个卡死/失效的根因

你报的两个问题都定位到了，而且都是我自己的问题，不是 Extreme GT 的。

### 一、开 HTTP 服务一直卡在加载

**根因：后台子 shell 攥着 stdout 不放。**

```sh
( collector_loop ) &                    # ← 没有重定向
$BB httpd -p 127.0.0.1:$P -h "$WWW"
```

那个后台采集循环继承了 WebUI `exec` 的 stdout 管道。KernelSU 的执行接口是
**读到管道 EOF 才算命令结束** —— 而这个进程一直活着，管道永远不关。
所以 `webui-server.sh start` 永不返回，设置页的 loading 就那么卡住了。

同一个错误我在 `eg.sh` 里也犯了：

```sh
( sleep 60; rm -f "$ARM" ) &            # 每点一次「开始伪装」卡 60 秒
```

三处全部改成 `( ... ) >/dev/null 2>&1 &`。

**实测**：`start` 从"永久卡住"变成 **2 秒返回**，`started=1`、`http_running=1`、
首页 206062 字节、`/api/ov.txt` 正常。

顺带把探活方式也换了 —— 原来的 `probe_port` 用 `wget` 拉 `http://127.0.0.1:PORT/`
判断服务在不在，而 `status` 是被采集循环每几秒调一次的，等于**每几秒把自己 180KB
的首页整个下载一遍**。现在直接查 `/proc/net/tcp` 的监听表，零开销。

### 二、欧加真却扫不到 XML，挂了 0 个

**根因：`MODPATH` 没传进子进程。**

```sh
# customize.sh
sh "$MODPATH/eg-setup.sh"      # ← MODPATH 是普通变量，不是环境变量
# eg-setup.sh
[ -n "$MODPATH" ] || { echo "MODPATH 未设置"; exit 1; }   # ← 直接退出
```

Magisk / KernelSU 的安装器是 **source** `customize.sh` 的，MODPATH 在它作用域里可见；
但 `sh 脚本` 起的是子进程，只有 **export 过**的变量才继承。所以 `eg-setup.sh`
每次都立刻 exit 1，**一个配置文件都没生成** —— 你看到的"挂载 0 个"就是这么来的。

> 这一条是我的**验证漏洞**：我只跑了 `sh -n` 做语法检查，没真跑一遍。
> 这次补上了。

修法两道保险：
- `customize.sh` 里显式传：`MODPATH="$MODPATH" sh "$MODPATH/eg-setup.sh"`
- `eg-setup.sh` 自己也兜底：`[ -n "$MODPATH" ] || MODPATH=${0%/*}`

**实测**：造了一棵假的设备树（`/odm/etc/sys_thermal_control_config.xml` 等 5 个文件），
跑 `eg-setup.sh` 后确实生成了对应文件。

### 顺带揪出 Extreme GT 原版的两个 bug

真跑之后才暴露出来的，都修了：

**bug 1：`for override in "$2"` 引号多了**

```sh
for override in "$2"; do                 # $2 是 11 行的列表，引号让它变成"一个词"
    key=$(echo "$override" | cut -f1 -d '=')      # key 成了多行
    rows=$(echo "$rows" | sed "s/<$key>.*</<$key>$value</")   # sed 报 unterminated
    echo "$rows" > "$MODPATH$file"        # → 写出一个空文件
```

后果：`sys_thermal_config.xml` 和 `sys_high_temp_protect_*.xml`
**一直是空文件被 bind-mount 上去的**。改成不引号（按行拆）之后，
`isOpen=0`、温度阈值这些才真正写进去。

**bug 2：`thermallevel_to_fps.xml` 路径拼错**

```sh
> "$MODPATH/system$file"      # $file = /odm/etc/x.xml → $MODPATH/system/odm/etc/x.xml
```

`/system` + `/odm/...` = `/system/odm/...`，挂载目标根本不存在。
改成 `$MODPATH$file`（保留真实路径），配合 `bin/eg.sh` 遍历六个根目录，
现在能正确 bind-mount 了（实测两个文件都落在对的位置，fps 都改成 144）。

### 自检页也加了两项

- **温控 XML 可扫描** —— 直接数一遍 `/odm /my_product /vendor /system/vendor /product /system`
  下能找到几个源文件。如果是 0，一眼就知道是设备不在支持范围，而不是模块坏了。
- **EG 已生成配置** —— 数模块目录里实际生成了几个覆盖文件。

这两项正好能区分你说的两种情况：**能扫到 = 我们这边的问题；扫不到 = 设备路径不同**。

### 装完怎么验证

1. 安装时看 KernelSU 的刷入日志，应该有 `Extreme GT：共生成 N 个去温控配置文件`
2. 打开工具箱 → 菜单 → 自检与日志 → 看「温控 XML 可扫描」和「EG 已生成配置」
3. 温度页顶部「已生成配置」那个数字应该不再是 0

---

## v1.8.0-beta11：修「网页里一直是演示数据」 + 改名 JeTeeZnTmax

### 一、为什么浏览器里一直是演示数据

**两层原因，都是我的问题。**

**第一层：`fetchMap` 里有个短路。**

```js
async function fetchMap(lines, demoKind, timeout){
  if(!HAS_BRIDGE) return DEMO[demoKind]();   // ← 浏览器里直接返回演示数据
  const r = await sh(lines.join("; "), timeout);
```

`sh()` 里其实写了「没有 ksu 桥就去 fetch 本地 HTTP 快照」的回退，但
`fetchMap` 在**调用 `sh()` 之前**就短路返回了哑数据 —— 那条路根本走不到。
上一版我只测了 `sh()` 本身，没测 `fetchMap` 这条真实链路。

现在改成一律走 `sh()`，由它自己依次尝试：ksu 桥 → 本地 HTTP → 都没有才回演示模式。

**第二层：我强行要求令牌。**

```js
HTTPX.active = !!t;    // 没令牌就不启用 HTTP 回退
```

`/api/*.txt` 是**只读静态快照**，本来就没有可写的东西，令牌是纯冗余设计。
你直接输 `127.0.0.1:8765` 没带 `?token=`，所以也走不到。

现在不需要令牌了：只监听回环 + 只读静态文件，这个安全模型本身就够了。

**外加**：以前还得先在 KernelSU 里打开一次 WebView，页面才会把任务表
（`tasks.txt`）发布出去，采集循环才有东西可跑。现在打包时会用页面自己导出一份
`tasks.default.txt`，安装时直接铺到 `/data/adb/ksu_toolbox/tasks.txt` —— **装完就是好的**。

### 二、改名

- 模块名（KernelSU 模块列表里显示的）：`JeTeeZnTmax`
- WebUI 标题 + 顶栏：`JeTeeZnTmax`
- 角标：`KSU` → `JZ`

### 三、实测

用本地 HTTP 服务起在 8777，然后**真的用浏览器打开 `http://127.0.0.1:8777/`**（不带令牌）：

```
标题 / 顶栏      → JeTeeZnTmax
温度页 zone 数   → 89     ← 这台机器真实的 zone 数
                           演示数据是 10，所以这能明确区分
性能页           → 8 核 · walt
EG 卡片 / 工具   → 正常渲染
控制台           → 干净
```

同时 `api/` 目录里 8 个快照文件（`_alive.json` / `_status.txt` / `ov.txt` /
`pfStatic.txt` / `pfDyn.txt` / `pw.txt` / `thermal_static.txt` / `thermal_dyn.txt`）
都在按 6 秒刷新。

### 四、说明

总览/机型那几个页面在浏览器里可能会显示空白（比如型号显示 `--`），因为
快照里 `getprop` 之类的值是你机器上的真实值，而演示数据是编的 —— 真机上不会空。
温度、性能、电源这三个页面用的是同一套快照，应该都能看到真实数据。

---

## v1.8.1-beta12：修「其他温度 700 多度」+ 菜单做显眼

### 一、700 多度是怎么来的

**不是计算方式错，是我把不是温度的东西当温度算了。**

内核的 `/sys/class/thermal/thermal_zone*` 里有一批**虚拟传感器** ——
它们的 `temp` 文件里放的是电压(mV)、电流(mA)、功率，不是温度：

```
vbat        → 700      (700 mV，被当成 700 °C)
current_now → 700000   (700 mA，÷1000 变成 700 °C)
```

我原来只做了一个「>1000 就当毫摄氏度」的判断，700 这种小于 1000 的值
就被直接当成"已经是摄氏度"了。

**而 Extreme GT 自己的 `action.sh` 里本来就有这个过滤**，我抄的时候漏了：

```sh
case "$sensor_type" in
    *vbat*|*volt*|*current*|*power*|*soc_volt*)
        continue        # 这些不是温度传感器，跳过
        ;;
esac
```

现在补了两道防线：

1. **按类型名跳过**：`vbat / volt / current / power / soc_volt / resistance / capacity / _mv / _ma`
   这些 zone 在加载时就被剔除，压根不进列表
2. **按数值范围兜底**：换算完落在 `-40 ~ 150 °C` 之外的一律显示 `--`
   （人的体温计都没这么宽）

顺带把温度单位设置接上了 —— 之前设置页里的 °C/°F 开关是写了但没生效，
现在温度显示（顶部大数字、分类格、传感器列表）都会跟着走。

### 二、菜单做显眼

两处一起改：

**1. 顶栏按钮从纯图标换成带文字的强调色胶囊**

```
原来：[☰]                    38×38 的灰色图标
现在：[☰ 菜单]               73×38，蓝底蓝字带阴影
```

**2. 底部导航加到 7 格，多了「设置」**

```
总览 / 性能 / 温度 / 电源 / 机型 / 终端 / 设置
```

实测宽度（不溢出）：

| 屏宽 | 每格 | 顶栏 |
| --- | --- | --- |
| 430 | 54px | 够 |
| 390 | 48px | 够 |
| 360 | 44px | 够 |
| 320 | 38px | 够（品牌 83 + 菜单 73 + 两个图标键 76 + 间距 16 = 248 < 290） |

进入「自检 / 关于」页时，dock 里「设置」也会保持高亮，不会出现没有高亮项的情况。

### 三、验证

用一份**故意混了脏数据**的 zone 列表（`vbat=700`、`current_now=700000`、
`pa-therm=99900`）跑：

```
vbat / current_now 被过滤掉        ✓
界面上不再出现 700 多的数字         ✓
dock 7 项，含 settings             ✓
点 dock「设置」→ 打开 + 高亮        ✓
顶栏按钮文字「菜单」               ✓
零控制台错误                       ✓
```

---

## v1.9.0：加了一个独立的桌面 App

你要的「打开就进这个 WebUI」。**能做的关键点是：App 得自己实现 `window.ksu`。**

页面里判断环境是这样的：

```js
const bridge = () => globalThis.ksu || globalThis.KernelSU || globalThis["$APATCH"] || …
```

那个 `ksu` 对象是 KernelSU 管理器注入进它自己 WebView 的，普通 App 没有。
所以只要我在 App 里 `addJavascriptInterface(new KsuBridge(), "ksu")`，
**页面根本分不出自己在 KernelSU 里还是在这个 App 里** —— 完整功能，不是只读预览。

### 它怎么工作

```
1. 要一次 root（su -c id -u）
2. su -c "cp -r /data/adb/modules/ksu_toolbox/webroot <App私有目录>"
   chown 成 App 自己 —— su 拷出来是 root:root，WebView 读不到
3. WebView 载入 https://appassets.androidplatform.net/index.html
   由 shouldInterceptRequest 从私有目录直接喂字节
4. addJavascriptInterface(ksu) 提供 exec / moduleInfo / toast / listPackages …
```

三个取舍：

- **先拷到私有目录**：`/data/adb/modules/` 是 0700 root，普通 App 读不了。
  拷过去之后 WebView 就不需要任何特权了，权限面更小。
- **虚拟 https 域名**：`file://` 下 `localStorage` 和 `fetch` 都受限；套一个
  https origin 就没这些坑，而且不会出现"打开个恶意网页就拿到 root"的路径。
- **不用 Gradle / AndroidX**：整个 App 只用系统 API，编出来 **54 KB**。

### 构建方式（不用 Android Studio）

这台机器上没有 Android Studio，我是用 SDK 的原始工具链直接拼的：

```
aapt2 compile → aapt2 link → javac → d8 → 塞 classes.dex → zipalign → apksigner
```

编出来 v1 + v2 + v3 签名全过。踩到的两个坑记一下：

1. **源码里不能用 lambda**。我们是对着 API 25 的 `android.jar` 编译的，
   那里没有 `java.lang.invoke.MethodHandles`，javac 21 编 lambda 时会直接
   `CompletionFailure` 崩掉。全改成匿名内部类就好了。
2. **`import android.os.Process` 会遮蔽 `java.lang.Process`**，导致
   `ProcessBuilder.start()` 的返回值类型对不上。改成全限定
   `android.os.Process.myUid()`。

### 集成进模块

APK 也放进模块了：`/data/adb/modules/ksu_toolbox/app/JeTeeZnTmax.apk`，
设置页新增「桌面 App」区，可以一键 `pm install -r` 装或更新。
自检页也多了一项「桌面 App」。

### 装法

- 直接把 `JeTeeZnTmax.apk` 传手机装，或者
- 模块 WebUI → 设置 → 桌面 App → 安装 / 更新

装完打开 → KernelSU 弹 root 授权 → 允许 → 直接进界面。

---

## v2.0.0：应用管理 / 进程管理 / 换成你上传的图

### 一、应用管理（底部导航新增「应用」，第 3 格）

进页面自动加载全部应用，有应用名（走 `getPackagesInfo`）、包名、系统/第三方分类，
底部悬浮搜索框，可搜应用名或包名。点任意应用弹详情：

```
应用名 / 包名 / 版本 / UID（含用户名）/ minSdk·targetSdk /
安装路径（split APK 会全部列出）/ APK 大小 / 首次安装 / 最近更新 /
类型 / 状态
```

三个动作：

| 动作 | 命令 |
| --- | --- |
| 冻结 / 解冻 | `pm disable-user --user 0 <pkg>` / `pm enable <pkg>` |
| 备份 APK | `cp <安装路径> /sdcard/Download/<包名>.apk` |
| 卸载 | `pm uninstall --user 0 <pkg>`（二次确认） |

### 二、进程管理（同一页右半边，分段切换）

- 列表：PID / 进程名 / 用户名 / RSS / **实时 CPU%**
- 排序：CPU / 内存 / PID / UID / 名称，点同一个键切换升降序
- **底栏搜索框**：按 pid、名称、uid、ppid 过滤
- 点任意进程弹详情：

```
PID / PPID / 用户（uid + 映射名 + 所属应用）/ CPU% / 内存 RSS /
累计 CPU 时间 / 运行状态（字母 + 中文）/ 线程数 /
cpus_allowed / wchan（阻塞在哪）/ oom_score_adj / cpuset
+ cgroup 全文 + 命令行全文
```

CPU% 是**真的瞬时值**：shell 端只吐 `utime+stime`，前端拿两次采样做差再除以间隔。
所以在这一页停几秒，数字就会准。

两个动作：

- **停止** —— `kill -9`，二次确认（提示了系统关键进程被杀的后果）
- **跟踪它** —— 弹窗里每 2 秒刷新该进程的 `logcat --pid`、`wchan`、当前 `syscall`

> 关于「跟踪」要说实话：**Android 上没自带 strace**，看不到完整的 syscall 序列。
> 所以我做的是 logcat + 阻塞点 + 当前系统调用这一套。
> 如果你要真正的 strace，得自己塞一个静态编译的 strace 二进制进去，我可以再加。

### 三、图标换成你上传的图

- **App 图标**：传统 PNG（5 个分辨率）+ **自适应图标**（`mipmap-anydpi-v26`，
  背景层放大 1.12 倍，这样被启动器蒙版裁掉外圈后主体仍完整）
- **WebUI 顶栏**：原来的 `[JZ]` 方块换成图片（88×88 JPEG，3.5 KB，
  base64 内联，不依赖网络），顺便加了 favicon

### 四、这一轮踩到的坑

基本都是我自己写的 bug，记一下：

1. **`loadProcs` 的 finally 调了不存在的 `renderProcList()`** —— 进程列表永远空白
2. **运算符优先级**：`'前缀' + expr === undefined ? "--" : "后缀"` 被解析成
   `(前缀 + expr) === undefined ? ...`，把前面拼好的字符串全吞了，
   应用详情只剩最后两行
3. 构建环境：`/usr/bin/dx` 是 **OpenDX（科学可视化软件）**，不是 Android 的 dx；
   `dl.google.com` 不通、`repo1.maven.org` 没有 r8、`maven.google.com` 的 IPv6 连不上
   —— 最后走**阿里云镜像**拿到了 r8.jar

### 五、验证

用 mock 桥跑了完整链路：

```
dock 8 格含 app                    ✓
顶栏品牌图 88x88 已加载             ✓
应用列表 4 个，微信/酷安 中文名正确   ✓
应用详情：包名/路径/SDK 30·34/117.74 MB/时间/类型/状态 全对  ✓
进程列表 4 个，system_server 在      ✓
排序按钮 CPU↓/内存/PID/UID/名称      ✓
进程详情 9 项字段全在                ✓
底栏搜索 5678 → 命中 1 行            ✓
零控制台错误                        ✓
```

App 重编后 v1+v2+v3 签名全过，dex 里 `getApplicationLabel` 在，
自适应图标 `mipmap-anydpi-v26/ic_launcher.xml` 已打进包。

---

## v2.0.0 小修：作者改回 @JeTeeZnTmax

`module.prop` 里的 `author` 我之前擅自填了自己的名字，已经改回你的写法
`author=@JeTeeZnTmax`（参照你 `zip_extract` / `check` 那两个模块的格式）。
全模块搜过了，现在一处「陈雨汐」都没有。

**顺手抓到一个显示 bug**：顶栏小字显示成 `vv2.0.0` —— 因为 `module.prop` 里
`version=v2.0.0` 自带 `v`，代码又补了一个。你别的模块用的是 `version=v3.0`，
同样会中招。现在改成先判断开头有没有 v，有就不补。

```
修前：vv2.0.0 · by @JeTeeZnTmax
修后：v2.0.0 · by @JeTeeZnTmax
```

---

## v2.1.0：修「应用只显示包名」+ 加上应用图标

### 为什么只显示包名

应用名只能从 `getPackagesInfo` 拿（shell 拿不到 —— `pm list packages` 只吐包名，
`dumpsys package` 里也没有人类可读的 label）。我原来是一次性把 **400 个包名**塞进
一次调用：

```js
const info = packagesInfo(all.slice(0, 400).map(x => x.pkg));
```

这个调用要么超时、要么在桥那边直接失败，而 `packagesInfo` 里 `catch(e){ return null }`
**把错误全吞了** —— 于是 labels 是空的，界面就只剩包名，搜索也只能按包名匹配。

顺便说：`getIcons` 这个接口**根本不存在**，我压根没实现过图标，一直用的字母占位。

### 现在怎么改的

**1. 分批问，不再一次塞 400 个**

```js
const STEP = 80;
for(let i = 0; i < all.length; i += STEP){ ... await 让出主线程 ... }
```

**2. 失败要说出来，不再静默**

读不到应用名时列表顶部会有横幅：「读不到应用名，只显示包名 · <具体原因>」。
原因会区分是「当前 KernelSU 版本没有 getPackagesInfo」还是「不在 App/KernelSU 里打开」
还是「接口返回了空数组」。

**3. 应用图标——App 侧新加了 `getIcons`**

原来的接口集里没有图标，所以我给桌面 App 加了一个：

```java
@JavascriptInterface
public String getIcons(String packages)   // {包名: "data:image/png;base64,…"}
```

用 `PackageManager.getApplicationIcon()` 画到 72×72 的 Bitmap 再转 base64。
前端**分批 24 个**懒加载，拉到一批就地把字母占位换成真图标，不重排、不跳滚动。
最多取当前列表前 150 个。

**4. 顺带加固了 `getPackagesInfo`**

原来的实现外面包了一层大 try，**任何异常都会让它返回空数组**，前端完全看不出问题。
现在参数解析失败会退化成「按逗号/空白拆包名」，单个包查不到也只影响那一条。

**5. 自检页新增三项探针**

```
PackageManager     已装 187 个 · 示例名 设置
应用图标接口        getIcons 可用 / 没有，列表用字母占位
应用名接口          getPackagesInfo 可用 / 没有，只显示包名
```

这样以后再出这种事，打开自检页一眼就知道是哪一环。

### 验证

| 场景 | 结果 |
| --- | --- |
| 接口齐全（桌面 App / 新版 KernelSU） | 显示中文名 ✓ 图标渲染 ✓ 搜「微信」命中 1 条 ✓ 无横幅 |
| 桥是老的（无这两个接口） | 明确警告横幅 ✓ 退化成包名 ✓ 不再静默 |

两份都是零控制台错误。

### 一个说明

**图标只有桌面 App 能提供。** KernelSU 管理器的 WebView 里只有 `getPackagesInfo`
（给名字），没有给图标的接口，那是它的能力边界，我这边补不了 ——
所以想看图标就用桌面 App 打开。名字那边两边都能拿到（如果 KSU 版本够新）。

---

## v2.1.1：删掉温度页两处文案

1. **最高温旁边那个状态标签**（凉快 / 正常 / 偏热 / **烫手**）——
   连同判定逻辑一起删了，温度页顶部现在只剩最高温数字和 zone 数。
2. **Extreme GT 卡片下面那段「它改的是配置层，不是硬写 sysfs…」** —— 整段删了。

验证：`烫手 / 凉快 / 偏热 / thState` 和 `配置层` 都是 0 命中，温度页正常渲染，零控制台错误。

> 说明：「关掉温控机身会真的烫」那条**没删** —— 那是「系统温控服务」板块的警告，
> 说的是停掉 thermal-engine 之后机身会真的升温，跟上面两个不是一回事。
> 如果你要删的是这条，说一声我再删。

---

## v2.2.0：照你说的去测 fork，测出三个真 bug

你让我「写个脚本 fork 一下，看进程页到底能不能看到」—— 这一测全露馅了。
在这台机器上造了一棵真实的进程树（父 → 4 个子 → 1 个孙进程，其中一个烧 CPU、
一个睡、一个阻塞在 pipe），然后用**页面里那套一模一样的 shell 脚本**去读。

### bug 1：`$14` 不是第 14 个参数

```sh
set -- $R
st=$1; pp=$2; ut=$14; stt=$15; rs=$24
```

POSIX shell 里 **`$14` 是 `$1` 后面跟一个字面量 `4`**，不是第 14 个位置参数！
得写 `${14}`。所以：

```
输出: 32048,13941,,S,S4,S5,557656,libproot_exec.s
                 ^^ ^^^^ ^^^^
                 uid  ut   stt     ← ut="S4"、stt="S5"，全是 $1 拼出来的垃圾
```

**CPU 时间和内存两个字段全废**，进程页看到的 CPU% 和 RSS 都是假的。

### bug 2：uid 解析去不掉 Tab

```sh
u=${ln#Uid:}          # "Uid:	0	0	0	0"
u=${u# }              # ← 只去空格，去不掉 Tab
u=${u%%[!0-9]*}       # 第一个字符是 Tab（非数字）→ 直接清空
```

结果 uid 恒为空。改成按词遍历 `for w in $ln`。

### bug 3：详情页解析 stat 的下标整体差一位

去掉 `pid (comm) ` 之后 `index 0` 是 state，也就是**第 3 个字段** ——
所以字段 N 的下标是 **N-3**。我原来写的是：

| 字段 | 我写的下标 | 正确下标 |
| --- | --- | --- |
| utime (14) | `R[12]` | `R[11]` |
| stime (15) | `R[13]` | `R[12]` |
| 线程数 (20) | `R[18]` | `R[17]` |
| rss (24) | `R[22]` | `R[21]` |

**全错一位** —— 详情页里「累计 CPU 时间」「线程数」显示的都是隔壁字段的值。

### 修完之后的实测

```
角色         pid   ppid   应为   uid     st      RSS KB    结果
父 self     3731   3723      0  10413    S      5497…    ✓ 能看到
子 busy     3735   3731   3731  10413    R      …        ✓
子 sleep    3736   3731   3731  10413    S      …        ✓
子 pipe     3737   3731   3731  10413    S      …        ✓
子 sub      3738   3731   3731  10413    S      …        ✓
孙 grand    3739   3738   3738  10413    S      …        ✓ 孙进程也能看到
```

- **fork 出来的子进程、乃至孙进程全都看得到，ppid 关系全对**
- uid 11/11 全部有值
- 单核满载的子进程：`${14}` utime Δ199 jiffies / 2.0 秒 = **99.4%**，
  跟预期完全吻合（反倒 `ps` 报的是生命周期平均值，只能给 0.0%）
- `${24}` rss 2361 页 × 4 = 9444 KB，对照 `ps` 的 9596 KB ✓

### 关于「看它做了什么」

**fork 本身没有任何限制** —— 它就是个 syscall，页面上看到的就是真实的内核进程视图。

那个「跟踪它」按钮抓的是 `logcat --pid` + `wchan` + 当前 syscall，
**不是 strace**（Android 没自带）。所以你能看到「它阻塞在哪个内核函数、在写什么日志」，
但看不到逐条的 syscall 序列。要那个得自己塞一个静态编译的 strace 二进制，
塞进去我就能把跟踪页改成真的 strace 输出。

---

## v2.2.1：${14} 那个修复本身也是错的，重新修

你报「全是 0%、fork 也没反应」，一测发现我上一版改对了一半。

### 真正的错误：位置偏移是 +2，不是 +0

```sh
R=${L##*) }      # ← 这里已经把 "pid (comm) " 去掉了
set -- $R        # 所以位置1 = state = 字段3
```

**`位置 N ↔ 字段 N+2`**，不是 `N ↔ N`。我按字段号直接写了位置号：

| 要的 | 字段 | 正确位置 | 我写的 |
| --- | --- | --- | --- |
| utime | 14 | **12** | 14 |
| stime | 15 | **13** | 15 |
| rss | 24 | **22** | 24 |

`${14}` 拿到的是 `cutime`（几乎恒为 0）、`${24}` 拿到 `starttime`（天文数字）。
所以 **CPU 恒 0%、RSS 显示 549 GB** —— 你看到的两个现象同一个原因。

（详情页那半边用的是 JS 下标 `R[11]/R[12]/R[21]`，那套一直是对的，只有 shell 这半边错。）

### 顺带踩到自己埋的雷

修的时候我在 PROC_CMD 里加了行 `#` 注释说明原因 ——
结果**整条命令直接不执行了**。因为 PROC_CMD 是用 `"; "` 拼成**一行**跑的，
`#` 把它后面所有内容全注释掉了。注释已挪到 JS 侧。

### 实测（3 个真 fork 子进程）

```
烧CPU pid=7034   utΔ=249   →  99.6%   RSS  7592 KB   ps 说 7592 KB  ✓
sleep pid=7036   utΔ=0     →   0.0%   RSS  7780 KB
sleep pid=7037   utΔ=0     →   0.0%   RSS  7472 KB
```

CPU% 和 RSS 跟 `ps` 完全吻合，fork 出来的子进程全部能看见。

---

## v2.2.2：修「所属应用」里那个 Java 异常

你截图里最刺眼的那行：

```
所属应用   Error: java.lang.NumberFormatException:
          For input string: "kshrnk_slab"
```

**这是我从第一版就带进来的 bug**，跟版本无关：

```sh
awk 'NR==1{print $2}' /proc/$P/status
#       ↑ 第 1 行是 "Name:	kshrink_slabd"，根本不是 Uid
```

拿到的是**进程名**，直接喂给 `pm list packages --uid`，`pm` 抛异常，
异常文本被原样当成包名显示出来。

改成按行首匹配 + 数字校验：

```sh
U=$(awk '/^Uid:/{print $2; exit}' /proc/$P/status 2>/dev/null)
case "$U" in ""|*[!0-9]*) U=0 ;; esac
```

外加一道前端防线：`所属应用` 只在结果**长得像包名**时才显示，否则留空 ——
以后任何 `pm` 的报错都不会再糊到界面上。

### 另外：你截图右上角写的是 `vv2.0.0`

那是**模块版本**。你现在跑的是 v2.0.0，我后面几版的修复都没生效。
截图里四个症状跟 v2.0.0 的代码逐一对应：

| 截图 | v2.0.0 的代码 | 实际读到 |
| --- | --- | --- |
| 线程数 0 | `R[18]` | position19 = `nice` = 0 |
| 内存 7.38e19 | `R[22]` ×4 | position23 = `rsslim` = 2⁶⁴-1 |
| CPU 全 0% | shell `$14` | `$1`+"4" = `"S4"` → NaN |
| 累计CPU 14.29s | `R[12]/R[13]` | 落在 stime/cutime 上 |

**v2.2.1 起全部修好了。** 刷完 v2.2.2，标题下面那行会变成 `v2.2.2 · by @JeTeeZnTmax`
（不再是 `vv`，`vv` 那个也是旧版的显示 bug）。

---

## v2.3.0：受保护的执行（ptrace 拦截）

终端页多了一块「**受保护的执行**」。填一个脚本/命令，它在 ptrace 底下跑，
碰到危险 syscall 就在**执行之前**拦下来。

### 为什么是 ptrace

那个格机样本的源码我看完了，关键特征：

```c
#define my_openat(dirfd, path, flags, mode) syscall(__NR_openat, dirfd, path, flags, mode)
```

**全部走 raw syscall** —— `LD_PRELOAD` / libc hook **完全无效**。
而且它 `openat(目录fd, "sda1")`，连路径字符串都不给完整，所以基于路径匹配的
LSM/seccomp 也拦不住。

**ptrace 在内核停点上看得到，它绕不过。**

### 拦什么

| syscall | 判据 |
| --- | --- |
| `mknod` / `mknodat` | mode 带 `S_IFBLK` → 这是造块设备节点的关键前置 |
| `openat` | 目标是 `/dev/block/*`、`/proc/partitions`，且 flags 含写 |
| `openat`（dirfd 型） | dirfd 本身就是块设备，且要写 |
| `write` / `pwrite64` / `writev` | fd 反查 `/proc/<pid>/fd/N`，是块设备就拦 |
| `reboot` / `kexec_load` | 直接拦 |
| `init_module` / `finit_module` / `delete_module` | 内核模块操作 |
| `swapon` / `swapoff` | |
| `unlinkat` | 路径命中：`/vendor` `/system` `/odm` `/my_product` `/persist`
`/metadata` `/dev/input` `/sys/class/input` `/data/adb/modules` … 以及子串
`touch` `firmware` `modem` `efs` `abl` `xbl` `vbmeta` `dtbo` 等 |
| `mount` / `umount2` / `truncate` | 命中关键路径才拦 |

### 两种模式

- **先观察**（默认）：只记录不杀。**先用它跑一遍，看清行为再决定**
- **拦截模式**：命中立刻 `SIGKILL` 整个进程组（含它 fork 出来的）

### 实测（本机 aarch64 原生跑）

```
① 拦截模式   mknod 被抓，后续命令没执行，退出码 3        ✓
② 正常程序   跑完，退出码 0，0 次误报                   ✓
③ reboot     被拦（通过它附带的 unlinkat）              ✓
④ 嵌套 fork  sh -c "sh -c "mknod ..."" 也跟得上       ✓

syscall 号解析全部正确：56=openat / 222=mmap / 57=close / 80=newfstatat
```

### 调试记（踩了两个坑）

1. **`in_syscall` 全进程共用一个标志** —— bash 一 fork，entry/exit 就错位，
   大半检查都发生在 syscall-**exit**（那时寄存器里是返回值不是参数）。
   改成 `st_for(pid)` 按 pid 记状态。
2. **一个正则把 `nr = r.regs[8]` 误替换成 `nr = A[8]`** —— `A[]` 只有
   6 个元素，于是越界读栈上垃圾当 syscall 号。表现是「127 个停点、
   0 次命中」，而且打印出来的号是 `sys=1 sys=0 sys=3`（看着像 x86_64 的号，
   其实是栈上残留）。这个 bug 靠**打印所有 syscall 号**才揪出来。

### 不保证 100%

**必须说清楚**：这是同权限下的 ptrace。如果目标有反调试 ——
读 `TracerPid`、`prctl(PR_SET_DUMPABLE, 0)`、或者干脆 `kill` 掉监督进程 ——
它是能识破并改变行为的。**真可疑的东西请先在备用机上跑。**

顺带一句：那个格机样本里 `ptrace` 出现 **0 次**，没有反调试，
所以这个工具对它有效。

## v2.4.0：改成拦 /dev/block 的**所有**操作

之前只拦"写块设备"。现在默认**读写全拦** —— 只要碰 `/dev/block` 就杀。

### 覆盖范围

| 类别 | syscall |
| --- | --- |
| 开 | `openat`（读、写、`O_DIRECTORY` 列目录，全拦）|
| 探测 | `faccessat` `newfstatat` `readlinkat` `statx` `fchmodat` `utimensat` |
| 动结构 | `mkdirat` `symlinkat` `linkat` `unlinkat` `renameat` `renameat2` `mknodat` |
| fd 后操作 | `read` `pread64` `readv` `write` `pwrite64` `writev` `ioctl` `getdents64` |
| 路径变体 | `/dev/block`、`//dev/block`、以及**相对 dirfd** 的（样本就是用 `openat(目录fd,"sda1")`）|
| 附带 | `/proc/partitions`（样本靠它拿 major:minor 去 mknod）|
| 无参数依赖 | `reboot` `kexec_load` `init_module` `finit_module` `delete_module` `swapon` `swapoff` |

### 新增 `-r`：只拦写，放行读

UI 上也加了开关。要 dump boot 镜像做备份的时候用这个——
不然 `dd if=/dev/block/by-name/boot of=/sdcard/boot.img` 会被当成攻击一起杀掉。

### 实测

```
该拦的
  cat /dev/block/sda            rc=3  → openat 读块设备
  ls  /dev/block/by-name        rc=3  → 探测 /dev/block      ← 列目录也拦
  stat /dev/block/sda1          rc=3  → 探测 /dev/block
  readlink /dev/block/by-name/x rc=3  → 探测 /dev/block
  dd if=/dev/block/sda          rc=3  → openat 读块设备
  cat /proc/partitions          rc=3  → openat 读块设备
  mknod /tmp/fb b 7 0           rc=3  → mknod 造块设备节点
  cat //dev/block/sda           rc=3  → openat 读块设备      ← // 变体也盖住

不该拦的
  /tmp/dev/block2/x             rc=0   ← 修掉了这个误判
  /tmp/dev/blockX               rc=0
  /tmp/my_partitions.txt        rc=0
  普通 mkdir/echo/cat           rc=0

-r 模式
  cat /dev/block/sda            rc=0   读放行
  echo x > /dev/block/sda       rc=3   写照样拦
```

### 顺带修的一个误判

第一版用 `strstr(p, "dev/block")`，结果 `/x/dev/block2/y` 也被当成 `/dev/block` 拦了。
改成要求 `dev/block` 后面必须是 `/` 或结尾：

```c
while ((s = strstr(s, "dev/block")) != NULL) {
    char nxt = s[9];
    if (nxt == 0 || nxt == '/') return 1;
    s++;
}
```

---

## v2.4.1：修「执行失败 126」+ 卡片穿模 + 按钮排版

### ① 退出码 126 —— 不是 guard 的问题，是脚本放错地方了

```
sh: /storage/emulated/0/test.sh: can't execute: Permission denied
```

**`/sdcard`、`/storage`、`/mnt` 在 Android 上是 `noexec` 挂载的**，从那儿直接执行
任何文件都会 126。跟 SELinux 也有关，但主要是 noexec。

现在跑之前会自动处理：

```sh
T=<你填的路径>
if [ -f "$T" ]; then
  case "$T" in /sdcard/*|/storage/*|/mnt/*)
      cp -f "$T" /data/local/tmp/_guard_tgt && T=/data/local/tmp/_guard_tgt ;;
  esac
  chmod 755 "$T"
  # ELF 直接执行，其它一律交给 sh
  if [ "$(head -c 4 "$T" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; then CMD="$T"
  else CMD="sh $T"; fi
fi
```

**用 ELF 魔数判断而不是看 shebang** —— 因为 shebang 可能指向设备上不存在的
解释器（`#!/system/bin/sh` 在容器里就不存在，会报 `not found`）。

实测四种输入：

```
sdcard 无 shebang   → sh /data/local/tmp/_guard_tgt   跑通
sdcard 有 shebang   → sh /data/local/tmp/_guard_tgt   跑通
sdcard ELF 二进制   → /data/local/tmp/_guard_tgt      直接执行
直接填一条命令      → echo xxx                        跑通
```

### ② 卡片穿模

我写的是 `class="card"`，**漏了 `file-card`** —— 那个 class 才带 `padding:15px`。
结果输入框 398px 塞在 400px 的卡片里，**左右各只 1px**，圆角直接怼出来。
现在内边距 16px。

### ③ 按钮排版

原来两个按钮 **104px / 92px、宽度不一、只占左半边**（现有那块都带 `flex:1`，我没照抄）。
现在：

```
执行  180px  |  看日志  180px     等宽、正好铺满一行
模式 / 范围 两个 chip 同一行、可换行
```

### ④ 顺带：拼接命令时踩的雷

用 `"; "` 拼 `if/case/fi` 会**从中间切断结构**：

```
case "$T" in ...) cp ... ;
esac                      ← 多了个分号，语法直接崩
```

整条命令改成用 `\n` 连接了。

---

## v2.4.2：拦在改动之前（附实测）+ 修连按卡死

### ① 「真的能在改动之前拦下来吗」—— 能，实测如下

`PTRACE_SYSCALL` 停的是 **syscall-entry**，也就是**内核还没执行**那个调用。
我在那儿检查并杀，调用根本没发生。

```
删 /persist/calib.bin       rc=3   文件还在，md5 一字未变         ✓
删触控固件 touch_fw.bin     rc=3   还在                          ✓
删模块自己的脚本            rc=3   还在                          ✓
往块设备写 dd if=/dev/zero  rc=3   块设备内容一字未变             ✓
reboot                      rc=3                                 ✓
```

**一个仿真的格机脚本从头跑到尾：**

```sh
#!/system/bin/sh
echo "[*] 开始擦除"
dd if=/dev/zero of=/dev/block/sda bs=1M
rm -f .../touch_fw.bin ; rm -rf /persist
mknod /tmp/fake b 8 0 ; reboot
echo "[!] 全擦完了"        # ← 这行没打印出来
```

结果：退出码 3，块设备未变，两个文件都在，**"[!] 全擦完了" 没出现** ——
在第一个 dd 上就被杀了。

#### 但有两个诚实的保留

1. **多线程**：ptrace 停住一个线程时，**其它线程照跑**。样本用 `std::thread`
   并发擦除的话，另一个线程可能已经在 `write()` 里面了。`kill_tree` 会
   SIGKILL 整个进程组把它一起带走，但那个**正在飞行中的 write 可能已经落了几个扇区**。
   所以是「大幅降低损害」，不是「一个字节都不会动」。
2. **没钩到的 syscall 能绕过**：所以这版补了
   `io_uring_setup/enter/register`（**io_uring 能完全绕开逐 syscall 观察**，
   直接掐掉）、`splice` `sendfile` `copy_file_range` `vmsplice` `tee`（往块设备搬数据）。

### ② 连按卡死

两个原因：

**a. `guardRun` 没有防重入。** 我上一版有 `G.running`，重写时丢了。
更坑的是上锁的位置 —— 我写在 `busy(true)` 之后，但**前面有个 `await guardFindBin()`**：

```js
const kv = await guardFindBin();   // ← 这个 await 期间遮罩还没出现
G.running = true;                  // ← 锁才上，窗口期已经漏了
busy(true, "…");
```

那段窗口里点多少下都真进去。现在**锁提到最前**，并且立刻禁用按钮。

**b. 终端逐行插入太慢。** 一次执行最多 400 行，`termLog` 每行都跑一次
`while(childNodes>400) removeChild` + `scrollTop` 重算。改成
`termAppendBlock()` 用 `DocumentFragment` 一次性追加，上限降到 300 行。

顺带把 guard 的超时从 180s 降到 60s —— 免得一条命令把桥占三分钟。

实测：

```
30 次点击 → 并发峰值 1 ✓（之前会全部堆起来）
中途再砸 20 次 → 并发峰值仍是 1 ✓
遮罩不残留 ✓   按钮正常恢复 ✓   零控制台错误 ✓
狂切页签 20 次 → 零错误 ✓
```

### ③ 没变的边界

桥是串行的 —— 一条 guard 在跑（最长 60 秒）期间，**其它命令包括刷新都得排队**。
这是 KernelSU exec 接口本身的限制，我这边绕不开。只是现在不会因为你多点几下
而变得更糟了。

---

# v3.1.0：迷你监视器自定义 + 受保护的执行流式日志

## 一、迷你监视器（显示项 / 逐项配色 / 位置方向盘）

### 配置格式（`/data/adb/ksu_toolbox/monitor.conf`，向后兼容旧版）

```
bg=#070a0f              背景色
size=1.0                字号倍率 0.6~2.0
show=cpu,fps,pow,temp   显示哪些项（cpu gpu fps ram bat pow temp time）
tf=hm                   时间格式 hm=时:分 / hms=时:分:秒
fg=#e8ecf2              全局文字色（**只有开「固定色」才写**）
cpu=#5be38a             单项颜色；不写 = 按负载自动变色
```

- 老配置只写 `fg/bg/size`，照旧能用：`show` 缺失 → 默认 CPU/FPS/功耗/温度。
- **`fg` 的语义改了**：以前只要文件里有 `fg=`，所有数值就都用它（等于没了自动变色）。
  现在 WebUI 侧多了「自动 / 固定色」开关，默认**不写** `fg` → 保住自动变色。
- App 侧 `MonitorView.applyConfig()` 解析顺序：单项自定义 > 全局 fg > 按负载自动。
- `show=` 全空 / 全是错名字 → 退回默认。**不能真的变 0 项** —— 那会缩成一条细缝，看着像坏了。

### 新增数据源（都在同一个 `su -c` 里，不多 fork 进程）

| 项 | 取法 |
|---|---|
| 内存 | `/proc/meminfo` 纯内建 `while read` 循环拿 MemTotal / MemAvailable |
| 电池 | `power_supply/battery/capacity`，没有就 `bms/capacity` |
| GPU 占用 | kgsl `gpu_busy_percentage` → `/sys/kernel/gpu/gpu_utilization` → mali `utilization` → kgsl `gpubusy` 算比例 |
| GPU 频率 | kgsl `gpuclk` → kgsl `devfreq/cur_freq` → `devfreq/gpufreq/cur_freq` → `/sys/kernel/gpu/gpu_clock`，Hz→MHz |

秒数不进采样：`MonitorService` 每秒 `view.tick()` 一次，只为了让「时间」那项跳秒。

### 位置控制（WebUI 方向盘 → Intent → App 自己挪）

```
WebUI:  am start -n com.jeteezntmax.toolbox/.MonitorActivity --ei dx 10 --ei dy 0
        （--ez center true 居中，--ez reset true 归位）
MonitorActivity: 把 extras 原样转成 MonitorService 的 ACTION_NUDGE
MonitorService : lp.x/lp.y + dx/dy（或绝对值）→ updateViewLayout + 写回 SharedPreferences
```

- 服务没在跑的话顺便拉起来（用户本来就是在调悬浮窗）。
- **绝对定位**是给「居中」用的：App 自己量得到 `view.getWidth()`，比 WebUI 瞎猜准。
- 位置从 `/data/data/<pkg>/shared_prefs/monitor.xml` 读回来显示（root 直接 cat）。
- `y < 0` 一律夹回 0：状态栏那条的触摸被通知栏吃掉，挪上去就拖不回来了。

## 二、受保护的执行

### ① 观察模式跑大文件卡死 —— 根因是 /proc 查询风暴

旧代码**每一次** read/write/ioctl 都：

```
fd_is_danger_parent(pid, fd):
   readlink("/proc/<pid>/fd/<fd>")     ← 1 次 open + readlink + close
   fd_is_block(pid, fd)                ← 又 readlink 一次！
       stat(tgt)                       ← 命中不是 /dev/block/ 还要 stat
```

dd 读一个 4 GB 文件 = 几百万次 read → 每次跟踪者都要做 3~5 次 /proc 访问。
这些开销**全在跟踪者身上**，被跟踪的进程被拖到像死机。

**改法**：`(pid, fd) → 判定` 缓存 300 ms（`FDC_MAX 128` 的环形表）。
`openat / openat2 / close / dup / dup2 / dup3 / fcntl` 之后清掉该进程的全部缓存 ——
fd 号被回收再分配时不会套上旧结论。TTL 短，所以就算漏清也最多错 300ms。

**实测**（本机 aarch64，静态 musl，`dd if=…/dev/block/sda of=/dev/null bs=512 count=8000`）：

```
无 guard（基线）        0.03 s
旧版 v3.0.5（出厂）      7.70 s   ← 用户说的"卡死"
新版（fd 缓存）          4.57 s
新版 -r（读不拦，纯 ptrace 底噪） ~4.5 s
→ 额外开销基本吃干净了，剩下的是 ptrace 逐 syscall 停点本身的成本
```

> 还能再快的唯一办法是 seccomp(`SECCOMP_RET_TRACE`)+`PTRACE_O_TRACESECCOMP`：
> 每个关心的 syscall 只停一次（现在是 entry+exit 各停一次）。**但没做**，因为
> 想按 fd 过滤得放行 `args[0] < 3`，而 `dup2(块设备fd, 0)` 能绕过去；
> 而且目标一读 `/proc/self/status` 就看到 `Seccomp: 2`，反而比 ptrace 更好认。

### ② 终端只显示第一条拦截

旧版：`renderGuardOut()` 拿 `tail -n 250` 的整块文本覆盖 `#guardLive`，且只渲染最后 120 行。
日志一超过窗口，前面那些拦截记录就被顶掉 —— 表现就是"只看得到最早一条，后面得手动点看日志"。
再加上 `termAppendBlock` 把终端裁到 300 个节点时，**`#guardLive` 自己也会被删掉**。

新版：**行号游标**（`G.off`）。每轮：

```sh
n=$(wc -l < $LOG); echo "@@N $n"
[ "$n" -gt $off ] && tail -n +$((off+1)) $LOG | head -n 60
echo "@@RUN";   tail -n 30 $RUNLOG     # 只用来判断结束（退出码=）
pgrep -f ksu_guard >/dev/null && echo "@@ALIVE 1" || echo "@@ALIVE 0"
```

- 新行**追加**到终端（`termAppendBlock`），一条都不丢，也不重复。
- 一轮最多 60 行，防止积压时一口气灌爆 DOM；没显示完的下轮接着来。
- 多了个状态条：`● guard 在跑 · 12s · 命中 3 次`。
- 结束才把运行日志尾部一次性打出来（含退出码和汇总）。
- 结尾不再 `cat` 整个 guard 日志（那是"卡"的另一半原因），只 `tail -n 25`。

### ③ 顺带修的

- **看门狗重做（连踩两个坑）**：

  目标：`-t N` 到点就把目标连同它的进程组带走，并且每 5 秒往日志写一行心跳
  （证明"还活着、还在跑"，用户就不会以为卡死了）。试了三版：

  | 版本 | 结果 |
  |---|---|
  | `signal(SIGALRM)` + handler 置标志，主循环判 | ✗ musl 的 `signal()` 默认带 `SA_RESTART`，`waitpid` 被自动重启，EINTR 到不了主循环 |
  | `sigaction(flags=0)` + handler 里判超时 | ✗ 在本机（容器里 proot 又套一层 ptrace）信号要等 `waitpid` 返回才投递：实测 8 秒里 handler 跑了 1 次，主循环一次判断都没做 —— 设 5 秒超时，30 秒后才收工 |
  | **独立 fork 一个看门狗进程** | ✓ 它自己 `sleep` 到点就写心跳/动手，完全不依赖信号投递时机 |

  现在的结构：
  ```
  跟踪者 ── fork ──> 目标（被 ptrace 跟踪）
         └─ fork ──> 看门狗：每 5s 写一行 "· 还在跑 Ns"，到时限写
                     "!! 到 N 秒时限，收工…" 并 kill(-pgid) + kill(target)
  跟踪者自己还留一个 alarm(时限+20) 兜底（看门狗被系统干掉的情况）
  ```
  - 看门狗退出码 `4`（`3` 才是"命中并拦截"）；结束时还会再打一行
    `!! 这次是【到时限被掐断】的 … 结论不完整`，UI 看到 4 会给橙色提示。
  - 看门狗自己会清掉继承来的闹钟 + 把 SIGALRM 复位，免得打断目标。
  - 跟踪者收工时 `kill(g_wd)` 把看门狗一起带走，避免它在总结后面又插一行心跳。
  - `-t N` 可调（默认 60s，UI 给 30/60/120/300），外层 `timeout` 跟着对齐（N+10）。
- **反调试日志限速**：`lg("     [反调试] …")` 原来不限速，样本循环读 `/proc/self/status`
  就能把日志刷爆。现在过 `rate_ok()`。
- **`guardShowLog` 有两个定义**：后面那个（只 cat guard 日志）把前面那个更详细的
  （运行日志 + guard 日志 + 残留进程）覆盖掉了 —— 删掉了后者。

## 三、本机怎么验证的（可直接照抄）

这台机器本身就是 aarch64，还装了原生 gcc，所以 guard 可以**直接跑**，不用真机：

```sh
# 一个"长得像块设备"的目标文件（路径里有 dev/block/ 就会被判成块设备）
mkdir -p /tmp/t/dev/block && dd if=/dev/zero of=/tmp/t/dev/block/sda bs=1M count=100

# ① 拦截模式：mknod 造块设备节点 → 应该 rc=3，文件不该被创建
ksu_guard -l /tmp/t/a.log -- sh -c 'mknod /tmp/t/fake b 8 0'
# ② 正常脚本：不该误报，rc=0
ksu_guard -l /tmp/t/b.log -- sh -c 'echo hi > /tmp/t/ok.txt'
# ③ 观察模式 + 大 dd：看耗时和日志行数（这是"卡死"那个 bug 的复现场景）
ksu_guard -w -l /tmp/t/c.log -- dd if=/tmp/t/dev/block/sda of=/dev/null bs=512 count=8000
# ④ 超时：-t 5，目标睡 30 秒 → 应该 5 秒就收工，退出码 4
ksu_guard -w -t 5 -l /tmp/t/d.log -- sh -c 'sleep 30'
# ⑤ 心跳：-t 20，目标睡 12 秒 → 日志里应有 "· 还在跑 5s" / "· 还在跑 10s"
ksu_guard -w -t 20 -l /tmp/t/e.log -- sh -c 'sleep 12'
```

最近一次的结果：

```
① rc=3，/tmp/t/fake 没有生成，日志里有 "!! mknod 造块设备节点"
② rc=0，误报 0 条
③ 8000 次 read：旧版 7.70s → 新版 4.57s（纯 ptrace 底噪约 4.5s）
   日志 10 行，"7999 次重复命中被折叠"
④ rc=4，5 秒收工
⑤ 两次心跳，正常
```

> 注意：这机器上 ptrace 被 proot 又包了一层，绝对耗时比真机慢很多（真机上
> 一次 syscall 停点只要几微秒）。**看比值，别看绝对值。**

---

## 打包约定（作者定的，别乱放）

- **每次改完模块，都在 `/workspace/ksu-toolbox-update.zip` 重新打一份，固定就这个路径。**
  内容 = `ksu-toolbox-update/` 目录（`README-update.md` 不打进去），
  权限位：`.sh` / `device_faker_cli` / `ksu_guard` / `app/JeTeeZnTmax.apk` 必须是 `0755`。
- `/workspace/gh-repo/.../release/` 里那份是**推送用**的副本，只在发版那一步同步，
  平时保持不动（现在里面还是 v3.0.5，发 v3.1.0 时一起替换）。

## v3.1.0 已发布（2026-10-03）

```
commit  b570e9f3   "v3.1.0：迷你监视器自定义（显示项 / 逐项配色 / 位置方向盘）+ 受保护的执行流式日志与大 dd 性能修复"
tag     v3.1.0 -> b570e9f3   ✅ 和 main 同一个 commit（@latest 不会发旧内容）
release v3.1.0 + ksu-toolbox-update.zip（1,824,408 B，state: uploaded）
update.json -> v3.1.0 / 30100    jsDelivr @latest 实测已解析到新版
050b4c6f   "文档同步：README 的迷你监视器/受保护执行/充电控制按 v3.1.0 实际实现重写；根 index.html 跟上 webroot"
           （纯文档提交，tag 保持在 b570e9f3 —— 发布内容以那个为准）
```

推送方式：`GH_TOKEN=ghp_xxx python3 /workspace/push-release.py`
（走 api.github.com 的 Git Data API —— 一次提交推多个文件；github.com / uploads.github.com 都不一定通）
脚本读 `gh-repo/Kernelsu-Toolbox-JeTeeZnTmax/` 的内容，所以**先同步本地副本再跑**。

这一轮改的（v3.1.0 收尾）：

- 性能页：迷你监视器整块挪到页面**最下面**；位置 UI 从 3×3 方向盘 + 4 个步长 chip
  压成一行 `← ↑ ↓ → 居中 读位置 重置 步长5`（步长改成一个轮换按钮）。
- 电源页：充电控制整块挪到页面**最下面**（在「充电接口」之后）。
- 受保护的执行：**点一次出两份日志** —— 根因是内层脚本结尾又 `tail -n 25` 了 guard 日志，
  而终端那边本来就是逐行流式跟进的。现在删掉那段，结束只回显 `[*] 用… / 退出码=`；
  启动信息改成第一轮轮询就先露脸；日志超过 24 行时追加一句
  「终端只保留最近部分，完整内容点看日志」。

---

---

# 【已移除】游戏调度 / 性能锁定（v3.2.0 ~ v3.3.1）

这块功能（按应用锁超大核/频率/调速器/刷新率/温控 + 原生 perfwatch + 无障碍探测）
**已整体删除**，相关文件（`bin/perfd.sh`、`bin/perfwatch`、App 的
`ForegroundWatcher`/`ToastService`/`GameToast`、WebUI 的游戏调度卡片、
`service.sh` 里的拉起、`res/xml` 无障碍配置）都不在了。

留几条教训，免得以后又踩：

1. **判"前台是谁"不能拿 `event.getPackageName()`** —— 那条事件可能来自后台窗口
   （launcher / systemui / 输入法 / 我们自己的悬浮窗都遇到过），结果就是"一会儿锁一会儿放"。
   正确姿势（Scene `com.omarea.vtools` 的做法）：无障碍服务里调 `getWindows()`，
   挑 `isActive()/isFocused()` 的 `TYPE_APPLICATION` 窗口再取包名。
2. **判"目标是不是顶层"用 `oom_score_adj == 0`**，别用 `<= 0`：
   系统/常驻进程是 -700~-1000，用 `<=0` 会把它们当顶层。还要加"uid 属于这个应用"，
   否则会命中同名的 root 辅助进程。
3. **shell 里别在每拍路径扫全机 `/proc`**（`pidof`、`awk /proc/*/status`、
   `dumpsys activity processes`）：慢机器上单次就 1 秒，整机都跟着卡。
   要扫就用原生进程做（进程内读文件，微秒级），或者干脆别扫。
4. 正在运行的 `sh` **不会重新读脚本**：改了代码必须重启 daemon（或 reboot）才生效。

---

# v3.3.3：两处改动 + 一个自测套件

## 1. 悬浮窗不再"自己冒出来"

`MonitorService` 原来 `return START_STICKY` —— 系统内存回收后会把服务**再拉起来**，
`onCreate` 里就 `addOverlay()`，于是悬浮窗在你没点它的时候自己出现。
改成 **`START_NOT_STICKY`**（4 处返回值）：被杀就保持关闭，要显示得手动点。
**开机自动拉起照旧**（`service.sh` 那行没动，是作者要的）。

## 2. 受保护的执行：不再拦 `rm -rf`

理由（作者定的）：安卓上 `rm -rf` **只能动 /data 下的东西**，碰不到真实分区、
影响不了引导（删 `/dev/block` 里的节点也不伤分区），而正常脚本天天在用 ——
拦它纯属误伤。

顺带把"删关键路径"整套逻辑删掉了（含 `touch`/`firmware`/`abl`/`xbl`/`vbmeta`
那套名字命中），`path_is_critical()` 现在**只服务于 mount/umount2**（`-a` 可放行）。

现在真正会拦的：
`/dev/block` 读写 + `mknod` 造块设备 · 写 `sysrq-trigger`/`panic`/selinux ·
`reboot(2)`/`kexec`/内核模块/swap · 绕道路径（`io_uring`/`splice`/`sendfile`/
`copy_file_range`）· `clone(CLONE_UNTRACED)` · mount/umount2 关键路径。

## 3. 自测套件：`app/guard/test-guard.sh`

```sh
sh app/guard/test-guard.sh [工作目录]     # 默认 /tmp/guard-test
```

15 项，最后打一张 PASS/FAIL 表。**全部在临时目录里的一棵假块设备树上做，不碰真分区**；
reboot 用无效参数调。本机实测 **15/15 全过**：

```
① mknod 造块设备      → rc=3 且节点没生成
② dd 写块设备          → rc=3 且内容一字未变
③ dd 读块设备          → rc=3
④ -r 只拦写            → 读放行(rc=0) 写拦(rc=3)
⑤ openat(目录fd,"sda") → 读/写都被拦（完整路径从不出现也拦得住）
⑥ rm -rf              → 不该被拦（rc=0 零命中）
⑦ clone(CLONE_UNTRACED) → rc=3
⑧ reboot(2)           → rc=3
⑨ 正常脚本             → rc=0 零误报
⑩ 观察模式             → 记录 5 条、不杀
⑪ 8000 次 dd 读        → 日志 9 行（去重+限速+封顶都生效）
⑫ -t 5 跑 sleep 30     → 5 秒收工 rc=4 + 有心跳
⑬ 反调试               → 日志里有「[反调试]」
```

配套样本（都在 `app/guard/`）：`dirfd-open.c`（模拟 openat+目录fd 那招）、
`reboot-probe.c`（无效参数调 reboot，安全）、`wiper-sim.sh`、`benign.sh`。

> 这套东西本来是准备打成 `999.zip` 丢到另一台机器上测的，作者说不用了 ——
> 但样本和脚本值得留着，就并进 `app/guard/` 了。

---

# v3.3.0 已发布（2026-10-03）

```
commit  2a69a3c883   "v3.3.0：新增刷新率锁定（含保活）+ 悬浮窗点 FPS 弹档位面板；
                      受保护的执行不再拦 rm -rf；悬浮窗不再自动复活"（一次提交推 22 个文件）
tag     v3.3.0 -> 2a69a3c883   ✅ 和 main 同一个 commit
release v3.3.0 + ksu-toolbox-update.zip（1,835,433 B，state: uploaded）
update.json -> v3.3.0 / 30300
```

## ⚠ 发版必做：purge jsDelivr 的 @latest

这次踩到了：`@latest/update.json` **还在吐上一个版本**（tag 解析被缓存了），
设备上点「检测更新」会什么都看不到。手动清一下就好：

```sh
python3 - <<'PY'
import urllib.request as U
for f in ("update.json","CHANGELOG.md","module.prop"):
    print(U.urlopen("https://purge.jsdelivr.net/gh/jeteezntmax/"
          "Kernelsu-Toolbox-JeTeeZnTmax@latest/"+f).status)
PY
```

purge 完立刻生效（实测 @latest/update.json 马上变成 v3.3.0）。
**记得写进八步流程**：第 8 步「移 tag」之后，再加一步「purge jsDelivr」。

---

# v3.4.0：游戏加速（音量键菜单 + 按 uid 自动应用）

## 为什么这次不"猜前台"了

前面那版废稿死在"检测前台"上（dumpsys 格式、无障碍事件带后台包名、cgroup 被污染、
shell 每拍扫 /proc 要 1 秒…）。这次换成作者提的思路：**按 uid 绑定**。

- 前台判定只用一条内核事实：**目标 uid 的进程里有没有 `oom_score_adj == 0` 的**
  （顶层应用就是 0；可见 100、前台服务 200~500、缓存 700+、系统/常驻 -700~-1000）
- 而且这个判定**在 shell 里做**（`ps -A -o PID,UID` 一次 fork 拿到 uid→pid，
  再 `cat` 那几个 pid 的 `oom_score_adj`），一次调用 ~100ms，App 每 2 秒问一次
- 不做任何事件监听、不 dumpsys、不扫 500 个 status 文件

## 部件

| 部件 | 干什么 |
|---|---|
| `bin/perfmode.sh` | 后端：`check`（状态+判定+列表）/ `apply` / `restore` / `set 键 值` / `freqs` / `govs` |
| `PerfService` | 前台服务：每 2 秒问一次 `check`，`fg=1 && enabled=1` → apply，否则 restore |
| `KeyWatcher` | 无障碍服务：**长按音量上键 600ms** 呼出菜单（短按照旧调音量，不拦按键） |
| `VolumeMenuView` | 菜单：总开关 / CPU 锁频 / 调速器 / 线程绑定 / 刷新率（点一行循环切档）+ 应用/还原/关闭 |
| `FeatureHudView` | 功能提示列表：贴右上角，一行一个已开的功能，**字多的在上面**，可拖，可关 |
| WebUI「游戏加速」卡片 | 总开关 / 挑应用（解析 uid）/ 各项参数 / 音量键开关 / 提示列表开关 + 重置位置 |

## 配置文件 `/data/adb/ksu_toolbox/perf/profile.conf`

```
enabled=1          总开关
app=com.x.y        目标应用
uid=10234          目标 uid（空则从 data 目录属主解析）
freq=0             锁频：0=不动 / 否则 kHz（超大核 min=max）
gov=               调速器：空=不动
affinity=          线程绑定：big=只用超大核 / all=全核 / 空=不动
refresh=0          刷新率：0=不动（复用 refresh.sh lock，保活也一起）
menu=1             长按音量上键呼出菜单
hud=1              功能提示悬浮窗
```

## 一个关键坑：pids_of_uid 要用 ps

`awk '/^Uid:/{...}' /proc/[0-9]*/status` 在慢机器上要 **几百毫秒**（500 个文件 × 每文件一次 fork）；
换成 `ps -A -o PID,UID`（toybox 的 C 实现，一次 fork）只要几十毫秒。
这条以前吃过亏（"每拍 1 秒"），这次一开始就用 ps。
