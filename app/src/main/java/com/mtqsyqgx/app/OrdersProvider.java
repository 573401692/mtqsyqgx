package com.mtqsyqgx.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileWriter;

/**
 * 跨进程订单通道（替代 su 文件搬运，全程无 root 依赖）：
 *
 * 采集器（注入美团进程）→ ContentResolver.call() 推送订单 JSON 到此 Provider
 * → 写入模块 App 自家私有缓存目录 lucky_orders/mt_orders.json
 * → 悬浮窗（与 Provider 同属模块 App 进程）直接 File 读取。
 *
 * 解决了之前 "App 进程内 su 失败导致 /data/local/tmp 不更新" 的根本问题。
 */
public class OrdersProvider extends ContentProvider {

    public static final String AUTHORITY = "com.mtqsyqgx.app.orders";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    private static final String METHOD_PUSH = "push";
    private static final String METHOD_HEARTBEAT = "heartbeat";
    private static final String EXTRA_JSON = "json";
    private static final String EXTRA_PROC = "proc";
    private static final String EXTRA_TIME = "time";
    private static final String RESULT_OK = "ok";

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * 供采集器（美团进程）跨进程推送模块心跳：写入模块私有缓存
     * lucky_orders/mt_module_heartbeat.txt，主页面直接 File 读取（免su）。
     */
    private Bundle handleHeartbeat(Bundle extras) {
        try {
            String proc = extras != null ? extras.getString(EXTRA_PROC, "") : "";
            long time = extras != null ? extras.getLong(EXTRA_TIME, System.currentTimeMillis()) : System.currentTimeMillis();
            File dir = new File(getContext().getCacheDir(), "lucky_orders");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "mt_module_heartbeat.txt");
            FileWriter fw = new FileWriter(f, false);
            fw.write("loaded|" + proc + "|" + time);
            fw.close();
            Bundle out = new Bundle();
            out.putBoolean(RESULT_OK, true);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 供采集器（美团进程）跨进程推送订单 JSON。
     * extras 携带 key="json" 的订单数组字符串，写入模块私有缓存。
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_HEARTBEAT.equals(method)) {
            return handleHeartbeat(extras);
        }
        if (METHOD_PUSH.equals(method) && extras != null) {
            String json = extras.getString(EXTRA_JSON);
            if (json != null && !json.isEmpty()) {
                try {
                    File dir = new File(getContext().getCacheDir(), "lucky_orders");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "mt_orders.json");
                    FileWriter fw = new FileWriter(f, false);
                    fw.write(json);
                    fw.close();
                    Bundle out = new Bundle();
                    out.putBoolean(RESULT_OK, true);
                    return out;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
