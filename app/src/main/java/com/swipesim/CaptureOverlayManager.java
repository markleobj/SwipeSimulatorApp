package com.swipesim;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

public class CaptureOverlayManager {

    public interface Callback {
        void onSaved(int xPct, int yPct);
    }

    @SuppressLint("StaticFieldLeak")
    private static CaptureOverlayManager current;

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private View root;
    private View cross;
    private TextView tvInfo;
    private final int pointIdx;
    private final String pointName;
    private final Callback cb;

    private boolean shown;

    private int screenW, screenH;
    private int crossW, crossH;
    private int crossX, crossY; // 中心坐标 px

    private boolean dragging;
    private int startX, startY, downRawX, downRawY;

    private CaptureOverlayManager(Context ctx, int pointIdx, String pointName, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.pointIdx = pointIdx;
        this.pointName = pointName;
        this.cb = cb;
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
        this.params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        Point size = new Point();
        wm.getDefaultDisplay().getRealSize(size);
        screenW = size.x;
        screenH = size.y;
    }

    public static synchronized boolean isShowing() { return current != null && current.shown; }

    public static synchronized void show(Context ctx, int pointIdx, String pointName, Callback cb) {
        if (current != null) current.removeInternal();
        current = new CaptureOverlayManager(ctx, pointIdx, pointName, cb);
        current.showInternal();
    }

    public static synchronized void hide() {
        if (current != null) { current.removeInternal(); current = null; }
    }

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void showInternal() {
        root = LayoutInflater.from(ctx).inflate(R.layout.capture_overlay, null);
        cross = root.findViewById(R.id.cross);
        tvInfo = root.findViewById(R.id.tv_capture_info);
        Button btnSave = root.findViewById(R.id.btn_save_point);
        Button btnCancel = root.findViewById(R.id.btn_cancel_point);

        cross.post(new Runnable() {
            @Override public void run() {
                crossW = cross.getWidth();
                crossH = cross.getHeight();
                // 初始居中
                setCenter(screenW / 2, screenH / 2);
            }
        });

        cross.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dragging = false;
                        downRawX = (int) e.getRawX();
                        downRawY = (int) e.getRawY();
                        startX = crossX;
                        startY = crossY;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) e.getRawX() - downRawX;
                        int dy = (int) e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > 4 || Math.abs(dy) > 4)) dragging = true;
                        if (dragging) {
                            setCenter(startX + dx, startY + dy);
                            return true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    setCenter((int) e.getRawX(), (int) e.getRawY());
                    return true;
                }
                return false;
            }
        });

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int xp = Math.max(0, Math.min(100, Math.round(crossX * 100f / screenW)));
                int yp = Math.max(0, Math.min(100, Math.round(crossY * 100f / screenH)));
                Callback callback = cb;
                removeInternal();
                current = null;
                if (callback != null) callback.onSaved(xp, yp);
            }
        });
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                removeInternal();
                current = null;
            }
        });

        tvInfo.setText("采点 " + pointName + " — 拖动或点屏幕定位，然后保存");
        wm.addView(root, params);
        shown = true;
    }

    private void setCenter(int cx, int cy) {
        int pad = 6;
        crossX = Math.max(pad, Math.min(screenW - pad, cx));
        crossY = Math.max(pad, Math.min(screenH - pad, cy));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cross.getLayoutParams();
        lp.leftMargin = crossX - crossW / 2;
        lp.topMargin  = crossY - crossH / 2;
        cross.setLayoutParams(lp);
        int xp = Math.max(0, Math.min(100, Math.round(crossX * 100f / screenW)));
        int yp = Math.max(0, Math.min(100, Math.round(crossY * 100f / screenH)));
        tvInfo.setText("采点 " + pointName + "  X=" + xp + "%  Y=" + yp + "%  (拖动或点屏幕移动)");
    }

    private void removeInternal() {
        if (!shown || root == null) return;
        try { wm.removeViewImmediate(root); } catch (Exception ignored) {}
        shown = false;
        root = null;
    }
}
