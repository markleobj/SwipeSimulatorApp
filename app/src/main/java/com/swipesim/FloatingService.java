package com.swipesim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class FloatingService extends Service {

    public static final String ACTION_SHOW        = "com.swipesim.SHOW";
    public static final String ACTION_HIDE        = "com.swipesim.HIDE";
    public static final String ACTION_REFRESH     = "com.swipesim.REFRESH";
    public static final String ACTION_HIDE_TEMP   = "com.swipesim.HIDE_TEMP";
    public static final String ACTION_RESTORE_TEMP = "com.swipesim.RESTORE_TEMP";
    public static final String ACTION_FLOATING_UPDATE = "com.swipesim.FLOATING_UPDATE";

    private static final String CHANNEL_ID = "swipe_floating";
    private static final int NOTIF_ID = 1001;

    private FloatingWindowManager floatingMgr;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                if (ACTION_HIDE.equals(intent.getAction())) {
                    if (floatingMgr != null) floatingMgr.remove();
                    try { notifyUpdate(); } catch (Throwable ignored) {}
                    try { stopSelf(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                android.util.Log.e("FloatSvc", "receiver err", t);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try { startForegroundCompat(); } catch (Throwable t) {
            android.util.Log.e("FloatSvc", "startForegroundCompat err", t);
        }
        try {
            floatingMgr = new FloatingWindowManager(this);
            floatingMgr.show();
        } catch (Throwable t) {
            android.util.Log.e("FloatSvc", "show floating err", t);
        }
        try { notifyUpdate(); } catch (Throwable ignored) {}

        try {
            IntentFilter f = new IntentFilter(ACTION_HIDE);
            LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // 再次确保前台通知（Android 12+ 有时 onStartCommand 再次触发需要确保）
            try { startForegroundCompat(); } catch (Throwable ignored) {}

            if (intent == null) return START_STICKY;
            String a = intent.getAction();
            if (ACTION_SHOW.equals(a)) {
                if (floatingMgr == null) {
                    floatingMgr = new FloatingWindowManager(this);
                    floatingMgr.show();
                } else {
                    floatingMgr.show();
                    floatingMgr.refreshFromIntent(intent);
                }
                try { notifyUpdate(); } catch (Throwable ignored) {}
                return START_STICKY;
            }
            if (ACTION_HIDE.equals(a)) {
                if (floatingMgr != null) floatingMgr.remove();
                try { notifyUpdate(); } catch (Throwable ignored) {}
                try { stopSelf(); } catch (Throwable ignored) {}
                return START_NOT_STICKY;
            }
            if (ACTION_REFRESH.equals(a)) {
                if (floatingMgr != null) floatingMgr.refreshFromIntent(intent);
                return START_STICKY;
            }
            if (ACTION_HIDE_TEMP.equals(a)) {
                if (floatingMgr != null) floatingMgr.hideTemp();
                return START_STICKY;
            }
            if (ACTION_RESTORE_TEMP.equals(a)) {
                if (floatingMgr != null) {
                    floatingMgr.restoreTemp();
                    floatingMgr.refreshFromIntent(intent);
                }
                return START_STICKY;
            }
        } catch (Throwable t) {
            android.util.Log.e("FloatSvc", "onStartCommand err", t);
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver); } catch (Throwable ignored) {}
        try { if (floatingMgr != null) floatingMgr.remove(); } catch (Throwable ignored) {}
        try { notifyUpdate(); } catch (Throwable ignored) {}
        try { super.onDestroy(); } catch (Throwable ignored) {}
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void notifyUpdate() {
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(ACTION_FLOATING_UPDATE));
    }

    private void startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "悬浮控制", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                if (nm != null) nm.createNotificationChannel(ch);
            }
            Intent launch = new Intent(this, MainActivity.class);
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 必须显式 FLAG_IMMUTABLE 或 FLAG_MUTABLE
                flags = PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(this, 0, launch, flags);
            Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("滑动模拟器 v1.2.1")
                    .setContentText("悬浮控制运行中，点击打开配置")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setOngoing(true)
                    .setContentIntent(pi)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
            startForeground(NOTIF_ID, n);
        } catch (Throwable t) {
            android.util.Log.e("FloatSvc", "startForegroundCompat throw", t);
        }
    }
}
