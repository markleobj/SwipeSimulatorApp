package com.swipesim;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.swipesim.SwipeConfig.Mode;
import com.swipesim.SwipeConfig.ClickPoint;

import java.util.List;

public class FloatingWindowManager {

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private View rootView;
    private boolean shown = false;

    private ImageView handleView;
    private Button btnStart;
    private TextView tvStatus, tvCount, tvParams;
    private View panel, btnClose;

    private boolean dragging = false;
    private int downRawX, downRawY;
    private int startX, startY;
    private long downAt;

    private boolean tempHidden = false;
    private Mode mode = Mode.SWIPE;
    private String dirArrow = "↓";
    private int intervalSec = 30;
    private int clickPointCount = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (SwipeAccessibilityService.ACTION_STATUS_ANS.equals(intent.getAction())) {
                boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
                int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
                String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
                updateStatus(running, count, state);
            }
        }
    };

    public FloatingWindowManager(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
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
            handleView = rootView.findViewById(R.id.handle);
            btnStart   = rootView.findViewById(R.id.btn_start);
            tvStatus   = rootView.findViewById(R.id.tv_status);
            tvCount    = rootView.findViewById(R.id.tv_count);
            tvParams   = rootView.findViewById(R.id.tv_params);
            panel      = rootView.findViewById(R.id.panel);
            btnClose   = rootView.findViewById(R.id.btn_close);

            setupDrag();
            setupClicks();
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
            toastShort("打开悬浮窗失败（请检查悬浮窗权限，含\"后台显示/显示在其他应用上层\"等子项）：" + safeMsg(t));
            rootView = null;
            shown = false;
            return;
        }

        try {
            IntentFilter f = new IntentFilter(SwipeAccessibilityService.ACTION_STATUS_ANS);
            LocalBroadcastManager.getInstance(ctx).registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
        try { requestStatus(); } catch (Throwable ignored) {}
        try { refreshParamsText(); } catch (Throwable ignored) {}
        try { updateStatus(false, 0, "idle"); } catch (Throwable ignored) {}
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
        refreshParamsText();
    }

    public void refreshFromIntent(Intent i) {
        if (i == null) return;
        String m = i.getStringExtra(SwipeConfig.EXTRA_MODE);
        if (m != null) {
            try { mode = Mode.valueOf(m); } catch (Exception ignored) {}
        }
        String d = i.getStringExtra(SwipeConfig.EXTRA_DIR);
        if (d != null) {
            try {
                SwipeConfig.Direction dir = SwipeConfig.Direction.valueOf(d);
                switch (dir) {
                    case UP:    dirArrow = "↑"; break;
                    case DOWN:  dirArrow = "↓"; break;
                    case LEFT:  dirArrow = "←"; break;
                    case RIGHT: dirArrow = "→"; break;
                }
            } catch (Exception ignored) {}
        }
        if (i.hasExtra(SwipeConfig.EXTRA_INTERVAL_S)) {
            intervalSec = i.getIntExtra(SwipeConfig.EXTRA_INTERVAL_S, intervalSec);
        }
        String json = i.getStringExtra(SwipeConfig.EXTRA_CLICK_JSON);
        if (json != null) {
            List<ClickPoint> list = ClickPoint.listFromJson(json);
            clickPointCount = list == null ? 0 : list.size();
        }
        refreshParamsText();
        updateBtnStart();
    }

    private void refreshParamsText() {
        if (tvParams == null) return;
        if (mode == Mode.SWIPE) {
            tvParams.setText("模式：滑动 · " + dirArrow + " · 间隔 " + intervalSec + "s");
        } else {
            tvParams.setText("模式：多点点击 · " + clickPointCount + " 个点\n一轮间隔 " + intervalSec + "s");
        }
    }

    private void updateBtnStart() {
        if (btnStart == null || btnStart.isSelected()) return; // running 中保持「停止」
        if (mode == Mode.SWIPE) {
            btnStart.setText("▶ 开始滑动");
        } else {
            btnStart.setText("▶ 开始点击");
        }
    }

    private void requestStatus() {
        LocalBroadcastManager.getInstance(ctx)
                .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STATUS_REQ));
    }

    private void setupClicks() {
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                SwipeAccessibilityService svc = SwipeAccessibilityService.get();
                if (svc != null) {
                    String act = btnStart.isSelected()
                            ? SwipeAccessibilityService.ACTION_STOP
                            : SwipeAccessibilityService.ACTION_START;
                    LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(act));
                } else {
                    tvStatus.setText("请先开启无障碍服务");
                }
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(FloatingService.ACTION_HIDE));
            }
        });
        handleView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
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
                        if (dragging) return true;
                        return false;
                }
                return false;
            }
        });
    }

    private void safeUpdate() {
        try { if (rootView != null) wm.updateViewLayout(rootView, params); } catch (Exception ignored) {}
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
                case "waiting_next":     txt = (mode == Mode.CLICK) ? "等待下一轮…" : "等待下一次…"; break;
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
