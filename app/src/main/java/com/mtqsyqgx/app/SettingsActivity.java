package com.mtqsyqgx.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;

import org.json.*;
import java.io.*;
import java.net.*;

public class SettingsActivity extends SecureActivity {

    private SharedPreferences prefs;
    private static final String HOOK_CONFIG_PATH = "/data/local/tmp/mt_config.json";
    private static final String LOG_DIR = "/storage/emulated/0/Download/lucky/log/";
    private static final String UPDATE_URL = "https://gitee.com/awana/mtqsyqgx/raw/master/update.json";
    private static final int REQ_INSTALL_PERMISSION = 1001;
    private static final String HIDDEN_SETTINGS_PREFS = "mtq_hidden_settings";
    private static final String KEY_HIDDEN_ENTRY_ENABLED = "hidden_entry_enabled";
    private DownloadManager dm;
    private long downloadId;
    private BroadcastReceiver downloadReceiver;
    private TextView tvUpdateStatus;

    private static final int COLOR_PRIMARY = Color.parseColor("#FF5722");
    private static final int COLOR_RED = Color.parseColor("#E53935");
    private static final int COLOR_BG = Color.parseColor("#F4F6F9");
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT_MAIN = Color.parseColor("#2C3E50");
    private static final int COLOR_TEXT_SUB = Color.parseColor("#7F8C8D");
    private static final int COLOR_GREEN = Color.parseColor("#4CAF50");
    private static final int COLOR_BLUE = Color.parseColor("#2196F3");
    private static final int COLOR_PURPLE = Color.parseColor("#9C27B0");

    // 隐藏页面入口卡片
    private LinearLayout hiddenEntryCard;

    // 隐藏入口触发状态机
    private static final int STATE_IDLE = 0;
    private static final int STATE_APP_NAME_CLICKED_1 = 1;
    private static final int STATE_APP_NAME_CLICKED_2 = 2;
    private static final int STATE_APP_NAME_CLICKED_3 = 3; // 完成第一步：点击众包小助手3次
    private static final int STATE_UPDATE_CLICKED_1 = 4;
    private static final int STATE_UPDATE_CLICKED_2 = 5; // 完成第二步：点击检查更新2次
    // 第三步：长按清除配置，完成后触发隐藏页面
    
    private int mSecretState = STATE_IDLE;
    private long mLastSecretActionTime = 0;
    private static final long SECRET_ACTION_TIMEOUT = 2000; // 2秒内连续操作有效

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
        downloadId = prefs.getLong("pending_download_id", 0);
        setupSettingsLayout();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 刷新隐藏页面入口的可见性
        if (hiddenEntryCard != null) {
            SharedPreferences hiddenPrefs = getSharedPreferences(HIDDEN_SETTINGS_PREFS, MODE_PRIVATE);
            boolean showHiddenEntry = hiddenPrefs.getBoolean(KEY_HIDDEN_ENTRY_ENABLED, false);
            hiddenEntryCard.setVisibility(showHiddenEntry ? View.VISIBLE : View.GONE);
        }
    }

    private void setupSettingsLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // 工具栏
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp2px(12), getStatusBarHeight() + dp2px(14), dp2px(20), dp2px(14));
        GradientDrawable tbBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Color.parseColor("#FF5722"), Color.parseColor("#FF7043")});
        toolbar.setBackground(tbBg);
        if (Build.VERSION.SDK_INT >= 21) toolbar.setElevation(10f);

        // 返回按钮（现代风格：半透明圆角矩形 + 箭头）
        TextView tvBack = new TextView(this);
        tvBack.setText("\u2039");
        tvBack.setTextSize(26);
        tvBack.setTextColor(Color.WHITE);
        tvBack.setGravity(Gravity.CENTER);
        tvBack.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable backBg = new GradientDrawable();
        backBg.setCornerRadius(dp2px(10));
        backBg.setColor(Color.parseColor("#22FFFFFF"));
        tvBack.setBackground(backBg);
        int backSize = dp2px(34);
        tvBack.setLayoutParams(new LinearLayout.LayoutParams(backSize, backSize));
        tvBack.setPadding(dp2px(2), 0, dp2px(4), 0);
        tvBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        toolbar.addView(tvBack);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("设置");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setPadding(dp2px(14), 0, 0, 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        toolbar.addView(tvTitle, tp);

        root.addView(toolbar);

        // 内容区域
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        scroll.setPadding(dp2px(14), dp2px(12), dp2px(14), dp2px(12));
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        // 清除配置卡片
        LinearLayout clearCard = createBaseCard();
        clearCard.addView(createCardHeader("清除配置", COLOR_RED));
        clearCard.addView(divider());

        LinearLayout clearContent = new LinearLayout(this);
        clearContent.setOrientation(LinearLayout.VERTICAL);
        clearContent.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(16));

        TextView clearDesc = new TextView(this);
        clearDesc.setText("此操作将删除所有功能配置并重置免责声明，不可恢复！\n\n包括：");
        clearDesc.setTextSize(14);
        clearDesc.setTextColor(COLOR_TEXT_SUB);
        clearDesc.setLineSpacing(2, 1.3f);
        clearContent.addView(clearDesc);

        TextView clearList = new TextView(this);
        clearList.setText("  \u2022  所有功能开关设置\n  \u2022  地图选点坐标\n  \u2022  自动刷新延迟配置\n  \u2022  免责声明同意状态\n  \u2022  更新配置缓存");
        clearList.setTextSize(13);
        clearList.setTextColor(COLOR_TEXT_MAIN);
        clearList.setLineSpacing(2, 1.4f);
        clearList.setPadding(dp2px(6), dp2px(8), 0, dp2px(12));
        clearContent.addView(clearList);

        Button btnClear = createStyledButton("清除所有配置", COLOR_RED);
        btnClear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showClearConfirmDialog(); }
        });
        // 长按"清除所有配置"按钮触发隐藏入口（组合操作第三步）
        btnClear.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                long now = System.currentTimeMillis();
                // 超时重置状态机
                if (now - mLastSecretActionTime > SECRET_ACTION_TIMEOUT) {
                    mSecretState = STATE_IDLE;
                }
                mLastSecretActionTime = now;
                
                // 状态机逻辑：只有在完成第二步后才能触发
                if (mSecretState == STATE_UPDATE_CLICKED_2) {
                    mSecretState = STATE_IDLE; // 重置状态机
                    checkAndShowHiddenEntry(); // 触发隐藏页面
                } else {
                    // 非预期状态，重置
                    mSecretState = STATE_IDLE;
                }
                return true;
            }
        });
        clearContent.addView(btnClear);

        // 清除全部日志按钮（置于清除配置下方）
        Button btnClearLogs = createStyledButton("清除全部日志", COLOR_BLUE);
        btnClearLogs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showLogClearConfirmDialog(); }
        });
        clearContent.addView(btnClearLogs);

        clearCard.addView(clearContent);
        container.addView(clearCard);

        // 检查更新卡片
        LinearLayout updateCard = createBaseCard();
        LinearLayout updateHeader = createCardHeader("检查更新", COLOR_GREEN);
        // 点击"检查更新"标题两次触发隐藏入口（组合操作第二步）
        updateHeader.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                // 超时重置状态机
                if (now - mLastSecretActionTime > SECRET_ACTION_TIMEOUT) {
                    mSecretState = STATE_IDLE;
                }
                mLastSecretActionTime = now;
                
                // 状态机逻辑：只有在完成第一步后才能继续
                if (mSecretState == STATE_APP_NAME_CLICKED_3) {
                    mSecretState = STATE_UPDATE_CLICKED_1;
                } else if (mSecretState == STATE_UPDATE_CLICKED_1) {
                    mSecretState = STATE_UPDATE_CLICKED_2; // 完成第二步
                } else {
                    // 非预期状态，重置
                    mSecretState = STATE_IDLE;
                }
            }
        });
        updateCard.addView(updateHeader);
        updateCard.addView(divider());

        LinearLayout updateContent = new LinearLayout(this);
        updateContent.setOrientation(LinearLayout.VERTICAL);
        updateContent.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(16));

        TextView updateDesc = new TextView(this);
        updateDesc.setText("手动检查是否有新版本可用。\n应用启动时也会自动检查更新。");
        updateDesc.setTextSize(14);
        updateDesc.setTextColor(COLOR_TEXT_SUB);
        updateDesc.setLineSpacing(2, 1.3f);
        updateContent.addView(updateDesc);

        tvUpdateStatus = new TextView(this);
        tvUpdateStatus.setTextSize(13);
        tvUpdateStatus.setTextColor(COLOR_TEXT_SUB);
        tvUpdateStatus.setPadding(0, dp2px(10), 0, dp2px(4));
        updateContent.addView(tvUpdateStatus);

        Button btnCheckUpdate = createStyledButton("立即检查更新", COLOR_GREEN);
        btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { checkUpdateManual(); }
        });
        updateContent.addView(btnCheckUpdate);

        updateCard.addView(updateContent);
        container.addView(updateCard);

        // 保存配置和日志卡片
        LinearLayout backupCard = createBaseCard();
        backupCard.addView(createCardHeader("保存配置和日志", COLOR_BLUE));
        backupCard.addView(divider());
        LinearLayout backupContent = new LinearLayout(this);
        backupContent.setOrientation(LinearLayout.VERTICAL);
        backupContent.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(16));
        TextView backupDesc = new TextView(this);
        backupDesc.setText("将当前配置和日志打包为压缩包，保存到 /sdcard/Download/lucky/，供开发者排查问题。");
        backupDesc.setTextSize(14);
        backupDesc.setTextColor(COLOR_TEXT_SUB);
        backupDesc.setLineSpacing(2, 1.3f);
        backupContent.addView(backupDesc);
        Button btnSaveBackup = createStyledButton("打包保存", COLOR_BLUE);
        btnSaveBackup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { saveConfigAndLogs(); }
        });
        backupContent.addView(btnSaveBackup);
        backupCard.addView(backupContent);
        container.addView(backupCard);

        // 关于项目卡片
        LinearLayout aboutCard = createBaseCard();
        aboutCard.addView(createCardHeader("关于项目", COLOR_BLUE));
        aboutCard.addView(divider());

        LinearLayout aboutContent = new LinearLayout(this);
        aboutContent.setOrientation(LinearLayout.VERTICAL);
        aboutContent.setPadding(dp2px(16), dp2px(20), dp2px(16), dp2px(20));
        aboutContent.setGravity(Gravity.CENTER);

        TextView appName = new TextView(this);
        appName.setText("众包小助手");
        appName.setTextSize(20);
        appName.setTypeface(Typeface.DEFAULT_BOLD);
        appName.setTextColor(COLOR_TEXT_MAIN);
        appName.setGravity(Gravity.CENTER);
        // 点击"众包小助手"三次触发隐藏入口（组合操作第一步）
        appName.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                // 超时重置状态机
                if (now - mLastSecretActionTime > SECRET_ACTION_TIMEOUT) {
                    mSecretState = STATE_IDLE;
                }
                mLastSecretActionTime = now;
                
                // 状态机逻辑
                if (mSecretState == STATE_IDLE) {
                    mSecretState = STATE_APP_NAME_CLICKED_1;
                } else if (mSecretState == STATE_APP_NAME_CLICKED_1) {
                    mSecretState = STATE_APP_NAME_CLICKED_2;
                } else if (mSecretState == STATE_APP_NAME_CLICKED_2) {
                    mSecretState = STATE_APP_NAME_CLICKED_3; // 完成第一步
                } else {
                    // 非预期状态，重置
                    mSecretState = STATE_IDLE;
                }
            }
        });
        aboutContent.addView(appName);

        TextView appVersion = new TextView(this);
        appVersion.setText("v" + getAppVersionName());
        appVersion.setTextSize(14);
        appVersion.setTextColor(COLOR_PRIMARY);
        appVersion.setTypeface(Typeface.DEFAULT_BOLD);
        appVersion.setPadding(0, dp2px(6), 0, dp2px(16));
        appVersion.setGravity(Gravity.CENTER);
        aboutContent.addView(appVersion);

        Button btnJoinGroup = createStyledButton("加入交流群", COLOR_GREEN);
        btnJoinGroup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { joinGroup(); }
        });
        aboutContent.addView(btnJoinGroup);

        aboutCard.addView(aboutContent);
        container.addView(aboutCard);

        // 隐藏页面入口卡片
        SharedPreferences hiddenPrefs = getSharedPreferences(HIDDEN_SETTINGS_PREFS, MODE_PRIVATE);
        boolean showHiddenEntry = hiddenPrefs.getBoolean(KEY_HIDDEN_ENTRY_ENABLED, false);
        hiddenEntryCard = createBaseCard();
        hiddenEntryCard.addView(createCardHeader("隐藏功能", COLOR_PURPLE));
        hiddenEntryCard.addView(divider());

        LinearLayout hiddenEntryContent = new LinearLayout(this);
        hiddenEntryContent.setOrientation(LinearLayout.VERTICAL);
        hiddenEntryContent.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));

        Button btnHiddenEntry = createStyledButton("进入隐藏设置页面", COLOR_PURPLE);
        btnHiddenEntry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(SettingsActivity.this, HiddenSettingsActivity.class);
                startActivity(intent);
            }
        });
        hiddenEntryContent.addView(btnHiddenEntry);

        TextView hiddenEntryHint = new TextView(this);
        hiddenEntryHint.setText("点击进入隐藏功能设置页面");
        hiddenEntryHint.setTextSize(12);
        hiddenEntryHint.setTextColor(COLOR_TEXT_SUB);
        hiddenEntryHint.setPadding(0, dp2px(8), 0, 0);
        hiddenEntryHint.setGravity(Gravity.CENTER);
        hiddenEntryContent.addView(hiddenEntryHint);

        hiddenEntryCard.addView(hiddenEntryContent);
        hiddenEntryCard.setVisibility(showHiddenEntry ? View.VISIBLE : View.GONE);
        container.addView(hiddenEntryCard);

        // 底部信息卡片
        LinearLayout infoCard = createBaseCard();
        LinearLayout infoContent = new LinearLayout(this);
        infoContent.setOrientation(LinearLayout.VERTICAL);
        infoContent.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));
        infoContent.setGravity(Gravity.CENTER);

        TextView copyright = new TextView(this);
        copyright.setText("\u00A9 2026 众包小软件\n本软件完全免费，仅供学习交流使用");
        copyright.setTextSize(13);
        copyright.setTextColor(COLOR_TEXT_SUB);
        copyright.setLineSpacing(2, 1.3f);
        copyright.setGravity(Gravity.CENTER);
        infoContent.addView(copyright);

        TextView techInfo = new TextView(this);
        techInfo.setText("\n技术栈: Xposed/LSPosed + Android\n目标应用: 美团众包");
        techInfo.setTextSize(12);
        techInfo.setTextColor(Color.parseColor("#95A5A6"));
        techInfo.setLineSpacing(2, 1.2f);
        techInfo.setGravity(Gravity.CENTER);
        infoContent.addView(techInfo);

        infoCard.addView(infoContent);
        container.addView(infoCard);

        // 底部间距
        View bottomSpace = new View(this);
        bottomSpace.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(20)));
        container.addView(bottomSpace);

        scroll.addView(container);
        root.addView(scroll);
        setContentView(root);
    }

    private void checkUpdateManual() {
        tvUpdateStatus.setText("正在检查更新...");
        tvUpdateStatus.setTextColor(COLOR_TEXT_SUB);

        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL(UPDATE_URL);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream()));
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
                    final int localVersion = getLocalVersionCode();

                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (serverVersion > localVersion) {
                                tvUpdateStatus.setText("发现新版本 v" + versionName);
                                tvUpdateStatus.setTextColor(COLOR_PRIMARY);
                                showUpdateDialog(versionName, updateLog, apkUrl, force);
                            } else {
                                tvUpdateStatus.setText("当前已是最新版本");
                                tvUpdateStatus.setTextColor(Color.parseColor("#4CAF50"));
                            }
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            tvUpdateStatus.setText("检查更新失败，请检查网络连接");
                            tvUpdateStatus.setTextColor(COLOR_RED);
                        }
                    });
                }
            }
        }).start();
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
                public void onClick(View v) { dialog.dismiss(); startDownload(apkUrl, version); }
            }, true);
            dialog.setCancelable(false);
        } else {
            addButton(btnRow, "稍后更新", Color.parseColor("#555555"), new View.OnClickListener() {
                public void onClick(View v) { dialog.dismiss(); }
            }, false);
            addButton(btnRow, "立即更新", COLOR_PRIMARY, new View.OnClickListener() {
                public void onClick(View v) { dialog.dismiss(); startDownload(apkUrl, version); }
            }, false);
            dialog.setCancelable(true);
        }
        card.addView(btnRow);
        root.addView(card);
        dialog.setContentView(root);
        dialog.show();
    }

    private void startDownload(String url, String version) {
        dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
        req.setTitle("众包小软件更新");
        req.setDescription("正在下载 " + version);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "peisong_" + version + ".apk");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI | DownloadManager.Request.NETWORK_MOBILE);
        downloadId = dm.enqueue(req);
        prefs.edit().putLong("pending_download_id", downloadId).apply();
        Toast.makeText(this, "开始下载新版本…", Toast.LENGTH_LONG).show();
        if (downloadReceiver != null) { try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {} }
        downloadReceiver = new BroadcastReceiver() {
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) installApk();
            }
        };
        registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
    }

    private void installApk() {
        if (downloadId == 0) { Toast.makeText(this, "下载任务不存在", Toast.LENGTH_LONG).show(); return; }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())), REQ_INSTALL_PERMISSION);
                return;
            }
        }
        Uri apkUri = dm.getUriForDownloadedFile(downloadId);
        if (apkUri == null) { Toast.makeText(this, "下载文件不存在", Toast.LENGTH_LONG).show(); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apkUri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); prefs.edit().remove("pending_download_id").apply(); }
        catch (ActivityNotFoundException e) { Toast.makeText(this, "无法打开安装器", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_INSTALL_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (getPackageManager().canRequestPackageInstalls()) installApk();
            else Toast.makeText(this, "未授予安装权限", Toast.LENGTH_LONG).show();
        }
    }

    private void showClearConfirmDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#80000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = createDialogCard(0.82f);
        addIconText(card, "\u26A0\uFE0F", 36);
        addTitle(card, "确定要清除吗？", COLOR_RED);
        addMessage(card, "此操作将删除所有功能配置并重置免责声明，不可恢复！", 14);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp2px(4), 0, 0);
        addButton(row, "取消", Color.parseColor("#555555"), new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        }, false);
        addButton(row, "确定清除", COLOR_RED, new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); executeClear(); }
        }, false);
        card.addView(row);
        root.addView(card);
        d.setContentView(root);
        d.setCancelable(false);
        d.show();
    }

    private void executeClear() {
        prefs.edit().clear().apply();
        getSharedPreferences("config", MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences("mtq_map_prefs", MODE_PRIVATE).edit().clear().apply();
        getSharedPreferences(HIDDEN_SETTINGS_PREFS, MODE_PRIVATE).edit().clear().apply();
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 用 su 删除配置文件（文件由 root 创建，普通进程无权直接删）
                    execSu("rm -f " + HOOK_CONFIG_PATH);
                    // 也清理旧路径（兼容）
                    execSu("rm -f /sdcard/mt_config.json");
                    // 删除状态文件
                    execSu("rm -f /data/local/tmp/mt_module_status.txt");

                    // 删除日志目录下的文件
                    execSu("rm -rf " + LOG_DIR + "/*");

                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "已清除所有配置和日志，即将退出应用", Toast.LENGTH_LONG).show();
                        new android.os.Handler().postDelayed(new Runnable() {
                            public void run() {
                                android.os.Process.killProcess(android.os.Process.myPid());
                                System.exit(0);
                            }
                        }, 1500);
                    } });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "清除失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    } });
                }
            }
        }).start();
    }

    /** 清除全部日志：二次确认弹窗（与清除配置同款 UI） */
    private void showLogClearConfirmDialog() {
        final Dialog d = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#80000000"));
        root.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout card = createDialogCard(0.82f);
        addIconText(card, "\u26A0\uFE0F", 36);
        addTitle(card, "确定清除日志？", COLOR_RED);
        addMessage(card, "此操作将删除所有运行日志（Hook日志、悬浮窗日志），不可恢复！", 14);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp2px(4), 0, 0);
        addButton(row, "取消", Color.parseColor("#555555"), new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); }
        }, false);
        addButton(row, "确定清除", COLOR_RED, new View.OnClickListener() {
            public void onClick(View v) { d.dismiss(); executeClearLogs(); }
        }, false);
        card.addView(row);
        root.addView(card);
        d.setContentView(root);
        d.setCancelable(false);
        d.show();
    }

    private void executeClearLogs() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    // 1) 直接 Java 删除（无需root，模块App有 MANAGE_EXTERNAL_STORAGE 可删 sdcard 全部）
                    deleteRecursive(new java.io.File("/sdcard/Download/lucky/log"));
                    deleteRecursive(new java.io.File("/storage/emulated/0/Download/lucky/log"));
                    // 清理历史导出备份 zip（旧日志包，一并删除）
                    try {
                        java.io.File lucky = new java.io.File("/sdcard/Download/lucky");
                        java.io.File[] zips = lucky.listFiles();
                        if (zips != null) for (java.io.File z : zips) if (z.getName().endsWith(".zip")) z.delete();
                    } catch (Throwable ignored) {}
                    // orders.log（Android/media 隔离区）
                    deleteRecursive(new java.io.File("/sdcard/Android/media/com.sankuai.meituan.dispatch.crowdsource/LSPilot/Plugin/GetNewTaskData"));
                    // 模块App自身缓存订单文件
                    deleteRecursive(new java.io.File("/data/user/0/com.mtqsyqgx.app/cache/lucky_orders"));
                    // 2) su 兜底（root 环境删除受保护路径；无root时sdcard已由上面删掉）
                    execSu("rm -rf " + LOG_DIR);
                    execSu("rm -rf /data/user/0/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log");
                    execSu("rm -rf /data/data/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log");
                    execSu("rm -rf /sdcard/Android/data/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log");
                    execSu("rm -rf /storage/emulated/0/Android/data/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log");
                    execSu("rm -rf /data/local/tmp/mt_logs");
                    execSu("rm -rf /sdcard/Android/media/com.sankuai.meituan.dispatch.crowdsource/LSPilot/Plugin/GetNewTaskData");
                    execSu("rm -rf /storage/emulated/0/Android/media/com.sankuai.meituan.dispatch.crowdsource/LSPilot/Plugin/GetNewTaskData");
                    execSu("rm -rf /data/user/0/com.mtqsyqgx.app/cache/lucky_orders");
                    execSu("rm -rf /data/data/com.mtqsyqgx.app/cache/lucky_orders");
                    execSu("rm -f /data/local/tmp/mt_orders.json /data/local/tmp/mt_grab_signal.json /data/local/tmp/mt_module_status.txt");
                    execSu("rm -f /data/local/tmp/su_write_*.tmp");
                    execSu("rm -f /data/local/tmp/su_orders_*.tmp");
                    // 3) 重建日志目录
                    new java.io.File(LOG_DIR).mkdirs();
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "已清除全部日志", Toast.LENGTH_SHORT).show();
                    } });
                } catch (Throwable t) {
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "清除日志失败: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                    } });
                }
            }
        }).start();
    }

    /** 打包保存配置和日志到 /sdcard/Download/lucky/backup_xxx.zip（供开发者查看） */
    private void saveConfigAndLogs() {
        new Thread(new Runnable() {
            public void run() {
                java.io.File tmp = null;
                try {
                    // 1) 输出目录（默认 /sdcard/Download/lucky，失败回退应用外部缓存）
                    java.io.File outDir = new java.io.File("/sdcard/Download/lucky");
                    if (!outDir.exists() && !outDir.mkdirs()) {
                        java.io.File alt = getExternalFilesDir(null);
                        if (alt == null) alt = getCacheDir();
                        outDir = new java.io.File(alt, "lucky");
                        if (!outDir.exists()) outDir.mkdirs();
                    }
                    final java.io.File zipFile = new java.io.File(outDir,
                            "backup_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".zip");

                    // 2) 暂存目录
                    tmp = new java.io.File(getCacheDir(), "lucky_backup_tmp");
                    if (tmp.exists()) deleteRecursive(tmp);
                    tmp.mkdirs();
                    java.io.File cfgDir = new java.io.File(tmp, "config");
                    java.io.File prefsDir = new java.io.File(tmp, "prefs");
                    java.io.File logsDir = new java.io.File(tmp, "logs");
                    cfgDir.mkdirs(); prefsDir.mkdirs(); logsDir.mkdirs();

                    // 3) 收集配置/状态/信号文件（su cp，文件不存在则忽略）
                    execSu("cp -f /data/local/tmp/mt_config.json " + cfgDir.getAbsolutePath() + "/");
                    execSu("cp -f /sdcard/mt_config.json " + cfgDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/local/tmp/mt_module_status.txt " + cfgDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/local/tmp/mt_orders.json " + cfgDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/local/tmp/mt_grab_signal.json " + cfgDir.getAbsolutePath() + "/");
                    // 4) 收集日志目录
                    execSu("cp -rf /storage/emulated/0/Download/lucky/log " + logsDir.getAbsolutePath() + "/");
                    execSu("cp -rf /data/local/tmp/mt_logs " + logsDir.getAbsolutePath() + "/");
                    execSu("cp -rf /data/user/0/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log " + logsDir.getAbsolutePath() + "/");
                    execSu("cp -rf /data/data/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_log " + logsDir.getAbsolutePath() + "/");
                    // 5) 收集 App 自身 SharedPreferences
                    execSu("cp -f /data/user/0/" + getPackageName() + "/shared_prefs/hook_config.xml " + prefsDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/user/0/" + getPackageName() + "/shared_prefs/config.xml " + prefsDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/user/0/" + getPackageName() + "/shared_prefs/mtq_map_prefs.xml " + prefsDir.getAbsolutePath() + "/");
                    execSu("cp -f /data/user/0/" + getPackageName() + "/shared_prefs/mtq_hidden_settings.xml " + prefsDir.getAbsolutePath() + "/");

                    // 6) README 说明
                    java.io.File readme = new java.io.File(tmp, "README.txt");
                    java.io.FileWriter rw = new java.io.FileWriter(readme);
                    int vc = 0;
                    try { vc = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Throwable ignored) {}
                    rw.write("众包小助手 v" + getAppVersionName() + " (build " + vc + ")\n");
                    rw.write("导出时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "\n");
                    rw.write("Android: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")\n");
                    rw.write("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + "\n\n");
                    rw.write("目录说明:\n  config/   模块配置与状态文件\n  prefs/    应用设置(SharedPreferences)\n  logs/     运行日志\n");
                    rw.close();

                    // ★ 功能配置导出/变更日志（统一写 sdcard 日志，便于核对功能变动时间）
                    try {
                        java.io.File lg = new java.io.File("/sdcard/Download/lucky/log");
                        if (lg.exists() || lg.mkdirs()) {
                            java.io.FileWriter lw = new java.io.FileWriter(new java.io.File(lg, "feature.log"), true);
                            lw.write("[" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "] 功能配置导出/备份完成\n");
                            lw.close();
                        }
                    } catch (Throwable ignored) {}

                    // 7) 打包 zip
                    zipDir(tmp, zipFile);

                    // 8) 清理暂存
                    deleteRecursive(tmp);
                    tmp = null;
                    final String path = zipFile.getAbsolutePath();
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "已保存: " + path, Toast.LENGTH_LONG).show();
                    } });
                } catch (Throwable t) {
                    if (tmp != null) { try { deleteRecursive(tmp); } catch (Throwable ignored) {} }
                    final String msg = t.getMessage();
                    runOnUiThread(new Runnable() { public void run() {
                        Toast.makeText(SettingsActivity.this, "保存失败: " + msg, Toast.LENGTH_SHORT).show();
                    } });
                }
            }
        }).start();
    }

    /** 递归压缩目录为 zip（保留相对路径） */
    private void zipDir(java.io.File srcDir, java.io.File zipFile) throws Exception {
        java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(zipFile));
        try {
            java.util.List<java.io.File> all = new java.util.ArrayList<>();
            collectFiles(srcDir, all);
            for (java.io.File f : all) {
                String rel = f.getAbsolutePath().substring(srcDir.getAbsolutePath().length() + 1).replace('\\', '/');
                zos.putNextEntry(new java.util.zip.ZipEntry(rel));
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                fis.close();
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
    }

    private void collectFiles(java.io.File dir, java.util.List<java.io.File> out) {
        java.io.File[] fs = dir.listFiles();
        if (fs == null) return;
        for (java.io.File f : fs) {
            if (f.isDirectory()) collectFiles(f, out);
            else out.add(f);
        }
    }

    private void deleteRecursive(java.io.File f) {
        if (f == null) return;
        if (f.isDirectory()) {
            java.io.File[] fs = f.listFiles();
            if (fs != null) for (java.io.File c : fs) deleteRecursive(c);
        }
        f.delete();
    }

    /** 加入交流群：拉取 update.json 的 Joingroup/JoingroupUrl，复制群号并打开群链接 */
    private void joinGroup() {
        new Thread(new Runnable() {
            public void run() {
                String group = null;
                String url = null;
                try {
                    URL u = new URL(UPDATE_URL);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(8000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    conn.disconnect();
                    JSONObject json = new JSONObject(sb.toString());
                    group = json.optString("Joingroup", "");
                    url = json.optString("JoingroupUrl", "");
                } catch (Throwable t) {
                    // 网络失败，走下方提示
                }
                final String fGroup = (group == null || group.trim().isEmpty()) ? null : group.trim();
                final String fUrl = (url == null || url.trim().isEmpty()) ? null : url.trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (fGroup == null) {
                            Toast.makeText(SettingsActivity.this, "未获取到群信息，请稍后重试", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        // 复制群号到剪贴板
                        try {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("group", fGroup));
                        } catch (Throwable ignored) {}
                        // 打开群链接
                        if (fUrl != null) {
                            try {
                                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(fUrl));
                                startActivity(Intent.createChooser(i, "打开交流群"));
                            } catch (Throwable t) {
                                Toast.makeText(SettingsActivity.this, "已复制群号 " + fGroup + "，浏览器无法打开可自行搜索", Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                        Toast.makeText(SettingsActivity.this, "已复制群号 " + fGroup + "，浏览器无法打开可自行搜索", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    /** 执行 su 命令 */
    private static String execSu(String cmd) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    // ======================== 工具方法 ========================

    private int getLocalVersionCode() {
        try { return (int) getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    private String getAppVersionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return "1.5.0"; }
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

    private View divider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)) {{ setMargins(dp2px(16), 0, dp2px(16), 0); }});
        v.setBackgroundColor(Color.parseColor("#F2F4F4"));
        return v;
    }

    private Button createStyledButton(String text, int bg) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(14); b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(0, dp2px(10), 0, dp2px(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp2px(20)); gd.setColor(bg);
        b.setBackground(gd);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) {{ setMargins(0, dp2px(12), 0, 0); }});
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

    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private void checkAndShowHiddenEntry() {
        // 检查是否已触发过隐藏入口
        SharedPreferences hiddenPrefs = getSharedPreferences(HIDDEN_SETTINGS_PREFS, MODE_PRIVATE);
        boolean alreadyTriggered = hiddenPrefs.getBoolean("secret_triggered", false);

        if (!alreadyTriggered) {
            // 首次触发，标记为已触发，默认关闭隐藏入口
            hiddenPrefs.edit()
                .putBoolean("secret_triggered", true)
                .putBoolean(KEY_HIDDEN_ENTRY_ENABLED, false)
                .apply();
        }

        // 打开隐藏设置页面
        Intent intent = new Intent(this, HiddenSettingsActivity.class);
        startActivity(intent);
    }
}