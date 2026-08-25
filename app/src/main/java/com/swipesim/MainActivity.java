package com.swipesim;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.swipesim.SwipeConfig.Mode;
import com.swipesim.SwipeConfig.ClickPoint;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // ===== 滑动相关 =====
    private TextView dirUp, dirDown, dirLeft, dirRight;
    private TextView tvDist, tvDur, tvMidPause, tvMidPos, tvOffset, tvInterval;
    private SeekBar sbDist, sbDur, sbMidPause, sbMidPos, sbOffset, sbInterval;

    // ===== 点击模式 =====
    private View swipeParams, clickParams;
    private TextView tabSwipe, tabClick;
    private LinearLayout pointContainer;
    private TextView btnAddPoint;
    private final List<PointRowHolder> pointHolders = new ArrayList<>();

    // ===== 公共 =====
    private TextView tvAccStatus, tvOverlayStatus;
    private TextView tvRunStatus, tvRunSub;
    private Button btnShowFloat, btnHideFloat, btnStart, btnStop;

    private SwipeConfig cfg;

    private static final int REQ_OVERLAY = 1001;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (SwipeAccessibilityService.ACTION_STATUS_ANS.equals(a)) {
                boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
                int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
                String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
                String sub   = intent.getStringExtra(SwipeAccessibilityService.EXTRA_SUB);
                tvRunStatus.setText("状态：" + stateText(running, state) + " ｜ 已完成：" + count + " 轮");
                tvRunStatus.setTextColor(running ? 0xFF38BDF8 : 0xFFA1A1AA);
                tvRunSub.setText(sub == null || sub.isEmpty() ? "" : "当前：" + sub);
            } else if (FloatingService.ACTION_FLOATING_UPDATE.equals(a)) {
                updateOverlayStatus();
            }
        }
    };

    private static String stateText(boolean running, String state) {
        String s = state == null ? "idle" : state;
        if (!running) return "空闲";
        switch (s) {
            case "swiping":       return "滑动中";
            case "pausing_mid":   return "中途停顿";
            case "clicking":      return "点击中";
            case "waiting_point": return "等待下一个点";
            case "waiting_next":
            case "waiting_cycle": return "等待下一轮";
            default:              return "运行中";
        }
    }

    private static class PointRowHolder {
        final View root;
        final int index;
        final TextView name, xVal, yVal, delayVal, btnCapture, btnRemove;
        final SeekBar xSb, ySb, delaySb;

        PointRowHolder(View root, int index) {
            this.root = root;
            this.index = index;
            name = root.findViewById(R.id.pt_name);
            xVal = root.findViewById(R.id.pt_x_val);
            yVal = root.findViewById(R.id.pt_y_val);
            delayVal = root.findViewById(R.id.pt_delay_val);
            btnCapture = root.findViewById(R.id.btn_capture_point);
            btnRemove = root.findViewById(R.id.btn_remove_point);
            xSb = root.findViewById(R.id.pt_x_sb);
            ySb = root.findViewById(R.id.pt_y_sb);
            delaySb = root.findViewById(R.id.pt_delay_sb);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cfg = SwipeConfig.load(this);

        swipeParams = findViewById(R.id.swipe_params);
        clickParams = findViewById(R.id.click_params);
        tabSwipe = findViewById(R.id.tab_swipe);
        tabClick = findViewById(R.id.tab_click);
        pointContainer = findViewById(R.id.point_container);
        btnAddPoint = findViewById(R.id.btn_add_point);

        dirUp    = findViewById(R.id.dir_up);
        dirDown  = findViewById(R.id.dir_down);
        dirLeft  = findViewById(R.id.dir_left);
        dirRight = findViewById(R.id.dir_right);

        tvDist = findViewById(R.id.tv_dist);
        sbDist = findViewById(R.id.sb_dist);
        tvDur = findViewById(R.id.tv_dur);
        sbDur = findViewById(R.id.sb_dur);
        tvMidPause = findViewById(R.id.tv_mid_pause);
        sbMidPause = findViewById(R.id.sb_mid_pause);
        tvMidPos = findViewById(R.id.tv_mid_pos);
        sbMidPos = findViewById(R.id.sb_mid_pos);
        tvOffset = findViewById(R.id.tv_offset);
        sbOffset = findViewById(R.id.sb_offset);
        tvInterval = findViewById(R.id.tv_interval);
        sbInterval = findViewById(R.id.sb_interval);

        tvAccStatus = findViewById(R.id.tv_acc_status);
        tvOverlayStatus = findViewById(R.id.tv_overlay_status);
        tvRunStatus = findViewById(R.id.tv_run_status);
        tvRunSub = findViewById(R.id.tv_run_sub);
        btnShowFloat = findViewById(R.id.btn_show_floating);
        btnHideFloat = findViewById(R.id.btn_hide_floating);
        btnStart = findViewById(R.id.btn_quick_start);
        btnStop = findViewById(R.id.btn_stop);

        View.OnClickListener dirClk = new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (v == dirUp)      cfg.direction = SwipeConfig.Direction.UP;
                if (v == dirDown)    cfg.direction = SwipeConfig.Direction.DOWN;
                if (v == dirLeft)    cfg.direction = SwipeConfig.Direction.LEFT;
                if (v == dirRight)   cfg.direction = SwipeConfig.Direction.RIGHT;
                refreshDirs();
                cfg.save(MainActivity.this);
            }
        };
        dirUp.setOnClickListener(dirClk);
        dirDown.setOnClickListener(dirClk);
        dirLeft.setOnClickListener(dirClk);
        dirRight.setOnClickListener(dirClk);
        refreshDirs();

        // ===== SeekBars =====
        sbDist.setProgress(Math.max(10, Math.min(90, cfg.distancePct)));
        tvDist.setText(cfg.distancePct + "%");
        sbDist.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int v = Math.max(10, p);
                if (user) sb.setProgress(v);
                cfg.distancePct = v;
                tvDist.setText(v + "%");
                if (user) cfg.save(MainActivity.this);
            }
        });

        sbDur.setProgress(cfg.durationMs);
        tvDur.setText(cfg.durationMs + "ms");
        sbDur.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.durationMs = Math.max(50, p);
                tvDur.setText(cfg.durationMs + "ms");
                if (user) cfg.save(MainActivity.this);
            }
        });

        sbMidPause.setProgress(cfg.midPauseMs);
        tvMidPause.setText(cfg.midPauseMs + "ms");
        sbMidPause.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.midPauseMs = p;
                tvMidPause.setText(p + "ms");
                if (user) cfg.save(MainActivity.this);
            }
        });

        sbMidPos.setProgress(cfg.midPausePosPct);
        tvMidPos.setText(cfg.midPausePosPct + "%");
        sbMidPos.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.midPausePosPct = p;
                tvMidPos.setText(p + "%");
                if (user) cfg.save(MainActivity.this);
            }
        });

        sbOffset.setProgress(cfg.startOffsetPct);
        tvOffset.setText(cfg.startOffsetPct + "%");
        sbOffset.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.startOffsetPct = p;
                tvOffset.setText(p + "%");
                if (user) cfg.save(MainActivity.this);
            }
        });

        sbInterval.setProgress(cfg.intervalSec);
        tvInterval.setText(cfg.intervalSec + "s");
        sbInterval.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.intervalSec = Math.max(1, Math.min(600, p));
                tvInterval.setText(cfg.intervalSec + "s");
                if (user) cfg.save(MainActivity.this);
            }
        });

        // ===== Tab =====
        View.OnClickListener tabClk = new View.OnClickListener() {
            @Override public void onClick(View v) {
                Mode m = (v == tabSwipe) ? Mode.SWIPE : Mode.CLICK;
                switchMode(m);
            }
        };
        tabSwipe.setOnClickListener(tabClk);
        tabClick.setOnClickListener(tabClk);

        btnAddPoint.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cfg.clickPoints.size() >= 8) {
                    Toast.makeText(MainActivity.this, "最多 8 个点击点", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (cfg.clickPoints.isEmpty()) {
                    addClickPoint(25, 50, 10);
                    addClickPoint(75, 50, 10);
                } else {
                    ClickPoint last = cfg.clickPoints.get(cfg.clickPoints.size() - 1);
                    int nx = (last.xPct + 20) % 100;
                    int ny = (last.yPct + 15) % 100;
                    if (nx < 5) nx = 50;
                    if (ny < 5) ny = 50;
                    addClickPoint(nx, ny, Math.max(0, Math.min(600, last.delaySec)));
                }
                cfg.save(MainActivity.this);
            }
        });

        // ===== 权限点击 =====
        tvAccStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAccessibilitySettings(); }
        });
        tvOverlayStatus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestOverlayPermission(); }
        });

        btnShowFloat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                cfg.save(MainActivity.this);
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction(FloatingService.ACTION_SHOW);
                putCfgIntent(i);
                startService(i);
                askOverlayIfNeeded();
            }
        });
        btnHideFloat.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, FloatingService.class);
                i.setAction(FloatingService.ACTION_HIDE);
                startService(i);
            }
        });
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isAccessibilityEnabled()) {
                    Toast.makeText(MainActivity.this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                    openAccessibilitySettings();
                    return;
                }
                if (cfg.mode == Mode.CLICK && cfg.clickPoints.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请至少添加一个点击点", Toast.LENGTH_LONG).show();
                    return;
                }
                cfg.save(MainActivity.this);
                // 先同步最新 cfg 给无障碍
                LocalBroadcastManager.getInstance(MainActivity.this)
                        .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_SYNC));
                // 显示悬浮窗
                Intent fi = new Intent(MainActivity.this, FloatingService.class);
                fi.setAction(FloatingService.ACTION_SHOW);
                putCfgIntent(fi);
                startService(fi);
                askOverlayIfNeeded();
                // 开始
                LocalBroadcastManager.getInstance(MainActivity.this)
                        .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_START));
                Toast.makeText(MainActivity.this, "已开始运行", Toast.LENGTH_SHORT).show();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                LocalBroadcastManager.getInstance(MainActivity.this)
                        .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STOP));
                Toast.makeText(MainActivity.this, "已停止", Toast.LENGTH_SHORT).show();
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1002);
        }

        // 载入模式 + 点
        switchMode(cfg.mode);
        rebuildPointList();

        IntentFilter f = new IntentFilter();
        f.addAction(SwipeAccessibilityService.ACTION_STATUS_ANS);
        f.addAction(FloatingService.ACTION_FLOATING_UPDATE);
        ContextCompat.registerReceiver(this, statusReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
        updateOverlayStatus();
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ============================================================
    // 模式切换
    // ============================================================
    private void switchMode(Mode m) {
        cfg.mode = m;
        boolean isSwipe = (m == Mode.SWIPE);
        swipeParams.setVisibility(isSwipe ? View.VISIBLE : View.GONE);
        clickParams.setVisibility(isSwipe ? View.GONE : View.VISIBLE);

        if (isSwipe) {
            tabSwipe.setBackgroundResource(R.drawable.bg_dir_selected);
            tabSwipe.setTextColor(0xFF38BDF8);
            tabClick.setBackgroundResource(R.drawable.bg_dir_normal);
            tabClick.setTextColor(0xFFE2E8F0);
        } else {
            tabClick.setBackgroundResource(R.drawable.bg_dir_selected);
            tabClick.setTextColor(0xFF38BDF8);
            tabSwipe.setBackgroundResource(R.drawable.bg_dir_normal);
            tabSwipe.setTextColor(0xFFE2E8F0);
            if (cfg.clickPoints.isEmpty()) {
                addClickPoint(30, 50, 10);
                addClickPoint(70, 50, 10);
            }
        }
        cfg.save(this);
        Intent fi = new Intent(this, FloatingService.class);
        fi.setAction(FloatingService.ACTION_REFRESH);
        putCfgIntent(fi);
        startService(fi);
    }

    private void putCfgIntent(Intent i) {
        i.putExtra(SwipeConfig.EXTRA_MODE, cfg.mode.name());
        i.putExtra(SwipeConfig.EXTRA_DIR, cfg.direction.name());
        i.putExtra(SwipeConfig.EXTRA_DIST_PCT, cfg.distancePct);
        i.putExtra(SwipeConfig.EXTRA_DUR_MS, cfg.durationMs);
        i.putExtra(SwipeConfig.EXTRA_INTERVAL_S, cfg.intervalSec);
        i.putExtra(SwipeConfig.EXTRA_MID_PAUSE_MS, cfg.midPauseMs);
        i.putExtra(SwipeConfig.EXTRA_MID_POS_PCT, cfg.midPausePosPct);
        i.putExtra(SwipeConfig.EXTRA_OFFSET_PCT, cfg.startOffsetPct);
        i.putExtra(SwipeConfig.EXTRA_CLICK_JSON, ClickPoint.listToJson(cfg.clickPoints));
    }

    // ============================================================
    // 点列表
    // ============================================================
    private String pointLetter(int idx) {
        if (idx < 26) return String.valueOf((char) ('A' + idx));
        return "P" + (idx + 1);
    }

    private void addClickPoint(int x, int y, int delaySec) {
        ClickPoint p = new ClickPoint();
        p.xPct = x; p.yPct = y; p.delaySec = delaySec;
        cfg.clickPoints.add(p);
        rebuildPointList();
    }

    private void rebuildPointList() {
        pointContainer.removeAllViews();
        pointHolders.clear();
        LayoutInflater lif = LayoutInflater.from(this);
        for (int i = 0; i < cfg.clickPoints.size(); i++) {
            ClickPoint pt = cfg.clickPoints.get(i);
            View row = lif.inflate(R.layout.row_point, pointContainer, false);
            final PointRowHolder h = new PointRowHolder(row, i);
            pointHolders.add(h);
            bindPointRow(h, pt);
            pointContainer.addView(row);
        }
        renamePoints();
    }

    private void renamePoints() {
        for (int i = 0; i < pointHolders.size(); i++) {
            pointHolders.get(i).name.setText(pointLetter(i) + " 点");
        }
    }

    private void bindPointRow(final PointRowHolder h, final ClickPoint pt) {
        h.xSb.setProgress(pt.xPct);
        h.ySb.setProgress(pt.yPct);
        h.delaySb.setProgress(Math.max(0, Math.min(600, pt.delaySec)));
        h.xVal.setText(pt.xPct + "%");
        h.yVal.setText(pt.yPct + "%");
        h.delayVal.setText(Math.max(0, Math.min(600, pt.delaySec)) + "s");

        h.xSb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.xPct = p;
                h.xVal.setText(p + "%");
                if (user) cfg.save(MainActivity.this);
            }
        });
        h.ySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.yPct = p;
                h.yVal.setText(p + "%");
                if (user) cfg.save(MainActivity.this);
            }
        });
        h.delaySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.delaySec = Math.max(0, Math.min(600, p));
                h.delayVal.setText(pt.delaySec + "s");
                if (user) cfg.save(MainActivity.this);
            }
        });
        h.btnCapture.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
                    requestOverlayPermission();
                    return;
                }
                cfg.save(MainActivity.this);
                // 临时隐藏悬浮窗以免挡住采点
                Intent fi = new Intent(MainActivity.this, FloatingService.class);
                fi.setAction(FloatingService.ACTION_HIDE_TEMP);
                startService(fi);
                CaptureOverlayManager.show(MainActivity.this,
                        h.index,
                        pointLetter(h.index),
                        new CaptureOverlayManager.Callback() {
                            @Override public void onSaved(int xPct, int yPct) {
                                // 恢复悬浮窗
                                Intent ri = new Intent(MainActivity.this, FloatingService.class);
                                ri.setAction(FloatingService.ACTION_RESTORE_TEMP);
                                putCfgIntent(ri);
                                startService(ri);
                                ClickPoint p = cfg.clickPoints.get(h.index);
                                p.xPct = xPct; p.yPct = yPct;
                                h.xSb.setProgress(xPct);
                                h.ySb.setProgress(yPct);
                                cfg.save(MainActivity.this);
                                Toast.makeText(MainActivity.this,
                                        pointLetter(h.index) + " 点已更新 X=" + xPct + "% Y=" + yPct + "%",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
            }
        });
        h.btnRemove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (cfg.clickPoints.size() <= 1) {
                    Toast.makeText(MainActivity.this, "至少保留 1 个点", Toast.LENGTH_SHORT).show();
                    return;
                }
                cfg.clickPoints.remove(h.index);
                cfg.save(MainActivity.this);
                rebuildPointList();
            }
        });
    }

    // ============================================================
    // 辅助方法
    // ============================================================
    private void refreshDirs() {
        applyDirBg(dirUp,    SwipeConfig.Direction.UP);
        applyDirBg(dirDown,  SwipeConfig.Direction.DOWN);
        applyDirBg(dirLeft,  SwipeConfig.Direction.LEFT);
        applyDirBg(dirRight, SwipeConfig.Direction.RIGHT);
    }

    private void applyDirBg(TextView tv, SwipeConfig.Direction d) {
        if (cfg.direction == d) {
            tv.setBackgroundResource(R.drawable.bg_dir_selected);
            tv.setTextColor(0xFF38BDF8);
        } else {
            tv.setBackgroundResource(R.drawable.bg_dir_normal);
            tv.setTextColor(0xFFE2E8F0);
        }
    }

    private void updateAccessibilityStatus() {
        boolean ok = isAccessibilityEnabled();
        tvAccStatus.setText(ok ? "✓ 已开启" : "✗ 点击开启");
        tvAccStatus.setBackgroundColor(ok ? 0xFF14532D : 0xFF7F1D1D);
        tvAccStatus.setTextColor(0xFFFFFFFF);
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String myId = new ComponentName(this, SwipeAccessibilityService.class).flattenToShortString();
        for (AccessibilityServiceInfo s : list) {
            String id = s.getResolveInfo().serviceInfo.packageName + "/" + s.getResolveInfo().serviceInfo.name;
            if (id.equals(myId)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        Toast.makeText(this, "找到「滑动模拟器」并开启", Toast.LENGTH_LONG).show();
    }

    private void updateOverlayStatus() {
        boolean ok = Settings.canDrawOverlays(this);
        tvOverlayStatus.setText(ok ? "✓ 已开启" : "✗ 点击开启");
        tvOverlayStatus.setBackgroundColor(ok ? 0xFF14532D : 0xFF7F1D1D);
        tvOverlayStatus.setTextColor(0xFFFFFFFF);
    }

    private void askOverlayIfNeeded() {
        if (!Settings.canDrawOverlays(this)) requestOverlayPermission();
    }

    private void requestOverlayPermission() {
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(i, REQ_OVERLAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        updateOverlayStatus();
    }

    private static abstract class SimpleSB implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
