package com.swipesim;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

public class CaptureOverlayManager {

    public interface Callback {
        void onSaved(int xPct, int yPct);
    }

    // 使用 WeakReference 避免静态引用持有 CaptureOverlayManager 导致内存泄漏
    private static java.lang.ref.WeakReference<CaptureOverlayManager> currentRef;

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams params;
    private View root;
    private View cross;
    private TextView tvInfo;
    private final int pointIdx;
    private final String pointName;
    private final java.lang.ref.WeakReference<Callback> cbRef; // 回调也弱引用，防泄漏

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
        this.cbRef = cb == null ? null : new java.lang.ref.WeakReference<>(cb);
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
        try {
            Point size = new Point();
            wm.getDefaultDisplay().getRealSize(size);
            screenW = Math.max(100, size.x);
            screenH = Math.max(100, size.y);
        } catch (Throwable t) {
            screenW = 1080; screenH = 1920;
        }
    }

    public static synchronized boolean isShowing() {
        CaptureOverlayManager cur = (currentRef == null) ? null : currentRef.get();
        return cur != null && cur.shown;
    }

    private static synchronized CaptureOverlayManager current() {
        return (currentRef == null) ? null : currentRef.get();
    }
    private static synchronized void setCurrent(CaptureOverlayManager m) {
        currentRef = (m == null) ? null : new java.lang.ref.WeakReference<>(m);
    }

    public static synchronized void show(Context ctx, int pointIdx, String pointName, Callback cb) {
        CaptureOverlayManager old = current();
        if (old != null) old.removeInternal();
        CaptureOverlayManager m = new CaptureOverlayManager(ctx, pointIdx, pointName, cb);
        setCurrent(m);
        m.showInternal();
    }

    public static synchronized void hide() {
        CaptureOverlayManager cur = current();
        if (cur != null) { cur.removeInternal(); }
        setCurrent(null);
    }

    private void toastShort(final String msg) {
        Util.toast(ctx, msg);
    }

    @SuppressLint({"InflateParams", "ClickableViewAccessibility"})
    private void showInternal() {
        try {
            root = LayoutInflater.from(ctx).inflate(R.layout.capture_overlay, null);
            cross = root.findViewById(R.id.cross);
            tvInfo = root.findViewById(R.id.tv_capture_info);
            Button btnSave = root.findViewById(R.id.btn_save_point);
            Button btnCancel = root.findViewById(R.id.btn_cancel_point);

            cross.post(new Runnable() {
                @Override public void run() {
                    try {
                        crossW = cross.getWidth();
                        crossH = cross.getHeight();
                        setCenter(screenW / 2, screenH / 2);
                    } catch (Throwable ignored) {}
                }
            });

            cross.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    try {
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
                    } catch (Throwable ignored) {}
                    return false;
                }
            });

            root.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    try {
                        if (e.getAction() == MotionEvent.ACTION_DOWN) {
                            setCenter((int) e.getRawX(), (int) e.getRawY());
                            return true;
                        }
                    } catch (Throwable ignored) {}
                    return false;
                }
            });

            btnSave.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        int xp = Math.max(0, Math.min(100, Math.round(crossX * 100f / screenW)));
                        int yp = Math.max(0, Math.min(100, Math.round(crossY * 100f / screenH)));
                        Callback callback = (cbRef == null) ? null : cbRef.get();
                        removeInternal();
                        setCurrent(null);
                        if (callback != null) {
                            try { callback.onSaved(xp, yp); }
                            catch (Throwable t) { toastShort("回调失败：" + safeMsg(t)); }
                        }
                    } catch (Throwable t) {
                        toastShort("保存采点失败：" + safeMsg(t));
                    }
                }
            });
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    removeInternal();
                    setCurrent(null);
                }
            });

            tvInfo.setText("采点 " + pointName + " — 拖动或点屏幕定位，然后保存");

            try {
                wm.addView(root, params);
                shown = true;
            } catch (Throwable t) {
                toastShort("打开采点界面失败（请确认悬浮窗权限完全打开，包括「后台显示悬浮窗/显示在其他应用上层」等子项）：" + safeMsg(t));
                shown = false;
                root = null;
                setCurrent(null);
            }
        } catch (Throwable t) {
            toastShort("采点初始化失败：" + safeMsg(t));
        }
    }

    private void setCenter(int cx, int cy) {
        try {
            if (cross == null || cross.getLayoutParams() == null) return;
            int pad = 6;
            crossX = Math.max(pad, Math.min(screenW - pad, cx));
            crossY = Math.max(pad, Math.min(screenH - pad, cy));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) cross.getLayoutParams();
            lp.leftMargin = crossX - crossW / 2;
            lp.topMargin  = crossY - crossH / 2;
            cross.setLayoutParams(lp);
            int xp = Math.max(0, Math.min(100, Math.round(crossX * 100f / screenW)));
            int yp = Math.max(0, Math.min(100, Math.round(crossY * 100f / screenH)));
            if (tvInfo != null) {
                tvInfo.setText("采点 " + pointName + "  X=" + xp + "%  Y=" + yp + "%  (拖动或点屏幕移动)");
            }
        } catch (Throwable ignored) {}
    }

    private void removeInternal() {
        if (!shown || root == null) return;
        try { wm.removeViewImmediate(root); }
        catch (Throwable t) {
            try { wm.removeView(root); } catch (Throwable ignored) {}
        }
        shown = false;
        root = null;
    }

    private static String safeMsg(Throwable t) {
        return Util.safeMsg(t);
    }
}
