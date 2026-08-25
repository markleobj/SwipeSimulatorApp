package com.swipesim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SwipeConfig {

    // ====== Intent extras ======
    public static final String EXTRA_MODE        = "extra_mode";
    public static final String EXTRA_DIR         = "extra_dir";
    public static final String EXTRA_DIST_PCT    = "extra_dist_pct";
    public static final String EXTRA_DUR_MS      = "extra_dur_ms";
    public static final String EXTRA_INTERVAL_S  = "extra_interval_s";
    public static final String EXTRA_MID_PAUSE_MS  = "extra_mid_pause_ms";
    public static final String EXTRA_MID_POS_PCT   = "extra_mid_pos_pct";
    public static final String EXTRA_OFFSET_PCT    = "extra_offset_pct";
    public static final String EXTRA_CLICK_JSON    = "extra_click_json";

    public enum Direction { UP, DOWN, LEFT, RIGHT }
    public enum Mode      { SWIPE, CLICK }

    public static class ClickPoint {
        public int xPct = 50;       // 0-100
        public int yPct = 50;       // 0-100
        public int delaySec = 10;   // 点击后等待秒数（0-600）

        public ClickPoint() {}
        public ClickPoint(int xPct, int yPct, int delaySec) {
            this.xPct = xPct; this.yPct = yPct; this.delaySec = delaySec;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("x", xPct).put("y", yPct).put("ds", delaySec);
            return o;
        }
        static ClickPoint fromJson(JSONObject o) throws JSONException {
            ClickPoint p = new ClickPoint();
            p.xPct = o.optInt("x", 50);
            p.yPct = o.optInt("y", 50);
            if (o.has("ds")) {
                p.delaySec = o.getInt("ds");
            } else if (o.has("d")) {
                // 兼容老字段：如果 d>=1000 视为毫秒，否则秒
                int raw = o.getInt("d");
                p.delaySec = raw >= 1000 ? (raw / 1000) : raw;
            }
            return p;
        }

        public static String listToJson(List<ClickPoint> list) {
            if (list == null) return "[]";
            JSONArray a = new JSONArray();
            try {
                for (ClickPoint p : list) a.put(p.toJson());
            } catch (JSONException ignored) {}
            return a.toString();
        }
        public static List<ClickPoint> listFromJson(String json) {
            List<ClickPoint> out = new ArrayList<>();
            if (json == null || json.isEmpty()) return out;
            try {
                JSONArray a = new JSONArray(json);
                for (int i = 0; i < a.length(); i++) {
                    out.add(fromJson(a.getJSONObject(i)));
                }
            } catch (JSONException ignored) {}
            return out;
        }
    }

    public Mode mode = Mode.SWIPE;

    // ---- Swipe params ----
    public Direction direction = Direction.DOWN;
    public int distancePct = 60;
    public int durationMs = 500;          // 原 swipeDurationMs
    public int midPauseMs = 0;
    public int midPausePosPct = 50;       // 原 midPauseAtPct
    public int startOffsetPct = 50;

    // ---- Click params ----
    public List<ClickPoint> clickPoints = new ArrayList<>();

    // ---- Common ----
    // intervalSec 为 source of truth（秒 1-600）；intervalMs 自动计算，兼容老 pref
    public int intervalSec = 30;

    public int getIntervalMs() { return Math.max(1000, intervalSec * 1000); }

    private static final String PREF = "swipe_config";

    public static SwipeConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SwipeConfig c = new SwipeConfig();
        c.clickPoints.clear();
        try {
            c.mode = Mode.valueOf(sp.getString("mode", Mode.SWIPE.name()));
        } catch (Exception e) { c.mode = Mode.SWIPE; }
        try { c.direction = Direction.valueOf(sp.getString("dir", Direction.DOWN.name())); }
        catch (Exception ignored) {}
        c.distancePct = sp.getInt("distancePct", 60);
        c.durationMs = sp.getInt("swipeDur", 500);
        c.midPauseMs = sp.getInt("midPause", 0);
        c.midPausePosPct = sp.getInt("midPauseAt", 50);
        c.startOffsetPct = sp.getInt("offset", 50);

        int intervalMs = sp.getInt("interval", 30000);
        c.intervalSec = Math.max(1, Math.min(600, intervalMs / 1000));

        String pts = sp.getString("clickPoints", null);
        if (pts != null) {
            c.clickPoints.addAll(ClickPoint.listFromJson(pts));
        }
        if (c.clickPoints.isEmpty()) {
            c.clickPoints.add(new ClickPoint(50, 40, 10));
            c.clickPoints.add(new ClickPoint(50, 60, 20));
        }
        return c;
    }

    public void save(Context ctx) {
        intervalSec = Math.max(1, Math.min(600, intervalSec));
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        ed.putString("mode", mode.name());
        ed.putString("dir", direction.name());
        ed.putInt("distancePct", distancePct);
        ed.putInt("swipeDur", durationMs);
        ed.putInt("midPause", midPauseMs);
        ed.putInt("midPauseAt", midPausePosPct);
        ed.putInt("offset", startOffsetPct);
        ed.putInt("interval", getIntervalMs());
        ed.putString("clickPoints", ClickPoint.listToJson(clickPoints));
        ed.apply();
    }

    // ====== 整个配置 <-> JSON（用于 Profile/方案 保存）======
    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", mode.name());
            o.put("dir", direction.name());
            o.put("distancePct", distancePct);
            o.put("durationMs", durationMs);
            o.put("midPauseMs", midPauseMs);
            o.put("midPausePosPct", midPausePosPct);
            o.put("startOffsetPct", startOffsetPct);
            o.put("intervalSec", Math.max(1, Math.min(600, intervalSec)));
            o.put("clickPoints", new JSONArray(ClickPoint.listToJson(clickPoints)));
            return o.toString();
        } catch (Throwable t) { return "{}"; }
    }

    public static SwipeConfig fromJson(String json) {
        SwipeConfig c = new SwipeConfig();
        c.clickPoints.clear();
        if (json == null || json.isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(json);
            try { c.mode = Mode.valueOf(o.optString("mode", Mode.SWIPE.name())); } catch (Exception ignored) {}
            try { c.direction = Direction.valueOf(o.optString("dir", Direction.DOWN.name())); } catch (Exception ignored) {}
            c.distancePct = o.optInt("distancePct", 60);
            c.durationMs = o.optInt("durationMs", 500);
            c.midPauseMs = o.optInt("midPauseMs", 0);
            c.midPausePosPct = o.optInt("midPausePosPct", 50);
            c.startOffsetPct = o.optInt("startOffsetPct", 50);
            int iSec = o.optInt("intervalSec", -1);
            if (iSec >= 1) c.intervalSec = Math.min(600, iSec);
            else c.intervalSec = Math.max(1, Math.min(600, o.optInt("intervalMs", 30000) / 1000));
            JSONArray arr = o.optJSONArray("clickPoints");
            if (arr == null) {
                String raw = o.optString("clickPointsRaw", null);
                if (raw != null) c.clickPoints.addAll(ClickPoint.listFromJson(raw));
            } else {
                // clickPoints 字段里已经是 JSONArray -> 转成字符串再用现成方法
                c.clickPoints.addAll(ClickPoint.listFromJson(arr.toString()));
            }
            if (c.clickPoints.isEmpty()) {
                c.clickPoints.add(new ClickPoint(30, 50, 10));
                c.clickPoints.add(new ClickPoint(70, 50, 10));
            }
        } catch (Throwable ignored) {}
        return c;
    }
}
