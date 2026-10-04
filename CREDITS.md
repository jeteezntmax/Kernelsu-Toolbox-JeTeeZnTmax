# 署名与第三方来源

本模块不是从零写出来的。感谢下面这些人和项目。

---

## 一、核心依赖（**不在本仓库内**，请自行获取）

### Device Faker

- 作者：[Seyud](https://github.com/Seyud/device_faker)
- 协议：**GPL-3.0**
- 本模块机型伪装功能的引擎

> 因为它是 GPL-3.0，本人**无权对它附加「禁止修改」之类的额外限制**，
> 所以它的任何文件（`device_faker_cli`、`zygisk/arm64-v8a.so`、
> 默认配置、GPL 协议文本）**都没有放进本仓库**。
> 你要用机型伪装功能，请直接去上游下载安装。

### KernelSU

- 作者：[tiann](https://github.com/tiann/KernelSU) 及社区
- 本模块运行的基础

### KernelPatch

- 作者：[bmax121](https://github.com/bmax121/KernelPatch)
- 「受保护的执行」用的是 ptrace，但接口设计参考了它的思路

---


### 对本人的修改说明

`eg-setup.sh` 相对原版改了两处（都是原版的 bug）：

1. `customize.sh` 调用它时没有 export `MODPATH`，子进程读不到变量，
   导致扫描不到温控 XML、挂载 0 个。改成脚本自己定位 `MODPATH`。
2. 原版 `for override in "$2"` 把多行列表当成单行处理，
   导致 XML 覆盖文件是空的。已修正为逐行处理。

---

## 三、UI 设计

- **UI 借鉴：[月虹yh](https://github.com/YueHongYH)（@月虹yh）**

本模块 WebUI 的玻璃拟态风格、卡片分层、底部 Dock、
环形仪表盘等视觉设计，参考了 **@月虹yh** 的作品。
在此致谢。

---

## 四、本仓库中属于本人原创的部分

```
webroot/index.html        全内联 UI（HTML/CSS/JS）
bin/ksu_guard             ptrace 拦截器（C，自写）+ 源码
bin/collect.sh            HTTP 快照采集
bin/webui-server.sh       本地 127.0.0.1 服务
customize.sh / service.sh / module.prop
app/                      独立桌面 App（Java + 构建脚本 + 图标生成）
app/guard/                上面那些安全测试用的小程序
```

其中 **`ksu_guard` 是全新写的**，下面是它的技术参考：

- `ptrace(2)` 手册 —— syscall-entry / syscall-exit 的语义
- ptrace 的 `PTRACE_O_TRACECLONE | TRACEFORK | TRACEVFORK` 递归跟踪机制
- `CLONE_UNTRACED` 逃逸手段的处理思路
- Android 属性服务（`property_service`）的消息格式，
  用于拦截 `sys.powerctl` 这条重启路径

---

## 五、如果漏了谁

请开 Issue 告诉我，我马上补上。
