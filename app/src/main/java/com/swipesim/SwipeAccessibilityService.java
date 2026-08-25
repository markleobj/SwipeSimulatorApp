package com.swipesim;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class SwipeAccessibilityService extends AccessibilityService {

    public static final String ACTION_START = "com.swipesim.START";
    public static final String ACTION_STOP  = "com.swipesim.STOP";
    public static final String ACTION_SYNC  = "com.swipesim.SYNC";
    public static final String ACTION_STATUS_REQ = "com.swipesim.STATUS_REQ";
    public static final String ACTION_STATUS_ANS = "com.swipesim.STATUS_ANS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_STATE = "state"; // idle | swiping | pausing_mid | waiting_next

    private static final String TAG = "SwipeAcc";

    private static SwipeAccessibilityService sInstance;
    public static boolean isServiceEnabled() { return sInstance != null; }
    public static SwipeAccessibilityService get() { return sInstance; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean cancelled = false;
    private int swipeCount = 0;
    private String state = "idle";
    private SwipeConfig cfg;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (ACTION_START.equals(a)) startLoop();
            else if (ACTION_STOP.equals(a)) stopLoop();
            else if (ACTION_SYNC.equals(a)) { cfg = SwipeConfig.load(getApplicationContext()); }
            else if (ACTION_STATUS_REQ.equals(a)) broadcastStatus();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        cfg = SwipeConfig.load(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_START);
        f.addAction(ACTION_STOP);
        f.addAction(ACTION_SYNC);
        f.addAction(ACTION_STATUS_REQ);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        cancelled = true;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        broadcastStatus();
    }

    private void setState(String s) { state = s; broadcastStatus(); }
    private void broadcastStatus() {
        Intent i = new Intent(ACTION_STATUS_ANS);
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_COUNT, swipeCount);
        i.putExtra(EXTRA_STATE, state);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ---------------- 控制 ----------------

    public void startLoop() {
        if (running) return;
        cfg = SwipeConfig.load(this);
        running = true;
        cancelled = false;
        swipeCount = 0;
        log("开始循环。间隔=" + cfg.intervalMs + "ms");
        setState("swiping");
        handler.post(loopRunnable);
    }

    public void stopLoop() {
        if (!running) return;
        cancelled = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        setState("idle");
        log("已停止。完成次数=" + swipeCount);
    }

    // ---------------- 核心循环 ----------------

    private final Runnable loopRunnable = new Runnable() {
        @Override public void run() {
            if (cancelled) return;
            performOneSwipe(new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    swipeCount++;
                    setState("waiting_next");
                    log("完成第 " + swipeCount + " 次滑动，等待 " + cfg.intervalMs + "ms 后下一次");
                    handler.postDelayed(loopRunnable, cfg.intervalMs);
                }
            });
        }
    };

    // 一次完整滑动（含中途停顿），结束后 cb.run()
    private void performOneSwipe(final Runnable cb) {
        final int W, H;
        DisplayMetrics dm = getScreenMetrics();
        W = dm.widthPixels; H = dm.heightPixels;

        float dist = 0;
        switch (cfg.direction) {
            case UP:   case DOWN:  dist = H * cfg.distancePct / 100f; break;
            case LEFT: case RIGHT: dist = W * cfg.distancePct / 100f; break;
        }
        float offPct = cfg.startOffsetPct / 100f;

        float sx = 0, sy = 0, ex = 0, ey = 0;
        switch (cfg.direction) {
            case UP:
                sx = ex = W * offPct;
                sy = H * (0.5f + cfg.distancePct / 200f);
                ey = H * (0.5f - cfg.distancePct / 200f);
                break;
            case DOWN:
                sx = ex = W * offPct;
                sy = H * (0.5f - cfg.distancePct / 200f);
                ey = H * (0.5f + cfg.distancePct / 200f);
                break;
            case LEFT:
                sy = ey = H * offPct;
                sx = W * (0.5f + cfg.distancePct / 200f);
                ex = W * (0.5f - cfg.distancePct / 200f);
                break;
            case RIGHT:
                sy = ey = H * offPct;
                sx = W * (0.5f - cfg.distancePct / 200f);
                ex = W * (0.5f + cfg.distancePct / 200f);
                break;
        }

        // Final copies for use inside inner classes (javac effectively-final check)
        final float fsx = sx, fsy = sy, fex = ex, fey = ey;
        final float ratio = cfg.midPauseAtPct / 100f;
        final float mx = fsx + (fex - fsx) * ratio;
        final float my = fsy + (fey - fsy) * ratio;

        final int firstDur  = (int)(cfg.swipeDurationMs * ratio);
        final int secondDur = Math.max(40, cfg.swipeDurationMs - firstDur);

        setState("swiping");
        dispatchSwipe(fsx, fsy, mx, my, Math.max(40, firstDur), new Runnable() {
            @Override public void run() {
                if (cancelled) return;
                if (cfg.midPauseMs > 0) {
                    setState("pausing_mid");
                    log("中途暂停 " + cfg.midPauseMs + "ms");
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (cancelled) return;
                            setState("swiping");
                            dispatchSwipe(mx, my, fex, fey, secondDur, cb);
                        }
                    }, cfg.midPauseMs);
                } else {
                    dispatchSwipe(mx, my, fex, fey, secondDur, cb);
                }
            }
        });
    }

    private void dispatchSwipe(float x1, float y1, float x2, float y2, int dur, final Runnable onDone) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (onDone != null) handler.post(onDone);
            }
            @Override public void onCancelled(GestureDescription g) {
                logWarning("手势被取消");
                if (onDone != null) handler.post(onDone);
            }
        }, null);
        if (!ok) logWarning("dispatchGesture 返回 false（手势发送失败）");
    }

    private DisplayMetrics getScreenMetrics() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        Display d = wm.getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            d.getRealMetrics(dm);
        } else {
            d.getMetrics(dm);
        }
        return dm;
    }

    private void log(String s) { Log.d(TAG, s); }
    private void logWarning(String s) { Log.w(TAG, s); }
}
