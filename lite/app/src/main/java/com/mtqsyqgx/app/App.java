package com.mtqsyqgx.app;

import android.app.Application;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 入口（官方推荐方式检测 LSPosed 是否启用本模块）：
 * 框架只为"已启用"的模块下发 service binder，onServiceBind 被回调 = 模块已激活。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static volatile XposedService sService = null;

    /** 返回框架下发的服务（非 null = 模块已被 LSPosed 启用并绑定） */
    public static XposedService getService() {
        return sService;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            XposedServiceHelper.registerListener(this);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
    }

    @Override
    public void onServiceDied(XposedService service) {
        sService = null;
    }
}
