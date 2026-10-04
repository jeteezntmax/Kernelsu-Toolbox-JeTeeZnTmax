# JeTeeZnTmax

KernelSU 工具箱模块 —— **一个把系统底层信息、机型伪装、温控、应用/进程管理
和「受保护的执行」全塞进一个玻璃拟态 WebUI 的东西。**

作者：**[@JeTeeZnTmax](https://github.com/jeteezntmax)**
UI 借鉴：**@月虹yh**

---

## 功能

### 🛡 受保护的执行（本模块最特别的一块）

把一个**来路不明的脚本或程序**放在 `ptrace` 底下跑，在危险 syscall
**执行之前**把它拦下来。

不是沙箱、不是虚拟机 —— 是同权限下的 syscall 级拦截。

**为什么需要它：**

现在流行的「格机」程序基本都是这样写的：

```c
#define my_openat(dirfd, path, flags, mode) syscall(__NR_openat, dirfd, path, flags, mode)
```

**全部走 raw syscall** —— `LD_PRELOAD` / libc hook 完全失效；
而且它用 `openat(目录fd, "sda1")` 这种方式打开块设备，
连完整路径都不给，基于路径匹配的 LSM / seccomp 也拦不住。

**ptrace 在内核停点上看得到，它绕不过。**

**拦什么：**

| 类别 | 内容 |
| --- | --- |
| 块设备 | `/dev/block` 下的一切（读、写、列目录、stat、readlink），`/proc/partitions` |
| 建节点 | `mknod` / `mknodat` 带 `S_IFBLK` |
| 内核开关 | 写 `/proc/sysrq-trigger`、`/proc/sys/kernel/panic`、`/sys/fs/selinux` |
| 危险删除 | `/vendor` `/system` `/persist` `/metadata` `/dev/input` `/data/data` 等，以及名字含 `touch` `firmware` `modem` `abl` `xbl` `vbmeta` 的 |
| 绕道路径 | `io_uring_setup/enter/register`、`splice`、`sendfile`、`copy_file_range` |
| 逃逸监控 | `clone` / `clone3` 带 `CLONE_UNTRACED` |
| 重启 | `reboot(2)` syscall、属性 `sys.powerctl` |

**两种模式：**

- **先观察** —— 只记录不拦。**先用它跑一遍看清行为，再决定要不要动刀**
- **拦截并杀死** —— 命中立刻 SIGKILL 整个进程组

**两种范围：**

- **读写全拦**（默认）—— 碰 `/dev/block` 就杀
- **只拦写** —— 放行读。**自己备份 boot 镜像的时候用这个**，
  否则 `dd if=/dev/block/by-name/boot of=/sdcard/boot.img` 会被当成攻击一起杀掉

**细节：**

- **日志实时滚动**：命中的那一刻就滚进终端，一条不丢；日志太长会提示你点
  「看日志」看完整内容（终端只保留最近那部分）
- **时限可调**：30 / 60 / 120 / 300 秒。到点连目标的**整个进程组**一起收工，
  退出码 `4`（`3` 才是"命中并拦截"），界面会明确告诉你这次结论不完整
- **看门狗是独立进程**：哪怕目标在狂刷 syscall 把跟踪者卡住，到点照样收工
- **大文件不会被拖死**：fd 判定带 300ms 缓存。旧版每一次 `read` / `write` 都要
  `readlink("/proc/<pid>/fd/N")` 判断是不是块设备，`dd` 几百万次读下来这些开销
  全砸在跟踪者身上，被跟踪的进程会被拖到像死机

### 📦 应用管理

全部应用列表（中文应用名）、搜索、点开看包名 / 版本 / 安装路径 / SDK /
UID / 大小 / 安装时间。支持**冻结**、**卸载**、**备份 APK**。

### ⚙️ 进程管理

PID / PPID / 用户 / **实时 CPU%（两次采样差值，不是生命周期平均值）** /
RSS / 状态 / cpuset / cgroup / wchan，可按 CPU / 内存 / PID / UID 排序，
底栏搜索。可以 **kill -9**。

### 🎚 迷你监视器（悬浮窗）

性能页最下面 →「打开悬浮窗」，把一条**浮在所有应用上面**的实时状态条放出来：

```
┌──────────────────────────────────────────────────────────────────────┐
│ CPU 20% │ GPU 27% │ FPS 60 │ 内存 63% │ 电池 88% │ 功耗 1.97W │ 温度 36.0° │ 10:24 │
└──────────────────────────────────────────────────────────────────────┘
```

**显示项随便挑**（八项里勾）：

| 项 | 说明 |
| --- | --- |
| CPU | 两次 `/proc/stat` 采样差值 |
| GPU | 高通 kgsl → MTK → Mali 依次探测占用率，拿不到就退而显示频率 |
| FPS | `Choreographer` 数屏幕的刷新回调 —— **不用 root、不用 dumpsys** |
| 内存 | `/proc/meminfo` 的 MemAvailable / MemTotal |
| 电池 | `power_supply/battery/capacity` |
| 功耗 | `current_now × voltage_now`，自动判断 µA/mA 与 µV/mV |
| 温度 | 电池温度（`battery/temp`，拿不到退 `bms/temp`），十分之一度自动识别 |
| 时间 | `时:分` 或 `时:分:秒`（秒是本地每秒跳，不占采样） |

**配色**：每一项都能单独指定颜色；不指定就按负载自动变色（CPU / GPU / 内存按占用，
温度按冷热，电量按高低）。

**位置**：在性能页用一行方向键就能挪 —— `← ↑ ↓ →` + 居中 / 读位置 / 重置，步长可切；
也能直接拖，位置会记住。**双击悬浮窗关闭**，或点通知里的「停止」。

数据由桌面 App 直接读 sysfs（App 有 root），不走 shell —— 所以整条状态条没有额外进程开销。
每 2 秒刷新一次，帧率每秒更新，时间每秒跳。

### 🎮 游戏加速（音量键菜单 + 提示悬浮窗）

- **音量上键呼出菜单**（音量下键收起）：总开关 / CPU 锁频 / 调速器 / 线程绑定 / 刷新率
  + 应用 / 还原 / 清后台 / 关闭
- **按 uid 绑定，不做前台猜测**：只看"这个 uid 的进程里有没有 `oom_score_adj == 0` 的"
  （顶层应用就是 0），在就自动应用、退出自动还原
- **功能提示悬浮窗**：右上角，标题行 + 已打开的功能 + 系统参数（时间到秒/CPU 频率/FPS/功耗/温度），
  按字数从多到少排，可拖、可勾显示项、彩虹渐变字一直流转
- **清后台**：只清第三方 + 缓存态进程，支持白名单（目标应用永远受保护）
- **保活**：被别的组件改回去会自动重写并计数；关掉的项从原值还原
- 需要无障碍的「过滤按键事件」（WebUI 一键开）

### 🎚 刷新率锁定（性能页）

- **扫描档位** —— 读 `dumpsys display` 的模式表，列出本机可用刷新率，
  并记下每档对应的 `modeId`（有些 ROM 只认 `cmd display set-user-preferred-display-mode`）
- **持续锁定** —— 写 system 的 `peak_refresh_rate` / `min_refresh_rate`，
  另外尽量切 display 的 user-preferred-mode
- **保活** —— 每 8 秒复查一次，被别的组件改回去就重写并计数
  （界面提示「⚠ 被改回去 N 次」）；开机自动恢复保活
- **恢复原值** —— 锁定前的值会先存下来，点一下原样还原
- **悬浮窗上直接换档** —— 监视器开着、显示项里有 FPS 时，
  点悬浮窗上的「FPS」会在它正下方弹出档位面板（每档一颗药丸 + 「恢复」，
  当前档高亮）；点面板外面 / 8 秒无操作 / 拖动悬浮窗都会自动收起

### 🌡 温度 / 电源 / 性能

- 环形仪表盘、各核心频率、真实 CPU 占用趋势
- 实时功率、电池健康 / 循环次数、充电信息、剩余时间
- **充电控制**（电源页最下面）：充电上限 / 停充恢复 / 慢充 / 功率上限 / 输入限流。
  每项能力列一串候选节点，按「厂商专属 → 通用」挑第一个**存在且可写**的，
  找不到就明确回「不支持」—— 探测式，不绑死某一家
- 温度分类总览、thermal zone 状态、降温限频、充电电流限制
- **Extreme GT 去温控**集成（见下方"依赖"）

### 📱 机型伪装

Device Faker 的配置前端（TOML 编辑、模板管理、备份）。

### 🌐 浏览器访问

设置页开启后，在浏览器输入 `127.0.0.1:8765` 就能用同一个界面。
（只读快照，不需要 token）

### 📱 独立桌面 App

`app/` 是真源码，`release/` 有编好的 APK。

它自带 `ksu` 桥，所以**打开就是完整功能，不是只读预览**。
自带应用图标（走 PackageManager，比 shell 快得多）。

---

## 安装

### 要求

- **KernelSU**（或任何提供 `su` 的 root 方案）
- Android 8+ / arm64
- 建议先完整备份

### 步骤

1. 下载 Release 里的 `ksu-toolbox-update.zip`
2. KernelSU 管理器 → 模块 → 从本地安装
3. 重启
4. 管理器里点模块的「打开」，或装桌面 App

---

## ⚠️ 依赖（**必须自己装，本仓库不含**）

### Device Faker —— 机型伪装需要

- 地址：https://github.com/Seyud/device_faker
- 协议：**GPL-3.0**

> 因为它是 GPL-3.0，本人**无权对它附加「禁止修改」这类限制**。
>
> **本仓库的源码里不含它的任何文件** —— 你要二次开发，请去上游拿。
>
> 但 `release/` 里的**安装包**为了开箱可用，打包了它的运行时文件
> （`device_faker_cli`、`zygisk/arm64-v8a.so`）。**这部分版权归 Seyud，
> 遵循 GPL-3.0**，可以自由再分发 / 修改，不受本仓库协议约束。

### Extreme GT —— 去温控需要

- 原作者：**嘟嘟ski & AB**
- 版本：vAB-1.3.0（二改板：无损去温控）

本仓库内有三个**基于它修改**的文件（`eg.sh` / `eg-setup.sh` /
`sys_thermal_control_config_default.xml`），它们**不属于本人原创，
不适用本仓库协议**，详见 CREDITS.md。

---

## 目录结构

```
.
├── index.html              和 webroot/index.html 同一份（给 Pages / 直接看用）
├── webroot/index.html      全内联 WebUI（HTML/CSS/JS，约 285KB，一个文件装完）
├── bin/
│   ├── ksu_guard           ptrace 拦截器（aarch64 静态 ELF，纯 musl）
│   ├── chg.sh              充电控制后端（节点探测 + 写入）
│   ├── refresh.sh          刷新率锁定 / 保活（scan/lock/restore/keepalive）
│   ├── collect.sh          HTTP 快照采集
│   └── webui-server.sh     本地服务（只监听 127.0.0.1）
├── app/                    独立桌面 App 源码
│   ├── java/.../MainActivity.java / MonitorService.java / MonitorView.java /
│   │            SysStats.java / MonitorActivity.java
│   ├── build.sh            不用 Gradle，直接调 SDK 工具链
│   ├── make_icon.py        纯 Python 生成图标（不依赖 PIL）
│   └── guard/              ksu_guard 的 C 源码 + 测试小程序
├── customize.sh / service.sh / module.prop
└── release/                编好的 APK 和模块 zip
     ↑ 模块 zip 里打包了 Device Faker 的运行时（GPL-3.0，见上）
       源码目录里没有它
```

---

## 自己编译

### WebUI

`webroot/index.html` 是全内联的，改完直接刷。

### ksu_guard（ptrace 拦截器）

需要 aarch64 交叉工具链 + musl 静态库：

```sh
# 工具链
apt install gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu
# musl（要自己编一份 aarch64 静态版，装到 /opt/musl）
sh app/guard/build-guard.sh
```

### 桌面 App

**不需要 Android Studio**，只要 JDK + SDK 的 build-tools：

```sh
sh app/build.sh
```

它干的事：`aapt2 compile` → `aapt2 link` → `javac` → `d8` →
塞 `classes.dex` → `zipalign` → `apksigner`。

---

## 协议

本模块采用 **GNU General Public License v3.0（GPL-3.0）**。

**为什么是 GPL**：模块里打包了 **Device Faker**（GPL-3.0，见 `NOTICE-device_faker.txt`）。
按 GPL 的要求，**把 GPL 组件和别的代码组合在一起分发时，整个组合作品也必须按 GPL 发布** ✓。
所以从本版起整个模块统一为 GPL-3.0 —— 以前那份「自定义许可 / 禁止二改后发布」不再适用
（GPL 不允许附加额外限制，那几条必须去掉）。

这意味着：

- ✅ 免费用、随便改、**改完也可以再发布**、可以拿去做别的项目
- ✅ 可以商业使用
- 📌 **但必须**：保留作者署名与版权声明；把你发布的那份**同样以 GPL-3.0 授权**，并**提供对应源码**
- 📌 **不能**再加任何额外限制（不改署名、不改协议、不加"禁止二改"之类的条款）

第三方组件各自保留原许可：**Device Faker**（GPL-3.0）、**Extreme GT**（原作者授权，
见 `NOTICE-extreme-gt.txt`）。

完整条款见 [LICENSE](LICENSE)（GPL-3.0 全文）。

---

## 免责声明

本模块涉及系统底层修改（内核接口、分区读写拦截、Zygisk 注入）。

**使用风险自负。** 因使用本模块导致的设备损坏、数据丢失、无法开机、
保修失效、账号被封等后果，作者不承担责任。

**请务必先备份，并先在备用设备上验证。**

---

## 致谢

- **@月虹yh** —— UI 设计借鉴
- **Seyud** —— Device Faker
- **嘟嘟ski & AB** —— Extreme GT
- **tiann** —— KernelSU

详见 [CREDITS.md](CREDITS.md)。
