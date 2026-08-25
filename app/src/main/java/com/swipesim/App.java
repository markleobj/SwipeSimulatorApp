package com.swipesim;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class App extends Application {

    public static final String CRASH_PREF = "swipe_crash_info";
    public static final String KEY_LAST_CRASH = "last_crash";
    public static final String KEY_LAST_CRASH_TIME = "last_crash_time";

    private static volatile App instance;
    public static App get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashHandler();
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try { saveCrash(t, e); } catch (Throwable ignored) {}
                // 尽量交给系统，避免死循环
                if (old != null) {
                    try { old.uncaughtException(t, e); return; } catch (Throwable ignored) {}
                }
                // 兜底：稍等一下后退出（给上面保存一点时间）
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try { Process.killProcess(Process.myPid()); System.exit(1); } catch (Throwable ignored) {}
                    }
                }, 300);
            }
        });
    }

    private void saveCrash(Thread t, Throwable e) {
        StringWriter sw = new StringWriter(2048);
        PrintWriter pw = new PrintWriter(sw);
        try {
            pw.append("【崩溃时间】").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
            pw.append("【线程】").append(t == null ? "?" : t.getName()).append(" (id=").append(String.valueOf(t == null ? -1 : t.getId())).append(")\n");
            pw.append("【设备信息】\n");
            try {
                pw.append("  Android=").append(android.os.Build.VERSION.RELEASE).append(" (SDK=").append(String.valueOf(android.os.Build.VERSION.SDK_INT)).append(")\n");
                pw.append("  厂商=").append(android.os.Build.MANUFACTURER).append(" / 型号=").append(android.os.Build.MODEL).append(" / 产品=").append(android.os.Build.PRODUCT).append("\n");
                pw.append("  应用版本=v1.2.1 / versionCode=1\n");
            } catch (Throwable ignore) {}
            pw.append("\n【异常信息】\n");
            Throwable cause = e;
            int depth = 0;
            while (cause != null && depth < 10) {
                if (depth > 0) pw.append("\nCaused by: ");
                pw.append(cause.toString()).append("\n");
                for (StackTraceElement s : cause.getStackTrace()) {
                    pw.append("    at ").append(s.toString()).append("\n");
                }
                cause = cause.getCause();
                depth++;
            }
            pw.flush();
        } catch (Throwable ignore) {} finally {
            try { pw.close(); } catch (Throwable ignored) {}
        }
        String stack = sw.toString();
        try {
            SharedPreferences sp = getSharedPreferences(CRASH_PREF, Context.MODE_PRIVATE);
            sp.edit()
              .putString(KEY_LAST_CRASH, stack)
              .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
              .apply();
            Log.e("SwipeApp", "Crash captured:\n" + stack);
        } catch (Throwable ignored) {}
    }

    public static String peekLastCrash(Context ctx) {
        try {
            return ctx.getSharedPreferences(CRASH_PREF, Context.MODE_PRIVATE)
                    .getString(KEY_LAST_CRASH, null);
        } catch (Throwable ignore) { return null; }
    }
    public static void clearLastCrash(Context ctx) {
        try {
            ctx.getSharedPreferences(CRASH_PREF, Context.MODE_PRIVATE)
                    .edit().remove(KEY_LAST_CRASH).remove(KEY_LAST_CRASH_TIME).apply();
        } catch (Throwable ignored) {}
    }
}
