package com.mtqsyqgx.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.*;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;

public class MainActivity extends SecureActivity {

    private SharedPreferences prefs;
    private TextView tvSubTitle;
    private EditText etDelay;
    private static final int REQ_MAP_PICKER = 1000;
    private static final int REQ_INSTALL_PERMISSION = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;
    private static final String UPDATE_URL = "https://gitee.com/awana/mtqsyqgx/raw/master/update.json";
    // 更新下载（应用内 HTTP 下载 + 进度弹窗 + 自动安装）
    private Thread updateDownloadThread;
    private Handler updateProgressHandler;
    private java.io.File mPendingApkFile;
    private boolean mUpdateForce = false;
    // 更新下载进度弹窗
    private Dialog updateProgressDialog;
    private ProgressBar updateProgressBar;
    private TextView updateProgressText;

    // ★ 统一配置路径 /sdcard/ — /data/local/tmp/ 不可写（EACCES），改用/sdcard/
    private static final String HOOK_CONFIG_PATH = "/data/local/tmp/mt_config.json";
    private static final String SYSTEM_CONFIG_PATH = "/data/system/mt_config.json";
    private static final String LOG_DIR = "/sdcard/Download/lucky/log/";
    private static final String HIDDEN_SETTINGS_PREFS = "mtq_hidden_settings";
    private static final String KEY_HIDDEN_ENTRY_ENABLED = "hidden_entry_enabled";

    private static final String[][] APP_KEYS = {
        {"mt_update", "mt_watermark", "mt_screenshot", "mt_anti_detect", "mt_online_check", "mt_dev_options",
            "mt_time_offset", "mt_auto_refresh_home", "mt_hide_speed", "mt_anti_mock", "mt_skip_grab_confirm", "mt_green_light",
            "mt_env_guard"}
    };

    private static final String[] ALL_KEYS = {"mt_update", "mt_watermark", "mt_screenshot", "mt_anti_detect",
        "mt_online_check", "mt_dev_options", "mt_time_offset",
        "mt_auto_refresh_home", "mt_hide_speed", "mt_anti_mock", "mt_location_spoof", "mt_skip_grab_confirm",
        "mt_green_light", "mt_env_guard",
        "location_lat", "location_lng"};

    private static final String[] TARGET_PACKAGES = {"com.sankuai.meituan.dispatch.crowdsource"};

    private TextView tvUpdateHint;
    private View actionCard; // 快捷操作卡片，用于实时刷新
    private LinearLayout container; // 内容容器，用于刷新卡片

    private static final int COLOR_PRIMARY = Color.parseColor("#FF5722");
    private static final int COLOR_PRIMARY_LIGHT = Color.parseColor("#FF8A65");
    private static final int COLOR_RED = Color.parseColor("#E53935");
    private static final int COLOR_BG = Color.parseColor("#F5F6FA");
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT_MAIN = Color.parseColor("#2C3E50");
    private static final int COLOR_TEXT_SUB = Color.parseColor("#95A5A6");
    private static final int COLOR_GREEN = Color.parseColor("#27AE60");
    private static final int COLOR_BLUE = Color.parseColor("#2980B9");
    private static final int COLOR_ORANGE = Color.parseColor("#F39C12");
    private static final int TEXT_COLOR_LIGHT = Color.parseColor("#BDC3C7");

    public boolean isModuleActive() {
        return false;
    }

    private boolean detectModuleActive() {
        try {
            if (isModuleActive()) return true;
        } catch (Throwable ignored) {}
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, null);
            return true;
        } catch (Throwable ignored) {}
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false,
                getApplication().getClassLoader());
            return true;
        } catch (Throwable ignored) {}
        try {
            Class.forName("de.robv.android.xposed.XposedHelpers", false, null);
            return true;
        } catch (Throwable ignored) {}
        try {
            Class<?> cls = Class.forName("de.robv.android.xposed.XposedBridge", false, null);
            if (cls.getDeclaredField("sHookedMethodCallbacks") != null) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        prefs = getSharedPreferences("hook_config", MODE_PRIVATE);
        clearOldUpdateCache();

        // 检查并请求存储权限（Android 11+ 需要 MANAGE_EXTERNAL_STORAGE）
        checkStoragePermission();

        syncConfigToUI();

        if (!prefs.getBoolean("disclaimer_accepted", false)) {
            showDisclaimerDialog();
        } else {
            enterMainIfReady();
        }
        silentCheckUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateEnvironmentStatusText();
        refreshActionCard();
        refreshPermissionGate();
    }

    private void refreshActionCard() {
        if (container == null || actionCard == null) return;
        int index = container.indexOfChild(actionCard);
        if (index == -1) return;
        View newActionCard = createActionCard();
        container.removeViewAt(index);
        container.addView(newActionCard, index);
        actionCard = newActionCard;
    }

    private void syncConfigToUI() {
        try {
            // 优先通过 su 读取（带超时）
            String content = null;
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + HOOK_CONFIG_PATH});
                java.io.BufferedReader sr = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = sr.readLine()) != null) sb.append(line);
                boolean completed = p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!completed) p.destroyForcibly();
                sr.close();
                if (sb.length() > 10) content = sb.toString();
            } catch (Throwable ignored) {}
            // 回退：直接文件读取
            if (content == null) {
                java.io.File configFile = new java.io.File(HOOK_CONFIG_PATH);
                if (!configFile.exists()) return;
                java.io.FileInputStream fis = new java.io.FileInputStream(configFile);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                fis.close();
                if (sb.length() > 10) content = sb.toString();
            }
            if (content == null) return;

            JSONObject config = new JSONObject(content);
            SharedPreferences.Editor editor = prefs.edit();
            for (String[] g : APP_KEYS) {
                for (String k : g) {
                    if (config.has(k)) {
                        editor.putBoolean(k, config.getBoolean(k));
                    }
                }
            }
            if (config.has("location_lat")) {
                editor.putFloat("location_lat", (float) config.getDouble("location_lat"));
            }
            if (config.has("location_lng")) {
                editor.putFloat("location_lng", (float) config.getDouble("location_lng"));
            }
            if (config.has("mt_auto_refresh_delay")) {
                editor.putInt("mt_auto_refresh_delay", config.getInt("mt_auto_refresh_delay"));
            }
            if (config.has("mt_location_spoof")) {
                editor.putBoolean("mt_location_spoof", config.getBoolean("mt_location_spoof"));
            }
            if (config.has("mt_anti_mock")) {
                editor.putBoolean("mt_anti_mock", config.getBoolean("mt_anti_mock"));
            }
            editor.apply();
        } catch (Throwable ignored) {}
    }

    private void clearOldUpdateCache() {
        try {
            java.io.File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && dir.exists()) {
                for (File f : dir.listFiles()) {
                    if (f.getName().startsWith("peisong_") && f.getName().endsWith(".apk")) f.delete();
                }
            }
        } catch (Exception ignored) {}
    }

    // ======================== 存储权限 ========================

    private void checkStoragePermission() {
        // ★ 不再自动跳转授权页：由「权限检测与授权」页让用户自行点击申请
        // Android 11+ 的 MANAGE_EXTERNAL_STORAGE 由权限门页面处理；
        // Android 10 及以下运行时权限同样由权限门页面申请。
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                syncConfigToUI();
            }
        }
    }

    // ======================== 更新检查 ========================

    private void silentCheckUpdate() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL(UPDATE_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();
                    JSONObject json = new JSONObject(sb.toString());
                    int serverVersion = json.optInt("versionCode", 0);
                    final String versionName = json.optString("versionName", "");
                    final String updateLog = json.optString("updateLog", "");
                    final String apkUrl = json.optString("apkUrl", "");
                    final boolean force = json.optBoolean("forceUpdate", false);
                    if (serverVersion > getLocalVersionCode()) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (tvUpdateHint != null) {
                                    tvUpdateHint.setVisibility(View.VISIBLE);
                                    tvUpdateHint.setText("发现新版本 v" + versionName + "，点击查看");
                                    tvUpdateHint.setOnClickListener(new View.OnClickListener() {
                                        public void onClick(View v) {
                                            showUpdateDialog(versionName, updateLog, apkUrl, force);
                                        }
                                    });
                                }
                                if (force) {
                                    showUpdateDialog(versionName, updateLog, apkUrl, force);
                                }
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    private int getLocalVersionCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    private void proceedToMain() {
        if (!prefs.getBoolean("disclaimer_accepted", false)) showDisclaimerDialog();
        else setupMainLayout();
    }

    private void showUpdateDialog(String version, String log, final String apkUrl, boolean force) {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#B3000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = createDialogCard(0.88f);
        addIconText(card, "\uD83D\uDE80", 40);
        addTitle(card, "发现新版本 " + version, COLOR_PRIMARY);
        addMessage(card, log.replace("\\n", "\n"), 14);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 8, 0, 0);
        if (force) {
            addButton(btnRow, "立即更新", COLOR_PRIMARY, new View.OnClickListener() {
                public void onClick(View v) { dialog.dismiss(); startUpdateDownload(apkUrl, version, force); }
            }, true);
            dialog.setCancelable(false);
        } else {
            addButton(btnRow, "稍后更新", Color.parseColor("#555555"), new View.OnClickListener() {
                public void onClick(View v) { dialog.dismiss(); proceedToMain(); }
            }, false);
            addButton(btnRow, "立即更新", COLOR_PRIMARY, new View.OnClickListener() {
                public void onClick(View v) { dialog.dismiss(); startUpdateDownload(apkUrl, version, force); }
            }, false);
            dialog.setCancelable(true);
        }
        card.addView(btnRow);
        root.addView(card);
        dialog.setContentView(root);
        dialog.show();
    }

    /** 应用内下载更新（HTTP 直下 + 进度弹窗 + 完成后自动安装，全程不退出） */
    private void startUpdateDownload(String url, String version, boolean force) {
        mUpdateForce = force;
        showUpdateProgressDialog(version);
        updateProgressHandler = new Handler(Looper.getMainLooper());
        updateDownloadThread = new Thread(new Runnable() {
            public void run() {
                HttpURLConnection conn = null;
                java.io.InputStream in = null;
                java.io.FileOutputStream out = null;
                try {
                    java.io.File outFile = new java.io.File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "peisong_" + version + ".apk");
                    if (outFile.exists()) outFile.delete();
                    URL u = new URL(url);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != 200) { postUpdateError("下载失败(HTTP " + code + ")"); return; }
                    long total = conn.getContentLengthLong();
                    in = conn.getInputStream();
                    out = new java.io.FileOutputStream(outFile);
                    byte[] buf = new byte[8192];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            final int pct = (int) (done * 100L / total);
                            final long d = done, t = total;
                            postUpdateProgress(pct, d, t);
                        }
                    }
                    out.flush();
                    out.close(); out = null;
                    in.close(); in = null;
                    conn.disconnect(); conn = null;
                    final java.io.File f = outFile;
                    postUpdateDone(f);
                } catch (final Throwable t) {
                    postUpdateError("下载失败: " + t.getMessage());
                } finally {
                    try { if (out != null) out.close(); } catch (Throwable ignored) {}
                    try { if (in != null) in.close(); } catch (Throwable ignored) {}
                    if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        });
        updateDownloadThread.start();
    }

    /** 显示下载进度弹窗（与页面同风格 UI） */
    private void showUpdateProgressDialog(String version) {
        updateProgressDialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#B3000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = createDialogCard(0.82f);
        addIconText(card, "\uD83D\uDE80", 36);
        addTitle(card, "正在下载 " + version, COLOR_PRIMARY);
        addMessage(card, "请保持网络畅通，下载完成后将自动安装", 13);
        updateProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgressBar.setMax(100);
        updateProgressBar.setProgress(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(14));
        lp.setMargins(dp2px(16), dp2px(10), dp2px(16), dp2px(4));
        updateProgressBar.setLayoutParams(lp);
        card.addView(updateProgressBar);
        updateProgressText = new TextView(this);
        updateProgressText.setText("0%");
        updateProgressText.setTextSize(13);
        updateProgressText.setTextColor(COLOR_TEXT_SUB);
        updateProgressText.setGravity(Gravity.CENTER);
        updateProgressText.setPadding(0, 0, 0, dp2px(10));
        card.addView(updateProgressText);
        root.addView(card);
        updateProgressDialog.setContentView(root);
        updateProgressDialog.setCancelable(false);
        updateProgressDialog.show();
    }

    /** 进度回调（UI 线程） */
    private void postUpdateProgress(final int pct, final long done, final long total) {
        updateProgressHandler.post(new Runnable() {
            public void run() {
                if (updateProgressDialog != null && updateProgressDialog.isShowing() && updateProgressBar != null) {
                    updateProgressBar.setProgress(pct);
                    updateProgressText.setText(pct + "%  (" + (done / 1024 / 1024) + "MB/" + (total / 1024 / 1024) + "MB)");
                }
            }
        });
    }

    /** 下载完成：关弹窗 → 拉起安装 */
    private void postUpdateDone(final java.io.File apkFile) {
        updateProgressHandler.post(new Runnable() {
            public void run() {
                dismissUpdateProgressDialog();
                installApkFile(apkFile);
            }
        });
    }

    /** 下载失败：关弹窗 → 提示（不退出 APP，可重试） */
    private void postUpdateError(final String msg) {
        updateProgressHandler.post(new Runnable() {
            public void run() {
                dismissUpdateProgressDialog();
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void dismissUpdateProgressDialog() {
        if (updateProgressDialog != null && updateProgressDialog.isShowing()) {
            updateProgressDialog.dismiss();
        }
        updateProgressDialog = null;
    }

    /** 安装 APK：先检查"安装未知应用"权限，未授权先请求再安装 */
    private void installApkFile(final java.io.File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            Toast.makeText(this, "下载文件不存在", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                mPendingApkFile = apkFile;
                startActivityForResult(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())), REQ_INSTALL_PERMISSION);
                return;
            }
        }
        doInstallApk(apkFile);
    }

    private void doInstallApk(java.io.File apkFile) {
        try {
            installWithPackageInstaller(apkFile);
        } catch (Throwable t) {
            // 兜底：ACTION_VIEW（部分设备会交给系统文件管理器，需其具备安装权限）
            try {
                Uri apkUri = new Uri.Builder().scheme("content")
                        .authority(getPackageName() + ".apkfile")
                        .appendPath(apkFile.getName()).build();
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(apkUri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Throwable t2) {
                Toast.makeText(this, "无法打开安装器: " + t2.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 通过 PackageInstaller 由本 App 直接安装（不走系统文件管理器，避免其无安装权限） */
    private void installWithPackageInstaller(java.io.File apkFile) throws Exception {
        android.content.pm.PackageInstaller.SessionParams params =
                new android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        android.content.pm.PackageInstaller pi = getPackageManager().getPackageInstaller();
        int sessionId = pi.createSession(params);
        android.content.pm.PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            java.io.InputStream in = new java.io.FileInputStream(apkFile);
            java.io.OutputStream out = session.openWrite("update_" + apkFile.getName(), 0, -1);
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            session.close();
        }
        android.app.PendingIntent done = android.app.PendingIntent.getBroadcast(this, 0,
                new Intent(this, UpdateInstallReceiver.class)
                        .putExtra("force", mUpdateForce),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        session.commit(done.getIntentSender());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) doInstallApk(mPendingApkFile);
            else Toast.makeText(this, "未授予安装权限", Toast.LENGTH_LONG).show();
        } else if (requestCode == REQ_MAP_PICKER && resultCode == RESULT_OK && data != null) {
            double lat = data.getDoubleExtra("latitude", 0);
            double lng = data.getDoubleExtra("longitude", 0);
            if (data.getBooleanExtra("is_clear", false)) {
                prefs.edit().putFloat("location_lat", 0f).putFloat("location_lng", 0f).apply();
                Toast.makeText(this, "已清除坐标", Toast.LENGTH_SHORT).show();
                autoSaveConfig();
            } else if (lat != 0 || lng != 0) {
                prefs.edit().putFloat("location_lat", (float) lat)
                           .putFloat("location_lng", (float) lng).apply();
                Toast.makeText(this, "✓ 选点成功: " + lat + ", " + lng, Toast.LENGTH_SHORT).show();
                autoSaveConfig();
            }
        }
    }

    // ======================== 免责声明 ========================

    private void showDisclaimerDialog() {
        final Dialog dialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#80000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = createDialogCard(0.85f);
        addIconText(card, "\u26A0\uFE0F", 40);
        addTitle(card, "免责声明", COLOR_PRIMARY);
        addMessage(card, "本软件为个人开发测试工具，完全免费。\n\n作者不对以下情况承担责任：\n• 软件闪退或异常\n• 账号被封禁或限制\n• 任何直接或间接损失\n\n请谨慎使用并自行承担风险。", 15);
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 8, 0, 0);
        addButton(btnRow, "不同意", COLOR_RED, new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); finish(); }
        }, false);
        addButton(btnRow, "同意", COLOR_PRIMARY, new View.OnClickListener() {
            public void onClick(View v) { dialog.dismiss(); prefs.edit().putBoolean("disclaimer_accepted", true).apply(); enterMainIfReady(); }
        }, false);
        card.addView(btnRow);
        root.addView(card);
        dialog.setContentView(root);
        dialog.setCancelable(false);
        dialog.show();
    }

    private void setupMainLayout() {
        permGateBody = null; // 进入主页后不再刷新权限门
        logFeatureBaseline();
        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp2px(20), getStatusBarHeight() + dp2px(14), dp2px(16), dp2px(14));
        GradientDrawable tbBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#FF5722"), Color.parseColor("#FF7043")});
        toolbar.setBackground(tbBg);
        if (Build.VERSION.SDK_INT >= 21) toolbar.setElevation(12f);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("众包小助手 Lite");
        tvTitle.setTextSize(20);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.WHITE);
        textCol.addView(tvTitle);
        tvSubTitle = new TextView(this);
        tvSubTitle.setTextSize(12);
        tvSubTitle.setTextColor(Color.parseColor("#FFE0B2"));
        tvSubTitle.setPadding(0, dp2px(2), 0, 0);
        textCol.addView(tvSubTitle);
        toolbar.addView(textCol, tp);
        updateEnvironmentStatusText();

        TextView tvVer = new TextView(this);
        tvVer.setText("v" + getAppVersionName());
        tvVer.setTextSize(11);
        tvVer.setTypeface(Typeface.DEFAULT_BOLD);
        tvVer.setTextColor(COLOR_PRIMARY);
        GradientDrawable vBg = new GradientDrawable();
        vBg.setColor(Color.WHITE);
        vBg.setCornerRadius(dp2px(12));
        tvVer.setBackground(vBg);
        tvVer.setPadding(dp2px(10), dp2px(4), dp2px(10), dp2px(4));
        toolbar.addView(tvVer);

        TextView tvSettings = new TextView(this);
        tvSettings.setText(" \u2699");
        tvSettings.setTextSize(22);
        tvSettings.setTextColor(Color.WHITE);
        tvSettings.setPadding(dp2px(10), dp2px(4), dp2px(4), dp2px(4));
        tvSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        toolbar.addView(tvSettings);

        root.addView(toolbar);

        tvUpdateHint = new TextView(this);
        tvUpdateHint.setVisibility(View.GONE);
        tvUpdateHint.setTextSize(13);
        tvUpdateHint.setTextColor(Color.WHITE);
        tvUpdateHint.setPadding(dp2px(20), dp2px(10), dp2px(20), dp2px(10));
        tvUpdateHint.setGravity(Gravity.CENTER);
        tvUpdateHint.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable hintBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#2980B9"), Color.parseColor("#3498DB")});
        tvUpdateHint.setBackground(hintBg);
        root.addView(tvUpdateHint);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        scroll.setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12));
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        container.addView(createAppCard("功能开关", COLOR_PRIMARY, APP_KEYS[0]));
        container.addView(createDelayCard());
        actionCard = createActionCard();
        container.addView(actionCard);

        scroll.addView(container);
        root.addView(scroll);
        setContentView(root);
    }

    private View createDelayCard() {
        LinearLayout card = createBaseCard();
        card.addView(createCardHeader("自动刷新延迟设置", Color.parseColor("#FF9800")));
        card.addView(divider());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp2px(16), dp2px(8), dp2px(16), dp2px(14));

        TextView delayLabel = new TextView(this);
        delayLabel.setText("自动刷新主页间隔（毫秒）");
        delayLabel.setTextSize(13);
        delayLabel.setTextColor(COLOR_TEXT_SUB);
        content.addView(delayLabel);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp2px(6), 0, 0);

        etDelay = new EditText(this);
        int savedDelay;
        try { savedDelay = prefs.getInt("mt_auto_refresh_delay", 1000); }
        catch (ClassCastException e) { savedDelay = Integer.parseInt(prefs.getString("mt_auto_refresh_delay", "1000")); }
        if (savedDelay <= 0) savedDelay = 1000;
        etDelay.setText(String.valueOf(savedDelay));
        etDelay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDelay.setTextSize(16);
        etDelay.setTextColor(COLOR_TEXT_MAIN);
        etDelay.setHint("1000 = 每秒刷新（默认）");
        etDelay.setHintTextColor(COLOR_TEXT_SUB);
        etDelay.setSelectAllOnFocus(true);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(dp2px(10));
        etBg.setColor(Color.parseColor("#F8F9FA"));
        etBg.setStroke(dp2px(1), Color.parseColor("#E0E0E0"));
        etDelay.setBackground(etBg);
        etDelay.setPadding(dp2px(12), dp2px(10), dp2px(12), dp2px(10));
        etDelay.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    int val = 1000;
                    try { val = Integer.parseInt(etDelay.getText().toString().trim()); } catch (Exception ignored) {}
                    if (val < 500) val = 500;      // 最小 500ms，防止空值/0 导致疯狂刷新
                    if (val > 10000) val = 10000;
                    etDelay.setText(String.valueOf(val));
                    prefs.edit().putInt("mt_auto_refresh_delay", val).apply();
                    autoSaveConfig();
                }
            }
        });
        inputRow.addView(etDelay, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvMs = new TextView(this);
        tvMs.setText(" ms");
        tvMs.setTextSize(14);
        tvMs.setTextColor(COLOR_TEXT_SUB);
        inputRow.addView(tvMs);

        content.addView(inputRow);

        TextView delayHint = new TextView(this);
        delayHint.setText("建议值: 1000(每秒) / 2000 / 3000 / 5000\n与自动抢单悬浮窗的刷新间隔共用同一数据，任一处修改两处同步生效");
        delayHint.setTextSize(11);
        delayHint.setTextColor(COLOR_TEXT_SUB);
        delayHint.setLineSpacing(2, 1.2f);
        delayHint.setPadding(0, dp2px(6), 0, 0);
        content.addView(delayHint);

        card.addView(content);
        return card;
    }

    private View createActionCard() {
        LinearLayout card = createBaseCard();
        card.addView(createCardHeader("快捷操作", COLOR_BLUE));
        card.addView(divider());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4));

        SharedPreferences configPrefs = getSharedPreferences("config", MODE_PRIVATE);
        // show_location_spoof: 隐藏页面中的模拟定位开关，控制快捷操作中模拟定位区域的显示与隐藏
        boolean showLocationSpoof = configPrefs.getBoolean("show_location_spoof", false);
        // mt_location_spoof: 快捷操作中的模拟定位 toggle，控制实际 hook 行为（写入 mt_config.json）
        boolean locationSpoofActive = prefs.getBoolean("mt_location_spoof", false);

        if (showLocationSpoof) {
            addSwitchRow(content, "模拟定位", "开启/关闭模拟定位功能（同时联动去除模拟位置检测）", locationSpoofActive,
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        // ★ 快捷开关：同时控制 模拟定位 + 反模拟位置检测（commit 同步落盘）
                        prefs.edit().putBoolean("mt_location_spoof", isChecked)
                                .putBoolean("mt_anti_mock", isChecked).commit();
                        autoSaveConfig();
                    }
                });

            addActionRow(content, "模拟定位选点", "设置伪装定位坐标", Color.parseColor("#FF9800"),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        Intent i = new Intent(MainActivity.this, MapPickerActivity.class);
                        i.putExtra(MapPickerActivity.EXTRA_TYPE, "location_spoof");
                        // ★ 如果用户已选过定位点，传入已保存坐标作为默认视窗中心
                        double savedLat = (double) prefs.getFloat("location_lat", 0f);
                        double savedLng = (double) prefs.getFloat("location_lng", 0f);
                        if (savedLat != 0 || savedLng != 0) {
                            i.putExtra(MapPickerActivity.EXTRA_LAT, savedLat);
                            i.putExtra(MapPickerActivity.EXTRA_LNG, savedLng);
                        }
                        startActivityForResult(i, REQ_MAP_PICKER);
                    }
                });
        }

        if (!showLocationSpoof) {
            TextView hint = new TextView(this);
            hint.setText("隐藏的小功能需要特定办法才能解锁哦");
            hint.setTextSize(14);
            hint.setTextColor(COLOR_TEXT_SUB);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp2px(16), dp2px(20), dp2px(16), dp2px(20));
            content.addView(hint);
        }

        card.addView(content);
        return card;
    }

    private void addActionRow(LinearLayout parent, String title, String subtitle, int color, View.OnClickListener listener) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.HORIZONTAL);
        wrapper.setGravity(Gravity.CENTER_VERTICAL);
        wrapper.setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12));

        View colorBar = new View(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp2px(4), dp2px(36));
        barLp.setMarginEnd(dp2px(12));
        GradientDrawable barBg = new GradientDrawable();
        barBg.setCornerRadius(dp2px(2));
        barBg.setColor(color);
        colorBar.setBackground(barBg);
        wrapper.addView(colorBar, barLp);

        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(COLOR_TEXT_MAIN);
        textArea.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextSize(11);
        tvSub.setTextColor(COLOR_TEXT_SUB);
        textArea.addView(tvSub);

        wrapper.addView(textArea);

        TextView arrow = new TextView(this);
        arrow.setText(">\uFE0F");
        arrow.setTextSize(16);
        arrow.setTextColor(COLOR_TEXT_SUB);
        wrapper.addView(arrow);

        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        wrapper.setBackgroundResource(tv.resourceId);
        wrapper.setOnClickListener(listener);

        parent.addView(wrapper);
        parent.addView(createThinDivider());
    }

    private View createThinDivider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)) {{
            setMargins(dp2px(36), 0, dp2px(14), 0);
        }});
        v.setBackgroundColor(Color.parseColor("#F2F4F4"));
        return v;
    }

    /** 功能开关操作日志（logcat + 文件，便于核对功能开关是否生效/出错） */
    private void logToggle(String feature, boolean on) {
        String msg = "[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date())
                + "] 功能开关 " + feature + " → " + (on ? "开" : "关");
        android.util.Log.e("MainToggle", msg);
        for (String dirPath : new String[]{
                "/sdcard/Download/lucky/log",
                getExternalFilesDir(null) + "/lucky_log"
        }) {
            try {
                java.io.File dir = new java.io.File(dirPath);
                if (dir.exists() || dir.mkdirs()) {
                    java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "feature.log"), true);
                    fw.write(msg + "\n");
                    fw.close();
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 应用启动时记录功能开关基线（时间戳，便于核对功能何时变动） */
    private void logFeatureBaseline() {
        String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        SharedPreferences cp = getSharedPreferences("config", MODE_PRIVATE);
        StringBuilder sb = new StringBuilder("[" + ts + "] 应用启动，功能开关基线: ");
        String[] keys = {"mt_location_spoof", "mt_auto_refresh_home", "mt_hide_speed", "mt_anti_mock",
                "mt_online_check", "mt_skip_grab_confirm", "mt_green_light", "show_location_spoof",
                "mt_auto_refresh_delay", "mt_dev_options"};
        for (int i = 0; i < keys.length; i++) {
            try {
                String k = keys[i];
                boolean v = false;
                if (prefs.contains(k)) {
                    v = "true".equals(String.valueOf(prefs.getAll().get(k)));
                } else if (cp.contains(k)) {
                    v = "true".equals(String.valueOf(cp.getAll().get(k)));
                }
                sb.append(k).append("=").append(v);
            } catch (Throwable e) {
                try { sb.append(keys[i]).append("=?"); } catch (Throwable ignored) {}
            }
            if (i < keys.length - 1) sb.append(", ");
        }
        android.util.Log.e("MainToggle", sb.toString());
        for (String dirPath : new String[]{"/sdcard/Download/lucky/log", getExternalFilesDir(null) + "/lucky_log"}) {
            try {
                java.io.File dir = new java.io.File(dirPath);
                if (dir.exists() || dir.mkdirs()) {
                    java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "feature.log"), true);
                    fw.write(sb.toString() + "\n");
                    fw.close();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void addSwitchRow(LinearLayout parent, String title, String subtitle, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp2px(8), 0, dp2px(8));

        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);
        firstRow.setPadding(dp2px(16), 0, dp2px(16), 0);

        TextView titleLabel = new TextView(this);
        titleLabel.setText(title);
        titleLabel.setTextSize(15);
        titleLabel.setTextColor(COLOR_TEXT_MAIN);
        titleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        firstRow.addView(titleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView statusBtn = new TextView(this);
        statusBtn.setTextSize(13);
        statusBtn.setTypeface(Typeface.DEFAULT_BOLD);
        statusBtn.setGravity(Gravity.CENTER);
        statusBtn.setPadding(dp2px(14), dp2px(5), dp2px(14), dp2px(5));
        updateToggleStyle(statusBtn, checked);
        firstRow.addView(statusBtn);

        row.addView(firstRow);

        TextView descLabel = new TextView(this);
        descLabel.setText(subtitle);
        descLabel.setTextSize(11);
        descLabel.setTextColor(COLOR_TEXT_SUB);
        descLabel.setPadding(dp2px(16), dp2px(4), dp2px(16), 0);
        row.addView(descLabel);

        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String tag = (String) v.getTag();
                if (tag != null) {
                    boolean newState = !Boolean.parseBoolean(tag);
                    v.setTag(String.valueOf(newState));
                    updateToggleStyle(statusBtn, newState);
                    logToggle(title, newState);
                    if (listener != null) {
                        listener.onCheckedChanged(null, newState);
                    }
                }
            }
        });
        row.setTag(String.valueOf(checked));

        TypedValue tv2 = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv2, true);
        row.setBackgroundResource(tv2.resourceId);

        parent.addView(row);
        parent.addView(createThinDivider());
    }

    private void updateToggleStyle(TextView v, boolean on) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(14));
        if (on) {
            v.setText("已开启");
            v.setTextColor(Color.WHITE);
            gd.setColor(COLOR_PRIMARY);
        } else {
            v.setText("已关闭");
            v.setTextColor(COLOR_TEXT_SUB);
            gd.setColor(Color.parseColor("#EAECEE"));
        }
        v.setBackground(gd);
    }

    private LinearLayout createCardHeader(String title, int color) {
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(12));
        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp2px(4), dp2px(16)));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(color);
        dg.setCornerRadius(dp2px(2));
        dot.setBackground(dg);
        hdr.addView(dot);
        TextView tv = new TextView(this);
        tv.setText("  " + title);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(COLOR_TEXT_MAIN);
        hdr.addView(tv);
        return hdr;
    }

    private void updateEnvironmentStatusText() {
        if (tvSubTitle == null) return;
        boolean hasSu = checkSuAvailable();
        int lsp = lsposedStatus();
        String suTxt = hasSu ? "SU权限:已获取" : "SU权限:无";
        String lsTxt, sep = "  |  ";
        int suColor = hasSu ? Color.parseColor("#66BB6A") : Color.parseColor("#FFCDD2");
        int lsColor;
        if (lsp == 2) { lsTxt = "LSPosed:已激活"; lsColor = Color.parseColor("#66BB6A"); }
        else if (lsp == 1) { lsTxt = "LSPosed:框架✓模块未确认"; lsColor = Color.parseColor("#FFD54F"); }
        else { lsTxt = "LSPosed:未激活"; lsColor = Color.parseColor("#FFCDD2"); }
        android.text.SpannableString ss = new android.text.SpannableString(suTxt + sep + lsTxt);
        ss.setSpan(new android.text.style.ForegroundColorSpan(suColor), 0, suTxt.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.ForegroundColorSpan(lsColor), suTxt.length() + sep.length(), suTxt.length() + sep.length() + lsTxt.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tvSubTitle.setText(ss);
    }

    /** 检测 su 权限：su -c id 输出含 uid=0 */
    private boolean checkSuAvailable() {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            p.waitFor();
            String out = sb.toString();
            return out.contains("uid=0") || out.contains("root");
        } catch (Throwable t) { return false; }
    }

    /**
     * LSPosed 激活状态：0=未激活 1=框架已激活(模块未确认) 2=框架+模块均已激活
     * 官方方式：libxposed service 绑定成功（onServiceBind）即模块已启用
     * 兜底：XposedBridge 类 + 心跳文件（多通道）
     */
    private int lsposedStatus() {
        // 1) 官方：模块 App 的 XposedService 绑定（框架只为启用模块下发）
        try {
            io.github.libxposed.service.XposedService svc = App.getService();
            if (svc != null) return 2;
        } catch (Throwable ignored) {}
        // 2) 兜底：XposedBridge 类存在 = 框架注入当前进程
        boolean framework = false;
        try { Class.forName("de.robv.android.xposed.XposedBridge"); framework = true; } catch (Throwable ignored) {}
        // 3) 兜底：模块心跳文件近期写入（多通道：模块App缓存 / sdcard / /data/local/tmp）
        boolean module = false;
        String[] paths = {
            "/data/user/0/" + getPackageName() + "/cache/lucky_orders/mt_module_heartbeat.txt",
            "/data/data/" + getPackageName() + "/cache/lucky_orders/mt_module_heartbeat.txt",
            "/sdcard/Download/lucky/mt_module_status.txt",
            "/data/local/tmp/mt_module_status.txt"
        };
        long now = System.currentTimeMillis();
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (f.exists()) {
                    // 心跳 90 秒内刷新 = 模块活跃
                    if (now - f.lastModified() < 90 * 1000L) { module = true; break; }
                    // 兼容：内容时间戳 30 分钟内
                    try {
                        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                        String s = r.readLine();
                        r.close();
                        if (s != null && s.startsWith("loaded|")) {
                            String[] parts = s.split("\\|");
                            if (parts.length >= 3) {
                                long ts = Long.parseLong(parts[2]);
                                if (now - ts < 30L * 60 * 1000L) { module = true; break; }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        if (module) return 2;
        if (framework) return 1;
        return 0;
    }

    private boolean checkRootSilently() {
        return false;
    }

    private LinearLayout createAppCard(String title, int theme, String[] keys) {
        LinearLayout card = createBaseCard();
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(12));
        View dot = new View(this);
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp2px(4), dp2px(16)));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(theme); dg.setCornerRadius(dp2px(2));
        dot.setBackground(dg);
        hdr.addView(dot);
        TextView tv = new TextView(this);
        tv.setText("  " + title); tv.setTextSize(16); tv.setTypeface(Typeface.DEFAULT_BOLD); tv.setTextColor(COLOR_TEXT_MAIN);
        hdr.addView(tv);
        card.addView(hdr);
        card.addView(divider());
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp2px(4), 0, dp2px(4), dp2px(8));
        for (String key : keys) list.addView(toggleRow(getLabel(key), key, theme));
        card.addView(list);
        return card;
    }

    private View toggleRow(String label, final String key, final int theme) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12));
        TypedValue tv = new TypedValue();
        getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);
        TextView txt = new TextView(this);
        txt.setText(label); txt.setTextSize(15); txt.setTextColor(COLOR_TEXT_MAIN);
        row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        final TextView btn = new TextView(this);
        btn.setTextSize(13); btn.setTypeface(Typeface.DEFAULT_BOLD); btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp2px(14), dp2px(5), dp2px(14), dp2px(5));
        boolean on = prefs.getBoolean(key, false);
        updateToggle(btn, on, theme);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean next = !prefs.getBoolean(key, false);
                prefs.edit().putBoolean(key, next).commit(); // commit 同步落盘，防进程被杀丢状态
                updateToggle(btn, next, theme);
                logToggle(key, next);
                autoSaveConfig();
            }
        });
        row.addView(btn);
        return row;
    }

    private void updateToggle(TextView v, boolean on, int theme) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(14));
        if (on) { v.setText("已开启"); v.setTextColor(Color.WHITE); gd.setColor(theme); }
        else { v.setText("已关闭"); v.setTextColor(COLOR_TEXT_SUB); gd.setColor(Color.parseColor("#EAECEE")); }
        v.setBackground(gd);
    }

    private String getLabel(String key) {
        switch (key) {
            case "mt_update": return "屏蔽强制更新";
            case "mt_watermark": return "去除个人水印";
            case "mt_time_offset": return "防派单";
            case "mt_screenshot": return "解除截屏录屏限制";
            case "mt_anti_detect": return "拦截截屏动作检测";
            case "mt_online_check": return "下线查看完整地址";
            case "mt_dev_options": return "去除开发者选项提示";
            case "mt_hide_speed": return "禁止超速检测";

            case "mt_auto_refresh_home": return "自动刷新主页";
            case "mt_anti_mock": return "去除模拟位置检测";
            case "mt_location_spoof": return "模拟定位";
            case "mt_skip_grab_confirm": return "跳过二次抢单确认";
            case "mt_green_light": return "锁定去除红绿灯检测";
            case "mt_env_guard": return "去除异常环境检测";
            default: return key;
        }
    }

    private final java.util.concurrent.ExecutorService configSaver = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean saveQueued = false;

    private void autoSaveConfig() {
        // ★ 在 UI 线程捕获延时值，避免后台线程跨线程读 EditText
        int capDelayVal;
        try { capDelayVal = prefs.getInt("mt_auto_refresh_delay", 0); }
        catch (ClassCastException e) { capDelayVal = Integer.parseInt(prefs.getString("mt_auto_refresh_delay", "0")); }
        if (etDelay != null) {
            try {
                String ds = etDelay.getText().toString().trim();
                int d = Integer.parseInt(ds);
                if (d < 500) d = 500;   // 最小 500ms，防止 0/空值导致 HookInit 按 500ms 疯狂刷新
                if (d > 10000) d = 10000;
                capDelayVal = d;
                prefs.edit().putInt("mt_auto_refresh_delay", capDelayVal).apply();
            } catch (Exception e) { /* 保留 prefs 中的默认值 */ }
        }
        final int finalDelayVal = capDelayVal;

        // ★ 单线程串行 + 合并：快速连点不会并发写、不会旧快照覆盖新状态
        //   （任务真正执行时会重新读取最新 prefs，故只需保证不堆积重复任务）
        synchronized (this) {
            if (saveQueued) return; // 已有待执行任务，它执行时会读到最新开关状态
            saveQueued = true;
        }
        configSaver.execute(new Runnable() {
            /** 写入配置；成功返回 true */
            private boolean writeFile(String path, String jsonStr) {
                // 方法1: 通过 su 写入（临时文件写到应用缓存目录，绕过权限限制）
                try {
                    java.io.File tmpDir = getCacheDir();
                    if (tmpDir == null) tmpDir = new java.io.File("/data/local/tmp/");
                    java.io.File tmpFile = new java.io.File(tmpDir, "su_config_" + System.nanoTime() + ".tmp");
                    java.io.FileWriter fw = new java.io.FileWriter(tmpFile);
                    fw.write(jsonStr);
                    fw.close();
                    java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cp " + tmpFile.getAbsolutePath() + " " + path});
                    boolean completed = p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!completed) p.destroyForcibly();
                    tmpFile.delete();
                    if (completed && p.exitValue() == 0) {
                        // ★ 确保目标App进程可读（su cp 创建的文件 owner 是 root:root:600）
                        try { Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 " + path}).waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                        try { Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 " + path}).waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                        // 校验文件可读
                        try {
                            java.io.File f2 = new java.io.File(path);
                            if (f2.exists() && f2.canRead()) { android.util.Log.i("ConfigWriter", "[OK] su 写入: " + path); return true; }
                        } catch (Throwable ignored) {}
                        // 文件存在但不可读（chmod 失败）→ 视为失败，交给直接写入兜底
                    }
                } catch (Throwable ignored) {}
                // 方法2: 直接写入
                try {
                    java.io.File f = new java.io.File(path);
                    java.io.File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    java.io.FileWriter fw = new java.io.FileWriter(f);
                    fw.write(jsonStr);
                    fw.close();
                    f.setReadable(true, false); // 确保其他 App（美团/HookInit）可读
                    android.util.Log.i("ConfigWriter", "[OK] 直接写入成功: " + path + " (" + jsonStr.length() + " bytes)");
                    return true;
                } catch (Throwable e) {
                    android.util.Log.e("ConfigWriter", "[FAIL] 写入失败: " + path + " - " + e.getMessage());
                    return false;
                }
            }
            public void run() {
                synchronized (MainActivity.this) { saveQueued = false; }
                try {
                    int delayVal = finalDelayVal;
                    // 合并已有配置，保留浮窗写入的键
                    JSONObject config = loadExistingConfig();
                    if (config == null) config = new JSONObject();
                    for (String[] g : APP_KEYS) {
                        for (String k : g) config.put(k, prefs.getBoolean(k, false));
                    }
                    try { config.put("location_lat", (double) prefs.getFloat("location_lat", 0f)); } catch (Exception e) { config.put("location_lat", 0.0); }
                    try { config.put("location_lng", (double) prefs.getFloat("location_lng", 0f)); } catch (Exception e) { config.put("location_lng", 0.0); }
                    config.put("mt_auto_refresh_delay", delayVal);
                    try { config.put("location_speed", (double) prefs.getFloat("location_speed", 0f)); } catch (Exception e) { config.put("location_speed", 0.0); }

                    // ★ mt_anti_mock 已在上方 APP_KEYS 循环里按 prefs 独立写入，不再强制联动 mt_location_spoof
                    boolean locationSpoofActive = prefs.getBoolean("mt_location_spoof", false);
                    config.put("mt_location_spoof", locationSpoofActive);

                    String jsonStr = config.toString(2);

                    boolean ok = writeFile(HOOK_CONFIG_PATH, jsonStr);
                    boolean systemOk = writeFile(SYSTEM_CONFIG_PATH, jsonStr);
                    if (!ok || !systemOk) {
                        // 重试一次（su 偶发超时/失败）
                        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                        if (!ok) ok = writeFile(HOOK_CONFIG_PATH, jsonStr);
                        if (!systemOk) systemOk = writeFile(SYSTEM_CONFIG_PATH, jsonStr);
                    }
                    if (!ok || !systemOk) {
                        final String err = "配置保存失败，功能可能不生效，请检查 Root 授权";
                        runOnUiThread(new Runnable() { public void run() { toast(err); } });
                    }
                } catch (Throwable ignored) {}
            }
        });
    }
    private JSONObject loadExistingConfig() {
        try {
            // 优先通过 su 读取
            try {
                java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + HOOK_CONFIG_PATH});
                java.io.BufferedReader sr = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String l; while ((l = sr.readLine()) != null) sb.append(l);
                p.waitFor();
                sr.close();
                if (sb.length() > 10) return new JSONObject(sb.toString());
            } catch (Throwable ignored) {}
            // 回退：直接文件读取
            java.io.File f = new java.io.File(HOOK_CONFIG_PATH);
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String l; while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            if (sb.length() > 10) return new JSONObject(sb.toString());
            return null;
        } catch (Throwable t) { return null; }
    }

    private void showNotice(String title, String msg, String btnText, View.OnClickListener btnListener) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#99000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp2px(22);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(dp2px(18));
        card.setBackground(bg);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.86f);
        card.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(25f);
        TextView t = new TextView(this);
        t.setText(title); t.setTextSize(18); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(COLOR_TEXT_MAIN);
        t.setPadding(0, 0, 0, dp2px(12));
        card.addView(t);
        View line = new View(this);
        line.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)));
        line.setBackgroundColor(Color.parseColor("#EAECEE"));
        card.addView(line);
        TextView m = new TextView(this);
        m.setText(msg); m.setTextSize(14); m.setTextColor(Color.parseColor("#566573"));
        m.setLineSpacing(6, 1.2f); m.setPadding(0, dp2px(14), 0, dp2px(20));
        card.addView(m);
        if (btnText != null) {
            Button b = new Button(this);
            b.setText(btnText); b.setTextSize(15); b.setTextColor(Color.WHITE);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setPadding(0, dp2px(10), 0, dp2px(10));
            GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor("#FF5722"), Color.parseColor("#FF7043")});
            gd.setCornerRadius(dp2px(12)); b.setBackground(gd);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { d.dismiss(); if (btnListener != null) btnListener.onClick(v); }
            });
            card.addView(b);
        }
        TextView close = new TextView(this);
        close.setText("知道了"); close.setTextSize(13); close.setTextColor(COLOR_TEXT_SUB);
        close.setGravity(Gravity.CENTER); close.setPadding(0, dp2px(12), 0, 0);
        close.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { d.dismiss(); } });
        card.addView(close);
        root.addView(card);
        d.setContentView(root);
        d.setCancelable(true);
        d.show();
    }

    private LinearLayout permGateBody;

    /** 全部权限齐全 → 直接进主页；否则 → 显示权限检测申请页 */
    private void enterMainIfReady() {
        if (isAllPermissionsGranted()) setupMainLayout();
        else setupPermissionGate();
    }

    /** 判断是否所有必要权限已授予（决定是否显示权限门） */
    private boolean isAllPermissionsGranted() {
        try { if (!checkSuAvailable()) return false; } catch (Throwable t) { return false; }
        try { if (lsposedStatus() != 2) return false; } catch (Throwable t) { return false; }
        try { if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return false; } catch (Throwable t) { return false; }
        try { if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false; } catch (Throwable t) { return false; }
        try { if (!checkInstallPermission()) return false; } catch (Throwable t) { return false; }
        try { if (checkSelfPermission(android.Manifest.permission.INTERNET) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false; } catch (Throwable t) { return false; }
        return true;
    }

    /** 进入主页面前的权限检测申请页（全屏） */
    private void setupPermissionGate() {
        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp2px(20), getStatusBarHeight() + dp2px(24), dp2px(20), dp2px(18));
        GradientDrawable hb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[]{Color.parseColor("#7E57C2"), Color.parseColor("#9575CD")});
        header.setBackground(hb);
        TextView tvT = new TextView(this);
        tvT.setText("权限检测与授权");
        tvT.setTextSize(22);
        tvT.setTypeface(Typeface.DEFAULT_BOLD);
        tvT.setTextColor(Color.WHITE);
        header.addView(tvT);
        TextView tvS = new TextView(this);
        tvS.setText("部分权限未授予，软件可能无法保证正常运行，请逐项授权后点击下方【进入应用】");
        tvS.setTextSize(12);
        tvS.setTextColor(Color.parseColor("#FFE082"));
        tvS.setPadding(0, dp2px(4), 0, 0);
        header.addView(tvS);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp2px(16), dp2px(16), dp2px(16), dp2px(4));
        permGateBody = body;
        refreshPermissionGate();
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setPadding(dp2px(20), dp2px(12), dp2px(20), dp2px(24));
        Button btnEnter = new Button(this);
        btnEnter.setText("进入应用");
        btnEnter.setTextSize(16);
        btnEnter.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(22));
        bg.setColor(Color.parseColor("#7E57C2"));
        btnEnter.setBackground(bg);
        btnEnter.setOnClickListener(v -> setupMainLayout());
        bottom.addView(btnEnter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(48)));
        root.addView(bottom);

        setContentView(root);
    }

    private void refreshPermissionGate() {
        if (permGateBody == null) return;
        // 若权限已全部补齐 → 自动进入主页
        if (isAllPermissionsGranted()) { setupMainLayout(); return; }
        permGateBody.removeAllViews();
        // 1. SU(Root) 权限
        final boolean su = checkSuAvailable();
        addPermRow(permGateBody, "SU(Root)权限", su, "已获取", "未获取",
            "需在 Magisk/KernelSU 中授权本应用", v -> {
                try { Runtime.getRuntime().exec(new String[]{"su", "-c", "id"}); } catch (Throwable ignored) {}
                toast("已触发 Root 授权请求，请在弹窗中点击允许");
                refreshPermissionGate();
            });
        // 2. LSPosed：已激活且美团在作用域 → 打开管理器；否则 → 一键申请美团作用域
        final int lsp = lsposedStatus();
        boolean meituanInScope = false;
        io.github.libxposed.service.XposedService svcTmp = App.getService();
        if (svcTmp != null) {
            try {
                java.util.List<String> sc = svcTmp.getScope();
                meituanInScope = sc != null && sc.contains("com.sankuai.meituan.dispatch.crowdsource");
            } catch (Throwable ignored) {}
        }
        final boolean inScope = meituanInScope;
        addPermRow(permGateBody, "LSPosed 框架", lsp == 2 && inScope, "已激活", lsp == 2 ? "已启用·美团未勾选" : (lsp == 1 ? "模块未确认" : "未激活"),
            (lsp == 2 && inScope) ? "点按打开管理器" : "点按一键申请美团作用域", v -> {
                if (lsp == 2 && inScope) openLsposedManager();
                else requestMeituanScope();
            });
        // 3. 文件管理权限（所有文件访问）
        final boolean storage = android.os.Environment.isExternalStorageManager();
        addPermRow(permGateBody, "文件管理权限", storage, "已授权", "未授权",
            "读写采集/订单日志所需", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); } catch (Throwable t2) {}
                }
            });
        // 4. 定位权限
        final boolean loc = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        addPermRow(permGateBody, "定位权限", loc, "已授权", "未授权",
            "地图/位置相关功能所需", v -> {
                try {
                    requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION}, 2001);
                } catch (Throwable ignored) {}
            });
        // 5. 安装未知应用（AppOps 实际状态）
        final boolean install = checkInstallPermission();
        addPermRow(permGateBody, "安装未知应用", install, "已允许", "未允许",
            "自动更新安装APK所需，以系统设置页开关为准", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {}
            });
        // 5.5 悬浮窗权限（完整版自动抢单悬浮窗所需）
        final boolean overlay = android.provider.Settings.canDrawOverlays(this);
        addPermRow(permGateBody, "悬浮窗权限", overlay, "已授权", "未授权",
            "自动抢单悬浮窗显示所需", v -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Throwable t) {}
            });
        // 6. 网络权限
        final boolean net = checkSelfPermission(android.Manifest.permission.INTERNET)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        addPermRow(permGateBody, "网络权限", net, "正常", "受限",
            "网络是进入APP的前提（不可关闭）", v -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); } catch (Throwable t) {}
            });
        addMessage(permGateBody, "部分授权后需返回本页刷新，全部就绪后点击下方【进入应用】。", 12);
    }

    /** 安装未知应用权限：以 AppOps 实际状态为准（canRequestPackageInstalls 对部分安装方式会误报） */
    private boolean checkInstallPermission() {
        try {
            android.app.AppOpsManager aom = (android.app.AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (aom == null) return getPackageManager().canRequestPackageInstalls();
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow("android:request_install_packages", android.os.Process.myUid(), getPackageName());
            } else {
                mode = aom.checkOpNoThrow("android:request_install_packages", android.os.Process.myUid(), getPackageName());
            }
            return mode == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            try { return getPackageManager().canRequestPackageInstalls(); } catch (Throwable t2) { return true; }
        }
    }

    /** 单行权限状态 + 去授权按钮 */
    private void addPermRow(LinearLayout parent, String title, boolean ok, String okText, String failText,
                            String sub, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp2px(14), dp2px(10), dp2px(14), dp2px(10));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(15);
        tvTitle.setTextColor(0xFF303030);
        txt.addView(tvTitle);
        TextView tvStatus = new TextView(this);
        tvStatus.setText((ok ? okText : failText) + (sub == null ? "" : " · " + sub));
        tvStatus.setTextSize(11);
        tvStatus.setTextColor(ok ? Color.parseColor("#66BB6A") : Color.parseColor("#E53935"));
        txt.addView(tvStatus);
        row.addView(txt);
        Button btn = new Button(this);
        btn.setText(ok ? "✓ 已授权" : "去授权");
        btn.setTextSize(13);
        btn.setTextColor(ok ? Color.parseColor("#66BB6A") : Color.WHITE);
        btn.setPadding(dp2px(12), 0, dp2px(12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(18));
        bg.setColor(ok ? Color.parseColor("#E8F5E9") : Color.parseColor("#7E57C2"));
        btn.setBackground(bg);
        btn.setOnClickListener(listener);
        row.addView(btn);
        parent.addView(row);
    }

    /** 自动检测 LSPosed 管理器：包名含 lsposed 关键词 */
    private String findLsposedManagerPkg() {
        try {
            for (android.content.pm.PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                String p = pi.packageName;
                if (p != null && p.toLowerCase().contains("lsposed")) return p;
            }
        } catch (Throwable ignored) {}
        return "org.lsposed.manager";
    }

    /** 一键申请把美团加入模块作用域（现代 API requestScope，框架弹确认框，无需管理器） */
    private void requestMeituanScope() {
        StringBuilder diag = new StringBuilder();
        final io.github.libxposed.service.XposedService svc = App.getService();
        diag.append("requestScope svc=").append(svc != null ? "bound" : "null").append("\n");
        if (svc == null) {
            logLspDiag(diag.toString());
            toast("LSPosed 框架未连接，请先在管理器启用本模块");
            return;
        }
        try {
            java.util.List<String> sc = svc.getScope();
            diag.append("current scope: ").append(sc).append("\n");
            if (sc != null && sc.contains("com.sankuai.meituan.dispatch.crowdsource")) {
                logLspDiag(diag.toString());
                toast("美团已在作用域内，无需重复申请");
                return;
            }
            diag.append("requestScope -> ").append("com.sankuai.meituan.dispatch.crowdsource").append("\n");
            logLspDiag(diag.toString());
            svc.requestScope(java.util.Collections.singletonList("com.sankuai.meituan.dispatch.crowdsource"),
                new io.github.libxposed.service.XposedService.OnScopeEventListener() {
                    public void onScopeRequestApproved(java.util.List<String> pkgs) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                logLspDiag("requestScope APPROVED: " + pkgs + "\n");
                                toast("美团已加入作用域 ✓ 重启美团生效");
                                refreshPermissionGate();
                            }
                        });
                    }
                    public void onScopeRequestFailed(String msg) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                logLspDiag("requestScope FAILED: " + msg + "\n");
                                toast("申请失败: " + (msg == null ? "被拒绝" : msg));
                            }
                        });
                    }
                });
        } catch (Throwable t) {
            diag.append("requestScope throw: ").append(t).append("\n");
            logLspDiag(diag.toString());
            toast("申请异常: " + t.getMessage());
        }
    }

    private void openLsposedManager() {
        String moduleUri = "module://" + getPackageName() + ":0";
        StringBuilder diag = new StringBuilder();
        boolean lspdAlive = false;
        diag.append("lsposedStatus=").append(lsposedStatus()).append("\n");
        // 0) 环境诊断：lsposed/lspd 包 + lspd 守护进程
        try {
            for (android.content.pm.PackageInfo pi : getPackageManager().getInstalledPackages(0)) {
                String p = pi.packageName;
                if (p != null && (p.toLowerCase().contains("lsposed") || p.toLowerCase().contains("lspd"))) {
                    diag.append("pkg: ").append(p).append("\n");
                }
            }
        } catch (Throwable ignored) {}
        try {
            String out = execOut("su", "-c", "ps -A | grep -i lspd");
            lspdAlive = !out.trim().isEmpty();
            diag.append("lspd proc: ").append(out.trim().isEmpty() ? "not found" : out.trim()).append("\n");
        } catch (Throwable ignored) {}
        // ① 只按 LAUNCH_MANAGER 类别启动（绝不盲启 com.android.shell/.BugreportWarningActivity——那是AOSP真实入口）
        String[] cmds = {
            "am start -W -a android.intent.action.MAIN -c org.lsposed.manager.LAUNCH_MANAGER --user 0",
            "am start -W -a android.intent.action.MAIN -c org.lsposed.manager.LAUNCH_MANAGER -d " + moduleUri
        };
        for (int round = 1; round >= 0; round--) { // round=1 先 su，round=0 再普通
            for (String c : cmds) {
                try {
                    String[] argv = round == 1 ? new String[]{"su", "-c", c} : c.split(" ");
                    java.lang.Process p = Runtime.getRuntime().exec(argv);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    StringBuilder out = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) out.append(line).append("\n");
                    p.waitFor();
                    String res = out.toString().trim();
                    diag.append((round == 1 ? "su " : "") + "am: ").append(res).append("\n");
                    if (res.contains("Status: ok") || (res.contains("Starting:") && !res.contains("Error"))) {
                        toast("已唤起 LSPosed");
                        logLspDiag(diag.toString());
                        return;
                    }
                } catch (Throwable t) { diag.append("am exec err: ").append(t).append("\n"); }
            }
        }
        // ② 动态查询响应 LAUNCH_MANAGER 的 Activity
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN);
            probe.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
            java.util.List<android.content.pm.ResolveInfo> ris = getPackageManager().queryIntentActivities(probe, 0);
            if (ris != null && !ris.isEmpty()) {
                android.content.pm.ActivityInfo ai = ris.get(0).activityInfo;
                diag.append("query LAUNCH_MANAGER -> ").append(ai.packageName).append("/").append(ai.name).append("\n");
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
                i.setComponent(new android.content.ComponentName(ai.packageName, ai.name));
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                toast("已唤起 LSPosed");
                logLspDiag(diag.toString());
                return;
            }
            diag.append("query LAUNCH_MANAGER -> empty\n");
        } catch (Throwable t) { diag.append("query err: ").append(t).append("\n"); }
        // ③ org.lsposed.manager 包名
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (i != null) {
                startActivity(i);
                toast("已唤起 LSPosed");
                logLspDiag(diag.toString());
                return;
            }
            diag.append("pkg org.lsposed.manager not found\n");
        } catch (Throwable t) { diag.append("pkg err: ").append(t).append("\n"); }
        // ④ 关键词
        String pkg = findLsposedManagerPkg();
        if (!"org.lsposed.manager".equals(pkg)) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    startActivity(i);
                    toast("已唤起 LSPosed");
                    logLspDiag(diag.toString());
                    return;
                }
            } catch (Throwable ignored) {}
        }
        logLspDiag(diag.toString());
        // ⑤ 全失败：明确诊断对话框（区分 框架未激活 / 框架在但管理器未安装）
        try {
            String msg;
            if (lspdAlive) {
                msg = "LSPosed 框架已激活（lspd 进程在运行），但【管理器 App 未安装或未就绪】，所以打不开管理器。\n\n" +
                    "解决办法：安装 LSPosed Manager（包名 org.lsposed.manager）后打开一次，再回来启用本模块。\n\n" +
                    "检测详情已写入 /sdcard/Download/lucky/log/lsp_launch.log";
            } else {
                msg = "未检测到 LSPosed 框架运行（lspd 进程不存在），可能模块未启用或未重启。\n\n" +
                    "请检查：\n" +
                    "1. 在 Magisk/KernelSU 中确认已启用 LSPosed (Zygisk) 模块\n" +
                    "2. 重启手机后再试\n\n" +
                    "检测详情已写入 /sdcard/Download/lucky/log/lsp_launch.log";
            }
            new android.app.AlertDialog.Builder(this)
                .setTitle("LSPosed 未就绪")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
        } catch (Throwable ignored) {}
    }

    private String execOut(String... argv) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(argv);
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) out.append(line).append("\n");
            p.waitFor();
            return out.toString();
        } catch (Throwable t) { return ""; }
    }

    /** 记录 LSPosed 唤起诊断（logcat + sdcard 日志） */
    private void logLspDiag(String content) {
        try {
            android.util.Log.e("LspLaunch", content);
            java.io.File dir = new java.io.File("/sdcard/Download/lucky/log");
            if (dir.exists() || dir.mkdirs()) {
                java.io.FileWriter fw = new java.io.FileWriter(new java.io.File(dir, "lsp_launch.log"), true);
                fw.write("[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "]\n" + content + "\n");
                fw.close();
            }
        } catch (Throwable ignored) {}
    }

    private void toast(String msg) {
        try { android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }

    private LinearLayout createBaseCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ setMargins(0, 0, 0, dp2px(14)); }});
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(14)); bg.setColor(Color.WHITE); bg.setStroke(dp2px(1), Color.parseColor("#EAECEE"));
        c.setBackground(bg);
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)) {{ setMargins(dp2px(16), 0, dp2px(16), 0); }});
        v.setBackgroundColor(Color.parseColor("#F2F4F4"));
        return v;
    }

    private Button createStyledButton(String text, int bg) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(15); b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(0, dp2px(12), 0, dp2px(12));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(24)); gd.setColor(bg);
        b.setBackground(gd);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ setMargins(0, dp2px(10), 0, dp2px(6)); }});
        return b;
    }

    private LinearLayout createDialogCard(float pct) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp2px(20);
        c.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(16)); bg.setColor(Color.WHITE);
        c.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) c.setElevation(12f);
        int w = (int) (getResources().getDisplayMetrics().widthPixels * pct);
        c.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
        return c;
    }

    private void addIconText(LinearLayout p, String txt, int sz) {
        TextView tv = new TextView(this); tv.setText(txt); tv.setTextSize(sz); tv.setGravity(Gravity.CENTER); p.addView(tv);
    }

    private void addTitle(LinearLayout p, String txt, int color) {
        TextView tv = new TextView(this); tv.setText(txt); tv.setTextSize(20); tv.setTextColor(color); tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp2px(10), 0, dp2px(10)); p.addView(tv);
    }

    private void addMessage(LinearLayout p, String txt, int sz) {
        TextView tv = new TextView(this); tv.setText(txt); tv.setTextSize(sz); tv.setTextColor(Color.parseColor("#444444"));
        tv.setLineSpacing(4, 1.2f); tv.setPadding(dp2px(10), 0, dp2px(10), dp2px(14)); p.addView(tv);
    }

    private void addButton(LinearLayout p, String txt, int bg, View.OnClickListener l, boolean full) {
        Button b = new Button(this);
        b.setText(txt); b.setTextSize(full ? 18 : 15); b.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(full ? dp2px(20) : dp2px(16)); gd.setColor(bg); b.setBackground(gd);
        b.setPadding(0, full ? dp2px(14) : dp2px(10), 0, full ? dp2px(14) : dp2px(10));
        LinearLayout.LayoutParams lp;
        if (full) { lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); lp.setMargins(0, dp2px(10), 0, 0); }
        else { lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); lp.setMargins(p.getChildCount() == 0 ? 0 : dp2px(6), 0, p.getChildCount() == 0 ? dp2px(6) : 0, 0); }
        b.setLayoutParams(lp); b.setOnClickListener(l); p.addView(b);
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private Context getContext() { return this; }

    private String getAppVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "1.5.0"; }
    }

    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}