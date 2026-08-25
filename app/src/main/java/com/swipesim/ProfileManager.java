package com.swipesim;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ProfileManager {

    public static class Profile {
        public final String name;
        public final String cfgJson;
        public Profile(String name, String cfgJson) {
            this.name = name == null ? "" : name.trim();
            this.cfgJson = cfgJson == null ? "{}" : cfgJson;
        }
    }

    private static final String PREF = "swipe_profiles";
    private static final String KEY_LIST = "list";
    private static final String KEY_ACTIVE = "active";

    private final Context ctx;

    public ProfileManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SharedPreferences sp() { return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE); }

    public List<Profile> listAll() {
        List<Profile> out = new ArrayList<>();
        String raw = sp().getString(KEY_LIST, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Profile(o.optString("n", ""), o.optString("c", "{}")));
            }
        } catch (JSONException ignored) {}
        return out;
    }

    public boolean exists(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        String n = name.trim();
        for (Profile p : listAll()) if (n.equals(p.name)) return true;
        return false;
    }

    private void saveAll(List<Profile> list) {
        JSONArray a = new JSONArray();
        try {
            for (Profile p : list) {
                JSONObject o = new JSONObject();
                o.put("n", p.name);
                o.put("c", p.cfgJson);
                a.put(o);
            }
        } catch (JSONException ignored) {}
        sp().edit().putString(KEY_LIST, a.toString()).apply();
    }

    // 保存：不存在则新建；存在则覆盖（等于"更新当前方案"）
    public Profile save(String name, SwipeConfig cfg) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (cfg == null) cfg = new SwipeConfig();
        String json = cfg.toJson();
        List<Profile> list = listAll();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(n)) {
                list.set(i, new Profile(n, json));
                found = true;
                break;
            }
        }
        if (!found) list.add(new Profile(n, json));
        saveAll(list);
        return new Profile(n, json);
    }

    public boolean rename(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String on = oldName.trim(), nn = newName.trim();
        if (on.isEmpty() || nn.isEmpty() || on.equals(nn)) return false;
        if (exists(nn)) return false;
        List<Profile> list = listAll();
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(on)) {
                list.set(i, new Profile(nn, list.get(i).cfgJson));
                changed = true;
                break;
            }
        }
        if (!changed) return false;
        saveAll(list);
        // 如果被改的就是激活方案，同步激活名
        String active = getActiveName();
        if (on.equals(active)) setActive(nn);
        return true;
    }

    public boolean delete(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        List<Profile> list = listAll();
        List<Profile> next = new ArrayList<>();
        boolean removed = false;
        for (Profile p : list) {
            if (!n.equals(p.name)) next.add(p); else removed = true;
        }
        if (!removed) return false;
        saveAll(next);
        String active = getActiveName();
        if (n.equals(active)) {
            // 删除激活方案 -> 清空激活名
            sp().edit().remove(KEY_ACTIVE).apply();
        }
        return true;
    }

    public SwipeConfig loadByName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        for (Profile p : listAll()) {
            if (n.equals(p.name)) {
                SwipeConfig cfg = SwipeConfig.fromJson(p.cfgJson);
                cfg.save(ctx); // 把加载的方案"落地"成当前 active cfg
                setActive(n);
                return cfg;
            }
        }
        return null;
    }

    public String getActiveName() {
        return sp().getString(KEY_ACTIVE, "");
    }
    public void setActive(String name) {
        if (name == null || name.trim().isEmpty()) {
            sp().edit().remove(KEY_ACTIVE).apply();
        } else {
            sp().edit().putString(KEY_ACTIVE, name.trim()).apply();
        }
    }

    public Profile getActive() {
        String n = getActiveName();
        if (n.isEmpty()) return null;
        for (Profile p : listAll()) if (n.equals(p.name)) return p;
        return null;
    }
}
