package com.swipesim;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
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

    // ===== 方案管理 =====
    private ProfileManager profiles;
    private TextView tvProfileName;
    private TextView btnSaveNewProfile, btnSaveOverProfile, btnListProfile;

    // ===== 公共 =====
    private TextView tvAccStatus, tvOverlayStatus;
    private TextView tvRunStatus, tvRunSub;
    private Button btnShowFloat, btnHideFloat, btnStart, btnStop;

    private SwipeConfig cfg;

    private static final int REQ_OVERLAY = 1001;

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                String a = intent.getAction();
                if (SwipeAccessibilityService.ACTION_STATUS_ANS.equals(a)) {
                    boolean running = intent.getBooleanExtra(SwipeAccessibilityService.EXTRA_RUNNING, false);
                    int count = intent.getIntExtra(SwipeAccessibilityService.EXTRA_COUNT, 0);
                    String state = intent.getStringExtra(SwipeAccessibilityService.EXTRA_STATE);
                    String sub   = intent.getStringExtra(SwipeAccessibilityService.EXTRA_SUB);
                    tvRunStatus.setText("状态：" + stateText(running, state) + " ｜ 已完成：" + count + " 轮");
                    tvRunStatus.setTextColor(running ? 0xFF38BDF8 : ("error".equals(state) ? 0xFFF87171 : 0xFFA1A1AA));
                    tvRunSub.setText(sub == null || sub.isEmpty() ? "" : "当前：" + sub);
                } else if (FloatingService.ACTION_FLOATING_UPDATE.equals(a)) {
                    updateOverlayStatus();
                }
            } catch (Throwable t) {
                toastShort("状态刷新异常：" + safeMsg(t));
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
            case "error":         return "出错";
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

        profiles = new ProfileManager(this);

        // 如果已经有激活方案，先加载（覆盖默认 cfg）；否则用 SwipeConfig.load
        String active = profiles.getActiveName();
        if (!active.isEmpty() && profiles.exists(active)) {
            cfg = profiles.loadByName(active);
        }
        if (cfg == null) cfg = SwipeConfig.load(this);

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

        tvProfileName = findViewById(R.id.tv_profile_name);
        btnSaveNewProfile = findViewById(R.id.btn_save_new_profile);
        btnSaveOverProfile = findViewById(R.id.btn_save_over_profile);
        btnListProfile = findViewById(R.id.btn_list_profile);

        // ===== 方向按钮 =====
        View.OnClickListener dirClk = wrap(v -> {
            if (v == dirUp)      cfg.direction = SwipeConfig.Direction.UP;
            if (v == dirDown)    cfg.direction = SwipeConfig.Direction.DOWN;
            if (v == dirLeft)    cfg.direction = SwipeConfig.Direction.LEFT;
            if (v == dirRight)   cfg.direction = SwipeConfig.Direction.RIGHT;
            refreshDirs();
            saveCfg();
        }, "切换方向");
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
                if (user) saveCfg();
            }
        });

        sbDur.setProgress(cfg.durationMs);
        tvDur.setText(cfg.durationMs + "ms");
        sbDur.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.durationMs = Math.max(50, p);
                tvDur.setText(cfg.durationMs + "ms");
                if (user) saveCfg();
            }
        });

        sbMidPause.setProgress(cfg.midPauseMs);
        tvMidPause.setText(cfg.midPauseMs + "ms");
        sbMidPause.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.midPauseMs = p;
                tvMidPause.setText(p + "ms");
                if (user) saveCfg();
            }
        });

        sbMidPos.setProgress(cfg.midPausePosPct);
        tvMidPos.setText(cfg.midPausePosPct + "%");
        sbMidPos.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.midPausePosPct = p;
                tvMidPos.setText(p + "%");
                if (user) saveCfg();
            }
        });

        sbOffset.setProgress(cfg.startOffsetPct);
        tvOffset.setText(cfg.startOffsetPct + "%");
        sbOffset.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.startOffsetPct = p;
                tvOffset.setText(p + "%");
                if (user) saveCfg();
            }
        });

        sbInterval.setProgress(cfg.intervalSec);
        tvInterval.setText(cfg.intervalSec + "s");
        sbInterval.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                cfg.intervalSec = Math.max(1, Math.min(600, p));
                tvInterval.setText(cfg.intervalSec + "s");
                if (user) saveCfg();
            }
        });

        // ===== Tab =====
        View.OnClickListener tabClk = wrap(v -> {
            Mode m = (v == tabSwipe) ? Mode.SWIPE : Mode.CLICK;
            switchMode(m);
        }, "切换模式");
        tabSwipe.setOnClickListener(tabClk);
        tabClick.setOnClickListener(tabClk);

        btnAddPoint.setOnClickListener(wrap(v -> {
            if (cfg.clickPoints.size() >= 8) {
                toastShort("最多 8 个点击点");
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
            saveCfg();
        }, "添加点"));

        // ===== 方案按钮 =====
        btnSaveNewProfile.setOnClickListener(wrap(v -> showSaveNewDialog(), "保存新方案"));
        btnSaveOverProfile.setOnClickListener(wrap(v -> saveOverActive(), "覆盖当前方案"));
        btnListProfile.setOnClickListener(wrap(v -> showProfileListDialog(), "方案列表"));
        refreshProfileNameUI();

        // ===== 权限 =====
        tvAccStatus.setOnClickListener(wrap(v -> openAccessibilitySettings(), "打开无障碍"));
        tvOverlayStatus.setOnClickListener(wrap(v -> requestOverlayPermission(), "打开悬浮窗权限"));

        btnShowFloat.setOnClickListener(wrap(v -> {
            saveCfg();
            Intent i = new Intent(MainActivity.this, FloatingService.class);
            i.setAction(FloatingService.ACTION_SHOW);
            putCfgIntent(i);
            startService(i);
            askOverlayIfNeeded();
        }, "显示悬浮窗"));
        btnHideFloat.setOnClickListener(wrap(v -> {
            Intent i = new Intent(MainActivity.this, FloatingService.class);
            i.setAction(FloatingService.ACTION_HIDE);
            startService(i);
        }, "隐藏悬浮窗"));
        btnStart.setOnClickListener(wrap(v -> {
            if (!isAccessibilityEnabled()) {
                toastShort("请先开启无障碍服务");
                openAccessibilitySettings();
                return;
            }
            if (cfg.mode == Mode.CLICK && cfg.clickPoints.isEmpty()) {
                toastShort("请至少添加一个点击点");
                return;
            }
            saveCfg();
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_SYNC));
            Intent fi = new Intent(MainActivity.this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_SHOW);
            putCfgIntent(fi);
            startService(fi);
            askOverlayIfNeeded();
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_START));
            toastShort("已开始运行");
        }, "开始"));
        btnStop.setOnClickListener(wrap(v -> {
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(SwipeAccessibilityService.ACTION_STOP));
            toastShort("已停止");
        }, "停止"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            try {
                ActivityCompat.requestPermissions(this,
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1002);
            } catch (Throwable ignored) {}
        }

        switchMode(cfg.mode);
        rebuildPointList();

        IntentFilter f = new IntentFilter();
        f.addAction(SwipeAccessibilityService.ACTION_STATUS_ANS);
        f.addAction(FloatingService.ACTION_FLOATING_UPDATE);
        try {
            ContextCompat.registerReceiver(this, statusReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ignored) {}
    }

    // ============================================================
    // 方案管理 UI
    // ============================================================
    private void refreshProfileNameUI() {
        String name = profiles.getActiveName();
        if (name == null || name.isEmpty()) {
            tvProfileName.setText("当前方案：未命名（可保存为方案）");
        } else {
            tvProfileName.setText("当前方案：" + name + " （共 " + profiles.listAll().size() + " 个方案）");
        }
    }

    private void showSaveNewDialog() {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setHint("方案名称（例如：看视频 / 点广告 / 签到）");
        new AlertDialog.Builder(this)
                .setTitle("保存为新方案")
                .setView(wrapDialogView(et))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            String name = et.getText() == null ? "" : et.getText().toString().trim();
                            if (name.isEmpty()) { toastShort("请输入方案名称"); return; }
                            if (name.length() > 30) name = name.substring(0, 30);
                            if (profiles.exists(name)) {
                                toastShort("方案名已存在，建议用「覆盖当前方案」");
                                return;
                            }
                            profiles.save(name, cfg);
                            profiles.setActive(name);
                            cfg.save(MainActivity.this);
                            refreshProfileNameUI();
                            toastShort("已保存为方案：" + name);
                        } catch (Throwable t) { toastShort("保存失败：" + safeMsg(t)); }
                    }
                }).show();
    }

    private void saveOverActive() {
        String active = profiles.getActiveName();
        if (active.isEmpty()) { toastShort("当前没有激活方案，请先「保存为新方案」"); return; }
        new AlertDialog.Builder(this)
                .setTitle("覆盖方案")
                .setMessage("确定要用当前所有配置覆盖方案「" + active + "」？\n（包括模式、滑动/点击点、所有延时）")
                .setNegativeButton("取消", null)
                .setPositiveButton("覆盖", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        try {
                            profiles.save(active, cfg);
                            cfg.save(MainActivity.this);
                            toastShort("已更新方案：" + active);
                        } catch (Throwable t) { toastShort("覆盖失败：" + safeMsg(t)); }
                    }
                }).show();
    }

    private void showProfileListDialog() {
        final List<ProfileManager.Profile> list = profiles.listAll();
        final String active = profiles.getActiveName();
        if (list.isEmpty()) {
            toastShort("还没有任何方案，点「保存为新方案」创建第一个吧");
            return;
        }
        final String[] names = new String[list.size()];
        final boolean[] isActive = new boolean[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = list.get(i).name + (active.equals(list.get(i).name) ? "  ✔当前" : "");
            isActive[i] = active.equals(list.get(i).name);
        }
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("我的方案  —  点一个来启用；长按/或点侧按钮重命名和删除");
        ab.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names), new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                try {
                    ProfileManager.Profile p = list.get(which);
                    SwipeConfig loaded = profiles.loadByName(p.name);
                    if (loaded == null) { toastShort("加载失败"); return; }
                    cfg = loaded;
                    applyCfgToUi();
                    refreshProfileNameUI();
                    toastShort("已启用方案：" + p.name);
                    // 同步刷新悬浮窗
                    sendCfgRefresh();
                } catch (Throwable t) { toastShort("启用失败：" + safeMsg(t)); }
            }
        });
        ab.setNeutralButton("删除/重命名…", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                showProfileManageDialog(list, active);
            }
        });
        ab.setPositiveButton("关闭", null);
        ab.show();
    }

    private void showProfileManageDialog(final List<ProfileManager.Profile> list, final String active) {
        final String[] plainNames = new String[list.size()];
        for (int i = 0; i < list.size(); i++) plainNames[i] = list.get(i).name;
        AlertDialog.Builder ab = new AlertDialog.Builder(this);
        ab.setTitle("管理方案（选择一个执行操作）");
        ab.setItems(plainNames, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface dialog, int which) {
                final String pickName = list.get(which).name;
                final CharSequence[] ops = new CharSequence[] {
                        "🔄 重命名",
                        "✕ 删除方案" + (pickName.equals(active) ? "（当前启用中）" : "")
                };
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("操作方案：" + pickName)
                        .setItems(ops, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int op) {
                                if (op == 0) {
                                    final EditText et = new EditText(MainActivity.this);
                                    et.setText(pickName);
                                    et.setSelection(et.getText().length());
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("重命名方案")
                                            .setView(wrapDialogView(et))
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dlg, int w) {
                                                    try {
                                                        String nm = et.getText() == null ? "" : et.getText().toString().trim();
                                                        if (nm.isEmpty()) { toastShort("请输入名称"); return; }
                                                        if (nm.length() > 30) nm = nm.substring(0, 30);
                                                        boolean ok = profiles.rename(pickName, nm);
                                                        toastShort(ok ? "已重命名为：" + nm : "重命名失败（同名或不存在）");
                                                        refreshProfileNameUI();
                                                    } catch (Throwable t) { toastShort("重命名异常：" + safeMsg(t)); }
                                                }
                                            }).show();
                                } else {
                                    new AlertDialog.Builder(MainActivity.this)
                                            .setTitle("确认删除")
                                            .setMessage("确定删除方案「" + pickName + "」？删除后不可恢复。")
                                            .setNegativeButton("取消", null)
                                            .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                                                @Override public void onClick(DialogInterface dlg, int w) {
                                                    try {
                                                        boolean ok = profiles.delete(pickName);
                                                        toastShort(ok ? "已删除方案：" + pickName : "删除失败");
                                                        refreshProfileNameUI();
                                                    } catch (Throwable t) { toastShort("删除异常：" + safeMsg(t)); }
                                                }
                                            }).show();
                                }
                            }
                        }).show();
            }
        });
        ab.show();
    }

    private LinearLayout wrapDialogView(EditText et) {
        LinearLayout ll = new LinearLayout(this);
        int pad = dp(20);
        ll.setPadding(pad, dp(14), pad, 0);
        ll.addView(et, new LinearLayout.LayoutParams(-1, -2));
        return ll;
    }
    private int dp(int dp) { return (int)(dp * getResources().getDisplayMetrics().density + 0.5f); }

    private void applyCfgToUi() {
        try {
            switchMode(cfg.mode);
            // seekbar
            sbDist.setProgress(Math.max(10, Math.min(90, cfg.distancePct)));
            sbDur.setProgress(cfg.durationMs);
            sbMidPause.setProgress(cfg.midPauseMs);
            sbMidPos.setProgress(cfg.midPausePosPct);
            sbOffset.setProgress(cfg.startOffsetPct);
            sbInterval.setProgress(cfg.intervalSec);
            tvDist.setText(cfg.distancePct + "%");
            tvDur.setText(cfg.durationMs + "ms");
            tvMidPause.setText(cfg.midPauseMs + "ms");
            tvMidPos.setText(cfg.midPausePosPct + "%");
            tvOffset.setText(cfg.startOffsetPct + "%");
            tvInterval.setText(cfg.intervalSec + "s");
            refreshDirs();
            rebuildPointList();
        } catch (Throwable t) { toastShort("应用配置异常：" + safeMsg(t)); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            updateAccessibilityStatus();
            updateOverlayStatus();
            refreshProfileNameUI();
        } catch (Throwable ignored) {}
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
        saveCfg();
        sendCfgRefresh();
    }

    private void sendCfgRefresh() {
        try {
            Intent fi = new Intent(this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_REFRESH);
            putCfgIntent(fi);
            startService(fi);
        } catch (Throwable ignored) {}
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
                if (user) saveCfg();
            }
        });
        h.ySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.yPct = p;
                h.yVal.setText(p + "%");
                if (user) saveCfg();
            }
        });
        h.delaySb.setOnSeekBarChangeListener(new SimpleSB() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                pt.delaySec = Math.max(0, Math.min(600, p));
                h.delayVal.setText(pt.delaySec + "s");
                if (user) saveCfg();
            }
        });
        h.btnCapture.setOnClickListener(wrap(v -> {
            if (!Settings.canDrawOverlays(MainActivity.this)) {
                toastShort("请先授予悬浮窗权限");
                requestOverlayPermission();
                return;
            }
            saveCfg();
            Intent fi = new Intent(MainActivity.this, FloatingService.class);
            fi.setAction(FloatingService.ACTION_HIDE_TEMP);
            startService(fi);
            CaptureOverlayManager.show(MainActivity.this,
                    h.index,
                    pointLetter(h.index),
                    new CaptureOverlayManager.Callback() {
                        @Override public void onSaved(int xPct, int yPct) {
                            try {
                                Intent ri = new Intent(MainActivity.this, FloatingService.class);
                                ri.setAction(FloatingService.ACTION_RESTORE_TEMP);
                                putCfgIntent(ri);
                                startService(ri);
                            } catch (Throwable ignored) {}
                            ClickPoint p = cfg.clickPoints.get(h.index);
                            p.xPct = xPct; p.yPct = yPct;
                            h.xSb.setProgress(xPct);
                            h.ySb.setProgress(yPct);
                            saveCfg();
                            toastShort(pointLetter(h.index) + " 点已更新 X=" + xPct + "% Y=" + yPct + "%");
                        }
                    });
        }, "采点"));
        h.btnRemove.setOnClickListener(wrap(v -> {
            if (cfg.clickPoints.size() <= 1) {
                toastShort("至少保留 1 个点");
                return;
            }
            cfg.clickPoints.remove(h.index);
            saveCfg();
            rebuildPointList();
        }, "删除点"));
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

    private void saveCfg() {
        try { cfg.save(this); }
        catch (Throwable t) { toastShort("保存配置异常：" + safeMsg(t)); }
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

    private void toastShort(String msg) {
        if (msg == null) return;
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
        catch (Throwable ignored) {}
    }
    private static String safeMsg(Throwable t) {
        try {
            String msg = t == null ? "未知错误" : t.getMessage();
            if (msg == null || msg.isEmpty()) msg = t == null ? "未知错误" : t.getClass().getSimpleName();
            if (msg.length() > 80) msg = msg.substring(0, 80) + "…";
            return msg;
        } catch (Throwable ignore) { return "异常"; }
    }

    // 所有按钮统一入口：try-catch，闪退只 Toast 错误
    private View.OnClickListener wrap(final View.OnClickListener r, final String tag) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    r.onClick(v);
                } catch (Throwable t) {
                    toastShort(tag + "失败：" + safeMsg(t));
                }
            }
        };
    }

    private static abstract class SimpleSB implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar sb) {}
        @Override public void onStopTrackingTouch(SeekBar sb) {}
    }
}
