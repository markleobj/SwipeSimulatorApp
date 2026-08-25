package com.swipesim;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_OVERLAY = 1001;

    private TextView tvAccStatus, tvOverlayStatus;
    private TextView tvDist, tvDur, tvMidPause, tvMidPos, tvOffset, tvInterval;
    private SeekBar sbDist, sbDur, sbMidPause, sbMidPos, sbOffset, sbInterval;
    private LinearLayout dirGroup;
    private SwipeConfig.Direction curDir;

    private Button btnShowFloating, btnHideFloating, btnQuickStart, btnStop;
    private TextView tvRunStatus;

    private SwipeConfig cfg;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
            int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
            String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
            updateRunStatus(running, count, state);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cfg = SwipeConfig.load(this);
        curDir = cfg.direction;

        bindViews();
        setupDirGroup();
        setupSeekBars();
        bindValues();
        setupButtons();

        IntentFilter f = new IntentFilter(SwipeAccessibilityService.ACTION_STATUS_ANS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, f);
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissions();
        requestStatus();
    }

    @Override protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        super.onDestroy();
    }

    private void bindViews() {
        tvAccStatus = findViewById(R.id.tv_acc_status);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvDist = findViewById(R.id.tv_dist);
        tvDur = findViewById(R.id.tv_dur);
        tvMidPause = findViewById(R.id.tv_mid_pause);
        tvMidPos = findViewById(R.id.tv_mid_pos);
        tvOffset = findViewById(R.id.tv_offset);
        tvInterval = findViewById(R.id.tv_interval);
        sbDist = findViewById(R.id.sb_dist);
        sbDur = findViewById(R.id.sb_dur);
        sbMidPause = findViewById(R.id.sb_mid_pause);
        sbMidPos = findViewById(R.id.sb_mid_pos);
        sbOffset = findViewById(R.id.sb_offset);
        sbInterval = findViewById(R.id.sb_interval);
        dirGroup = findViewById(R.id.dir_group);
        btnShowFloating = findViewById(R.id.btn_show_floating);
        btnHideFloating = findViewById(R.id.btn_hide_floating);
        btnQuickStart = findViewById(R.id.btn_quick_start);
        btnStop = findViewById(R.id.btn_stop);
        tvRunStatus = findViewById(R.id.tv_run_status);
    }

    private void setupDirGroup() {
        int[] ids = { R.id.dir_up, R.id.dir_down, R.id.dir_left, R.id.dir_right };
        SwipeConfig.Direction[] dirs = {
                SwipeConfig.Direction.UP, SwipeConfig.Direction.DOWN,
                SwipeConfig.Direction.LEFT, SwipeConfig.Direction.RIGHT };
        for (int i = 0; i < ids.length; i++) {
            final int idx = i;
            View v = findViewById(ids[i]);
            v.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    curDir = dirs[idx];
                    refreshDirUI();
                    saveCfg();
                }
            });
        }
        refreshDirUI();
    }

    private void refreshDirUI() {
        int[] ids = { R.id.dir_up, R.id.dir_down, R.id.dir_left, R.id.dir_right };
        SwipeConfig.Direction[] dirs = {
                SwipeConfig.Direction.UP, SwipeConfig.Direction.DOWN,
                SwipeConfig.Direction.LEFT, SwipeConfig.Direction.RIGHT };
        for (int i = 0; i < ids.length; i++) {
            findViewById(ids[i]).setSelected(dirs[i] == curDir);
        }
    }

    private void setupSeekBars() {
        sbDist.setOnSeekBarChangeListener(new Cb(tvDist, "%") {
            @Override void onSet(int v) { cfg.distancePct = v; saveCfg(); }
        });
        sbDur.setOnSeekBarChangeListener(new Cb(tvDur, "ms") {
            @Override void onSet(int v) { cfg.swipeDurationMs = v; saveCfg(); }
        });
        sbMidPause.setOnSeekBarChangeListener(new Cb(tvMidPause, "ms") {
            @Override void onSet(int v) { cfg.midPauseMs = v; saveCfg(); }
        });
        sbMidPos.setOnSeekBarChangeListener(new Cb(tvMidPos, "%") {
            @Override void onSet(int v) { cfg.midPauseAtPct = v; saveCfg(); }
        });
        sbOffset.setOnSeekBarChangeListener(new Cb(tvOffset, "%") {
            @Override void onSet(int v) { cfg.startOffsetPct = v; saveCfg(); }
        });
        sbInterval.setOnSeekBarChangeListener(new Cb(tvInterval, "s") {
            @Override void onSet(int v) { cfg.intervalMs = v * 1000; saveCfg(); }
        });
    }

    private void bindValues() {
        sbDist.setProgress(cfg.distancePct);
        sbDur.setProgress(cfg.swipeDurationMs);
        sbMidPause.setProgress(cfg.midPauseMs);
        sbMidPos.setProgress(cfg.midPauseAtPct);
        sbOffset.setProgress(cfg.startOffsetPct);
        int sec = Math.max(1, cfg.intervalMs / 1000);
        sbInterval.setProgress(Math.min(sbInterval.getMax(), sec));
    }

    private abstract static class Cb implements SeekBar.OnSeekBarChangeListener {
        private final TextView tv;
        private final String suf;
        Cb(TextView tv, String suffix) { this.tv = tv; this.suf = suffix; }
        @Override public void onProgressChanged(SeekBar sb, int v, boolean user) {
            tv.setText(v + suf);
            if (user) onSet(v);
        }
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
        abstract void onSet(int v);
    }

    private void saveCfg() {
        cfg.direction = curDir;
        cfg.save(this);
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_SYNC));
    }

    // ---------------- 权限 ----------------

    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (enabled != 1) return false;
            String services = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(services)) return false;
            String[] arr = services.split(":");
            String myId = getPackageName() + "/" + SwipeAccessibilityService.class.getCanonicalName();
            for (String s : arr) if (s.equalsIgnoreCase(myId)) return true;
            return false;
        } catch (Exception e) { return false; }
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    private void refreshPermissions() {
        boolean accOk = isAccessibilityEnabled();
        boolean ovOk = canDrawOverlays();
        tvAccStatus.setText(accOk ? "✅ 已开启" : "❌ 未开启，点击去设置");
        tvAccStatus.setTextColor(getColor(accOk ? R.color.ok : R.color.bad));
        tvAccStatus.setOnClickListener(accOk ? null : new View.OnClickListener() {
            @Override public void onClick(View v) { askAccessibility(); }
        });
        tvOverlayStatus.setText(ovOk ? "✅ 已开启" : "❌ 未开启，点击去设置");
        tvOverlayStatus.setTextColor(getColor(ovOk ? R.color.ok : R.color.bad));
        tvOverlayStatus.setOnClickListener(ovOk ? null : new View.OnClickListener() {
            @Override public void onClick(View v) { askOverlay(); }
        });
        btnShowFloating.setEnabled(ovOk && accOk);
        btnQuickStart.setEnabled(accOk);
    }

    private void askAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("开启无障碍服务")
                .setMessage("需要开启无障碍服务才能在其它APP界面执行滑动手势。\n\n系统设置 → 无障碍 → 找到「滑动模拟器」并开启。")
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void askOverlay() {
        new AlertDialog.Builder(this)
                .setTitle("开启悬浮窗权限")
                .setMessage("需要悬浮窗权限才能在其它APP上方显示开始/停止按钮。")
                .setPositiveButton("去开启", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(i, REQ_OVERLAY);
                    }
                }).setNegativeButton("取消", null).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) refreshPermissions();
    }

    // ---------------- 按钮 ----------------

    private void setupButtons() {
        btnShowFloating.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!canDrawOverlays() || !isAccessibilityEnabled()) {
                    refreshPermissions();
                    Toast.makeText(MainActivity.this, "请先开启所需权限", Toast.LENGTH_SHORT).show();
                    return;
                }
                startService(new Intent(MainActivity.this, FloatingService.class)
                        .setAction(FloatingService.ACTION_SHOW));
                Toast.makeText(MainActivity.this, "悬浮窗已显示，切换到视频APP即可操作", Toast.LENGTH_LONG).show();
                // 延时回到桌面，方便用户立刻去其他APP
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() { moveTaskToBack(false); }
                }, 800);
            }
        });
        btnHideFloating.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startService(new Intent(MainActivity.this, FloatingService.class)
                        .setAction(FloatingService.ACTION_HIDE));
            }
        });
        btnQuickStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocalBroadcastManager.getInstance(MainActivity.this)
                        .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_START));
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocalBroadcastManager.getInstance(MainActivity.this)
                        .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STOP));
            }
        });
    }

    private void requestStatus() {
        LocalBroadcastManager.getInstance(this)
                .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STATUS_REQ));
    }

    private void updateRunStatus(boolean running, int count, String state) {
        String txt;
        switch (state == null ? "idle" : state) {
            case "swiping":      txt = "滑动中"; break;
            case "pausing_mid":  txt = "中途停顿中"; break;
            case "waiting_next": txt = "等待下一次滑动…"; break;
            default:             txt = running ? "运行中" : "空闲";
        }
        tvRunStatus.setText(String.format("状态：%s ｜ 已滑动：%d 次", txt, count));
    }
}
