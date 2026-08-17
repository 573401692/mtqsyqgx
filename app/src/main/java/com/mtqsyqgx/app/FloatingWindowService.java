package com.mtqsyqgx.app;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class FloatingWindowService extends Service {
    private static final String TAG = "MtFloatService";
    private static final int MAX_LOG_LINES = 80;
    private static final int DELAY_UNIFORM = 0, DELAY_GAUSSIAN = 1, DELAY_BIMODAL = 2, DELAY_BURST = 3;

    private WindowManager wm;
    private WindowManager.LayoutParams floatParams;
    private View floatRoot;
    private LinearLayout panelCollapsed;
    private View panelExpanded;

    private TextView tvStatusCollapsed, tvLogLineCollapsed;
    private TextView tvStatusExpanded, tvLogLineExpanded;

    private boolean isExpanded = false;

    private CheckBox chkUsePerKm;
    private TextView togglePerKmBtn;
    private TextView perKmInvalidHint;
    private EditText etMinAmt, etMaxAmt, etMinPerKm;
    private EditText etDelayMin, etDelayMax;
    private EditText etPickupKeywords, etDeliveryKeywords;
    private EditText etMaxOrders;
    private Spinner spDelayAlgo;
    private TextView tvPickupLoc, tvDeliveryLoc;
    private double pickupLat = 0, pickupLng = 0;
    private double deliveryLat = 0, deliveryLng = 0;
    private EditText etOnWayMin;
    private TextView toggleAutoRefreshBtn;
    private boolean autoRefreshOn = true;
    private EditText etAutoRefreshDelay;
    private CheckBox chkContinuous;
    private boolean configLoading = false;
    private TextView tvLogMultiLine;
    private ScrollView svLog;
    private volatile boolean autoGrabEnabled = false;
    private ScheduledExecutorService scheduler;
    private final LinkedList<String> logBuf = new LinkedList<>();
    private final Object logLock = new Object();
    private SharedPreferences prefs;
    // 采集订单展示：轮询读取采集器写入的共享订单文件
    // 采集器写美团进程缓存目录（无需su，100%可靠），悬浮窗用 su cat 读取；/data/local/tmp 为兼容路径
    private static final String[] ORDERS_PATHS = {
        // ★ 主通道：sdcard（采集器写、浮窗同App免su直读；采集日志已证明该通道100%可用）
        "/sdcard/Download/lucky/log/mt_orders.json",
        "/storage/emulated/0/Download/lucky/log/mt_orders.json",
        // 次通道：OrdersProvider 写入的模块私有缓存（同进程直接 File 读，无 su）
        "/data/user/0/com.mtqsyqgx.app/cache/lucky_orders/mt_orders.json",
        "/data/data/com.mtqsyqgx.app/cache/lucky_orders/mt_orders.json",
        // 兼容：美团进程缓存（su cat 读取）
        "/data/user/0/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_orders/mt_orders.json",
        "/data/data/com.sankuai.meituan.dispatch.crowdsource/cache/lucky_orders/mt_orders.json",
        "/data/local/tmp/mt_orders.json"
    };
    // ★ 抢单成功信号（HookInit 写入 /data/local/tmp，悬浮窗轮询显示"抢到哪个订单"）
    private static final String GRAB_SIGNAL_PATH = "/data/local/tmp/mt_grab_signal.json";
    // ★ 订单数据只保留最近30分钟
    private static final long ORDER_KEEP_MS = 30L * 60L * 1000L;
    private Handler orderPoller;
    private android.os.HandlerThread orderPollerThread;
    private Runnable orderPollTask;
    private String lastOrdersSig = "";
    private String lastGrabSig = "";
    private TextView tvOrders;

    private static final int C_BG = 0xE61A1C23;
    private static final int C_TEXT = 0xFFEAEAEA;
    private static final int C_HINT = 0xFF6B7280;
    private static final int C_LABEL = 0xFFB0BAC9;
    private static final int C_GREEN = 0xFF00C853;
    private static final int C_RED = 0xFFFF5252;
    private static final int C_BLUE = 0xFF448AFF;
    private static final int C_PRIMARY = 0xFFFF6D3A;
    private static final int C_ORANGE = 0xFFFFAB40;
    private static final int C_INPUT_BG = 0xFF1E2029;
    private static final int C_INPUT_STROKE = 0xFF353840;
    private static final int C_DIVIDER = 0x18FFFFFF;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("hook_config", MODE_PRIVATE);
        buildFloatView();
        loadConfig();
        // ★ 采集订单轮询独立于自动抢单：无条件启动，后台持续读取采集器数据
        startOrderPolling();
        log("悬浮窗服务已启动");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopAutoGrab();
        stopOrderPolling();
        if (floatRoot != null) try { wm.removeView(floatRoot); } catch (Exception ignored) {}
        super.onDestroy();
    }

    private void buildFloatView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        panelCollapsed = buildCollapsed();
        root.addView(panelCollapsed);
        panelExpanded = buildExpanded();
        panelExpanded.setVisibility(View.GONE);
        root.addView(panelExpanded);

        floatParams = new WindowManager.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        floatParams.gravity = Gravity.TOP | Gravity.START;
        floatParams.x = dp(12); floatParams.y = dp(80);

        wm.addView(root, floatParams);
        floatRoot = root;
        setupDrag(root);
    }

    private LinearLayout buildCollapsed() {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(makeBg(C_BG, 16));
        p.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(makeDot(8, C_RED));

        tvStatusCollapsed = new TextView(this);
        tvStatusCollapsed.setText(" 已停止");
        tvStatusCollapsed.setTextSize(13);
        tvStatusCollapsed.setTextColor(C_RED);
        tvStatusCollapsed.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tvStatusCollapsed);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        row.addView(spacer);

        TextView btnExpand = makeBtnBig("展开 ▼", C_ORANGE);
        btnExpand.setOnClickListener(v -> toggle());
        row.addView(btnExpand);

        TextView btnClose = makeBtnBig("✕", C_RED);
        btnClose.setOnClickListener(v -> stopSelf());
        row.addView(btnClose);
        p.addView(row);

        tvLogLineCollapsed = new TextView(this);
        tvLogLineCollapsed.setText("等待启动...");
        tvLogLineCollapsed.setTextSize(10);
        tvLogLineCollapsed.setTextColor(C_HINT);
        tvLogLineCollapsed.setSingleLine(true);
        tvLogLineCollapsed.setEllipsize(TextUtils.TruncateAt.END);
        tvLogLineCollapsed.setPadding(dp(12), dp(4), 0, 0);
        p.addView(tvLogLineCollapsed);

        return p;
    }

    private View buildExpanded() {
        int maxH = (int)(getResources().getDisplayMetrics().heightPixels * 0.58);

        FrameLayout outerFrame = new FrameLayout(this);
        outerFrame.setBackground(makeBg(C_BG, 16));
        outerFrame.setMinimumWidth(dp(300));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        outerFrame.addView(sv, svLp);

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));

        LinearLayout tb = new LinearLayout(this);
        tb.setOrientation(LinearLayout.HORIZONTAL);
        tb.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("自动抢单");
        title.setTextSize(15); title.setTextColor(C_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        tb.addView(title);
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        tb.addView(sp);
        tvStatusExpanded = new TextView(this);
        tvStatusExpanded.setText("● 已停止");
        tvStatusExpanded.setTextSize(11);
        tvStatusExpanded.setTextColor(C_RED);
        tvStatusExpanded.setTypeface(Typeface.DEFAULT_BOLD);
        tb.addView(tvStatusExpanded);
        View sp2 = new View(this);
        sp2.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        tb.addView(sp2);
        TextView collapseBtn = makeBtnBig("收起 ▲", C_ORANGE);
        collapseBtn.setOnClickListener(v -> toggle());
        tb.addView(collapseBtn);
        c.addView(tb);
        c.addView(makeDivider());

        tvLogLineExpanded = new TextView(this);
        tvLogLineExpanded.setText("等待启动...");
        tvLogLineExpanded.setTextSize(10);
        tvLogLineExpanded.setTextColor(C_HINT);
        tvLogLineExpanded.setSingleLine(true);
        tvLogLineExpanded.setEllipsize(TextUtils.TruncateAt.END);
        tvLogLineExpanded.setPadding(0, dp(4), 0, dp(4));
        c.addView(tvLogLineExpanded);
        c.addView(makeDivider());

        // ── 采集订单展示区（采集器读取到的订单数据）──
        TextView lblOrders = makeLabel("采集订单");
        lblOrders.setTextColor(C_GREEN);
        c.addView(lblOrders);
        tvOrders = new TextView(this);
        tvOrders.setText("等待采集器数据...");
        tvOrders.setTextSize(9);
        tvOrders.setTextColor(C_GREEN);
        tvOrders.setTypeface(Typeface.MONOSPACE);
        tvOrders.setLineSpacing(0, 1.15f);
        tvOrders.setPadding(dp(8), dp(4), dp(8), dp(4));
        ScrollView svOrders = new InnerScrollView(this);
        // ★ 固定高度 70dp：内容超出时可独立上下滑动（嵌套滚动必须固定高度）
        svOrders.setMinimumHeight(dp(70));
        svOrders.setBackground(makeBg(0xFF10231B, 8));
        svOrders.addView(tvOrders);
        FrameLayout.LayoutParams svOrdersLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        svOrdersLp.setMargins(0, dp(2), 0, dp(4));
        c.addView(svOrders, svOrdersLp);

        tvLogMultiLine = new TextView(this);
        tvLogMultiLine.setText("日志区域 - 启动后将显示筛选/抢单记录...");
        tvLogMultiLine.setTextSize(10);
        tvLogMultiLine.setTextColor(C_HINT);
        tvLogMultiLine.setTypeface(Typeface.MONOSPACE);
        tvLogMultiLine.setLineSpacing(0, 1.2f);
        tvLogMultiLine.setPadding(dp(8), dp(6), dp(8), dp(6));

        svLog = new InnerScrollView(this);
        // ★ 固定高度 110dp：内容超出时可独立上下滑动（现在能滑动了，所以可以缩小高度）
        svLog.setMinimumHeight(dp(110));
        svLog.setBackground(makeBg(0xFF1A2332, 8));
        svLog.addView(tvLogMultiLine);
        FrameLayout.LayoutParams svLogLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(110));
        svLogLp.setMargins(0, dp(2), 0, dp(4));
        c.addView(svLog, svLogLp);

        c.addView(makeLabel("金额范围 (元)"));
        etMinAmt = makeInput("最低金额", InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etMaxAmt = makeInput("最高金额", InputType.TYPE_NUMBER_FLAG_DECIMAL);
        c.addView(makeRow(etMinAmt, etMaxAmt));

        LinearLayout perKmRow = new LinearLayout(this);
        perKmRow.setOrientation(LinearLayout.HORIZONTAL);
        perKmRow.setGravity(Gravity.CENTER_VERTICAL);
        perKmRow.setPadding(dp(4), dp(6), dp(4), dp(6));

        TextView lblPerKm = new TextView(this);
        lblPerKm.setText("每公里价格筛选");
        lblPerKm.setTextSize(13);
        lblPerKm.setTextColor(C_ORANGE);
        lblPerKm.setTypeface(Typeface.DEFAULT_BOLD);
        perKmRow.addView(lblPerKm);

        View spPerKm = new View(this);
        spPerKm.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        perKmRow.addView(spPerKm);

        chkUsePerKm = new CheckBox(this);
        chkUsePerKm.setVisibility(View.GONE);

        togglePerKmBtn = new TextView(this);
        togglePerKmBtn.setText("关闭");
        togglePerKmBtn.setTextSize(11);
        togglePerKmBtn.setTextColor(C_HINT);
        togglePerKmBtn.setGravity(Gravity.CENTER);
        togglePerKmBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        GradientDrawable toggleBg = new GradientDrawable();
        toggleBg.setCornerRadius(dp(14));
        toggleBg.setColor(0xFF2D2D2D);
        toggleBg.setStroke(dp(1), 0xFF444444);
        togglePerKmBtn.setBackground(toggleBg);
        togglePerKmBtn.setOnClickListener(v -> {
            chkUsePerKm.setChecked(!chkUsePerKm.isChecked());
            boolean on = chkUsePerKm.isChecked();
            updateTogglePerKmUI(on);
            etMinAmt.setEnabled(!on); etMaxAmt.setEnabled(!on);
            etMinPerKm.setEnabled(on);
            saveConfig();
        });
        perKmRow.addView(togglePerKmBtn);
        c.addView(perKmRow);

        c.addView(makeLabel("每公里最低价格 (元/km)"));
        etMinPerKm = makeInput("如: 1（金额÷距离四舍五入后 ≥ 此值才抢）", InputType.TYPE_NUMBER_FLAG_DECIMAL); etMinPerKm.setEnabled(false);
        c.addView(etMinPerKm);
        c.addView(makeHint("计算: Math.round(订单金额 ÷ 送货距离km)"));
        // ★ 每公里价格筛选失效提醒（开启时显示）
        perKmInvalidHint = makeHint("⚠ 每公里价格筛选已失效：订单距离数据不可靠，该筛选不生效，请改用金额筛选");
        perKmInvalidHint.setVisibility(View.GONE);
        c.addView(perKmInvalidHint);

        c.addView(makeLabel("取货地定位"));

        LinearLayout pickupLocRow = new LinearLayout(this);
        pickupLocRow.setOrientation(LinearLayout.HORIZONTAL);
        pickupLocRow.setGravity(Gravity.CENTER_VERTICAL);
        pickupLocRow.setPadding(0, dp(2), 0, dp(4));
        tvPickupLoc = new TextView(this);
        // ★ 回读已保存的取货地坐标（防广播丢失/重启后丢失显示）
        double pLat0 = parseD(prefs.getString("grab_pickup_lat", ""), 0);
        double pLng0 = parseD(prefs.getString("grab_pickup_lng", ""), 0);
        pickupLat = pLat0; pickupLng = pLng0;
        boolean pSet = (pLat0 != 0 || pLng0 != 0);
        tvPickupLoc.setText(pSet ? String.format(Locale.US, "取货: %.4f,%.4f", pLat0, pLng0) : "取货定位: 未设置");
        tvPickupLoc.setTextSize(10); tvPickupLoc.setTextColor(pSet ? C_TEXT : C_HINT);
        tvPickupLoc.setPadding(0, 0, dp(6), 0);
        pickupLocRow.addView(tvPickupLoc);
        View sp3a = new View(this);
        sp3a.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        pickupLocRow.addView(sp3a);
        TextView btnPickupLoc = makeBtnBig("🗺 选点", C_GREEN);
        btnPickupLoc.setOnClickListener(v -> openMapPicker("pickup"));
        pickupLocRow.addView(btnPickupLoc);
        c.addView(pickupLocRow);
        c.addView(makeLabel("取货地排除关键词"));
        etPickupKeywords = makeInput("如: 超市,药店,加油站（逗号分隔）", InputType.TYPE_CLASS_TEXT);
        c.addView(etPickupKeywords);

        c.addView(makeLabel("送货地定位"));

        LinearLayout deliveryLocRow = new LinearLayout(this);
        deliveryLocRow.setOrientation(LinearLayout.HORIZONTAL);
        deliveryLocRow.setGravity(Gravity.CENTER_VERTICAL);
        deliveryLocRow.setPadding(0, dp(2), 0, dp(4));
        tvDeliveryLoc = new TextView(this);
        // ★ 回读已保存的送货地坐标（防广播丢失/重启后丢失显示）
        double dLat0 = parseD(prefs.getString("grab_delivery_lat", ""), 0);
        double dLng0 = parseD(prefs.getString("grab_delivery_lng", ""), 0);
        deliveryLat = dLat0; deliveryLng = dLng0;
        boolean dSet = (dLat0 != 0 || dLng0 != 0);
        tvDeliveryLoc.setText(dSet ? String.format(Locale.US, "送货: %.4f,%.4f", dLat0, dLng0) : "送货定位: 未设置");
        tvDeliveryLoc.setTextSize(10); tvDeliveryLoc.setTextColor(dSet ? C_TEXT : C_HINT);
        tvDeliveryLoc.setPadding(0, 0, dp(6), 0);
        deliveryLocRow.addView(tvDeliveryLoc);
        View sp3b = new View(this);
        sp3b.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        deliveryLocRow.addView(sp3b);
        TextView btnDeliveryLoc = makeBtnBig("🗺 选点", C_GREEN);
        btnDeliveryLoc.setOnClickListener(v -> openMapPicker("delivery"));
        deliveryLocRow.addView(btnDeliveryLoc);
        c.addView(deliveryLocRow);
        c.addView(makeLabel("送货地排除关键词"));
        etDeliveryKeywords = makeInput("如: 医院,学校,写字楼（逗号分隔）", InputType.TYPE_CLASS_TEXT);
        c.addView(etDeliveryKeywords);
        c.addView(makeHint("地址包含关键词的订单将跳过不抢"));
        c.addView(makeDivider());

        c.addView(makeLabel("顺路度筛选 (0~100, 越高越顺路)"));
        etOnWayMin = makeInput("最低顺路度，留空不限", InputType.TYPE_CLASS_NUMBER);
        c.addView(etOnWayMin);
        c.addView(makeHint("已抢到订单时，新订单顺路度需 ≥ 此值才抢"));
        c.addView(makeDivider());

        // ── 抢转单 / 抢路线任务（开启=此类订单直接抢，绕过金额/距离/关键词/顺路度筛选）──
        boolean grabTransferOn = prefs.getBoolean("grab_transfer", true);
        LinearLayout transferRow = new LinearLayout(this);
        transferRow.setOrientation(LinearLayout.HORIZONTAL);
        transferRow.setGravity(Gravity.CENTER_VERTICAL);
        transferRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView lblTransfer = new TextView(this);
        lblTransfer.setText("抢转单");
        lblTransfer.setTextSize(13);
        lblTransfer.setTextColor(C_PRIMARY);
        lblTransfer.setTypeface(Typeface.DEFAULT_BOLD);
        transferRow.addView(lblTransfer);
        View spTrans = new View(this);
        spTrans.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        transferRow.addView(spTrans);
        TextView toggleTransferBtn = new TextView(this);
        toggleTransferBtn.setTextSize(11);
        toggleTransferBtn.setGravity(Gravity.CENTER);
        toggleTransferBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        updateToggleGrabTypeUI(toggleTransferBtn, grabTransferOn);
        toggleTransferBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("grab_transfer", true);
            prefs.edit().putBoolean("grab_transfer", on).commit();
            updateToggleGrabTypeUI(toggleTransferBtn, on);
            writeConfigFile();
            log("抢转单: " + (on ? "开" : "关") + "（开启=抢符合条件的转单，关闭=一律不抢转单）");
        });
        transferRow.addView(toggleTransferBtn);
        c.addView(transferRow);
        c.addView(makeHint("关闭=一律不抢转单；开启=转单仍按金额/距离/关键词等常规筛选（符合才抢）"));

        boolean grabRouteOn = prefs.getBoolean("grab_route", true);
        LinearLayout routeRow = new LinearLayout(this);
        routeRow.setOrientation(LinearLayout.HORIZONTAL);
        routeRow.setGravity(Gravity.CENTER_VERTICAL);
        routeRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView lblRoute = new TextView(this);
        lblRoute.setText("抢路线任务");
        lblRoute.setTextSize(13);
        lblRoute.setTextColor(C_PRIMARY);
        lblRoute.setTypeface(Typeface.DEFAULT_BOLD);
        routeRow.addView(lblRoute);
        View spRoute = new View(this);
        spRoute.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        routeRow.addView(spRoute);
        TextView toggleRouteBtn = new TextView(this);
        toggleRouteBtn.setTextSize(11);
        toggleRouteBtn.setGravity(Gravity.CENTER);
        toggleRouteBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        updateToggleGrabTypeUI(toggleRouteBtn, grabRouteOn);
        toggleRouteBtn.setOnClickListener(v -> {
            boolean on = !prefs.getBoolean("grab_route", true);
            prefs.edit().putBoolean("grab_route", on).commit();
            updateToggleGrabTypeUI(toggleRouteBtn, on);
            writeConfigFile();
            log("抢路线任务: " + (on ? "开" : "关") + "（开启=抢符合条件的路线任务，关闭=一律不抢路线任务）");
        });
        routeRow.addView(toggleRouteBtn);
        c.addView(routeRow);
        c.addView(makeHint("关闭=一律不抢路线任务；开启=路线任务仍按金额/距离/关键词等常规筛选（符合才抢）"));
        c.addView(makeDivider());

        // ── 自动刷新主页（独立开关 + 间隔控制，不依赖自动抢单，实时生效）──
        LinearLayout autoRefreshRow = new LinearLayout(this);
        autoRefreshRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRefreshRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRefreshRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView lblAutoRefresh = new TextView(this);
        lblAutoRefresh.setText("自动刷新主页");
        lblAutoRefresh.setTextSize(13);
        lblAutoRefresh.setTextColor(C_PRIMARY);
        lblAutoRefresh.setTypeface(Typeface.DEFAULT_BOLD);
        autoRefreshRow.addView(lblAutoRefresh);
        View spAuto = new View(this);
        spAuto.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        autoRefreshRow.addView(spAuto);
        // ★ 与"每公里价格筛选"同款胶囊 toggle 按钮
        toggleAutoRefreshBtn = new TextView(this);
        toggleAutoRefreshBtn.setTextSize(11);
        toggleAutoRefreshBtn.setGravity(Gravity.CENTER);
        toggleAutoRefreshBtn.setPadding(dp(16), dp(6), dp(16), dp(6));
        updateToggleAutoRefreshUI(autoRefreshOn);
        toggleAutoRefreshBtn.setOnClickListener(v -> {
            autoRefreshOn = !autoRefreshOn;
            updateToggleAutoRefreshUI(autoRefreshOn);
            // ★ 立即写入共享配置（/data/local/tmp/mt_config.json），Hook端实时读取 → 独立生效
            writeConfigFile();
            log("自动刷新: " + (autoRefreshOn ? "开" : "关") + "（独立生效）");
        });
        autoRefreshRow.addView(toggleAutoRefreshBtn);
        c.addView(autoRefreshRow);
        c.addView(makeLabel("刷新间隔 (ms)"));
        etAutoRefreshDelay = makeInput("1000", InputType.TYPE_CLASS_NUMBER);
        // ★ 失去焦点即写入共享配置，间隔修改独立生效
        etAutoRefreshDelay.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && etAutoRefreshDelay != null) {
                writeConfigFile();
                log("自动刷新间隔已保存: " + txt(etAutoRefreshDelay) + "ms");
            }
        });
        c.addView(etAutoRefreshDelay);
        c.addView(makeHint("自动刷新新任务列表，开关/间隔独立于自动抢单，改后即时生效（最小500ms）"));
        c.addView(makeDivider());

        c.addView(makeLabel("抢单延迟 (ms)"));
        etDelayMin = makeInput("300", InputType.TYPE_CLASS_NUMBER);
        etDelayMax = makeInput("1500", InputType.TYPE_CLASS_NUMBER);
        c.addView(makeRow(etDelayMin, etDelayMax));

        c.addView(makeLabel("延迟算法"));
        spDelayAlgo = new Spinner(this);
        spDelayAlgo.setMinimumHeight(dp(44));
        String[] algos = {"均匀随机", "高斯正态", "双峰分布", "突发间隔"};
        ArrayAdapter<String> spinAdapter = new ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, algos) {
            @Override public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convertView, parent);
                tv.setTextColor(C_TEXT);
                tv.setTextSize(14);
                tv.setPadding(dp(10), dp(8), dp(10), dp(8));
                return tv;
            }
            @Override public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getDropDownView(pos, cv, parent);
                tv.setTextColor(C_TEXT);
                tv.setTextSize(15);
                tv.setBackgroundColor(C_INPUT_BG);
                tv.setPadding(dp(14), dp(12), dp(14), dp(12));
                tv.setMinimumHeight(dp(48));
                return tv;
            }
        };
        spinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDelayAlgo.setAdapter(spinAdapter);
        spDelayAlgo.setBackground(makeBg(C_INPUT_BG, 8));
        spDelayAlgo.setPopupBackgroundDrawable(new ColorDrawable(C_INPUT_BG));
        c.addView(spDelayAlgo);
        c.addView(makeHint("均匀等概 | 高斯集中 | 双峰偏端 | 突发短快"));
        c.addView(makeDivider());

        c.addView(makeLabel("最多可抢单数 (0=不限)"));
        etMaxOrders = makeInput("0", InputType.TYPE_CLASS_NUMBER);
        c.addView(etMaxOrders);

        // ── 连抢模式：开启=持续抢至上限/关闭；关闭=每次只抢一单 ──
        LinearLayout continuousRow = new LinearLayout(this);
        continuousRow.setOrientation(LinearLayout.HORIZONTAL);
        continuousRow.setGravity(Gravity.CENTER_VERTICAL);
        continuousRow.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView lblContinuous = new TextView(this);
        lblContinuous.setText("连抢模式");
        lblContinuous.setTextSize(13);
        lblContinuous.setTextColor(C_ORANGE);
        lblContinuous.setTypeface(Typeface.DEFAULT_BOLD);
        continuousRow.addView(lblContinuous);
        View spContinuous = new View(this);
        spContinuous.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        continuousRow.addView(spContinuous);
        chkContinuous = new CheckBox(this);
        chkContinuous.setOnCheckedChangeListener((b, checked) -> { saveConfig(); log("连抢模式: " + (checked ? "开" : "关")); });
        continuousRow.addView(chkContinuous);
        c.addView(continuousRow);
        c.addView(makeHint("开启=持续抢符合筛选的订单直至上限/关闭；关闭=每次只抢一单，重新启动可再抢"));
        c.addView(makeDivider());

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(4), 0, 0);
        btns.addView(makeAction("启动", C_GREEN, v -> {
            saveConfig(); autoGrabEnabled = true; startAutoGrab();
        }));
        btns.addView(makeAction("停止", C_RED, v -> {
            autoGrabEnabled = false; stopAutoGrab();
        }));
        btns.addView(makeAction("保存", C_BLUE, v -> { saveConfig(); log("配置已保存"); }));
        c.addView(btns);

        c.addView(makeAction("查看日志", C_ORANGE, v -> showLogDialog()));
        c.addView(makeAction("关闭悬浮窗", 0xFF607D8B, v -> stopSelf()));

        sv.addView(c);

        return outerFrame;
    }

    public boolean shouldGrabOrder(String orderId, double amount, double fLat, double fLng,
                                   double dLat, double dLng, double distKm, int onWayScore,
                                   boolean alreadyGrabbed, String pickupAddr, String deliveryAddr) {
        // 每公里价格筛选（启用时替代金额筛选）
        boolean upk = prefs.getBoolean("grab_use_per_km", false);
        if (upk) {
            if (distKm <= 0) {
                log("距离为0，跳过每公里价格筛选: " + orderId);
                return false;
            }
            long pk = Math.round(amount / distKm);
            int mn = parseInt(prefs.getString("grab_min_per_km", ""), 0);
            if (mn > 0 && pk < mn) {
                log("每公里价格不足: " + pk + " < " + mn + " → 跳过 " + orderId);
                return false;
            }
        } else {
            // 金额范围筛选
            double ma = parseD(prefs.getString("grab_min_amount", ""), 0);
            double xa = parseD(prefs.getString("grab_max_amount", ""), 99999);
            if (ma > 0 && amount < ma) return false;
            if (xa < 99999 && amount > xa) return false;
        }

        // 取货地距离 + 关键词
        double tLat = parseD(prefs.getString("grab_pickup_lat", ""), 0);
        double tLng = parseD(prefs.getString("grab_pickup_lng", ""), 0);
        if (tLat != 0 && tLng != 0) {
            double pr = parseD(prefs.getString("grab_pickup_range", ""), 0);
            if (pr > 0 && haversine(tLat, tLng, fLat, fLng) > pr) {
                log("取货地超出范围 → 跳过 " + orderId);
                return false;
            }
        }
        String pkw = prefs.getString("grab_pickup_keywords", "");
        if (!pkw.isEmpty() && pickupAddr != null) {
            for (String kw : pkw.split("[,，]")) {
                String kwT = kw.trim();
                if (!kwT.isEmpty() && pickupAddr.contains(kwT)) {
                    log("取货地命中「" + kwT + "」 → 跳过 " + orderId);
                    return false;
                }
            }
        }

        // 送货地距离 + 关键词
        double dLat2 = parseD(prefs.getString("grab_delivery_lat", ""), 0);
        double dLng2 = parseD(prefs.getString("grab_delivery_lng", ""), 0);
        if (dLat2 != 0 && dLng2 != 0) {
            double dr = parseD(prefs.getString("grab_delivery_range", ""), 0);
            if (dr > 0 && haversine(dLat2, dLng2, dLat, dLng) > dr) {
                log("送货地超出范围 → 跳过 " + orderId);
                return false;
            }
        }
        String dkw = prefs.getString("grab_delivery_keywords", "");
        if (!dkw.isEmpty() && deliveryAddr != null) {
            for (String kw : dkw.split("[,，]")) {
                String kwT = kw.trim();
                if (!kwT.isEmpty() && deliveryAddr.contains(kwT)) {
                    log("送货地命中「" + kwT + "」 → 跳过 " + orderId);
                    return false;
                }
            }
        }

        // 顺路度筛选：仅在已抢到订单时生效
        if (alreadyGrabbed) {
            int minOnWay = parseInt(prefs.getString("grab_onway_min", ""), 0);
            if (minOnWay > 0 && onWayScore < minOnWay) {
                log("顺路度不足: " + onWayScore + " < " + minOnWay + " → 跳过 " + orderId);
                return false;
            }
        }
        return true;
    }

    public void executeGrab(String orderId, Runnable action) {
        if (!autoGrabEnabled) return;
        int mn = parseInt(prefs.getString("grab_delay_min", "300"), 300);
        int mx = parseInt(prefs.getString("grab_delay_max", "1500"), 1500);
        int algo = prefs.getInt("grab_delay_algo", DELAY_UNIFORM);
        int d = computeDelay(mn, mx, algo);
        log("匹配: " + orderId + " → " + d + "ms (" + algoName(algo) + ")");
        if (scheduler == null) scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.schedule(() -> { log("执行抢单: " + orderId); if (action != null) action.run(); }, d, TimeUnit.MILLISECONDS);
    }

    private int computeDelay(int min, int max, int algo) {
        if (min >= max) return min;
        Random r = new Random();
        int range = max - min;
        switch (algo) {
            case DELAY_GAUSSIAN:
                double mid = (min + max) / 2.0, sig = range / 6.0;
                double u = Math.sqrt(-2 * Math.log(r.nextDouble())) * Math.cos(2 * Math.PI * r.nextDouble());
                return clamp((int)(mid + u * sig), min, max);
            case DELAY_BIMODAL:
                double d = r.nextDouble();
                if (d < 0.4) return min + r.nextInt((int)(range * 0.3));
                if (d > 0.6) return max - r.nextInt((int)(range * 0.3));
                return min + r.nextInt(range);
            case DELAY_BURST:
                return r.nextDouble() < 0.2
                    ? min + r.nextInt((int)(range * 0.2))
                    : min + (int)(range * 0.6) + r.nextInt((int)(range * 0.4));
            default: return min + r.nextInt(range);
        }
    }

    private String algoName(int a) {
        switch(a) { case 1: return "高斯"; case 2: return "双峰"; case 3: return "突发"; default: return "均匀"; }
    }

    private void startAutoGrab() {
        autoGrabEnabled = true;
        updateStatus(true);
        log("自动抢单已启动");
        writeConfigFile();
        startOrderPolling();
    }

    private void stopAutoGrab() {
        autoGrabEnabled = false;
        updateStatus(false);
        log("自动抢单已停止");
        writeConfigFile();
        // ★ 采集订单轮询独立于自动抢单：停止抢单不影响采集展示
    }

    // ═══════════ 采集订单展示（轮询读取 mt_orders.json；后台线程，避免主线程卡顿） ═══════════
    private void startOrderPolling() {
        stopOrderPolling();
        if (orderPollerThread == null || !orderPollerThread.isAlive()) {
            orderPollerThread = new android.os.HandlerThread("order-poller");
            orderPollerThread.start();
            orderPoller = new Handler(orderPollerThread.getLooper());
        }
        orderPollTask = new Runnable() {
            @Override public void run() {
                try {
                    // 1) 采集订单展示
                    String content = null;
                    for (String path : ORDERS_PATHS) {
                        content = readRootFile(path);
                        if (content != null && !content.trim().isEmpty()) break;
                    }
                    if (content != null && !content.equals(lastOrdersSig)) {
                        lastOrdersSig = content;
                        displayOrders(content);
                    }
                    // 2) 抢单成功信号：显示"抢到哪个订单"
                    try {
                        String sig = readRootFile(GRAB_SIGNAL_PATH);
                        if (sig != null && !sig.trim().isEmpty() && !sig.equals(lastGrabSig)) {
                            lastGrabSig = sig;
                            String gid = "?";
                            String gt = "";
                            try {
                                org.json.JSONObject j = new org.json.JSONObject(sig);
                                gid = j.optString("id", "?");
                                long tm = j.optLong("time", 0);
                                if (tm > 0) gt = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date(tm));
                            } catch (Throwable ignored) {}
                            final String show = "★ 已抢到订单: " + gid + (gt.isEmpty() ? "" : " (" + gt + ")");
                            log(show);
                            new Handler(Looper.getMainLooper()).post(() -> {
                                if (tvStatusExpanded != null) { tvStatusExpanded.setText("● " + show); tvStatusExpanded.setTextColor(C_GREEN); }
                                if (tvStatusCollapsed != null) { tvStatusCollapsed.setText(show); tvStatusCollapsed.setTextColor(C_GREEN); }
                            });
                        }
                    } catch (Throwable ignored) {}
                } catch (Throwable t) {
                    Log.e(TAG, "orderPoll err: " + t.getMessage());
                }
                if (orderPollTask != null) orderPoller.postDelayed(this, 2500);
            }
        };
        orderPoller.post(orderPollTask);
    }

    private void stopOrderPolling() {
        if (orderPollTask != null) {
            orderPoller.removeCallbacks(orderPollTask);
            orderPollTask = null;
        }
    }

    private void displayOrders(String content) {        try {
            long now = System.currentTimeMillis();
            org.json.JSONArray arr = new org.json.JSONArray(content);
            if (arr.length() == 0) return;
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (int i = arr.length() - 1; i >= 0; i--) {
                try {
                    Object o = arr.get(i);
                    String line = null;
                    long t = 0;
                    if (o instanceof org.json.JSONObject) {
                        org.json.JSONObject jo = (org.json.JSONObject) o;
                        line = jo.optString("txt", "");
                        t = jo.optLong("t", 0);
                    } else if (o != null) {
                        line = o.toString();
                    }
                    if (line == null || line.isEmpty()) continue;
                    // ★ 只展示最近30分钟的订单
                    if (t > 0 && now - t > ORDER_KEEP_MS) continue;
                    if (shown == 0) sb.append("最近订单:\n");
                    sb.append(line).append("\n");
                    shown++;
                } catch (Throwable ignored) {}
            }
            if (shown == 0) return;
            final String text = sb.toString().trim();
            new Handler(Looper.getMainLooper()).post(() -> {
                if (tvOrders != null) tvOrders.setText(text);
            });
            log("采集器返回 " + shown + " 条订单，已展示在【采集订单】区");
        } catch (Throwable ignored) {}
    }

    /** 读取共享文件：直接读 → su cp 到 /data/local/tmp（验证过的成功路径）→ su -c cat 兜底 */
    private String readRootFile(String path) {
        // 1) 直接 File 读
        try {
            java.io.File f = new java.io.File(path);
            if (f.exists() && f.canRead()) {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                StringBuilder sb = new StringBuilder();
                String l; while ((l = r.readLine()) != null) sb.append(l).append("\n");
                r.close();
                String res = sb.toString().trim();
                if (!res.isEmpty()) return res;
            }
        } catch (Throwable ignored) {}
        // 2) su cp 到 /data/local/tmp（shell_data_file，悬浮窗可直接读；与 mt_config.json 同一写入模式）
        try {
            String tmp = "/data/local/tmp/mt_orders.json";
            java.lang.Process suCp = Runtime.getRuntime().exec(new String[]{"su", "-c", "cp " + path + " " + tmp + " && chmod 666 " + tmp});
            boolean done = suCp.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) suCp.destroyForcibly();
            if (done) {
                java.io.File f = new java.io.File(tmp);
                if (f.exists() && f.canRead()) {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                    StringBuilder sb = new StringBuilder();
                    String l; while ((l = r.readLine()) != null) sb.append(l).append("\n");
                    r.close();
                    String res = sb.toString().trim();
                    if (!res.isEmpty()) return res;
                }
            }
        } catch (Throwable ignored) {}
        // 3) su -c cat 兜底
        try {
            final java.lang.Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + path});
            final StringBuilder sb = new StringBuilder();
            Thread rt = new Thread(() -> {
                try {
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
                    String l; while ((l = r.readLine()) != null) sb.append(l).append("\n");
                    r.close();
                } catch (Throwable ignored) {}
            });
            rt.start();
            boolean done = p.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) { p.destroyForcibly(); rt.interrupt(); }
            else { rt.join(1000); String res = sb.toString().trim(); if (!res.isEmpty()) return res; }
        } catch (Throwable ignored) {}
        return null;
    }

    private void updateStatus(boolean running) {
        new Handler(Looper.getMainLooper()).post(() -> {
            int c = running ? C_GREEN : C_RED;
            String s = running ? " 运行中" : " 已停止";
            String dot = running ? "● " : "● ";
            if (tvStatusCollapsed != null) { tvStatusCollapsed.setText(s); tvStatusCollapsed.setTextColor(c); }
            if (tvStatusExpanded != null) { tvStatusExpanded.setText(dot + (running ? "运行中" : "已停止")); tvStatusExpanded.setTextColor(c); }
        });
    }

    private void log(String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = "[" + ts + "] " + msg;
        synchronized (logLock) { logBuf.addLast(entry); while (logBuf.size() > MAX_LOG_LINES) logBuf.removeFirst(); }
        new Handler(Looper.getMainLooper()).post(() -> {
            if (tvLogLineCollapsed != null) tvLogLineCollapsed.setText(msg);
            if (tvLogLineExpanded != null) tvLogLineExpanded.setText(msg);
            if (tvLogMultiLine != null) {
                StringBuilder sb = new StringBuilder();
                synchronized (logLock) {
                    for (String l : logBuf) sb.append(l).append("\n");
                }
                tvLogMultiLine.setText(sb.toString());
                if (svLog != null) svLog.post(() -> {
                    // 智能跟随：仅当用户已滚到底部时才自动拉到底，用户上翻查看历史时不打扰
                    if (svLog.getScrollY() + svLog.getHeight() >= tvLogMultiLine.getHeight() - dp(24)) {
                        svLog.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
        writeToFile(entry);
        Log.d(TAG, msg);
    }

    private void writeToFile(String line) {
        try {
            String dir = "/storage/emulated/0/Download/lucky/log/";
            // 优先通过 su 创建目录（非阻塞，不卡主线程；直接 File 写失败再走 su 兜底）
            try {
                Runtime.getRuntime().exec(new String[]{"su", "-c", "mkdir -p " + dir});
            } catch (Throwable ignored) {}
            java.io.File logDir = new java.io.File(dir);
            if (!logDir.exists()) logDir.mkdirs();

            String file = dir + "auto_grab_" + new java.text.SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date()) + ".txt";
            try {
                java.io.FileWriter fw = new java.io.FileWriter(file, true);
                java.io.BufferedWriter bw = new java.io.BufferedWriter(fw);
                bw.write(line);
                bw.newLine();
                bw.close();
                fw.close();
            } catch (Throwable t1) {
                // 直接写入失败，通过 su 追加（临时文件写到应用缓存目录）
                java.io.File tmpDir = getCacheDir();
                if (tmpDir == null) tmpDir = new java.io.File("/data/local/tmp/");
                java.io.File tmpFile = new java.io.File(tmpDir, "su_log_" + System.nanoTime() + ".tmp");
                java.io.FileWriter fw = new java.io.FileWriter(tmpFile);
                fw.write(line + "\n");
                fw.close();
                java.lang.Process suAppend = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat " + tmpFile.getAbsolutePath() + " >> " + file});
                boolean done = suAppend.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (!done) suAppend.destroyForcibly();
                tmpFile.delete();
            }
        } catch (Exception ignored) {}
    }

    private static final String MAP_PICKER_ACTION = "com.mtqsyqgx.MAP_PICKER_RESULT";
    private BroadcastReceiver mapPickerReceiver;

    private void openMapPicker(String type) {
        double lat = "pickup".equals(type) ? pickupLat : deliveryLat;
        double lng = "pickup".equals(type) ? pickupLng : deliveryLng;

        // 自动收起悬浮窗
        if (isExpanded) {
            toggle();
        }

        Intent intent = new Intent(this, MapPickerActivity.class);
        intent.putExtra(MapPickerActivity.EXTRA_TYPE, type);
        intent.putExtra(MapPickerActivity.EXTRA_LAT, lat);
        intent.putExtra(MapPickerActivity.EXTRA_LNG, lng);
        intent.putExtra(MapPickerActivity.EXTRA_RANGE, 0f); // 不传范围，由页面自行管理
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);

        // 注册广播接收器获取选点结果
        registerMapPickerReceiver();
    }

    private void registerMapPickerReceiver() {
        if (mapPickerReceiver != null) {
            try { unregisterReceiver(mapPickerReceiver); } catch (Exception ignored) {}
        }
        mapPickerReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!MAP_PICKER_ACTION.equals(intent.getAction())) return;

                // 恢复悬浮窗显示
                floatRoot.setVisibility(View.VISIBLE);

                double lat = intent.getDoubleExtra(MapPickerActivity.RESULT_LAT, 0);
                double lng = intent.getDoubleExtra(MapPickerActivity.RESULT_LNG, 0);
                String address = intent.getStringExtra(MapPickerActivity.RESULT_ADDRESS);
                String type = intent.getStringExtra(MapPickerActivity.EXTRA_TYPE);
                if (lat == 0 && lng == 0) {
                    try { unregisterReceiver(mapPickerReceiver); } catch (Exception ignored) {}
                    mapPickerReceiver = null;
                    return;
                }

                double range = intent.getDoubleExtra(MapPickerActivity.EXTRA_RANGE, 0);

                if ("pickup".equals(type)) {
                    pickupLat = lat; pickupLng = lng;
                    tvPickupLoc.setText(String.format(Locale.US, "取货: %.4f,%.4f", lat, lng));
                    tvPickupLoc.setTextColor(C_TEXT);
                    prefs.edit()
                        .putString("grab_pickup_lat", String.valueOf(lat))
                        .putString("grab_pickup_lng", String.valueOf(lng))
                        .apply();
                    if (range > 0) {
                        prefs.edit().putString("grab_pickup_range", String.format(Locale.US, "%.1f", range)).apply();
                    }
                    log("取货地定位已设: " + String.format(Locale.US, "%.4f,%.4f 范围%.1fkm", lat, lng, range));
                } else {
                    deliveryLat = lat; deliveryLng = lng;
                    tvDeliveryLoc.setText(String.format(Locale.US, "送货: %.4f,%.4f", lat, lng));
                    tvDeliveryLoc.setTextColor(C_TEXT);
                    prefs.edit()
                        .putString("grab_delivery_lat", String.valueOf(lat))
                        .putString("grab_delivery_lng", String.valueOf(lng))
                        .apply();
                    if (range > 0) {
                        prefs.edit().putString("grab_delivery_range", String.format(Locale.US, "%.1f", range)).apply();
                    }
                    log("送货地定位已设: " + String.format(Locale.US, "%.4f,%.4f 范围%.1fkm", lat, lng, range));
                }
                saveConfig();
                try { unregisterReceiver(mapPickerReceiver); } catch (Exception ignored) {}
                mapPickerReceiver = null;
            }
        };
        IntentFilter filter = new IntentFilter(MAP_PICKER_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mapPickerReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mapPickerReceiver, filter);
        }
    }

    private void showLogDialog() {
        Dialog dlg = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xB3000000);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(makeBg(0xFF1A1C25, 16));
        int w = (int)(getResources().getDisplayMetrics().widthPixels * 0.92f);
        int h = (int)(getResources().getDisplayMetrics().heightPixels * 0.70f);
        card.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        TextView tv = new TextView(this);
        tv.setText("抢单日志"); tv.setTextSize(15); tv.setTextColor(C_TEXT);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tv); card.addView(makeDivider());
        TextView tvLog = new TextView(this);
        tvLog.setTextSize(10); tvLog.setTextColor(C_LABEL);
        tvLog.setTypeface(Typeface.MONOSPACE);
        synchronized (logLock) { StringBuilder sb = new StringBuilder(); for (String l : logBuf) sb.append(l).append("\n"); tvLog.setText(sb.toString()); }
        tvLog.setMovementMethod(ScrollingMovementMethod.getInstance());
        ScrollView s = new ScrollView(this); s.addView(tvLog);
        card.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Button btnClose = new Button(this);
        btnClose.setText("关闭"); btnClose.setTextSize(13); btnClose.setTextColor(Color.WHITE);
        btnClose.setBackground(makeBg(C_PRIMARY, 10));
        btnClose.setOnClickListener(v -> dlg.dismiss());
        card.addView(btnClose);
        root.addView(card);
        dlg.setContentView(root);
        dlg.show();
    }

    private void updateTogglePerKmUI(boolean on) {
        if (togglePerKmBtn == null) return;
        togglePerKmBtn.setText(on ? "开启" : "关闭");
        togglePerKmBtn.setTextColor(on ? C_GREEN : C_HINT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? 0x3010B981 : 0xFF2D2D2D);
        bg.setStroke(dp(1), on ? 0xFF10B981 : 0xFF444444);
        togglePerKmBtn.setBackground(bg);
        // 失效提醒：开启时显示小字
        if (perKmInvalidHint != null) {
            perKmInvalidHint.setVisibility(on ? View.VISIBLE : View.GONE);
        }
    }

    // ★ 自动刷新胶囊 toggle：与"每公里价格筛选"完全同款样式
    private void toggleAutoRefreshBg(GradientDrawable bg, boolean on) {
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? 0x3010B981 : 0xFF2D2D2D);
        bg.setStroke(dp(1), on ? 0xFF10B981 : 0xFF444444);
    }

    private void updateToggleAutoRefreshUI(boolean on) {
        if (toggleAutoRefreshBtn == null) return;
        toggleAutoRefreshBtn.setText(on ? "开启" : "关闭");
        toggleAutoRefreshBtn.setTextColor(on ? C_GREEN : C_HINT);
        GradientDrawable bg = new GradientDrawable();
        toggleAutoRefreshBg(bg, on);
        toggleAutoRefreshBtn.setBackground(bg);
    }

    /** 抢转单/抢路线任务 胶囊开关样式 */
    private void updateToggleGrabTypeUI(TextView v, boolean on) {
        v.setText(on ? "开" : "关");
        v.setTextColor(on ? Color.WHITE : C_HINT);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(on ? C_PRIMARY : 0xFFEAECEE);
        v.setBackground(bg);
    }

    private void loadConfig() {
        try {
            setText(etMinAmt, prefs.getString("grab_min_amount", ""));
            setText(etMaxAmt, prefs.getString("grab_max_amount", ""));
            setText(etMinPerKm, prefs.getString("grab_min_per_km", ""));
            setText(etPickupKeywords, prefs.getString("grab_pickup_keywords", ""));
            setText(etDeliveryKeywords, prefs.getString("grab_delivery_keywords", ""));
            setText(etDelayMin, prefs.getString("grab_delay_min", "300"));
            setText(etDelayMax, prefs.getString("grab_delay_max", "1500"));
            setText(etOnWayMin, prefs.getString("grab_onway_min", ""));
            setText(etMaxOrders, prefs.getString("grab_max_orders", ""));
            autoRefreshOn = prefs.getBoolean("mt_auto_refresh_home", true);
            updateToggleAutoRefreshUI(autoRefreshOn);
            try { setText(etAutoRefreshDelay, String.valueOf(prefs.getInt("mt_auto_refresh_delay", 1000))); }
            catch (ClassCastException e) { setText(etAutoRefreshDelay, prefs.getString("mt_auto_refresh_delay", "1000")); }
            chkContinuous.setChecked(prefs.getBoolean("grab_continuous", false));
            boolean perKmOn = prefs.getBoolean("grab_use_per_km", false);
            chkUsePerKm.setChecked(perKmOn);
            updateTogglePerKmUI(perKmOn);
            etMinPerKm.setEnabled(perKmOn);
            etMinAmt.setEnabled(!perKmOn);
            etMaxAmt.setEnabled(!perKmOn);
            spDelayAlgo.setSelection(prefs.getInt("grab_delay_algo", 0));
            autoGrabEnabled = prefs.getBoolean("grab_auto_enabled", false);
            pickupLat = Double.parseDouble(prefs.getString("grab_pickup_lat", "0"));
            pickupLng = Double.parseDouble(prefs.getString("grab_pickup_lng", "0"));
            if (pickupLat != 0 && pickupLng != 0 && tvPickupLoc != null) {
                tvPickupLoc.setText(String.format(Locale.US, "取货: %.4f,%.4f", pickupLat, pickupLng));
                tvPickupLoc.setTextColor(C_TEXT);
            }
            deliveryLat = Double.parseDouble(prefs.getString("grab_delivery_lat", "0"));
            deliveryLng = Double.parseDouble(prefs.getString("grab_delivery_lng", "0"));
            if (deliveryLat != 0 && deliveryLng != 0 && tvDeliveryLoc != null) {
                tvDeliveryLoc.setText(String.format(Locale.US, "送货: %.4f,%.4f", deliveryLat, deliveryLng));
                tvDeliveryLoc.setTextColor(C_TEXT);
            }
            if (autoGrabEnabled) { startAutoGrab(); }
        } catch (Exception e) { log("配置加载异常: " + e.getMessage()); }
    }

    private void saveConfig() {
        prefs.edit()
            .putString("grab_min_amount", txt(etMinAmt))
            .putString("grab_max_amount", txt(etMaxAmt))
            .putBoolean("grab_use_per_km", chkUsePerKm.isChecked())
            .putString("grab_min_per_km", txt(etMinPerKm))
            .putString("grab_pickup_lat", String.valueOf(pickupLat))
            .putString("grab_pickup_lng", String.valueOf(pickupLng))
            .putString("grab_delivery_lat", String.valueOf(deliveryLat))
            .putString("grab_delivery_lng", String.valueOf(deliveryLng))
            .putString("grab_pickup_keywords", txt(etPickupKeywords))
            .putString("grab_delivery_keywords", txt(etDeliveryKeywords))
            .putString("grab_onway_min", txt(etOnWayMin))
            .putString("grab_delay_min", txt(etDelayMin))
            .putString("grab_delay_max", txt(etDelayMax))
            .putInt("grab_delay_algo", spDelayAlgo.getSelectedItemPosition())
            .putBoolean("grab_auto_enabled", autoGrabEnabled)
            .putString("grab_max_orders", txt(etMaxOrders))
            .putBoolean("mt_auto_refresh_home", autoRefreshOn)
            .putInt("mt_auto_refresh_delay", parseInt(txt(etAutoRefreshDelay), 1000))
            .putBoolean("grab_continuous", chkContinuous.isChecked())
            .apply();
    }

    private final java.util.concurrent.ExecutorService configSaver = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean saveQueued = false;

    private void writeConfigFile() {
        synchronized (this) {
            if (saveQueued) return; // 已有待执行任务，执行时会读取最新状态（合并写入）
            saveQueued = true;
        }
        configSaver.execute(() -> {
            synchronized (FloatingWindowService.this) { saveQueued = false; }
            try {
                saveConfig();
                // ★ 先加载现有配置合并，保留 MainActivity 写入的键（mt_location_spoof/mt_anti_mock 等），
                //   否则重建配置会把这些键覆盖丢失 → 模拟定位失效
                JSONObject j = new JSONObject();
                try {
                    java.io.File cfgFile = new java.io.File("/data/local/tmp/mt_config.json");
                    if (cfgFile.exists() && cfgFile.canRead()) {
                        String existing = new String(java.nio.file.Files.readAllBytes(cfgFile.toPath()), "UTF-8");
                        org.json.JSONObject old = new org.json.JSONObject(existing);
                        java.util.Iterator<String> it = old.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            if (!j.has(k)) j.put(k, old.get(k));
                        }
                    }
                } catch (Throwable ignored) {}
                for (String k : new String[]{"mt_update","mt_watermark","mt_screenshot","mt_anti_detect","mt_online_check","mt_dev_options","mt_hide_speed","mt_time_offset","mt_auto_refresh_home"}) j.put(k, prefs.getBoolean(k, false));
                // ★ 刷新间隔以字符串存储（EditText），需解析后写入
                try { j.put("mt_auto_refresh_delay", prefs.getInt("mt_auto_refresh_delay", 1000)); }
                catch (ClassCastException e) { j.put("mt_auto_refresh_delay", parseInt(prefs.getString("mt_auto_refresh_delay", "1000"), 1000)); }
                j.put("location_lat", prefs.getFloat("location_lat", 0f));
                j.put("location_lng", prefs.getFloat("location_lng", 0f));
                j.put("grab_auto_enabled", autoGrabEnabled);
                j.put("grab_continuous", prefs.getBoolean("grab_continuous", false));
                for (String k : new String[]{"grab_min_amount","grab_max_amount","grab_min_per_km","grab_pickup_range","grab_delivery_range","grab_delay_min","grab_delay_max","grab_pickup_keywords","grab_delivery_keywords","grab_max_orders"}) j.put(k, prefs.getString(k, ""));
                j.put("grab_pickup_lat", Double.parseDouble(prefs.getString("grab_pickup_lat", "0")));
                j.put("grab_pickup_lng", Double.parseDouble(prefs.getString("grab_pickup_lng", "0")));
                j.put("grab_delivery_lat", Double.parseDouble(prefs.getString("grab_delivery_lat", "0")));
                j.put("grab_delivery_lng", Double.parseDouble(prefs.getString("grab_delivery_lng", "0")));
                j.put("grab_use_per_km", prefs.getBoolean("grab_use_per_km", false));
                j.put("grab_onway_min", prefs.getString("grab_onway_min", ""));
                j.put("grab_delay_algo", prefs.getInt("grab_delay_algo", 0));
                // 抢转单/抢路线任务（开关开启=抢符合条件的此类订单；关闭=一律不抢）
                j.put("grab_transfer", prefs.getBoolean("grab_transfer", true));
                j.put("grab_route", prefs.getBoolean("grab_route", true));
                // 高德key配置（从SharedPreferences读取，随配置文件一起持久化）
                SharedPreferences mapPrefs = getSharedPreferences("mtq_map_prefs", MODE_PRIVATE);
                j.put("gaode_key", mapPrefs.getString("gaode_api_key", ""));
                j.put("gaode_jscode", mapPrefs.getString("gaode_security_jscode", ""));

                String jsonStr = j.toString(2);
                // ★ 统一写入 /data/local/tmp/mt_config.json — 控制端和Hook端共用
                // 优先通过 su 写入（临时文件写到应用缓存目录）
                try {
                    java.io.File tmpDir = getCacheDir();
                    if (tmpDir == null) tmpDir = new java.io.File("/data/local/tmp/");
                    java.io.File tmpFile = new java.io.File(tmpDir, "su_config_" + System.nanoTime() + ".tmp");
                    java.io.FileWriter fw = new java.io.FileWriter(tmpFile);
                    java.io.BufferedWriter bw = new java.io.BufferedWriter(fw);
                    bw.write(jsonStr);
                    bw.close();
                    fw.close();
                    java.lang.Process suCp = Runtime.getRuntime().exec(new String[]{"su", "-c", "cp " + tmpFile.getAbsolutePath() + " /data/local/tmp/mt_config.json"});
                    boolean done = suCp.waitFor(5000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!done) suCp.destroyForcibly();
                    tmpFile.delete();
                    // ★ 确保目标App进程可读（su cp 创建的文件 owner 是 root:root:600）
                    if (done) {
                        try { Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 /data/local/tmp/mt_config.json"}).waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                        try { Runtime.getRuntime().exec(new String[]{"su", "-c", "chmod 666 /data/local/tmp/mt_config.json"}).waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t1) {
                    // 回退：直接写入 /data/local/tmp/
                    try {
                        java.io.File hf = new java.io.File("/data/local/tmp/mt_config.json");
                        java.io.File hp = hf.getParentFile();
                        if (hp != null && !hp.exists()) hp.mkdirs();
                        java.io.FileWriter fw = new java.io.FileWriter(hf);
                        java.io.BufferedWriter bw = new java.io.BufferedWriter(fw);
                        bw.write(jsonStr);
                        bw.close();
                        fw.close();
                        hf.setReadable(true, false);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) { Log.e(TAG, "writeConfigFile", e); }
        });
    }

    private void toggle() {
        isExpanded = !isExpanded;
        panelCollapsed.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
        panelExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        floatParams.width = dp(isExpanded ? 320 : 300);
        if (!isExpanded) {
            if ((floatParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                floatParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            // 收起键盘
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(floatRoot.getWindowToken(), 0);
        }
        wm.updateViewLayout(floatRoot, floatParams);
    }

    // ═══════════ 拖拽 ═══════════
    private void setupDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            int lx, ly; boolean moved;
            public boolean onTouch(View view, MotionEvent e) {
                if (isExpanded && e.getRawY() - floatParams.y > dp(40)) return false;
                // 触摸非EditText区域时恢复FLAG_NOT_FOCUSABLE
                if (e.getAction() == MotionEvent.ACTION_DOWN && (floatParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                    View touched = findFocusEditText(floatRoot, (int)e.getRawX(), (int)e.getRawY());
                    if (touched == null) {
                        floatParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        wm.updateViewLayout(floatRoot, floatParams);
                        android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                        if (imm != null) imm.hideSoftInputFromWindow(floatRoot.getWindowToken(), 0);
                    }
                }
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: lx = (int)e.getRawX(); ly = (int)e.getRawY(); moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)e.getRawX() - lx, dy = (int)e.getRawY() - ly;
                        if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;
                        if (moved) { floatParams.x += dx; floatParams.y += dy; wm.updateViewLayout(floatRoot, floatParams); }
                        lx = (int)e.getRawX(); ly = (int)e.getRawY(); return true;
                    case MotionEvent.ACTION_UP: return moved;
                }
                return false;
            }
        });
    }

    /** 递归查找触摸点下的EditText */
    private View findFocusEditText(View v, int rawX, int rawY) {
        if (v instanceof EditText) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + v.getWidth() &&
                rawY >= loc[1] && rawY <= loc[1] + v.getHeight()) {
                return v;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFocusEditText(vg.getChildAt(i), rawX, rawY);
                if (found != null) return found;
            }
        }
        return null;
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }
    private int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private double parseD(String s, double def) { try { return Double.parseDouble(s); } catch (Exception e) { return def; } }
    private int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }
    private String txt(EditText et) { return et != null ? et.getText().toString().trim() : ""; }
    private void setText(EditText et, String v) { if (et != null && v != null && !v.isEmpty()) et.setText(v); }

    private double haversine(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) + Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLng/2)*Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private GradientDrawable makeBg(int color, int r) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(r)); g.setColor(color); return g;
    }

    private View makeDot(int size, int color) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color);
        v.setBackground(g); return v;
    }

    private View makeDivider() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        v.setBackgroundColor(C_DIVIDER); return v;
    }

    private TextView makeLabel(String t) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(11);
        tv.setTextColor(C_LABEL); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(2)); return tv;
    }

    private TextView makeHint(String t) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(9);
        tv.setTextColor(C_HINT); tv.setPadding(0, dp(2), 0, dp(6)); return tv;
    }

    private EditText makeInput(String hint, int type) {
        EditText et = new EditText(this);
        et.setHint(hint); et.setHintTextColor(0xFF4A5568);
        et.setTextSize(12); et.setTextColor(C_TEXT);
        et.setInputType(type); et.setSingleLine(true);
        et.setOnFocusChangeListener((v, f) -> {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8)); bg.setColor(C_INPUT_BG);
            bg.setStroke(dp(f ? 2 : 1), f ? C_ORANGE : C_INPUT_STROKE);
            et.setBackground(bg);
            if (f) {
                floatParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                wm.updateViewLayout(floatRoot, floatParams);
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        et.setOnTouchListener((v, event) -> {
            if ((floatParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                floatParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                wm.updateViewLayout(floatRoot, floatParams);
                et.requestFocus();
            }
            return false;
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8)); bg.setColor(C_INPUT_BG);
        bg.setStroke(dp(1), C_INPUT_STROKE);
        et.setBackground(bg);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        et.setMinimumHeight(dp(36));
        return et;
    }

    private CheckBox makeSwitch(String t) {
        CheckBox cb = new CheckBox(this); cb.setText(t); cb.setTextSize(12);
        cb.setTextColor(C_TEXT); cb.setTypeface(Typeface.DEFAULT_BOLD);
        cb.setButtonTintList(ColorStateList.valueOf(C_GREEN));
        cb.setPadding(0, dp(6), 0, dp(4)); return cb;
    }

    private TextView makeCapsule(String t, int c) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(10);
        tv.setTextColor(c); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(3), dp(10), dp(3));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(20));
        bg.setColor(0x18FFFFFF); bg.setStroke(dp(1), 0x30FFFFFF);
        tv.setBackground(bg); return tv;
    }

    private TextView makeBtnBig(String t, int c) {
        TextView tv = new TextView(this); tv.setText(t); tv.setTextSize(12);
        tv.setTextColor(c); tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12));
        bg.setColor(0x22FFFFFF); bg.setStroke(dp(1), 0x40FFFFFF);
        tv.setBackground(bg); return tv;
    }

    private LinearLayout makeRow(EditText a, EditText b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        r.addView(a, lp);
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
        r.addView(gap);
        r.addView(b, lp);
        return r;
    }

    private Button makeAction(String t, int color, View.OnClickListener l) {
        Button b = new Button(this); b.setText(t); b.setTextSize(12);
        b.setTextColor(Color.WHITE); b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(10));
        bg.setColor(color); b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setAllCaps(false); b.setOnClickListener(l);
        return b;
    }

    // ═══════════ 支持嵌套滚动的内层 ScrollView ═══════════
    // 外层 ScrollView 会默认拦截内层 ScrollView 的触摸事件，导致内层无法滚动。
    // 该容器在内容可滚动时请求父容器放行触摸，实现订单区/日志区独立上下滑动。
    private static class InnerScrollView extends ScrollView {
        private float downY;

        InnerScrollView(Context c) { super(c); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                downY = ev.getY();
                // 内容超出自身高度（可滚动）时，请求父容器不要拦截本次手势
                getParent().requestDisallowInterceptTouchEvent(canScrollInternal());
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_MOVE && getChildCount() > 0) {
                float dy = ev.getY() - downY;
                boolean atTop = getScrollY() <= 0 && dy > 0;      // 已到顶部且继续向下滑
                boolean atBottom = getScrollY() + getHeight() >= getChildAt(0).getBottom() && dy < 0; // 已到底部且继续向上滑
                // 内层滚到边界后，放行父容器接管（恢复外层整体滚动）
                if (atTop || atBottom) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                } else {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
            }
            return super.onTouchEvent(ev);
        }

        /** 内容高度是否超过可视高度 */
        private boolean canScrollInternal() {
            if (getChildCount() == 0) return false;
            return getChildAt(0).getHeight() > getHeight();
        }
    }
}
