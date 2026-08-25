package com.swipesim;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.swipesim.SwipeConfig.Mode;
import com.swipesim.SwipeConfig.Direction;
import com.swipesim.SwipeConfig.ClickPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FloatingWindowManager {

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private View rootView;
    private boolean shown = false;

    private ImageView handleView;
    private Button btnStart;
    private TextView tvStatus, tvCount, tvParams, btnClose;
    private View panel;
    private TextView tabSwipe, tabClick;
    private View swipePanel, clickPanel;
    private TextView[] dirBtns; // up, down, left, right
    // 滑动模式 4 行输入
    private ViewGroup rowDistance, rowIntervalSwipe, rowDuration, rowMidPause, rowMidPos, rowOffset;
    private EditText etDistance, etIntervalSwipe, etDuration, etMidPause, etMidPos, etOffset;
    // 点击模式
    private ViewGroup rowIntervalClick;
    private EditText etIntervalClick;
    private SeekBar sbIntervalClick;
    private TextView btnAddPoint;
    private LinearLayout pointsContainer;

    // 方案管理
    private ProfileManager profiles;
    private TextView btnSaveProfile, btnProfiles;

    // 缓存 cfg
    private SwipeConfig cfg;
    private boolean settingText = false; // 防止 TextWatcher 死循环

    private boolean dragging = false;
    private int downRawX, downRawY;
    private int startX, startY;
    private long downAt;

    private boolean tempHidden = false;

    // 保存展开 panel 前小球的位置，收起时恢复
    private int savedHandleX = -1;
    private int savedHandleY = -1;

    // 自定义长按检测（避免和拖动冲突）
    private static final long LONG_PRESS_DELAY_MS = 1500L; // 1.5秒，比系统默认长，减少误触
    private final Handler longPressHandler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = new Runnable() {
        @Override public void run() {
            try {
                toastShort("已关闭悬浮窗");
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(FloatingService.ACTION_HIDE));
            } catch (Throwable ignored) {}
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (SwipeAccessibilityService.ACTION_STATUS_ANS.equals(intent.getAction())) {
                    boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
                    int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
                    String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
                    updateStatus(running, count, state);
                }
            } catch (Throwable ignored) {}
        }
    };

    public FloatingWindowManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // 先默认 0,0；show() 里 addView 后再按屏幕实际尺寸移动到正中央
        params.x = 0;
        params.y = 0;
    }

    @SuppressLint("InflateParams")
    public void show() {
        if (shown) return;
        try {
            rootView = LayoutInflater.from(ctx).inflate(R.layout.floating_widget, null);
            cfg = SwipeConfig.load(ctx);
            if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();

            handleView    = rootView.findViewById(R.id.handle);
            btnStart      = rootView.findViewById(R.id.btn_start);
            tvStatus      = rootView.findViewById(R.id.tv_status);
            tvCount       = rootView.findViewById(R.id.tv_count);
            tvParams      = rootView.findViewById(R.id.tv_params);
            panel         = rootView.findViewById(R.id.panel);
            btnClose      = rootView.findViewById(R.id.btn_close);
            tabSwipe      = rootView.findViewById(R.id.tab_swipe2);
            tabClick      = rootView.findViewById(R.id.tab_click2);
            swipePanel    = rootView.findViewById(R.id.swipe_panel);
            clickPanel    = rootView.findViewById(R.id.click_panel);

            dirBtns = new TextView[4];
            dirBtns[0] = rootView.findViewById(R.id.dir_up);
            dirBtns[1] = rootView.findViewById(R.id.dir_down);
            dirBtns[2] = rootView.findViewById(R.id.dir_left);
            dirBtns[3] = rootView.findViewById(R.id.dir_right);

            // 滑动模式 4 行
            rowDistance       = rootView.findViewById(R.id.row_distance);
            rowIntervalSwipe  = rootView.findViewById(R.id.row_interval_swipe);
            rowDuration       = rootView.findViewById(R.id.row_duration);
            rowMidPause       = rootView.findViewById(R.id.row_midpause);
            rowMidPos         = rootView.findViewById(R.id.row_mid_pos);
            rowOffset         = rootView.findViewById(R.id.row_offset);
            etDistance       = rowDistance.findViewById(R.id.row_value);
            etIntervalSwipe  = rowIntervalSwipe.findViewById(R.id.row_value);
            etDuration       = rowDuration.findViewById(R.id.row_value);
            etMidPause       = rowMidPause.findViewById(R.id.row_value);
            etMidPos         = rowMidPos.findViewById(R.id.row_value);
            etOffset         = rowOffset.findViewById(R.id.row_value);
            setRowLabel(rowDistance, "滑动距离", "%");
            setRowLabel(rowIntervalSwipe, "每轮间隔", "秒");
            setRowLabel(rowDuration, "滑动时长", "ms");
            setRowLabel(rowMidPause, "中点停顿", "ms");
            setRowLabel(rowMidPos, "停顿位置", "%");
            setRowLabel(rowOffset, "起点偏移", "%");

            // 点击模式
            rowIntervalClick = rootView.findViewById(R.id.row_interval_click);
            etIntervalClick  = rowIntervalClick.findViewById(R.id.row_value);
            sbIntervalClick  = rootView.findViewById(R.id.sb_interval_click);
            btnAddPoint      = rootView.findViewById(R.id.btn_add_point);
            pointsContainer  = rootView.findViewById(R.id.points_container);
            setRowLabel(rowIntervalClick, "循环间隔", "秒");

            // 方案按钮
            btnSaveProfile   = rootView.findViewById(R.id.btn_save_profile);
            btnProfiles      = rootView.findViewById(R.id.btn_profiles);
            profiles         = new ProfileManager(ctx);

            setupDrag();
            setupClicks();
            bindAllInputs();
            applyCfgToUi();
        } catch (Throwable t) {
            toastShort("悬浮窗初始化失败：" + safeMsg(t));
            rootView = null;
            shown = false;
            return;
        }

        try {
            wm.addView(rootView, params);
            shown = true;
            tempHidden = false;
        } catch (Throwable t) {
            toastShort("打开悬浮窗失败（请确认悬浮窗权限完全打开，包括「后台显示悬浮窗/显示在其他应用上层」等子项）：" + safeMsg(t));
            rootView = null;
            shown = false;
            return;
        }

        // 把悬浮窗（初始只有圆形小球）移动到屏幕正中央
        try {
            rootView.post(new Runnable() {
                @Override public void run() {
                    try {
                        int w = rootView.getWidth();
                        int h = rootView.getHeight();
                        if (w <= 0 || h <= 0) {
                            rootView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                            w = rootView.getMeasuredWidth();
                            h = rootView.getMeasuredHeight();
                        }
                        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                        // gravity=TOP|START 下，params.x / params.y 是左上角相对屏幕的偏移
                        // 中心定位：(屏幕宽 - 悬浮窗宽) / 2 ，(屏幕高 - 悬浮窗高) / 2
                        params.x = Math.max(0, (dm.widthPixels - w) / 2);
                        // 垂直方向稍微偏上一点（40%），避免展开面板时被底部导航/手势条挡住
                        params.y = Math.max(0, (int) (dm.heightPixels * 0.4) - h / 2);
                        safeUpdate();
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}

        try {
            IntentFilter f = new IntentFilter();
            f.addAction(SwipeAccessibilityService.ACTION_STATUS_ANS);
            LocalBroadcastManager.getInstance(ctx).registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
        try { requestStatus(); } catch (Throwable ignored) {}
        try { refreshParamsText(); } catch (Throwable ignored) {}
        try { updateStatus(false, 0, "idle"); } catch (Throwable ignored) {}
    }

    private void setRowLabel(ViewGroup row, String label, String unit) {
        TextView tvL = row.findViewById(R.id.row_label);
        TextView tvU = row.findViewById(R.id.row_unit);
        if (tvL != null) tvL.setText(label);
        if (tvU != null) tvU.setText(unit);
    }

    public void remove() {
        if (!shown || rootView == null) return;
        try {
            LocalBroadcastManager.getInstance(ctx).unregisterReceiver(receiver);
            wm.removeViewImmediate(rootView);
        } catch (Exception ignored) {}
        shown = false;
        tempHidden = false;
    }

    public void hideTemp() {
        if (!shown || tempHidden || rootView == null) return;
        try { wm.removeViewImmediate(rootView); } catch (Exception ignored) {}
        tempHidden = true;
    }

    public void restoreTemp() {
        if (!tempHidden || rootView == null) return;
        try { wm.addView(rootView, params); } catch (Exception ignored) {}
        tempHidden = false;
        try { applyCfgToUi(); } catch (Throwable ignored) {}
        try { refreshParamsText(); } catch (Throwable ignored) {}
    }

    public void refreshFromIntent(Intent i) {
        try {
            cfg = SwipeConfig.load(ctx);
            if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
            applyCfgToUi();
            refreshParamsText();
            updateBtnStart();
        } catch (Throwable ignored) {}
    }

    // ============== UI 渲染 ==============
    private void applyCfgToUi() {
        settingText = true;
        try {
            // Mode
            setTabUi(cfg.mode);

            // Direction
            setDirectionUi(cfg.direction);

            // Swipe rows
            etDistance.setText(String.valueOf(Math.max(10, Math.min(90, cfg.distancePct))));
            etIntervalSwipe.setText(String.valueOf(Math.max(1, Math.min(600, cfg.intervalSec))));
            etDuration.setText(String.valueOf(Math.max(50, cfg.durationMs)));
            etMidPause.setText(String.valueOf(Math.max(0, cfg.midPauseMs)));
            etMidPos.setText(String.valueOf(Math.max(0, Math.min(100, cfg.midPausePosPct))));
            etOffset.setText(String.valueOf(Math.max(0, Math.min(100, cfg.startOffsetPct))));

            // Click rows
            etIntervalClick.setText(String.valueOf(Math.max(1, Math.min(600, cfg.intervalSec))));
            try { sbIntervalClick.setProgress(Math.max(1, Math.min(600, cfg.intervalSec))); } catch (Throwable ignored) {}
            renderPointsList();
        } finally { settingText = false; }
    }

    private void setTabUi(Mode m) {
        boolean swipe = (m == Mode.SWIPE);
        if (tabSwipe != null) {
            tabSwipe.setBackgroundResource(swipe ? R.drawable.bg_dir_selected : R.drawable.bg_dir_normal);
            tabSwipe.setTextColor(swipe ? 0xFF38BDF8 : 0xFFE2E8F0);
        }
        if (tabClick != null) {
            tabClick.setBackgroundResource(swipe ? R.drawable.bg_dir_normal : R.drawable.bg_dir_selected);
            tabClick.setTextColor(swipe ? 0xFFE2E8F0 : 0xFF38BDF8);
        }
        if (swipePanel != null) swipePanel.setVisibility(swipe ? View.VISIBLE : View.GONE);
        if (clickPanel != null) clickPanel.setVisibility(swipe ? View.GONE : View.VISIBLE);
    }

    private void setDirectionUi(Direction d) {
        int idx = (d == Direction.UP) ? 0 : (d == Direction.DOWN) ? 1 : (d == Direction.LEFT) ? 2 : 3;
        String[] arrows = {"⬆", "⬇", "⬅", "➡"};
        if (dirBtns == null) return;
        for (int i = 0; i < dirBtns.length; i++) {
            if (dirBtns[i] == null) continue;
            dirBtns[i].setSelected(i == idx);
            dirBtns[i].setTextColor(i == idx ? 0xFF38BDF8 : 0xFFE2E8F0);
            dirBtns[i].setText(arrows[i]);
        }
    }

    private static final List<String> POINT_LETTERS = Arrays.asList(
            "A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z",
            "A2","B2","C2","D2","E2","F2","G2","H2","I2","J2","K2","L2","M2","N2","O2","P2","Q2","R2","S2","T2","U2","V2","W2","X2","Y2","Z2");
    private static final int[] POINT_COLORS = {
            0xFF38BDF8, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFA855F7, 0xFFEC4899, 0xFF14B8A6, 0xFFFB923C, 0xFF6366F1, 0xFF84CC16
    };

    private void renderPointsList() {
        if (pointsContainer == null) return;
        pointsContainer.removeAllViews();
        List<ClickPoint> pts = cfg.clickPoints;
        if (pts == null || pts.isEmpty()) return;
        int size = pts.size();
        for (int i = 0; i < size; i++) {
            ClickPoint p = pts.get(i);
            final int fIdx = i;
            String letter = (i < POINT_LETTERS.size()) ? POINT_LETTERS.get(i) : ("P" + (i + 1));
            int color = POINT_COLORS[i % POINT_COLORS.length];

            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp2(2), 0, dp2(2));

            // 字母标识
            TextView tvLet = new TextView(ctx);
            tvLet.setText(letter);
            tvLet.setTextSize(13);
            tvLet.setTypeface(null, android.graphics.Typeface.BOLD);
            tvLet.setTextColor(0xFFFFFFFF);
            tvLet.setGravity(android.view.Gravity.CENTER);
            tvLet.setBackgroundDrawable(makeBg(color));
            int sz = dp2(20);
            tvLet.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
            tvLet.setPadding(0, 0, 0, 0);

            // 坐标显示
            TextView tvXY = new TextView(ctx);
            tvXY.setText("(" + p.xPct + "%," + p.yPct + "%)");
            tvXY.setTextSize(10);
            tvXY.setTextColor(0xFFE2E8F0);
            tvXY.setPadding(dp2(4), 0, dp2(4), 0);

            // 采点按钮
            TextView btnPick = new TextView(ctx);
            btnPick.setText("采");
            btnPick.setTextSize(10);
            btnPick.setTextColor(0xFF38BDF8);
            btnPick.setTypeface(null, android.graphics.Typeface.BOLD);
            btnPick.setGravity(android.view.Gravity.CENTER);
            btnPick.setBackgroundResource(R.drawable.bg_dir_normal);
            btnPick.setPadding(dp2(6), 0, dp2(6), 0);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp2(22));
            btnPick.setLayoutParams(bp);
            btnPick.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startPickPoint(fIdx, letterFromIdx(fIdx));
                }
            });

            // 延时 EditText
            EditText etDelay = new EditText(ctx);
            etDelay.setText(String.valueOf(Math.max(0, Math.min(600, p.delaySec))));
            etDelay.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            etDelay.setTextColor(0xFFE2E8F0);
            etDelay.setTextSize(11);
            etDelay.setGravity(android.view.Gravity.CENTER);
            etDelay.setBackgroundResource(R.drawable.bg_input);
            etDelay.setPadding(dp2(3), 0, dp2(3), 0);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp2(46), dp2(24));
            ep.setMargins(dp2(3), 0, 0, 0);
            etDelay.setLayoutParams(ep);
            etDelay.setHint("秒");
            etDelay.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    if (settingText) return;
                    int v = parseIntSafe(s.toString(), 0);
                    v = Math.max(0, Math.min(600, v));
                    // 同步到 cfg
                    if (fIdx >= 0 && fIdx < cfg.clickPoints.size()) {
                        cfg.clickPoints.get(fIdx).delaySec = v;
                        persistCfg();
                    }
                }
            });
            // 点位延时 EditText 获取焦点时弹软键盘
            etDelay.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) showKeyboardFor(etDelay);
                }
            });

            // 单位"秒"
            TextView tvSec = new TextView(ctx);
            tvSec.setText("秒");
            tvSec.setTextSize(9);
            tvSec.setTextColor(0xFF718096);
            tvSec.setPadding(dp2(2), 0, 0, 0);

            // 占位
            View space = new View(ctx);
            space.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));

            // 删除按钮
            TextView btnDel = new TextView(ctx);
            btnDel.setText("✕");
            btnDel.setTextSize(12);
            btnDel.setTextColor(0xFFF87171);
            btnDel.setGravity(android.view.Gravity.CENTER);
            btnDel.setPadding(dp2(4), 0, dp2(4), 0);
            btnDel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (fIdx >= 0 && fIdx < cfg.clickPoints.size()) {
                        cfg.clickPoints.remove(fIdx);
                        if (cfg.clickPoints.isEmpty()) {
                            cfg.clickPoints.add(new ClickPoint(30, 50, 10));
                        }
                        persistCfg();
                        renderPointsList();
                        refreshParamsText();
                    }
                }
            });

            row.addView(tvLet);
            row.addView(tvXY);
            row.addView(btnPick);
            row.addView(etDelay);
            row.addView(tvSec);
            row.addView(space);
            row.addView(btnDel);
            pointsContainer.addView(row);
        }
    }

    private android.graphics.drawable.GradientDrawable makeBg(int color) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(0, 0);
        return d;
    }

    private static String letterFromIdx(int i) {
        if (i >= 0 && i < POINT_LETTERS.size()) return POINT_LETTERS.get(i);
        return "P" + (i + 1);
    }

    private int dp2(int dp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) (dp * d + 0.5f);
    }

    // ============== 事件绑定 ==============
    private void bindAllInputs() {
        // Tab 切换
        tabSwipe.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode(Mode.SWIPE); }
        });
        tabClick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { switchMode(Mode.CLICK); }
        });
        // 方向按钮
        dirBtns[0].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDirection(Direction.UP); }});
        dirBtns[1].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDirection(Direction.DOWN); }});
        dirBtns[2].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDirection(Direction.LEFT); }});
        dirBtns[3].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { setDirection(Direction.RIGHT); }});

        attachNumWatcher(etDistance, new NumApply() {
            @Override public void apply(int v) { cfg.distancePct = Math.max(10, Math.min(90, v)); persistCfg(); }
        });
        attachNumWatcher(etDuration, new NumApply() {
            @Override public void apply(int v) { cfg.durationMs = Math.max(50, v); persistCfg(); }
        });
        attachNumWatcher(etMidPause, new NumApply() {
            @Override public void apply(int v) { cfg.midPauseMs = Math.max(0, v); persistCfg(); }
        });
        attachNumWatcher(etMidPos, new NumApply() {
            @Override public void apply(int v) { cfg.midPausePosPct = Math.max(0, Math.min(100, v)); persistCfg(); }
        });
        attachNumWatcher(etOffset, new NumApply() {
            @Override public void apply(int v) { cfg.startOffsetPct = Math.max(0, Math.min(100, v)); persistCfg(); }
        });
        attachNumWatcher(etIntervalSwipe, new NumApply() {
            @Override public void apply(int v) { cfg.intervalSec = Math.max(1, Math.min(600, v)); persistCfg(); syncBothInterval(); }
        });
        attachNumWatcher(etIntervalClick, new NumApply() {
            @Override public void apply(int v) {
                int clamped = Math.max(1, Math.min(600, v));
                cfg.intervalSec = clamped;
                settingText = true;
                try { if (sbIntervalClick != null) sbIntervalClick.setProgress(clamped); } catch (Throwable ignored) {}
                finally { settingText = false; }
                persistCfg();
                syncBothInterval();
            }
        });
        // 点击模式循环间隔 SeekBar 双向联动
        sbIntervalClick.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = Math.max(1, Math.min(600, progress));
                cfg.intervalSec = v;
                if (!settingText) {
                    settingText = true;
                    try { etIntervalClick.setText(String.valueOf(v)); }
                    finally { settingText = false; }
                }
                if (fromUser) { persistCfg(); syncBothInterval(); }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnAddPoint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
                if (cfg.clickPoints.size() >= 8) {
                    toastShort("最多 8 个点击点");
                    return;
                }
                // 不添加默认坐标点 → 直接进入全屏采点，让用户自己戳屏幕定位 A/B/C/D...
                int newIdx = cfg.clickPoints.size();
                startPickPoint(newIdx, letterFromIdx(newIdx));
            }
        });
    }

    private interface NumApply { void apply(int v); }

    private void attachNumWatcher(final EditText et, final NumApply app) {
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (settingText) return;
                int v = parseIntSafe(s.toString(), 0);
                try { app.apply(v); } catch (Throwable ignored) {}
                try { refreshParamsText(); } catch (Throwable ignored) {}
            }
        });
        // 获取焦点时主动弹软键盘
        et.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) showKeyboardFor(et);
            }
        });
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            // 支持浮点输入，比如 "2.5" → 取整
            double d = Double.parseDouble(s.toString());
            return (int) Math.round(d);
        } catch (Exception e) { return def; }
    }

    private void switchMode(Mode m) {
        cfg.mode = m;
        persistCfg();
        setTabUi(m);
        updateBtnStart();
        refreshParamsText();
    }

    private void setDirection(Direction d) {
        cfg.direction = d;
        persistCfg();
        setDirectionUi(d);
        refreshParamsText();
    }

    private void syncBothInterval() {
        // 滑动/点击用同一个 intervalSec，两个输入框 + 点击 SeekBar 保持一致
        int v = Math.max(1, Math.min(600, cfg.intervalSec));
        settingText = true;
        try {
            etIntervalSwipe.setText(String.valueOf(v));
            etIntervalClick.setText(String.valueOf(v));
            if (sbIntervalClick != null) sbIntervalClick.setProgress(v);
        } finally { settingText = false; }
        try { refreshParamsText(); } catch (Throwable ignored) {}
    }

    // ============== 采点流程 ==============
    private void startPickPoint(final int idx, final String letter) {
        if (idx < 0 || idx > cfg.clickPoints.size()) return;
        final boolean isNew = (idx == cfg.clickPoints.size());
        // 新增点：先 append 一个占位，然后全屏去采
        if (isNew) {
            if (cfg.clickPoints.size() >= 8) {
                toastShort("最多 8 个点击点");
                return;
            }
            int delaySec = 10;
            if (!cfg.clickPoints.isEmpty()) {
                delaySec = Math.max(0, Math.min(600, cfg.clickPoints.get(cfg.clickPoints.size() - 1).delaySec));
            }
            cfg.clickPoints.add(new ClickPoint(50, 50, delaySec));
            persistCfg();
            renderPointsList();
        }
        // 临时隐藏悬浮窗 → 打开 CaptureOverlayManager → 采完恢复
        hideTemp();
        CaptureOverlayManager.show(ctx, idx, letter, new CaptureOverlayManager.Callback() {
            @Override public void onSaved(int xPct, int yPct) {
                try { restoreTemp(); } catch (Throwable ignored) {}
                try {
                    if (idx >= 0 && idx < cfg.clickPoints.size()) {
                        cfg.clickPoints.get(idx).xPct = xPct;
                        cfg.clickPoints.get(idx).yPct = yPct;
                        persistCfg();
                        renderPointsList();
                        refreshParamsText();
                    }
                    toastShort(letter + " 点已" + (isNew ? "添加" : "更新") + ": X=" + xPct + "% Y=" + yPct + "%");
                } catch (Throwable t) { toastShort("保存采点失败: " + safeMsg(t)); }            }
        });
    }

    // ============== 持久化 + 通知 ==============
    private void persistCfg() {
        try {
            if (cfg == null) return;
            cfg.save(ctx);
            // 通知无障碍服务：重新加载最新 cfg
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(
                    new Intent(SwipeAccessibilityService.ACTION_SYNC));
            // 通知 MainActivity：刷新 UI
            LocalBroadcastManager.getInstance(ctx).sendBroadcast(
                    new Intent(FloatingService.ACTION_FLOATING_UPDATE));
        } catch (Throwable ignored) {}
    }

    // ============== 方案管理 UI（悬浮窗内精简版） ==============
    private void showSaveProfileDialog() {
        try {
            if (profiles == null) profiles = new ProfileManager(ctx);
            final EditText et = new EditText(ctx);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            et.setTextColor(0xFFE2E8F0);
            et.setHintTextColor(0xFF718096);
            et.setBackgroundResource(R.drawable.bg_input);
            int pad = dp2(10);
            et.setPadding(pad, pad, pad, pad);
            et.setTextSize(14);
            final String active = profiles.getActiveName();
            if (!active.isEmpty()) {
                et.setHint("当前方案：" + active + "（留空则覆盖当前）");
            } else {
                et.setHint("方案名，例如：看视频 / 签到 / 点广告");
            }

            // 包一层带 padding 的 LinearLayout，避免贴边
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.VERTICAL);
            int outer = dp2(20);
            wrap.setPadding(outer, dp2(14), outer, 0);
            wrap.addView(et, new LinearLayout.LayoutParams(-1, -2));

            AlertDialog.Builder ab = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            ab.setTitle("💾 保存当前配置为方案");
            ab.setView(wrap);
            ab.setNegativeButton("取消", null);
            ab.setPositiveButton("保存", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    try {
                        String name = et.getText() == null ? "" : et.getText().toString().trim();
                        // 没填：若有激活方案 → 直接覆盖激活方案
                        if (name.isEmpty()) {
                            if (active.isEmpty()) { toastShort("请输入方案名，或先激活一个方案再覆盖"); return; }
                            name = active;
                        }
                        if (name.length() > 30) name = name.substring(0, 30);
                        if (!active.equals(name) && profiles.exists(name)) {
                            toastShort("方案「" + name + "」已存在，请到📂方案里覆盖");
                            return;
                        }
                        persistCfg();
                        profiles.save(name, cfg);
                        profiles.setActive(name);
                        toastShort("已保存方案：" + name);
                    } catch (Throwable t) { toastShort("保存失败: " + safeMsg(t)); }
                }
            });
            AlertDialog d = ab.create();
            // 对话框也需要 TYPE_SYSTEM_ALERT / TYPE_APPLICATION_OVERLAY 才能从悬浮窗弹
            try {
                WindowManager.LayoutParams lp = d.getWindow() == null ? null : d.getWindow().getAttributes();
                if (lp != null) {
                    lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                }
            } catch (Throwable ignored) {}
            try { d.show(); } catch (Throwable t) { toastShort("弹保存窗失败: " + safeMsg(t)); }
        } catch (Throwable t) { toastShort("保存对话框异常: " + safeMsg(t)); }
    }

    private void showProfileListDialog() {
        try {
            if (profiles == null) profiles = new ProfileManager(ctx);
            final List<ProfileManager.Profile> list = profiles.listAll();
            final String active = profiles.getActiveName();
            if (list.isEmpty()) {
                toastShort("还没有任何方案 → 点 💾 保存第一个");
                return;
            }
            final String[] names = new String[list.size()];
            for (int i = 0; i < list.size(); i++) {
                ProfileManager.Profile p = list.get(i);
                names[i] = p.name + (active.equals(p.name) ? "  ✔当前" : "");
            }
            AlertDialog.Builder ab = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            ab.setTitle("📂 我的方案（点击加载）");
            ab.setAdapter(new ArrayAdapter<>(ctx, android.R.layout.simple_list_item_1, names), new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    try {
                        ProfileManager.Profile p = list.get(which);
                        SwipeConfig loaded = profiles.loadByName(p.name);
                        if (loaded == null) { toastShort("加载失败"); return; }
                        cfg = loaded;
                        if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
                        applyCfgToUi();
                        persistCfg(); // 落地 + 通知主界面和无障碍
                        toastShort("已加载方案：" + p.name);
                    } catch (Throwable t) { toastShort("加载失败: " + safeMsg(t)); }
                }
            });
            ab.setNeutralButton("管理（删除）", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    showProfileDeleteDialog(list, active);
                }
            });
            ab.setPositiveButton("关闭", null);
            AlertDialog d = ab.create();
            try {
                WindowManager.LayoutParams lp = d.getWindow() == null ? null : d.getWindow().getAttributes();
                if (lp != null) {
                    lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                }
            } catch (Throwable ignored) {}
            try { d.show(); } catch (Throwable t) { toastShort("弹方案列表失败: " + safeMsg(t)); }
        } catch (Throwable t) { toastShort("方案列表异常: " + safeMsg(t)); }
    }

    private void showProfileDeleteDialog(final List<ProfileManager.Profile> list, final String active) {
        try {
            final String[] plainNames = new String[list.size()];
            for (int i = 0; i < list.size(); i++) plainNames[i] = list.get(i).name + (active.equals(list.get(i).name) ? "（当前）" : "");
            AlertDialog.Builder ab = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
            ab.setTitle("删除方案");
            ab.setItems(plainNames, new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    final String pickName = list.get(which).name;
                    AlertDialog.Builder confirm = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
                    confirm.setTitle("确认删除「" + pickName + "」？");
                    confirm.setMessage("删除后不可恢复");
                    confirm.setNegativeButton("取消", null);
                    confirm.setPositiveButton("删除", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            try {
                                boolean ok = profiles.delete(pickName);
                                toastShort(ok ? "已删除方案：" + pickName : "删除失败");
                            } catch (Throwable t) { toastShort("删除异常: " + safeMsg(t)); }
                        }
                    });
                    AlertDialog d2 = confirm.create();
                    try {
                        WindowManager.LayoutParams lp = d2.getWindow() == null ? null : d2.getWindow().getAttributes();
                        if (lp != null) {
                            lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                                    : WindowManager.LayoutParams.TYPE_PHONE;
                        }
                    } catch (Throwable ignored) {}
                    try { d2.show(); } catch (Throwable ignored) {}
                }
            });
            AlertDialog d = ab.create();
            try {
                WindowManager.LayoutParams lp = d.getWindow() == null ? null : d.getWindow().getAttributes();
                if (lp != null) {
                    lp.type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE;
                }
            } catch (Throwable ignored) {}
            try { d.show(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void setupClicks() {
        // ========= 方案管理：💾 保存 / 📂 我的方案 =========
        btnSaveProfile.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showSaveProfileDialog(); }
        });
        btnProfiles.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showProfileListDialog(); }
        });

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SwipeAccessibilityService svc = SwipeAccessibilityService.get();
                if (svc == null) {
                    if (tvStatus != null) tvStatus.setText("请先开启无障碍服务");
                    toastShort("请先开启无障碍服务");
                    return;
                }
                if (cfg.mode == Mode.CLICK && (cfg.clickPoints == null || cfg.clickPoints.isEmpty())) {
                    toastShort("请先至少添加一个点击点");
                    return;
                }
                // 触发前先确保一次持久化+同步
                persistCfg();
                String act = btnStart.isSelected()
                        ? SwipeAccessibilityService.ACTION_STOP
                        : SwipeAccessibilityService.ACTION_START;
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(act));
                // 开始/停止后收起面板，恢复小球位置，释放焦点
                collapsePanelAndRestore();
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 点 ✕ 收起透明大面板 → 恢复小球位置 + 释放焦点
                collapsePanelAndRestore();
            }
        });
        handleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 点击圆形小球 → 展开/收起透明大面板
                if (panel == null) return;
                if (panel.getVisibility() == View.VISIBLE) {
                    collapsePanelAndRestore();
                } else {
                    expandPanelAndCenter();
                }
            }
        });
        // （删除系统 OnLongClickListener，改由 setupDrag 里的 Handler+Runnable 检测长按，避免与拖动冲突）
    }

    private void setupDrag() {
        View dragArea = handleView;
        dragArea.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        downAt = System.currentTimeMillis();
                        downRawX = (int) e.getRawX();
                        downRawY = (int) e.getRawY();
                        startX = params.x;
                        startY = params.y;
                        // 1) 先取消任何已排期的长按（防止重复）
                        try { longPressHandler.removeCallbacks(longPressRunnable); } catch (Throwable ignored) {}
                        // 2) 排期新的长按检测（1.5秒后执行关闭）
                        try { longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS); } catch (Throwable ignored) {}
                        return false; // 返回false，让 OnClickListener 还能收到点击事件（展开/收面板）
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) e.getRawX() - downRawX;
                        int dy = (int) e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            dragging = true;
                            // 一旦判断为拖动 → 立刻取消长按（解决向右拖误触关闭的问题）
                            try { longPressHandler.removeCallbacks(longPressRunnable); } catch (Throwable ignored) {}
                        }
                        if (dragging) {
                            params.x = Math.max(0, startX + dx);
                            params.y = Math.max(0, startY + dy);
                            safeUpdate();
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        // 抬手/取消 → 取消长按（避免松手后又触发）
                        try { longPressHandler.removeCallbacks(longPressRunnable); } catch (Throwable ignored) {}
                        return dragging;
                }
                return false;
            }
        });
    }

    private void safeUpdate() {
        try { if (rootView != null) wm.updateViewLayout(rootView, params); } catch (Exception ignored) {}
    }

    // 展开透明面板，同时整个窗口移动到「panel 居中」的位置；保存小球原位置以便收起时恢复
    private void expandPanelAndCenter() {
        if (rootView == null || panel == null) return;
        // 1) 先保存当前小球位置（仅第一次展开时保存，避免连续展开覆盖）
        if (savedHandleX == -1) { savedHandleX = params.x; savedHandleY = params.y; }
        // 2) 显示面板 + 获取焦点
        panel.setVisibility(View.VISIBLE);
        acquireFocus();
        // 3) 测量展开后的 rootView，计算 panel 居中的窗口位置
        rootView.post(new Runnable() {
            @Override public void run() {
                try {
                    android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                    int rootW = rootView.getWidth();
                    int rootH = rootView.getHeight();
                    if (rootW <= 0 || rootH <= 0) {
                        rootView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                        rootW = rootView.getMeasuredWidth();
                        rootH = rootView.getMeasuredHeight();
                    }
                    // handle 在 panel 上方，两者都是 rootView 的子 View
                    // panel 顶部在 rootView 中的 y 偏移 = handle 高度 + panel 的 marginTop(6dp)
                    int handleH = handleView == null ? 0 : handleView.getHeight();
                    if (handleH <= 0) handleH = dp2(56);
                    int panelTopInRoot = handleH + dp2(6); // 6dp marginTop
                    int panelH = panel.getHeight();
                    if (panelH <= 0) panelH = dp2(480);
                    int panelW = panel.getWidth();
                    if (panelW <= 0) panelW = dp2(264);
                    // panel 自身的 left 在 rootView 中的 x 偏移：panel 在 LinearLayout 中居中/撑满
                    // 直接用「让 panel 左上角相对屏幕居中」反推 rootView 左上角坐标
                    // panelLeftOnScreen = (dm.widthPixels  - panelW) / 2
                    // panelTopOnScreen  = (dm.heightPixels - panelH) / 2
                    // rootView.x = panelLeftOnScreen - panelLeftInRoot
                    // rootView.y = panelTopOnScreen  - panelTopInRoot
                    int panelLeftInRoot = Math.max(0, (rootW - panelW) / 2);
                    int targetX = Math.max(0, (dm.widthPixels  - panelW) / 2 - panelLeftInRoot);
                    int targetY = Math.max(0, (dm.heightPixels - panelH) / 2 - panelTopInRoot);
                    params.x = targetX;
                    params.y = targetY;
                    safeUpdate();
                } catch (Throwable ignored) {}
            }
        });
    }

    // 收起透明面板，恢复小球保存的位置
    private void collapsePanelAndRestore() {
        releaseFocus();
        if (panel != null) panel.setVisibility(View.GONE);
        // 恢复小球位置（仅在保存过有效值时）
        if (savedHandleX != -1) {
            params.x = savedHandleX;
            params.y = savedHandleY;
            savedHandleX = -1;
            savedHandleY = -1;
            safeUpdate();
        }
    }

    // 移除 FLAG_NOT_FOCUSABLE，让悬浮窗能获得焦点（EditText 才能弹软键盘）
    private void acquireFocus() {
        try {
            if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                safeUpdate();
            }
        } catch (Throwable ignored) {}
    }

    // 加回 FLAG_NOT_FOCUSABLE，悬浮窗不再抢下面 APP 的焦点；同时隐藏软键盘
    private void releaseFocus() {
        try {
            if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                safeUpdate();
            }
        } catch (Throwable ignored) {}
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && rootView != null) {
                imm.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {}
    }

    // 主动为某个 EditText 弹出软键盘（acquireFocus 之后再调用）
    private void showKeyboardFor(View v) {
        if (v == null) return;
        try {
            acquireFocus();
            v.requestFocus();
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                v.postDelayed(() -> {
                    try { imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT); } catch (Throwable ignored) {}
                }, 80);
            }
        } catch (Throwable ignored) {}
    }

    private void requestStatus() {
        LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STATUS_REQ));
    }

    private void refreshParamsText() {
        if (tvParams == null) return;
        if (cfg == null) return;
        StringBuilder sb = new StringBuilder();
        String arrow = "?";
        try {
            switch (cfg.direction == null ? Direction.DOWN : cfg.direction) {
                case UP:    arrow = "⬆"; break;
                case DOWN:  arrow = "⬇"; break;
                case LEFT:  arrow = "⬅"; break;
                case RIGHT: arrow = "➡"; break;
            }
        } catch (Throwable ignored) {}
        if (cfg.mode == Mode.SWIPE) {
            sb.append("滑动 ").append(arrow)
                    .append(" · D=").append(cfg.distancePct).append("%")
                    .append(" · T=").append(cfg.intervalSec).append("s")
                    .append("\n时长=").append(cfg.durationMs).append("ms");
            if (cfg.midPauseMs > 0) sb.append(" · 中停=").append(cfg.midPauseMs).append("ms").append("@").append(cfg.midPausePosPct).append("%");
            if (cfg.startOffsetPct != 50) sb.append(" · 起点=").append(cfg.startOffsetPct).append("%");
        } else {
            int n = cfg.clickPoints == null ? 0 : cfg.clickPoints.size();
            sb.append("点击 · ").append(n).append("点 · 一轮=").append(cfg.intervalSec).append("s");
            if (n > 0) {
                sb.append("\n");
                for (int i = 0; i < Math.min(n, 3); i++) {
                    ClickPoint p = cfg.clickPoints.get(i);
                    if (i > 0) sb.append(" / ");
                    sb.append(letterFromIdx(i)).append(":").append(p.delaySec).append("s");
                }
                if (n > 3) sb.append("…(+").append(n - 3).append(")");
            }
        }
        tvParams.setText(sb.toString());
    }

    private void updateBtnStart() {
        if (btnStart == null || btnStart.isSelected()) return;
        if (cfg.mode == Mode.SWIPE) btnStart.setText("▶ 开始滑动");
        else                        btnStart.setText("▶ 开始点击");
    }

    private void updateStatus(boolean running, int count, String state) {
        try {
            if (btnStart == null) return;
            btnStart.setSelected(running);
            if (running) {
                btnStart.setText("■ 停止");
                btnStart.setBackgroundResource(R.drawable.bg_btn_stop);
            } else {
                updateBtnStart();
                btnStart.setBackgroundResource(R.drawable.bg_btn_primary);
            }
            if (tvCount != null) tvCount.setText("次数: " + count);
            String txt;
            String s = state == null ? "idle" : state;
            switch (s) {
                case "swiping":          txt = "滑动中…"; break;
                case "pausing_mid":      txt = "中途停顿…"; break;
                case "clicking":         txt = "点击中…"; break;
                case "waiting_point":    txt = "等待下一个点…"; break;
                case "waiting_cycle":    txt = "等待下一轮…"; break;
                case "waiting_next":     txt = (cfg.mode == Mode.CLICK) ? "等待下一轮…" : "等待下一次…"; break;
                default:                 txt = running ? "运行中" : "就绪";
            }
            if (tvStatus != null) tvStatus.setText(txt);
        } catch (Throwable ignored) {}
    }

    private void toastShort(final String msg) {
        if (msg == null) return;
        try { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }
    private static String safeMsg(Throwable t) {
        try {
            String msg = t == null ? "未知错误" : t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t == null ? "未知错误" : t.getClass().getSimpleName();
            if (msg.length() > 100) msg = msg.substring(0, 100) + "…";
            return msg;
        } catch (Throwable ignore) { return "异常"; }
    }
}
