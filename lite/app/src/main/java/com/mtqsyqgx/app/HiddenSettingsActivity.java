package com.mtqsyqgx.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class HiddenSettingsActivity extends SecureActivity {

    private SharedPreferences hiddenPrefs;
    private SharedPreferences configPrefs;

    private static final String HIDDEN_SETTINGS_PREFS = "mtq_hidden_settings";
    private static final String CONFIG_PREFS = "config";
    private static final String KEY_HIDDEN_ENTRY_ENABLED = "hidden_entry_enabled";
    private static final String KEY_LOCATION_SPOOF = "show_location_spoof";

    // UI颜色
    private static final int COLOR_BG = Color.parseColor("#F4F6F9");
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_TEXT_MAIN = Color.parseColor("#2C3E50");
    private static final int COLOR_TEXT_SUB = Color.parseColor("#7F8C8D");
    private static final int COLOR_PRIMARY = Color.parseColor("#FF5722");
    private static final int COLOR_RED = Color.parseColor("#E53935");
    private static final int COLOR_PURPLE = Color.parseColor("#9C27B0");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 设置状态栏透明，与主页面保持一致
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        hiddenPrefs = getSharedPreferences(HIDDEN_SETTINGS_PREFS, MODE_PRIVATE);
        configPrefs = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE);
        setupLayout();
    }

    private void setupLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // 工具栏
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp2px(12), getStatusBarHeight() + dp2px(14), dp2px(20), dp2px(14));
        GradientDrawable tbBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#FF5722"), Color.parseColor("#FF7043")});
        toolbar.setBackground(tbBg);

        TextView tvBack = new TextView(this);
        tvBack.setText("\u2190");
        tvBack.setTextSize(22);
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
        tvTitle.setText("隐藏入口设置");
        tvTitle.setTextSize(19);
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

        // 页面入口开关卡片
        LinearLayout entryCard = createCard();
        entryCard.addView(createCardHeader("页面入口", COLOR_PURPLE));
        entryCard.addView(createDivider());

        LinearLayout entryContent = new LinearLayout(this);
        entryContent.setOrientation(LinearLayout.VERTICAL);
        entryContent.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));

        // 使用统一的开关样式
        entryContent.addView(createSwitchRow("是否开启页面入口", "在设置页面添加隐藏设置入口",
                hiddenPrefs.getBoolean(KEY_HIDDEN_ENTRY_ENABLED, false),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        hiddenPrefs.edit().putBoolean(KEY_HIDDEN_ENTRY_ENABLED, isChecked).apply();
                        Toast.makeText(HiddenSettingsActivity.this,
                                isChecked ? "已开启页面入口，设置页面将显示跳转入口" : "已关闭页面入口，设置页面将隐藏跳转入口",
                                Toast.LENGTH_SHORT).show();
                    }
                }));

        entryCard.addView(entryContent);
        container.addView(entryCard);

        // 功能开关卡片
        LinearLayout funcCard = createCard();
        funcCard.addView(createCardHeader("功能开关", COLOR_PRIMARY));
        funcCard.addView(createDivider());

        LinearLayout funcContent = new LinearLayout(this);
        funcContent.setOrientation(LinearLayout.VERTICAL);
        funcContent.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));

        // 模拟定位开关
        funcContent.addView(createSwitchRow("模拟定位显示", "开启后可在快捷操作中显示模拟定位开关和地图选点入口",
                configPrefs.getBoolean(KEY_LOCATION_SPOOF, false),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                        configPrefs.edit().putBoolean(KEY_LOCATION_SPOOF, isChecked).apply();
                    }
                }));

        funcCard.addView(funcContent);
        container.addView(funcCard);

        // 说明卡片
        LinearLayout infoCard = createCard();
        infoCard.addView(createCardHeader("说明", COLOR_TEXT_SUB));
        infoCard.addView(createDivider());

        LinearLayout infoContent = new LinearLayout(this);
        infoContent.setOrientation(LinearLayout.VERTICAL);
        infoContent.setPadding(dp2px(16), dp2px(14), dp2px(16), dp2px(14));
        infoContent.setGravity(Gravity.CENTER);

        TextView infoText = new TextView(this);
        infoText.setText("此页面为隐藏功能的设置页面\n" +
                "相关功能为高风险项\n" +
                "请自行判断是否开启\n" +
                "技术栈: Xposed/LSPosed + Android\n目标应用: 美团众包");
        infoText.setTextSize(13);
        infoText.setTextColor(COLOR_TEXT_SUB);
        infoText.setLineSpacing(4, 1.3f);
        infoText.setGravity(Gravity.CENTER);
        infoContent.addView(infoText);

        infoCard.addView(infoContent);
        container.addView(infoCard);

        // 底部间距
        View bottomSpace = new View(this);
        bottomSpace.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(30)));
        container.addView(bottomSpace);

        scroll.addView(container);
        root.addView(scroll);
        setContentView(root);
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

    private LinearLayout createSwitchRow(String title, String desc, boolean checked,
            android.widget.CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp2px(8), 0, dp2px(8));

        // 第一行：标题和状态按钮
        LinearLayout firstRow = new LinearLayout(this);
        firstRow.setOrientation(LinearLayout.HORIZONTAL);
        firstRow.setGravity(Gravity.CENTER_VERTICAL);
        firstRow.setPadding(dp2px(16), 0, dp2px(16), 0);

        // 标题文本
        TextView titleLabel = new TextView(this);
        titleLabel.setText(title);
        titleLabel.setTextSize(15);
        titleLabel.setTextColor(COLOR_TEXT_MAIN);
        titleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        firstRow.addView(titleLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // 状态按钮（与主页面toggleRow样式一致）
        final TextView statusBtn = new TextView(this);
        statusBtn.setTextSize(13);
        statusBtn.setTypeface(Typeface.DEFAULT_BOLD);
        statusBtn.setGravity(Gravity.CENTER);
        statusBtn.setPadding(dp2px(14), dp2px(5), dp2px(14), dp2px(5));
        updateToggleStyle(statusBtn, checked);
        firstRow.addView(statusBtn);

        row.addView(firstRow);

        // 第二行：描述文本
        TextView descLabel = new TextView(this);
        descLabel.setText(desc);
        descLabel.setTextSize(12);
        descLabel.setTextColor(COLOR_TEXT_SUB);
        descLabel.setPadding(dp2px(16), dp2px(4), dp2px(16), 0);
        row.addView(descLabel);

        // 点击整个行切换状态
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 获取当前状态并切换
                String tag = (String) v.getTag();
                if (tag != null) {
                    boolean newState = !Boolean.parseBoolean(tag);
                    v.setTag(String.valueOf(newState));
                    updateToggleStyle(statusBtn, newState);
                    logToggle(title, newState);
                    // 触发回调
                    if (listener != null) {
                        listener.onCheckedChanged(null, newState);
                    }
                }
            }
        });
        row.setTag(String.valueOf(checked));

        // 添加点击效果
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        return row;
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

    private LinearLayout createCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp2px(14));
        c.setLayoutParams(params);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp2px(14));
        bg.setColor(COLOR_CARD);
        bg.setStroke(dp2px(1), Color.parseColor("#EAECEE"));
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
        tv.setText(title);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(COLOR_TEXT_MAIN);
        tv.setPadding(dp2px(10), 0, 0, 0);
        hdr.addView(tv);

        return hdr;
    }

    private View createDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1));
        params.setMargins(dp2px(16), 0, dp2px(16), 0);
        v.setLayoutParams(params);
        v.setBackgroundColor(Color.parseColor("#F2F4F4"));
        return v;
    }

    private int getStatusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp2px(int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
