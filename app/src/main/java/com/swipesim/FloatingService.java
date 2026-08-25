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

    public static final String ACTION_SHOW = "com.swipesim.SHOW";
    public static final String ACTION_HIDE = "com.swipesim.HIDE";

    private static final String CHANNEL_ID = "swipe_floating";
    private static final int NOTIF_ID = 1001;

    private FloatingWindowManager floatingMgr;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_HIDE.equals(intent.getAction())) {
                if (floatingMgr != null) floatingMgr.remove();
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundCompat();
        floatingMgr = new FloatingWindowManager(this);
        floatingMgr.show();

        IntentFilter f = new IntentFilter(ACTION_HIDE);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            if (floatingMgr != null) floatingMgr.remove();
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        if (floatingMgr != null) floatingMgr.remove();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "悬浮控制", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("滑动模拟器")
                .setContentText("悬浮控制运行中，点击打开配置")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
        startForeground(NOTIF_ID, n);
    }
}
