package com.mtqsyqgx.app;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 完整性校验（已精简）：仅保留「签名校验」+「网络检测」。
 * 已移除：调试器检测 / Native库完整性 / DEX注入检测 / 反模拟器 / forceShutdown 清配置。
 * 校验失败 → 提示并退出 APP，但★不删除任何配置数据★。
 */
public class IntegrityChecker {
    private static final String TAG = "IntegrityChecker";

    /** 校验失败原因 */
    public enum FailReason { NONE, SIGNATURE_MISMATCH, NETWORK_UNREACHABLE }

    /** 预期的签名证书 SHA-256 指纹（luckykey.jks / luckyapp） */
    private static final String EXPECTED_CERT_SHA256 = "DC465F07881619B9A8D78FA8ED2422BD20F7746375C1C55B79C336CEE3F79956";

    // ========== 1. 签名校验（唯一保留的完整性校验项） ==========

    /** 校验 APK 签名证书指纹是否匹配；返回 true 表示不匹配（被篡改/换签） */
    public static boolean checkSignature(Context context) {
        if (EXPECTED_CERT_SHA256 == null || EXPECTED_CERT_SHA256.isEmpty()) return false;
        try {
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.GET_SIGNATURES;
            if (Build.VERSION.SDK_INT >= 28) {
                flags = PackageManager.GET_SIGNING_CERTIFICATES;
            }
            PackageInfo pi = pm.getPackageInfo(context.getPackageName(), flags);
            Signature[] signatures;
            if (Build.VERSION.SDK_INT >= 28) {
                signatures = pi.signingInfo.getApkContentsSigners();
            } else {
                signatures = pi.signatures;
            }
            if (signatures == null || signatures.length == 0) return true;

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] certHash = md.digest(signatures[0].toByteArray());
            String actualHash = bytesToHex(certHash);
            return !actualHash.equalsIgnoreCase(EXPECTED_CERT_SHA256);
        } catch (Throwable t) {
            Log.e(TAG, "签名校验异常(视为安全): " + t.getMessage());
            return false;
        }
    }

    /** 防篡改检测：仅签名校验 */
    public static boolean isTampered(Context context) {
        return checkSignature(context);
    }

    // ========== 2. 网络检测（通过 HTTP 更新链接） ==========

    private static final String UPDATE_URL = "https://gitee.com/awana/mtqsyqgx/raw/master/update.json";
    private static final int NETWORK_TIMEOUT = 5000; // 5秒超时

    /** 通过 HTTP 请求更新链接检测网络是否畅通；只有真正能访问远端才算有网络 */
    public static boolean isNetworkReachable() {
        android.os.StrictMode.ThreadPolicy oldPolicy = android.os.StrictMode.getThreadPolicy();
        try {
            android.os.StrictMode.setThreadPolicy(
                new android.os.StrictMode.ThreadPolicy.Builder().permitAll().build());

            HttpURLConnection conn = null;
            try {
                URL url = new URL(UPDATE_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(NETWORK_TIMEOUT);
                conn.setReadTimeout(NETWORK_TIMEOUT);
                return conn.getResponseCode() == 200;
            } catch (Throwable t) {
                Log.e(TAG, "网络检测失败（无法访问更新链接）: " + t.getClass().getSimpleName() + " - " + t.getMessage());
                return false;
            } finally {
                if (conn != null) conn.disconnect();
            }
        } finally {
            android.os.StrictMode.setThreadPolicy(oldPolicy);
        }
    }

    // ========== 综合校验 ==========

    /** 按顺序校验：先签名，后网络；返回失败原因 */
    public static FailReason checkIntegrity(Context context) {
        if (checkSignature(context)) return FailReason.SIGNATURE_MISMATCH;
        if (!isNetworkReachable()) return FailReason.NETWORK_UNREACHABLE;
        return FailReason.NONE;
    }

    /** 校验失败 → 提示用户 + 退出 APP（不删除任何配置） */
    public static void checkAndShutdownIfTampered(Context context) {
        FailReason reason = checkIntegrity(context);
        if (reason == FailReason.NONE) return;

        String msg;
        if (reason == FailReason.SIGNATURE_MISMATCH) {
            Log.e(TAG, "========== 签名校验失败，退出应用（配置已保留） ==========");
            msg = "签名校验失败，应用将退出";
        } else {
            Log.e(TAG, "========== 网络不可达，退出应用（配置已保留） ==========");
            msg = "网络不可用，请检查网络后重试";
        }

        // 提示用户（Toast，不阻塞退出）
        try { android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}

        // 退出：关闭所有 Activity，再兜底结束进程（不删配置）
        try { if (context instanceof Activity) ((Activity) context).finishAffinity(); } catch (Throwable ignored) {}
        try { android.os.Process.killProcess(android.os.Process.myPid()); } catch (Throwable ignored) {}
    }

    // ========== 工具方法 ==========

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
