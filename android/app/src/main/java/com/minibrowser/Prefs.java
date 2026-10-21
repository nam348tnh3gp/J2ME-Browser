package com.minibrowser;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.List;

public class Prefs {
    private static final String NAME = "minibrowser";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public String getHost() { return sp.getString("host", "192.168.1.100"); }
    public int getPort()    { return sp.getInt("port", 9999); }
    public void setProxy(String h, int p) {
        sp.edit().putString("host", h).putInt("port", p).apply();
    }
    public boolean hasProxy() { return sp.contains("host"); }

    public List<String[]> getBookmarks() {
        List<String[]> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(sp.getString("bm", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONArray e = arr.getJSONArray(i);
                list.add(new String[]{e.getString(0), e.getString(1)});
            }
        } catch (JSONException ignored) {}
        return list;
    }

    public void saveBookmarks(List<String[]> list) {
        JSONArray arr = new JSONArray();
        try {
            for (String[] e : list) {
                JSONArray j = new JSONArray();
                j.put(e[0]); j.put(e[1]);
                arr.put(j);
            }
        } catch (JSONException ignored) {}
        sp.edit().putString("bm", arr.toString()).apply();
    }
}
