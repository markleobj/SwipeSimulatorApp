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

public class FloatingWindowManager {

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private View rootView;
    private boolean shown = false;

    private ImageView handleView;
    private Button btnStart;
    private TextView tvStatus;
    private TextView tvCount;
    private View panel;
    private View btnClose;

    private boolean dragging = false;
    private int downRawX, downRawY;
    private int startX, startY;
    private long downAt;

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
        rootView = LayoutInflater.from(ctx).inflate(R.layout.floating_widget, null);
        handleView = rootView.findViewById(R.id.handle);
        btnStart  = rootView.findViewById(R.id.btn_start);
        tvStatus  = rootView.findViewById(R.id.tv_status);
        tvCount   = rootView.findViewById(R.id.tv_count);
        panel     = rootView.findViewById(R.id.panel);
        btnClose  = rootView.findViewById(R.id.btn_close);

        setupDrag();
        setupClicks();

        wm.addView(rootView, params);
        shown = true;

        IntentFilter f = new IntentFilter(SwipeAccessibilityService.ACTION_STATUS_ANS);
        LocalBroadcastManager.getInstance(ctx).registerReceiver(receiver, f);
        requestStatus();
        updateStatus(false, 0, "idle");
    }

    public void remove() {
        if (!shown || rootView == null) return;
        try {
            LocalBroadcastManager.getInstance(ctx).unregisterReceiver(receiver);
            wm.removeView(rootView);
        } catch (Exception ignored) {}
        shown = false;
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
                    // 用广播更稳（service绑定场景兼容）
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
                        return false; // 不消费，让 click 能触发
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
        try { wm.updateViewLayout(rootView, params); } catch (Exception ignored) {}
    }

    private void updateStatus(boolean running, int count, String state) {
        if (btnStart == null) return;
        btnStart.setSelected(running);
        btnStart.setText(running ? "■ 停止" : "▶ 开始滑动");
        btnStart.setBackgroundResource(running ? R.drawable.bg_btn_stop : R.drawable.bg_btn_primary);
        tvCount.setText("次数: " + count);
        String txt;
        switch (state == null ? "idle" : state) {
            case "swiping":      txt = "滑动中…"; break;
            case "pausing_mid":  txt = "中途停顿…"; break;
            case "waiting_next": txt = "等待下一次…"; break;
            default:             txt = running ? "运行中" : "就绪";
        }
        tvStatus.setText(txt);
    }
}
