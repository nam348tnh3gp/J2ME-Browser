package com.minibrowser;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements BrowserView.Listener {

    private static final int MAX_TABS = 3;
    private static final int CACHE_MAX = 6;

    private Prefs prefs;
    private BrowserEngine engine = new BrowserEngine();
    private BrowserView view;
    private List<Tab> tabs = new ArrayList<>();
    private int activeTab = 0;
    private Map<String, Object[]> cache = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        view = new BrowserView(this);
        view.setListener(this);
        view.speedDial = new java.util.Vector<>();
        for (String[] d : SpeedDial.DEFAULT) view.speedDial.add(d);
        setContentView(view);

        tabs.add(new Tab());
        activeTab = 0;
        bindActiveTab();

        if (!prefs.hasProxy()) promptSettings(true);
        else if (tabs.get(activeTab).url.equals("home://")) view.setStatus("");
    }

    private Tab active() { return tabs.get(activeTab); }

    private void bindActiveTab() { view.tab = active(); view.selectedLink = -1; view.invalidate(); }

    // ── BrowserView.Listener ──
    @Override public void onMenuClicked() { showMainMenu(); }
    @Override public void onBackClicked() { goBack(); }
    @Override public void onReloadClicked() { reload(); }
    @Override public void onStopClicked() { engine.abort(); view.stopLoading(); view.setStatus("Đã dừng"); }
    @Override public void onUrlClicked() { promptUrl(); }
    @Override public void onHomeDialClicked(int idx) { openSpeedDial(idx); }
    @Override public void onLinkClicked(String url) { navigate(url); }
    @Override public void onLongPressLink(String url) {
        new AlertDialog.Builder(this).setTitle("Link").setMessage(url)
            .setPositiveButton("Mở", (d, w) -> navigate(url))
            .setNegativeButton("Đóng", null).show();
    }

    // ── navigation ──
    private void navigate(String url) {
        Tab t = active();
        t.url = url; t.bitmap = null; t.links = null; t.scrollY = 0;
        view.selectedLink = -1;
        loadUrl(url);
    }

    private void openSpeedDial(int idx) {
        if (idx < 0 || idx >= view.speedDial.size()) return;
        navigate(view.speedDial.get(idx)[1]);
    }

    private void goHome() {
        Tab t = active();
        t.url = "home://"; t.bitmap = null; t.links = null; t.scrollY = 0;
        view.selectedLink = -1;
        view.invalidate();
    }

    private void goBack() {
        Tab t = active();
        if (t.bitmap != null || (t.links != null && !t.links.isEmpty())) goHome();
        else if (tabs.size() > 1) {
            tabs.remove(activeTab);
            if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
            bindActiveTab();
        }
    }

    private void reload() {
        Tab t = active();
        cache.clear();
        t.bitmap = null; t.links = null; t.scrollY = 0;
        view.selectedLink = -1;
        view.invalidate();
        if (!"home://".equals(t.url)) loadUrl(t.url);
    }

    // ── loader ──
    private void loadUrl(final String url) {
        int w = view.getWidth();
        if (w == 0) { view.post(() -> loadUrl(url)); return; }
        String key = url + "@" + w;
        Object[] c = cache.get(key);
        if (c != null) {
            Tab t = active();
            t.url = url; t.bitmap = (android.graphics.Bitmap) c[0];
            t.links = (java.util.Vector<LinkRect>) c[1];
            t.scrollY = 0; view.selectedLink = -1;
            view.stopLoading(); view.invalidate();
            view.setStatus(url + " [cache]");
            return;
        }
        engine.load(prefs.getHost(), prefs.getPort(), url, w, view.getHeight(),
            new BrowserEngine.Callback() {
                @Override public void onStart() { view.startLoading(); view.setStatus(""); }
                @Override public void onResult(String u, android.graphics.Bitmap bm,
                                               java.util.Vector<LinkRect> links, int bytes) {
                    Tab t = active();
                    t.url = u; t.bitmap = bm; t.links = links; t.scrollY = 0;
                    t.lastBytes = bytes;
                    view.selectedLink = (links != null && !links.isEmpty()) ? 0 : -1;
                    if (view.selectedLink >= 0) view.ensureVisible();
                    if (cache.size() > CACHE_MAX) cache.clear();
                    cache.put(u + "@" + bm.getWidth(), new Object[]{bm, links});
                    view.invalidate();
                    view.setStatus(u + "  (" + (bytes / 1024) + " KB)");
                }
                @Override public void onError(String msg) { view.setStatus("Lỗi: " + msg); }
                @Override public void onStop() { view.stopLoading(); view.invalidate(); }
            });
    }

    // ── menus ──
    private void showMainMenu() {
        final String[] items = {
            "URL...", "Home", "New tab", "Tabs (" + tabs.size() + "/" + MAX_TABS + ")",
            "Reload", "Stop", "Bookmarks", "Save bookmark", "Clear cache", "Settings", "Exit"
        };
        new AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(items, (d, w) -> {
                switch (w) {
                    case 0: promptUrl(); break;
                    case 1: goHome(); break;
                    case 2: newTab(); break;
                    case 3: showTabs(); break;
                    case 4: reload(); break;
                    case 5: engine.abort(); view.stopLoading(); view.setStatus("Đã dừng"); break;
                    case 6: showBookmarks(); break;
                    case 7: saveBookmark(); break;
                    case 8: cache.clear(); toast("Đã xoá cache"); break;
                    case 9: promptSettings(false); break;
                    case 10: finish(); break;
                }
            }).show();
    }

    private void newTab() {
        if (tabs.size() >= MAX_TABS) { toast("Tối đa " + MAX_TABS + " tab"); return; }
        tabs.add(new Tab());
        activeTab = tabs.size() - 1;
        bindActiveTab();
    }

    private void showTabs() {
        String[] items = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            String u = "home://".equals(tabs.get(i).url) ? "Home" : tabs.get(i).url;
            items[i] = (i == activeTab ? "● " : "  ") + (i + 1) + ": " + shorten(u, 30);
        }
        new AlertDialog.Builder(this)
            .setTitle("Tabs")
            .setItems(items, (d, w) -> { activeTab = w; bindActiveTab(); })
            .setNegativeButton("Đóng tab này", (d, w) -> {
                if (tabs.size() > 1) {
                    tabs.remove(activeTab);
                    if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
                    bindActiveTab();
                } else toast("Không thể đóng tab cuối");
            })
            .setNeutralButton("Hủy", null)
            .show();
    }

    private void promptUrl() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        String cur = "home://".equals(active().url) ? "" : active().url;
        et.setText(cur);
        et.setSelection(et.getText().length());
        FrameLayout pad = new FrameLayout(this);
        int p = (int)(16 * getResources().getDisplayMetrics().density);
        pad.setPadding(p, p/2, p, 0);
        pad.addView(et);
        new AlertDialog.Builder(this)
            .setTitle("URL:")
            .setView(pad)
            .setPositiveButton("OK", (d, w) -> {
                String u = et.getText().toString().trim();
                if (u.isEmpty()) return;
                if (!u.contains("://")) u = "http://" + u;
                navigate(u);
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private void promptSettings(final boolean firstRun) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setText(prefs.getHost() + ":" + prefs.getPort());
        FrameLayout pad = new FrameLayout(this);
        int p = (int)(16 * getResources().getDisplayMetrics().density);
        pad.setPadding(p, p/2, p, 0);
        pad.addView(et);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
            .setTitle("Proxy (host:port)")
            .setView(pad)
            .setPositiveButton("Lưu", (d, w) -> applyProxy(et.getText().toString()))
            .setNegativeButton(firstRun ? "Để sau" : "Hủy", null);
        if (!firstRun) b.setNeutralButton("Mặc định", (d, w) -> {
            prefs.setProxy("192.168.1.100", 9999);
            toast("Mặc định");
        });
        b.show();
    }

    private void applyProxy(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) { toast("Trống"); return; }
        String h; int port;
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) { h = s; port = 9999; }
        else {
            h = s.substring(0, idx).trim();
            try { port = Integer.parseInt(s.substring(idx + 1).trim()); }
            catch (NumberFormatException e) { toast("Port sai"); return; }
        }
        if (h.isEmpty() || port < 1 || port > 65535) { toast("Sai host/port"); return; }
        prefs.setProxy(h, port);
        toast("Đã lưu: " + h + ":" + port);
    }

    private void showBookmarks() {
        final List<String[]> bms = prefs.getBookmarks();
        if (bms.isEmpty()) { toast("Chưa có bookmark"); return; }
        String[] items = new String[bms.size()];
        for (int i = 0; i < bms.size(); i++)
            items[i] = bms.get(i)[0] + "\n" + shorten(bms.get(i)[1], 40);
        new AlertDialog.Builder(this)
            .setTitle("Bookmarks")
            .setItems(items, (d, w) -> navigate(bms.get(w)[1]))
            .setNegativeButton("Xóa", (d, w) -> showDeleteBookmark(bms))
            .setNeutralButton("Đóng", null)
            .show();
    }

    private void showDeleteBookmark(final List<String[]> bms) {
        String[] items = new String[bms.size()];
        for (int i = 0; i < bms.size(); i++) items[i] = bms.get(i)[0];
        new AlertDialog.Builder(this)
            .setTitle("Xóa bookmark")
            .setItems(items, (d, w) -> {
                bms.remove(w);
                prefs.saveBookmarks(bms);
                toast("Đã xóa");
            })
            .setNegativeButton("Hủy", null)
            .show();
    }

    private void saveBookmark() {
        Tab t = active();
        if ("home://".equals(t.url) || t.url == null) { toast("Không có trang"); return; }
        List<String[]> bms = prefs.getBookmarks();
        for (String[] bm : bms) if (bm[1].equals(t.url)) { toast("Đã có bookmark"); return; }
        bms.add(new String[]{ shorten(t.url, 40), t.url });
        prefs.saveBookmarks(bms);
        toast("Đã lưu bookmark");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String shorten(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    @Override protected void onDestroy() { super.onDestroy(); engine.abort(); }
}
