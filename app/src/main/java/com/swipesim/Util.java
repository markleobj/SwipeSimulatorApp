package com.swipesim;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.Toast;

/**
 * 全局公共工具：消除 safeMsg / toastShort / dp / parseIntSafe 在多个类中重复定义。
 */
public final class Util {

    private Util() {}

    // --- 主线程 Handler（懒加载） ---
    private static class MainHolder {
        static final Handler H = new Handler(Looper.getMainLooper());
    }
    public static Handler mainHandler() { return MainHolder.H; }

    // --- Toast ---
    private static Toast sToast; // 复用同一个 Toast，避免快速连续弹 Toast 排队堆积

    public static void toast(Context ctx, String msg) {
        if (msg == null) return;
        final Context appCtx = ctx.getApplicationContext();
        MainHolder.H.post(new Runnable() {
            @Override public void run() {
                try {
                    if (sToast != null) sToast.cancel();
                    sToast = Toast.makeText(appCtx, msg, Toast.LENGTH_SHORT);
                    sToast.show();
                } catch (Throwable ignored) {}
            }
        });
    }

    // --- 异常消息提取 ---
    public static String safeMsg(Throwable t) {
        try {
            String msg = t == null ? "未知错误" : t.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = t == null ? "未知错误" : t.getClass().getSimpleName();
            }
            if (msg.length() > 100) msg = msg.substring(0, 100) + "…";
            return msg;
        } catch (Throwable ignore) { return "异常"; }
    }

    // --- dp → px ---
    public static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    // --- 安全解析整数（支持 "2.5" → 3） ---
    public static int parseIntSafe(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            double d = Double.parseDouble(s);
            return (int) Math.round(d);
        } catch (Exception e) { return def; }
    }

    // --- 版本信息缓存 ---
    private static volatile String sVersionName = null;
    private static volatile int sVersionCode = 0;

    public static String versionName(Context ctx) {
        if (sVersionName != null) return sVersionName;
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            sVersionName = pi.versionName;
            sVersionCode = pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            sVersionName = "unknown";
            sVersionCode = 0;
        }
        return sVersionName;
    }

    public static int versionCode(Context ctx) {
        if (sVersionCode != 0) return sVersionCode;
        versionName(ctx); // 触发初始化
        return sVersionCode;
    }

    // --- 颜色常量（消除硬编码 ARGB 散落各处） ---
    public static final int COLOR_ACCENT   = 0xFF38BDF8;
    public static final int COLOR_TEXT     = 0xFFE2E8F0;
    public static final int COLOR_TEXT_DIM = 0xFF718096;
    public static final int COLOR_DANGER   = 0xFFF87171;
    public static final int COLOR_WHITE    = 0xFFFFFFFF;

    // --- 点位颜色循环表 ---
    public static final int[] POINT_COLORS = {
            0xFF38BDF8, 0xFF22C55E, 0xFFF59E0B, 0xFFEF4444, 0xFFA855F7,
            0xFFEC4899, 0xFF14B8A6, 0xFFFB923C, 0xFF6366F1, 0xFF84CC16
    };
}
