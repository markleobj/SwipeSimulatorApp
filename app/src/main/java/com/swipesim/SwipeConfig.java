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

    /** 统一的默认点击点（消除 load / fromJson 两处不一致的默认值） */
    private static void ensureDefaultPoints(List<ClickPoint> list) {
        if (list.isEmpty()) {
            list.add(new ClickPoint(50, 40, 10));
            list.add(new ClickPoint(50, 60, 20));
        }
    }

    public static SwipeConfig load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SwipeConfig c = new SwipeConfig();
        if (c.clickPoints == null) c.clickPoints = new ArrayList<>();
        c.clickPoints.clear();
        try {
            c.mode = Mode.valueOf(sp.getString("mode", Mode.SWIPE.name()));
        } catch (Exception e) { c.mode = Mode.SWIPE; }
        try { c.direction = Direction.valueOf(sp.getString("dir", Direction.DOWN.name())); }
        catch (Exception ignored) { c.direction = Direction.DOWN; }
        c.distancePct = Math.max(10, Math.min(90, sp.getInt("distancePct", 60)));
        c.durationMs = Math.max(50, sp.getInt("swipeDur", 500));
        c.midPauseMs = Math.max(0, sp.getInt("midPause", 0));
        c.midPausePosPct = Math.max(0, Math.min(100, sp.getInt("midPauseAt", 50)));
        c.startOffsetPct = Math.max(0, Math.min(100, sp.getInt("offset", 50)));

        int intervalMs = sp.getInt("interval", 30000);
        c.intervalSec = Math.max(1, Math.min(600, intervalMs / 1000));

        String pts = sp.getString("clickPoints", null);
        if (pts != null) {
            List<ClickPoint> parsed = ClickPoint.listFromJson(pts);
            if (parsed != null) c.clickPoints.addAll(parsed);
        }
        if (c.clickPoints.isEmpty()) {
            ensureDefaultPoints(c.clickPoints);
        }
        // 最终兜底
        if (c.mode == null) c.mode = Mode.SWIPE;
        if (c.direction == null) c.direction = Direction.DOWN;
        return c;
    }

    public void save(Context ctx) {
        if (mode == null) mode = Mode.SWIPE;
        if (direction == null) direction = Direction.DOWN;
        if (clickPoints == null) clickPoints = new ArrayList<>();
        intervalSec = Math.max(1, Math.min(600, intervalSec));
        distancePct = Math.max(10, Math.min(90, distancePct));
        durationMs = Math.max(50, durationMs);
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
            if (clickPoints == null) clickPoints = new ArrayList<>();
            JSONObject o = new JSONObject();
            o.put("mode", mode == null ? Mode.SWIPE.name() : mode.name());
            o.put("dir", direction == null ? Direction.DOWN.name() : direction.name());
            o.put("distancePct", Math.max(10, Math.min(90, distancePct)));
            o.put("durationMs", Math.max(50, durationMs));
            o.put("midPauseMs", Math.max(0, midPauseMs));
            o.put("midPausePosPct", Math.max(0, Math.min(100, midPausePosPct)));
            o.put("startOffsetPct", Math.max(0, Math.min(100, startOffsetPct)));
            o.put("intervalSec", Math.max(1, Math.min(600, intervalSec)));
            // clickPoints 直接存 JSON 字符串，避免 toJsonArray 再 fromJsonArray 多次解析
            o.put("clickPointsRaw", ClickPoint.listToJson(clickPoints));
            return o.toString();
        } catch (Throwable t) {
            android.util.Log.e("SwipeConfig", "toJson err", t);
            return "{}";
        }
    }

    public static SwipeConfig fromJson(String json) {
        SwipeConfig c = new SwipeConfig();
        if (c.clickPoints == null) c.clickPoints = new ArrayList<>();
        c.clickPoints.clear();
        if (json == null || json.isEmpty()) return c;
        try {
            JSONObject o = new JSONObject(json);
            try { c.mode = Mode.valueOf(o.optString("mode", Mode.SWIPE.name())); } catch (Exception ignored) { c.mode = Mode.SWIPE; }
            try { c.direction = Direction.valueOf(o.optString("dir", Direction.DOWN.name())); } catch (Exception ignored) { c.direction = Direction.DOWN; }
            c.distancePct = Math.max(10, Math.min(90, o.optInt("distancePct", 60)));
            c.durationMs = Math.max(50, o.optInt("durationMs", 500));
            c.midPauseMs = Math.max(0, o.optInt("midPauseMs", 0));
            c.midPausePosPct = Math.max(0, Math.min(100, o.optInt("midPausePosPct", 50)));
            c.startOffsetPct = Math.max(0, Math.min(100, o.optInt("startOffsetPct", 50)));
            int iSec = o.optInt("intervalSec", -1);
            if (iSec >= 1) c.intervalSec = Math.min(600, iSec);
            else c.intervalSec = Math.max(1, Math.min(600, o.optInt("intervalMs", 30000) / 1000));

            // 优先用 clickPointsRaw（新格式，存 JSON 字符串）；兼容旧的 JSONArray 格式
            String raw = o.optString("clickPointsRaw", null);
            if (raw == null || raw.isEmpty()) {
                JSONArray arr = o.optJSONArray("clickPoints");
                if (arr != null) raw = arr.toString();
            }
            List<ClickPoint> parsed = ClickPoint.listFromJson(raw);
            if (parsed != null) c.clickPoints.addAll(parsed);

            if (c.clickPoints.isEmpty()) {
                ensureDefaultPoints(c.clickPoints);
            }
        } catch (Throwable t) {
            android.util.Log.e("SwipeConfig", "fromJson err", t);
        }
        // 最终兜底
        if (c.mode == null) c.mode = Mode.SWIPE;
        if (c.direction == null) c.direction = Direction.DOWN;
        if (c.clickPoints == null) c.clickPoints = new ArrayList<>();
        return c;
    }
}
