package com.swipesim;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

public class SwipeAccessibilityService extends AccessibilityService {

    public static final String ACTION_START      = "com.swipesim.START";
    public static final String ACTION_STOP       = "com.swipesim.STOP";
    public static final String ACTION_SYNC       = "com.swipesim.SYNC";
    public static final String ACTION_STATUS_REQ = "com.swipesim.STATUS_REQ";
    public static final String ACTION_STATUS_ANS = "com.swipesim.STATUS_ANS";
    // 无障碍服务连接状态变化（MainActivity 用它刷新 UI 上的 ✓/✗）
    public static final String ACTION_ACC_STATE_CHANGED = "com.swipesim.ACC_STATE_CHANGED";

    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_COUNT   = "count";
    public static final String EXTRA_STATE   = "state";
    public static final String EXTRA_MODE    = "mode";
    public static final String EXTRA_SUB     = "sub";

    private static final String TAG = "SwipeAcc";

    // 手势超时：手势时长 + 额外缓冲。超时后强制继续，防止回调丢失导致循环卡死
    private static final int GESTURE_TIMEOUT_EXTRA_MS = 1000;
    // 连续失败阈值：超过此数自动停止
    private static final int MAX_CONSECUTIVE_FAILS = 10;

    private static volatile SwipeAccessibilityService sInstance;
    public static SwipeAccessibilityService get() { return sInstance; }

    private SwipeConfig cfg;
    private volatile boolean running;
    private volatile boolean cancelled;
    private int cycleCount;
    private String state = "idle";
    private String subInfo = "";

    // 连续失败计数（手势超时或 dispatch 失败）
    private int consecutiveFails = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 缓存 DisplayMetrics，避免每次手势都做 Binder 调用
    private DisplayMetrics cachedMetrics;
    private long lastMetricsRefreshMs;
    private static final long METRICS_CACHE_MS = 30000; // 30秒缓存

    // 状态广播节流：合并同一帧内的多次 broadcastStatus
    private boolean statusBroadcastPending = false;
    private final Runnable statusBroadcastRunnable = new Runnable() {
        @Override public void run() {
            statusBroadcastPending = false;
            doBroadcastStatus();
        }
    };

    // 复用 Path 对象，减少 GC 压力
    private final Path reusePath = new Path();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (ACTION_START.equals(a))      startLoop();
                else if (ACTION_STOP.equals(a)) stopLoop();
                else if (ACTION_SYNC.equals(a)) {
                    cfg = SwipeConfig.load(SwipeAccessibilityService.this);
                    // 同步配置后重置失败计数
                    consecutiveFails = 0;
                    toastShort("已同步最新配置");
                } else if (ACTION_STATUS_REQ.equals(a)) {
                    broadcastStatus();
                }
            } catch (Throwable t) {
                Log.e(TAG, "receiver err", t);
                toastShort("操作异常：" + t.getMessage());
            }
        }
    };

    // ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        try { cfg = SwipeConfig.load(this); } catch (Throwable t) { cfg = new SwipeConfig(); }
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
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver); } catch (Throwable ignored) {}
        cancelled = true;
        running = false;
        try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        try { sendAccStateChanged(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() {
        // 部分 ROM（尤其 MIUI/ColorOS）停止服务时只走 onInterrupt，不走 onDestroy
        sInstance = null;
        cancelled = true;
        running = false;
        try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        try { sendAccStateChanged(); } catch (Throwable ignored) {}
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        // 连接成功后清空失败计数
        consecutiveFails = 0;
        try { sendAccStateChanged(); } catch (Throwable ignored) {}
        try { broadcastStatus(); } catch (Throwable ignored) {}
    }

    private void sendAccStateChanged() {
        Intent i = new Intent(ACTION_ACC_STATE_CHANGED);
        i.putExtra("connected", (sInstance != null));
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ------------------------------------------------------------

    private void setState(String s, String sub) {
        state = s == null ? "" : s;
        subInfo = sub == null ? "" : sub;
        broadcastStatus();
    }

    /**
     * 节流版状态广播：同一帧内多次调用只发一次广播
     * 减少主线程压力（每轮滑动 3-5 次 setState → 合并为 1 次广播）
     */
    private void broadcastStatus() {
        if (statusBroadcastPending) return;
        statusBroadcastPending = true;
        handler.post(statusBroadcastRunnable);
    }

    private void doBroadcastStatus() {
        Intent i = new Intent(ACTION_STATUS_ANS);
        i.putExtra(EXTRA_RUNNING, running);
        i.putExtra(EXTRA_COUNT, cycleCount);
        i.putExtra(EXTRA_STATE, state);
        i.putExtra(EXTRA_MODE, cfg == null ? "" : cfg.mode.name());
        i.putExtra(EXTRA_SUB, subInfo);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // ------------------------------------------------------------

    private void log(String msg) { Log.d(TAG, msg); }
    private void logWarning(String msg) { Log.w(TAG, msg); }

    // Toast 防抖动：同一消息 2 秒内只弹一次
    private String lastToastMsg = "";
    private long lastToastMs = 0;
    private void toastShort(final String msg) {
        if (msg == null) return;
        long now = System.currentTimeMillis();
        if (msg.equals(lastToastMsg) && now - lastToastMs < 2000) return;
        lastToastMsg = msg;
        lastToastMs = now;
        try {
            handler.post(new Runnable() {
                @Override public void run() {
                    try { Toast.makeText(SwipeAccessibilityService.this, msg, Toast.LENGTH_SHORT).show(); }
                    catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
    }

    /**
     * 获取屏幕尺寸，带 30 秒缓存
     * 避免每轮多次 Binder 调用（原 performOneSwipe + dispatchClick 每轮多次调用）
     */
    private DisplayMetrics getScreenMetrics() {
        long now = System.currentTimeMillis();
        if (cachedMetrics != null && now - lastMetricsRefreshMs < METRICS_CACHE_MS) {
            return cachedMetrics;
        }
        DisplayMetrics out = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                wm.getDefaultDisplay().getRealMetrics(out);
            }
        } catch (Throwable t) {
            Log.e(TAG, "getScreenMetrics err", t);
        }
        // 保证不崩，最低兜底值 1080x1920
        if (out.widthPixels <= 0)  out.widthPixels = 1080;
        if (out.heightPixels <= 0) out.heightPixels = 1920;
        cachedMetrics = out;
        lastMetricsRefreshMs = now;
        return out;
    }

    private String pointName(int idx) {
        if (idx < 26) return String.valueOf((char) ('A' + idx));
        return "P" + (idx + 1);
    }

    // ---------------- 控制 ----------------

    public void startLoop() {
        try {
            if (running) return;
            if (cfg == null) cfg = SwipeConfig.load(this);
            if (cfg == null) cfg = new SwipeConfig();
            if (cfg.clickPoints == null) cfg.clickPoints = new java.util.ArrayList<>();
            if (cfg.mode == null) cfg.mode = SwipeConfig.Mode.SWIPE;
            if (cfg.direction == null) cfg.direction = SwipeConfig.Direction.DOWN;

            if (cfg.mode == SwipeConfig.Mode.CLICK && cfg.clickPoints.isEmpty()) {
                toastShort("请先添加至少一个点击点");
                return;
            }
            running = true;
            cancelled = false;
            cycleCount = 0;
            consecutiveFails = 0;
            log("开始循环。模式=" + cfg.mode + "，周期间隔=" + cfg.getIntervalMs() + "ms");
            setState(cfg.mode == SwipeConfig.Mode.SWIPE ? "swiping" : "clicking", "");
            handler.post(new Runnable() {
                @Override public void run() {
                    try { loopRunnable.run(); }
                    catch (Throwable t) { failSafe(t, "主循环异常"); }
                }
            });
        } catch (Throwable t) {
            running = false;
            Log.e(TAG, "startLoop", t);
            setState("error", "启动失败: " + safeMsg(t));
            toastShort("启动失败：" + safeMsg(t));
        }
    }

    public void stopLoop() {
        try {
            if (!running) return;
            cancelled = true;
            running = false;
            try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
            setState("idle", "");
            log("已停止。完成轮次=" + cycleCount);
            toastShort("已停止，共 " + cycleCount + " 轮");
        } catch (Throwable t) {
            Log.e(TAG, "stopLoop", t);
        }
    }

    private void failSafe(Throwable t, String label) {
        Log.e(TAG, label, t);
        running = false;
        cancelled = true;
        try { handler.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        setState("error", label + ": " + safeMsg(t));
        toastShort(label + "：" + safeMsg(t));
    }

    /**
     * 记录一次失败。连续失败超过阈值则自动停止。
     */
    private void onGestureFail(String reason) {
        consecutiveFails++;
        logWarning("手势失败(" + consecutiveFails + "/" + MAX_CONSECUTIVE_FAILS + "): " + reason);
        if (consecutiveFails >= MAX_CONSECUTIVE_FAILS) {
            logWarning("连续失败达到阈值，自动停止");
            toastShort("连续 " + MAX_CONSECUTIVE_FAILS + " 次失败，已自动停止（请检查无障碍服务是否正常）");
            stopLoop();
        }
    }

    private static String safeMsg(Throwable t) {
        try {
            String msg = t == null ? "未知错误" : t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t == null ? "未知错误" : t.getClass().getSimpleName();
            if (msg.length() > 80) msg = msg.substring(0, 80) + "…";
            return msg;
        } catch (Throwable ignore) { return "异常"; }
    }

    // ---------------- 核心循环 ----------------

    private final Runnable loopRunnable = new Runnable() {
        @Override public void run() {
            if (cancelled) return;
            final Runnable done = new Runnable() {
                @Override public void run() {
                    try {
                        if (cancelled) return;
                        cycleCount++;
                        // 完成一轮后清空失败计数（恢复正常了）
                        consecutiveFails = 0;
                        setState("waiting_next", "");
                        log("完成第 " + cycleCount + " 轮，等待 " + cfg.getIntervalMs() + "ms 后下一轮");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try { loopRunnable.run(); }
                                catch (Throwable t) { failSafe(t, "下一轮调度异常"); }
                            }
                        }, cfg.getIntervalMs());
                    } catch (Throwable t) { failSafe(t, "完成回调异常"); }
                }
            };
            if (cfg.mode == SwipeConfig.Mode.SWIPE) {
                try { performOneSwipe(done); }
                catch (Throwable t) { failSafe(t, "滑动异常"); }
            } else {
                try { performClickCycle(0, done); }
                catch (Throwable t) { failSafe(t, "点击循环异常"); }
            }
        }
    };

    // ---------- 滑动模式 ----------

    private void performOneSwipe(final Runnable cb) {
        if (cfg == null) {
            try { if (cb != null) cb.run(); } catch (Throwable t) { failSafe(t, "cb 异常"); }
            return;
        }
        DisplayMetrics dm = getScreenMetrics();
        final int W = dm.widthPixels, H = dm.heightPixels;

        float dist;
        switch (cfg.direction) {
            case UP:   case DOWN:  dist = H * cfg.distancePct / 100f; break;
            case LEFT: case RIGHT: dist = W * cfg.distancePct / 100f; break;
            default: dist = 0; break;
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
        // 边界兜底（避免 0/负坐标导致手势失败或崩）
        final int pad = 4;
        if (sx < pad) sx = pad; if (sy < pad) sy = pad;
        if (ex < pad) ex = pad; if (ey < pad) ey = pad;
        if (sx > W - pad) sx = W - pad; if (sy > H - pad) sy = H - pad;
        if (ex > W - pad) ex = W - pad; if (ey > H - pad) ey = H - pad;

        final float fsx = sx, fsy = sy, fex = ex, fey = ey;
        final float ratio = Math.max(0, Math.min(1, cfg.midPausePosPct / 100f));
        final float mx = fsx + (fex - fsx) * ratio;
        final float my = fsy + (fey - fsy) * ratio;

        final int firstDur  = (int)(cfg.durationMs * ratio);
        final int secondDur = Math.max(40, cfg.durationMs - firstDur);
        final int fFirstDur = Math.max(40, firstDur);

        setState("swiping", "距离=" + (int)dist + "px 时长=" + cfg.durationMs + "ms");
        dispatchSwipe(fsx, fsy, mx, my, fFirstDur, new Runnable() {
            @Override public void run() {
                try {
                    if (cancelled) return;
                    if (cfg.midPauseMs > 0) {
                        setState("pausing_mid", cfg.midPauseMs + "ms");
                        log("中途暂停 " + cfg.midPauseMs + "ms");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (cancelled) return;
                                    setState("swiping", "");
                                    dispatchSwipe(mx, my, fex, fey, secondDur, cb);
                                } catch (Throwable t) { failSafe(t, "第二段滑动异常"); }
                            }
                        }, cfg.midPauseMs);
                    } else {
                        dispatchSwipe(mx, my, fex, fey, secondDur, cb);
                    }
                } catch (Throwable t) { failSafe(t, "滑动回调异常"); }
            }
        });
    }

    /**
     * 分发滑动手势，带超时保护。
     * 如果 GestureResultCallback 在（时长 + 1s）内未回调，强制继续，防止循环卡死。
     */
    private void dispatchSwipe(float x1, float y1, float x2, float y2, int dur, final Runnable onDone) {
        try {
            // 复用 Path 对象
            reusePath.reset();
            reusePath.moveTo(x1, y1);
            reusePath.lineTo(x2, y2);
            final int safeDur = Math.max(10, dur);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(reusePath, 0, safeDur));

            // 超时保护：用 boolean 标记确保回调只执行一次
            // 注意顺序：timeoutRunnable 先声明（不依赖 fireOnce），fireOnce 后声明（依赖 timeoutRunnable）
            final boolean[] fired = {false};
            // 超时 Runnable：超时后直接执行完成逻辑
            final Runnable swipeTimeoutRunnable = new Runnable() {
                @Override public void run() {
                    if (fired[0]) return;
                    fired[0] = true;
                    logWarning("swipe 超时（" + safeDur + "ms + " + GESTURE_TIMEOUT_EXTRA_MS + "ms 缓冲），强制继续");
                    onGestureFail("swipe 超时");
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable t) { failSafe(t, "swipe timeout done 异常"); }
                    }
                }
            };
            // 正常完成回调：执行完成并取消超时
            final Runnable swipeFireOnce = new Runnable() {
                @Override public void run() {
                    if (fired[0]) return;
                    fired[0] = true;
                    handler.removeCallbacks(swipeTimeoutRunnable);
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable t) { failSafe(t, "swipe done 异常"); }
                    }
                }
            };

            boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (!fired[0]) handler.post(new Runnable() {
                        @Override public void run() { swipeFireOnce.run(); }
                    });
                }
                @Override public void onCancelled(GestureDescription g) {
                    logWarning("swipe 被取消");
                    onGestureFail("swipe 被取消");
                    if (!fired[0]) handler.post(new Runnable() {
                        @Override public void run() { swipeFireOnce.run(); }
                    });
                }
            }, null);

            if (!ok) {
                logWarning("dispatchSwipe 返回 false  （需 Android 7.0+）");
                onGestureFail("dispatchSwipe 返回 false");
                toastShort("手势下发失败（需 Android 7.0+）");
                // 返回 false 时直接执行回调，不走超时
                fired[0] = true;
                if (onDone != null) handler.post(onDone);
                return;
            }
            // 启动超时计时器
            handler.postDelayed(swipeTimeoutRunnable, safeDur + GESTURE_TIMEOUT_EXTRA_MS);
        } catch (Throwable t) {
            Log.e(TAG, "dispatchSwipe", t);
            onGestureFail("dispatchSwipe 异常: " + safeMsg(t));
            toastShort("滑动下发失败：" + safeMsg(t));
            if (onDone != null) handler.post(onDone);
        }
    }

    // ---------- 多点点击模式 ----------

    private void performClickCycle(final int idx, final Runnable cb) {
        if (cancelled) return;
        if (cfg == null) {
            try { if (cb != null) cb.run(); } catch (Throwable t) { failSafe(t, "cb 异常"); }
            return;
        }
        // 快照一份，防止 UI 线程改动 list 导致并发/越界
        List<SwipeConfig.ClickPoint> raw = cfg.clickPoints;
        if (raw == null) raw = new java.util.ArrayList<>();
        final List<SwipeConfig.ClickPoint> points = new java.util.ArrayList<>(raw);
        if (points.isEmpty()) {
            logWarning("点击点为空，跳过该轮");
            try { if (cb != null) cb.run(); } catch (Throwable t) { failSafe(t, "cb 异常"); }
            return;
        }
        if (idx < 0 || idx >= points.size()) {
            // 越界：认为本轮完成
            try { if (cb != null) cb.run(); } catch (Throwable t) { failSafe(t, "cb 异常"); }
            return;
        }
        final SwipeConfig.ClickPoint pt = points.get(idx);
        final String name = pointName(idx);
        final int safeX = Math.max(0, Math.min(100, pt.xPct));
        final int safeY = Math.max(0, Math.min(100, pt.yPct));
        final int safeDelaySec = Math.max(0, Math.min(600, pt.delaySec));
        DisplayMetrics dm = getScreenMetrics();
        final float x = dm.widthPixels * (safeX / 100f);
        final float y = dm.heightPixels * (safeY / 100f);
        setState("clicking", name + "(" + safeX + "%," + safeY + "%)");
        final int afterDelayMs = safeDelaySec * 1000;
        log("点击 " + name + " -> (" + safeX + "%, " + safeY + "%)  点后延时=" + safeDelaySec + "s");

        dispatchClick(x, y, new Runnable() {
            @Override public void run() {
                try {
                    if (cancelled) return;
                    if (afterDelayMs > 0) {
                        setState("waiting_point", name + " 后等待 " + safeDelaySec + "s");
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    if (cancelled) return;
                                    performClickCycle(idx + 1, cb);
                                } catch (Throwable t) { failSafe(t, "下一个点异常"); }
                            }
                        }, afterDelayMs);
                    } else {
                        performClickCycle(idx + 1, cb);
                    }
                } catch (Throwable t) { failSafe(t, name + " 点击后异常"); }
            }
        });
    }

    /**
     * 分发点击手势，带超时保护。
     */
    private void dispatchClick(float x, float y, final Runnable onDone) {
        try {
            // 边界兜底：不能太靠边（0 或等于宽/高会失败）
            DisplayMetrics dm = getScreenMetrics();
            final int pad = 2;
            float cx = Math.max(pad, Math.min(dm.widthPixels - pad, x));
            float cy = Math.max(pad, Math.min(dm.heightPixels - pad, y));

            // 复用 Path 对象
            reusePath.reset();
            reusePath.moveTo(cx, cy);
            final int clickDur = 30;
            GestureDescription.Builder b = new GestureDescription.Builder();
            // 典型点击：按住 8ms，然后抬起，总体长 30ms 模拟真实
            b.addStroke(new GestureDescription.StrokeDescription(reusePath, 0, clickDur));

            // 超时保护（顺序：timeout 先声明，fireOnce 后声明避免前向引用）
            final boolean[] fired = {false};
            final Runnable clickTimeoutRunnable = new Runnable() {
                @Override public void run() {
                    if (fired[0]) return;
                    fired[0] = true;
                    logWarning("click 超时（" + clickDur + "ms + " + GESTURE_TIMEOUT_EXTRA_MS + "ms 缓冲），强制继续");
                    onGestureFail("click 超时");
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable t) { failSafe(t, "click timeout done 异常"); }
                    }
                }
            };
            final Runnable clickFireOnce = new Runnable() {
                @Override public void run() {
                    if (fired[0]) return;
                    fired[0] = true;
                    handler.removeCallbacks(clickTimeoutRunnable);
                    if (onDone != null) {
                        try { onDone.run(); } catch (Throwable t) { failSafe(t, "click done 异常"); }
                    }
                }
            };

            boolean ok = dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (!fired[0]) handler.post(new Runnable() {
                        @Override public void run() { clickFireOnce.run(); }
                    });
                }
                @Override public void onCancelled(GestureDescription g) {
                    logWarning("click 被取消");
                    onGestureFail("click 被取消");
                    if (!fired[0]) handler.post(new Runnable() {
                        @Override public void run() { clickFireOnce.run(); }
                    });
                }
            }, null);

            if (!ok) {
                logWarning("dispatchClick 返回 false  （需 Android 7.0+）");
                onGestureFail("dispatchClick 返回 false");
                toastShort("点击下发失败（需 Android 7.0+）");
                fired[0] = true;
                if (onDone != null) handler.post(onDone);
                return;
            }
            handler.postDelayed(clickTimeoutRunnable, clickDur + GESTURE_TIMEOUT_EXTRA_MS);
        } catch (Throwable t) {
            Log.e(TAG, "dispatchClick", t);
            onGestureFail("dispatchClick 异常: " + safeMsg(t));
            toastShort("点击下发失败：" + safeMsg(t));
            if (onDone != null) handler.post(onDone);
        }
    }
}
