package com.swipesim;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.swipesim.SwipeConfig.Mode;
import com.swipesim.SwipeConfig.ClickPoint;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ===== 滑动相关 =====
    private TextView dirUp, dirDown, dirLeft, dirRight;
    private SeekBar sbDist, sbDur, sbMidPause, sbMidPos, sbOffset, sbInterval;
    private EditText etDist, etDur, etMidPause, etMidPos, etOffset, etInterval;
    private boolean settingText = false; // 防止 TextWatcher 死循环

    // ===== 点击模式 =====
    private View swipeParams, clickParams;
    private TextView tabSwipe, tabClick;
    private LinearLayout pointContainer;
    private TextView btnAddPoint;
    private final List<PointRowHolder> pointHolders = new ArrayList<>();

    // ===== 方案管理 =====
    private ProfileManager profiles;
    private TextView tvProfileName;
    private TextView btnSaveNewProfile, btnSaveOverProfile, btnListProfile;

    // ===== 公共 =====
    private TextView tvAccStatus, tvOverlayStatus;
    private TextView tvRunStatus, tvRunSub;
    private Button btnShowFloat, btnHideFloat, btnStart, btnStop;

    private SwipeConfig cfg;

    private static final int REQ_OVERLAY = 1001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (SwipeAccessibilityService.ACTION_STATUS_ANS.equals(a)) {
                    boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
                    int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
                    String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
                    String sub   = intent.getStringExtra(SwipeAccessibilityService.EXTRA_SUB);
                    if (tvRunStatus != null) {
                        tvRunStatus.setText("状态：" + stateText(running, state) + " ｜ 已完成：" + count + " 轮");
                        tvRunStatus.setTextColor(running ? 0xFF38BDF8 : ("error".equals(state) ? 0xFFF87171 : 0xFFA1A1AA));
                    }
                    if (tvRunSub != null) tvRunSub.setText(sub == null || sub.isEmpty() ? "" : "当前：" + sub);
                } else if (FloatingService.ACTION_FLOATING_UPDATE.equals(a)) {
                    // 悬浮窗改了参数 → 重新加载 cfg 并刷新主界面
                    try {
                        cfg = SwipeConfig.load(MainActivity.this);
                        if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
                        applyCfgToUi();
                    } catch (Throwable ignored) {}
                    updateOverlayStatus();
                } else if (SwipeAccessibilityService.ACTION_ACC_STATE_CHANGED.equals(a)) {
                    // 无障碍服务连接变化，延迟 300ms 再刷一次（Settings.Secure 有写入延迟）
                    updateAccessibilityStatus();
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() { updateAccessibilityStatus(); }
                    }, 300);
                    mainHandler.postDelayed(new Runnable() {
                        @Override public void run() { updateAccessibilityStatus(); }
                    }, 1000);
                }
            } catch (Throwable t) {
                toastShort("状态刷新异常：" + safeMsg(t));
            }
        }
    };

    private static String stateText(boolean running, String state) {
        String s = state == null ? "idle" : state;
        if (!running) return "空闲";
        switch (s) {
            case "swiping":       return "滑动中";
            case "pausing_mid":   return "中途停顿";
            case "clicking":      return "点击中";
            case "waiting_point": return "等待下一个点";
            case "waiting_next":
            case "waiting_cycle": return "等待下一轮";
            case "error":         return "出错";
            default:              return "运行中";
        }
    }

    private static class PointRowHolder {
        final View root;
        final int index;
        final TextView name, btnCapture, btnRemove;
        final EditText xVal, yVal, delayVal;
        final SeekBar xSb, ySb, delaySb;

        PointRowHolder(View root, int index) {
            this.root = root;
            this.index = index;
            name = root.findViewById(R.id.pt_name);
            xVal = root.findViewById(R.id.pt_x_val);
            yVal = root.findViewById(R.id.pt_y_val);
            delayVal = root.findViewById(R.id.pt_delay_val);
            btnCapture = root.findViewById(R.id.btn_capture_point);
            btnRemove = root.findViewById(R.id.btn_remove_point);
            xSb = root.findViewById(R.id.pt_x_sb);
            ySb = root.findViewById(R.id.pt_y_sb);
            delaySb = root.findViewById(R.id.pt_delay_sb);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        profiles = new ProfileManager(this);

        // 如果已经有激活方案，先加载（覆盖默认 cfg）；否则用 SwipeConfig.load
        String active = profiles.getActiveName();
        if (!active.isEmpty() && profiles.exists(active)) {
            cfg = profiles.loadByName(active);
        }
        if (cfg == null) cfg = SwipeConfig.load(this);

        swipeParams = findViewById(R.id.swipe_params);
        clickParams = findViewById(R.id.click_params);
        tabSwipe = findViewById(R.id.tab_swipe);
        tabClick = findViewById(R.id.tab_click);
        pointContainer = findViewById(R.id.point_container);
        btnAddPoint = findViewById(R.id.btn_add_point);

        dirUp    = findViewById(R.id.dir_up);
        dirDown  = findViewById(R.id.dir_down);
        dirLeft  = findViewById(R.id.dir_left);
        dirRight = findViewById(R.id.dir_right);

        sbDist = findViewById(R.id.sb_dist);
        etDist = findViewById(R.id.et_dist);
        sbDur = findViewById(R.id.sb_dur);
        etDur = findViewById(R.id.et_dur);
        sbMidPause = findViewById(R.id.sb_mid_pause);
        etMidPause = findViewById(R.id.et_mid_pause);
        sbMidPos = findViewById(R.id.sb_mid_pos);
        etMidPos = findViewById(R.id.et_mid_pos);
        sbOffset = findViewById(R.id.sb_offset);
        etOffset = findViewById(R.id.et_offset);
        sbInterval = findViewById(R.id.sb_interval);
        etInterval = findViewById(R.id.et_interval);

        tvAccStatus = findViewById(R.id.tv_acc_status);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvRunStatus = findViewById(R.id.tv_run_status);
        tvRunSub = findViewById(R.id.tv_run_sub);
        btnShowFloat = findViewById(R.id.btn_show_floating);
        btnHideFloat = findViewById(R.id.btn_hide_floating);
        btnStart = findViewById(R.id.btn_quick_start);
        btnStop = findViewById(R.id.btn_stop);

        tvProfileName = findViewById(R.id.tv_profile_name);
        btnSaveNewProfile = findViewById(R.id.btn_save_new_profile);
        btnSaveOverProfile = findViewById(R.id.btn_save_over_profile);
        btnListProfile = findViewById(R.id.btn_list_profile);

        // ===== 方向按钮 =====
        View.OnClickListener dirClk = wrap(v -> {
            if (v == dirUp)      cfg.direction = SwipeConfig.Direction.UP;
            if (v == dirDown)    cfg.direction = SwipeConfig.Direction.DOWN;
            if (v == dirLeft)    cfg.direction = SwipeConfig.Direction.LEFT;
            if (v == dirRight)   cfg.direction = SwipeConfig.Direction.RIGHT;
            refreshDirs();
            saveCfg();
        }, "切换方向");
        dirUp.setOnClickListener(dirClk);
        dirDown.setOnClickListener(dirClk);
        dirLeft.setOnClickListener(dirClk);
        dirRight.setOnClickListener(dirClk);
        refreshDirs();

        // ===== SeekBars + EditText 双向联动 =====
        // 每个参数：(SeekBar, EditText, min, max, 初始值, cfg写入回调)
        setupSwipeParam(sbDist, etDist, 10, 90, cfg.distancePct, new ValSetter() {
            @Override public void set(int v) { cfg.distancePct = v; }
        });
        setupSwipeParam(sbDur, etDur, 50, Integer.MAX_VALUE, cfg.durationMs, new ValSetter() {
            @Override public void set(int v) { cfg.durationMs = v; }
        });
        setupSwipeParam(sbMidPause, etMidPause, 0, Integer.MAX_VALUE, cfg.midPauseMs, new ValSetter() {
            @Override public void set(int v) { cfg.midPauseMs = v; }
        });
        setupSwipeParam(sbMidPos, etMidPos, 0, 100, cfg.midPausePosPct, new ValSetter() {
            @Override public void set(int v) { cfg.midPausePosPct = v; }
        });
        setupSwipeParam(sbOffset, etOffset, 0, 100, cfg.startOffsetPct, new ValSetter() {
            @Override public void set(int v) { cfg.startOffsetPct = v; }
        });
        setupSwipeParam(sbInterval, etInterval, 1, 600, cfg.intervalSec, new ValSetter() {
            @Override public void set(int v) { cfg.intervalSec = v; }
        });

        // ===== Tab =====
        View.OnClickListener tabClk = wrap(v -> {
            Mode m = (v == tabSwipe) ? Mode.SWIPE : Mode.CLICK;
            switchMode(m);
        }, "切换模式");
        tabSwipe.setOnClickListener(tabClk);
        tabClick.setOnClickListener(tabClk);

        btnAddPoint.setOnClickListener(wrap(v -> {
            if (cfg.clickPoints.size() >= 8) {
                toastShort("最多 8 个点击点");
                return;
            }
            if (cfg.clickPoints.isEmpty()) {
                addClickPoint(25, 50, 10);
                addClickPoint(75, 50, 10);
            } else {
                ClickPoint last = cfg.clickPoints.get(cfg.clickPoints.size() - 1);
                int nx = (last.xPct + 20) % 100;
                int ny = (last.yPct + 15) % 100;
                if (nx < 5) nx = 50;
                if (ny < 5) ny = 50;
                addClickPoint(nx, ny, Math.max(0, Math.min(600, last.delaySec)));
            }
            saveCfg();
        }, "添加点"));

        // ===== 方案按钮 =====
        btnSaveNewProfile.setOnClickListener(wrap(v -> showSaveNewDialog(), "保存新方案"));
        btnSaveOverProfile.setOnClickListener(wrap(v -> saveOverActive(), "覆盖当前方案"));
        btnListProfile.setOnClickListener(wrap(v -> showProfileListDialog(), "方案列表"));
        refreshProfileNameUI();

        // ===== 权限 =====
        tvAccStatus.setOnClickListener(wrap(v -> openAccessibilitySettings(), "打开无障碍"));
        tvOverlayStatus.setOnClickListener(wrap(v -> requestOverlayPermission(), "打开悬浮窗权限"));

        btnShowFloat.setOnClickListener(wrap(v -> {
            saveCfg();
            Intent i = new Intent(MainActivity.this, FloatingService.class);
            i.setAction(FloatingService.ACTION_SHOW);
            putCfgIntent(i);
            safeStartFgService(i);
            askOverlayIfNeeded();
        }, "显示悬浮窗"));
        btnHideFloat.setOnClickListener(wrap(v -> {
            Intent i = new Intent(MainActivity.this, FloatingService.class);
            i.setAction(FloatingService.ACTION_HIDE);
            safeStartFgService(i);
        }, "隐藏悬浮窗"));
        btnStart.setOnClickListener(wrap(v -> {
            if (!isAccessibilityEnabled()) {
                toastShort("请先开启无障碍服务");
                openAccessibilitySettings();
                return;
            }
            if (cfg.mode == Mode.CLICK && cfg.clickPoints.isEmpty()) {
                toastShort("请至少添加一个点击点");
                return;
            }
            saveCfg();
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_SYNC));
            Intent fi = new Intent(MainActivity.this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_SHOW);
            putCfgIntent(fi);
            safeStartFgService(fi);
            askOverlayIfNeeded();
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_START));
            toastShort("已开始运行");
        }, "开始"));
        btnStop.setOnClickListener(wrap(v -> {
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STOP));
            toastShort("已停止");
        }, "停止"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                ActivityCompat.requestPermissions(this,
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1002);
            } catch (Throwable ignored) {}
        }

        // clickPoints 兜底：防止 load/profile 后被置 null
        if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();

        switchMode(cfg.mode);
        rebuildPointList();

        IntentFilter f = new IntentFilter();
        f.addAction(SwipeAccessibilityService.ACTION_STATUS_ANS);
        f.addAction(FloatingService.ACTION_FLOATING_UPDATE);
        f.addAction(SwipeAccessibilityService.ACTION_ACC_STATE_CHANGED);
        try {
            ContextCompat.registerReceiver(this, statusReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {}

        // ===== 检测上次崩溃并弹窗展示 =====
        try {
            String lastCrash = App.peekLastCrash(this);
            if (lastCrash != null && !lastCrash.isEmpty()) {
                showLastCrashDialog(lastCrash);
            }
        } catch (Throwable ignored) {}
    }

    // ===== 上次崩溃弹窗 =====
    private void showLastCrashDialog(final String crashText) {
        try {
            final ScrollView sv = new ScrollView(this);
            final TextView tv = new TextView(this);
            int pad = dp(16);
            tv.setPadding(pad, pad, pad, pad);
            tv.setTextSize(11);
            tv.setTextColor(0xFFE2E8F0);
            tv.setText(crashText);
            sv.addView(tv, new ScrollView.LayoutParams(-1, -2));

            AlertDialog.Builder ab = new AlertDialog.Builder(this)
                    .setTitle("⚠ 检测到上次发生崩溃")
                    .setView(sv)
                    .setNegativeButton("关闭", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try { App.clearLastCrash(MainActivity.this); } catch (Throwable ignored) {}
                        }
                    })
                    .setPositiveButton("复制并清除", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                                if (cm != null) {
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", crashText));
                                    toastShort("崩溃日志已复制到剪贴板");
                                }
                            } catch (Throwable ignored) {}
                            try { App.clearLastCrash(MainActivity.this); } catch (Throwable ignored) {}
                        }
                    });
            ab.setCancelable(false);
            ab.show();
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // 方案管理 UI
    // ============================================================
    private void refreshProfileNameUI() {
        String name = profiles.getActiveName();
        if (name == null || name.isEmpty()) {
            tvProfileName.setText("当前方案：未命名（可保存为方案）");
        } else {
            tvProfileName.setText("当前方案：" + name + " （共 " + profiles.listAll().size() + " 个方案）");
        }
    }

    private void showSaveNewDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("方案名称（例如：看视频 / 点广告 / 签到）");
        new AlertDialog.Builder(this)
                .setTitle("保存为新方案")
                .setView(wrapDialogView(et))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            String name = et.getText() == null ? "" : et.getText().toString().trim();
                            if (name.isEmpty()) { toastShort("请输入方案名称"); return; }
                            if (name.length() > 30) name = name.substring(0, 30);
                            if (profiles.exists(name)) {
                                toastShort("方案名已存在，建议用「覆盖当前方案」");
                                return;
                            }
                            profiles.save(name, cfg);
                            profiles.setActive(name);
                            cfg.save(MainActivity.this);
                            refreshProfileNameUI();
                            toastShort("已保存为方案：" + name);
                        } catch (Throwable t) { toastShort("保存失败：" + safeMsg(t)); }
                    }
                }).show();
    }

    private void saveOverActive() {
        String active = profiles.getActiveName();
        if (active.isEmpty()) { toastShort("当前没有激活方案，请先「保存为新方案」"); return; }
        new AlertDialog.Builder(this)
                .setTitle("覆盖方案")
                .setMessage("确定要用当前所有配置覆盖方案「" + active + "」？\n（包括模式、滑动/点击点、所有延时）")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            profiles.save(active, cfg);
                            cfg.save(MainActivity.this);
                            toastShort("已更新方案：" + active);
                        } catch (Throwable t) { toastShort("覆盖失败：" + safeMsg(t)); }
                    }
                }).show();
    }

    private void showProfileListDialog() {
        final List<ProfileManager.Profile> list = profiles.listAll();
        final String active = profiles.getActiveName();
        if (list.isEmpty()) {
            toastShort("还没有任何方案，点「保存为新方案」创建第一个吧");
            return;
        }
        final String[] names = new String[list.size()];
        final boolean[] isActive = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name + (active.equals(list.get(i).name) ? "  ✔当前" : "");
            isActive[i] = active.equals(list.get(i).name);
        }
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("我的方案  —  点一个来启用；长按/或点侧按钮重命名和删除");
        ab.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                try {
                    ProfileManager.Profile p = list.get(which);
                    SwipeConfig loaded = profiles.loadByName(p.name);
                    if (loaded == null) { toastShort("加载失败"); return; }
                    cfg = loaded;
                    applyCfgToUi();
                    refreshProfileNameUI();
                    toastShort("已启用方案：" + p.name);
                    // 同步刷新悬浮窗
                    sendCfgRefresh();
                } catch (Throwable t) { toastShort("启用失败：" + safeMsg(t)); }
            }
        });
        ab.setNeutralButton("删除/重命名…", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                showProfileManageDialog(list, active);
            }
        });
        ab.setPositiveButton("关闭", null);
        ab.show();
    }

    private void showProfileManageDialog(final List<ProfileManager.Profile> list, final String active) {
        final String[] plainNames = new String[list.size()];
        for (int i = 0; i < list.size(); i++) plainNames[i] = list.get(i).name;
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("管理方案（选择一个执行操作）");
        ab.setItems(plainNames, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final String pickName = list.get(which).name;
                final CharSequence[] ops = new CharSequence[] {
                        "🔄 重命名",
                        "✕ 删除方案" + (pickName.equals(active) ? "（当前启用中）" : "")
                };
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("操作方案：" + pickName)
                        .setItems(ops, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int op) {
                                if (op == 0) {
                                    final EditText et = new EditText(MainActivity.this);
                                    et.setText(pickName);
                                    et.setSelection(et.getText().length());
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("重命名方案")
                                            .setView(wrapDialogView(et))
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dlg, int w) {
                                                    try {
                                                        String nm = et.getText() == null ? "" : et.getText().toString().trim();
                                                        if (nm.isEmpty()) { toastShort("请输入名称"); return; }
                                                        if (nm.length() > 30) nm = nm.substring(0, 30);
                                                        boolean ok = profiles.rename(pickName, nm);
                                                        toastShort(ok ? "已重命名为：" + nm : "重命名失败（同名或不存在）");
                                                        refreshProfileNameUI();
                                                    } catch (Throwable t) { toastShort("重命名异常：" + safeMsg(t)); }
                                                }
                                            }).show();
                                } else {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("确认删除")
                                            .setMessage("确定删除方案「" + pickName + "」？删除后不可恢复。")
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dlg, int w) {
                                                    try {
                                                        boolean ok = profiles.delete(pickName);
                                                        toastShort(ok ? "已删除方案：" + pickName : "删除失败");
                                                        refreshProfileNameUI();
                                                    } catch (Throwable t) { toastShort("删除异常：" + safeMsg(t)); }
                                                }
                                            }).show();
                                }
                            }
                        }).show();
            }
        });
        ab.show();
    }

    private LinearLayout wrapDialogView(EditText et) {
        LinearLayout ll = new LinearLayout(this);
        int pad = dp(20);
        ll.setPadding(pad, dp(14), pad, 0);
        ll.addView(et, new LinearLayout.LayoutParams(-1, -2));
        return ll;
    }
    private int dp(int dp) { return (int)(dp * getResources().getDisplayMetrics().density + 0.5f); }

    private void applyCfgToUi() {
        try {
            switchMode(cfg.mode);
            settingText = true;
            try {
                sbDist.setProgress(Math.max(10, Math.min(90, cfg.distancePct)));
                sbDur.setProgress(cfg.durationMs);
                sbMidPause.setProgress(cfg.midPauseMs);
                sbMidPos.setProgress(cfg.midPausePosPct);
                sbOffset.setProgress(cfg.startOffsetPct);
                sbInterval.setProgress(cfg.intervalSec);
                etDist.setText(String.valueOf(Math.max(10, Math.min(90, cfg.distancePct))));
                etDur.setText(String.valueOf(Math.max(50, cfg.durationMs)));
                etMidPause.setText(String.valueOf(Math.max(0, cfg.midPauseMs)));
                etMidPos.setText(String.valueOf(Math.max(0, Math.min(100, cfg.midPausePosPct))));
                etOffset.setText(String.valueOf(Math.max(0, Math.min(100, cfg.startOffsetPct))));
                etInterval.setText(String.valueOf(Math.max(1, Math.min(600, cfg.intervalSec))));
            } finally { settingText = false; }
            refreshDirs();
            rebuildPointList();
        } catch (Throwable t) { toastShort("应用配置异常：" + safeMsg(t)); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateAccessibilityStatus();
            updateOverlayStatus();
            refreshProfileNameUI();
            // 从无障碍设置页切回来，Settings.Secure 更新有延迟（MIUI/ColorOS 明显）
            // 300ms、1000ms、2000ms 再刷三次兜底
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { updateAccessibilityStatus(); }
            }, 300);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { updateAccessibilityStatus(); }
            }, 1000);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { updateAccessibilityStatus(); }
            }, 2000);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ============================================================
    // 模式切换
    // ============================================================
    private void switchMode(Mode m) {
        cfg.mode = m;
        if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
        boolean isSwipe = (m == Mode.SWIPE);
        if (swipeParams != null)
            swipeParams.setVisibility(isSwipe ? View.VISIBLE : View.GONE);
        if (clickParams != null)
            clickParams.setVisibility(isSwipe ? View.GONE : View.VISIBLE);

        if (isSwipe) {
            if (tabSwipe != null) {
                tabSwipe.setBackgroundResource(R.drawable.bg_dir_selected);
                tabSwipe.setTextColor(0xFF38BDF8);
            }
            if (tabClick != null) {
                tabClick.setBackgroundResource(R.drawable.bg_dir_normal);
                tabClick.setTextColor(0xFFE2E8F0);
            }
        } else {
            if (tabClick != null) {
                tabClick.setBackgroundResource(R.drawable.bg_dir_selected);
                tabClick.setTextColor(0xFF38BDF8);
            }
            if (tabSwipe != null) {
                tabSwipe.setBackgroundResource(R.drawable.bg_dir_normal);
                tabSwipe.setTextColor(0xFFE2E8F0);
            }
            if (cfg.clickPoints.isEmpty()) {
                addClickPoint(30, 50, 10);
                addClickPoint(70, 50, 10);
            }
        }
        saveCfg();
        sendCfgRefresh();
    }

    private void sendCfgRefresh() {
        try {
            Intent fi = new Intent(this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_REFRESH);
            putCfgIntent(fi);
            safeStartFgService(fi);
        } catch (Throwable ignored) {}
    }

    private void safeStartFgService(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, i);
            } else {
                startService(i);
            }
        } catch (Throwable t) {
            toastShort("启动服务失败：" + safeMsg(t));
        }
    }

    private void putCfgIntent(Intent i) {
        i.putExtra(SwipeConfig.EXTRA_MODE, cfg.mode.name());
        i.putExtra(SwipeConfig.EXTRA_DIR, cfg.direction.name());
        i.putExtra(SwipeConfig.EXTRA_DIST_PCT, cfg.distancePct);
        i.putExtra(SwipeConfig.EXTRA_DUR_MS, cfg.durationMs);
        i.putExtra(SwipeConfig.EXTRA_INTERVAL_S, cfg.intervalSec);
        i.putExtra(SwipeConfig.EXTRA_MID_PAUSE_MS, cfg.midPauseMs);
        i.putExtra(SwipeConfig.EXTRA_MID_POS_PCT, cfg.midPausePosPct);
        i.putExtra(SwipeConfig.EXTRA_OFFSET_PCT, cfg.startOffsetPct);
        i.putExtra(SwipeConfig.EXTRA_CLICK_JSON, ClickPoint.listToJson(cfg.clickPoints));
    }

    // ============================================================
    // 点列表
    // ============================================================
    private String pointLetter(int idx) {
        if (idx < 26) return String.valueOf((char) ('A' + idx));
        return "P" + (idx + 1);
    }

    private void addClickPoint(int x, int y, int delaySec) {
        ClickPoint p = new ClickPoint();
        p.xPct = x; p.yPct = y; p.delaySec = delaySec;
        cfg.clickPoints.add(p);
        rebuildPointList();
    }

    private void rebuildPointList() {
        pointContainer.removeAllViews();
        pointHolders.clear();
        LayoutInflater lif = LayoutInflater.from(this);
        for (int i = 0; i < cfg.clickPoints.size(); i++) {
            ClickPoint pt = cfg.clickPoints.get(i);
            View row = lif.inflate(R.layout.row_point, pointContainer, false);
            final PointRowHolder h = new PointRowHolder(row, i);
            pointHolders.add(h);
            bindPointRow(h, pt);
            pointContainer.addView(row);
        }
        renamePoints();
    }

    private void renamePoints() {
        for (int i = 0; i < pointHolders.size(); i++) {
            pointHolders.get(i).name.setText(pointLetter(i) + " 点");
        }
    }

    private void bindPointRow(final PointRowHolder h, final ClickPoint pt) {
        // 初始值
        settingText = true;
        try {
            h.xSb.setProgress(pt.xPct);
            h.ySb.setProgress(pt.yPct);
            h.delaySb.setProgress(Math.max(0, Math.min(600, pt.delaySec)));
            h.xVal.setText(String.valueOf(pt.xPct));
            h.yVal.setText(String.valueOf(pt.yPct));
            h.delayVal.setText(String.valueOf(Math.max(0, Math.min(600, pt.delaySec))));
        } finally { settingText = false; }

        // X: SeekBar -> EditText + cfg
        h.xSb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.xPct = p;
                if (!settingText) {
                    settingText = true;
                    try { h.xVal.setText(String.valueOf(p)); }
                    finally { settingText = false; }
                }
                if (user) saveCfg();
            }
        });
        // X: EditText -> SeekBar + cfg
        h.xVal.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                int v = parseIntSafe(s.toString(), pt.xPct);
                v = Math.max(0, Math.min(100, v));
                pt.xPct = v;
                settingText = true;
                try { h.xSb.setProgress(v); }
                finally { settingText = false; }
                saveCfg();
            }
        });

        // Y: SeekBar -> EditText + cfg
        h.ySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.yPct = p;
                if (!settingText) {
                    settingText = true;
                    try { h.yVal.setText(String.valueOf(p)); }
                    finally { settingText = false; }
                }
                if (user) saveCfg();
            }
        });
        // Y: EditText -> SeekBar + cfg
        h.yVal.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                int v = parseIntSafe(s.toString(), pt.yPct);
                v = Math.max(0, Math.min(100, v));
                pt.yPct = v;
                settingText = true;
                try { h.ySb.setProgress(v); }
                finally { settingText = false; }
                saveCfg();
            }
        });

        // Delay: SeekBar -> EditText + cfg
        h.delaySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int v = Math.max(0, Math.min(600, p));
                pt.delaySec = v;
                if (!settingText) {
                    settingText = true;
                    try { h.delayVal.setText(String.valueOf(v)); }
                    finally { settingText = false; }
                }
                if (user) saveCfg();
            }
        });
        // Delay: EditText -> SeekBar + cfg
        h.delayVal.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                int v = parseIntSafe(s.toString(), pt.delaySec);
                v = Math.max(0, Math.min(600, v));
                pt.delaySec = v;
                settingText = true;
                try { h.delaySb.setProgress(v); }
                finally { settingText = false; }
                saveCfg();
            }
        });
        h.btnCapture.setOnClickListener(wrap(v -> {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                toastShort("请先授予悬浮窗权限");
                requestOverlayPermission();
                return;
            }
            saveCfg();
            Intent fi = new Intent(MainActivity.this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_HIDE_TEMP);
            safeStartFgService(fi);
            CaptureOverlayManager.show(MainActivity.this,
                    h.index,
                    pointLetter(h.index),
                    new CaptureOverlayManager.Callback() {
                        @Override public void onSaved(int xPct, int yPct) {
                            try {
                                Intent ri = new Intent(MainActivity.this, FloatingService.class);
                                ri.setAction(FloatingService.ACTION_RESTORE_TEMP);
                                putCfgIntent(ri);
                                safeStartFgService(ri);
                            } catch (Throwable ignored) {}
                            try {
                                if (h.index >= 0 && h.index < cfg.clickPoints.size()) {
                                    ClickPoint p = cfg.clickPoints.get(h.index);
                                    p.xPct = xPct; p.yPct = yPct;
                                    h.xSb.setProgress(xPct);
                                    h.ySb.setProgress(yPct);
                                    saveCfg();
                                    toastShort(pointLetter(h.index) + " 点已更新 X=" + xPct + "% Y=" + yPct + "%");
                                }
                            } catch (Throwable t) { toastShort("更新点失败：" + safeMsg(t)); }
                        }
                    });
        }, "采点"));
        h.btnRemove.setOnClickListener(wrap(v -> {
            if (cfg.clickPoints.size() <= 1) {
                toastShort("至少保留 1 个点");
                return;
            }
            cfg.clickPoints.remove(h.index);
            saveCfg();
            rebuildPointList();
        }, "删除点"));
    }

    // ============================================================
    // 辅助方法
    // ============================================================
    private void refreshDirs() {
        applyDirBg(dirUp,    SwipeConfig.Direction.UP);
        applyDirBg(dirDown,  SwipeConfig.Direction.DOWN);
        applyDirBg(dirLeft,  SwipeConfig.Direction.LEFT);
        applyDirBg(dirRight, SwipeConfig.Direction.RIGHT);
    }

    private void applyDirBg(TextView tv, SwipeConfig.Direction d) {
        if (cfg.direction == d) {
            tv.setBackgroundResource(R.drawable.bg_dir_selected);
            tv.setTextColor(0xFF38BDF8);
        } else {
            tv.setBackgroundResource(R.drawable.bg_dir_normal);
            tv.setTextColor(0xFFE2E8F0);
        }
    }

    private void saveCfg() {
        try { cfg.save(this); }
        catch (Throwable t) { toastShort("保存配置异常：" + safeMsg(t)); }
    }

    private void updateAccessibilityStatus() {
        boolean ok = isAccessibilityEnabled();
        tvAccStatus.setText(ok ? "✓ 已开启" : "✗ 点击开启");
        tvAccStatus.setBackgroundColor(ok ? 0xFF14532D : 0xFF7F1D1D);
        tvAccStatus.setTextColor(0xFFFFFFFF);
    }

    private boolean isAccessibilityEnabled() {
        // 方法1：AccessibilityManager 列表（可能在部分 ROM 不准，作为次优先）
        boolean method1 = false;
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am != null && am.isEnabled()) {
                List<AccessibilityServiceInfo> list =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                ComponentName cn = new ComponentName(this, SwipeAccessibilityService.class);
                String shortId = cn.flattenToShortString();
                String longId  = cn.flattenToString();
                String pkg     = cn.getPackageName();
                String cls     = cn.getClassName();
                for (AccessibilityServiceInfo s : list) {
                    if (s == null || s.getResolveInfo() == null || s.getResolveInfo().serviceInfo == null) continue;
                    String p = s.getResolveInfo().serviceInfo.packageName;
                    String c = s.getResolveInfo().serviceInfo.name;
                    String id = p + "/" + c;
                    if (id.equals(shortId) || id.equals(longId)) { method1 = true; break; }
                    // 有些 ROM 的 c 是短类名（相对 pkg 的相对路径），尝试比对
                    if (pkg.equals(p) && (cls.equals(c) || cls.endsWith("." + c) || (pkg + c).equals(cls))) {
                        method1 = true; break;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 方法2：读 Settings.Secure enabled_accessibility_services（最可靠，所有系统都会写）
        boolean method2 = false;
        try {
            String enabled = android.provider.Settings.Secure.getString(
                    getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled != null && !enabled.isEmpty()) {
                ComponentName cn = new ComponentName(this, SwipeAccessibilityService.class);
                String shortId = cn.flattenToShortString();
                String longId  = cn.flattenToString();
                String flatPkg = cn.getPackageName() + "/" + cn.getShortClassName(); // 注意：getShortClassName 可能是 ".SwipeAccessibilityService"
                String absCls  = cn.getPackageName() + cn.getShortClassName(); // 补成绝对
                for (String seg : enabled.split(":")) {
                    seg = seg.trim();
                    if (seg.isEmpty()) continue;
                    if (seg.equalsIgnoreCase(shortId)
                            || seg.equalsIgnoreCase(longId)
                            || seg.equalsIgnoreCase(flatPkg)
                            || seg.equalsIgnoreCase(absCls)) {
                        method2 = true; break;
                    }
                    // 再模糊一次：按 / 劈开，两边分别是包名和类名
                    String[] ps = seg.split("/", 2);
                    if (ps.length == 2) {
                        String segPkg = ps[0].trim();
                        String segCls = ps[1].trim();
                        String ourPkg = cn.getPackageName();
                        String ourCls = cn.getClassName();
                        if (ourPkg.equalsIgnoreCase(segPkg)) {
                            if (ourCls.equalsIgnoreCase(segCls)
                                    || ourCls.endsWith("." + segCls)
                                    || (segCls.startsWith(".") && ourCls.equalsIgnoreCase(segPkg + segCls))) {
                                method2 = true; break;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 方法3：我们自己的 Service 已经 onServiceConnected（最直接）
        boolean method3 = (SwipeAccessibilityService.get() != null);

        // 任意一个为真即认为开启（方法2/3 优先级更高，方法1 有缓存延迟）
        return method2 || method3 || method1;
    }

    private void openAccessibilitySettings() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        Toast.makeText(this, "找到「滑动模拟器」并开启", Toast.LENGTH_LONG).show();
    }

    private void updateOverlayStatus() {
        boolean ok = Settings.canDrawOverlays(this);
        tvOverlayStatus.setText(ok ? "✓ 已开启" : "✗ 点击开启");
        tvOverlayStatus.setBackgroundColor(ok ? 0xFF14532D : 0xFF7F1D1D);
        tvOverlayStatus.setTextColor(0xFFFFFFFF);
    }

    private void askOverlayIfNeeded() {
        if (!Settings.canDrawOverlays(this)) requestOverlayPermission();
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(i, REQ_OVERLAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updateOverlayStatus();
    }

    private void toastShort(String msg) {
        if (msg == null) return;
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }
    private static String safeMsg(Throwable t) {
        try {
            String msg = t == null ? "未知错误" : t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t == null ? "未知错误" : t.getClass().getSimpleName();
            if (msg.length() > 80) msg = msg.substring(0, 80) + "…";
            return msg;
        } catch (Throwable ignore) { return "异常"; }
    }

    // 所有按钮统一入口：try-catch，闪退只 Toast 错误
    private View.OnClickListener wrap(final View.OnClickListener r, final String tag) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    r.onClick(v);
                } catch (Throwable t) {
                    toastShort(tag + "失败：" + safeMsg(t));
                }
            }
        };
    }

    // ===== 滑动参数通用双向联动 =====
    private interface ValSetter { void set(int v); }
    private void setupSwipeParam(final SeekBar sb, final EditText et,
                                 final int min, final int max, int initVal,
                                 final ValSetter setter) {
        final int clampedInit = Math.max(min, Math.min(max, initVal));
        setter.set(clampedInit);
        settingText = true;
        try {
            // 若 SeekBar 的 max 小于我们的逻辑 max，以 SeekBar.xml 为准（比如 duration/midPause 用xml的max）
            int sbMax = sb.getMax();
            int sbVal = Math.min(clampedInit, sbMax);
            sb.setProgress(sbVal);
            et.setText(String.valueOf(clampedInit));
        } finally { settingText = false; }

        sb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean user) {
                int v = Math.max(min, Math.min(max, p));
                setter.set(v);
                if (!settingText) {
                    settingText = true;
                    try { et.setText(String.valueOf(v)); }
                    finally { settingText = false; }
                }
                if (user) saveCfg();
            }
        });
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                int v = parseIntSafe(s.toString(), Math.max(min, Math.min(max, sb.getProgress())));
                v = Math.max(min, Math.min(max, v));
                setter.set(v);
                settingText = true;
                try {
                    int sbMax = sb.getMax();
                    int sbVal = Math.min(v, sbMax);
                    if (sb.getProgress() != sbVal) sb.setProgress(sbVal);
                } finally { settingText = false; }
                saveCfg();
            }
        });
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            double d = Double.parseDouble(s.toString());
            return (int) Math.round(d);
        } catch (Exception e) { return def; }
    }

    private static abstract class SimpleSB implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
