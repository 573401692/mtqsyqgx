package com.mtqsyqgx.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/** PackageInstaller 安装结果接收器（安装完成/失败/用户取消回调） */
public class UpdateInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        boolean force = intent.getBooleanExtra("force", false);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            return; // 安装成功：进程将被系统替换/重启，无需处理
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            return; // 系统正在显示安装确认界面
        }
        // 安装失败/用户取消
        String msg = "安装失败: " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        try {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
        if (force) {
            // 强制更新：不可跳过，退出 APP
            try {
                if (context instanceof android.app.Activity) {
                    ((android.app.Activity) context).finishAffinity();
                }
            } catch (Throwable ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
