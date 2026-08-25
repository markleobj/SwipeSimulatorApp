package com.swipesim;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

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
    private ViewGroup rowDistance, rowIntervalSwipe, rowDuration, rowMidPause;
    private EditText etDistance, etIntervalSwipe, etDuration, etMidPause;
    // 点击模式
    private EditText etIntervalClick;
    private TextView btnAddPoint;
    private LinearLayout pointsContainer;

    // 缓存 cfg
    private SwipeConfig cfg;
    private boolean settingText = false; // 防止 TextWatcher 死循环

    private boolean dragging = false;
    private int downRawX, downRawY;
    private int startX, startY;
    private long downAt;

    private boolean tempHidden = false;

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
        params.x = 20;
        params.y = 400;
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
            etDistance       = rowDistance.findViewById(R.id.row_value);
            etIntervalSwipe  = rowIntervalSwipe.findViewById(R.id.row_value);
            etDuration       = rowDuration.findViewById(R.id.row_value);
            etMidPause       = rowMidPause.findViewById(R.id.row_value);
            setRowLabel(rowDistance, "滑动距离", "%");
            setRowLabel(rowIntervalSwipe, "每轮间隔", "秒");
            setRowLabel(rowDuration, "滑动时长", "ms");
            setRowLabel(rowMidPause, "中点停顿", "ms");

            // 点击模式
            etIntervalClick  = rootView.findViewById(R.id.et_interval_click);
            btnAddPoint      = rootView.findViewById(R.id.btn_add_point);
            pointsContainer  = rootView.findViewById(R.id.points_container);

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

            // Click rows
            etIntervalClick.setText(String.valueOf(Math.max(1, Math.min(600, cfg.intervalSec))));
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
        attachNumWatcher(etIntervalSwipe, new NumApply() {
            @Override public void apply(int v) { cfg.intervalSec = Math.max(1, Math.min(600, v)); persistCfg(); syncBothInterval(); }
        });
        attachNumWatcher(etIntervalClick, new NumApply() {
            @Override public void apply(int v) { cfg.intervalSec = Math.max(1, Math.min(600, v)); persistCfg(); syncBothInterval(); }
        });

        btnAddPoint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cfg.clickPoints == null) cfg.clickPoints = new ArrayList<>();
                int baseX = 50, baseY = 50, delay = 10;
                if (!cfg.clickPoints.isEmpty()) {
                    ClickPoint last = cfg.clickPoints.get(cfg.clickPoints.size() - 1);
                    baseX = (last.xPct + 20) % 100;
                    if (baseX < 5) baseX = 50;
                    baseY = (last.yPct + 15) % 100;
                    if (baseY < 5) baseY = 50;
                    delay = Math.max(0, Math.min(600, last.delaySec));
                }
                cfg.clickPoints.add(new ClickPoint(baseX, baseY, delay));
                persistCfg();
                renderPointsList();
                refreshParamsText();
                toastShort("已添加点 " + letterFromIdx(cfg.clickPoints.size() - 1));
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
        // 滑动/点击用同一个 intervalSec，两个输入框保持一致
        int v = Math.max(1, Math.min(600, cfg.intervalSec));
        settingText = true;
        try {
            etIntervalSwipe.setText(String.valueOf(v));
            etIntervalClick.setText(String.valueOf(v));
        } finally { settingText = false; }
    }

    // ============== 采点流程 ==============
    private void startPickPoint(final int idx, final String letter) {
        if (idx < 0) return;
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
                    toastShort(letter + " 点已更新: X=" + xPct + "% Y=" + yPct + "%");
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

    private void setupClicks() {
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
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 点 ✕ 只收起透明大面板，回到只显示圆形小球（不关闭悬浮窗服务）
                if (panel != null) panel.setVisibility(View.GONE);
            }
        });
        handleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 点击圆形小球 → 展开/收起透明大面板
                if (panel != null) {
                    panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                }
            }
        });
        handleView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                // 长按圆形小球 2 秒 → 才真正关闭整个悬浮窗服务
                try {
                    toastShort("已关闭悬浮窗");
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(FloatingService.ACTION_HIDE));
                } catch (Throwable ignored) {}
                return true;
            }
        });
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
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) e.getRawX() - downRawX;
                        int dy = (int) e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) {
                            dragging = true;
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
                        return dragging;
                }
                return false;
            }
        });
    }

    private void safeUpdate() {
        try { if (rootView != null) wm.updateViewLayout(rootView, params); } catch (Exception ignored) {}
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
            if (cfg.midPauseMs > 0) sb.append(" · 中停=").append(cfg.midPauseMs).append("ms");
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
