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
    private static volatile boolean configDefaultPending = false; // 默认配置后台写入在途（loadCfg 先用内存默认值）
    private static volatile boolean suConfigReadPending = false;  // 配置文件 su 后台读取去重标记
    private boolean isMeituan;
    private static volatile boolean hooked;
    private static String moduleCacheDir = null; // 缓存进程的cache目录路径
    private static String currentPackageName = ""; // 当前进程的主包名（用于构造 data 目录回退）

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
        // 通道2：sdcard（美团进程有存储权限时可写，模块App直接读）
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
    /** ★ 卡顿修复：配置文件读写专用后台单线程（与日志队列分离，避免 su 子进程与日志 I/O 互相排队） */
    private static final java.util.concurrent.ExecutorService CFG_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mtq-cfg");
                t.setDaemon(true);
                return t;
            });

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
    /** 功能调用/开关变动日志 */
    private static void logFeatureCall(String s) {
        Log.e("FeatureLog", s);
        appendSdLog("feature.log", s);
    }
    /** 输出功能开关配置状态 */
    private void dumpFeatureSwitches() {
        L("=== 功能开关状态 ===");
        String[] keys = {"mt_hide_speed", "mt_location_spoof", "mt_anti_mock", "mt_auto_refresh_home",
                "mt_auto_refresh_delay", "mt_online_check", "mt_green_light", "mt_dev_options", "mt_skip_grab_confirm",
                "show_location_spoof"};
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
    /** 直读文件（/data/local/tmp/ 是 world-readable 的）。无子进程、不等待，可安全用于 hook 热路径 */
    private static String readFileDirect(String path) {
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
            Log.e("HookInit", "[readFileDirect] direct read fail: " + t.getMessage());
        }
        return null;
    }

    /** 通过 su -c cat 读取文件（带超时）。会同步等待 su 子进程，★只能从后台线程调用 */
    private static String readFileViaSu(String path) {
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

    /** 读取文件内容（直读优先，su 兜底）。★仅限后台线程调用时才有 su 兜底 */
    private String readFileRoot(String path) {
        String r = readFileDirect(path);
        if (r != null) return r;
        return readFileViaSu(path);
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
            def.put("mt_green_light", true);        // 锁定绿灯（红绿灯显示+上报锁定为绿灯/60s）
            def.put("mt_dev_options", false);       // 开发者选项（默认关）
            def.put("mt_auto_refresh_delay", 1000); // 自动刷新主页间隔(ms)
            def.put("mt_skip_grab_confirm", false); // 跳过二次抢单确认（默认关）
            def.put("location_lat", 22.533864);     // 默认坐标（深圳）
            def.put("location_lng", 113.930291);
            def.put("location_speed", 0.0);         // 默认速度0

            cfg.getParentFile().mkdirs();
            String json = def.toString(2);
            // ★ 卡顿修复：内存缓存立即生效（hooks 高频读取走缓存），
            //   su 写入移到后台线程，避免在进程主线程同步等待 su 子进程（最长3s，卡冷启动）
            configCache = def;
            configLastRaw = json;
            configDefaultPending = true;
            CFG_EXEC.execute(() -> {
                try {
                    // 写入前复查：期间文件已被写入（如App保存了配置）则不覆盖
                    if (cfg.exists() && cfg.length() > 4) return;
                    // ★ 使用 writeViaSu 写入（确保文件权限为 666，目标App进程可读）
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
                } catch (Throwable t) {
                    Log.e("HookInit", "[Config] ✗ create failed: " + t.getMessage());
                } finally {
                    configDefaultPending = false;
                }
            });
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
            // ★ 卡顿修复：热路径只做直读（无子进程、不等待）。直读失败需要 su 时改后台异步读取，
            //   绝不在此同步等待 su 子进程（最长 3s，会卡住 hook 回调所在的任意线程）
            String raw = readFileDirect(CONFIG_PATH);
            if (raw == null) {
                // ★ 区分「文件不存在」和「文件存在但读取失败」
                // 文件不存在 → 若默认配置写入仍在途，先用内存默认值；否则重置缓存（功能回到默认关闭）
                // 文件存在但读取失败 → 保持缓存，并触发一次后台 su 读取
                if (!f.exists()) {
                    if (configDefaultPending) return configCache;
                    Log.e("HookInit", "[loadCfg] config file deleted, reset cache to defaults");
                    configCache = null;
                    configLastRaw = null;
                    return null;
                }
                requestConfigReadViaSu();
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

    /** ★ 卡顿修复：配置文件 su 读取改后台（直读失败时才触发，去重；hook 热路径零等待） */
    private void requestConfigReadViaSu() {
        if (suConfigReadPending) return;
        suConfigReadPending = true;
        CFG_EXEC.execute(() -> {
            try {
                String raw = readFileViaSu(CONFIG_PATH);
                if (raw != null && !raw.isEmpty()) {
                    configCache = new JSONObject(raw);
                    configLastRaw = raw;
                    Log.e("HookInit", "[loadCfg] su 后台读取成功");
                }
            } catch (Throwable t) {
                Log.e("HookInit", "[loadCfg] su 后台读取失败: " + t.getMessage());
            } finally {
                suConfigReadPending = false;
            }
        });
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

    @Override
	public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
		String proc = param.getProcessName();
		// 提炼主包名（去掉 :suffix 子进程后缀），用于日志目录回退
		currentPackageName = (proc != null && proc.contains(":")) ? proc.substring(0, proc.indexOf(":")) : proc;
		// ★ 先用 Log.e 输出，确保 logcat 能看到（此时文件日志可能还未就绪）
		Log.e("HookInit", "=== onModuleLoaded: " + proc + " pid=" + android.os.Process.myPid() + " pkg=" + currentPackageName + " ===");
		isMeituan = proc != null && proc.contains("meituan");

		// 模块存活标记 — 多通道写入（su /data/local/tmp + sdcard）
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
            // ★ 合并 main.java v1.1.0：WaterMarkView 挂载入口全拦截（按方法签名查找，兼容混淆变体）
            //   a() / a(Activity) → 向 DecorView 叠加防截屏水印（a(Activity) 为 main.java 主拦截点）
            //   e(Activity)      → 兜底挂载入口
            {
                String[] wmClasses = {
                        "com.meituan.banma.base.common.ui.view.WaterMarkView",
                        "com.meituan.banma.mutual.waterMark.view.WaterMarkView"
                };
                int wmHooked = 0;
                for (String wcn : wmClasses) {
                    Class<?> wm = findClassOrNull(cl, wcn);
                    if (wm == null) { L("[WM] 未找到: " + wcn); continue; }
                    for (Method m : wm.getDeclaredMethods()) {
                        if (!"a".equals(m.getName())) continue;
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts.length == 0 || (pts.length == 1 && Activity.class.isAssignableFrom(pts[0]))) {
                            try { hook(m).intercept(c -> null); wmHooked++; L("[WM] 已跳过: " + wcn + "." + m.getName() + "(" + pts.length + "参)"); } catch (Throwable ig) {}
                        }
                    }
                    try { Method e = wm.getDeclaredMethod("e", Activity.class); e.setAccessible(true); hook(e).intercept(c -> null); wmHooked++; L("[WM] 已跳过: " + wcn + ".e(Activity)"); } catch (NoSuchMethodException ig) {} catch (Throwable ig) {}
                }
                logFeature("去水印", wmHooked > 0, wmHooked + " 个挂载入口已拦截");
            }
            // setVisibility 隐藏（兜底：万一水印已挂载，直接隐藏 WaterMarkView）
            try { hook(M(cl, "android.view.View", "setVisibility", int.class)).intercept(chain -> { if (chain.getThisObject().getClass().getName().contains("WaterMarkView")) return null; return chain.proceed(); }); } catch (Throwable t) {}
            // TextView "内测/请勿分享" 水印文字清理
            try {
                Class<?> tv = cl.loadClass("android.widget.TextView");
                XposedInterface.Hooker filter = chain -> {
                    Object a = chain.getArgs().get(0);
                    if (a instanceof CharSequence && (a.toString().contains("内测") || a.toString().contains("请勿分享"))) {
                        chain.proceed();
                        try { chain.getThisObject().getClass().getMethod("setText", CharSequence.class).invoke(chain.getThisObject(), ""); } catch (Throwable ig) {}
                        return null;
                    }
                    return chain.proceed();
                };
                try { hook(tv.getDeclaredMethod("setText", CharSequence.class)).intercept(filter); } catch (NoSuchMethodException ig) {}
                try { hook(tv.getDeclaredMethod("setText", CharSequence.class, TextView.BufferType.class)).intercept(filter); } catch (Throwable ig) {}
            } catch (Throwable t) {}
            L("[WM] ✓");
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
                // ★ main.java v1.1.0 兼容：混淆后可能是 forbidScreenshot 的静态内部类 b（$b 写法），
                //   也可能是包名下独立类 b（.b 写法），两种形式都试
                String[] forbidCandidates = { "com.meituan.banma.base.common.ui.forbidScreenshot$b", "com.meituan.banma.base.common.ui.forbidScreenshot.b", "com.meituan.banma.base.common.ui.forbidScreenshot" };
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
            logFeature("允许截屏", !failed.contains("截屏"), "Window 层 FLAG_SECURE 剥离 + ForbidScreenshot 跳过 + 检测拦截");
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

        // ═══ 锁定绿灯（mt_green_light：红绿灯数据拦截锁定，显示+上报全部锁定为绿灯/60s） ═══
        // 颜色语义：TBT/Safety 层 1=红 2=黄 3=绿；SDK 显示层 201=红 202=黄 203=绿
        if (p("mt_green_light", false)) {
            // 1. 显示链路：ConvertUtils.naviTrafficCountDownListTbtToSdk（地图气泡：红→绿，倒计时=60）
            try {
                Class<?> cuClass = findClassOrNull(cl, "com.meituan.sankuai.navisdk.utils.ConvertUtils");
                if (cuClass != null) {
                    boolean hooked = false;
                    for (Method m : cuClass.getDeclaredMethods()) {
                        if (!"naviTrafficCountDownListTbtToSdk".equals(m.getName())) continue;
                        hook(m).intercept(chain -> {
                            Object r = chain.proceed();
                            try {
                                if (r instanceof java.util.List) {
                                    for (Object info : (java.util.List<?>) r) fixSdkLight(info);
                                }
                            } catch (Throwable ig) {}
                            return r;
                        });
                        L("[绿灯] ✓ 显示链路: ConvertUtils.naviTrafficCountDownListTbtToSdk");
                        hooked = true;
                        break;
                    }
                    if (!hooked) L("[绿灯] 显示链路方法未找到");
                } else { L("[绿灯] 未找到 ConvertUtils"); }
            } catch (Throwable t) { Le("[绿灯] 显示链路异常", t); }

            // 2. 上报数据层：SafeSDKNaviModel$a.onLightCountdownInfo（上报数据源改好）
            try {
                Class<?> saClass = findClassOrNull(cl, "com.meituan.banma.trafficsafety.model.SafeSDKNaviModel$a");
                if (saClass != null) {
                    boolean hooked = false;
                    for (Method m : saClass.getDeclaredMethods()) {
                        if (!"onLightCountdownInfo".equals(m.getName())) continue;
                        hook(m).intercept(chain -> {
                            try {
                                Object arg = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                                if (arg instanceof java.util.List) {
                                    for (Object info : (java.util.List<?>) arg) fixSafetyLight(info);
                                }
                            } catch (Throwable ig) {}
                            return chain.proceed();
                        });
                        L("[绿灯] ✓ 上报数据层: SafeSDKNaviModel$a.onLightCountdownInfo");
                        hooked = true;
                        break;
                    }
                    if (!hooked) L("[绿灯] 上报数据层方法未找到");
                } else { L("[绿灯] 未找到 SafeSDKNaviModel$a"); }
            } catch (Throwable t) { Le("[绿灯] 上报数据层异常", t); }

            // 3. 上报层兜底：trafficsafety.a.a（按参数特征过滤 navigationTrafficLightCountdown 上报包）
            try {
                Class<?> tfaClass = findClassOrNull(cl, "com.meituan.banma.trafficsafety.a");
                if (tfaClass != null) {
                    hookAllMethodsBefore(tfaClass, "a", chain -> {
                        try {
                            if (chain.getArgs().size() == 2
                                    && chain.getArgs().get(0) instanceof String
                                    && chain.getArgs().get(1) instanceof java.util.Map
                                    && "navigationTrafficLightCountdown".equals(chain.getArgs().get(0))) {
                                Object infos = ((java.util.Map<?, ?>) chain.getArgs().get(1)).get("countdownInfos");
                                if (infos instanceof java.util.List) {
                                    for (Object info : (java.util.List<?>) infos) fixSafetyLight(info);
                                }
                            }
                        } catch (Throwable ig) {}
                        return chain.proceed();
                    });
                    L("[绿灯] ✓ 上报层兜底: trafficsafety.a.a");
                } else { L("[绿灯] 未找到 trafficsafety.a"); }
            } catch (Throwable t) { Le("[绿灯] 上报层异常", t); }

            // 4. 上报网络层：SafeSDKNaviModel$a.onWaitRedLightTime（等红灯时长不上报）
            try {
                Class<?> saClass2 = findClassOrNull(cl, "com.meituan.banma.trafficsafety.model.SafeSDKNaviModel$a");
                if (saClass2 != null) {
                    boolean hooked = false;
                    for (Method m : saClass2.getDeclaredMethods()) {
                        if (!"onWaitRedLightTime".equals(m.getName())) continue;
                        hook(m).intercept(chain -> {
                            try {
                                Object arg = chain.getArgs().size() > 1 ? chain.getArgs().get(1) : null;
                                if (arg instanceof java.util.List) {
                                    for (Object item : (java.util.List<?>) arg) {
                                        if (item == null) continue;
                                        try { Field f = findFieldUp(item.getClass(), "hasWaitedRedLight"); if (f != null) f.setBoolean(item, false); } catch (Throwable ig) {}
                                        try { Field f = findFieldUp(item.getClass(), "waitTime"); if (f != null) f.setInt(item, 0); } catch (Throwable ig) {}
                                    }
                                }
                            } catch (Throwable ig) {}
                            return chain.proceed();
                        });
                        L("[绿灯] ✓ 上报网络层: SafeSDKNaviModel$a.onWaitRedLightTime");
                        hooked = true;
                        break;
                    }
                    if (!hooked) L("[绿灯] 上报网络层方法未找到");
                }
            } catch (Throwable t) { Le("[绿灯] 上报网络层异常", t); }
            logFeature("锁定绿灯", true, "显示+上报四层锁定（绿灯/60s）");
        }

        // ═══ 9. 跳过二次抢单确认（NoConfirmDialogs） ═══
        if (p("mt_skip_grab_confirm", false)) {
            installSkipGrabConfirm(cl);
            logFeature("跳过二次抢单确认", true, "已安装");
        } else {
            logFeature("跳过二次抢单确认", true, "未启用(开关关)");
        }

        dumpFeatureSwitches();

        L("=== doHook 结束 失败=" + join(failed, ",") + (needCoord ? " 坐标未设置" : " 全部成功") + " ===");
    }

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

    /** ★ 锁定绿灯辅助：SDK 显示层红绿灯对象（红201→绿203，倒计时→60） */
    private void fixSdkLight(Object info) {
        if (info == null) return;
        try {
            Object color = callAnyMethod(info, "getLightColor");
            if (color instanceof Number && ((Number) color).intValue() == 201) {
                callAnyMethod(info, "setLightColor", 203);
            }
        } catch (Throwable ig) {}
        try { callAnyMethod(info, "setCountdown", 60); } catch (Throwable ig) {}
    }

    /** ★ 锁定绿灯辅助：Safety 层红绿灯对象（public color/downCount 字段，3=绿，倒计时=60） */
    private void fixSafetyLight(Object info) {
        if (info == null) return;
        try { Field f = findFieldUp(info.getClass(), "color"); if (f != null) f.setInt(info, 3); } catch (Throwable ig) {}
        try { Field f = findFieldUp(info.getClass(), "downCount"); if (f != null) f.setInt(info, 60); } catch (Throwable ig) {}
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
}
