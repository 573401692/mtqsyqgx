package com.mtqsyqgx.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.GeolocationPermissions;

import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;

public class MapPickerActivity extends SecureActivity {

    private WebView webView;
    private int statusBarHeight = 0;
    private android.app.Dialog gaodeDialog;
    private static final String PREFS_NAME = "mtq_map_prefs";
    private static final String KEY_GAODE_KEY = "gaode_api_key";
    private static final String KEY_GAODE_JSCODE = "gaode_security_jscode";
    private static final String KEY_HAS_SHOWN_KEY_DIALOG = "has_shown_key_dialog";
    private static final String CONFIG_PATH = "/data/local/tmp/mt_config.json";
    private static final int REQ_LOCATION = 1001;

    public static final String EXTRA_TYPE = "type";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_RANGE = "range";

    public static final String RESULT_LAT = "result_lat";
    public static final String RESULT_LNG = "result_lng";
    public static final String RESULT_ADDRESS = "result_address";
    public static final String MAP_PICKER_ACTION = "com.mtqsyqgx.MAP_PICKER_RESULT";

    private String pendingGaodeKey = null;
    private String pendingGaodeJscode = null;

    private boolean permissionRequested = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // 不使用FLAG_FULLSCREEN，让悬浮窗(TYPE_APPLICATION_OVERLAY)可以显示在上方
        getWindow().setStatusBarColor(0xFF111827);
        getWindow().setNavigationBarColor(0xFF111827);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            // API < 30: use legacy flags for edge-to-edge
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        }

        // 获取状态栏高度（像素），注入给WebView做padding（注意setDecorFitsSystemWindows=false）
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }

        // 先请求运行时定位权限（不return，同时初始化WebView）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionRequested = true;
                requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQ_LOCATION);
            }
        }
        // 无论是否已授权都立即初始化（不等权限回调，避免白屏/权限弹窗被遮挡）
        initAfterPermission();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        // 权限结果回来后通知WebView刷新定位
        if (webView != null) {
            webView.evaluateJavascript(
                "if(typeof locateMe==='function')locateMe();", null);
        }
    }

    private void initAfterPermission() {
        String gaodeKey = readKeyFromConfigFile();
        String gaodeJscode = readJscodeFromConfigFile();
        if (gaodeKey.isEmpty()) {
            SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            gaodeKey = sp.getString(KEY_GAODE_KEY, "");
            gaodeJscode = sp.getString(KEY_GAODE_JSCODE, "");
            boolean hasShownDialog = sp.getBoolean(KEY_HAS_SHOWN_KEY_DIALOG, false);
            // 第一次进入且没有key时弹窗询问
            if (gaodeKey.isEmpty() && !hasShownDialog) {
                showGaodeKeyDialog();
                return;
            }
        }
        // 有key或已弹过窗，直接初始化
        initWebView(gaodeKey, gaodeJscode);
    }

    private void showGaodeKeyDialog() {
        // 使用自定义 Dialog 替代 AlertDialog，彻底消除默认的窗口遮罩层
        gaodeDialog = new android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        gaodeDialog.setCancelable(false);

        // 外层容器：半透明黑色遮罩 + 居中卡片
        FrameLayout outer = new FrameLayout(this);
        outer.setBackgroundColor(0x99000000); // 60% 黑色遮罩
        outer.setFitsSystemWindows(false);

        // 内层卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(28), dp(24), dp(28), dp(20));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(dp(20));
        cardBg.setColor(0xFF1F2937);
        cardBg.setStroke(dp(1), 0xFF374151);
        card.setBackground(cardBg);

        // 标题图标 + 标题行
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dp(4));

        TextView icon = new TextView(this);
        icon.setText("\uD83D\uDCF1"); // 📱
        icon.setTextSize(20);
        titleRow.addView(icon);

        TextView title = new TextView(this);
        title.setText("  设置高德地图 Key");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        titleRow.addView(title);

        card.addView(titleRow);

        // 副标题
        TextView subtitle = new TextView(this);
        subtitle.setText("有 Key 可使用高德搜索，无 Key 自动切换 Nominatim");
        subtitle.setTextColor(0xFF6B7280);
        subtitle.setTextSize(12);
        subtitle.setPadding(0, 0, 0, dp(14));
        card.addView(subtitle);

        // 说明文字
        TextView desc = new TextView(this);
        desc.setText("• 申请地址: console.amap.com\n• 创建应用 > 服务平台选「Web端(JS API)」\n• Key 用于搜索和逆地理编码\n• 地图底图始终使用免费瓦片");
        desc.setTextColor(0xFF9CA3AF);
        desc.setTextSize(11);
        desc.setLineSpacing(0, 1.6f);
        desc.setPadding(dp(12), dp(10), dp(12), dp(14));
        GradientDrawable descBg = new GradientDrawable();
        descBg.setCornerRadius(dp(10));
        descBg.setColor(0xFF111827);
        descBg.setStroke(dp(1), 0xFF374151);
        desc.setBackground(descBg);
        card.addView(desc);

        // JS API Key 输入框
        TextView lblKey = new TextView(this);
        lblKey.setText("JS API Key");
        lblKey.setTextColor(0xFF9CA3AF);
        lblKey.setTextSize(12);
        lblKey.setPadding(0, dp(2), 0, dp(6));
        card.addView(lblKey);
        EditText inputKey = new EditText(this);
        inputKey.setHint("输入高德 JS API Key");
        inputKey.setHintTextColor(0xFF4B5563);
        inputKey.setTextColor(Color.WHITE);
        inputKey.setTextSize(14);
        inputKey.setInputType(InputType.TYPE_CLASS_TEXT);
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(10));
        inputBg.setColor(0xFF111827);
        inputBg.setStroke(dp(1), 0xFF374151);
        inputKey.setBackground(inputBg);
        inputKey.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyLp.setMargins(0, 0, 0, dp(12));
        card.addView(inputKey, keyLp);

        // 安全密钥输入框
        TextView lblJscode = new TextView(this);
        lblJscode.setText("安全密钥 (securityJsCode)");
        lblJscode.setTextColor(0xFF9CA3AF);
        lblJscode.setTextSize(12);
        lblJscode.setPadding(0, dp(2), 0, dp(6));
        card.addView(lblJscode);
        EditText inputJscode = new EditText(this);
        inputJscode.setHint("输入安全密钥");
        inputJscode.setHintTextColor(0xFF4B5563);
        inputJscode.setTextColor(Color.WHITE);
        inputJscode.setTextSize(14);
        inputJscode.setInputType(InputType.TYPE_CLASS_TEXT);
        GradientDrawable inputBg2 = new GradientDrawable();
        inputBg2.setCornerRadius(dp(10));
        inputBg2.setColor(0xFF111827);
        inputBg2.setStroke(dp(1), 0xFF374151);
        inputJscode.setBackground(inputBg2);
        inputJscode.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams jsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jsLp.setMargins(0, 0, 0, dp(20));
        card.addView(inputJscode, jsLp);

        // 按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        // 跳过按钮
        TextView btnSkip = new TextView(this);
        btnSkip.setText("跳过");
        btnSkip.setTextColor(0xFF9CA3AF);
        btnSkip.setTextSize(14);
        btnSkip.setPadding(dp(20), dp(12), dp(20), dp(12));
        btnSkip.setOnClickListener(v -> {
            gaodeDialog.dismiss();
            initWebView("", "");
        });
        btnRow.addView(btnSkip);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        btnRow.addView(spacer);

        // 确认按钮
        TextView btnConfirm = new TextView(this);
        btnConfirm.setText("确认");
        btnConfirm.setTextColor(Color.WHITE);
        btnConfirm.setTextSize(14);
        btnConfirm.setTypeface(null, Typeface.BOLD);
        GradientDrawable confirmBg = new GradientDrawable();
        confirmBg.setCornerRadius(dp(10));
        confirmBg.setColor(0xFF10B981);
        btnConfirm.setBackground(confirmBg);
        btnConfirm.setPadding(dp(28), dp(12), dp(28), dp(12));
        btnConfirm.setOnClickListener(v -> {
            String key = inputKey.getText().toString().trim();
            String jscode = inputJscode.getText().toString().trim();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(KEY_GAODE_KEY, key)
                .putString(KEY_GAODE_JSCODE, jscode)
                .putBoolean(KEY_HAS_SHOWN_KEY_DIALOG, true)
                .apply();
            saveKeyToConfigFile(key, jscode);
            gaodeDialog.dismiss();
            initWebView(key, jscode);
        });
        btnRow.addView(btnConfirm);

        card.addView(btnRow);

        // 将卡片居中放入 outer
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.leftMargin = dp(24);
        cardLp.rightMargin = dp(24);
        outer.addView(card, cardLp);

        gaodeDialog.setContentView(outer);

        // 点击遮罩区域不可关闭
        outer.setOnClickListener(v -> {});

        gaodeDialog.show();

        // 设置弹窗宽度
        Window window = gaodeDialog.getWindow();
        if (window != null) {
            window.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            window.setGravity(Gravity.CENTER);
            window.setDimAmount(0f); // 自己管理遮罩，不使用系统 dim
        }

        // 自动聚焦输入框弹出键盘
        inputKey.requestFocus();
        inputKey.postDelayed(() -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(inputKey, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }, 300);
    }

    /** 从 /data/local/tmp/mt_config.json 读取 gaode_key */
    private String readKeyFromConfigFile() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) return "";
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            JSONObject json = new JSONObject(content);
            return json.optString("gaode_key", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 从 /data/local/tmp/mt_config.json 读取 gaode_jscode */
    private String readJscodeFromConfigFile() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.exists()) return "";
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            JSONObject json = new JSONObject(content);
            return json.optString("gaode_jscode", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 将 key + jscode 写入 /data/local/tmp/mt_config.json（先写缓存文件再root复制） */
    private void saveKeyToConfigFile(String key, String jscode) {
        try {
            JSONObject json = new JSONObject();
            File f = new File(CONFIG_PATH);
            if (f.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()));
                json = new JSONObject(content);
            }
            json.put("gaode_key", key);
            json.put("gaode_jscode", jscode);
            // 写入缓存临时文件，再用root复制（避免shell转义问题）
            File tmp = new File(getCacheDir(), "mt_config_tmp.json");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp);
            fos.write(json.toString(2).getBytes());
            fos.close();
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{
                "su", "-c", "cp " + tmp.getAbsolutePath() + " " + CONFIG_PATH
                    + " && chmod 644 " + CONFIG_PATH
            });
            p.waitFor();
            tmp.delete();
        } catch (Exception e) {
            // 写入失败不影响功能
        }
    }

    private void initWebView(String gaodeKey, String gaodeJscode) {
        webView = new WebView(this);
        setContentView(webView);

        // 获取状态栏高度
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setGeolocationEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // 注入状态栏高度(dp)和导航栏高度(dp)
                float density = getResources().getDisplayMetrics().density;
                int statusBarDp = statusBarHeight > 0 ? (int)(statusBarHeight / density) : 24;
                // 获取导航栏高度
                int navBarHeight = 0;
                int navBarId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (navBarId > 0) {
                    navBarHeight = getResources().getDimensionPixelSize(navBarId);
                }
                int navBarDp = navBarHeight > 0 ? (int)(navBarHeight / density) : 16;
                view.evaluateJavascript(
                    "(function(){var sb='" + statusBarDp + "px';" +
                    "var nb='" + navBarDp + "px';" +
                    "document.documentElement.style.setProperty('--sbh',sb);" +
                    "document.documentElement.style.setProperty('--nbh',nb);" +
                    "var sp=document.getElementById('searchPanel');" +
                    "if(sp){sp.style.paddingTop=sb;sp.style.paddingBottom='10px';" +
                    "setTimeout(function(){" +
                    "var h=sp.offsetHeight;" +
                    "var rp=document.getElementById('rangePanel');" +
                    "var rt=document.getElementById('rangeTag');" +
                    "if(rp)rp.style.top=(h+12)+'px';" +
                    "if(rt)rt.style.top=(h-30)+'px';" +
                    "},100);}})()", null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
        });

        webView.setBackgroundColor(0xFF111827);
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        String type = getIntent().getStringExtra(EXTRA_TYPE);
        if (type == null) type = "pickup";
        double lat = getIntent().getDoubleExtra(EXTRA_LAT, 0);
        double lng = getIntent().getDoubleExtra(EXTRA_LNG, 0);
        float range = getIntent().getFloatExtra(EXTRA_RANGE, 3.0f);

        // URL编码key和jscode，避免特殊字符破坏URL
        String encodedKey = gaodeKey != null ? URLEncoder.encode(gaodeKey) : "";
        String encodedJscode = gaodeJscode != null ? URLEncoder.encode(gaodeJscode) : "";

        String url = "file:///android_asset/map_picker.html?type=" + type
                + "&lat=" + lat
                + "&lng=" + lng
                + "&range=" + range
                + "&key=" + encodedKey
                + "&jscode=" + encodedJscode;
        webView.loadUrl(url);
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void showKeySettings() {
            runOnUiThread(() -> showGaodeKeyDialog());
        }

        @JavascriptInterface
        public void onConfirm(double lat, double lng, String address, double range) {
            // 广播地图选点结果（显式包名，Android 14+ 隐式广播限制下也能可靠送达）
            Intent resultIntent = new Intent(MAP_PICKER_ACTION);
            resultIntent.setPackage(getPackageName());
            resultIntent.putExtra(RESULT_LAT, lat);
            resultIntent.putExtra(RESULT_LNG, lng);
            resultIntent.putExtra(RESULT_ADDRESS, address != null ? address : "");
            resultIntent.putExtra(EXTRA_TYPE, getIntent().getStringExtra(EXTRA_TYPE));
            resultIntent.putExtra(EXTRA_RANGE, range);
            sendBroadcast(resultIntent);
            // setResult 给 MainActivity (startActivityForResult)
            Intent data = new Intent();
            data.putExtra("latitude", lat);
            data.putExtra("longitude", lng);
            data.putExtra("address", address != null ? address : "");
            setResult(RESULT_OK, data);
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            Intent resultIntent = new Intent(MAP_PICKER_ACTION);
            resultIntent.setPackage(getPackageName());
            resultIntent.putExtra(RESULT_LAT, 0.0);
            resultIntent.putExtra(RESULT_LNG, 0.0);
            resultIntent.putExtra(RESULT_ADDRESS, "");
            resultIntent.putExtra(EXTRA_TYPE, getIntent().getStringExtra(EXTRA_TYPE));
            sendBroadcast(resultIntent);
            setResult(RESULT_CANCELED);
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
}
