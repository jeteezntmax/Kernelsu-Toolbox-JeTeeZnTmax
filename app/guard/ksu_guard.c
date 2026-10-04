// ============================================================
//  ksu_guard — 受保护的执行
//  把一个来路不明的程序放在 ptrace 底下跑，在危险 syscall
//  【执行之前】把它拦下来。
//
//  为什么是 ptrace 而不是 hook：
//    这类样本基本都用 raw syscall（syscall(__NR_openat, ...)）
//    绕开 libc，所以 LD_PRELOAD 那套完全无效。ptrace 在内核
//    停点上看得到，它没法绕。
//
//  用法：
//    ksu_guard -- /path/to/suspicious [args...]      拦截并杀死
//    ksu_guard -w -- /path/to/suspicious             只记录，不杀（先跑这个）
//    ksu_guard -a -- ...                             放行 mount/umount2
//    ksu_guard -t 120 -- ...                         看门狗改成 120 秒（默认 60）
//    ksu_guard -l /x.log -w -- ...                   指定日志
//
//  v3.1.0 性能修复：fd 判定加 300ms 缓存。旧版每遇到一次 read/write 都要
//  readlink("/proc/<pid>/fd/N")（有时还要 stat），dd 大文件 = 几百万次
//  /proc 访问全落在跟踪者身上，被跟踪的进程被拖到像死机 —— 这就是
//  「观察模式跑大文件会卡死」的真正原因。现在同一 fd 300ms 内只查一次。
//
//  注意：不保证 100%。目标如果有反调试（TracerPid 检测、
//  PR_SET_DUMPABLE=0）能识破并改变行为。真恶意的东西请先在
//  备用机上跑。
// ============================================================
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <time.h>
#include <signal.h>
#include <sys/time.h>
/* ── 看门狗 ────────────────────────────────────────────────
   心跳和超时【都在一个独立 fork 出来的看门狗进程里做】，
   不放在跟踪者自己的信号处理函数里。

   为什么（踩过两次）：
     ① musl 的 signal() 默认带 SA_RESTART，waitpid 会被自动重启，
        EINTR 根本到不了主循环；
     ② 换成 sigaction(flags=0) 之后，在容器（proot 又套一层 ptrace）
        里依然不行 —— 实测 handler 一直等到目标自己跑完才被投递：
        跟踪者 99% 的时间堵在 waitpid 上，信号要等这个 syscall
        返回才处理。表现就是"设了 5 秒超时，30 秒后才收工"。
   看门狗是独立进程，自己 sleep 到点就动手，完全不依赖信号投递时机。

   跟踪者另外还留了一个 alarm() 兜底：万一看门狗被系统干掉，
   到点也能把目标带走（真机上信号是正常投递的）。 */
static void watchdog_proc(pid_t target, pid_t tracer, int timeout, const char *logpath) {
    FILE *f = fopen(logpath, "a");
    /* 别继承跟踪者那个闹钟 */
    {
        struct itimerval zero;
        memset(&zero, 0, sizeof zero);
        setitimer(ITIMER_REAL, &zero, NULL);
        signal(SIGALRM, SIG_DFL);
    }
    time_t t0 = time(NULL);
    for (;;) {
        sleep(5);
        if (getppid() != tracer) break;          /* 跟踪者已经退出了 */
        long el = (long)(time(NULL) - t0);
        if (el >= timeout) {
            if (f) { fprintf(f, "!! 到 %ld 秒时限，收工：目标连同它 fork 出来的子进程一起带走\n", el); fflush(f); }
            pid_t g = getpgid(target);
            if (g > 1) kill(-g, SIGKILL);
            if (target > 0) kill(target, SIGKILL);
            break;
        }
        if (f) { fprintf(f, "    · 还在跑 %lds\n", el); fflush(f); }
    }
    if (f) fclose(f);
    _exit(0);
}

static pid_t  g_child   = -1;      /* 目标 */
static pid_t  g_wd      = -1;      /* 看门狗 */
static int    g_timeout = 60;
static void on_alarm(int s) {      /* 兜底：看门狗没了也能到点收工 */
    (void)s;
    if (g_child > 0) {
        pid_t g = getpgid(g_child);
        if (g > 1 && g != getpgid(0)) kill(-g, SIGKILL);
        kill(g_child, SIGKILL);
    }
    _exit(4);                      /* 4 = 到时限收工（3 是"命中并拦截"） */
}
#include <dirent.h>
#include <limits.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/prctl.h>
#include <sys/types.h>

#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

/* aarch64 通用寄存器组 */
struct arm_pt_regs { unsigned long long regs[31], sp, pc, pstate; };

/* ── EINTR 重试 ──────────────────────────────────────────
   装了 5 秒心跳之后（sigaction 故意不带 SA_RESTART），任何一次系统调用
   都可能被打断。ptrace 要是被打断还照常往下走，那一次检查就漏了 ——
   写在块设备上的动作可能正好被放过。所以统一重试。
   注意：这一层必须放在所有 include 之后、任何 ptrace 调用之前。 */
static long xptrace(int req, pid_t pid, void *addr, void *data) {
    long r;
    do { r = ptrace(req, pid, addr, data); } while (r < 0 && errno == EINTR);
    return r;
}
#define ptrace(req, pid, addr, data) xptrace((req), (pid), (void *)(addr), (void *)(data))

#if defined(__x86_64__)
#define NR_ptrace       101
#define NR_prctl        157
#else
#define NR_ptrace       117
#define NR_prctl        167
#endif

/* --- 关心的 syscall 编号（按架构）--- */
#if defined(__x86_64__)
#define NR_openat       257
#define NR_mknodat      259
#define NR_mknod        133
#define NR_write        1
#define NR_pwrite64     18
#define NR_writev       20
#define NR_reboot       169
#define NR_kexec_load   246
#define NR_init_module  175
#define NR_finit_module 313
#define NR_delete_module 176
#define NR_swapon       167
#define NR_swapoff      168
#define NR_unlinkat     263
#define NR_mount        165
#define NR_umount2      166
#define NR_truncate     76
#define NR_faccessat    269
#define NR_newfstatat   262
#define NR_readlinkat   267
#define NR_mkdirat      258
#define NR_symlinkat    266
#define NR_linkat       265
#define NR_renameat     264
#define NR_renameat2    316
#define NR_fchmodat     268
#define NR_utimensat    280
#define NR_statx        332
#define NR_read         0
#define NR_pread64      17
#define NR_readv        19
#define NR_mmap        9
#define NR_ioctl        16
#define NR_getdents64   217
#define NR_execve       59
#define NR_sendmsg      46
#define NR_connect      42
#define NR_clone        56
#define NR_clone3       435
#define NR_sendto       44
#define NR_sendfile     40
#define NR_splice       275
#define NR_vmsplice     278
#define NR_tee          276
#define NR_copy_file_range 326
#define NR_io_uring_setup 425
#define NR_io_uring_enter 426
#define NR_io_uring_register 427
#else  /* aarch64 */
#define NR_openat       56
#define NR_mknodat      33
#define NR_mknod        297
#define NR_write        64
#define NR_pwrite64     68
#define NR_writev       66
#define NR_reboot       142
#define NR_kexec_load   104
#define NR_init_module  105
#define NR_finit_module 273
#define NR_delete_module 106
#define NR_swapon       224
#define NR_swapoff      225
#define NR_unlinkat     35
#define NR_mount        40
#define NR_umount2      39
#define NR_truncate     45
#define NR_faccessat    48
#define NR_newfstatat   79
#define NR_readlinkat   78
#define NR_mkdirat      34
#define NR_symlinkat    36
#define NR_linkat       37
#define NR_renameat     38
#define NR_renameat2    276
#define NR_fchmodat     53
#define NR_utimensat    88
#define NR_statx        291
#define NR_read         63
#define NR_pread64      67
#define NR_readv        65
#define NR_mmap        222
#define NR_ioctl        29
#define NR_getdents64   61
#define NR_execve       221
#define NR_sendmsg      211
#define NR_connect      203
#define NR_clone        220
#define NR_clone3       435
#define NR_sendto       206
#define NR_sendfile     71
#define NR_splice       275
#define NR_vmsplice     75
#define NR_tee          77
#define NR_copy_file_range 285
#define NR_io_uring_setup 425
#define NR_io_uring_enter 426
#define NR_io_uring_register 427
#endif

/* fd 号复位相关的 syscall —— 命中之后要清掉 fd 判定缓存 */
#if defined(__x86_64__)
#define NR_close    3
#define NR_dup      32
#define NR_dup3     292
#define NR_fcntl    72
#else
#define NR_close    57
#define NR_dup      23
#define NR_dup3     24
#define NR_fcntl    25
#endif
#define NR_openat2  437

static int  opt_kill  = 1;   /* 1=拦截并杀死  0=只记录 */
static int  opt_allow_mount = 0;
static int  opt_block_reads = 1;   /* 默认：连读也拦 */
static int  opt_timeout = 60;      /* 看门狗秒数（-t N 可改） */
static FILE *logf = NULL;
static pid_t child = -1;
static int  hit_count = 0;
static long g_antidebug = 0;

/* 每个被跟踪进程/线程各自记 entry/exit —— 共用一个标志一 fork 就错位 */
#define STMAX 512
static struct { pid_t pid; int in; } g_st[STMAX];
static int *st_for(pid_t p) {
    int free_i = -1;
    for (int i = 0; i < STMAX; i++) {
        if (g_st[i].pid == p) return &g_st[i].in;
        if (free_i < 0 && g_st[i].pid == 0) free_i = i;
    }
    if (free_i < 0) { for (int i = 0; i < STMAX; i++) if (g_st[i].pid == 0) { free_i = i; break; } }
    if (free_i < 0) free_i = 0;
    g_st[free_i].pid = p; g_st[free_i].in = 0;
    return &g_st[free_i].in;
}
static void st_drop(pid_t p) { for (int i = 0; i < STMAX; i++) if (g_st[i].pid == p) { g_st[i].pid = 0; g_st[i].in = 0; } }

/* ── 日志去重 + 限速 + 封顶 ────────────────────────────────
   dd 这种一块一块读写的，同一条会刷几十万次。

   ⚠ 上一版的坑：只记「最后一个签名」。
     可程序是在 read / lseek / ioctl / openat 之间来回切的，
     签名每隔一次就变，g_last_sig 一直被顶掉 —— 去重永远不命中，
     于是 dd 每读一块就写一行（用户实测每 500KB 一行，卡死）。
     所以改成【一张表】，记住最近 SIGTAB 条签名。

   另外两道保险：
     · 全局限速：不管什么情况，每秒最多 RATE_MAX 行
     · 总行数封顶：写到 LOG_MAX 行就只计数、不再写文件         */
#define SIGTAB   64
#define SIG_WIN  30          /* 同一条签名 30 秒内都算重复 */
#define LOG_MAX  3000        /* 日志最多这么多行 */
#define RATE_MAX 8           /* 全局最多每秒 8 行 */

static struct { long long sig; time_t t; long cnt; } g_sig[SIGTAB];
static int  g_sig_n  = 0;
static int  g_sig_rr = 0;
static long g_dup_total = 0;
static long long hit_total = 0;
static long g_log_lines = 0;
static time_t g_rate_t = 0;
static int  g_rate_n = 0;

/* 返回 1 = 重复，调用方【什么都别输出】 */
static int is_repeat(long long sig) {
    time_t now = time(NULL);
    for (int i = 0; i < g_sig_n; i++) {
        if (g_sig[i].sig == sig && (now - g_sig[i].t) <= SIG_WIN) {
            g_sig[i].cnt++; g_sig[i].t = now; g_dup_total++;
            return 1;
        }
    }
    int idx;
    if (g_sig_n < SIGTAB) idx = g_sig_n++;
    else { idx = g_sig_rr; g_sig_rr = (g_sig_rr + 1) % SIGTAB; }
    g_sig[idx].sig = sig; g_sig[idx].t = now; g_sig[idx].cnt = 0;
    return 0;
}

/* 全局限速 + 封顶。返回 0 = 这一行不用写了 */
static int rate_ok(void) {
    time_t now = time(NULL);
    if (now != g_rate_t) { g_rate_t = now; g_rate_n = 0; }
    if (g_log_lines >= LOG_MAX) return 0;
    if (g_rate_n >= RATE_MAX) return 0;
    g_rate_n++; g_log_lines++;
    return 1;
}
static void lg_raw(const char *fmt, ...) {
    if (!logf) return;
    va_list ap; va_start(ap, fmt); vfprintf(logf, fmt, ap); va_end(ap);
}

static void lg(const char *fmt, ...) {
    if (!logf) return;
    char t[32]; time_t now = time(NULL);
    struct tm tmv; localtime_r(&now, &tmv);
    strftime(t, sizeof t, "%H:%M:%S", &tmv);
    fprintf(logf, "[%s] ", t);
    va_list ap; va_start(ap, fmt); vfprintf(logf, fmt, ap); va_end(ap);
    fputc('\n', logf); fflush(logf);
}

/* --- 读目标进程内存里的字符串 --- */
static int rdstr(pid_t pid, unsigned long long addr, char *out, size_t n) {
    if (!addr) return -1;
    struct iovec l = { out, n - 1 }, r = { (void *)(size_t)addr, n - 1 };
    ssize_t k;
    do { k = syscall(SYS_process_vm_readv, pid, &l, 1, &r, 1, 0); } while (k < 0 && errno == EINTR);
    if (k <= 0) { out[0] = 0; return -1; }
    out[k] = 0;
    return (int)k;
}

/* --- 在二进制缓冲区里找子串（不能只用 strstr：属性消息开头是二进制的 cmd，
       里面带 NUL，strstr 到第一个 NUL 就停了） --- */
static int mem_has(const char *hay, size_t n, const char *needle) {
    size_t m = strlen(needle);
    if (!m || n < m) return 0;
    for (size_t i = 0; i + m <= n; i++)
        if (memcmp(hay + i, needle, m) == 0) return 1;
    return 0;
}
/* 把二进制内容变成可打印的，写日志用 */
static void sanitize(char *s, size_t n) {
    /* NUL 也换成 '.'，不要在这里截断 —— 属性消息第 5 字节才开始是字符串 */
    for (size_t i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c < 32 || c > 126) s[i] = '.';
    }
    s[n] = 0;
}

/* --- 读目标进程内存的原始字节 --- */
static int rdmem(pid_t pid, unsigned long long addr, void *out, size_t n) {
    if (!addr) return -1;
    struct iovec l = { out, n }, r = { (void *)(size_t)addr, n };
    long k;
    do { k = syscall(SYS_process_vm_readv, pid, &l, 1, &r, 1, 0); } while (k < 0 && errno == EINTR);
    return k == (long)n ? 0 : -1;
}

/* ── fd 判定缓存（「大文件 dd 卡死」的真正原因就在这）─────────────
   旧写法：每一次 read / write / ioctl 都去 readlink("/proc/<pid>/fd/N")，
           命中 /dev/block 的话还要再 stat 一次；而且 is_danger_parent
           里面又调了一遍 fd_is_block —— 一次判定最多 3 次 /proc 访问。
           dd 一个几 GB 的文件 = 几百万次 read，这些开销全落在
           【跟踪者】身上：被跟踪的进程被拖得像死机，日志也跟着爆炸。

   现在：把 (pid, fd) → 判定结果 记 300ms。dd 循环里 fd 一直是同一个，
         几百万次查询塌成每 300ms 一次。
   安全性：TTL 只有 300ms，而且 openat / close / dup 会立刻清掉
         该进程的缓存 —— fd 号被复用之后不会残留旧结论。            */
#define FDC_MAX    128
#define FDC_TTL_MS 300
static struct { pid_t pid; int fd; int v; long long t; } g_fdc[FDC_MAX];
static int g_fdc_rr = 0;

static long long mono_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static int fd_cache_get(pid_t pid, int fd, int *out) {
    long long now = mono_ms();
    for (int i = 0; i < FDC_MAX; i++) {
        if (g_fdc[i].pid == pid && g_fdc[i].fd == fd) {
            if (now - g_fdc[i].t > FDC_TTL_MS) { g_fdc[i].pid = 0; return 0; }
            *out = g_fdc[i].v;
            return 1;
        }
    }
    return 0;
}
static void fd_cache_put(pid_t pid, int fd, int v) {
    int idx = -1;
    for (int i = 0; i < FDC_MAX; i++) {
        if (g_fdc[i].pid == pid && g_fdc[i].fd == fd) { idx = i; break; }
        if (idx < 0 && g_fdc[i].pid == 0) idx = i;
    }
    if (idx < 0) { idx = g_fdc_rr; g_fdc_rr = (g_fdc_rr + 1) % FDC_MAX; }
    g_fdc[idx].pid = pid; g_fdc[idx].fd = fd; g_fdc[idx].v = v; g_fdc[idx].t = mono_ms();
}
/* fd 号可能被回收再分配 —— 这三类 syscall 之后必须立刻清掉该进程的缓存 */
static void fd_cache_flush(pid_t pid) {
    for (int i = 0; i < FDC_MAX; i++) if (g_fdc[i].pid == pid) g_fdc[i].pid = 0;
}

/* 0 = 普通文件/终端/管道…, 1 = 块设备, 2 = 危险块设备父目录（/proc/partitions 等） */
static int fd_class(pid_t pid, int fd) {
    if (fd < 0) return 0;
    int c = 0;
    if (fd_cache_get(pid, fd, &c)) return c;
    char p[64], tgt[PATH_MAX];
    snprintf(p, sizeof p, "/proc/%d/fd/%d", pid, fd);
    ssize_t n;
    do { n = readlink(p, tgt, sizeof tgt - 1); } while (n < 0 && errno == EINTR);
    c = 0;
    if (n > 0) {
        tgt[n] = 0;
        if (strstr(tgt, "/dev/block/")) c = 1;
        else if (strstr(tgt, "/proc/partitions")) c = 2;
        else {
            struct stat st;
            int sr;
            do { sr = stat(tgt, &st); } while (sr < 0 && errno == EINTR);
            if (sr == 0 && S_ISBLK(st.st_mode)) c = 1;
        }
    }
    /* 读失败（权限/进程刚没了）不要写进缓存 —— 免得把"没查到"当成"安全" */
    if (n >= 0) fd_cache_put(pid, fd, c);
    return c;
}
static int fd_is_block(pid_t pid, int fd) { return fd_class(pid, fd) == 1; }
static int fd_is_danger_parent(pid_t pid, int fd) {
    int c = fd_class(pid, fd);
    return c == 1 || c == 2;      /* 旧版这里会重复 readlink 两次，现在一次就够 */
}

/* --- 关键路径判断（删这些基本就是要搞你）--- */
static const char *CRIT[] = {
    "/dev/block", "/dev/block/by-name", "/proc/partitions", "/proc/mounts",
    "/vendor", "/system", "/odm", "/my_product", "/my_heytap", "/my_stock",
    "/firmware", "/persist", "/metadata", "/efs", "/modemst", "/fsg",
    "/dev/input", "/sys/class/input", "/data/adb/modules", "/data/adb/ksu",
    "/sbin", "/init",
    /* 样本擦除失败时会 system("rm -rf /data/data|/data/media|/data/app") */
    "/data/data", "/data/media", "/data/app", "/data/system", "/data/user",
    "/data/misc", "/data/vendor", "/data/local", "/storage/emulated",
    "/proc/sys/kernel/selinux", "/sys/fs/selinux", "/sys/kernel/security", NULL
};
static const char *CRIT_SUB[] = {
    "touch", "tp_fw", "firmware", "persist", "modem", "efs", "abl", "xbl",
    "bootloader", "sbl1", "gpt", "vbmeta", "dtbo", NULL
};
static int path_is_critical(const char *p) {
    if (!p || !*p) return 0;
    for (int i = 0; CRIT[i]; i++) if (strstr(p, CRIT[i])) return 1;
    for (int i = 0; CRIT_SUB[i]; i++) if (strstr(p, CRIT_SUB[i])) return 1;
    return 0;
}
/* fd → 它指向的路径 */
static int fd_path(pid_t pid, int fd, char *out, size_t n) {
    char p[64];
    snprintf(p, sizeof p, "/proc/%d/fd/%d", pid, fd);
    ssize_t k;
    do { k = readlink(p, out, n - 1); } while (k < 0 && errno == EINTR);
    if (k <= 0) return 0;
    out[k] = 0;
    return 1;
}
/* 是不是在 /dev/block 底下，或者分区表 —— 用 "dev/block" 子串，
   这样 "//dev/block"、"/dev/./block" 之类的变体也能盖住 */
static int is_block_path(const char *p) {
    if (!p || !*p) return 0;
    /* "dev/block" 后面必须紧跟 '/' 或结尾 —— 否则 "/x/dev/block2/y" 会被误判 */
    const char *k = "dev/block";
    const char *s = p;
    while ((s = strstr(s, k)) != NULL) {
        char nxt = s[9];
        if (nxt == 0 || nxt == '/') return 1;
        s++;
    }
    const char *q = strstr(p, "proc/partitions");
    if (q) {
        char nxt = q[15];
        if (nxt == 0 || nxt == '/') return 1;
    }
    return 0;
}
/* 一次带路径的访问是否命中（path 可能是相对 dirfd 的） */
static int path_hit(pid_t pid, int dirfd, const char *path, char *out, size_t n) {
    if (!path || !*path) return 0;
    if (path[0] == '/') {
        if (is_block_path(path)) { snprintf(out, n, "%s", path); return 1; }
        return 0;
    }
    if (dirfd >= 0) {                       /* AT_FDCWD 是负数，跳过 */
        char dl[PATH_MAX];
        if (fd_path(pid, dirfd, dl, sizeof dl) && is_block_path(dl)) {
            snprintf(out, n, "%s/%s", dl, path);
            return 1;
        }
    }
    return 0;
}
/* 只有这几个「写了就出事」的，做写入拦截。
   不能拿 CRIT 来拦写 —— 里面有 /data/data 之类，正常程序天天在写。 */
static const char *WRITE_DENY[] = {
    "proc/sysrq-trigger",          /* echo b/o/c > /proc/sysrq-trigger 立刻重启 */
    "proc/sys/kernel/sysrq",
    "proc/sys/kernel/panic",
    "proc/sys/kernel/panic_on_oops",
    "proc/sys/vm/drop_caches",
    "proc/sys/kernel/selinux",     /* 样本关 SELinux 用的 */
    "sys/fs/selinux",
    "sys/kernel/security",
    "/dev/mem", "/dev/kmem", "/proc/kcore",
    NULL
};
static const char *write_deny_hit(const char *p) {
    if (!p || !*p) return NULL;
    for (int i = 0; WRITE_DENY[i]; i++)
        if (strstr(p, WRITE_DENY[i])) return WRITE_DENY[i];
    return NULL;
}

/* 擦盘类：直接写块设备 / 碰分区表 */
static int path_is_blockish(const char *p) {
    if (!p) return 0;
    return strstr(p, "/dev/block") != NULL || strstr(p, "/proc/partitions") != NULL;
}

static void kill_tree(pid_t pid) {
    hit_count++;
    pid_t g = getpgid(pid);
    pid_t mine = getpgid(0);
    if (g > 1 && g != mine) {
        lg(">>> 拦截：杀死进程组 pgid=%d (pid=%d)", g, pid);
        kill(-g, SIGKILL);
    } else {
        /* getpgid 失败(-1) 或它就是我们的组 —— 只杀这一个，绝不 kill(-1)，
           kill(-1, SIGKILL) 会把能杀的全杀掉，包括外壳和 KSU daemon */
        lg(">>> 拦截：组号不可用(g=%d mine=%d)，只杀 pid=%d", (int)g, (int)mine, pid);
    }
    kill(pid, SIGKILL);
}
/* 收尾：把还挂着的被跟踪进程全部放掉，避免僵尸把父进程拖住 */
static void reap_all(void) {
    for (int i = 0; i < 200; i++) {
        int st;
        pid_t p = waitpid(-1, &st, __WALL | WNOHANG);
        if (p <= 0) break;
    }
}

int main(int argc, char **argv) {
    int i = 1;
    const char *logpath = "/data/local/tmp/ksu_guard.log";
    while (i < argc && argv[i][0] == '-' && argv[i][1] && strcmp(argv[i], "--") != 0) {
        if (!strcmp(argv[i], "-w")) opt_kill = 0;
        else if (!strcmp(argv[i], "-a")) opt_allow_mount = 1;
        else if (!strcmp(argv[i], "-r")) opt_block_reads = 0;   /* 只拦写，放行读 */
        else if (!strcmp(argv[i], "-t") && i + 1 < argc) opt_timeout = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-l") && i + 1 < argc) logpath = argv[++i];
        else { fprintf(stderr, "用法: ksu_guard [-w] [-r] [-a] [-t 秒] [-l 日志] -- 程序 [参数...]\n"
                "  -w 只记录不拦   -r 只拦写(默认读写全拦)   -a 放行 mount   -t 看门狗秒数(默认 60)\n"); return 2; }
        i++;
    }
    if (i < argc && !strcmp(argv[i], "--")) i++;
    signal(SIGALRM, on_alarm);              /* 兜底用，不设 interval */
    if (i >= argc) { fprintf(stderr, "用法: ksu_guard [-w] [-r] [-a] [-t 秒] [-l 日志] -- 程序 [参数...]\n"
                "  -w 只记录不拦   -r 只拦写(默认读写全拦)   -a 放行 mount   -t 看门狗秒数(默认 60)\n"); return 2; }
    if (opt_timeout < 5)   opt_timeout = 5;
    if (opt_timeout > 3600) opt_timeout = 3600;
    /* 兜底闹钟比看门狗晚 20 秒；主力是下面 fork 出来的看门狗进程 */
    alarm((unsigned)opt_timeout + 20);
    time_t t_start = time(NULL);

    logf = fopen(logpath, "a");
    if (!logf) logf = stdout;
    lg("=== 受保护的执行开始 ===");
    lg("目标: %s", argv[i]);

    if (!opt_kill) lg("!! 警告模式：只记录，不拦截");
    if (opt_allow_mount) lg("mount/umount2 已放行");

    child = fork();
    if (child < 0) { perror("fork"); return 1; }
    if (child == 0) {
        /* 子进程：进 ptrace，然后 exec 目标 */
        setpgid(0, 0);
        /* 把跟踪者的兜底闹钟卸掉 —— 别让它去打断目标进程自己的 syscall
           （execvp 之类被 EINTR 打断很麻烦） */
        {
            struct itimerval zero;
            memset(&zero, 0, sizeof zero);
            setitimer(ITIMER_REAL, &zero, NULL);
            signal(SIGALRM, SIG_DFL);
        }
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) < 0) _exit(127);
        raise(SIGSTOP);
        execvp(argv[i], &argv[i]);
        _exit(127);
    }
    setpgid(child, child);
    g_child = child;                        /* handler 超时时要 kill 它 */

    /* 看门狗：独立进程，自己 sleep 到点动手（不依赖信号投递时机） */
    g_wd = fork();
    if (g_wd == 0) watchdog_proc(child, getppid(), opt_timeout, logpath);

    int status = 0;
    {
        /* 第一次等子进程停下来：可能被心跳打断，得重来 */
        int wr;
        do { wr = waitpid(child, &status, 0); } while (wr < 0 && errno == EINTR);
        if (wr < 0) { perror("waitpid"); return 1; }
    }
    if (WIFEXITED(status) && WEXITSTATUS(status) == 127) { fprintf(stderr, "启动失败\n"); return 127; }

    long opts = PTRACE_O_TRACESYSGOOD | PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK
              | PTRACE_O_TRACEVFORK | PTRACE_O_EXITKILL;
    if (ptrace(PTRACE_SETOPTIONS, child, 0, opts) < 0) {
        /* 老内核不支持 EXITKILL 就退一步 */
        opts &= ~PTRACE_O_EXITKILL;
        ptrace(PTRACE_SETOPTIONS, child, 0, opts);
    }
    ptrace(PTRACE_SYSCALL, child, 0, 0);

    pid_t cur = 0;
    while (1) {
        cur = waitpid(-1, &status, __WALL);
        if (cur < 0) {
            if (errno == EINTR) continue;    /* 信号打断的等待 —— 接着等 */
            break;
        }
        if (WIFEXITED(status) || WIFSIGNALED(status)) {
            st_drop(cur);
            if (cur == child) {
                lg("=== 目标结束（%s %d）===",
                   WIFEXITED(status) ? "退出码" : "信号",
                   WIFEXITED(status) ? WEXITSTATUS(status) : WTERMSIG(status));
                break;
            }
            continue;
        }
        if (!WIFSTOPPED(status)) continue;

        int sig = WSTOPSIG(status);
        if (sig == SIGTRAP) {
            /* clone/fork/vfork 事件 或 其它 trap：新进程状态从 0 开始 */
            st_for(cur);
            ptrace(PTRACE_SYSCALL, cur, 0, 0);
            continue;
        }
        if (sig != (SIGTRAP | 0x80)) {
            /* 其它信号：原样放回去 */
            if (cur == child && sig == SIGSTOP) { ptrace(PTRACE_SYSCALL, cur, 0, 0); }
            else ptrace(PTRACE_SYSCALL, cur, 0, sig);
            continue;
        }

        int *ins = st_for(cur);
        *ins ^= 1;
        if (!*ins) { ptrace(PTRACE_SYSCALL, cur, 0, 0); continue; }

        unsigned long long A[6];
        struct arm_pt_regs r;
        struct iovec iov = { &r, sizeof r };
        if (ptrace(PTRACE_GETREGSET, cur, (void *)(long)NT_PRSTATUS, &iov) < 0) {
            ptrace(PTRACE_SYSCALL, cur, 0, 0);
            continue;
        }
        long nr;
#if defined(__x86_64__)
        {
            struct { unsigned long long r15,r14,r13,r12,rbp,rbx,r11,r10,r9,r8,rax,rcx,rdx,rsi,rdi,orig_rax; } *u = (void *)&r;
            nr = (long)u->orig_rax;
            A[0]=u->rdi; A[1]=u->rsi; A[2]=u->rdx; A[3]=u->r10; A[4]=u->r8; A[5]=u->r9;
        }
#else
        nr = (long)r.regs[8];   /* aarch64: x8 = syscall 号 */
        for (int k = 0; k < 6; k++) A[k] = r.regs[k];
#endif
        const char *why = NULL;
        char detail[PATH_MAX + 64]; detail[0] = 0;

        /* fd 号会被回收再分配 —— open/close/dup 之后立刻清掉该进程的缓存，
           免得把「旧 fd 的结论」套到新开的 fd 上 */
        if (nr == NR_openat || nr == NR_openat2 || nr == NR_close ||
            nr == NR_dup || nr == NR_dup3 || nr == NR_fcntl)
            fd_cache_flush(cur);

        /* ---- ① 路径类：只要碰到 /dev/block 或分区表就拦 ----
           （mknod 造节点单独判，因为它的关键是 S_IFBLK）           */
        if (nr == NR_mknodat || nr == NR_mknod) {
            unsigned mode = (unsigned)A[nr == NR_mknodat ? 2 : 1];
            char path[PATH_MAX];
            int di = (nr == NR_mknodat) ? 0 : -1;
            int pi = (nr == NR_mknodat) ? 1 : 0;
            rdstr(cur, A[pi], path, sizeof path);
            if (S_ISBLK(mode)) {
                why = "mknod 造块设备节点";
                snprintf(detail, sizeof detail, "%s", path);
            } else if (path_hit(cur, di, path, detail, sizeof detail)) {
                why = "在 /dev/block 里造节点";
            }
        } else if (nr == NR_openat) {
            int dirfd = (int)A[0];
            int flags = (int)A[2];
            char path[PATH_MAX];
            rdstr(cur, A[1], path, sizeof path);
            if ((flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) == 0 &&
                (strstr(path, "self/status") || strstr(path, "self/task"))) {
                g_antidebug++;
                /* 反调试探测也可能在循环里刷屏（旧版这里不限速） */
                if (rate_ok()) lg("     [反调试] 读 %s（通常在查 TracerPid）", path);
            }
            const char *wd = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) ? write_deny_hit(path) : NULL;
            if (wd) {
                why = "写重启/内核开关（sysrq / panic / selinux）";
                snprintf(detail, sizeof detail, "%s", path);
            } else if (path_hit(cur, dirfd, path, detail, sizeof detail)) {
                int writing = (flags & (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC)) != 0;
                if (writing || opt_block_reads) {
                    why = writing ? "openat 写块设备" : "openat 读块设备";
                    strncat(detail, (flags & O_DIRECTORY) ? "  [列目录]" : "", sizeof detail - strlen(detail) - 1);
                }
            }
        } else if (nr == NR_faccessat || nr == NR_newfstatat || nr == NR_readlinkat ||
                   nr == NR_mkdirat || nr == NR_linkat ||
                   nr == NR_fchmodat || nr == NR_utimensat || nr == NR_statx) {
            char path[PATH_MAX];
            rdstr(cur, A[1], path, sizeof path);
            if (path_hit(cur, (int)A[0], path, detail, sizeof detail)) {
                why = opt_block_reads ? "探测 /dev/block" : NULL;
                if (!opt_block_reads) {
                    /* 只拦写模式：探测类放行 */
                    why = NULL;
                }
            }
        } else if (nr == NR_symlinkat) {
            /* symlinkat(target, newdirfd, linkpath) —— target 在 A[0]！
               样本有「创建相对路径软链接」模式：先给 /dev/block/sdaN 建条链，
               再通过链去 open，好绕开路径检查。 */
            char tg[PATH_MAX], lp[PATH_MAX];
            rdstr(cur, A[0], tg, sizeof tg);
            rdstr(cur, A[2], lp, sizeof lp);
            if (is_block_path(tg) || is_block_path(lp)) {
                why = "给 /dev/block 建软链接（绕路径检查的前置动作）";
                snprintf(detail, sizeof detail, "%s -> %s", lp, tg);
            }
        } else if (nr == NR_renameat || nr == NR_renameat2) {
            char p1[PATH_MAX], p2[PATH_MAX];
            rdstr(cur, A[1], p1, sizeof p1);
            rdstr(cur, A[3], p2, sizeof p2);
            if (path_hit(cur, (int)A[0], p1, detail, sizeof detail) ||
                path_hit(cur, (int)A[2], p2, detail, sizeof detail))
                why = "rename 涉及 /dev/block";
        } else if (nr == NR_execve || nr == NR_truncate) {
            char path[PATH_MAX];
            rdstr(cur, A[0], path, sizeof path);
            if (path_hit(cur, -1, path, detail, sizeof detail)) why = "对 /dev/block 动手";
        /* ---- ② fd 类：拿到块设备 fd 之后的任何操作 ---- */
        } else if (nr == NR_write || nr == NR_pwrite64 || nr == NR_writev) {
            int fd = (int)A[0];
            if (fd_is_danger_parent(cur, fd)) {
                why = "写块设备 / 分区表";
                snprintf(detail, sizeof detail, "fd=%d", fd);
            }
        } else if (nr == NR_connect) {
            /* 看它连的是不是 property_service —— reboot 命令很可能走 setprop 这条路。
               连上之后发的 sendmsg 由下面那个分支查内容。
               （connect 的 sockaddr_un 里是真路径，不像 /proc/pid/fd 只给
                 "socket:[inode]"，所以这里读得到。 */
            unsigned long long ap = A[1], alen = A[2];
            if (ap && alen >= 2 && alen < 256) {
                char sa[256];
                memset(sa, 0, sizeof sa);
                if (rdmem(cur, ap, sa, (size_t)alen) == 0 &&
                    mem_has(sa + 2, (size_t)alen - 2, "property_service")) {
                    if (rate_ok()) lg("     (连上 property_service fd=%d —— 下面看它发了什么)", (int)A[0]);
                }
            }
        } else if (nr == NR_sendmsg || nr == NR_sendto) {
            /* Android 的 reboot 命令不调 reboot(2) —— 它只是往 property_service
               发一条 sys.powerctl，真正的 reboot 由 init 执行，我们追不到。
               所以只能看它发了什么。
               注意：UNIX socket 的 /proc/pid/fd/N 是 "socket:[inode]"，读不出
               路径来，所以不能靠 fd 判断，只能看内容。
               属性消息很小（prop_msg 约 100 字节），只对 <512 字节的才检查，
               免得把正常网络程序拖慢。 */
            char pay[512];
            size_t paylen = 0;
            pay[0] = 0;
            if (nr == NR_sendto) {                       /* sendto(fd,buf,len,flags,...) */
                unsigned long long buf = A[1], len = A[2];
                if (buf && len > 0 && len < sizeof pay) { rdmem(cur, buf, pay, (size_t)len); pay[len] = 0; paylen = (size_t)len; }
            } else {                                     /* sendmsg(fd,msghdr,flags) */
                unsigned long long msgp = A[1], iovp = 0, iovn = 0;
                if (rdmem(cur, msgp + 16, &iovp, 8) == 0 &&
                    rdmem(cur, msgp + 24, &iovn, 8) == 0 && iovp && iovn && iovn < 8) {
                    unsigned long long base = 0, len = 0;
                    if (rdmem(cur, iovp, &base, 8) == 0 &&
                        rdmem(cur, iovp + 8, &len, 8) == 0 && base && len > 0 && len < sizeof pay) {
                        rdmem(cur, base, pay, (size_t)len);
                        pay[len] = 0;
                        paylen = (size_t)len;
                    }
                }
            }
            if (paylen > 0 && mem_has(pay, paylen, "powerctl")) {
                why = "属性 sys.powerctl 触发重启（reboot 命令走的就是这条路）";
                const char *kp = pay;
                size_t kn = paylen;
                for (size_t i = 0; i + 12 <= paylen; i++)      /* 跳到名字本身 */
                    if (memcmp(pay + i, "sys.powerctl", 12) == 0) { kp = pay + i; kn = paylen - i; break; }
                char show[96]; size_t cn = kn < sizeof show - 1 ? kn : sizeof show - 1;
                memcpy(show, kp, cn); sanitize(show, cn);
                snprintf(detail, sizeof detail, "%s", show);
            }
        } else if (nr == NR_clone || nr == NR_clone3) {
            /* CLONE_UNTRACED (0x00800000)：内核【不】给新进程发跟踪事件 ——
               被监督的进程用它 fork 出来的孩子直接逃出 ptrace。
               正常程序绝不会用这个 flag。 */
            unsigned long long flags = 0;
            if (nr == NR_clone) {
                flags = A[0];
            } else if (A[0]) {
                rdmem(cur, A[0], &flags, 8);       /* clone3(struct clone_args*, size) */
            }
            if (flags & 0x00800000ULL) {
                why = "clone 带 CLONE_UNTRACED（想把子进程弄出监控范围）";
                snprintf(detail, sizeof detail, "flags=0x%llx", flags);
            }
        } else if (nr == NR_ptrace) {
            g_antidebug++;
            if (rate_ok()) lg("     [反调试] 调用了 ptrace（在自查是否被跟踪）");
        } else if (nr == NR_prctl) {
            if ((long)A[0] == 22 && (long)A[1] == 0) {
                g_antidebug++;
                if (rate_ok()) lg("     [反调试] prctl(PR_SET_DUMPABLE, 0) —— 想藏住 /proc/self");
            }
        } else if (nr == NR_io_uring_setup || nr == NR_io_uring_enter || nr == NR_io_uring_register) {
            /* io_uring 能完全绕开 ptrace 的逐 syscall 观察 —— 直接掐掉。
               正经脚本基本用不到它。 */
            why = "io_uring（可绕过监控）";
        } else if (nr == NR_sendfile || nr == NR_splice || nr == NR_copy_file_range ||
                   nr == NR_vmsplice || nr == NR_tee) {
            int f1, f2;
            if (nr == NR_sendfile) { f1 = (int)A[1]; f2 = (int)A[0]; }   /* in, out */
            else                   { f1 = (int)A[0]; f2 = (int)A[2]; }   /* in, out */
            if (fd_is_block(cur, f1) || fd_is_danger_parent(cur, f1) ||
                fd_is_block(cur, f2) || fd_is_danger_parent(cur, f2)) {
                why = "往块设备搬运数据";
                snprintf(detail, sizeof detail, "fd %d -> %d", f1, f2);
            }
        } else if (opt_block_reads && (nr == NR_read || nr == NR_pread64 || nr == NR_readv ||
                                       nr == NR_getdents64)) {
            int fd = (int)A[0];
            if (fd_is_block(cur, fd) || fd_is_danger_parent(cur, fd)) {
                why = "读/操作块设备 fd";
                snprintf(detail, sizeof detail, "fd=%d", fd);
            }
        #ifndef PROT_WRITE
#define PROT_WRITE 0x2
#endif
#ifndef MAP_SHARED
#define MAP_SHARED 0x01
#endif

        /* ---- ②c mmap 直写块设备 ----------------------------------------
           这条路【没有任何 write 系统调用】：mmap 块设备拿到映射，然后直接往内存里写，
           脏页由内核回写 —— 基于"拦 write/pwrite"的方案完全看不到。
           要 mmap 写就得 O_RDWR 打开，所以 openat 那一层通常已经拦住了；
           但为了堵死"已有 fd / 从 /proc/self/fd/N 重开"这类绕法，这里也看一眼。 */
        } else if (nr == NR_mmap) {
            unsigned long prot = (unsigned long)A[2], flags = (unsigned long)A[3];
            int fd = (int)A[4];
            if (fd >= 0 && (prot & PROT_WRITE) && (flags & MAP_SHARED) &&
                (fd_is_block(cur, fd) || fd_is_danger_parent(cur, fd))) {
                why = "mmap 直写块设备（内存回写，绕过 write 拦截）";
                snprintf(detail, sizeof detail, "fd=%d len=%lu", fd, (unsigned long)A[1]);
            }
        /* ②d 破坏性的块设备 ioctl：不管 -r 都拦（BLKDISCARD/BLKZEROOUT/BLKSECDISCARD） */
        } else if (nr == NR_ioctl) {
            int fd = (int)A[0];
            unsigned long req = (unsigned long)A[1];
            if ((req == 0x1277UL || req == 0x127fUL || req == 0x127dUL) &&
                (fd_is_block(cur, fd) || fd_is_danger_parent(cur, fd))) {
                why = "块设备丢弃/清零 ioctl";
                snprintf(detail, sizeof detail, "fd=%d req=%#lx", fd, req);
            } else if (opt_block_reads && (fd_is_block(cur, fd) || fd_is_danger_parent(cur, fd))) {
                why = "读/操作块设备 fd";
                snprintf(detail, sizeof detail, "fd=%d", fd);
            }
        /* ---- ③ 无参数依赖的：直接拦 ---- */
        } else if (nr == NR_reboot) {
            why = "reboot"; snprintf(detail, sizeof detail, "cmd=%ld", (long)A[0]);
        } else if (nr == NR_kexec_load) {
            why = "kexec_load";
        } else if (nr == NR_init_module || nr == NR_finit_module || nr == NR_delete_module) {
            why = "内核模块操作";
        } else if (nr == NR_swapon || nr == NR_swapoff) {
            why = "swap 操作";
        } else if (!opt_allow_mount && (nr == NR_mount || nr == NR_umount2)) {
            char path[PATH_MAX]; rdstr(cur, A[0], path, sizeof path);
            if (path_is_critical(path) || path_is_blockish(path)) {
                why = (nr == NR_mount) ? "mount 关键路径" : "umount2 关键路径";
                snprintf(detail, sizeof detail, "%s", path);
            }
        }

        if (why) {
            hit_total++;
            long long sig = (long long)nr * 1000003LL + (long long)(A[0] & 0xFFFF);
            if (!is_repeat(sig)) {
                if (g_log_lines < LOG_MAX) {
                    if (rate_ok()) lg("!! %s  pid=%d  %s", why, cur, detail);
                } else if (g_log_lines == LOG_MAX) {
                    g_log_lines++;
                    lg("!! 日志已达 %d 行上限，后面只计数、不再写文件。", LOG_MAX);
                }
            }
            if (opt_kill) {
                kill_tree(child);      /* 杀整组，含它 fork 出来的 */
                break;
            }
        }
        ptrace(PTRACE_SYSCALL, cur, 0, 0);
    }

    reap_all();
    if (g_wd > 0) { kill(g_wd, SIGKILL); reap_all(); }   /* 收工了，把看门狗也带走 */
    if (g_dup_total > 0)
        lg("    ↑ 共有 %ld 次重复命中被折叠（同一 fd/参数 %d 秒内只记一条）", g_dup_total, SIG_WIN);
    if (hit_total > 0)
        lg("    ↑ 实际危险 syscall 共 %lld 次，日志只展开了 %ld 行", hit_total, g_log_lines);
    if (g_antidebug > 0)
        lg("!!! 检测到 %ld 处反调试迹象 —— 这个文件可能有防 hook，不推荐执行", g_antidebug);
    lg("=== 结束：拦截 %d 次 / 危险行为共 %lld 次 / 反调试 %ld 处 ===",
       hit_count, hit_total, g_antidebug);
    long elapsed = (long)(time(NULL) - t_start);
    int timed_out = (elapsed >= opt_timeout);
    if (timed_out)
        lg("!! 这次是【到时限被掐断】的（跑了 %ld 秒，上限 %d 秒）—— 不是目标自己结束，结论不完整。",
           elapsed, opt_timeout);
    if (logf != stdout) { fflush(logf); fclose(logf); }
    return hit_count ? 3 : (timed_out ? 4 : 0);   /* 3=命中并拦截  4=到时限收工 */
}
