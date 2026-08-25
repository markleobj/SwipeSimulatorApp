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

import java.util.List;

public class SwipeAccessibilityService extends AccessibilityService {

    public static final String ACTION_START = "com.swipesim.START";
    public static final String ACTION_STOP  = "com.swipesim.STOP";
    public static final String ACTION_SYNC  = "com.swipesim.SYNC";
    public static final String ACTION_STATUS_REQ = "com.swipesim.STATUS_REQ";
    public static final String ACTION_STATUS_ANS = "com.swipesim.STATUS_ANS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_SUB = "sub";   // 辅助信息（当前点击第几号点等）

    private static final String TAG = "SwipeAcc";

    private static SwipeAccessibilityService sInstance;
    public static boolean isServiceEnabled() { return sInstance != null; }
    public static SwipeAccessibilityService get() { return sInstance; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private boolean cancelled = false;
    private int cycleCount = 0;
    private String state = "idle";
    private String subInfo = "";
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

    private void setState(String s, String sub) {
        state = s == null ? "" : s;
        subInfo = sub == null ? "" : sub;
        broadcastStatus();
    }
    private void broadcastStatus() {
        Intent i = new Intent(ACTION_STATUS_ANS);
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_COUNT, cycleCount);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_MODE, cfg == null ? "" : cfg.mode.name());
        i.putExtra(EXTRA_SUB, subInfo);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ---------------- 控制 ----------------

    public void startLoop() {
        if (running) return;
        cfg = SwipeConfig.load(this);
        running = true;
        cancelled = false;
        cycleCount = 0;
        log("开始循环。模式=" + cfg.mode + "，周期间隔=" + cfg.getIntervalMs() + "ms");
        setState(cfg.mode == SwipeConfig.Mode.SWIPE ? "swiping" : "clicking", "");
        handler.post(loopRunnable);
    }

    public void stopLoop() {
        if (!running) return;
        cancelled = true;
        running = false;
        handler.removeCallbacksAndMessages(null);
        setState("idle", "");
        log("已停止。完成轮次=" + cycleCount);
    }

    // ---------------- 核心循环 ----------------

    private final Runnable loopRunnable = new Runnable() {
        @Override public void run() {
            if (cancelled) return;
            final Runnable done = new Runnable() {
                @Override public void run() {
                    if (cancelled) return;
                    cycleCount++;
                    setState("waiting_next", "");
                    log("完成第 " + cycleCount + " 轮，等待 " + cfg.getIntervalMs() + "ms 后下一轮");
                    handler.postDelayed(loopRunnable, cfg.getIntervalMs());
                }
            };
            if (cfg.mode == SwipeConfig.Mode.SWIPE) {
                performOneSwipe(done);
            } else {
                performClickCycle(0, done);
            }
        }
    };

    // ---------- 滑动模式 ----------

    private void performOneSwipe(final Runnable cb) {
        DisplayMetrics dm = getScreenMetrics();
        final int W = dm.widthPixels, H = dm.heightPixels;

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

        final float fsx = sx, fsy = sy, fex = ex, fey = ey;
        final float ratio = cfg.midPausePosPct / 100f;
        final float mx = fsx + (fex - fsx) * ratio;
        final float my = fsy + (fey - fsy) * ratio;

        final int firstDur  = (int)(cfg.durationMs * ratio);
        final int secondDur = Math.max(40, cfg.durationMs - firstDur);
        final int fFirstDur = Math.max(40, firstDur);

        setState("swiping", "");
        dispatchSwipe(fsx, fsy, mx, my, fFirstDur, new Runnable() {
            @Override public void run() {
                if (cancelled) return;
                if (cfg.midPauseMs > 0) {
                    setState("pausing_mid", cfg.midPauseMs + "ms");
                    log("中途暂停 " + cfg.midPauseMs + "ms");
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (cancelled) return;
                            setState("swiping", "");
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
                logWarning("swipe 被取消");
                if (onDone != null) handler.post(onDone);
            }
        }, null);
        if (!ok) logWarning("dispatchSwipe 返回 false");
    }

    // ---------- 多点点击模式 ----------

    private void performClickCycle(final int idx, final Runnable cb) {
        if (cancelled) return;
        final List<SwipeConfig.ClickPoint> points = cfg.clickPoints;
        if (points == null || points.isEmpty()) {
            logWarning("点击点为空，跳过该轮");
            cb.run();
            return;
        }
        if (idx >= points.size()) {
            cb.run();
            return;
        }
        final SwipeConfig.ClickPoint pt = points.get(idx);
        final String name = pointName(idx);
        DisplayMetrics dm = getScreenMetrics();
        final float x = dm.widthPixels * (pt.xPct / 100f);
        final float y = dm.heightPixels * (pt.yPct / 100f);
        setState("clicking", name + "(" + pt.xPct + "%," + pt.yPct + "%)");
        final int afterDelayMs = Math.max(0, pt.delaySec) * 1000;
        log("点击 " + name + " -> (" + pt.xPct + "%, " + pt.yPct + "%)  点后延时=" + pt.delaySec + "s");

        dispatchClick(x, y, new Runnable() {
            @Override public void run() {
                if (cancelled) return;
                if (afterDelayMs > 0 && idx < points.size() - 1) {
                    // 不是最后一个点，等待该点自己的 delay
                    setState("waiting_point", name + " 后等待" + pt.delaySec + "s");
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            performClickCycle(idx + 1, cb);
                        }
                    }, afterDelayMs);
                } else if (afterDelayMs > 0 && idx == points.size() - 1) {
                    // 最后一个点的 delay：等待后再结束本轮
                    setState("waiting_point", name + " 后等待" + pt.delaySec + "s");
                    handler.postDelayed(new Runnable() {
                        @Override public void run() {
                            performClickCycle(idx + 1, cb);
                        }
                    }, afterDelayMs);
                } else {
                    performClickCycle(idx + 1, cb);
                }
            }
        });
    }

    private String pointName(int i) {
        if (i < 0) return "";
        StringBuilder sb = new StringBuilder();
        while (true) {
            sb.insert(0, (char)('A' + (i % 26)));
            i = i / 26 - 1;
            if (i < 0) break;
        }
        return sb.toString() + " 点";
    }

    private void dispatchClick(float x, float y, final Runnable onDone) {
        // Single-stroke tap: stay at (x,y) for ~100ms
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, 100));
        boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) {
                if (onDone != null) handler.post(onDone);
            }
            @Override public void onCancelled(GestureDescription g) {
                logWarning("click 被取消");
                if (onDone != null) handler.post(onDone);
            }
        }, null);
        if (!ok) logWarning("dispatchClick 返回 false");
    }

    // ---------- 工具 ----------

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
