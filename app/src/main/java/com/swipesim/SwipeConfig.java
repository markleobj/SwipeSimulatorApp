package com.swipesim;

import android.content.Context;
import android.content.SharedPreferences;

public class SwipeConfig {

    public enum Direction { UP, DOWN, LEFT, RIGHT }

    public Direction direction = Direction.UP;
    public int distancePct = 60;           // 滑动距离占屏幕比例 0-100
    public int swipeDurationMs = 500;      // 单次滑动总时长（不含停顿）
    public int midPauseMs = 0;             // 滑动中途停顿时长
    public int midPauseAtPct = 50;         // 中途停顿位置百分比
    public int startOffsetPct = 50;        // 起点偏移百分比
    public int intervalMs = 30000;         // 每次滑动之间的等待（间隔30s默认）

    private static final String PREF = "swipe_config";

    public static SwipeConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SwipeConfig c = new SwipeConfig();
        c.direction = Direction.valueOf(sp.getString("dir", Direction.UP.name()));
        c.distancePct = sp.getInt("distancePct", 60);
        c.swipeDurationMs = sp.getInt("swipeDur", 500);
        c.midPauseMs = sp.getInt("midPause", 0);
        c.midPauseAtPct = sp.getInt("midPauseAt", 50);
        c.startOffsetPct = sp.getInt("offset", 50);
        c.intervalMs = sp.getInt("interval", 30000);
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("dir", direction.name())
                .putInt("distancePct", distancePct)
                .putInt("swipeDur", swipeDurationMs)
                .putInt("midPause", midPauseMs)
                .putInt("midPauseAt", midPauseAtPct)
                .putInt("offset", startOffsetPct)
                .putInt("interval", intervalMs)
                .apply();
    }
}
