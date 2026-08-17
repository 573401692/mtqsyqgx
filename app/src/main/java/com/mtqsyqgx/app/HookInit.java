package com.mtqsyqgx.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface;

public class HookInit extends XposedModule {

    // ★ 配置文件 /data/local/tmp/ — world-readable，目标App进程可读
    private static final String CONFIG_PATH = "/data/local/tmp/mt_config.json";
    // ★ 采集器订单共享文件（悬浮窗进程轮询读取展示）
    private static final String ORDERS_PATH = "/data/local/tmp/mt_orders.json";
    // ★ 抢单成功信号（悬浮窗轮询显示"抢到哪个订单"）
    private static final String GRAB_SIGNAL_PATH = "/data/local/tmp/mt_grab_signal.json";
    // ★ 订单数据保留时长：只保留最近30分钟
    private static final long ORDER_KEEP_MS = 30L * 60L * 1000L;
    // ★ 模块状态标记
    private static final String STATUS_PATH = "/data/local/tmp/mt_module_status.txt";
    // ★ 所有已注册的定位监听者（线程安全）
    private final CopyOnWriteArrayList<Object> allLocationListeners = new CopyOnWriteArrayList<>();
    // ★ 上次广播的坐标，用于变更检测（NaN表示未广播过）
    private double lastBroadcastLat = Double.NaN;
    private double lastBroadcastLng = Double.NaN;
    // ★ 用于在构造函数中初始化时创建 Location 所需
    private Object spoofLoc = null;
    // ★ 美团App的ClassLoader，用于反射创建Location对象（广播需要）
    private ClassLoader KClassLoader = null;
    // 日志写入当前进程的专用缓存目录（不依赖 su）
    private static final String LOG_PREFIX = "hook_log";
    private static final int MAX_LOG_FILES = 10;
    private static final int KEEP_DAYS = 3;
    private static final int SU_TIMEOUT_MS = 3000; // su命令超时：3秒
    private static File LOG_DIR;
    private static File currentLogFile;
    private long lastLogCleanTs = 0; // 日志自动清理时间戳（每小时一次）
    private static JSONObject configCache;
    private static String configLastRaw;
    private boolean isMeituan;
    private static volatile boolean hooked;
    private static volatile int grabCount = 0; // 当前已抢单数
    private static volatile long lastGrabTime = 0; // 上次抢单时间（间隔控制）
    private static final java.util.Set<String> grabbedIds = new java.util.HashSet<>(); // 已抢订单ID去重
    private static volatile boolean lastAutoGrabEnabled = false; // 自动抢单开关上一状态（关→开时重置本轮计数）
    private static volatile boolean continuousSkipLogged = false; // 连抢关闭提示是否已记录（每轮仅提示一次）
    // ── 延迟算法优化：复用随机源 + 防连续相同延迟（避免机器特征）──
    private final java.util.Random delayRand = new java.util.Random();
    private volatile int lastDelay = -1;
    private static String moduleCacheDir = null; // 缓存进程的cache目录路径
    private static String currentPackageName = ""; // 当前进程的主包名（用于构造 data 目录回退）
    // ── 骑手定位（顺路度计算用，一次获取缓存）──
    private static volatile double riderLat = 0.0;
    private static volatile double riderLng = 0.0;
    private static volatile boolean riderGot = false;
    // ── 新任务数据 v15：骑手已接订单（路线顺路参考） + 转单识别 ──
    private final java.util.List<Object> riderOrders = new java.util.ArrayList<>();
    private volatile boolean riderRouteGot = false;
    private final java.util.Map<String, Boolean> transferOrderIds = new java.util.HashMap<>();
    // ── v15 增强：订单类型识别 + 持久化订单文件 + 防抖单行输出 ──
    private final java.util.Map<String, Object> wbCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, String> labelCache = new java.util.HashMap<>();
    private final java.util.Map<String, String> labelLastText = new java.util.HashMap<>();
    private final java.util.Map<String, String> btnTypeCache = new java.util.HashMap<>();
    private final java.util.Map<String, String> btnLastText = new java.util.HashMap<>();
    private final java.util.Map<String, String> reLoggedTypes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Long> emitPending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean emitTimerRunning = false;
    // ★ 卡顿修复：订单 emit 流程（含 su 子进程 + 文件 I/O + binder 推送）移出主线程，
    //   改用专用后台线程，避免阻塞美团主线程导致 Input dispatching 超时(ANR)/卡顿
    private final android.os.HandlerThread emitThread = new android.os.HandlerThread("mtq-emit");
    private final android.os.Handler emitHandler;
    {
        emitThread.start();
        emitHandler = new android.os.Handler(emitThread.getLooper());
    }
    private static final String ORDERS_LOG_PATH = "/sdcard/Android/media/com.sankuai.meituan.dispatch.crowdsource/LSPilot/Plugin/GetNewTaskData/orders.log";

    public HookInit() { super(); }

    /** 获取当前进程的缓存目录路径（不依赖 su） */
    private static String getCacheDirPath() {
        if (moduleCacheDir != null) return moduleCacheDir;
        // 方式1: ActivityThread.getApplication().getCacheDir()
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null);
            android.app.Application app = (android.app.Application) at.getClass()
                    .getMethod("getApplication").invoke(at);
            if (app != null && app.getCacheDir() != null) {
                moduleCacheDir = app.getCacheDir().getAbsolutePath();
                Log.e("HookInit", "getCacheDir: ActivityThread → " + moduleCacheDir);
                return moduleCacheDir;
            }
        } catch (Throwable t) { Log.e("HookInit", "getCacheDir ActivityThread fail: " + t.getMessage()); }
        // 方式2: 从 mInitialApplication 获取
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = at.getClass().getDeclaredField("mInitialApplication");
            f.setAccessible(true);
            android.app.Application app = (android.app.Application) f.get(at);
            if (app != null && app.getCacheDir() != null) {
                moduleCacheDir = app.getCacheDir().getAbsolutePath();
                Log.e("HookInit", "getCacheDir: mInitialApplication → " + moduleCacheDir);
                return moduleCacheDir;
            }
        } catch (Throwable t) { Log.e("HookInit", "getCacheDir mInitialApplication fail: " + t.getMessage()); }
        // 方式3: 用包名构造 data 目录（进程自己的目录，一定有写权限）
        if (currentPackageName != null && !currentPackageName.isEmpty()) {
            for (String base : new String[]{
                    "/data/user/0/" + currentPackageName + "/cache",
                    "/data/data/" + currentPackageName + "/cache"
            }) {
                File f = new File(base);
                if (f.exists()) {
                    moduleCacheDir = base;
                    Log.e("HookInit", "getCacheDir: data → " + moduleCacheDir + " exists=" + f.exists() + " writable=" + f.canWrite());
                    return moduleCacheDir;
                }
                // 尝试创建
                if (f.mkdirs() && f.canWrite()) {
                    moduleCacheDir = base;
                    Log.e("HookInit", "getCacheDir: created → " + moduleCacheDir);
                    return moduleCacheDir;
                }
            }
        }
        // 方式4: 最后回退到 /data/local/tmp/ (某些进程可能无写权限)
        moduleCacheDir = "/data/local/tmp";
        Log.e("HookInit", "getCacheDir: fallback → " + moduleCacheDir);
        return moduleCacheDir;
    }

    /** 获取临时文件目录（即进程缓存目录） */
    private static File getTmpDir() {
        File cache = new File(getCacheDirPath());
        cache.mkdirs();
        return cache;
    }

    private void ensureLogPath() {
        if (LOG_DIR != null) return;
        File logRoot = null;
        // 方式1：进程缓存目录（最佳）
        try {
            String cachePath = getCacheDirPath();
            logRoot = new File(cachePath, "lucky_log");
            if (!logRoot.exists() && !logRoot.mkdirs()) { logRoot = null; }
            else if (!logRoot.canWrite()) { logRoot = null; }
        } catch (Throwable t) { Log.e("HookInit", "[ensureLogPath] cache fail: " + t.getMessage()); logRoot = null; }
        // 方式2：/data/local/tmp/mt_logs/（仅当目录可创建时使用）
        if (logRoot == null) {
            logRoot = new File("/data/local/tmp/mt_logs");
            if (!logRoot.exists() && !logRoot.mkdirs()) {
                Log.e("HookInit", "[ensureLogPath] /data/local/tmp/mt_logs mkdirs failed (SELinux?)");
                logRoot = null;
            } else if (!logRoot.canWrite()) {
                Log.e("HookInit", "[ensureLogPath] /data/local/tmp/mt_logs not writable");
                logRoot = null;
            }
        }
        // 方式3：最后尝试 /sdcard/Android/data/<pkg>/cache/lucky_log
        if (logRoot == null && currentPackageName != null && !currentPackageName.isEmpty()) {
            for (String sdcardBase : new String[]{
                    "/sdcard/Android/data/" + currentPackageName + "/cache/lucky_log",
                    "/storage/emulated/0/Android/data/" + currentPackageName + "/cache/lucky_log"
            }) {
                File f = new File(sdcardBase);
                if ((f.exists() && f.canWrite()) || f.mkdirs()) {
                    logRoot = f;
                    Log.e("HookInit", "[ensureLogPath] sdcard → " + logRoot.getAbsolutePath());
                    break;
                }
            }
        }
        if (logRoot == null) {
            // 完全无法创建日志目录，禁用文件日志
            Log.e("HookInit", "[ensureLogPath] ALL FAILED - file logging disabled");
            LOG_DIR = new File("."); // 占位，避免重复调用
            currentLogFile = null;
            return;
        }
        LOG_DIR = logRoot;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        currentLogFile = new File(LOG_DIR, LOG_PREFIX + "_" + ts + ".txt");
        cleanOldLogs();
        Log.e("HookInit", "[ensureLogPath] logDir=" + LOG_DIR.getAbsolutePath() + " exists=" + LOG_DIR.exists() + " writable=" + LOG_DIR.canWrite());
    }

    private void cleanOldLogs() {
        if (LOG_DIR == null) return;
        File[] files = LOG_DIR.listFiles();
        if (files == null || files.length == 0) return;
        long cutoff = System.currentTimeMillis() - (long) KEEP_DAYS * 24 * 60 * 60 * 1000;
        // 按修改时间降序排序（最新的在前）
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        int count = 0;
        for (File f : files) {
            count++;
            // 超过保留数量或超过保留天数的文件删除
            if (f.lastModified() < cutoff || count > MAX_LOG_FILES) {
                f.delete();
            }
        }
    }

    private void L(String s) {
        Log.e("HookInit", s);
        try {
            ensureLogPath();
            if (currentLogFile == null) return;
            // ★ 自动清理：每小时检查一次，只保留最近 KEEP_DAYS 天日志
            long now = System.currentTimeMillis();
            if (now - lastLogCleanTs > 3600000L) {
                lastLogCleanTs = now;
                cleanOldLogs();
            }
            String line = "[" + new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                    + "][" + android.os.Process.myPid() + "] " + s + "\n";
            // ★ 卡顿修复：日志落盘改为后台异步写，避免在美团主线程做同步 FUSE 文件 I/O
            final File target = currentLogFile;
            SD_LOG_EXEC.execute(() -> {
                try {
                    FileWriter fw = new FileWriter(target, true);
                    fw.write(line);
                    fw.close();
                } catch (Throwable t) {
                    Log.e("HookInit", "[LOG FAIL] " + t.getMessage());
                }
            });
        } catch (Throwable t) {
            Log.e("HookInit", "[LOG FAIL] " + t.getMessage());
            // 写入失败可能是目录未创建，尝试重新初始化
            currentLogFile = null;
            java.io.File oldLogDir = LOG_DIR;
            LOG_DIR = null;
            try { ensureLogPath(); } catch (Throwable t2) { LOG_DIR = oldLogDir; }
        }
        // ★ 镜像一份到 /sdcard/Download/lucky/log/hook.log（异步后台写入，不阻塞主线程）
        appendSdLog("hook.log", s);
    }

    /** 通过 su -c 写入文件（su异常时静默忽略，调用方需自己处理fallback） */
    private static boolean writeViaSu(String path, String content) {
        try {
            File tmp = new File(getTmpDir(), "su_write_" + System.nanoTime() + ".tmp");
            FileWriter fw = new FileWriter(tmp);
            fw.write(content);
            fw.close();
            boolean ok = execCommand(new String[]{"su", "-c", "cp " + tmp.getAbsolutePath() + " " + path});
            tmp.delete();
            // ★ 确保目标App进程可读（su cp 创建的文件 owner 是 root:root:600）
            if (ok) {
                try { execCommand(new String[]{"su", "-c", "chmod 666 " + path}); } catch (Throwable ignored) {}
            }
            return ok;
        } catch (Throwable t) { return false; }
    }

    /** 推送模块心跳（多通道，免su为主，供主页面检测 LSPosed 是否加载本模块） */
    private static void pushModuleHeartbeat(String proc) {
        long now = System.currentTimeMillis();
        String status = "loaded|" + proc + "|" + now;
        // 通道1：su → /data/local/tmp（需美团进程已授权 su）
        // ★ 卡顿修复：su 子进程放后台线程执行，避免 30s 心跳在主线程阻塞（su 慢时可阻塞数秒）
        final String st = status;
        try { SD_LOG_EXEC.execute(() -> { try { writeViaSu(STATUS_PATH, st); } catch (Throwable ignored) {} }); } catch (Throwable ignored) {}
        // 通道2：ContentResolver → 模块App OrdersProvider（免su，完整版）
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null);
            android.app.Application app = (android.app.Application)
                    at.getClass().getMethod("getApplication").invoke(at);
            if (app != null) {
                android.os.Bundle extras = new android.os.Bundle();
                extras.putString("proc", proc);
                extras.putLong("time", now);
                app.getContentResolver().call(
                        android.net.Uri.parse("content://com.mtqsyqgx.app.orders"),
                        "heartbeat", null, extras);
            }
        } catch (Throwable ignored) {}
        // 通道3：sdcard（美团进程有存储权限时可写，模块App直接读）
        try {
            java.io.File sf = new java.io.File("/sdcard/Download/lucky/mt_module_status.txt");
            java.io.File parent = sf.getParentFile();
            if (parent != null) parent.mkdirs();
            java.io.FileWriter fw = new java.io.FileWriter(sf, false);
            fw.write(status);
            fw.close();
        } catch (Throwable ignored) {}
    }

    /** 通过 su 创建目录 */
    private static void mkdirViaSu(String path) {
        try { execCommand(new String[]{"su", "-c", "mkdir -p " + path}); } catch (Throwable ignored) {}
    }

    private void Le(String s, Throwable t) { L(s + " | " + (t != null ? t.getMessage() : "null")); }

    /** 功能生效状态记录（doHook 结束时统一汇总输出） */
    private static final java.util.LinkedHashMap<String, String> FEATURE_STATE = new java.util.LinkedHashMap<>();
    private void logFeature(String name, boolean ok, String detail) {
        String st = (ok ? "✓生效" : "✗失败") + (detail == null || detail.isEmpty() ? "" : " | " + detail);
        FEATURE_STATE.put(name, st);
        L("[功能] " + name + " → " + st);
        logFeatureCall("[功能] " + name + " → " + st);
    }

    // ═══════════ 分类日志（/sdcard/Download/lucky/log/）═══════════
    // hook.log      → hook 钩子安装/生效/出错
    // grab.log      → 抢单日志（启动/连抢/成功/失败/信号）
    // 采集_XX.txt   → 数据采集日志（按每次刷新保存，每文件最多20次刷新，最多保留10个文件）
    // feature.log   → 功能调用/开关变动日志
    // ★ 全部写入走单线程后台队列，避免在美团主线程做 FUSE sdcard I/O（防卡顿/ANR/蓝屏）
    private static final Object SD_LOG_LOCK = new Object();
    /** append 失败时的内存尾部缓存（fileName → 最近日志），截断写兜底 */
    private static final java.util.Map<String, StringBuilder> SD_TAIL = new java.util.HashMap<>();
    private static final java.util.concurrent.ExecutorService SD_LOG_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sd-log-writer");
                t.setDaemon(true);
                return t;
            });
    private static void appendSdLog(String fileName, String line) {
        final String full = "[" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + "][pid=" + android.os.Process.myPid() + "] " + line + "\n";
        try {
            SD_LOG_EXEC.execute(() -> {
                synchronized (SD_LOG_LOCK) {
                    // ★ 双写：sdcard（尽力，权限允许则写）+ 美团进程缓存（必然可写，导出时 su cp 读取）
                    writeLogFileTo("/sdcard/Download/lucky/log", fileName, full);
                    try { writeLogFileTo(getCacheDirPath() + "/lucky_log", fileName, full); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /** 写一行日志到指定目录（含 300KB 保留尾部 + append 失败截断兜底） */
    private static void writeLogFileTo(String dirPath, String fileName, String full) {
        try {
            java.io.File dir = new java.io.File(dirPath);
            if (dir.exists() || dir.mkdirs()) {
                java.io.File f = new java.io.File(dir, fileName);
                // 防占用过大：单文件超过300KB 时只保留最近100KB（不再产生 .old 残留）
                if (f.exists() && f.length() > 300L * 1024L) {
                    try {
                        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw");
                        long len = raf.length();
                        long keep = Math.min(len, 100L * 1024L);
                        raf.seek(len - keep);
                        byte[] tail = new byte[(int) keep];
                        raf.readFully(tail);
                        raf.setLength(0);
                        raf.seek(0);
                        raf.write(tail);
                        raf.close();
                    } catch (Throwable ig) {}
                    SD_TAIL.remove(fileName);
                }
                try {
                    FileWriter fw = new FileWriter(f, true);
                    fw.write(full);
                    fw.close();
                    SD_TAIL.remove(fileName);
                } catch (Throwable t) {
                    // ★ append 不可写（FUSE 跨会话权限/scoped storage）→ 用内存尾部缓存 + 截断写，保证最新日志不丢
                    try {
                        StringBuilder sb = SD_TAIL.get(fileName);
                        if (sb == null) { sb = new StringBuilder(); SD_TAIL.put(fileName, sb); }
                        if (sb.length() > 400000) sb.setLength(0);
                        sb.append(full);
                        FileWriter fw2 = new FileWriter(f, false);
                        fw2.write(sb.toString());
                        fw2.close();
                    } catch (Throwable ignored2) {}
                }
            }
        } catch (Throwable ignored) {}
    }
    /** 抢单日志 */
    private static void logGrab(String s) {
        Log.e("GrabLog", s);
        appendSdLog("grab.log", s);
    }
    /** 功能调用/开关变动日志 */
    private static void logFeatureCall(String s) {
        Log.e("FeatureLog", s);
        appendSdLog("feature.log", s);
    }
    /** 采集滚动状态：每文件最多20次刷新 */
    private static int collectRefreshCount = 0;
    private static int collectFileIndex = 1;
    /** 数据采集日志（采集_XX.txt，20次刷新滚一个文件，最多保留10个文件） */
    private static void logCollect(String s) {
        Log.e("CollectLog", s);
        appendSdLog(String.format(Locale.US, "采集_%02d.txt", collectFileIndex), s);
    }
    /** 一次刷新开始（写刷新分隔块 + 滚动文件；清理也走后台线程） */
    private static void startCollectRefresh(int orderCount) {
        collectRefreshCount++;
        collectSeq++;
        if (collectRefreshCount > 20) {
            collectRefreshCount = 1;
            collectFileIndex++;
            if (collectFileIndex > 99) collectFileIndex = 1;
            logCollect("===== 新采集文件（前一个文件已满20次刷新） =====");
            // 清理旧采集文件，最多保留10个（后台执行，不阻塞主线程）
            try {
                SD_LOG_EXEC.execute(() -> {
                    try {
                        // 清理 sdcard + 美团进程缓存 两处的旧采集文件，最多保留10个
                        cleanCollectFiles("/sdcard/Download/lucky/log");
                        try { cleanCollectFiles(getCacheDirPath() + "/lucky_log"); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }
        logCollect("===== 刷新 #" + collectRefreshCount + "，共 " + orderCount + " 单 =====");
    }
    /** 清理采集日志文件：目录下 采集_XX.txt 最多保留10个 */
    private static void cleanCollectFiles(String dirPath) {
        try {
            java.io.File dir = new java.io.File(dirPath);
            java.io.File[] fs = dir.listFiles();
            if (fs != null) {
                java.util.List<java.io.File> cf = new java.util.ArrayList<>();
                for (java.io.File f : fs) if (f.getName().startsWith("采集_") && f.getName().endsWith(".txt")) cf.add(f);
                cf.sort((a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                while (cf.size() > 10) { cf.remove(0).delete(); }
            }
        } catch (Throwable ignored) {}
    }
    /** 输出功能开关配置状态（数据采集核心视角） */
    private void dumpFeatureSwitches() {
        L("=== 功能开关状态（数据采集核心） ===");
        String[] keys = {"mt_hide_speed", "mt_location_spoof", "mt_anti_mock", "mt_auto_refresh_home",
                "mt_auto_refresh_delay", "mt_online_check", "mt_dev_options", "mt_skip_grab_confirm",
                "mt_skip_confirm", "grab_auto_enabled", "grab_continuous", "show_location_spoof"};
        for (String k : keys) {
            try {
                JSONObject c = loadCfg();
                Object v = (c != null && c.has(k)) ? c.get(k) : null;
                L("  [开关] " + k + " = " + (v == null ? "false (未配置,默认关)" : v));
            } catch (Throwable e) {
                L("  [开关] " + k + " = 读取失败: " + e.getMessage());
            }
        }
        L("=== 功能生效汇总（hook 安装结果，共 " + FEATURE_STATE.size() + " 项） ===");
        if (FEATURE_STATE.isEmpty()) L("  （各功能在安装时记录，见上方 [功能] 行）");
        for (java.util.Map.Entry<String, String> e : FEATURE_STATE.entrySet()) {
            L("  " + e.getKey() + " → " + e.getValue());
        }
    }

    private boolean p(String k, boolean d) {
        try { JSONObject c = loadCfg(); return c != null && c.has(k) ? c.getBoolean(k) : d; } catch (Throwable e) { return d; }
    }
    private double pd(String k, double d) {
        try { JSONObject c = loadCfg(); return c != null && c.has(k) ? c.getDouble(k) : d; } catch (Throwable e) { return d; }
    }
    private int pi(String k, int d) {
        try { JSONObject c = loadCfg(); return c != null && c.has(k) ? c.getInt(k) : d; } catch (Throwable e) { return d; }
    }
    /** 读取文件内容（直接File API优先，su作为备选） */
    private String readFileRoot(String path) {
        // 优先：直接 File API 读取（/data/local/tmp/ 是 world-readable 的）
        try {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                BufferedReader r = new BufferedReader(new FileReader(f));
                StringBuilder sb = new StringBuilder();
                String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                r.close();
                String result = sb.toString().trim();
                if (!result.isEmpty()) return result;
            }
        } catch (Throwable t) {
            Log.e("HookInit", "[readFileRoot] direct read fail: " + t.getMessage());
        }
        // 备选：通过 su -c cat 读取（带超时）
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            final StringBuilder sb = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String l; while ((l = r.readLine()) != null) { sb.append(l).append("\n"); }
                    r.close();
                } catch (Throwable ignored) {}
            });
            readerThread.start();
            boolean completed = p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                p.destroyForcibly();
                readerThread.interrupt();
            } else {
                readerThread.join(1000);
                String result = sb.toString().trim();
                if (!result.isEmpty()) return result;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 执行命令并等待完成（带超时） */
    private static boolean execCommand(String[] cmd) {
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            boolean completed = p.waitFor(SU_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                // 超时，强制销毁进程
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Throwable e) {
            return false;
        }
    }
    /** ★ 自动创建默认配置文件 — 确模块开箱即用，无需用户手动设置 */
    private void createDefaultConfigIfNeeded() {
        try {
            java.io.File cfg = new java.io.File(CONFIG_PATH);
            if (cfg.exists() && cfg.length() > 4) {
                Log.e("HookInit", "[Config] ✓ exists (" + cfg.length() + " bytes)");
                return;
            }
            // 创建默认配置，启用所有核心功能
            JSONObject def = new JSONObject();
            def.put("mt_location_spoof", true);    // 位置模拟
            def.put("mt_anti_mock", true);          // 反模拟检测
            def.put("mt_watermark", true);          // 去水印
            def.put("mt_screenshot", true);         // 允许截屏
            def.put("mt_update", true);             // 阻止更新
            def.put("mt_auto_refresh_home", true);  // 自动刷新主页
            def.put("mt_anti_detect", true);        // 反检测
            def.put("mt_hide_speed", true);         // 禁止超速检测
            def.put("mt_time_offset", false);       // 时间偏移（默认关）
            def.put("mt_online_check", true);       // 下线查看完整地址
            def.put("mt_dev_options", false);       // 开发者选项（默认关）
            def.put("grab_auto_enabled", true);     // 自动抢单
            def.put("grab_continuous", false);      // 连抢：关闭=每次只抢一单，开启=持续抢至上限
            def.put("mt_auto_refresh_delay", 1000); // 自动刷新主页间隔(ms)
            def.put("mt_skip_grab_confirm", false); // 跳过二次抢单确认（默认关）
            def.put("grab_delay_ms", 200);          // 抢单延迟200ms
            def.put("location_lat", 22.533864);     // 默认坐标（深圳）
            def.put("location_lng", 113.930291);
            def.put("location_speed", 0.0);         // 默认速度0

            cfg.getParentFile().mkdirs();
            // ★ 使用 writeViaSu 写入（确保文件权限为 666，目标App进程可读）
            String json = def.toString(2);
            boolean ok = writeViaSu(CONFIG_PATH, json);
            if (!ok) {
                // fallback: 直接写入（可能因权限失败，但不崩溃）
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(cfg);
                    fw.write(json);
                    fw.close();
                } catch (Throwable ignored) {}
            }
            Log.e("HookInit", "[Config] ✓ default created at " + CONFIG_PATH);
            // 重置缓存，让下次 loadCfg 重新读取
            configCache = def;
            configLastRaw = def.toString(2);
        } catch (Throwable t) {
            Log.e("HookInit", "[Config] ✗ create failed: " + t.getMessage());
        }
    }

    private static long lastCfgReadMs = 0;

    private JSONObject loadCfg() {
        // ★ 时间门控缓存：至少 150ms 才重读一次文件。
        //   实时开关仍即时生效（150ms 粒度用户无感），但避免高频 hook（如 Location.getSpeed）每次调用都读盘造成卡顿
        long nowMs = System.currentTimeMillis();
        if (configCache != null && lastCfgReadMs != 0 && (nowMs - lastCfgReadMs) < 150) {
            return configCache;
        }
        lastCfgReadMs = nowMs;
        try {
            File f = new File(CONFIG_PATH);
            String raw = readFileRoot(CONFIG_PATH);
            if (raw == null) {
                // ★ 区分「文件不存在」和「文件存在但读取失败」
                // 文件不存在 → 重置缓存，功能回到默认值（关闭）
                // 文件存在但读取失败 → 保持缓存（可能是临时权限问题）
                if (!f.exists()) {
                    Log.e("HookInit", "[loadCfg] config file deleted, reset cache to defaults");
                    configCache = null;
                    configLastRaw = null;
                    return null;
                }
                // 文件存在但读不到（权限问题），回退到上次缓存
                return configCache;
            }
            // 内容没变则跳过解析
            if (configCache != null && configLastRaw != null && configLastRaw.equals(raw)) {
                return configCache;
            }
            configCache = new JSONObject(raw);
            configLastRaw = raw;
            return configCache;
        } catch (Throwable t) { return configCache; }
    }

    /** ★ 性能：字段反射缓存（className#fieldName → Field），避免热路径重复遍历类层次 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Field> fieldCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> fieldMiss = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Field findFieldUp(Class<?> c, String fname) {
        String key = c.getName() + '#' + fname;
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;
        if (fieldMiss.contains(key)) return null;
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { Field f = cur.getDeclaredField(fname); f.setAccessible(true); fieldCache.put(key, f); return f; } catch (NoSuchFieldException ig) {}
            cur = cur.getSuperclass();
        }
        fieldMiss.add(key);
        return null;
    }

    private void forceCoords(Object loc) {
        if (loc == null) return;
        if (!p("mt_location_spoof", false)) return; // 开关关闭，不修改
        double lat = pd("location_lat", 0);
        double lng = pd("location_lng", 0);
        if (lat == 0 && lng == 0) return; // 无目标坐标，跳过
        try {
            Class<?> c = loc.getClass(); String name = c.getName();
            boolean coordOk = false;

            // ═══ 策略1: 字段 mLatitude/mLongitude（遍历类层次，覆盖Location及其子类） ═══
            try {
                Field fLat = findFieldUp(c, "mLatitude");
                Field fLng = findFieldUp(c, "mLongitude");
                if (fLat != null && fLng != null) {
                    double oldLat = fLat.getDouble(loc), oldLng = fLng.getDouble(loc);
                    fLat.setDouble(loc, lat); fLng.setDouble(loc, lng);
                    coordOk = true;
                }
            } catch (Throwable ig) {}

            // ═══ 策略2: getter/setter 方法（MtLocation 等无 mLatitude 字段的自定义类） ═══
            if (!coordOk) {
                try {
                    Method gLat = null, gLng = null, sLat = null, sLng = null;
                    Class<?> cur = c;
                    while (cur != null && cur != Object.class) {
                        try { if (gLat == null) gLat = cur.getDeclaredMethod("getLatitude"); } catch (NoSuchMethodException ig) {}
                        try { if (gLng == null) gLng = cur.getDeclaredMethod("getLongitude"); } catch (NoSuchMethodException ig) {}
                        try { if (sLat == null) sLat = cur.getDeclaredMethod("setLatitude", double.class); } catch (NoSuchMethodException ig) {}
                        try { if (sLng == null) sLng = cur.getDeclaredMethod("setLongitude", double.class); } catch (NoSuchMethodException ig) {}
                        if (gLat != null && sLat != null && gLng != null && sLng != null) break;
                        cur = cur.getSuperclass();
                    }
                    if (sLat != null && sLng != null) {
                        gLat.setAccessible(true); gLng.setAccessible(true);
                        sLat.setAccessible(true); sLng.setAccessible(true);
                        sLat.invoke(loc, lat); sLng.invoke(loc, lng);
                        coordOk = true;
                    }
                } catch (Throwable ig) {}
            }

            // ═══ 策略3: 搜索 double 字段（兜底，适配任意自定义位置类） ═══
            if (!coordOk) {
                try {
                    Class<?> cur = c;
                    while (cur != null && cur != Object.class) {
                        for (Field f : cur.getDeclaredFields()) {
                            String fn = f.getName().toLowerCase();
                            if (f.getType() == double.class || f.getType() == Double.class) {
                                if (fn.contains("lat") || fn.equals("latitude")) {
                                    f.setAccessible(true); f.setDouble(loc, lat); coordOk = true;
                                } else if (fn.contains("lng") || fn.contains("lon") || fn.equals("longitude")) {
                                    f.setAccessible(true); f.setDouble(loc, lng);
                                }
                            }
                        }
                        if (coordOk) break;
                        cur = cur.getSuperclass();
                    }
                } catch (Throwable ig) {}
            }

            // ═══ 附加清理（与坐标策略无关，逐项尝试） ═══
            try { Field f = findFieldUp(c, "mIsFromMockProvider"); if (f != null) f.setBoolean(loc, false); } catch (Throwable ig) {}
            try { Field f = findFieldUp(c, "mProvider"); if (f != null) f.set(loc, "gps"); } catch (Throwable ig) {}
            try { Field f = findFieldUp(c, "mTime"); if (f != null) f.setLong(loc, System.currentTimeMillis()); } catch (Throwable ig) {}
            if (name.contains("MtLocation")) {
                try { Field f = findFieldUp(c, "mExtras"); if (f != null) { Bundle ext = (Bundle) f.get(loc); if (ext != null) { ext.remove("cityid_mt"); ext.remove("cityid_dp"); ext.remove("dpName"); } } } catch (Throwable ig) {}
                try {
                    double speed = pd("location_speed", 0.0);
                    Field fSpeed = findFieldUp(c, "mSpeed");
                    if (fSpeed != null) {
                        fSpeed.setFloat(loc, (float) speed);
                        if (speed > 0) {
                            try { Field fMask = findFieldUp(c, "mFieldsMask"); if (fMask != null) fMask.setInt(loc, fMask.getInt(loc) | 2); } catch (Throwable ig) {}
                        }
                    }
                } catch (Throwable ig) {}
                try { Field f = findFieldUp(c, "mSpeedAccuracyMetersPerSecond"); if (f != null) f.setFloat(loc, 0.0f); } catch (Throwable ig) {}
            }
            if (name.contains("LocationEntity")) { try { Field f = findFieldUp(c, "from"); if (f != null) f.set(loc, "gps"); } catch (Throwable ig) {} }
        } catch (Throwable ig) {}
    }

    private void forceAndroidLocation(Object loc) {
        if (loc == null) return;
        if (!p("mt_location_spoof", false)) return; // 开关关闭，不修改
        double lat = pd("location_lat", 0), lng = pd("location_lng", 0);
        if (lat == 0 && lng == 0) return; // 无目标坐标，跳过
        try {
            Class<?> c = loc.getClass();
            // 遍历类层次查找 setLatitude/setLongitude 方法
            Method sLat = null, sLng = null;
            Class<?> cur = c;
            while (cur != null && cur != Object.class) {
                try { if (sLat == null) sLat = cur.getDeclaredMethod("setLatitude", double.class); } catch (NoSuchMethodException ig) {}
                try { if (sLng == null) sLng = cur.getDeclaredMethod("setLongitude", double.class); } catch (NoSuchMethodException ig) {}
                if (sLat != null && sLng != null) break;
                cur = cur.getSuperclass();
            }
            if (sLat != null && sLng != null) {
                sLat.setAccessible(true); sLng.setAccessible(true);
                sLat.invoke(loc, lat); sLng.invoke(loc, lng);
            } else {
                // 回退: 直接调用 forceCoords 的字段策略
                forceCoords(loc);
                return;
            }
            // 附加清理
            cur = c;
            while (cur != null && cur != Object.class) {
                try { Method st = cur.getDeclaredMethod("setTime", long.class); st.setAccessible(true); st.invoke(loc, System.currentTimeMillis()); break; } catch (NoSuchMethodException ig) { cur = cur.getSuperclass(); }
            }
            cur = c;
            while (cur != null && cur != Object.class) {
                try { Method sp = cur.getDeclaredMethod("setProvider", String.class); sp.setAccessible(true); sp.invoke(loc, "gps"); break; } catch (NoSuchMethodException ig) { cur = cur.getSuperclass(); }
            }
            // 速度注入
            try {
                double speed = pd("location_speed", 0.0);
                cur = c;
                while (cur != null && cur != Object.class) {
                    try { Method sSpeed = cur.getDeclaredMethod("setSpeed", float.class); sSpeed.setAccessible(true); sSpeed.invoke(loc, (float) speed); break; } catch (NoSuchMethodException ig) { cur = cur.getSuperclass(); }
                }
            } catch (Throwable ig) {}
        } catch (Throwable ig) {}
    }

    /** ★ 构造伪造的 android.location.Location（含坐标+速度+时间） */
    private Object createSpoofedLocation(ClassLoader cl) {
        try {
            Class<?> LocClass = cl.loadClass("android.location.Location");
            Constructor<?> ctor = LocClass.getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            Object loc = ctor.newInstance("gps");
            Method sLat = LocClass.getDeclaredMethod("setLatitude", double.class);
            Method sLng = LocClass.getDeclaredMethod("setLongitude", double.class);
            Method sTime = LocClass.getDeclaredMethod("setTime", long.class);
            Method sSpeed = LocClass.getDeclaredMethod("setSpeed", float.class);
            Method sAcc = LocClass.getDeclaredMethod("setAccuracy", float.class);
            sLat.setAccessible(true); sLng.setAccessible(true);
            sTime.setAccessible(true); sSpeed.setAccessible(true); sAcc.setAccessible(true);
            sLat.invoke(loc, pd("location_lat", 0));
            sLng.invoke(loc, pd("location_lng", 0));
            sTime.invoke(loc, System.currentTimeMillis());
            sSpeed.invoke(loc, (float) pd("location_speed", 0.0));
            sAcc.invoke(loc, 3.0f);
            return loc;
        } catch (Throwable t) { Le("createSpoofedLocation", t); return null; }
    }

    /** ★ 广播新位置到所有已注册的LocationListener */
    private void broadcastLocationUpdate() {
        if (allLocationListeners.isEmpty()) return;
        Object loc = createSpoofedLocation(KClassLoader);
        if (loc == null) return;
        int count = 0;
        for (Object listener : allLocationListeners) {
            try {
                Method onLocChanged = listener.getClass().getDeclaredMethod("onLocationChanged", loc.getClass());
                onLocChanged.setAccessible(true);
                onLocChanged.invoke(listener, loc);
                count++;
            } catch (Throwable ig) {}
        }
        if (count > 0) L("[Loc] broadcast to " + count + "/" + allLocationListeners.size() + " listeners");
    }

    /** ★ 检测配置坐标变更，变更时主动广播 */
    private void checkAndBroadcastLocation() {
        double curLat = pd("location_lat", 0);
        double curLng = pd("location_lng", 0);
        if (curLat == 0 && curLng == 0) return;
        if (curLat != lastBroadcastLat || curLng != lastBroadcastLng) {
            lastBroadcastLat = curLat;
            lastBroadcastLng = curLng;
            L("[Loc] coord changed → " + curLat + "," + curLng + " broadcasting...");
            broadcastLocationUpdate();
        }
    }

    // ★ 中文优先匹配（美团APP界面全中文），旧版兼容英文
    private static final String[] CONFIRM_TEXTS = {"确认", "确定", "立即", "同意", "确认抢单", "确认接单", "立即确认", "我知道了", "接受转单", "确认转单", "接收转单"};
    private boolean matchesConfirmText(String ts) {
        if (ts == null) return false;
        for (String s : CONFIRM_TEXTS) { if (ts.contains(s)) return true; }
        return false;
    }
    /** 检查View树中是否有匹配确认文本的按钮 */
    private boolean hasConfirmButton(View view) {
        if (view == null) return false;
        if (view instanceof Button || view instanceof TextView) {
            CharSequence t = ((TextView) view).getText();
            if (t != null && matchesConfirmText(t.toString().trim())) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasConfirmButton(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }
    private void autoClickConfirmButton(View view) {
        if (view == null) return;
        if (view instanceof Button) {
            CharSequence t = ((Button) view).getText();
            if (t != null) {
                String ts = t.toString().trim();
                if (matchesConfirmText(ts)) {
                    view.performClick(); L("[Grab] ★ 点击: " + ts); return;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) autoClickConfirmButton(vg.getChildAt(i));
        }
    }

    private void autoClickConfirmInRN(View view, int depth, Activity activity) {
        if (view == null || depth > 20) return;

        try {
            // 检查所有可点击的View的文本
            if (view instanceof TextView) {
                CharSequence t = ((TextView) view).getText();
                String desc = view.getContentDescription() != null ? view.getContentDescription().toString() : "";
                if (t != null) {
                    String ts = t.toString().trim();
                    if (matchesConfirmText(ts)) {
                        L("[Grab] ★★★ D/E: 找到文本 text=\"" + ts + "\" class=" + view.getClass().getSimpleName()
                            + " clickable=" + view.isClickable());

                        if (view.isClickable()) {
                            // 文本View本身可点击，直接点击
                            try {
                                view.performClick();
                                L("[Grab] ★★★ D/E: click ok! text=\"" + ts + "\"");
                            } catch (Throwable t2) { L("[Grab] D/Eclick fail: " + t2.getMessage()); }
                        } else {
                            // RN文本不可点击，找最近的可点击父节点
                            View clickableParent = findClickableParent(view);
                            if (clickableParent != null) {
                                L("[Grab] ★★★ D/E: 点击父节点 class=" + clickableParent.getClass().getSimpleName()
                                    + " for text=\"" + ts + "\"");
                                try {
                                    clickableParent.performClick();
                                    L("[Grab] ★★★ D/E: 父节点click ok! text=\"" + ts + "\"");
                                } catch (Throwable t2) { L("[Grab] D/E父节点click fail: " + t2.getMessage()); }
                            } else {
                                // 没有可点击父节点，用坐标点击
                                L("[Grab] ★★★ D/E: 无父节点，坐标点击 text=\"" + ts + "\"");
                                int[] loc = new int[2];
                                view.getLocationOnScreen(loc);
                                int x = loc[0] + view.getWidth() / 2;
                                int y = loc[1] + view.getHeight() / 2;
                                try {
                                    long downTime = android.os.SystemClock.uptimeMillis();
                                    android.view.MotionEvent down = android.view.MotionEvent.obtain(
                                        downTime, downTime, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                                    android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                        downTime, downTime + 100, android.view.MotionEvent.ACTION_UP, x, y, 0);
                                    view.dispatchTouchEvent(down);
                                    view.dispatchTouchEvent(up);
                                    down.recycle();
                                    up.recycle();
                                    L("[Grab] ★★★ D/E: 坐标click ok! x=" + x + " y=" + y + " text=\"" + ts + "\"");
                                } catch (Throwable t2) { L("[Grab] D/E坐标click fail: " + t2.getMessage()); }
                            }
                        }
                        return;
                    }
                }
                // 也检查contentDescription（中英文兼查）
                if (matchesConfirmText(desc) || desc.contains("抢单")) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { view.performClick(); L("[Grab] ★★★ D/E: click ok(desc)!"); }
                        catch (Throwable t2) { L("[Grab] D/Eclick fail: " + t2.getMessage()); }
                    }, 200);
                    return;
                }
            }

            // 递归遍历ViewGroup的子视图
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    autoClickConfirmInRN(vg.getChildAt(i), depth + 1, activity);
                }
            }
        } catch (Throwable t) {
            L("[Grab] D遍历节点异常 depth=" + depth + ": " + t.getMessage());
        }
    }

    // 从指定View向上查找最近的可点击父节点
    private View findClickableParent(View view) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View pv = (View) parent;
            if (pv.isClickable()) return pv;
            parent = pv.getParent();
        }
        return null;
    }

    @Override
	public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
		String proc = param.getProcessName();
		// 提炼主包名（去掉 :suffix 子进程后缀），用于日志目录回退
		currentPackageName = (proc != null && proc.contains(":")) ? proc.substring(0, proc.indexOf(":")) : proc;
		// ★ 先用 Log.e 输出，确保 logcat 能看到（此时文件日志可能还未就绪）
		Log.e("HookInit", "=== onModuleLoaded: " + proc + " pid=" + android.os.Process.myPid() + " pkg=" + currentPackageName + " ===");
		isMeituan = proc != null && proc.contains("meituan");

		// 模块存活标记 — 多通道写入（su /data/local/tmp + 免su OrdersProvider + sdcard）
		try {
			pushModuleHeartbeat(proc);
			Log.e("HookInit", "[status] heartbeat pushed");
		} catch (Throwable t) {
			Log.e("HookInit", "[status] write failed: " + t.getMessage());
		}

		// ★ 心跳：每30秒刷新存活标记，供主页面检测 LSPosed 激活状态
		try {
			final String hbProc = proc;
			final android.os.Handler hb = new android.os.Handler(android.os.Looper.getMainLooper());
			hb.postDelayed(new Runnable() {
				@Override
				public void run() {
					try {
						pushModuleHeartbeat(hbProc);
					} catch (Throwable ignored) {}
					hb.postDelayed(this, 30000L);
				}
			}, 30000L);
		} catch (Throwable t) {
			Log.e("HookInit", "[status] heartbeat start failed: " + t.getMessage());
		}

		// ★ 自动创建默认配置文件（如果不存在）— 确保模块开箱即用
		createDefaultConfigIfNeeded();

		// ★ 初始化日志（确保之后 L() 可用）
		try {
			ensureLogPath();
			L("onModuleLoaded: " + proc);
		} catch (Throwable t) {
			Log.e("HookInit", "ensureLogPath fail: " + t.getMessage());
		}

		if ("com.mtqsyqgx.app".equals(proc)) {
			try {
				hook(Class.forName("com.mtqsyqgx.app.MainActivity")
					 .getDeclaredMethod("isModuleActive"))
                    .intercept(c -> true);  // ★ 能装上hook就说明模块活着
				L("isModuleActive ✓");
			} catch (Throwable t) { Le("isModuleActive FAILED", t); }
		}
	}

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        String pkg = param.getPackageName();
        L("onPackageReady: " + pkg + " pid=" + android.os.Process.myPid());
        if ("com.sankuai.meituan.dispatch.crowdsource".equals(pkg) && !hooked) {
            hooked = true;
            doHook(param.getClassLoader());
        }
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam p) {}
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam p) {}

    private Method M(ClassLoader cl, String cls, String name, Class<?>... pts) throws Exception {
        Method m = cl.loadClass(cls).getDeclaredMethod(name, pts); m.setAccessible(true); return m;
    }

    /** ★ 收集 requestLocationUpdates 中传入的 LocationListener */
    private void collectUpdateListeners(ClassLoader cl, Class<?> targetClass) {
        try {
            for (Method m : targetClass.getDeclaredMethods()) {
                if (!m.getName().equals("requestLocationUpdates")) continue;
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    for (Object arg : chain.getArgs()) {
                        if (arg == null) continue;
                        try {
                            if (cl.loadClass("android.location.LocationListener").isInstance(arg)) {
                                if (!allLocationListeners.contains(arg)) {
                                    allLocationListeners.add(arg);
                                    L("[Loc] listener collected: " + arg.getClass().getName() + " (total=" + allLocationListeners.size() + ")");
                                }
                            }
                        } catch (Throwable ig) {}
                    }
                    return chain.proceed();
                });
                L("[Loc] requestLocationUpdates hook ✓ (" + targetClass.getSimpleName() + ")");
                return;
            }
        } catch (Throwable t) { Le("collect listeners " + targetClass.getSimpleName(), t); }
    }

    private void removeUpdateListeners(ClassLoader cl, Class<?> targetClass) {
        try {
            for (Method m : targetClass.getDeclaredMethods()) {
                if (!m.getName().equals("removeUpdates")) continue;
                Class<?>[] pts = m.getParameterTypes();
                boolean hasListener = false;
                for (Class<?> pt : pts) {
                    try { if (cl.loadClass("android.location.LocationListener").isAssignableFrom(pt)) { hasListener = true; break; } } catch (Throwable ig) {}
                }
                if (!hasListener) continue;
                m.setAccessible(true);
                hook(m).intercept(chain -> {
                    for (Object arg : chain.getArgs()) {
                        if (arg == null) continue;
                        try {
                            if (cl.loadClass("android.location.LocationListener").isInstance(arg)) {
                                allLocationListeners.remove(arg);
                                L("[Loc] listener removed: " + arg.getClass().getName() + " (total=" + allLocationListeners.size() + ")");
                            }
                        } catch (Throwable ig) {}
                    }
                    return chain.proceed();
                });
                L("[Loc] removeUpdates hook ✓ (" + targetClass.getSimpleName() + ")");
                return;
            }
        } catch (Throwable t) { Le("remove listeners " + targetClass.getSimpleName(), t); }
    }

    private void doHook(ClassLoader cl) {
        KClassLoader = cl;
        L("doHook start");

        // ★ 网络检查：不允许在无网络状态使用目标app
        try {
            if (!IntegrityChecker.isNetworkReachable()) {
                Log.e("HookInit", "[Integrity] 无网络连接，跳过所有hook（功能不生效）");
                L("[Integrity] 无网络连接，跳过所有hook");
                return; // 无网络则不执行任何hook，功能全部保持默认关闭
            }
        } catch (Throwable t) {
            Log.e("HookInit", "[Integrity] 网络检查异常: " + t.getMessage());
        }

        List<String> failed = new ArrayList<>();
        boolean needCoord = false;

        // ═══ 禁止超速检测（mt_hide_speed，v3：只拦截纯业务读取；★开关实时生效，无需重启美团）═══
        {
            L("[HideSpeed] >>> 安装 禁止超速检测（v3：仅业务读取，开关实时生效）");
            // 1. MtLocation 速度：只有纯业务读取才拦截（保护地图/导航/定位SDK）
            Class<?> mtClass = findClassOrNull(cl, "com.meituan.android.common.locate.MtLocation");
            if (mtClass != null) {
                hookAllMethodsBefore(mtClass, "getSpeed", chain -> {
                    if (!p("mt_hide_speed", false)) return chain.proceed(); // 开关关 → 立即放行
                    boolean prot = isSpeedCallerProtected();
                    return prot ? chain.proceed() : 0.0f;
                });
                hookAllMethodsBefore(mtClass, "hasSpeed", chain -> {
                    if (!p("mt_hide_speed", false)) return chain.proceed();
                    return isSpeedCallerProtected() ? chain.proceed() : false;
                });
                hookAllMethodsBefore(mtClass, "getSpeedAccuracyMetersPerSecond", chain -> {
                    if (!p("mt_hide_speed", false)) return chain.proceed();
                    return isSpeedCallerProtected() ? chain.proceed() : 0.0f;
                });
                L("[HideSpeed] 已Hook: MtLocation 速度（仅业务读取）");
            } else {
                L("[HideSpeed] 未找到 MtLocation");
            }
            // 2. 腾讯定位 loader bean：同样只拦截业务读取
            Class<?> tencentBean = findClassOrNull(cl, "com.meituan.android.common.locate.loader.tencent.bean.b");
            if (tencentBean != null) {
                hookAllMethodsBefore(tencentBean, "o", chain -> {
                    if (!p("mt_hide_speed", false)) return chain.proceed();
                    return isSpeedCallerProtected() ? chain.proceed() : 0.0f;
                });
                L("[HideSpeed] 已Hook: tencent bean 速度（仅业务读取）");
            } else {
                L("[HideSpeed] 未找到 tencent bean");
            }
            // 3. Location.getSpeed()：同样只拦截业务读取
            Class<?> locClass = findClassOrNull(cl, "android.location.Location");
            if (locClass != null) {
                hookAllMethodsBefore(locClass, "getSpeed", chain -> {
                    if (!p("mt_hide_speed", false)) return chain.proceed();
                    try {
                        if (!isSpeedCallerProtected()) return 0.0f;
                    } catch (Throwable e) {}
                    return chain.proceed();
                });
                L("[HideSpeed] 已Hook: Location.getSpeed()（仅业务读取）");
            } else {
                L("[HideSpeed] 未找到 Location");
            }
            L("[HideSpeed] 插件加载完成");
            logFeature("禁止超速", true, "插件加载完成(开关实时生效)");
        }

        // ═══ 定位模拟钩子（仅开关开启时安装；forceCoords 内部仍做二次检查） ═══
        if (p("mt_location_spoof", false)) {
        double lat = pd("location_lat", 0); double lng = pd("location_lng", 0);
        if (lat == 0 && lng == 0) { needCoord = true; L("[Loc] no coord yet, hooks standing by"); }
        L("[Loc] target: " + lat + "," + lng);
        // ═══ 数据流入点hook（进入App的所有定位数据都会被篡改） ═══
        try { hook(M(cl, "com.meituan.android.common.locate.MasterLocatorImpl", "getLastMtLocation")).intercept(chain -> { Object r = chain.proceed(); forceCoords(r); checkAndBroadcastLocation(); return r; }); L("[Loc] getLastMtLocation ✓"); } catch (Throwable t) { failed.add("cache"); Le("getLastMtLocation", t); }
        try { Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(M(cl, "com.meituan.android.common.locate.MasterLocatorImpl", "onLocationGot", mt)).intercept(chain -> { forceCoords(chain.getArgs().get(0)); checkAndBroadcastLocation(); return chain.proceed(); }); L("[Loc] onLocationGot(Mt) ✓"); } catch (Throwable t) { failed.add("realtime"); Le("onLocationGot(Mt)", t); }
        try { Class<?> loc = cl.loadClass("android.location.Location"); hook(M(cl, "com.meituan.android.common.locate.MasterLocatorImpl", "onLocationGot", loc)).intercept(chain -> { forceAndroidLocation(chain.getArgs().get(0)); checkAndBroadcastLocation(); return chain.proceed(); }); L("[Loc] onLocationGot(Loc) ✓"); } catch (Throwable t) { failed.add("onLocationGot(Loc)"); Le("onLocationGot(Loc)", t); }
            try { hook(M(cl, "com.meituan.android.common.locate.MasterLocatorImpl", "getLastLocation")).intercept(chain -> { 
                Object r = chain.proceed(); 
                if (r == null) {
                    // 原始返回null时，创建伪造的Location对象
                    r = createSpoofedLocation(KClassLoader);
                    L("[Loc] getLastLocation was null, created spoofed location");
                }
                forceAndroidLocation(r); 
                checkAndBroadcastLocation(); 
                return r; 
            }); L("[Loc] getLastLocation ✓"); } catch (Throwable t) { Le("getLastLocation", t); }
            try { Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(M(cl, "com.meituan.android.common.locate.MasterLocatorImpl", "setMtLocation", mt)).intercept(chain -> { forceCoords(chain.getArgs().get(0)); checkAndBroadcastLocation(); return chain.proceed(); }); L("[Loc] setMtLocation ✓"); } catch (Throwable t) { Le("setMtLocation", t); }
            // ═══ LocationSpiImpl hook（覆盖SPI位置获取路径） ═══
            try { Class<?> lsi = cl.loadClass("com.meituan.android.common.locate.LocationSpiImpl"); hook(lsi.getDeclaredMethod("getLastKnownLocation")).intercept(chain -> { Object r = chain.proceed(); if (r != null) forceCoords(r); return r; }); L("[Loc] LocationSpiImpl.getLastKnownLocation ✓"); } catch (Throwable t) { Le("LocationSpiImpl.getLastKnownLocation", t); }
            try { Class<?> lsi2 = cl.loadClass("com.meituan.android.common.locate.LocationSpiImpl"); hook(lsi2.getDeclaredMethod("getLocation")).intercept(chain -> { Object r = chain.proceed(); if (r != null) forceCoords(r); return r; }); L("[Loc] LocationSpiImpl.getLocation ✓"); } catch (Throwable t) { Le("LocationSpiImpl.getLocation", t); }
            // ═══ getter级别hook（覆盖所有读取路径） ═══
            try { Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(mt.getDeclaredMethod("getLatitude")).intercept(c -> pd("location_lat", 0)); hook(mt.getDeclaredMethod("getLongitude")).intercept(c -> pd("location_lng", 0)); L("[Loc] MtLocation getter ✓"); } catch (Throwable t) { Le("MtLocation getter", t); }
            try { Class<?> le = cl.loadClass("com.meituan.android.privacy.locate.LocationEntity"); Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(le.getDeclaredMethod("toEntity", mt)).intercept(chain -> { Object r = chain.proceed(); forceCoords(r); checkAndBroadcastLocation(); return r; }); hook(le.getDeclaredMethod("toMtLocation")).intercept(chain -> { Object r = chain.proceed(); forceCoords(r); checkAndBroadcastLocation(); return r; }); L("[Loc] LocationEntity ✓"); } catch (Throwable t) { Le("LocationEntity", t); }
            try { Class<?> rtl = cl.loadClass("com.sankuai.meituan.pai.location.RealTimeLocation"); Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(rtl.getDeclaredMethod("getLocation")).intercept(chain -> { Object r = chain.proceed(); forceAndroidLocation(r); checkAndBroadcastLocation(); return r; }); hook(rtl.getDeclaredMethod("convertMtLocation", mt)).intercept(chain -> { Object r = chain.proceed(); forceAndroidLocation(r); checkAndBroadcastLocation(); return r; }); L("[Loc] RealTimeLocation ✓"); } catch (Throwable t) { Le("RealTimeLocation", t); }
            try { Class<?> gm = cl.loadClass("com.meituan.android.common.pos.manager.GPSManager"); hook(gm.getDeclaredMethod("initGPSData")).intercept(chain -> { chain.proceed(); try { Field f = gm.getDeclaredField("mLasterLocation"); f.setAccessible(true); Object last = f.get(chain.getThisObject()); if (last != null) forceCoords(last); } catch (Throwable ig) {} return null; }); L("[Loc] GPSManager ✓"); } catch (Throwable t) { Le("GPSManager", t); }
            try { Class<?> b = cl.loadClass("com.meituan.android.common.locate.loader.tencent.bean.b"); hook(b.getDeclaredMethod("b")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lat", 0) : c.proceed()); hook(b.getDeclaredMethod("c")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lng", 0) : c.proceed()); hook(b.getDeclaredMethod("h")).intercept(c -> ""); hook(b.getDeclaredMethod("i")).intercept(c -> ""); hook(b.getDeclaredMethod("j")).intercept(c -> ""); L("[Loc] tencent.bean.b ✓"); } catch (Throwable t) { Le("tencent.bean.b", t); }
            try { Class<?> loc = cl.loadClass("android.location.Location"); hook(loc.getDeclaredMethod("getLatitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lat", 0) : c.proceed()); hook(loc.getDeclaredMethod("getLongitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lng", 0) : c.proceed()); L("[Loc] Location getter ✓"); } catch (Throwable t) {}
            // ═══ 地图自定义位置源hook（覆盖订单详情地图显示） ═══
            try { Class<?> cml = cl.loadClass("com.meituan.banma.map.CustomMyLocation$LocationWrapper"); hook(cml.getDeclaredMethod("getLatitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lat", 0) : c.proceed()); hook(cml.getDeclaredMethod("getLongitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lng", 0) : c.proceed()); L("[Loc] CustomMyLocation getter ✓"); } catch (Throwable t) { Le("CustomMyLocation getter", t); }
            try { Class<?> mml = cl.loadClass("com.meituan.banma.mrn.map.MrnMapExtraLocationSource$1"); hook(mml.getDeclaredMethod("getLatitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lat", 0) : c.proceed()); hook(mml.getDeclaredMethod("getLongitude")).intercept(c -> p("mt_location_spoof", false) ? pd("location_lng", 0) : c.proceed()); L("[Loc] MrnMapExtraLocationSource getter ✓"); } catch (Throwable t) { Le("MrnMapExtraLocationSource getter", t); }
            // ═══ ★ 核心修复：CustomMyLocation.getCurrentLocation/getCurrentMapLocation（蓝点依赖这些方法） ═══
            try {
                Class<?> cmlClass = cl.loadClass("com.meituan.banma.map.CustomMyLocation");
                // getCurrentLocation() 返回 mCurrentLocation，蓝点直接用这个
                hook(cmlClass.getDeclaredMethod("getCurrentLocation")).intercept(chain -> {
                    Object r = chain.proceed();
                    if (r != null) { forceAndroidLocation(r); }
                    else { r = createSpoofedLocation(KClassLoader); }
                    return r;
                });
                // getCurrentMapLocation() 返回 mCurrentMapLocation，热力图等使用
                hook(cmlClass.getDeclaredMethod("getCurrentMapLocation")).intercept(chain -> {
                    Object r = chain.proceed();
                    if (r != null) { forceCoords(r); }
                    return r;
                });
                // access$100 是 CustomMyLocation.a(Location) 的合成访问器，在蓝点更新前拦截
                try {
                    hook(cmlClass.getDeclaredMethod("access$100", cmlClass, cl.loadClass("android.location.Location")))
                        .intercept(chain -> {
                            Object loc = chain.getArgs().get(1);
                            if (loc != null) { forceAndroidLocation(loc); }
                            return chain.proceed();
                        });
                    L("[Loc] CustomMyLocation.access$100 ✓");
                } catch (Throwable ig) {}
                L("[Loc] CustomMyLocation.getCurrentLocation/getCurrentMapLocation ✓");
            } catch (Throwable t) { Le("CustomMyLocation core", t); }
            // ═══ ★ 导航蓝点：NaviLocationMarkerAgent ═══
            try {
                Class<?> nlmClass = cl.loadClass("com.meituan.sankuai.navisdk_ui.map.onself.NaviLocationMarkerAgent");
                Class<?> nlClass = cl.loadClass("com.meituan.sankuai.navisdk.api.inside.model.NaviLocation");
                // NaviLocation 有 latitude/longitude 字段，没有 setter，直接反射字段
                try { Field nLat = nlClass.getDeclaredField("latitude"); Field nLng = nlClass.getDeclaredField("longitude"); nLat.setAccessible(true); nLng.setAccessible(true);
                hook(nlmClass.getDeclaredMethod("onLocationUpdate", nlClass)).intercept(chain -> {
                    Object naviLoc = chain.getArgs().get(0);
                    if (naviLoc != null) {
                        try {
                            nLat.setDouble(naviLoc, pd("location_lat", 0));
                            nLng.setDouble(naviLoc, pd("location_lng", 0));
                        } catch (Throwable ig) {}
                    }
                    return chain.proceed();
                }); L("[Loc] NaviLocationMarkerAgent.onLocationUpdate ✓"); } catch (Throwable ig) { Le("onLocationUpdate", ig); }
                // getMarkerLatLng 返回 LatLng，不可变，需要替换为新的
                try {
                    Class<?> latLngClass = cl.loadClass("com.sankuai.meituan.mapsdk.maps.model.LatLng");
                    hook(nlmClass.getDeclaredMethod("getMarkerLatLng", nlClass)).intercept(chain -> {
                        Object r = chain.proceed();
                        double la = pd("location_lat", 0), lo = pd("location_lng", 0);
                        if (la != 0 || lo != 0) {
                            try { return latLngClass.getConstructor(double.class, double.class).newInstance(la, lo); }
                            catch (Throwable ig) {}
                        }
                        return r;
                    });
                    L("[Loc] NaviLocationMarkerAgent.getMarkerLatLng ✓");
                } catch (Throwable ig) { Le("getMarkerLatLng", ig); }
                // updateMarker - 强制更新蓝点
                try { hook(nlmClass.getDeclaredMethod("updateMarker", boolean.class, boolean.class, nlClass)).intercept(chain -> {
                    Object naviLoc = chain.getArgs().get(2);
                    if (naviLoc != null) {
                        try {
                            Field nLat2 = nlClass.getDeclaredField("latitude"); Field nLng2 = nlClass.getDeclaredField("longitude"); nLat2.setAccessible(true); nLng2.setAccessible(true);
                            nLat2.setDouble(naviLoc, pd("location_lat", 0));
                            nLng2.setDouble(naviLoc, pd("location_lng", 0));
                        } catch (Throwable ig) {}
                    }
                    return chain.proceed();
                }); L("[Loc] NaviLocationMarkerAgent.updateMarker ✓"); } catch (Throwable ig) {}
            } catch (Throwable t) { Le("NaviLocationMarkerAgent", t); }
            // ═══ ★ 智能助手位置：GetLocationJsHandler (Titans JS Bridge) ═══
            try {
                Class<?> gljClass = cl.loadClass("com.dianping.titans.js.jshandler.GetLocationJsHandler");
                hook(gljClass.getDeclaredMethod("exec")).intercept(chain -> {
                    L("[Loc] GetLocationJsHandler.exec intercepted");
                    return chain.proceed();
                });
                L("[Loc] GetLocationJsHandler ✓");
            } catch (Throwable t) { Le("GetLocationJsHandler", t); }
            // ═══ ★ 额外位置源覆盖：ExtraLocationSources、BanmaMapLocationService ═══
            try {
                Class<?> elsClass = cl.loadClass("com.meituan.banma.map.ExtraLocationSources");
                for (Method m : elsClass.getDeclaredMethods()) {
                    if (m.getName().contains("Location") || m.getName().contains("location")) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) { forceCoords(r); forceAndroidLocation(r); }
                            return r;
                        });
                    }
                }
                L("[Loc] ExtraLocationSources ✓");
            } catch (Throwable t) { Le("ExtraLocationSources", t); }
            try {
                Class<?> bmlClass = cl.loadClass("com.meituan.banma.map.location.BanmaMapLocationService");
                for (Method m : bmlClass.getDeclaredMethods()) {
                    if (m.getName().contains("Location") || m.getName().contains("location") || m.getName().contains("getLast")) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) { forceCoords(r); forceAndroidLocation(r); }
                            return r;
                        });
                    }
                }
                L("[Loc] BanmaMapLocationService ✓");
            } catch (Throwable t) { Le("BanmaMapLocationService", t); }
            try {
                Class<?> bmlSrc = cl.loadClass("com.meituan.banma.map.location.BanmaMapLocationSource");
                for (Method m : bmlSrc.getDeclaredMethods()) {
                    if (m.getName().contains("Location") || m.getName().contains("location") || m.getName().contains("getLast")) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) { forceCoords(r); forceAndroidLocation(r); }
                            return r;
                        });
                    }
                }
                L("[Loc] BanmaMapLocationSource ✓");
            } catch (Throwable t) { Le("BanmaMapLocationSource", t); }
            try {
                Class<?> cmlSrc = cl.loadClass("com.meituan.banma.map.location.CSMapLocationSource");
                for (Method m : cmlSrc.getDeclaredMethods()) {
                    if (m.getName().contains("Location") || m.getName().contains("location") || m.getName().contains("getLast")) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) { forceCoords(r); forceAndroidLocation(r); }
                            return r;
                        });
                    }
                }
                L("[Loc] CSMapLocationSource ✓");
            } catch (Throwable t) { Le("CSMapLocationSource", t); }
            // ═══ ★ LocationSourceDelegator（地图SDK位置代理） ═══
            try {
                Class<?> lsdClass = cl.loadClass("com.meituan.banma.map.location.LocationSourceDelegator");
                for (Method m : lsdClass.getDeclaredMethods()) {
                    if (m.getName().contains("Location") || m.getName().contains("location") || m.getName().contains("getLast")) {
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            if (r != null) { forceCoords(r); forceAndroidLocation(r); }
                            return r;
                        });
                    }
                }
                L("[Loc] LocationSourceDelegator ✓");
            } catch (Throwable t) { Le("LocationSourceDelegator", t); }
            // ═══ ★ 系统 LocationManager.getLastKnownLocation（兜底覆盖） ═══
            try {
                Class<?> lmClass = cl.loadClass("android.location.LocationManager");
                hook(lmClass.getDeclaredMethod("getLastKnownLocation", String.class)).intercept(chain -> {
                    Object r = chain.proceed();
                    if (r != null) { forceAndroidLocation(r); }
                    else {
                        String provider = (String) chain.getArgs().get(0);
                        if ("gps".equals(provider) || "network".equals(provider) || "fused".equals(provider)) {
                            r = createSpoofedLocation(KClassLoader);
                        }
                    }
                    return r;
                });
                L("[Loc] LocationManager.getLastKnownLocation ✓");
            } catch (Throwable t) { Le("LocationManager.getLastKnownLocation", t); }
            // ═══ 监听者收集（注册时收集所有LocationListener，用于后续广播） ═══
            try { collectUpdateListeners(cl, cl.loadClass("com.meituan.android.common.locate.MasterLocatorImpl")); } catch (Throwable t) { Le("MLI listeners", t); }
            try { collectUpdateListeners(cl, cl.loadClass("android.location.LocationManager")); } catch (Throwable t) { Le("LM listeners", t); }
            try { removeUpdateListeners(cl, cl.loadClass("com.meituan.android.common.locate.MasterLocatorImpl")); } catch (Throwable t) {}
            try { removeUpdateListeners(cl, cl.loadClass("android.location.LocationManager")); } catch (Throwable t) {}
            // ═══ 速度检测hook ═══
            try {
                Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation");
                hook(mt.getDeclaredMethod("getSpeed")).intercept(c -> {
                    double cfgSpeed = pd("location_speed", 0.0);
                    if (cfgSpeed > 0) return (float) cfgSpeed; // 模拟速度
                    return c.proceed(); // 未模拟 → 返回真实速度（不再刷日志/不覆盖）
                });
                hook(mt.getDeclaredMethod("hasSpeed")).intercept(c -> {
                    if (pd("location_speed", 0.0) > 0) return true;
                    return c.proceed();
                });
                try { hook(mt.getDeclaredMethod("getSpeedAccuracyMetersPerSecond")).intercept(c -> {
                    if (pd("location_speed", 0.0) > 0) return 0.0f;
                    return c.proceed();
                }); } catch (Throwable ig) {}
                try { hook(mt.getDeclaredMethod("setSpeed", float.class)).intercept(chain -> {
                    double cfgSpeed = pd("location_speed", 0.0);
                    if (cfgSpeed > 0) chain.getArgs().set(0, (float) cfgSpeed);
                    return chain.proceed();
                }); } catch (Throwable ig) {}
                L("[Loc] MtLocation speed ✓");
            } catch (Throwable t) { Le("MtLocation speed", t); }
            try {
                Class<?> loc = cl.loadClass("android.location.Location");
                hook(loc.getDeclaredMethod("getSpeed")).intercept(c -> {
                    if (pd("location_speed", 0.0) > 0) return (float) pd("location_speed", 0.0);
                    return c.proceed();
                });
                hook(loc.getDeclaredMethod("hasSpeed")).intercept(c -> {
                    if (pd("location_speed", 0.0) > 0) return true;
                    return c.proceed();
                });
                L("[Loc] Location speed ✓");
            } catch (Throwable t) {}
        } else {
            L("[Loc] 定位模拟 未启用(开关关)，不安装钩子");
        }

        // ═══ 反模拟位置检测（仅开关开启时安装） ═══
        if (p("mt_anti_mock", false)) {
        try { Class<?> mt = cl.loadClass("com.meituan.android.common.locate.MtLocation"); hook(mt.getDeclaredMethod("isFromMockProvider")).intercept(c -> false); L("[Anti] MtLocation.isFromMockProvider ✓"); } catch (Throwable t) { Le("反模拟 MtLocation.isFromMockProvider", t); }
        try { hook(cl.loadClass("android.location.Location").getDeclaredMethod("isFromMockProvider")).intercept(c -> false); L("[Anti] isFromMockProvider ✓"); } catch (Throwable t) { Le("反模拟 isFromMockProvider", t); }
        try { hook(M(cl, "android.provider.Settings$Secure", "getInt", ContentResolver.class, String.class)).intercept(chain -> { if ("mock_location".equals(chain.getArgs().get(1))) return 0; return chain.proceed(); }); L("[Anti] mock_location ✓"); } catch (Throwable t) { Le("反模拟 mock_location", t); }
        try { Class<?> mlu = cl.loadClass("com.sankuai.meituan.pai.util.MockLocationUtils"); hook(mlu.getDeclaredMethod("isOpen", cl.loadClass("android.content.Context"))).intercept(c -> false); L("[Anti] MockLocationUtils ✓"); } catch (Throwable t) { Le("反模拟 MockLocationUtils", t); }
        try { hook(M(cl, "android.location.LocationManager", "isProviderEnabled", String.class)).intercept(chain -> { if ("mock".equals(chain.getArgs().get(0))) return false; return chain.proceed(); }); L("[Anti] isProviderEnabled ✓"); } catch (Throwable t) { Le("反模拟 isProviderEnabled", t); }
        logFeature("反模拟位置检测", true, "已安装5项钩子");
        } else {
            logFeature("反模拟位置检测", true, "未启用(开关关)，不安装钩子");
        }

        if (p("mt_update", false)) {
            try { hook(M(cl, "com.meituan.android.upgrade.UpgradeDialogActivity", "onCreate", Bundle.class)).intercept(chain -> { chain.proceed(); ((Activity) chain.getThisObject()).finish(); return null; }); L("[Upd] ✓"); } catch (Throwable t) { failed.add("更新"); }

            // ── 升级弹窗拦截 v2（DisableUpdate）──
            // 1. appupgrade.a.a(Context,String,String,boolean,OnClickListener,OnClickListener) 弹窗显示 → 跳过
            Class<?> upgradeAClass = findClassOrNull(cl, "com.meituan.banma.appupgrade.a");
            if (upgradeAClass != null) {
                hookAllMethodsBefore(upgradeAClass, "a", chain -> {
                    try {
                        if (chain.getArgs().size() == 6) {
                            L("[DisableUpdate] 已拦截: 升级提示弹窗");
                            return null;
                        }
                    } catch (Throwable e) {}
                    return chain.proceed();
                });
                L("[DisableUpdate] 已Hook: appupgrade.a (升级弹窗)");
            }

            // 2. main.model.r.b/c(UpdateInfo) 显示升级弹窗 → 跳过
            Class<?> rClass = findClassOrNull(cl, "com.meituan.banma.main.model.r");
            Class<?> updateInfoClass = findClassOrNull(cl, "com.meituan.banma.main.bean.UpdateInfo");
            if (rClass != null && updateInfoClass != null) {
                final Class<?> uiCls = updateInfoClass;
                hookAllMethodsBefore(rClass, "b", chain -> {
                    try {
                        if (chain.getArgs().size() == 1 && chain.getArgs().get(0) != null
                                && uiCls.isInstance(chain.getArgs().get(0))) {
                            L("[DisableUpdate] 已拦截: 升级弹窗(b)");
                            return null;
                        }
                    } catch (Throwable e) {}
                    return chain.proceed();
                });
                hookAllMethodsBefore(rClass, "c", chain -> {
                    try {
                        if (chain.getArgs().size() == 1 && chain.getArgs().get(0) != null
                                && uiCls.isInstance(chain.getArgs().get(0))) {
                            L("[DisableUpdate] 已拦截: 升级弹窗(c)");
                            return null;
                        }
                    } catch (Throwable e) {}
                    return chain.proceed();
                });
                L("[DisableUpdate] 已Hook: main.model.r (升级流程)");
            }

            // 3. 兜底：onUpdateOK（检查到新版本 → 不处理）
            if (rClass != null) {
                hookAllMethodsBefore(rClass, "onUpdateOK", chain -> {
                    L("[DisableUpdate] 已拦截: 更新检查完成");
                    return null;
                });
                L("[DisableUpdate] 已Hook: onUpdateOK (兜底)");
            }

            L("[DisableUpdate] 插件加载完成");
        }

        if (p("mt_watermark", false)) {
            try { hook(cl.loadClass("com.meituan.banma.base.common.ui.view.WaterMarkView").getDeclaredMethod("a")).intercept(c -> null); } catch (Throwable t) {}
            try { hook(cl.loadClass("com.meituan.banma.mutual.waterMark.view.WaterMarkView").getDeclaredMethod("a")).intercept(c -> null); } catch (Throwable t) {}
            try { Method e = cl.loadClass("com.meituan.banma.base.common.ui.view.WaterMarkView").getDeclaredMethod("e", Activity.class); e.setAccessible(true); hook(e).intercept(c -> null); } catch (Throwable t) {}
            try { hook(M(cl, "android.view.View", "setVisibility", int.class)).intercept(chain -> { if (chain.getThisObject().getClass().getName().contains("WaterMarkView")) return null; return chain.proceed(); }); } catch (Throwable t) {}
            try { Class<?> tv = cl.loadClass("android.widget.TextView"); XposedInterface.Hooker filter = chain -> { Object a = chain.getArgs().get(0); if (a instanceof CharSequence && (a.toString().contains("内测") || a.toString().contains("请勿分享"))) { chain.proceed(); try { chain.getThisObject().getClass().getMethod("setText", CharSequence.class).invoke(chain.getThisObject(), ""); } catch (Throwable ig) {} return null; } return chain.proceed(); }; try { hook(tv.getDeclaredMethod("setText", CharSequence.class)).intercept(filter); } catch (NoSuchMethodException ig) {} try { hook(tv.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class)).intercept(filter); } catch (Throwable ig) {} L("[WM] ✓"); } catch (Throwable t) {}
        }

        // ═══ 新功能：新任务页面自动刷新（智能版）═══
        // ★ 每 1 秒自动刷新，用户滑动页面时自动暂停 5 秒，5 秒内没继续滑动则恢复
        //   开关/间隔实时读取（mt_auto_refresh_home / mt_auto_refresh_delay），无需重启模块
        if (p("mt_auto_refresh_home", false)) {
        final long[] pauseUntil = {0L}; // 暂停截止时间（毫秒时间戳）
        final long PAUSE_MS = 5000L;
        L("[AutoRefreshTasks] >>> 安装新任务列表自动刷新（智能版：滑动自动暂停）");
        try {
            Class<?> hClass = findClassOrNull(cl, "com.meituan.banma.waybill.list.biz.h");
            if (hClass == null) {
                L("[AutoRefreshTasks] 错误: NewTasksBizModel(h) 未找到");
                if (p("mt_auto_refresh_home", false)) failed.add("自动刷新主页");
            } else {
                L("[AutoRefreshTasks] 已找到业务模型: " + hClass.getName());

                // ===== 检测用户滑动 → 暂停刷新 15 秒 =====
                // RecyclerView 双包名兼容（support / androidx）
                Class<?> rvClass = findClassOrNull(cl, "android.support.v7.widget.RecyclerView");
                if (rvClass == null) rvClass = findClassOrNull(cl, "androidx.recyclerview.widget.RecyclerView");

                if (rvClass != null) {
                    // 1. 触摸滑动（ACTION_MOVE）
                    hookAllMethodsBefore(rvClass, "dispatchTouchEvent", chain -> {
                        try {
                            Object ev = chain.getArgs().size() > 0 ? chain.getArgs().get(0) : null;
                            if (ev != null && ev instanceof android.view.MotionEvent
                                    && ((android.view.MotionEvent) ev).getAction() == android.view.MotionEvent.ACTION_MOVE) {
                                pauseUntil[0] = System.currentTimeMillis() + PAUSE_MS;
                            }
                        } catch (Throwable e) {}
                        return chain.proceed();
                    });
                    L("[AutoRefreshTasks] 已Hook: 触摸滑动检测");

                    // 2. 惯性滑动 fling（快速滑动松手后）
                    hookAllMethodsBefore(rvClass, "fling", chain -> {
                        pauseUntil[0] = System.currentTimeMillis() + PAUSE_MS;
                        return chain.proceed();
                    });
                    L("[AutoRefreshTasks] 已Hook: 惯性滑动检测");

                    // 3. 兜底：OnScrollListener.onScrolled / onScrollStateChanged
                    for (String onScrollName : new String[]{
                            "android.support.v7.widget.RecyclerView$OnScrollListener",
                            "androidx.recyclerview.widget.RecyclerView$OnScrollListener"}) {
                        Class<?> scrollListenerClass = findClassOrNull(cl, onScrollName);
                        if (scrollListenerClass != null) {
                            hookAllMethodsBefore(scrollListenerClass, "onScrolled", chain -> {
                                pauseUntil[0] = System.currentTimeMillis() + PAUSE_MS;
                                return chain.proceed();
                            });
                            hookAllMethodsBefore(scrollListenerClass, "onScrollStateChanged", chain -> {
                                pauseUntil[0] = System.currentTimeMillis() + PAUSE_MS;
                                return chain.proceed();
                            });
                            L("[AutoRefreshTasks] 已Hook: 滚动监听（兜底）" + onScrollName);
                        }
                    }
                }

                // ===== 自动刷新任务（滑动暂停时跳过） =====
                final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                Runnable refreshTask = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // ★ 滑动暂停中 → 跳过本轮刷新；开关实时生效
                            if (System.currentTimeMillis() >= pauseUntil[0]
                                    && p("mt_auto_refresh_home", false)) {
                                Object h = callAnyStatic(hClass, "a");
                                if (h != null) {
                                    callMethodExact(h, "a", new Class[]{Integer.TYPE}, 1);
                                }
                            }
                        } catch (Throwable e) {
                            L("[AutoRefreshTasks] 刷新异常: " + e);
                        }
                        // ★ 每次动态读取延迟配置（默认 1000ms，最小 500ms 防抖）
                        long cur = pi("mt_auto_refresh_delay", 1000);
                        if (cur < 500) { cur = 500L; }
                        handler.postDelayed(this, cur);
                    }
                };
                long first = pi("mt_auto_refresh_delay", 1000);
                if (first < 500) { first = 500L; }
                handler.postDelayed(refreshTask, first);
                L("[AutoRefreshTasks] 已启动: 间隔" + first + "ms，滑动自动暂停" + (PAUSE_MS / 1000) + "秒后恢复");
            }
        } catch (Throwable t) {
            if (p("mt_auto_refresh_home", false)) failed.add("自动刷新主页");
        }
        } else {
            L("[AutoRefreshTasks] 未启用(开关关)，不安装");
        }

        // ── 旧版"抢单确认按钮点击"(mt_skip_confirm)逻辑：已被"自动刷新主页"替代，条件恒false不再启用 ──
        if (p("mt_skip_confirm", false) && false) {
            L("[Grab] >>> 安装 (IDA逆向精确拦截)");

            // ═══ 策略A: 精确拦截 NewCanTakeWaybillDialog ═══
            try {
                Class<?> nctwClass = null;
                String fullName = "com.meituan.banma.account.view.NewCanTakeWaybillDialog";
                try { nctwClass = cl.loadClass(fullName); L("[Grab] A: 加载成功"); } catch (Throwable ignored) {}

                if (nctwClass != null) {
                    final Class<?> tc = nctwClass;
                    L("[Grab] A: 目标=" + tc.getName());

                    // Hook a() - 初始化UI
                    try {
                        Method aMethod = tc.getDeclaredMethod("a");
                        aMethod.setAccessible(true);
                        hook(aMethod).intercept(ch -> {
                            Object result = ch.proceed();
                            L("[Grab] ★ A: a()完成，延迟自动confirm...");
                            final Object dialog = ch.getThisObject();
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    // 方式1: 直接调用 onClickRight
                                    Method confirm = dialog.getClass().getDeclaredMethod("onClickRight");
                                    confirm.setAccessible(true);
                                    confirm.invoke(dialog);
                                    L("[Grab] ★★★ A: onClickRight自动confirm成功!");
                                } catch (Throwable t1) {
                                    try {
                                        // 方式2: 找btnDialogRight字段点击
                                        Field f = dialog.getClass().getDeclaredField("btnDialogRight");
                                        f.setAccessible(true);
                                        Object btn = f.get(dialog);
                                        if (btn instanceof android.view.View) {
                                            ((android.view.View) btn).performClick();
                                            L("[Grab] ★★★ A: btnDialogRightclick ok!");
                                        }
                                    } catch (Throwable t2) {
                                        // 方式3: 递归遍历视图树
                                        try {
                                            android.app.Dialog dlg = (android.app.Dialog) dialog;
                                            if (dlg.getWindow() != null) {
                                                autoClickConfirmButton(dlg.getWindow().getDecorView());
                                                L("[Grab] ★★★ A: 视图遍历点击完成");
                                            }
                                        } catch (Throwable t3) { L("[Grab] A全部失败: " + t3.getMessage()); }
                                    }
                                }
                            }, 500);
                            return result;
                        });
                        L("[Grab] A: hook a() ✓");
                    } catch (Throwable t) { L("[Grab] A: a() hook失败: " + t.getMessage()); }

                    // Hook onClickRight 记录confirm操作（透传，不拦截）
                    try {
                        Method or = tc.getDeclaredMethod("onClickRight");
                        or.setAccessible(true);
                        hook(or).intercept(ch -> {
                            L("[Grab] ★★★ onClickRight(confirm)被调用!");
                            return ch.proceed();
                        });
                        L("[Grab] A: hook onClickRight ✓");
                    } catch (Throwable t) { L("[Grab] A: onClickRight hook失败: " + t.getMessage()); }

                    L("[Grab] A(NewCanTakeWaybillDialog) 完成");
                } else {
                    L("[Grab] A: 未找到 " + fullName);
                }
            } catch (Throwable t) { Le("[Grab] A异常", t); }

            try {
                hook(android.app.Dialog.class.getDeclaredMethod("show"))
                        .intercept(ch -> {
                            Object result = ch.proceed();
                            try {
                                Object dlg = ch.getThisObject();
                                String cn = dlg.getClass().getName();
                                boolean isTarget = cn.contains("Waybill") || cn.contains("waybill")
                                        || cn.contains("CanTake") || cn.contains("Banma")
                                        || cn.contains("Confirm") || cn.contains("BmDialog")
                                        || cn.contains("Transfer") || cn.contains("transfer");

                                // 策略B2：如果dialog类名不匹配，尝试通过Activity上下文和按钮文本来识别
                                if (!isTarget && dlg instanceof android.app.Dialog) {
                                    android.app.Dialog d = (android.app.Dialog) dlg;
                                    android.content.Context ctx = d.getContext();
                                    if (ctx instanceof android.app.Activity) {
                                        String actName = ((android.app.Activity) ctx).getClass().getName();
                                        // 检查Activity是否与抢单/转单相关
                                        if (actName.contains("Waybill") || actName.contains("waybill")
                                                || actName.contains("CanTake") || actName.contains("Banma")
                                                || actName.contains("Transfer") || actName.contains("transfer")
                                                || actName.contains("Grab") || actName.contains("Confirm")
                                                || actName.contains("OrderConfirm") || actName.contains("TakeOrder")) {
                                            isTarget = true;
                                        }
                                    }

                                    // 策略B3：通过反射检查dialog标题是否包含转单确认文本
                                    if (!isTarget) {
                                        try {
                                            java.lang.reflect.Method getTitleMethod = d.getClass().getMethod("getTitle");
                                            getTitleMethod.setAccessible(true);
                                            Object titleObj = getTitleMethod.invoke(d);
                                            if (titleObj != null) {
                                                String titleStr = titleObj.toString();
                                                if (titleStr.contains("抢单") || titleStr.contains("转单")
                                                        || titleStr.contains("接单") || titleStr.contains("确认")
                                                        || titleStr.contains("抢") || titleStr.contains("接受")) {
                                                    isTarget = true;
                                                }
                                            }
                                        } catch (Throwable ig) {}
                                    }

                                    // 策略B4：检查dialog中是否有匹配CONFIRM_TEXTS的按钮
                                    if (!isTarget) {
                                        try {
                                            if (d.getWindow() != null) {
                                                android.view.View decor = d.getWindow().getDecorView();
                                                isTarget = hasConfirmButton(decor);
                                            }
                                        } catch (Throwable ig) {}
                                    }
                                }

                                if (isTarget) {
                                    L("[Grab] ★★ B: Dialog.show → " + cn);
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                        try {
                                            if (dlg instanceof android.app.Dialog) {
                                                android.app.Dialog d = (android.app.Dialog) dlg;
                                                if (d.getWindow() != null) autoClickConfirmButton(d.getWindow().getDecorView());
                                            }
                                            L("[Grab] ★ B: 自动点击完成");
                                        } catch (Throwable ig) { L("[Grab] B异常: " + ig.getMessage()); }
                                    }, 400);
                                }
                            } catch (Throwable ignored) {}
                            return result;
                        });
                L("[Grab] B(Dialog.show) ✓");
            } catch (Throwable t) { Le("[Grab] B异常", t); }

            try {
                String[][] fgs = {
                    {"androidx.fragment.app.DialogFragment", "androidx.fragment.app.FragmentManager"},
                    {"android.support.v4.app.DialogFragment", "android.support.v4.app.FragmentManager"},
                };
                for (String[] fc : fgs) {
                    try {
                        hook(cl.loadClass(fc[0]).getDeclaredMethod("show", cl.loadClass(fc[1]), String.class))
                                .intercept(ch -> {
                                    L("[Grab] ★ " + fc[0] + ".show");
                                    Object result = ch.proceed();
                                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                        try {
                                            android.app.Dialog dlg = (android.app.Dialog)
                                                    ch.getThisObject().getClass().getMethod("getDialog").invoke(ch.getThisObject());
                                            if (dlg != null && dlg.getWindow() != null) autoClickConfirmButton(dlg.getWindow().getDecorView());
                                        } catch (Throwable ig) {}
                                    }, 300);
                                    return result;
                                });
                        L("[Grab] C(" + fc[0] + ") ✓");
                    } catch (Throwable ig) {}
                }
            } catch (Throwable t) { Le("[Grab] C异常", t); }

            try {
                String bmMrnDialog = "com.meituan.banma.mrn.component.ui.BmMrnDialogActivity";
                Class<?> bmClass = cl.loadClass(bmMrnDialog);
                L("[Grab] D: 加载成功 " + bmClass.getName());

                Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                hook(onCreate).intercept(ch -> {
                    Object result = ch.proceed();
                    try {
                        Activity activity = (Activity) ch.getThisObject();
                        String clsName = activity.getClass().getName();
                        if (clsName.contains("BmMrnDialogActivity") || clsName.contains("MrnDialog")) {
                            L("[Grab] ★★ D: BmMrnDialogActivity.onCreate 事件驱动!");
                            int baseDelay = pi("mt_auto_refresh_delay", 0);
                            L("[Grab] D: 用户配置延迟=" + baseDelay + "ms (事件驱动模式)");

                            View decorView = activity.getWindow().getDecorView();
                            final long startTime = System.currentTimeMillis();
                            final int maxWait = 3000; // 最多等3秒

                            // ★ 核心：ViewTreeObserver事件驱动 —— RN每次布局变化自动触发
                            if (decorView.getViewTreeObserver().isAlive()) {
                                decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                                    new ViewTreeObserver.OnGlobalLayoutListener() {
                                        int attempt = 0;
                                        @Override
                                        public void onGlobalLayout() {
                                            long elapsed = System.currentTimeMillis() - startTime;
                                            if (elapsed > maxWait) {
                                                try { decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ig) {}
                                                return;
                                            }
                                            attempt++;
                                            try {
                                                autoClickConfirmInRN(decorView, 0, activity);
                                            } catch (Throwable t) { L("[Grab] D遍历异常: " + t.getMessage()); }
                                        }
                                    }
                                );
                                L("[Grab] D: ViewTreeObserver已注册 ✓");
                            }

                            // ★ 额外保障：post(0) now尝试一次（处理已渲染好的情况）
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try { autoClickConfirmInRN(decorView, 0, activity); } catch (Throwable t) {}
                            });

                            // ★ 如果用户设置了延迟，延迟后再触发一次
                            if (baseDelay > 0) {
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try { autoClickConfirmInRN(decorView, 0, activity); } catch (Throwable t) {}
                                }, baseDelay);
                            }
                        }
                    } catch (Throwable t) { L("[Grab] D: " + t.getMessage()); }
                    return result;
                });
                L("[Grab] D(BmMrnDialogActivity 事件驱动) ✓");
            } catch (Throwable t) { L("[Grab] D加载失败: " + t.getMessage()); }

            try {
                Method onCreateAll = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                hook(onCreateAll).intercept(ch -> {
                    Object result = ch.proceed();
                    try {
                        Activity activity = (Activity) ch.getThisObject();
                        String clsName = activity.getClass().getName();
                        // 匹配抢单confirm相关的Activity（含转单）
                        if (clsName.contains("CanTake") || clsName.contains("Confirm")
                                || clsName.contains("Waybill") || clsName.contains("Grab")
                                || clsName.contains("OrderConfirm") || clsName.contains("TakeOrder")
                                || clsName.contains("Transfer") || clsName.contains("transfer")) {
                            L("[Grab] ★ E: 兜底匹配 Activity=" + clsName);
                            int eDelay = pi("mt_auto_refresh_delay", 0);
                            L("[Grab] E: 配置延迟=" + eDelay + "ms (事件驱动)");

                            View decorView = activity.getWindow().getDecorView();
                            final long startTime = System.currentTimeMillis();
                            final int maxWait = 3000;

                            // ★ 事件驱动：ViewTreeObserver
                            if (decorView.getViewTreeObserver().isAlive()) {
                                decorView.getViewTreeObserver().addOnGlobalLayoutListener(
                                    new ViewTreeObserver.OnGlobalLayoutListener() {
                                        @Override
                                        public void onGlobalLayout() {
                                            long elapsed = System.currentTimeMillis() - startTime;
                                            if (elapsed > maxWait) {
                                                try { decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this); } catch (Throwable ig) {}
                                                return;
                                            }
                                            try {
                                                autoClickConfirmInRN(decorView, 0, activity);
                                            } catch (Throwable t) { L("[Grab] E遍历异常: " + t.getMessage()); }
                                        }
                                    }
                                );
                            }

                            // ★ now尝试一次
                            new Handler(Looper.getMainLooper()).post(() -> {
                                try { autoClickConfirmInRN(decorView, 0, activity); } catch (Throwable t) {}
                            });

                            // ★ 用户配置延迟后额外尝试
                            if (eDelay > 0) {
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    try { autoClickConfirmInRN(decorView, 0, activity); } catch (Throwable t) {}
                                }, eDelay);
                            }
                        }
                    } catch (Throwable ignored) {}
                    return result;
                });
                L("[Grab] E(全局兜底 事件驱动) ✓");
            } catch (Throwable t) { Le("[Grab] E异常", t); }

            L("[Grab] >>> 五层拦截安装完毕");
        }

        if (p("mt_screenshot", false)) {
            // 1. Window.setFlags 移除 FLAG_SECURE(0x2000)
            try { Method sf = M(cl, "android.view.Window", "setFlags", int.class, int.class); hook(sf).intercept(chain -> { int flags = (int) chain.getArgs().get(0), mask = (int) chain.getArgs().get(1); chain.getArgs().set(0, flags & ~0x2000); chain.getArgs().set(1, mask & ~0x2000); return chain.proceed(); }); L("[截屏] setFlags ✓"); } catch (Throwable t) { failed.add("截屏"); }
            // 2. Window.addFlags 移除 FLAG_SECURE（美团部分页面用 addFlags 加 FLAG_SECURE，需一并拦截）
            try { Method af = M(cl, "android.view.Window", "addFlags", int.class); hook(af).intercept(chain -> { int flags = (int) chain.getArgs().get(0); chain.getArgs().set(0, flags & ~0x2000); return chain.proceed(); }); L("[截屏] addFlags ✓"); } catch (Throwable t) { L("[截屏] addFlags 未找到"); }
            // 3. SurfaceView.setSecure 强制 false（解除地图/视频页的录屏与截图黑屏）
            try { Method ss = M(cl, "android.view.SurfaceView", "setSecure", boolean.class); hook(ss).intercept(chain -> { chain.getArgs().set(0, false); return chain.proceed(); }); L("[截屏] SurfaceView.setSecure ✓"); } catch (Throwable t) { L("[截屏] SurfaceView.setSecure 未找到"); }
            // 4. Window.setAttributes(LayoutParams) 剥离 FLAG_SECURE（防止直接改 attrs.flags 绕过 setFlags/addFlags）
            try {
                Class<?> lpClass = cl.loadClass("android.view.WindowManager$LayoutParams");
                Method sa = M(cl, "android.view.Window", "setAttributes", lpClass);
                hook(sa).intercept(chain -> {
                    try {
                        Object attrs = chain.getArgs().get(0);
                        if (attrs != null) {
                            Field fFlags = findFieldUp(attrs.getClass(), "flags");
                            if (fFlags != null) fFlags.setInt(attrs, fFlags.getInt(attrs) & ~0x2000);
                        }
                    } catch (Throwable ig) {}
                    return chain.proceed();
                });
                L("[截屏] setAttributes ✓");
            } catch (Throwable t) { L("[截屏] setAttributes 未找到: " + t.getMessage()); }
            // 5. 跳过 ForbidScreenshotUtil.b(Activity)（页面级 setFlags(FLAG_SECURE, FLAG_SECURE)）
            {
                boolean forbidHooked = false;
                String[] forbidCandidates = { "com.meituan.banma.base.common.ui.forbidScreenshot$b", "com.meituan.banma.base.common.ui.forbidScreenshot" };
                for (String cn : forbidCandidates) {
                    Class<?> forbidClazz = findClassOrNull(cl, cn);
                    if (forbidClazz == null) continue;
                    Method forbidMethod = null;
                    for (Method m : forbidClazz.getDeclaredMethods()) {
                        if ("b".equals(m.getName()) && m.getParameterTypes().length == 1 && Activity.class.isAssignableFrom(m.getParameterTypes()[0])) { forbidMethod = m; break; }
                    }
                    if (forbidMethod != null) {
                        final Method fm = forbidMethod;
                        hook(fm).intercept(chain -> null);
                        forbidHooked = true;
                        L("[截屏] 已跳过: ForbidScreenshotUtil.b(Activity) [" + cn + "]");
                        break;
                    }
                }
                if (!forbidHooked) L("[截屏] 提示: ForbidScreenshotUtil.b(Activity) 未找到（可能已混淆改名）");
            }
            // ★ 去除截屏动作检测：csi.utils.g 检测链路全拦截（注册监听/上报/停止监听/AsyncTask 兜底）
            try {
                Class<?> gClass = findClassOrNull(cl, "com.meituan.banma.csi.utils.g");
                if (gClass != null) {
                    // a() 注册 ContentObserver 监听 + a(aVar,str) 截图回调上报 → 全部跳过
                    hookAllMethodsBefore(gClass, "a", chain -> null);
                    // b() 停止监听 → 跳过（兜底）
                    hookAllMethodsBefore(gClass, "b", chain -> null);
                    L("[截屏] 已拦截: csi.utils.g 注册/上报/停止监听");
                } else {
                    L("[截屏] 警告: csi.utils.g 未找到（版本可能不同，跳过检测拦截）");
                }
                // 兜底：g$b AsyncTask 查询/确认截图 → 跳过
                Class<?> gbClass = findClassOrNull(cl, "com.meituan.banma.csi.utils.g$b");
                if (gbClass != null) {
                    hookAllMethodsBefore(gbClass, "onPostExecute", chain -> null);
                    hookAllMethodsBefore(gbClass, "doInBackground", chain -> null);
                    L("[截屏] 已拦截: g$b AsyncTask 截图确认（兜底）");
                }
            } catch (Throwable t) {
                Le("[截屏] 去除截屏检测异常", t);
            }
        }


        if (p("mt_dev_options", false)) {
            try { hook(M(cl, "android.provider.Settings$Global", "getInt", ContentResolver.class, String.class)).intercept(chain -> { if ("development_settings_enabled".equals(chain.getArgs().get(1))) return 0; return chain.proceed(); }); L("[开发者] ✓ (getInt 2参)"); } catch (Throwable t) { failed.add("开发者"); }
            // ★ 去除开发者选项检测：mutual.util.i.a() 弹窗入口 → 跳过（无参才拦，有参重载放行）
            try {
                Class<?> iClass = findClassOrNull(cl, "com.meituan.banma.mutual.util.i");
                if (iClass != null) {
                    hookAllMethodsBefore(iClass, "a", chain -> {
                        if (chain.getArgs().isEmpty()) return null;   // 无参 a() = 开发者选项检查弹窗入口 → 跳过
                        return chain.proceed();                        // 其他重载放行
                    });
                    L("[开发者] 已拦截: mutual.util.i.a() 安全风险弹窗");
                } else {
                    L("[开发者] 警告: mutual.util.i 未找到（版本可能不同）");
                }
            } catch (Throwable t) { Le("[开发者] mutual.util.i hook异常", t); }
            // ★ 兜底：Settings.Global.getInt 3参(ContentResolver,String,int) → development_settings_enabled 返回 0
            try {
                hook(M(cl, "android.provider.Settings$Global", "getInt", ContentResolver.class, String.class, int.class)).intercept(chain -> {
                    if ("development_settings_enabled".equals(chain.getArgs().get(1))) return 0;
                    return chain.proceed();
                });
                L("[开发者] ✓ (getInt 3参兜底)");
            } catch (Throwable t) { failed.add("开发者"); }
        }

        // ═══ 6. 防派单（mt_time_offset，★开关实时生效，无需重启美团） ═══
        {
            try { Class<?> td = cl.loadClass("com.meituan.banma.base.net.time.d"); Method tgt = null; for (Method m : td.getDeclaredMethods()) if ("a".equals(m.getName()) && m.getParameterTypes().length == 0) { tgt = m; break; } if (tgt != null) { hook(tgt).intercept(c -> { if (!p("mt_time_offset", false)) return c.proceed(); return System.currentTimeMillis() - 600000L; }); L("[防派单] ✓"); logFeature("防派单", true, "time.d.a(实时)"); } else { failed.add("防派单"); logFeature("防派单", false, "time.d.a 未找到"); } } catch (Throwable t) { failed.add("防派单"); Le("防派单", t); logFeature("防派单", false, t.getMessage()); }
        }

        // ═══ 7. 下线查看完整地址（ShowFullAddress：完整显示收货地址） ═══
        if (p("mt_online_check", false)) {
            // ① j.b() → false（强制显示完整地址）
            try {
                Class<?> jClass = findClassOrNull(cl, "com.meituan.banma.waybill.list.model.j");
                if (jClass != null) {
                    hookAllMethodsBefore(jClass, "b", chain -> false);
                    L("[下线查看] 已Hook: j.b() → false");
                }
            } catch (Throwable t) { failed.add("下线查看"); Le("online_check.j", t); }

            // ② delegate.a.a(String,String) → 直接返回收货地址
            try {
                Class<?> stringClass = findClassOrNull(cl, "java.lang.String");
                Class<?> delegateAClass = findClassOrNull(cl, "com.meituan.banma.waybill.delegate.a");
                if (delegateAClass != null && stringClass != null) {
                    hookAllMethodsBefore(delegateAClass, "a", chain -> {
                        try {
                            if (chain.getArgs().size() == 2
                                    && chain.getArgs().get(0) != null && chain.getArgs().get(1) != null
                                    && stringClass.isInstance(chain.getArgs().get(0))
                                    && stringClass.isInstance(chain.getArgs().get(1))) {
                                return chain.getArgs().get(1);
                            }
                        } catch (Throwable e) {}
                        return chain.proceed();
                    });
                    L("[下线查看] 已Hook: delegate.a.a");
                }
            } catch (Throwable t) { Le("online_check.delegate", t); }

            // ③ AddressView.setData → 把"上线"文本替换为完整收货地址
            try {
                Class<?> addressViewClass = findClassOrNull(cl, "com.meituan.banma.core.display.modules.AddressView");
                Class<?> dsClass = findClassOrNull(cl, "com.meituan.banma.waybill.repository.waybillDataSource.a");
                if (addressViewClass != null && dsClass != null) {
                    hookAllMethodsBefore(addressViewClass, "setData", chain -> {
                        try {
                            Object bean = chain.getArgs().size() > 0 ? chain.getArgs().get(0) : null;
                            if (bean == null) return chain.proceed();
                            Object eventInfoData = callAnyMethod(bean, "getEventInfoData");
                            Object wbId = (eventInfoData == null) ? null : callAnyMethod(eventInfoData, "getWaybillId");
                            Object wb = null;
                            if (wbId != null && wbId.toString().length() > 0) {
                                Object ds = callAnyStatic(dsClass, "a");
                                if (ds != null) wb = callAnyMethod(ds, "a", Long.parseLong(wbId.toString()));
                            }
                            if (wb == null) return chain.proceed();
                            Field recipientF = findFieldUp(wb.getClass(), "recipientAddress");
                            if (recipientF == null) return chain.proceed();
                            Object recipient = recipientF.get(wb);
                            if (recipient == null) return chain.proceed();
                            Object displayInfo = callAnyMethod(bean, "getDisplayInfo");
                            if (displayInfo != null) {
                                Field titleF = findFieldUp(displayInfo.getClass(), "title");
                                Field descF = findFieldUp(displayInfo.getClass(), "desc");
                                if (titleF != null) replaceOnlineText(titleF.get(displayInfo), recipient.toString());
                                if (descF != null) replaceOnlineText(descF.get(displayInfo), recipient.toString());
                                // ★ 性能：删除高频日志（每订单 4 次 Log.e+FileWriter 是滚动卡顿主因之一）
                            }
                        } catch (Throwable e) { L("[下线查看] AddressView异常: " + e); }
                        return chain.proceed();
                    });
                    L("[下线查看] 已Hook: AddressView.setData");
                }
            } catch (Throwable t) { Le("online_check.AddressView", t); }

            // ④ DistanceView$1.onClick → 路线控件改为打开任务地图
            try {
                Class<?> dv1Class = findClassOrNull(cl, "com.meituan.banma.core.display.modules.DistanceView$1");
                if (dv1Class != null) {
                    hookAllMethodsBefore(dv1Class, "onClick", chain -> {
                        try {
                            Field beanF = findFieldUp(chain.getThisObject().getClass(), "a");
                            if (beanF != null) {
                                Object bean = beanF.get(chain.getThisObject());
                                if (bean != null) {
                                    Object displayInfo = callAnyMethod(bean, "getDisplayInfo");
                                    if (displayInfo != null) {
                                        Object eventHandle = callAnyMethod(displayInfo, "getEventHandle");
                                        if (eventHandle != null) {
                                            callAnyMethod(eventHandle, "setModuleId", "event-operate-openTaskListMap");
                                            L("[下线查看] 路线控件→打开地图");
                                        }
                                    }
                                }
                            }
                        } catch (Throwable e) { L("[下线查看] 路线篡改异常: " + e); }
                        return chain.proceed();
                    });
                    L("[下线查看] 已Hook: DistanceView$1.onClick");
                }
            } catch (Throwable t) { Le("online_check.DistanceView", t); }

            // ⑤ core.events.c.h → 屏蔽"上线后"事件
            try {
                Class<?> cClass = findClassOrNull(cl, "com.meituan.banma.core.events.c");
                if (cClass != null) {
                    hookAllMethodsBefore(cClass, "h", chain -> {
                        try {
                            if (chain.getArgs().size() == 2 && chain.getArgs().get(1) != null
                                    && chain.getArgs().get(1) instanceof java.util.Map) {
                                Object content = ((java.util.Map<?, ?>) chain.getArgs().get(1)).get("content");
                                if (content != null && content.toString().contains("上线后")) {
                                    L("[下线查看] 屏蔽上线后事件");
                                    return null;
                                }
                            }
                        } catch (Throwable e) {}
                        return chain.proceed();
                    });
                    L("[下线查看] 已Hook: core.events.c.h");
                }
            } catch (Throwable t) { Le("online_check.events", t); }

            L("[下线查看] ShowFullAddress 插件加载完成");
            logFeature("下线查看完整地址", true, "ShowFullAddress");
        }

        // ═══ 8. 反检测 ═══
        try { Class<?> fm = cl.loadClass("com.meituan.android.common.fingerprint.FingerprintManager"); hook(fm.getDeclaredMethod("detection", cl.loadClass("android.content.Context"))).intercept(c -> ""); L("[反检测] detection ✓"); logFeature("反检测", true, "detection"); } catch (Throwable t) { Le("反检测 detection", t); logFeature("反检测", false, t.getMessage()); }
        try { Class<?> fm = cl.loadClass("com.meituan.android.common.fingerprint.FingerprintManager"); for (Method m : fm.getDeclaredMethods()) { if ("root".equals(m.getName()) && m.getParameterTypes().length == 0 && m.getReturnType() == int.class) { hook(m).intercept(c -> 0); L("[反检测] root ✓"); break; } } } catch (Throwable t) { Le("反检测 root", t); }

        // ═══ 9. 跳过二次抢单确认（NoConfirmDialogs） ═══
        if (p("mt_skip_grab_confirm", false)) {
            installSkipGrabConfirm(cl);
            logFeature("跳过二次抢单确认", true, "已安装");
        } else {
            logFeature("跳过二次抢单确认", true, "未启用(开关关)");
        }

        // ═══ 10. 自动抢单筛选（读取悬浮窗配置） ═══
        installAutoGrabFilter(cl);

        // ═══ 10. 后台静默获取完整订单列表（与自动抢单悬浮窗绑定） ═══
        installNewTaskData(cl);

        dumpFeatureSwitches();

        L("=== doHook 结束 失败=" + join(failed, ",") + (needCoord ? " 坐标未设置" : " 全部成功") + " ===");
    }

    // ═══════════ 自动抢单筛选 ═══════════
    // 读取悬浮窗写入的筛选配置，匹配到符合条件的订单时自动抢单
    private void installAutoGrabFilter(ClassLoader cl) {
        if (!p("grab_auto_enabled", false)) {
            L("[自动抢单] 未启用，跳过");
            return;
        }
        L("[自动抢单] >>> 安装订单筛选 + 延迟抢单");

        // Hook NewCombinationTaskItemView.setData(CombineWaybillBean) 来拦截订单展示
        try {
            Class<?> itemClass = cl.loadClass("com.meituan.banma.waybill.list.item.NewCombinationTaskItemView");
            for (Method m : itemClass.getDeclaredMethods()) {
                String name = m.getName();
                Class<?>[] params = m.getParameterTypes();
                if ("setData".equals(name) && params.length == 1) {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object view = chain.getThisObject();
                            Object bean = chain.getArgs().get(0);
                            if (bean != null && view != null) {
                                processOrderData(bean, view);
                            }
                        } catch (Throwable t) { L("[自动抢单] 处理异常: " + t.getMessage()); }
                        return result;
                    });
                    L("[自动抢单] hook setData ✓");
                    break;
                }
            }
        } catch (Throwable t) { Le("[自动抢单] itemClass加载失败", t); }

        L("[自动抢单] 筛选引擎安装完成");
    }

    // ═══════════ 后台静默获取完整订单列表（NewTaskData v15：真实回调 + ViewHolder 懒加载） ═══════════
    private void installNewTaskData(ClassLoader cl) {
        // ★ 采集功能独立于自动抢单：默认开启
        if (!p("collect_enabled", true)) {
            L("[新任务数据] 未启用（采集开关关闭），跳过");
            return;
        }
        L("[新任务数据] >>> 安装后台静默获取完整订单列表 (v15 真实回调)");

        final Class<?> beanClass = findClassOrNull(cl, "com.meituan.banma.waybill.list.bean.NewTaskListBean");
        final Class<?> gClass = findClassOrNull(cl, "com.meituan.banma.core.model.g");
        // ★ 双包名兼容：老版本 support / 新版本 androidx
        Class<?> rvTmp = findClassOrNull(cl, "android.support.v7.widget.RecyclerView");
        if (rvTmp == null) rvTmp = findClassOrNull(cl, "androidx.recyclerview.widget.RecyclerView");
        final Class<?> rvClass = rvTmp;
        final Class<?> stringClass = findClassOrNull(cl, "java.lang.String");
        final Class<?> baseActivityClass = findClassOrNull(cl, "com.meituan.banma.base.common.ui.BaseActivity");
        if (rvClass == null) L("[新任务数据] 警告: 未找到 RecyclerView(support/androidx)，懒加载将失效");
        if (beanClass == null) {
            L("[新任务数据] 未找到 NewTaskListBean，跳过");
            return;
        }

        // 状态变量（供回调与定时任务共享）
        final Object[] rvRef = {null};
        final int[] lastCount = {-1};
        final int[] stableCount = {0};
        final boolean[] loadDone = {false};
        final int[] lazyTries = {0};
        final boolean[] rvLogged = {false};
        final boolean[] rvFailLogged = {false};
        final int[] triggerCount = {0};
        final int[] lastTriggerCnt = {-1};
        final int[] triggerStable = {0};

        // ===== 0. 骑手已接订单（路线顺路参考）：Hook 进行中订单回调 =====
        try {
            Class<?> presCClass = findClassOrNull(cl, "com.meituan.banma.waybill.list.presenter.c");
            Class<?> respClass = findClassOrNull(cl, "com.meituan.banma.waybill.bizbean.MyDoingWaybillListResponse");
            if (presCClass != null && respClass != null) {
                final Class<?> rCls = respClass;
                XposedInterface.Hooker doingHandler = chain -> {
                    try {
                        if (chain.getArgs().size() == 1 && chain.getArgs().get(0) != null
                                && rCls.isInstance(chain.getArgs().get(0))) {
                            Object resp = chain.getArgs().get(0);
                            Object list = getFieldValue(resp.getClass(), resp, "waybillList");
                            riderOrders.clear();
                            if (list instanceof java.util.List) {
                                java.util.List<?> l = (java.util.List<?>) list;
                                L("[新任务数据] ===== 进行中订单 " + l.size() + " 单 =====");
                                for (Object wb : l) {
                                    if (wb == null) continue;
                                    try {
                                        Object id = getFieldValue(wb.getClass(), wb, "id");
                                        Object senderName = getFieldValue(wb.getClass(), wb, "senderName");
                                        Object sender = getFieldValue(wb.getClass(), wb, "senderAddress");
                                        Object recipient = getFieldValue(wb.getClass(), wb, "recipientAddress");
                                        Object income = getFieldValue(wb.getClass(), wb, "income");
                                        Object st = getFieldValue(wb.getClass(), wb, "status");
                                        String statusStr = "";
                                        if (st instanceof Number) {
                                            int s = ((Number) st).intValue();
                                            if (s == 10 || s == 15) statusStr = " [待取货]";
                                            else if (s == 20) statusStr = " [配送中]";
                                        }
                                        L("[新任务数据] 进行中 订单" + id + " 商家=" + senderName + " 金额=" + income + statusStr);
                                        L("[新任务数据]   取货: " + sender);
                                        L("[新任务数据]   送货: " + recipient);
                                    } catch (Throwable ignored) {}
                                    Object sLat = getFieldValue(wb.getClass(), wb, "senderLat");
                                    Object sLng = getFieldValue(wb.getClass(), wb, "senderLng");
                                    Object rLat = getFieldValue(wb.getClass(), wb, "recipientLat");
                                    Object rLng = getFieldValue(wb.getClass(), wb, "recipientLng");
                                    if (sLat != null && sLng != null && rLat != null && rLng != null
                                            && ((Number) sLat).longValue() != 0 && ((Number) rLat).longValue() != 0) {
                                        java.util.Map<String, Object> ro = new java.util.HashMap<>();
                                        ro.put("sLat", ((Number) sLat).longValue() / 1000000.0);
                                        ro.put("sLng", ((Number) sLng).longValue() / 1000000.0);
                                        ro.put("rLat", ((Number) rLat).longValue() / 1000000.0);
                                        ro.put("rLng", ((Number) rLng).longValue() / 1000000.0);
                                        riderOrders.add(ro);
                                    }
                                }
                                riderRouteGot = true;
                                L("[新任务数据] 骑手已接订单: " + riderOrders.size() + "单（路线顺路参考）");
                                logCollect("[新任务数据] 骑手已接订单: " + riderOrders.size() + "单（路线顺路参考）");
                            }
                        }
                    } catch (Throwable e) {
                        L("[新任务数据] 获取已接订单异常: " + e);
                    }
                    return chain.proceed();
                };
                hookAllMethodsBefore(presCClass, "a", doingHandler);
                hookAllMethodsBefore(presCClass, "b", doingHandler);
                L("[新任务数据] 已Hook: 进行中订单(待取货/配送中 + 路线参考 a/b)");
            } else {
                L("[新任务数据] 路线参考Hook未挂载: pres=" + presCClass + " resp=" + respClass);
            }
        } catch (Throwable t) { Le("[新任务数据] 进行中订单 hook异常", t); }

        // ===== 1. 按钮文字类型识别：抢单按钮文字 = 订单类型（转单/美团直送/帮买/帮送/1对1/路线任务/优享送等） =====
        try {
            Class<?> ozClass = findClassOrNull(cl, "com.meituan.banma.core.display.modules.OperateZoneBaseBtnView");
            Class<?> btnClass = findClassOrNull(cl, "com.meituan.banma.core.bean.display.BaseButtonBean");
            if (ozClass != null && btnClass != null) {
                final Class<?> btnC = btnClass;
                hookAllMethodsBefore(ozClass, "setData", chain -> {
                    try {
                        if (chain.getArgs().size() >= 2 && chain.getArgs().get(0) != null
                                && btnC.isInstance(chain.getArgs().get(0))) {
                            Object btn = chain.getArgs().get(0);
                            Object title = callAnyMethod(btn, "getTitle");
                            if (title != null) {
                                Object nt = callAnyMethod(title, "getNormalText");
                                String txt = (nt != null) ? nt.toString() : "";
                                // 按钮文字可能是模板+参数拼接（如 normalText="抢{type}单", params={type:美团直送}）
                                try {
                                    Object pms = callAnyMethod(title, "getParams");
                                    if (pms instanceof java.util.Map) {
                                        for (Object v : ((java.util.Map<?, ?>) pms).values()) {
                                            if (v != null) txt = txt + " " + v.toString();
                                        }
                                    }
                                } catch (Throwable e4) {}
                                if (txt.length() > 0) {
                                    Object wbId = null;
                                    try {
                                        wbId = callAnyMethod(chain.getArgs().get(1), "getWaybillId");
                                    } catch (Throwable e2) {}
                                    if (wbId == null) {
                                        try {
                                            Object wbl = callAnyMethod(chain.getArgs().get(1), "getWaybillIdList");
                                            if (wbl instanceof java.util.List && !((java.util.List<?>) wbl).isEmpty()) {
                                                wbId = ((java.util.List<?>) wbl).get(0);
                                            }
                                        } catch (Throwable e3) {}
                                    }
                                    if (txt.contains("接受转单")) {
                                        if (wbId != null) {
                                            transferOrderIds.put(wbId.toString(), true);
                                            logCollect("[新任务数据] 识别到转单按钮: 订单" + wbId);
                                            reLogOrder(wbId.toString());
                                        }
                                    }
                                    String bt = btnTypeFromText(txt);
                                    if (bt.length() > 0 && wbId != null) {
                                        String widS = wbId.toString();
                                        String lastBtnTxt = btnLastText.get(widS);
                                        if (lastBtnTxt == null || !lastBtnTxt.equals(txt)) {
                                            btnLastText.put(widS, txt);
                                            btnTypeCache.put(widS, bt);
                                            reLogOrder(widS);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {}
                    return chain.proceed();
                });
                L("[新任务数据] 已Hook: 按钮文字类型识别(转单/美团直送/帮买/帮送/1对1/路线任务/优享送等)");
            }
        } catch (Throwable t) { Le("[新任务数据] 按钮类型识别 hook异常", t); }

        // ===== 1.5 LableView 渲染后读取真实标签（避免模板误判） =====
        try {
            Class<?> lvClass = findClassOrNull(cl, "com.meituan.banma.core.display.modules.LableView");
            if (lvClass != null) {
                hookAllMethodsBefore(lvClass, "setData", chain -> {
                    Object ret = chain.proceed(); // 先执行 setData（渲染标签）再读取
                    try {
                        if (chain.getArgs().size() >= 1 && chain.getArgs().get(0) != null
                                && chain.getArgs().get(0).getClass().getName().contains("LableZoneBean")) {
                            Object lzb = chain.getArgs().get(0);
                            String wbId = "";
                            Object ei = getFieldValue(lzb.getClass(), lzb, "eventInfoData");
                            if (ei != null) {
                                Object t2 = callAnyMethod(ei, "getWaybillId");
                                if (t2 != null) wbId = t2.toString();
                            }
                            if (wbId.length() > 0) {
                                StringBuilder sb2 = new StringBuilder();
                                collectTexts(chain.getThisObject(), sb2);
                                String txt2 = sb2.toString();
                                if (txt2.length() > 0) {
                                    String lt2 = allTypeFromText(txt2);
                                    if (lt2.length() > 0) {
                                        String lastLblTxt = labelLastText.get(wbId);
                                        if (lastLblTxt == null || !lastLblTxt.equals(txt2)) {
                                            labelLastText.put(wbId, txt2);
                                            String oldCl = labelCache.get(wbId);
                                            if (oldCl != null && oldCl.length() > 0) {
                                                lt2 = dedupTypeStr(oldCl + " " + lt2);
                                            }
                                            labelCache.put(wbId, lt2);
                                            reLogOrder(wbId);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable e) {}
                    return ret;
                });
                L("[新任务数据] 已Hook: LableView(标签渲染文本)");
            } else {
                L("[新任务数据] LableView 未找到");
            }
        } catch (Throwable t) { Le("[新任务数据] LableView hook异常", t); }

        // ===== 2. Hook 新任务列表回调：提取 + 稳定检测 =====
        try {
            final Class<?> loaderClass = findClassOrNull(cl, "com.meituan.banma.core.page.list.loader.a");
            final Class<?> beanC = beanClass;
            Method m = null;
            if (loaderClass != null && stringClass != null) {
                m = findMethodUp(loaderClass, "a", int.class, int.class, String.class, int.class, beanClass);
            }
            if (m == null) {
                L("[新任务数据] 未找到列表回调");
                return;
            }
            hook(m).intercept(chain -> {
                Object r = chain.proceed();
                try {
                    if (chain.getArgs().size() == 5 && chain.getArgs().get(4) != null
                            && beanC.isInstance(chain.getArgs().get(4))) {
                        Object bean = chain.getArgs().get(4);
                        Object list = getFieldValue(bean.getClass(), bean, "list");
                        if (list instanceof java.util.List && !((java.util.List<?>) list).isEmpty()) {
                            java.util.List<?> l = (java.util.List<?>) list;
                            startCollectRefresh(l.size());
                            for (Object wb : l) {
                                dumpOrder(wb);
                                grabCollectedWaybill(cl, wb);
                            }
                            if (l.size() == lastCount[0]) {
                                stableCount[0]++;
                                if (stableCount[0] >= 2) {
                                    logCollect("[新任务数据] ***** 加载完成: 共 " + l.size() + " 单 *****");
                                    loadDone[0] = true;
                                }
                            } else {
                                lastCount[0] = l.size();
                                stableCount[0] = 0;
                            }
                        }
                    }
                } catch (Throwable e) {
                    L("[新任务数据] hook异常: " + e);
                }
                return r;
            });
            L("[新任务数据] 已Hook: 列表回调");
        } catch (Throwable t) { Le("[新任务数据] 列表回调 hook异常", t); }

        // ===== 3. Hook onLoadNextPageEvent：篡改 itemCount 为 0（绕过位置检查） =====
        if (gClass != null) {
            hookAllMethodsBefore(gClass, "onLoadNextPageEvent", chain -> {
                try {
                    if (chain.getArgs().size() == 4) {
                        chain.getArgs().set(3, 0);
                    }
                } catch (Throwable e) {}
                return chain.proceed();
            });
            L("[新任务数据] 已Hook: 懒加载入口");
        }

        // ===== 4. 定时用真实 ViewHolder 触发懒加载 =====
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable autoLoadTask = new Runnable() {
            @Override
            public void run() {
                // ★ 每次轮询按大厅实际列表同步 orders.log（重复保留/消失清空/新增添加）
                try { syncToAdapter(rvRef[0]); } catch (Throwable ignored) {}
                if (loadDone[0]) {
                    L("[新任务数据] 后台获取完成");
                    return;
                }
                try {
                    // 采集开关：关闭 → 停止
                    if (!p("collect_enabled", true)) {
                        L("[新任务数据] 采集已关闭，停止后台获取");
                        return;
                    }
                    if (rvRef[0] == null && baseActivityClass != null) {
                        Object activity = null;
                        try { activity = callAnyStatic(baseActivityClass, "getCurrentActivity"); } catch (Throwable t) { activity = null; }
                        if (activity != null) {
                            Object window = callAnyMethod(activity, "getWindow");
                            Object decorView = callAnyMethod(window, "getDecorView");
                            final Object[] rvFallback = {null};
                            rvRef[0] = findTaskRV(rvClass, decorView, rvFallback);
                            if (rvRef[0] == null && rvFallback[0] != null) {
                                rvRef[0] = rvFallback[0]; // 关键词不匹配 → 回退任意RV
                                if (!rvLogged[0]) {
                                    rvLogged[0] = true;
                                    try {
                                        Object ad = callAnyMethod(rvRef[0], "getAdapter");
                                        L("[新任务数据] 未匹配任务列表adapter，回退任意RV: " + (ad == null ? "null" : ad.getClass().getName()));
                                    } catch (Throwable t) { L("[新任务数据] 未匹配任务列表adapter，回退任意RV"); }
                                }
                            } else if (rvRef[0] != null) {
                                if (!rvLogged[0]) {
                                    rvLogged[0] = true;
                                    try {
                                        Object ad = callAnyMethod(rvRef[0], "getAdapter");
                                        L("[新任务数据] 已找到新任务列表 adapter=" + (ad == null ? "null" : ad.getClass().getName()));
                                    } catch (Throwable t) { L("[新任务数据] 已找到新任务列表"); }
                                }
                            }
                        } else {
                            // Activity 获取不到（可能不在前台）→ 提示一次
                            if (!rvFailLogged[0] && lazyTries[0] >= 3) {
                                rvFailLogged[0] = true;
                                L("[新任务数据] ⚠ getCurrentActivity 为空（页面不在前台，懒加载待页面激活后继续）");
                            }
                        }
                        lazyTries[0]++;
                        if (rvRef[0] == null && !rvFailLogged[0] && lazyTries[0] >= 5) {
                            rvFailLogged[0] = true;
                            L("[新任务数据] ⚠ 未找到新任务列表RV（懒加载将失效，仅采集当前页）");
                        }
                    }
                    if (rvRef[0] != null && gClass != null) {
                        Object rv = rvRef[0];
                        // ★ RV 失效检测：页面销毁/切走 → 清空引用下一轮重新查找
                        try {
                            Boolean attached = (Boolean) callAnyMethod(rv, "isAttachedToWindow");
                            if (attached == null || !attached.booleanValue()) {
                                rvRef[0] = null;
                                handler.postDelayed(this, 3000);
                                return;
                            }
                        } catch (Throwable t) {
                            rvRef[0] = null;
                            handler.postDelayed(this, 3000);
                            return;
                        }
                        Object adapter = callAnyMethod(rv, "getAdapter");
                        if (adapter == null) {
                            rvRef[0] = null;
                            handler.postDelayed(this, 3000);
                            return;
                        }
                        int cnt = ((Number) callAnyMethod(adapter, "getItemCount")).intValue();
                        // 触发稳定检测（cnt 连续 2 次相同 → 判定到底）
                        if (cnt == lastTriggerCnt[0]) {
                            triggerStable[0]++;
                            if (triggerStable[0] >= 2) {
                                if (!loadDone[0]) logCollect("[新任务数据] ***** 加载完成: 共 " + cnt + " 项 *****");
                                loadDone[0] = true;
                                handler.postDelayed(this, 5000);
                                return;
                            }
                        } else {
                            lastTriggerCnt[0] = cnt;
                            triggerStable[0] = 0;
                            loadDone[0] = false;
                        }
                        if (cnt > 0) {
                            int childCnt = ((Number) callAnyMethod(rv, "getChildCount")).intValue();
                            Object holder = null;
                            if (childCnt > 0) {
                                Object lastChild = callAnyMethod(rv, "getChildAt", childCnt - 1);
                                holder = callAnyMethod(rv, "getChildViewHolder", lastChild);
                            }
                            if (holder != null) {
                                Object gModel = callAnyStatic(gClass, "a");
                                if (gModel != null) {
                                    triggerCount[0]++;
                                    callAnyMethod(gModel, "onLoadNextPageEvent", "newWaybillList", false, holder, cnt);
                                    Object pos = callAnyMethod(holder, "getAdapterPosition");
                                    L("[新任务数据] 触发懒加载 #" + triggerCount[0] + " (cnt=" + cnt + ", pos=" + pos + ")");
                                } else {
                                    L("[新任务数据] 懒加载失败: gClass=" + gClass.getName() + " 静态a()为空");
                                }
                            } else {
                                // 无子 View（视图被回收）→ 先滚动到底部让 RV 生成子 View，下轮再触发
                                L("[新任务数据] 懒加载等待: childCnt=0, 滚动到底部 (cnt=" + cnt + ")");
                                try { callAnyMethod(rv, "scrollToPosition", cnt - 1); } catch (Throwable ignored) {}
                            }
                        }
                        handler.postDelayed(this, loadDone[0] ? 5000 : 3000);
                        return;
                    }
                } catch (Throwable t) {
                    L("[新任务数据] 触发异常: " + t.getMessage());
                }
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(autoLoadTask, 3000);

        L("[新任务数据] 已启动: 后台静默获取完整订单列表 (v15)");
    }

    // ═══════════ 新任务数据 v15 辅助 ═══════════
    /** 秒级时间戳 → HH:mm（本地时区） */
    private String fmtTS(int sec) {
        try {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(sec * 1000L);
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int m = cal.get(java.util.Calendar.MINUTE);
            return (h < 10 ? "0" + h : "" + h) + ":" + (m < 10 ? "0" + m : "" + m);
        } catch (Throwable e) { return "" + sec; }
    }

    /** HHmm 格式转 HH:mm（如 1230 → 12:30） */
    private String fmtHHMM(int v) {
        try {
            int h = v / 100, m = v % 100;
            return (h < 10 ? "0" + h : "" + h) + ":" + (m < 10 ? "0" + m : "" + m);
        } catch (Throwable e) { return "" + v; }
    }

    /** Haversine 距离（米） */
    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000.0;
        double dLat = (lat2 - lat1) * Math.PI / 180.0;
        double dLng = (lng2 - lng1) * Math.PI / 180.0;
        double a = Math.sin(dLat / 2.0) * Math.sin(dLat / 2.0)
            + Math.cos(lat1 * Math.PI / 180.0) * Math.cos(lat2 * Math.PI / 180.0)
            * Math.sin(dLng / 2.0) * Math.sin(dLng / 2.0);
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a));
        return R * c;
    }

    /** 方位角（0-360） */
    private double bearing(double lat1, double lng1, double lat2, double lng2) {
        double dLng = (lng2 - lng1) * Math.PI / 180.0;
        double lat1r = lat1 * Math.PI / 180.0;
        double lat2r = lat2 * Math.PI / 180.0;
        double x = Math.sin(dLng) * Math.cos(lat2r);
        double y = Math.cos(lat1r) * Math.sin(lat2r) - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLng);
        double b = Math.atan2(x, y) * 180.0 / Math.PI;
        return (b + 360.0) % 360.0;
    }

    /** 夹角差（0-180） */
    private double angleDiff(double a1, double a2) {
        double d = Math.abs(a1 - a2);
        if (d > 180.0) d = 360.0 - d;
        return d;
    }

    /** 获取骑手当前位置（美团定位 delegate.f.c()，一次获取缓存） */
    private void getRiderLoc() {
        if (riderGot) return;
        try {
            Class<?> fClass = findClassOrNull(KClassLoader, "com.meituan.banma.waybill.delegate.f");
            if (fClass != null) {
                Object locInfo = callAnyStatic(fClass, "c");
                if (locInfo != null) {
                    Object lat = callAnyMethod(locInfo, "getLatitude");
                    Object lng = callAnyMethod(locInfo, "getLongitude");
                    if (lat instanceof Number) riderLat = ((Number) lat).doubleValue();
                    if (lng instanceof Number) riderLng = ((Number) lng).doubleValue();
                    L("[新任务数据] 骑手位置: (" + riderLat + ", " + riderLng + ")");
                }
            }
        } catch (Throwable t) { L("[新任务数据] 获取定位异常: " + t.getMessage()); }
        riderGot = true;
    }

    /** 计算顺路度：Map(sl=顺路度, d1=最近距离, d2=配送距离) */
    private java.util.Map<String, Double> calcShunlu(Object wb) {
        try {
            Class<?> c = wb.getClass();
            double sLat = ((Number) getFieldValue(c, wb, "senderLat")).longValue() / 1000000.0;
            double sLng = ((Number) getFieldValue(c, wb, "senderLng")).longValue() / 1000000.0;
            double rLat = ((Number) getFieldValue(c, wb, "recipientLat")).longValue() / 1000000.0;
            double rLng = ((Number) getFieldValue(c, wb, "recipientLng")).longValue() / 1000000.0;
            double d2 = haversine(sLat, sLng, rLat, rLng);
            java.util.Map<String, Double> result = new java.util.HashMap<>();
            result.put("d2", d2);

            if (riderRouteGot && riderOrders.size() > 0) {
                getRiderLoc();
                double bestTake = 0.0, bestDeliver = 0.0, bestMinD = 999999.0;
                for (Object roObj : riderOrders) {
                    java.util.Map<?, ?> ro = (java.util.Map<?, ?>) roObj;
                    double osLat = ((Number) ro.get("sLat")).doubleValue();
                    double osLng = ((Number) ro.get("sLng")).doubleValue();
                    double orLat = ((Number) ro.get("rLat")).doubleValue();
                    double orLng = ((Number) ro.get("rLng")).doubleValue();
                    // 取货顺路度：距离 + 方向
                    double dA = haversine(sLat, sLng, osLat, osLng);
                    if (dA < bestMinD) bestMinD = dA;
                    double takeDistScore = 100.0 - dA / 30.0;
                    if (takeDistScore < 0) takeDistScore = 0.0;
                    double b1 = bearing(riderLat, riderLng, sLat, sLng);
                    double b2 = bearing(riderLat, riderLng, osLat, osLng);
                    double takeDirScore = 100.0 - angleDiff(b1, b2) / 1.5;
                    if (takeDirScore < 0) takeDirScore = 0.0;
                    double takeScore = 0.6 * takeDistScore + 0.4 * takeDirScore;
                    if (takeScore > bestTake) bestTake = takeScore;
                    // 送货顺路度：距离 + 方向
                    double dB = haversine(rLat, rLng, orLat, orLng);
                    double delDistScore = 100.0 - dB / 30.0;
                    if (delDistScore < 0) delDistScore = 0.0;
                    double b3 = bearing(sLat, sLng, rLat, rLng);
                    double b4 = bearing(osLat, osLng, orLat, orLng);
                    double delDirScore = 100.0 - angleDiff(b3, b4) / 1.5;
                    if (delDirScore < 0) delDirScore = 0.0;
                    double delScore = 0.5 * delDistScore + 0.5 * delDirScore;
                    if (delScore > bestDeliver) bestDeliver = delScore;
                }
                result.put("sl", (bestTake + bestDeliver) / 2.0);
                result.put("d1", bestMinD);
                return result;
            }
            // 无已有订单 → 用骑手当前位置
            getRiderLoc();
            if (riderLat == 0.0 && riderLng == 0.0) return null;
            double d1 = haversine(riderLat, riderLng, sLat, sLng);
            result.put("d1", d1);
            result.put("sl", (d1 + d2 <= 0) ? 0.0 : (1.0 - d1 / (d1 + d2)) * 100.0);
            return result;
        } catch (Throwable t) { return null; }
    }

    /** 遍历 View 树找新任务列表 RecyclerView（TaskListAdapter） */
    private Object findTaskRV(Class<?> rvClass, Object view, Object[] fallback) {
        if (view == null) return null;
        try {
            if (rvClass.isInstance(view)) {
                Object adapter = callAnyMethod(view, "getAdapter");
                if (adapter != null) {
                    if (fallback != null && fallback[0] == null) fallback[0] = view; // 记录任意RV作兜底
                    String an = adapter.getClass().getName();
                    String lower = an.toLowerCase();
                    // 放宽匹配：TaskList/NewTask/WaybillList 等新版类名（原只认 TaskListAdapter）
                    if (an.contains("TaskListAdapter") || lower.contains("tasklist")
                            || lower.contains("newtask") || lower.contains("waybilllist")) {
                        return view;
                    }
                }
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    Object r = findTaskRV(rvClass, vg.getChildAt(i), fallback);
                    if (r != null) return r;
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    /** 打印订单（v15：转单/预约/剩余时间/顺路度），并推送悬浮窗 */
    // ★ 采集数据只保留最近2次刷新（文件过滤用 collectSeq）+ 订单去重（时间窗，重复订单不二次写入/不重复记录日志）
    private static int collectSeq = 0;
    private static final long COLLECT_DEDUP_MS = 3L * 60L * 1000L; // 同一订单3分钟内只记录一次
    private static final java.util.Map<String, Long> collectedOrderTs = new java.util.HashMap<>();

    /** 订单去重：3分钟内已采集过 → 返回 true（跳过写入与日志）；首次遇到则登记 */
    private static boolean isCollectedDuplicate(String id) {
        if (id == null || "?".equals(id)) return false;
        long now = System.currentTimeMillis();
        Long t = collectedOrderTs.get(id);
        if (t != null && now - t < COLLECT_DEDUP_MS) return true;
        collectedOrderTs.put(id, now);
        // 防内存膨胀：超500条时清理过期记录
        if (collectedOrderTs.size() > 500) {
            java.util.Iterator<java.util.Map.Entry<String, Long>> it = collectedOrderTs.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() >= COLLECT_DEDUP_MS) it.remove();
            }
        }
        return false;
    }

    /** 采集核心 v15 增强：登记订单（缓存 + 记录出现时间 + 清标签缓存 + 防抖输出） */
    private void dumpOrder(Object wb) {
        try {
            if (wb == null) return;
            Class<?> c = wb.getClass();
            Object id = getFieldValue(c, wb, "id");
            String idStr = (id == null) ? "?" : id.toString();
            if ("?".equals(idStr)) return;
            try { wbCache.put(idStr, wb); } catch (Throwable ignored) {}
            try { lastSeen.put(idStr, System.currentTimeMillis()); } catch (Throwable ignored) {}
            try { labelCache.remove(idStr); } catch (Throwable ignored) {}
            try { labelLastText.remove(idStr); } catch (Throwable ignored) {}
            scheduleEmit(idStr);
        } catch (Throwable e) {
            L("[新任务数据] 提取异常: " + e);
        }
    }

    // ═══════════ 防抖单行输出（每个订单 800ms 内只输出一次） ═══════════
    private void scheduleEmit(String idS) {
        try {
            if (idS == null || idS.length() == 0) return;
            emitPending.put(idS, System.currentTimeMillis());
            if (!emitTimerRunning) {
                emitTimerRunning = true;
                emitHandler.postDelayed(emitTick, 800);
            }
        } catch (Throwable ignored) {}
    }

    private final java.lang.Runnable emitTick = new java.lang.Runnable() {
        @Override
        public void run() {
            try {
                emitTimerRunning = false;
                long now = System.currentTimeMillis();
                java.util.List<String> dueIds = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, Long> e : emitPending.entrySet()) {
                    if (e.getValue() != null && (now - e.getValue()) >= 800) dueIds.add(e.getKey());
                }
                for (String k : dueIds) {
                    emitPending.remove(k);
                    doEmitOnce(k);
                }
                if (emitPending.size() == 0) {
                    syncOrderFile();
                }
                if (emitPending.size() > 0 && !emitTimerRunning) {
                    emitTimerRunning = true;
                    emitHandler.postDelayed(emitTick, 800);
                }
            } catch (Throwable t) {
                emitTimerRunning = false;
            }
        }
    };

    private void doEmitOnce(String idS) {
        try {
            Object wb2 = wbCache.get(idS);
            if (wb2 == null) return;
            Class<?> c2 = wb2.getClass();
            Object id2 = getFieldValue(c2, wb2, "id");
            String id2S = (id2 == null) ? idS : id2.toString();
            String ts2 = currentTypeStr(wb2, id2S);
            String lastT = reLoggedTypes.get(id2S);
            if (lastT != null && lastT.equals(ts2)) return; // 类型没变不重复输出
            reLoggedTypes.put(id2S, ts2);
            emitOrder(wb2, id2S);
        } catch (Throwable ignored) {}
    }

    /** 输出一条完整订单行（单行整合 + 类型 + 坐标 + 顺路度），持久化 + 采集日志 + 浮窗推送 */
    private void emitOrder(Object wb, String idS) {
        try {
            if (wb == null) return;
            Class<?> c = wb.getClass();
            Object id2 = getFieldValue(c, wb, "id");
            String id2S = (id2 == null) ? idS : id2.toString();
            Object senderName2 = getFieldValue(c, wb, "senderName");
            if (senderName2 == null || senderName2.toString().length() == 0) {
                senderName2 = getFieldValue(c, wb, "senderAddress");
            }
            Object recipient2 = getFieldValue(c, wb, "recipientAddress");
            if (recipient2 == null || recipient2.toString().length() == 0) {
                recipient2 = getFieldValue(c, wb, "recipientName");
            }
            Object income2 = getFieldValue(c, wb, "income");
            if (income2 == null || !(income2 instanceof Number) || ((Number) income2).doubleValue() == 0) {
                income2 = getFieldValue(c, wb, "planChargeAmount");
            }
            if (income2 == null || !(income2 instanceof Number) || ((Number) income2).doubleValue() == 0) {
                try {
                    Object ti2 = getFieldValue(c, wb, "totalIncomeStr");
                    if (ti2 != null && ti2.toString().length() > 0) income2 = Double.parseDouble(ti2.toString());
                } catch (Throwable ignored) {}
            }
            if (income2 == null || !(income2 instanceof Number) || ((Number) income2).doubleValue() == 0) {
                try {
                    Object sgi2 = getFieldValue(c, wb, "similarGroupIncome");
                    if (sgi2 != null && ((Number) sgi2).doubleValue() > 0) income2 = sgi2;
                } catch (Throwable ignored) {}
            }
            if (income2 == null || !(income2 instanceof Number) || ((Number) income2).doubleValue() == 0) {
                try {
                    Object ai2 = getFieldValue(c, wb, "activityIncomeTotal");
                    if (ai2 != null && ai2.toString().length() > 0) income2 = Double.parseDouble(ai2.toString());
                } catch (Throwable ignored) {}
            }
            Object delDist2 = getFieldValue(c, wb, "deliveryDistance");
            Object fetchDist2 = getFieldValue(c, wb, "fetchDistance");
            String remaining2 = "?";
            Object assess2 = getFieldValue(c, wb, "riderAssessTime");
            long nowSec2 = System.currentTimeMillis() / 1000;
            if (assess2 != null) {
                try {
                    Object cd2 = getFieldValue(assess2.getClass(), assess2, "countDownEndTime");
                    Object ld2 = getFieldValue(assess2.getClass(), assess2, "latestDeliveryTime");
                    long rs2 = -1;
                    if (cd2 != null && ((Number) cd2).intValue() >= 0) rs2 = ((Number) cd2).intValue();
                    if (ld2 != null && ((Number) ld2).intValue() > 0) {
                        long rr2 = ((Number) ld2).intValue() - nowSec2;
                        if (rr2 > rs2) rs2 = rr2;
                    }
                    if (rs2 >= 0) {
                        remaining2 = (rs2 <= 0) ? "已过期" : (rs2 / 60) + "分" + (rs2 % 60) + "秒";
                    }
                } catch (Throwable ignored) {}
            }
            String ts2 = currentTypeStr(wb, id2S);
            String sCoord = "";
            Object sLat2 = getFieldValue(c, wb, "senderLat");
            Object sLng2 = getFieldValue(c, wb, "senderLng");
            if (sLat2 != null && sLng2 != null && ((Number) sLat2).longValue() != 0) {
                sCoord = (((Number) sLat2).longValue() / 1000000.0) + "," + (((Number) sLng2).longValue() / 1000000.0);
            }
            String rCoord = "";
            Object rLat2 = getFieldValue(c, wb, "recipientLat");
            Object rLng2 = getFieldValue(c, wb, "recipientLng");
            if (rLat2 != null && rLng2 != null && ((Number) rLat2).longValue() != 0) {
                rCoord = (((Number) rLat2).longValue() / 1000000.0) + "," + (((Number) rLng2).longValue() / 1000000.0);
            }
            String shunluStr = "";
            java.util.Map<String, Double> shunlu2 = calcShunlu(wb);
            if (shunlu2 != null) {
                double sl2 = shunlu2.get("sl");
                double d12 = shunlu2.get("d1");
                double d22 = shunlu2.get("d2");
                if (riderRouteGot && riderOrders.size() > 0) {
                    shunluStr = "顺路" + ((int) sl2) + "%(已有取货" + Math.round(d12 / 1000.0) + "km/送" + Math.round(d22 / 1000.0) + "km)";
                } else {
                    shunluStr = "顺路" + ((int) sl2) + "%(取货" + Math.round(d12 / 1000.0) + "km/送" + Math.round(d22 / 1000.0) + "km)";
                }
            }
            String orderLine = "订单: id=" + id2S + " 商家=" + senderName2 + " 送货=" + recipient2
                    + " 金额=" + income2 + " 距离=" + delDist2 + "米 取货距=" + fetchDist2 + "米"
                    + " 取货[" + sCoord + "] 送货[" + rCoord + "] 剩余=" + remaining2 + " " + shunluStr + " 类型:" + ts2;
            persistOrder(id2S, orderLine);
            logCollect("[新任务数据] " + orderLine);
            try {
                String shop = senderName2 != null ? senderName2.toString() : "";
                String inc = income2 != null ? income2.toString() : "0";
                String dd = delDist2 != null ? delDist2.toString() : "0";
                pushOrderToFloat(id2S, shop, inc, dd, remaining2);
            } catch (Throwable ignored) {}
        } catch (Throwable e) {
            logCollect("[新任务数据] emitOrder异常: " + e);
        }
    }

    // ═══════════ 订单类型识别 ═══════════
    private void addType(java.util.List<String> list, String tag) {
        try { if (!list.contains(tag)) list.add(tag); } catch (Throwable ignored) {}
    }
    private String tagText(Object el) {
        try {
            if (el == null) return "";
            if (el instanceof String) return (String) el;
            try {
                Object t = getFieldValue(el.getClass(), el, "content");
                if (t != null && t.toString().length() > 0) return t.toString();
            } catch (Throwable e2) {}
            try {
                Object ext = getFieldValue(el.getClass(), el, "extension");
                if (ext != null) {
                    Object tc = getFieldValue(ext.getClass(), ext, "tagContent");
                    if (tc != null && tc.toString().length() > 0) return tc.toString();
                    Object lbl = getFieldValue(ext.getClass(), ext, "labelNameList");
                    if (lbl != null) {
                        StringBuilder s = new StringBuilder();
                        for (Object x : (java.util.List<?>) lbl) { s.append(x.toString()).append(" "); }
                        if (s.length() > 0) return s.toString();
                    }
                }
            } catch (Throwable e3) {}
            try {
                Object t = getFieldValue(el.getClass(), el, "specialSymbol");
                if (t != null && t.toString().length() > 0) return t.toString();
            } catch (Throwable e4) {}
            try {
                Object t = getFieldValue(el.getClass(), el, "instruction");
                if (t != null && t.toString().length() > 0) return t.toString();
            } catch (Throwable e5) {}
            return el.toString();
        } catch (Throwable e) { return ""; }
    }
    private void appendTagList(StringBuilder sb, Object list) {
        try {
            if (list == null) return;
            for (Object el : (java.util.List<?>) list) {
                String t = tagText(el);
                if (t.length() > 0) { sb.append(t).append(" "); }
            }
        } catch (Throwable ignored) {}
    }
    private void appendTagStr(StringBuilder sb, Object v) {
        try {
            if (v != null) {
                String s = v.toString();
                if (s.length() > 0) { sb.append(s).append(" "); }
            }
        } catch (Throwable ignored) {}
    }
    private String extractTags(Object wb) {
        try {
            Class<?> c = wb.getClass();
            StringBuilder sb = new StringBuilder();
            appendTagList(sb, getFieldValue(c, wb, "waybillTags"));
            appendTagList(sb, getFieldValue(c, wb, "tags"));
            appendTagList(sb, getFieldValue(c, wb, "crowdTags"));
            appendTagList(sb, getFieldValue(c, wb, "subsidyWaybillTags"));
            Object v2 = getFieldValue(c, wb, "waybillTagV2");
            if (v2 != null) {
                Class<?> c2 = v2.getClass();
                appendTagList(sb, getFieldValue(c2, v2, "addressBelow"));
                appendTagList(sb, getFieldValue(c2, v2, "addressUpper"));
                appendTagList(sb, getFieldValue(c2, v2, "serviceType"));
                appendTagList(sb, getFieldValue(c2, v2, "subsidyIncome"));
            }
            appendTagStr(sb, getFieldValue(c, wb, "recommendTag"));
            appendTagStr(sb, getFieldValue(c, wb, "localSourceTag"));
            appendTagStr(sb, getFieldValue(c, wb, "subsidyTimeWaybillTagsDesc"));
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }
    private String wrapperListText(Object wb, String listName) {
        try {
            Object v2 = getFieldValue(wb.getClass(), wb, "waybillTagV2");
            if (v2 == null) return "";
            Object lst = getFieldValue(v2.getClass(), v2, listName);
            if (lst == null) return "";
            StringBuilder sb = new StringBuilder();
            for (Object el : (java.util.List<?>) lst) {
                String t = tagText(el);
                if (t.length() > 0) { sb.append(t).append(" "); }
            }
            return sb.toString();
        } catch (Throwable e) { return ""; }
    }
    private String calcTypeStr(Object wb) {
        try {
            Class<?> c = wb.getClass();
            java.util.List<String> typeList = new java.util.ArrayList<>();
            Object paotui = getFieldValue(c, wb, "paotuiDetail");
            if (paotui != null) {
                addType(typeList, "[跑腿]");
                Object bt = getFieldValue(paotui.getClass(), paotui, "businessType");
                if (bt != null) {
                    if (((Number) bt).intValue() == 2) addType(typeList, "[帮买]");
                    else if (((Number) bt).intValue() == 1) addType(typeList, "[帮送]");
                }
            }
            String stText = wrapperListText(wb, "serviceType");
            String stLow = stText.toLowerCase();
            if (stText.indexOf("美团直送") >= 0) addType(typeList, "[美团直送]");
            if (stText.indexOf("帮买") >= 0) addType(typeList, "[帮买]");
            if (stText.indexOf("帮送") >= 0) addType(typeList, "[帮送]");
            if (stText.indexOf("路线") >= 0) addType(typeList, "[路线任务]");
            if (stText.indexOf("1对1") >= 0 || stText.indexOf("一对一") >= 0 || stLow.indexOf("1v1") >= 0 || stText.indexOf("急送") >= 0 || stText.indexOf("专人") >= 0) addType(typeList, "[1对1急送]");
            if (stText.indexOf("外卖") >= 0) addType(typeList, "[外卖]");
            String abText = wrapperListText(wb, "addressBelow");
            if (abText.indexOf("同城") >= 0) addType(typeList, "[同城]");
            if (abText.indexOf("跑腿") >= 0) addType(typeList, "[跑腿]");
            if (abText.indexOf("优享送") >= 0) addType(typeList, "[优享送]");
            if (abText.indexOf("全城送") >= 0) addType(typeList, "[全城送]");
            String tags = extractTags(wb);
            String tagsLow = tags.toLowerCase();
            if (tags.indexOf("同城") >= 0) addType(typeList, "[同城]");
            if (tags.indexOf("全城送") >= 0) addType(typeList, "[全城送]");
            if (tags.indexOf("跑腿") >= 0) addType(typeList, "[跑腿]");
            if (tags.indexOf("美团直送") >= 0) addType(typeList, "[美团直送]");
            if (tags.indexOf("优享送") >= 0) addType(typeList, "[优享送]");
            if (tags.indexOf("帮买") >= 0) addType(typeList, "[帮买]");
            if (tags.indexOf("帮送") >= 0) addType(typeList, "[帮送]");
            if (tags.indexOf("路线") >= 0) addType(typeList, "[路线任务]");
            if (tags.indexOf("1对1") >= 0 || tags.indexOf("一对一") >= 0 || tagsLow.indexOf("1v1") >= 0 || tags.indexOf("急送") >= 0 || tags.indexOf("专人") >= 0) addType(typeList, "[1对1急送]");
            if (tags.indexOf("外卖") >= 0) addType(typeList, "[外卖]");
            Object udv = getFieldValue(c, wb, "urgentDeliveryView");
            if (udv != null) {
                Object ud = getFieldValue(udv.getClass(), udv, "urgentDelivery");
                if (ud != null && ((Number) ud).intValue() == 1) addType(typeList, "[1对1急送]");
            }
            StringBuilder typeStr = new StringBuilder();
            if (typeList.size() == 0) {
                typeStr.append(" [外卖]");
            } else {
                for (String t2 : typeList) typeStr.append(" ").append(t2);
            }
            return typeStr.toString();
        } catch (Throwable e) { return " [外卖]"; }
    }
    private String dedupTypeStr(String s) {
        try {
            if (s == null) return "";
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[[^\\]]+\\]");
            java.util.regex.Matcher m = p.matcher(s);
            java.util.Set<String> seen = new java.util.HashSet<>();
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String tag = m.group();
                if (!seen.contains(tag)) { seen.add(tag); sb.append(" ").append(tag); }
            }
            return sb.toString();
        } catch (Throwable e) { return s; }
    }
    private String currentTypeStr(Object wb, String idS) {
        try {
            String ts = calcTypeStr(wb);
            String cb = btnTypeCache.get(idS);
            if (cb != null && cb.length() > 0) {
                ts = (ts.trim().equals("[外卖]")) ? cb : ts + cb;
            }
            String cl = labelCache.get(idS);
            if (cl != null && cl.length() > 0) {
                ts = (ts.trim().equals("[外卖]")) ? cl : ts + cl;
            }
            Object booked2 = getFieldValue(wb.getClass(), wb, "booked");
            if (booked2 != null && ((Boolean) booked2).booleanValue()) ts = ts + " [预约]";
            if (transferOrderIds.containsKey(idS)) ts = ts + " [转单]";
            Object tsf = getFieldValue(wb.getClass(), wb, "transferStatus");
            if (tsf != null && ((Number) tsf).intValue() > 0) ts = ts + " [转单]";
            Object dtf = getFieldValue(wb.getClass(), wb, "directTransferStatus");
            if (dtf != null && ((Number) dtf).intValue() > 0) ts = ts + " [转单]";
            Object rti = getFieldValue(wb.getClass(), wb, "routeTaskId");
            if (rti != null && ((Number) rti).longValue() > 0) ts = ts + " [路线任务]";
            if (ts.indexOf("[外卖]") >= 0) {
                String wmRemoved = ts.replace("[外卖]", "").trim();
                if (wmRemoved.length() > 0) ts = " " + wmRemoved;
            }
            return dedupTypeStr(ts);
        } catch (Throwable e) { return " [外卖]"; }
    }

    /** 标签/按钮确认后：只安排一次防抖输出 */
    private void reLogOrder(String idS) {
        try { if (idS != null && idS.length() > 0) scheduleEmit(idS); } catch (Throwable ignored) {}
    }

    /** 按钮文字 → 类型（抢单按钮文字 = 订单类型） */
    private String btnTypeFromText(String txt) {
        try {
            String t = "";
            String low = txt.toLowerCase();
            if (txt.indexOf("美团直送") >= 0) t = t + " [美团直送]";
            if (txt.indexOf("优享送") >= 0) t = t + " [优享送]";
            if (txt.indexOf("帮买") >= 0) t = t + " [帮买]";
            if (txt.indexOf("帮送") >= 0) t = t + " [帮送]";
            if (txt.indexOf("1对1") >= 0 || txt.indexOf("一对一") >= 0 || low.indexOf("1v1") >= 0 || txt.indexOf("急送") >= 0 || txt.indexOf("专人") >= 0) t = t + " [1对1急送]";
            if (txt.indexOf("同城") >= 0) t = t + " [同城]";
            if (txt.indexOf("全城送") >= 0) t = t + " [全城送]";
            if (txt.indexOf("跑腿") >= 0) t = t + " [跑腿]";
            if (txt.indexOf("路线") >= 0) t = t + " [路线任务]";
            if (txt.indexOf("外卖") >= 0) t = t + " [外卖]";
            return t;
        } catch (Throwable e) { return ""; }
    }

    /** LableView 渲染后读取真实显示的标签文字 */
    private void collectTexts(Object view, StringBuilder sb) {
        try {
            if (view == null) return;
            android.view.View v = (android.view.View) view;
            if (v.getVisibility() == 8) return;
            if (v instanceof android.widget.TextView) {
                CharSequence t = ((android.widget.TextView) v).getText();
                if (t != null) {
                    String s = t.toString();
                    if (s.length() > 0 && sb.indexOf(s) < 0) { sb.append(s).append(" "); }
                }
            }
            if (v instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) v;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    collectTexts(vg.getChildAt(i), sb);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 标签文本 → 类型 */
    private String allTypeFromText(String txt) {
        try {
            String t = "";
            String low = txt.toLowerCase();
            if (txt.indexOf("1对1") >= 0 || txt.indexOf("一对一") >= 0 || low.indexOf("1v1") >= 0 || txt.indexOf("急送") >= 0 || txt.indexOf("专人") >= 0) t = t + " [1对1急送]";
            if (txt.indexOf("美团直送") >= 0) t = t + " [美团直送]";
            if (txt.indexOf("优享送") >= 0) t = t + " [优享送]";
            if (txt.indexOf("同城") >= 0) t = t + " [同城]";
            if (txt.indexOf("全城送") >= 0) t = t + " [全城送]";
            if (txt.indexOf("跑腿") >= 0) t = t + " [跑腿]";
            if (txt.indexOf("高价值") >= 0) t = t + " [高价值]";
            if (txt.indexOf("帮买") >= 0) t = t + " [帮买]";
            if (txt.indexOf("帮送") >= 0) t = t + " [帮送]";
            if (txt.indexOf("预约") >= 0) t = t + " [预约]";
            if (txt.indexOf("路线") >= 0) t = t + " [路线任务]";
            if (txt.indexOf("外卖") >= 0) t = t + " [外卖]";
            return t;
        } catch (Throwable e) { return ""; }
    }

    // ═══════════ 订单持久化文件（orders.log，每单一行原位更新） ═══════════
    private java.util.List<String> readOrderLines() {
        java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            java.io.File f = new java.io.File(ORDERS_LOG_PATH);
            if (f.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String s = br.readLine();
                while (s != null) { lines.add(s); s = br.readLine(); }
                br.close();
            }
        } catch (Throwable ignored) {}
        return lines;
    }
    private void writeOrderLines(java.util.List<String> lines) {
        try {
            java.io.File f = new java.io.File(ORDERS_LOG_PATH);
            java.io.File dir = f.getParentFile();
            if (!dir.exists()) dir.mkdirs();
            java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(f), "UTF-8"));
            for (String ln : lines) { bw.write(ln); bw.newLine(); }
            bw.close();
        } catch (Throwable e) { L("[新任务数据] writeOrderLines异常: " + e); }
    }
    private void persistOrder(String idS, String line) {
        try {
            if (idS == null || idS.length() == 0) return;
            java.util.List<String> lines = readOrderLines();
            boolean found = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).indexOf("id=" + idS + " ") >= 0) {
                    lines.set(i, line);
                    found = true;
                    break;
                }
            }
            if (!found) lines.add(line);
            writeOrderLines(lines);
        } catch (Throwable e) { L("[新任务数据] persistOrder异常: " + e); }
    }
    private void syncOrderFile() {
        try {
            if (lastSeen.size() == 0) return;
            long now = System.currentTimeMillis();
            java.util.List<String> lines = readOrderLines();
            java.util.List<String> newLines = new java.util.ArrayList<>();
            for (String ln : lines) {
                boolean keep = false;
                for (java.util.Map.Entry<String, Long> e : lastSeen.entrySet()) {
                    if (ln.indexOf("id=" + e.getKey() + " ") >= 0) {
                        Long t = e.getValue();
                        if (t != null && (now - t) < 600000) keep = true;
                        break;
                    }
                }
                if (keep) newLines.add(ln);
            }
            if (newLines.size() != lines.size()) writeOrderLines(newLines);
        } catch (Throwable ignored) {}
    }

    // ═══════════ 大厅列表同步（重复保留/消失清空/新增添加） ═══════════
    private String extractWbId(Object wcb) {
        try {
            if (wcb == null) return "";
            Object di = getFieldValue(wcb.getClass(), wcb, "displayInfo");
            if (di == null) return "";
            Object woul = getFieldValue(di.getClass(), di, "waybillOperateUnitList");
            if (woul != null) {
                for (Object wou : (java.util.List<?>) woul) {
                    if (wou == null) continue;
                    Object wdi = getFieldValue(wou.getClass(), wou, "displayInfo");
                    if (wdi == null) continue;
                    Object wtdi = getFieldValue(wdi.getClass(), wdi, "waybillTaskDisplayInfo");
                    if (wtdi == null) continue;
                    Object dis = getFieldValue(wtdi.getClass(), wtdi, "displayInfo");
                    if (dis != null) {
                        for (Object d : (java.util.List<?>) dis) {
                            Object wbid = getFieldValue(d.getClass(), d, "waybillId");
                            if (wbid != null && wbid.toString().length() > 0) return wbid.toString();
                        }
                    }
                }
            }
            try {
                Object ei2 = getFieldValue(wcb.getClass(), wcb, "eventInfoData");
                if (ei2 != null) {
                    Object wbId2 = callAnyMethod(ei2, "getWaybillId");
                    if (wbId2 != null && wbId2.toString().length() > 0) return wbId2.toString();
                }
            } catch (Throwable e6) {}
            return "";
        } catch (Throwable e) { return ""; }
    }
    private void syncToAdapter(Object rvRef) {
        try {
            if (rvRef == null) return;
            Object adapter = callAnyMethod(rvRef, "getAdapter");
            if (adapter == null) return;
            Object kList = getFieldValue(adapter.getClass(), adapter, "k");
            if (kList == null || ((java.util.List<?>) kList).size() == 0) return;
            java.util.Set<String> adapterIds = new java.util.HashSet<>();
            for (Object wcb : (java.util.List<?>) kList) {
                String wid = extractWbId(wcb);
                if (wid.length() > 0) adapterIds.add(wid);
            }
            if (adapterIds.size() == 0) return;
            java.util.List<String> lines = readOrderLines();
            java.util.List<String> newLines = new java.util.ArrayList<>();
            java.util.Set<String> keptIds = new java.util.HashSet<>();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("id=(\\d+) ");
            for (String ln : lines) {
                java.util.regex.Matcher m = p.matcher(ln);
                if (m.find()) {
                    String idIn = m.group(1);
                    if (adapterIds.contains(idIn)) { newLines.add(ln); keptIds.add(idIn); }
                } else {
                    newLines.add(ln);
                }
            }
            if (newLines.size() != lines.size()) writeOrderLines(newLines);
            for (String aid : adapterIds) {
                if (!keptIds.contains(aid)) {
                    Object wb2 = wbCache.get(aid);
                    // ★ 卡顿修复：emitOrder 含 su + 文件 I/O，改走后台防抖队列，不在主线程直接执行
                    if (wb2 != null) scheduleEmit(aid);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 采集器订单共享文件路径：写入当前进程缓存目录（无需su，100%可靠），悬浮窗进程用 su cat 读取 */
    private static String getOrdersPath() {
        String cache = getCacheDirPath();
        File dir = new File(cache, "lucky_orders");
        try { if (!dir.exists()) dir.mkdirs(); } catch (Throwable ignored) {}
        return new File(dir, "mt_orders.json").getAbsolutePath();
    }

    /** 采集器 → 悬浮窗：把订单摘要写入共享文件（精简：商家名+金额+距离+剩余；只保留最近30分钟） */
    private void pushOrderToFloat(String id, String shopName, String income, String delDist,
                                  String remaining) {
        try {
            long now = System.currentTimeMillis();
            String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
            // ★ 精简：商家名（默认取收货地址前12字兜底），不输完整地址
            String name = (shopName != null && !shopName.isEmpty())
                    ? shopName
                    : (id == null ? "?" : id);
            String line = "[" + ts + "] " + name
                    + " | " + (income == null || income.isEmpty() ? "?" : income) + "元"
                    + " | " + (delDist == null || delDist.isEmpty() ? "?" : delDist) + "米"
                    + " | 剩" + (remaining == null ? "?" : remaining);
            // 读取现有 JSON 数组
            org.json.JSONArray arr = new org.json.JSONArray();
            String path = getOrdersPath();
            try {
                String existing = readFileRoot(path);
                if (existing != null && !existing.trim().isEmpty()) arr = new org.json.JSONArray(existing);
            } catch (Throwable ignored) {}
            // ★ 只保留最近3分钟的订单（时间窗，跨进程/重启不失效；旧数据丢弃）
            org.json.JSONArray kept = new org.json.JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                try {
                    Object o = arr.get(i);
                    long t = 0;
                    if (o instanceof org.json.JSONObject) {
                        t = ((org.json.JSONObject) o).optLong("t", 0);
                    } else if (o instanceof String && !((String) o).isEmpty()) {
                        t = now; // 旧字符串条目无时间戳，本次保留
                    }
                    if (t <= 0 || now - t <= 3L * 60L * 1000L) kept.put(o);
                } catch (Throwable ignored) {}
            }
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("t", now);
            entry.put("seq", collectSeq);
            entry.put("id", id == null ? "" : id);
            entry.put("txt", line);
            kept.put(entry);
            arr = kept;
            // 保留最近50条
            while (arr.length() > 50) arr.remove(0);
            // ★ 直接写自身缓存目录（无需su，美团进程必然可写），并设为全局可读供悬浮窗 su cat
            File f = new File(path);
            java.io.FileWriter fw = new java.io.FileWriter(f, false);
            fw.write(arr.toString());
            fw.close();
            f.setReadable(true, false);
            // ★ 主通道：ContentResolver 跨进程推送到模块 App 的 OrdersProvider（无 su，100% 可靠）
            pushOrdersViaProvider(arr.toString());
            // 兼容：若本进程有 su 权限，额外写一份到 /data/local/tmp（悬浮窗可直接读）
            writeViaSu(ORDERS_PATH, arr.toString());
            // ★ 额外写一份到模块App自身缓存（浮窗同App可免su直读，路径1/2），保证订单展示
            try {
                String modCache = "/data/user/0/com.mtqsyqgx.app/cache/lucky_orders/mt_orders.json";
                File tmpF = new File(getTmpDir(), "su_orders_" + System.nanoTime() + ".tmp");
                FileWriter tfw = new FileWriter(tmpF);
                tfw.write(arr.toString());
                tfw.close();
                execCommand(new String[]{"su", "-c",
                        "mkdir -p /data/user/0/com.mtqsyqgx.app/cache/lucky_orders && cp " + tmpF.getAbsolutePath()
                                + " " + modCache + " && chmod 666 " + modCache});
                tmpF.delete();
            } catch (Throwable ignored) {}
            // ★ 最关键通道：写 sdcard（采集日志已证明美团进程可写sdcard；浮窗同App免su直读）
            try {
                final String jsonStr = arr.toString();
                SD_LOG_EXEC.execute(() -> {
                    try {
                        java.io.File dir = new java.io.File("/sdcard/Download/lucky/log");
                        if (dir.exists() || dir.mkdirs()) {
                            FileWriter fw3 = new FileWriter(new java.io.File(dir, "mt_orders.json"), false);
                            fw3.write(jsonStr);
                            fw3.close();
                        }
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        } catch (Throwable t) { Le("pushOrderToFloat", t); }
    }

    /** ★ 无 su 跨进程推送：美团进程 → 模块 App 的 OrdersProvider（com.mtqsyqgx.app）私有缓存 */
    private static boolean pushProviderFailLogged = false;
    private void pushOrdersViaProvider(String json) {
        try {
            Object at = Class.forName("android.app.ActivityThread")
                    .getDeclaredMethod("currentActivityThread").invoke(null);
            android.app.Application app = (android.app.Application)
                    at.getClass().getMethod("getApplication").invoke(at);
            if (app == null) return;
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("json", json);
            app.getContentResolver().call(
                    android.net.Uri.parse("content://com.mtqsyqgx.app.orders"),
                    "push", null, extras);
        } catch (Throwable t) {
            // 失败只记一次（该通道在部分机型不可用，sdcard 通道已兜底，避免刷日志）
            if (!pushProviderFailLogged) {
                pushProviderFailLogged = true;
                L("[pushOrdersViaProvider] fail: " + (t != null ? t.getMessage() : "null"));
            }
        }
    }

    /** 遍历 View 树找新任务列表 RecyclerView */
    // 采集器 RecyclerView 的 adapter 类名匹配（兼容多版本命名）
    private void grabCollectedWaybill(ClassLoader cl, Object wb) {
        try {
            if (wb == null) return;
            Class<?> c = wb.getClass();
            String idStr = safeGetField(c, wb, "id", "orderId", "waybillId", "billId");
            if (idStr == null || idStr.isEmpty()) return;
            if (grabbedIds.contains(idStr)) return; // 已抢过，去重

            JSONObject cfg = loadCfg();
            if (cfg == null) return;
            boolean autoOn = cfg.optBoolean("grab_auto_enabled", false);
            // ★ 自动抢单 关→开 时重置本轮计数（连抢关闭时"每次启动只抢一单"）
            if (autoOn && !lastAutoGrabEnabled) {
                grabCount = 0;
                continuousSkipLogged = false;
                logGrab("[自动抢单] 新一轮自动抢单启动，计数已重置 (连抢=" + cfg.optBoolean("grab_continuous", false) + ")");
            }
            lastAutoGrabEnabled = autoOn;
            if (!autoOn) return;
            // ★ 连抢模式：开启=持续抢符合筛选的订单直至上限；关闭=每次只抢一单
            boolean continuous = cfg.optBoolean("grab_continuous", false);
            if (!continuous && grabCount >= 1) {
                if (!continuousSkipLogged) {
                    continuousSkipLogged = true;
                    logGrab("[自动抢单] 连抢已关闭：本轮已抢 " + grabCount + " 单，只抢一次（需停止后重新启动继续）");
                }
                return;
            }

            // ── 提取订单字段（与 dumpWaybill 相同的采集字段）──
            double income = safeGetDoubleField(c, wb, "income", "price", "amount", "totalIncome", "actualIncome");
            double delDist = safeGetDoubleField(c, wb, "deliveryDistance", "distance", "totalDistance");
            double fetchLat = safeGetDoubleField(c, wb, "senderLat", "fetchLatitude", "fetchLat", "pickupLat");
            double fetchLng = safeGetDoubleField(c, wb, "senderLng", "fetchLongitude", "fetchLng", "pickupLng");
            double delLat = safeGetDoubleField(c, wb, "recipientLat", "deliveryLatitude", "deliveryLat");
            double delLng = safeGetDoubleField(c, wb, "recipientLng", "deliveryLongitude", "deliveryLng");
            String pickupAddr = safeGetField(c, wb, "senderAddress", "fetchAddress", "pickupAddress");
            String deliveryAddr = safeGetField(c, wb, "recipientAddress", "deliveryAddress", "deliverAddress");
            double onWayScore = safeGetDoubleField(c, wb, "onWay", "onWayScore", "matchScore", "orderMatchScore");

            // ★ 转单/路线任务：开关决定是否抢该类型；关闭则一律跳过（即使符合筛选），开启仍走常规筛选
            boolean isTransfer = false;
            try {
                String tsf = safeGetField(c, wb, "transferStatus", "directTransferStatus");
                if (tsf != null) { int tv = (int) Double.parseDouble(tsf.trim()); if (tv > 0) isTransfer = true; }
            } catch (Throwable ignored) {}
            if (!isTransfer && transferOrderIds != null && transferOrderIds.containsKey(idStr)) isTransfer = true;
            boolean isRoute = false;
            try {
                String rti = safeGetField(c, wb, "routeTaskId");
                if (rti != null && !rti.trim().isEmpty()) { long rv = (long) Double.parseDouble(rti.trim()); if (rv > 0) isRoute = true; }
            } catch (Throwable ignored) {}
            boolean grabTransfer = cfg.optBoolean("grab_transfer", true);
            boolean grabRoute = cfg.optBoolean("grab_route", true);
            if (isTransfer && !grabTransfer) {
                logGrab("[自动抢单] 跳过: 转单开关关闭 → " + idStr);
                return;
            }
            if (isRoute && !grabRoute) {
                logGrab("[自动抢单] 跳过: 路线任务开关关闭 → " + idStr);
                return;
            }

            // ══ 完整复用原有订单筛选类 shouldGrabOrder 的筛选逻辑（不改 UI）══
            // 1. 每公里价格筛选（启用时替代金额筛选）
            boolean usePerKm = cfg.optBoolean("grab_use_per_km", false);
            if (usePerKm) {
                if (delDist <= 0) {
                    logGrab("[自动抢单] 跳过: 距离为0，无法按每公里价格筛选 " + idStr);
                    return;
                }
                double perKm = income / (delDist / 1000.0);
                double minPK = safeParseDouble(cfg.optString("grab_min_per_km", ""), 0);
                if (minPK > 0 && perKm < minPK) {
                    logGrab("[自动抢单] 跳过: 每公里价格 " + perKm + " < " + minPK + " → " + idStr);
                    return;
                }
            } else {
                // 金额范围筛选
                double minA = safeParseDouble(cfg.optString("grab_min_amount", ""), 0);
                double maxA = safeParseDouble(cfg.optString("grab_max_amount", ""), 99999);
                if (minA > 0 && income < minA) {
                    logGrab("[自动抢单] 跳过: 金额 " + income + " < " + minA + " → " + idStr);
                    return;
                }
                if (maxA < 99999 && income > maxA) {
                    logGrab("[自动抢单] 跳过: 金额 " + income + " > " + maxA + " → " + idStr);
                    return;
                }
            }

            // 2. 取货地距离范围（grab_pickup_lat/lng + grab_pickup_range，单位 km）
            double tLat = safeParseDouble(cfg.optString("grab_pickup_lat", ""), 0);
            double tLng = safeParseDouble(cfg.optString("grab_pickup_lng", ""), 0);
            if (tLat != 0 && tLng != 0 && (fetchLat != 0 || fetchLng != 0)) {
                double pr = safeParseDouble(cfg.optString("grab_pickup_range", ""), 0);
                if (pr > 0) {
                    double dist = haversineCalc(tLat, tLng, fetchLat, fetchLng);
                    if (dist > pr) {
                        logGrab("[自动抢单] 跳过: 取货地超范围 " + dist + "km > " + pr + "km → " + idStr);
                        return;
                    }
                }
            }
            // 3. 取货地关键词（命中即跳过）
            String pkw = cfg.optString("grab_pickup_keywords", "");
            if (!pkw.isEmpty() && pickupAddr != null) {
                for (String kw : pkw.split("[,，]")) {
                    String kwT = kw.trim();
                    if (!kwT.isEmpty() && pickupAddr.contains(kwT)) {
                        logGrab("[自动抢单] 跳过: 取货地命中「" + kwT + "」→ " + idStr);
                        return;
                    }
                }
            }

            // 4. 送货地距离范围（grab_delivery_lat/lng + grab_delivery_range，单位 km）
            double dLat2 = safeParseDouble(cfg.optString("grab_delivery_lat", ""), 0);
            double dLng2 = safeParseDouble(cfg.optString("grab_delivery_lng", ""), 0);
            if (dLat2 != 0 && dLng2 != 0 && (delLat != 0 || delLng != 0)) {
                double dr = safeParseDouble(cfg.optString("grab_delivery_range", ""), 0);
                if (dr > 0) {
                    double dist = haversineCalc(dLat2, dLng2, delLat, delLng);
                    if (dist > dr) {
                        logGrab("[自动抢单] 跳过: 送货地超范围 " + dist + "km > " + dr + "km → " + idStr);
                        return;
                    }
                }
            }
            // 5. 送货地关键词（命中即跳过）
            String dkw = cfg.optString("grab_delivery_keywords", "");
            if (!dkw.isEmpty() && deliveryAddr != null) {
                for (String kw : dkw.split("[,，]")) {
                    String kwT = kw.trim();
                    if (!kwT.isEmpty() && deliveryAddr.contains(kwT)) {
                        logGrab("[自动抢单] 跳过: 送货地命中「" + kwT + "」→ " + idStr);
                        return;
                    }
                }
            }

            // 6. 顺路度筛选：仅在已抢到订单时生效
            if (grabCount > 0) {
                int minOnWay = cfg.optInt("grab_onway_min", 0);
                if (minOnWay > 0 && onWayScore < minOnWay) {
                    logGrab("[自动抢单] 跳过: 顺路度 " + onWayScore + " < " + minOnWay + " → " + idStr);
                    return;
                }
            }

            // 7. 最大抢单数限制
            int maxOrders = cfg.optInt("grab_max_orders", 0);
            if (maxOrders > 0 && grabCount >= maxOrders) {
                L("[自动抢单] 已达最大抢单数 " + maxOrders + "，停止抢单");
                return;
            }

            // 8. 间隔 2 秒抢一单（避免连续抢被限流）
            long now = System.currentTimeMillis();
            if (now - lastGrabTime < 2000) return;

            grabbedIds.add(idStr);
            lastGrabTime = now;
            logGrab("[自动抢单] ★★★ 采集器匹配成功: 订单=" + idStr + " 金额=" + income + " 距离=" + delDist + "m");

            // 9. 延迟算法（与原有 executeGrab 相同，复用 grab_delay_min/max/algo）
            int delayMin = cfg.optInt("grab_delay_min", 300);
            int delayMax = cfg.optInt("grab_delay_max", 1500);
            int algo = cfg.optInt("grab_delay_algo", 0);
            int delay = computeDelay(delayMin, delayMax, algo);
            L("[自动抢单] 匹配: " + idStr + " → " + delay + "ms (" + algoName(algo) + ")");

            final Object wbf = wb;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    logGrab("[自动抢单] ★ 执行抢单 [订单" + idStr + "]");
                    Class<?> grabClass = findClassOrNull(cl, "com.meituan.banma.waybill.coreflow.grab.a");
                    if (grabClass == null) { L("[自动抢单] 未找到 grab.a 类"); return; }
                    Object grabInstance = grabClass.getDeclaredConstructor().newInstance();
                    callAnyMethod(grabInstance, "a", wbf, 1);
                    grabCount++;
                    logGrab("[自动抢单] ★ 抢单调用成功! 计数: " + grabCount + (maxOrders > 0 ? "/" + maxOrders : ""));
                    signalGrab(idStr); // ★ 悬浮窗显示抢到哪个订单
                } catch (Throwable t) {
                    L("[自动抢单] 抢单调用异常: " + t.getMessage());
                }
            }, delay);
        } catch (Throwable t) {
            L("[自动抢单] 采集器抢单异常: " + t.getMessage());
        }
    }

    /** 处理单个订单数据，根据配置决定是否抢单 */
    private void processOrderData(Object bean, Object itemView) {
        try {
            JSONObject cfg = loadCfg();
            if (cfg == null) return;
            boolean autoOn = cfg.optBoolean("grab_auto_enabled", false);
            // ★ 自动抢单 关→开 时重置本轮计数（与 grabCollectedWaybill 同步）
            if (autoOn && !lastAutoGrabEnabled) {
                grabCount = 0;
                continuousSkipLogged = false;
                logGrab("[自动抢单] 新一轮自动抢单启动，计数已重置 (连抢=" + cfg.optBoolean("grab_continuous", false) + ")");
            }
            lastAutoGrabEnabled = autoOn;
            if (!autoOn) return;
            // ★ 连抢模式：关闭时每轮只抢一单
            boolean continuous = cfg.optBoolean("grab_continuous", false);
            if (!continuous && grabCount >= 1) {
                if (!continuousSkipLogged) {
                    continuousSkipLogged = true;
                    logGrab("[自动抢单] 连抢已关闭：本轮已抢 " + grabCount + " 单，只抢一次（需停止后重新启动继续）");
                }
                return;
            }

            // 通过反射读取订单字段
            Class<?> c = bean.getClass();
            String orderId = safeGetField(c, bean, "orderId", "id", "waybillId", "billId");
            double amount = safeGetDoubleField(c, bean, "price", "income", "amount", "totalIncome", "actualIncome");
            double distance = safeGetDoubleField(c, bean, "distance", "totalDistance", "km");
            double fetchLat = safeGetDoubleField(c, bean, "fetchLatitude", "fetchLat", "pickupLat");
            double fetchLng = safeGetDoubleField(c, bean, "fetchLongitude", "fetchLng", "pickupLng");
            double deliveryLat = safeGetDoubleField(c, bean, "deliveryLatitude", "deliveryLat");
            double deliveryLng = safeGetDoubleField(c, bean, "deliveryLongitude", "deliveryLng");

            if (orderId == null || orderId.isEmpty()) return;

            L("[自动抢单] 订单: " + orderId + " 金额=" + amount + " 距离=" + distance + "km");

            // ── 筛选逻辑 ──

            // 1. 指定订单ID
            String targetId = cfg.optString("grab_target_order_id", "");
            if (!targetId.isEmpty() && !targetId.equals(orderId)) {
                logGrab("[自动抢单] 跳过: 订单ID不匹配 " + targetId);
                return;
            }

            // 2. 每公里价格筛选 vs 金额筛选 (互斥)
            boolean usePerKm = cfg.optBoolean("grab_use_per_km", false);
            if (usePerKm) {
                if (distance <= 0) { logGrab("[自动抢单] 跳过: 距离为0"); return; }
                double perKm = amount / distance;
                double minPK = safeParseDouble(cfg.optString("grab_min_per_km", ""), 0);
                double maxPK = safeParseDouble(cfg.optString("grab_max_per_km", ""), 999);
                L("[自动抢单] 每公里=" + perKm + " 范围[" + minPK + "~" + maxPK + "]");
                if (perKm < minPK || perKm > maxPK) {
                    logGrab("[自动抢单] 跳过: 每公里价格不符合");
                    return;
                }
            } else {
                double minA = safeParseDouble(cfg.optString("grab_min_amount", ""), 0);
                double maxA = safeParseDouble(cfg.optString("grab_max_amount", ""), 99999);
                L("[自动抢单] 金额=" + amount + " 范围[" + minA + "~" + maxA + "]");
                if (amount < minA || amount > maxA) {
                    logGrab("[自动抢单] 跳过: 金额不符合");
                    return;
                }
            }

            // 3. 取货地/送货地范围
            double targetLat = safeParseDouble(cfg.optString("grab_target_lat", ""), 0);
            double targetLng = safeParseDouble(cfg.optString("grab_target_lng", ""), 0);
            if (targetLat != 0 && targetLng != 0) {
                double pickupRange = safeParseDouble(cfg.optString("grab_pickup_range", ""), 0);
                if (pickupRange > 0 && (fetchLat != 0 || fetchLng != 0)) {
                    double dist = haversineCalc(targetLat, targetLng, fetchLat, fetchLng);
                    if (dist > pickupRange) {
                        logGrab("[自动抢单] 跳过: 取货地超出范围 " + dist + "km > " + pickupRange + "km");
                        return;
                    }
                }
                double deliveryRange = safeParseDouble(cfg.optString("grab_delivery_range", ""), 0);
                if (deliveryRange > 0 && (deliveryLat != 0 || deliveryLng != 0)) {
                    double dist = haversineCalc(targetLat, targetLng, deliveryLat, deliveryLng);
                    if (dist > deliveryRange) {
                        logGrab("[自动抢单] 跳过: 送货地超出范围 " + dist + "km > " + deliveryRange + "km");
                        return;
                    }
                }
            }

            // ── 符合条件! 执行延迟抢单 ──
            int delayMin = cfg.optInt("grab_delay_min", 300);
            int delayMax = cfg.optInt("grab_delay_max", 1500);
            int algo = cfg.optInt("grab_delay_algo", 0);
            int delay = computeDelay(delayMin, delayMax, algo);

            L("[自动抢单] ★★★ 符合条件! 订单=" + orderId + " 延迟=" + delay + "ms 算法=" + algoName(algo));

            // 检查最大抢单数限制
            int maxOrders = cfg.optInt("grab_max_orders", 0);
            if (maxOrders > 0 && grabCount >= maxOrders) {
                L("[自动抢单] ★ 已达最大抢单数限制 " + maxOrders + " 单，停止抢单");
                return;
            }

            // 延迟后执行抢单 — 查找并点击抢单按钮
            final Object targetView = itemView;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    logGrab("[自动抢单] ★★★ 执行抢单: " + orderId);
                    // 优先尝试直接调用 onRightButtonClick
                    boolean success = false;
                    try {
                        Method clickMethod = targetView.getClass().getMethod("onRightButtonClick");
                        clickMethod.setAccessible(true);
                        clickMethod.invoke(targetView);
                        success = true;
                        L("[自动抢单] ★ onRightButtonClick 已调用");
                    } catch (Throwable t1) {
                        L("[自动抢单] onRightButtonClick 不可用: " + t1.getMessage());
                    }
                    // 备选: 在View树中查找并点击抢单按钮
                    if (!success && targetView instanceof View) {
                        success = findAndClickGrabButton((View) targetView);
                    }
                    if (success) {
                        grabCount++;
                        logGrab("[自动抢单] ★ 抢单成功! 计数: " + grabCount + (maxOrders > 0 ? "/" + maxOrders : ""));
                        signalGrab(orderId); // ★ 悬浮窗显示抢到哪个订单
                    } else {
                        logGrab("[自动抢单] ★ 抢单失败: 未找到可点击的抢单按钮");
                    }
                } catch (Throwable t) { L("[自动抢单] 抢单执行失败: " + t.getMessage()); }
            }, delay);

        } catch (Throwable t) { L("[自动抢单] processOrderData异常: " + t.getMessage()); }
    }

    /** 在View树中递归查找"抢单"/"接单"/"抢"按钮并点击 */
    private boolean findAndClickGrabButton(View view) {
        if (view == null) return false;
        try {
            if (view instanceof android.widget.Button || view instanceof android.widget.TextView) {
                String text = "";
                if (view instanceof android.widget.TextView) {
                    text = ((android.widget.TextView) view).getText().toString();
                }
                if (text.contains("抢单") || text.contains("接单") || text.contains("抢") || text.contains("取")) {
                    L("[自动抢单] 找到抢单按钮: " + text);
                    view.performClick();
                    return true;
                }
            }
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) view;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    if (findAndClickGrabButton(vg.getChildAt(i))) return true;
                }
            }
        } catch (Throwable t) {}
        return false;
    }

    /** 反射安全读取String字段 */
    private String safeGetField(Class<?> c, Object obj, String... names) {
        for (String name : names) {
            try {
                // 直接字段
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v != null) return v.toString();
                } catch (NoSuchFieldException ig) {}
                // 尝试在父类中查找
                Class<?> p = c.getSuperclass();
                while (p != null && p != Object.class) {
                    try {
                        Field f = p.getDeclaredField(name);
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) return v.toString();
                    } catch (NoSuchFieldException ig2) {}
                    p = p.getSuperclass();
                }
                // 尝试getter方法
                String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
                try {
                    Method m = c.getMethod(getter);
                    Object v = m.invoke(obj);
                    if (v != null) return v.toString();
                } catch (NoSuchMethodException ig3) {}
            } catch (Throwable ig4) {}
        }
        return null;
    }

    /** 反射安全读取double字段 */
    private double safeGetDoubleField(Class<?> c, Object obj, String... names) {
        String val = safeGetField(c, obj, names);
        return safeParseDouble(val, 0);
    }

    private double safeParseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    /** Haversine距离公式 */
    private double haversineCalc(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon/2) * Math.sin(dLon/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 反射取字段值（含父类，供坐标读取） */
    private Object getFieldValue(Class<?> c, Object obj, String name) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ig) {} catch (Throwable t) { return null; }
            cur = cur.getSuperclass();
        }
        return null;
    }

    /**
     * 随机延迟算法（优化版：人类行为模拟 + 防连续相同延迟）
     * - 复用同一随机源（线程安全），避免每次 new Random 造成的同种子连号特征
     * - 每种算法都带"尾部加重"，减少恰好落在边界整数的机器特征
     * - 若与上次延迟完全相同，自动 ±5% 抖动，防时序特征被识别
     */
    private int computeDelay(int min, int max, int algo) {
        if (min >= max) return min;
        int range = max - min;
        int d;
        switch (algo) {
            case 1: { // 高斯分布：集中中段模拟自然操作，30% 概率完全随机防过于集中
                double mid = (min + max) / 2.0;
                double sigma = Math.max(range / 5.0, 1);
                double u1 = Math.max(delayRand.nextDouble(), 1e-9);
                double u2 = delayRand.nextDouble();
                double z = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
                d = (int)(mid + z * sigma);
                if (delayRand.nextInt(100) < 30) d = min + delayRand.nextInt(Math.max(1, range));
                break;
            }
            case 2: { // 双峰分布：偏好两端（快抢/稳妥各 45%），中间窄带过渡
                double rnd = delayRand.nextDouble();
                if (rnd < 0.45) d = min + delayRand.nextInt(Math.max(1, (int)(range * 0.25)));
                else if (rnd > 0.55) d = max - delayRand.nextInt(Math.max(1, (int)(range * 0.25)));
                else d = min + (int)(range * 0.45) + delayRand.nextInt(Math.max(1, (int)(range * 0.1)));
                break;
            }
            case 3: { // 突发+间隔：18% 快抢、12% 中段、70% 慢速——多数走慢速防频繁触发检测
                double rnd = delayRand.nextDouble();
                if (rnd < 0.18) d = min + delayRand.nextInt(Math.max(1, (int)(range * 0.15)));
                else if (rnd < 0.30) d = min + (int)(range * 0.5) + delayRand.nextInt(Math.max(1, (int)(range * 0.3)));
                else d = min + (int)(range * 0.6) + delayRand.nextInt(Math.max(1, (int)(range * 0.35)));
                break;
            }
            default: { // 均匀随机：15% 尾部加重，其余集中在中段 80% 区间（避开边界整值）
                if (delayRand.nextInt(100) < 15) {
                    d = delayRand.nextBoolean()
                        ? min + delayRand.nextInt(Math.max(1, (int)(range * 0.2)))
                        : max - delayRand.nextInt(Math.max(1, (int)(range * 0.2)));
                } else {
                    d = min + (int)(range * 0.1) + delayRand.nextInt(Math.max(1, (int)(range * 0.8)));
                }
                break;
            }
        }
        d = clamp(d, min, max);
        // ★ 防重复特征：与上次完全相同时 ±5% 抖动
        if (lastDelay > 0 && d == lastDelay) {
            int jitter = Math.max(1, (int)(range * 0.05));
            d = clamp(d + (delayRand.nextBoolean() ? jitter : -jitter), min, max);
        }
        lastDelay = d;
        return d;
    }

    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    private String algoName(int a) {
        switch(a) { case 1: return "高斯"; case 2: return "双峰"; case 3: return "突发间隔"; default: return "均匀随机"; }
    }

    /** 写入抢单信号文件 — 直接写入缓存目录 */
    /** 写入抢单成功信号（/data/local/tmp/mt_grab_signal.json + 自身缓存），悬浮窗轮询显示"抢到哪个订单" */
    private void writeSignalFile(String content) {
        try {
            // ★ 主通道：/data/local/tmp（world-readable，悬浮窗可直接读）
            writeViaSu(GRAB_SIGNAL_PATH, content);
            // 兜底：自身缓存目录
            ensureLogPath();
            if (LOG_DIR != null) {
                java.io.File sf = new java.io.File(LOG_DIR, "mt_grab_signal.json");
                java.io.FileWriter fw = new java.io.FileWriter(sf, false);
                fw.write(content);
                fw.close();
            }
        } catch (Exception ignored) {}
    }

    /** 抢单成功 → 写信号（订单id + 时间），悬浮窗显示"已抢到: xxx" */
    private void signalGrab(String orderId) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("id", orderId == null ? "?" : orderId);
            j.put("time", System.currentTimeMillis());
            j.put("txt", "★ 已抢到订单: " + (orderId == null ? "?" : orderId));
            writeSignalFile(j.toString());
        } catch (Throwable ignored) {}
    }

    // ═══════════ 禁止超速检测辅助（v3：保护范围判断） ═══════════
    /** ★ 性能：getStackTrace 结果缓存（同一调用点高频调 getSpeed 时，短窗口内调用者不变，避免每次展开整个调用栈） */
    private static volatile long lastStackTime = 0;
    private static volatile boolean lastStackProtected = false;

    /** 判断调用栈是否处于"保护范围"（地图/导航SDK、美团定位SDK内部 → 不拦截速度） */
    private boolean isSpeedCallerProtected() {
        long now = System.currentTimeMillis();
        if (now - lastStackTime < 50) return lastStackProtected;
        lastStackTime = now;
        try {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int i = 2; i < stack.length && i < 25; i++) {
                String s = stack[i].toString();
                if (s.contains("com.tencent.map") || s.contains("com.tencent.tencentmap")
                        || s.contains("com.tencent.lbs") || s.contains("com.tencent.mapsdk")
                        || s.contains("com.tencent.mapcore") || s.contains("com.baidu")
                        || s.contains("com.amap") || s.contains("com.autonavi")
                        // 美团导航/地图SDK
                        || s.contains("com.meituan.sankuai.map") || s.contains("com.meituan.sankuai.mtnavisdk")
                        || s.contains("com.meituan.sankuai.navisdk") || s.contains("com.meituan.sankuai.navi")
                        // 美团定位SDK内部（导航可能通过它获取定位/速度）
                        || s.contains("com.meituan.android.common.locate")
                        || s.contains("com.meituan.android.common.pos")) {
                    lastStackProtected = true;
                    return true;
                }
            }
        } catch (Throwable e) {}
        lastStackProtected = false;
        return false;
    }

    // ═══════════ 跳过二次抢单确认（NoConfirmDialogs） ═══════════
    // 1) 抢单确认弹窗：core.operation 步骤组件 c() 显示确认弹窗
    //    → Hook 组件 c()，跳过弹窗，直接回调 e.a(String,String,String)"确认"让流程继续
    // 2) 转单确认弹窗：directTransfer.e.b(long) 显示"确认接受转单"弹窗
    //    → Hook 后直接调用私有方法 f(long)（官方新流程直接接受）
    private void installSkipGrabConfirm(ClassLoader cl) {
        L("[NoConfirmDialogs] >>> 安装 跳过二次抢单确认");
        Class<?> eClass = findClassOrNull(cl, "com.meituan.banma.core.operation.modules.e");
        Class<?> stringClass = findClassOrNull(cl, "java.lang.String");
        // 提前获取确认回调方法 e.a(String, String, String)（onNotifyProceed）
        Method mConfirm = null;
        if (eClass != null && stringClass != null) {
            try {
                mConfirm = findMethodUp(eClass, "a", stringClass, stringClass, stringClass);
                if (mConfirm != null) mConfirm.setAccessible(true);
            } catch (Throwable t) { mConfirm = null; }
        }
        if (mConfirm == null) {
            L("[NoConfirmDialogs] 初始化失败: mConfirm 为 null");
            return;
        }
        final Method confirm = mConfirm;

        // ===== 1. 抢单/转单确认弹窗组件 =====
        String[] clsNames = new String[]{"com.meituan.banma.core.operation.modules.s",
                "com.meituan.banma.core.operation.modules.ac",
                "com.meituan.banma.core.operation.modules.ai",
                "com.meituan.banma.core.operation.modules.u",
                "com.meituan.banma.core.operation.modules.b",
                "com.meituan.banma.core.operation.modules.r"};
        for (String n : clsNames) {
            Class<?> cls = findClassOrNull(cl, n);
            if (cls != null) {
                hookAllMethodsBefore(cls, "c", chain -> {
                    try {
                        // 跳过弹窗显示，直接回调"确认"让流程继续
                        confirm.invoke(chain.getThisObject(), null, "0", null);
                        L("[NoConfirmDialogs] 已跳过确认弹窗 [" + cls.getName() + "]，直接继续");
                        return null; // 跳过原方法
                    } catch (Throwable e) {
                        L("[NoConfirmDialogs] hook异常 [" + cls.getName() + "]: " + e);
                        return chain.proceed();
                    }
                });
                L("[NoConfirmDialogs] 已挂载: " + n);
            } else {
                L("[NoConfirmDialogs] 类未找到: " + n);
            }
        }

        // ===== 2. 转单确认弹窗：directTransfer.e.b(long) → 直接 f(long) =====
        Class<?> eTsfClass = findClassOrNull(cl, "com.meituan.banma.waybill.coreflow.directTransfer.e");
        if (eTsfClass != null) {
            hookAllMethodsBefore(eTsfClass, "b", chain -> {
                try {
                    if (chain.getArgs().size() == 1 && chain.getArgs().get(0) instanceof Long) {
                        // 官方新流程直接接受（跳过"确认接受转单"弹窗）
                        callAnyMethod(chain.getThisObject(), "f", chain.getArgs().get(0));
                        L("[NoConfirmDialogs] 已跳过转单确认弹窗，直接接受");
                        return null;
                    }
                } catch (Throwable e) {
                    L("[NoConfirmDialogs] 转单hook异常: " + e);
                }
                return chain.proceed();
            });
            L("[NoConfirmDialogs] 已Hook: directTransfer.e.b (转单确认)");
        }

        L("[NoConfirmDialogs] 插件加载完成");
    }

    /** 按精确参数类型向上查找方法（含父类），找不到返回 null */
    private Method findMethodUp(Class<?> c, String name, Class<?>... pts) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            try { return cur.getDeclaredMethod(name, pts); } catch (NoSuchMethodException ig) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private String join(List<String> l, String d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(d); sb.append(l.get(i)); }
        return sb.toString();
    }

    /** 反射调用任意实例方法（按名称+参数个数+类型匹配，含父类） */
    /** ★ 性能：方法反射缓存（className#name#参数类型 → Method），避免热路径重复 getDeclaredMethods 枚举 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Method> methodCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static String methodKey(Class<?> c, String name, Object[] args) {
        StringBuilder sb = new StringBuilder(c.getName()).append('#').append(name);
        for (Object a : args) sb.append('#').append(a == null ? "null" : a.getClass().getName());
        return sb.toString();
    }

    private Object callAnyMethod(Object obj, String name, Object... args) throws Exception {
        Class<?> c = obj.getClass();
        String key = methodKey(c, name, args);
        Method cached = methodCache.get(key);
        if (cached != null) return cached.invoke(obj, args);
        while (c != null && c != Object.class) {
            Method best = null, fallback = null;
            int bestScore = Integer.MIN_VALUE;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
                if (fallback == null) fallback = m;
                int score = typeScore(m.getParameterTypes(), args);
                if (score > bestScore) { bestScore = score; best = m; }
            }
            Method target = (best != null && bestScore > 0) ? best : fallback;
            if (target != null) {
                target.setAccessible(true);
                methodCache.put(key, target);
                return target.invoke(obj, args);
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    /** 反射调用任意静态方法（按名称+参数个数+类型匹配） */
    private Object callAnyStatic(Class<?> c, String name, Object... args) throws Exception {
        String key = methodKey(c, name, args);
        Method cached = methodCache.get(key);
        if (cached != null) return cached.invoke(null, args);
        Method best = null, fallback = null;
        int bestScore = Integer.MIN_VALUE;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            if (fallback == null) fallback = m;
            int score = typeScore(m.getParameterTypes(), args);
            if (score > bestScore) { bestScore = score; best = m; }
        }
        Method target = (best != null && bestScore > 0) ? best : fallback;
        if (target != null) {
            target.setAccessible(true);
            methodCache.put(key, target);
            return target.invoke(null, args);
        }
        throw new NoSuchMethodException(name);
    }

    /** 按精确参数类型反射调用实例方法（含父类查找） */
    private Object callMethodExact(Object obj, String name, Class<?>[] paramTypes, Object... args) throws Exception {
        Class<?> c = obj.getClass();
        Method m = null;
        while (c != null && c != Object.class) {
            try {
                m = c.getDeclaredMethod(name, paramTypes);
                break;
            } catch (NoSuchMethodException e) {
                c = c.getSuperclass();
            }
        }
        if (m == null) throw new NoSuchMethodException(name);
        m.setAccessible(true);
        return m.invoke(obj, args);
    }

    /** 计算实参与形参的类型匹配得分：精确/基本类型3分，可赋值2分 */
    private int typeScore(Class<?>[] pts, Object[] args) {
        int score = 0;
        for (int i = 0; i < pts.length; i++) {
            Object a = args[i];
            Class<?> pt = pts[i];
            if (a == null) {
                if (pt.isPrimitive()) return -1;
                continue;
            }
            Class<?> at = a.getClass();
            if (pt == at) { score += 3; continue; }
            if (pt.isPrimitive()) {
                if ((pt == int.class && at == Integer.class)
                        || (pt == long.class && at == Long.class)
                        || (pt == boolean.class && at == Boolean.class)
                        || (pt == double.class && at == Double.class)
                        || (pt == float.class && at == Float.class)
                        || (pt == short.class && at == Short.class)
                        || (pt == byte.class && at == Byte.class)
                        || (pt == char.class && at == Character.class)) { score += 3; continue; }
            }
            if (pt.isAssignableFrom(at)) { score += 2; continue; }
        }
        return score;
    }

    /** ★ 性能：类加载缓存（name → Class），减少 doHook 重复 loadClass */
    private static final java.util.concurrent.ConcurrentHashMap<String, Class<?>> classCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> classMiss = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 安全加载类，失败返回 null */
    private Class<?> findClassOrNull(ClassLoader cl, String name) {
        if (classCache.containsKey(name)) return classCache.get(name);
        if (classMiss.contains(name)) return null;
        try {
            Class<?> c = cl.loadClass(name);
            classCache.put(name, c);
            return c;
        } catch (Throwable t) {
            classMiss.add(name);
            return null;
        }
    }

    /** Hook 目标类所有同名方法（含重载），回调中 return 值即跳过原方法 */
    private void hookAllMethodsBefore(Class<?> target, String methodName, XposedInterface.Hooker hooker) {
        if (target == null || hooker == null) return;
        for (Method m : target.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            try {
                hook(m).intercept(hooker);
            } catch (Throwable t) {
                L("[HookAll] hook失败 " + target.getName() + "." + methodName + ": " + t);
            }
        }
    }

    /** 把包含"上线"的 normalText 替换为完整收货地址 */
    private void replaceOnlineText(Object holder, String replacement) {
        if (holder == null) return;
        try {
            Field f = findFieldUp(holder.getClass(), "normalText");
            if (f == null) return;
            Object t = f.get(holder);
            if (t != null && t.toString().contains("上线")) f.set(holder, replacement);
        } catch (Throwable ig) {}
    }
}
