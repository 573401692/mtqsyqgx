package com.mtqsyqgx.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity基类：防非法唤醒 + 完整性校验
 * 所有Activity必须继承此类
 */
public abstract class SecureActivity extends Activity {
    private static final String TAG = "SecureActivity";

    // ========== 合法的启动方式 ==========
    // 只允许以下方式启动Activity：
    // 1. APP内部Intent启动（flag包含 FLAG_ACTIVITY_NEW_TASK 或从内部Activity启动）
    // 2. 用户直接点击桌面图标启动（action=MAIN, category=HOME）
    // 3. PendingIntent触发（系统级通知）

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ===== 完整性校验（签名校验 + 网络检测；失败退出但不删配置） =====
        try {
            IntegrityChecker.checkAndShutdownIfTampered(this);
        } catch (Throwable t) {
            Log.e(TAG, "完整性校验异常: " + t.getMessage());
        }

        // ===== 防非法唤醒检测 =====
        if (!isLaunchAllowed()) {
            Log.e(TAG, "非法唤醒检测：拒绝启动 " + getClass().getName());
            super.onCreate(savedInstanceState);
            finish();
            return;
        }

        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    /**
     * 检查是否允许启动当前Activity
     */
    private boolean isLaunchAllowed() {
        Intent intent = getIntent();
        if (intent == null) return false;

        String action = intent.getAction();
        Intent baseIntent = getBaseIntent(this);

        // 1. 允许：桌面图标直接启动（action=MAIN + category=HOME）
        if (Intent.ACTION_MAIN.equals(action) && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            return true;
        }

        // 2. 允许：从本APP内部启动（baseIntent有效）
        if (baseIntent != null) {
            String baseAction = baseIntent.getAction();
            if (Intent.ACTION_MAIN.equals(baseAction) && baseIntent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                return true;
            }
        }

        // 3. 允许：Activity间的内部跳转（通过component判断来源）
        if (intent.getComponent() != null) {
            // 补充判断：component的包名是否为本应用
            String componentPkg = intent.getComponent().getPackageName();
            if (getPackageName().equals(componentPkg)) {
                return true;
            }
        }

        // 4. 拒绝：其他所有启动方式
        return false;
    }

    /**
     * 获取APP的启动Intent（桌面图标对应的Intent）
     */
    private static Intent getBaseIntent(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
            return launchIntent;
        } catch (Throwable t) {
            return null;
        }
    }
}
