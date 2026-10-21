import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.*;

public class MiniBrowser extends MIDlet implements CommandListener {

    static final String DEFAULT_HOST = "192.168.1.100";
    static final int DEFAULT_PORT = 9999;
    static final String RMS_CFG = "MiniBrowserCfg";
    static final String RMS_BM  = "MiniBrowserBm";
    static final int MAX_TABS = 3;
    static final int CACHE_MAX = 8;

    static String proxyHost = DEFAULT_HOST;
    static int proxyPort = DEFAULT_PORT;

    Display display;
    BrowserCanvas canvas;
    Vector tabs = new Vector();
    int activeTab = 0;
    boolean firstRun = false;

    // Commands (2 softkey + menu items)
    Command menuCmd, backCmd;

    // Cache & network
    Hashtable cache = new Hashtable();
    volatile boolean stopRequested = false;
    volatile SocketConnection currentConnection = null;

    // Bookmarks + Speed Dial
    Vector bookmarks = new Vector();       // String[2]{title,url}
    Vector speedDial = new Vector();       // String[2]{title,url}
    static final String[][] DEFAULT_DIAL = {
        {"Google",    "http://google.com"},
        {"Wikipedia", "http://vi.wikipedia.org"},
        {"VNExpress", "http://vnexpress.net"},
        {"Dan Tri",   "http://dantri.com.vn"},
        {"YouTube",   "http://m.youtube.com"},
        {"Facebook",  "http://m.facebook.com"},
        {"Bao Moi",   "http://baomoi.com"},
        {"Tinhte",    "http://tinhte.vn"},
        {"Example",   "http://example.com"}
    };

    static class Tab {
        String url = "home://";
        Image image;
        Vector links;
        int scrollY = 0;
        long bytes = 0;
        Tab() {}
    }

    static class LinkRect {
        int x1, y1, x2, y2; String url;
        LinkRect(int a, int b, int c, int d, String u) { x1=a; y1=b; x2=c; y2=d; url=u; }
    }

    public void startApp() {
        display = Display.getDisplay(this);
        firstRun = !loadSettings();
        loadBookmarks();
        loadSpeedDial();

        menuCmd = new Command("Menu", Command.SCREEN, 1);
        backCmd = new Command("Lùi", Command.BACK, 2);

        canvas = new BrowserCanvas(this);
        canvas.addCommand(menuCmd);
        canvas.addCommand(backCmd);
        canvas.setCommandListener(this);

        tabs.addElement(new Tab());
        activeTab = 0;

        display.setCurrent(canvas);

        if (firstRun) {
            canvas.setStatus("Chưa cấu hình proxy — Menu > Cấu hình");
            promptSettings();
        } else {
            canvas.repaint();
        }
    }

    public void pauseApp() {}
    public void destroyApp(boolean u) { abortCurrent(); }

    public void commandAction(Command c, Displayable d) {
        if (c == menuCmd) showMenu();
        else if (c == backCmd) goBack();
    }

    Tab activeTab() { return (Tab) tabs.elementAt(activeTab); }

    // ── Menu ──

    void showMenu() {
        List menu = new List("Menu", List.IMPLICIT);
        menu.append("URL...", null);
        menu.append("Home (Speed Dial)", null);
        menu.append("New tab", null);
        menu.append("Tabs (" + tabs.size() + ")", null);
        menu.append("Reload", null);
        menu.append("Stop", null);
        menu.append("Bookmarks (" + bookmarks.size() + ")", null);
        menu.append("Save bookmark", null);
        menu.append("Settings", null);
        menu.append("Exit", null);
        menu.addCommand(new Command("Đóng", Command.CANCEL, 1));

        final List m = menu;
        menu.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.CANCEL) { display.setCurrent(canvas); return; }
                int idx = m.getSelectedIndex();
                display.setCurrent(canvas);
                switch (idx) {
                    case 0: promptUrl(); break;
                    case 1: goHome(); break;
                    case 2: newTab(); break;
                    case 3: showTabs(); break;
                    case 4: reload(); break;
                    case 5: abortCurrent(); canvas.setStatus("Đã dừng"); break;
                    case 6: showBookmarks(); break;
                    case 7: saveBookmark(); break;
                    case 8: promptSettings(); break;
                    case 9: notifyDestroyed(); break;
                }
            }
        });
        display.setCurrent(menu);
    }

    void goHome() {
        activeTab().url = "home://";
        activeTab().image = null;
        activeTab().links = null;
        activeTab().scrollY = 0;
        canvas.repaint();
    }

    void newTab() {
        if (tabs.size() >= MAX_TABS) {
            canvas.setStatus("Tối đa " + MAX_TABS + " tab");
            return;
        }
        tabs.addElement(new Tab());
        activeTab = tabs.size() - 1;
        canvas.repaint();
    }

    void showTabs() {
        List list = new List("Tabs (" + tabs.size() + "/" + MAX_TABS + ")", List.IMPLICIT);
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = (Tab) tabs.elementAt(i);
            String u = "home://".equals(t.url) ? "Home (Speed Dial)" : t.url;
            list.append((i == activeTab ? "● " : "  ") + (i + 1) + ": " + shorten(u, 30), null);
        }
        list.addCommand(new Command("Đóng tab này", Command.SCREEN, 1));
        list.addCommand(new Command("Hủy", Command.CANCEL, 2));

        final List l = list;
        list.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.CANCEL) {
                    display.setCurrent(canvas);
                } else if (c.getLabel().equals("Đóng tab này")) {
                    if (tabs.size() > 1) {
                        tabs.removeElementAt(activeTab);
                        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
                    }
                    display.setCurrent(canvas);
                    canvas.repaint();
                } else {
                    int idx = l.getSelectedIndex();
                    if (idx >= 0 && idx < tabs.size()) {
                        activeTab = idx;
                        canvas.repaint();
                    }
                    display.setCurrent(canvas);
                }
            }
        });
        display.setCurrent(list);
    }

    void reload() {
        Tab t = activeTab();
        cache.remove(t.url);
        t.image = null;
        t.links = null;
        if (!"home://".equals(t.url)) loadAsync(t.url);
    }

    void goBack() {
        Tab t = activeTab();
        if (t.image != null || (t.links != null && t.links.size() > 0)) {
            // Đang xem trang → về Home
            goHome();
        } else {
            // Đang ở home → lùi tab
            if (tabs.size() > 1) {
                tabs.removeElementAt(activeTab);
                if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
                canvas.repaint();
            }
        }
    }

    void showBookmarks() {
        if (bookmarks.size() == 0) { canvas.setStatus("Chưa có bookmark"); return; }
        List list = new List("Bookmarks", List.IMPLICIT);
        for (int i = 0; i < bookmarks.size(); i++) {
            String[] bm = (String[]) bookmarks.elementAt(i);
            list.append(bm[0] + "\n" + shorten(bm[1], 35), null);
        }
        list.addCommand(new Command("Xóa", Command.SCREEN, 1));
        list.addCommand(new Command("Hủy", Command.CANCEL, 2));
        final List l = list;
        list.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.CANCEL) { display.setCurrent(canvas); return; }
                if (c.getLabel().equals("Xóa")) {
                    int idx = l.getSelectedIndex();
                    if (idx >= 0) { bookmarks.removeElementAt(idx); saveBookmarks(); }
                    display.setCurrent(canvas);
                    return;
                }
                int idx = l.getSelectedIndex();
                if (idx >= 0) {
                    String[] bm = (String[]) bookmarks.elementAt(idx);
                    activeTab().url = bm[1];
                    activeTab().image = null;
                    activeTab().links = null;
                    activeTab().scrollY = 0;
                    loadAsync(bm[1]);
                }
                display.setCurrent(canvas);
            }
        });
        display.setCurrent(list);
    }

    void saveBookmark() {
        Tab t = activeTab();
        if ("home://".equals(t.url) || t.url == null) {
            canvas.setStatus("Không có trang để lưu");
            return;
        }
        String title = shorten(t.url, 40);
        // Tránh trùng
        for (int i = 0; i < bookmarks.size(); i++) {
            String[] bm = (String[]) bookmarks.elementAt(i);
            if (bm[1].equals(t.url)) {
                canvas.setStatus("Đã có bookmark");
                return;
            }
        }
        bookmarks.addElement(new String[]{ title, t.url });
        saveBookmarks();
        canvas.setStatus("Đã lưu bookmark");
    }

    // ── RMS ──

    boolean loadSettings() {
        RecordStore rs = null; boolean exists = false;
        try {
            rs = RecordStore.openRecordStore(RMS_CFG, true);
            if (rs.getNumRecords() > 0) {
                String s = new String(rs.getRecord(1), "UTF-8");
                int idx = s.lastIndexOf(':');
                if (idx > 0) {
                    proxyHost = s.substring(0, idx);
                    proxyPort = Integer.parseInt(s.substring(idx + 1));
                    exists = true;
                }
            }
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
        return exists;
    }

    void saveSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_CFG, true);
            byte[] d = (proxyHost + ":" + proxyPort).getBytes("UTF-8");
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length);
            else rs.addRecord(d, 0, d.length);
        } catch (Exception e) { canvas.setStatus("Lỗi lưu: " + e.getMessage()); }
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    void loadBookmarks() {
        bookmarks.removeAllElements();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_BM, true);
            if (rs.getNumRecords() > 0) {
                String data = new String(rs.getRecord(1), "UTF-8");
                String[] lines = split(data, '\n');
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].length() == 0) continue;
                    int bar = lines[i].indexOf('|');
                    if (bar > 0) bookmarks.addElement(new String[]{
                        lines[i].substring(0, bar), lines[i].substring(bar + 1)});
                }
            }
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    void saveBookmarks() {
        RecordStore rs = null;
        try {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < bookmarks.size(); i++) {
                String[] b = (String[]) bookmarks.elementAt(i);
                sb.append(b[0]).append('|').append(b[1]).append('\n');
            }
            rs = RecordStore.openRecordStore(RMS_BM, true);
            byte[] d = sb.toString().getBytes("UTF-8");
            if (rs.getNumRecords() > 0) rs.setRecord(1, d, 0, d.length);
            else rs.addRecord(d, 0, d.length);
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    void loadSpeedDial() {
        speedDial.removeAllElements();
        for (int i = 0; i < DEFAULT_DIAL.length; i++) {
            speedDial.addElement(new String[]{ DEFAULT_DIAL[i][0], DEFAULT_DIAL[i][1] });
        }
    }

    // ── Cấu hình proxy ──

    void promptSettings() {
        final TextBox tb = new TextBox("Proxy (host:port)", proxyHost + ":" + proxyPort, 100, TextField.URL);
        tb.addCommand(new Command("Lưu", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.addCommand(new Command("Mặc định", Command.SCREEN, 3));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) applyProxyInput(tb.getString());
                else if (c.getLabel().equals("Mặc định")) {
                    proxyHost = DEFAULT_HOST; proxyPort = DEFAULT_PORT;
                    saveSettings();
                    canvas.setStatus("Mặc định: " + proxyHost + ":" + proxyPort);
                    display.setCurrent(canvas);
                } else display.setCurrent(canvas);
            }
        });
        display.setCurrent(tb);
    }

    void applyProxyInput(String raw) {
        String s = raw.trim();
        if (s.length() == 0) { canvas.setStatus("Trống"); display.setCurrent(canvas); return; }
        String h; int p;
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) { h = s; p = DEFAULT_PORT; }
        else {
            h = s.substring(0, idx).trim();
            try { p = Integer.parseInt(s.substring(idx + 1).trim()); }
            catch (NumberFormatException ne) { canvas.setStatus("Port sai"); display.setCurrent(canvas); return; }
        }
        if (h.length() == 0 || p < 1 || p > 65535) { canvas.setStatus("Host/port sai"); display.setCurrent(canvas); return; }
        proxyHost = h; proxyPort = p; saveSettings();
        canvas.setStatus("Đã lưu: " + proxyHost + ":" + proxyPort);
        display.setCurrent(canvas);
    }

    void promptUrl() {
        Tab t = activeTab();
        String cur = "home://".equals(t.url) ? "" : t.url;
        final TextBox tb = new TextBox("URL:", cur, 200, TextField.URL);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) {
                    String u = tb.getString().trim();
                    if (u.length() == 0) { display.setCurrent(canvas); return; }
                    if (u.indexOf("://") < 0) u = "http://" + u;
                    activeTab().url = u;
                    activeTab().image = null;
                    activeTab().links = null;
                    activeTab().scrollY = 0;
                    loadAsync(u);
                }
                display.setCurrent(canvas);
            }
        });
        display.setCurrent(tb);
    }

    // ── Network ──

    synchronized void abortCurrent() {
        stopRequested = true;
        SocketConnection old = currentConnection;
        currentConnection = null;
        if (old != null) try { old.close(); } catch (Exception e) {}
    }

    void loadAsync(final String url) {
        abortCurrent();
        new Thread(new Runnable() { public void run() { loadUrl(url); } }).start();
    }

    void loadUrl(String url) {
        // Cache hit
        Object c = cache.get(url);
        if (c != null) {
            Object[] arr = (Object[]) c;
            Tab t = activeTab();
            t.image = (Image) arr[0];
            t.links = (Vector) arr[1];
            t.scrollY = 0;
            t.url = url;
            canvas.stopLoading();
            canvas.repaint();
            return;
        }

        stopRequested = false;
        canvas.startLoading();
        SocketConnection sc = null;
        try {
            sc = (SocketConnection) Connector.open("socket://" + proxyHost + ":" + proxyPort);
            synchronized (this) {
                if (stopRequested) { sc.close(); canvas.stopLoading(); return; }
                currentConnection = sc;
            }

            DataOutputStream out = new DataOutputStream(sc.openOutputStream());
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length); out.write(ub); out.flush();

            DataInputStream in = new DataInputStream(sc.openInputStream());
            int w = in.readInt();
            int h = in.readInt();
            int len = in.readInt();
            if (len <= 0 || len > 5 * 1024 * 1024) throw new IOException("Kích thước sai");

            byte[] imgData = new byte[len];
            in.readFully(imgData);

            int linkCount = in.readInt();
            Vector links = new Vector();
            for (int i = 0; i < linkCount; i++) {
                int x1 = in.readInt(), y1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt();
                int ul = in.readInt();
                byte[] ubb = new byte[ul];
                in.readFully(ubb);
                links.addElement(new LinkRect(x1, y1, x2, y2, new String(ubb, "UTF-8")));
            }

            synchronized (this) { if (currentConnection == sc) currentConnection = null; }
            try { sc.close(); } catch (Exception e) {}
            sc = null;

            if (stopRequested) { canvas.stopLoading(); return; }

            Image img = Image.createImage(imgData, 0, imgData.length);
            if (cache.size() > CACHE_MAX) cache.clear();
            cache.put(url, new Object[]{ img, links });

            Tab t = activeTab();
            t.url = url;
            t.image = img;
            t.links = links;
            t.scrollY = 0;
            t.bytes = imgData.length;

            canvas.stopLoading();
            canvas.repaint();
        } catch (Exception e) {
            canvas.stopLoading();
            if (!stopRequested) {
                canvas.setStatus("Lỗi: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            if (sc != null) try { sc.close(); } catch (Exception e) {}
            synchronized (this) { if (currentConnection == sc) currentConnection = null; }
        }
    }

    // ── Helpers ──
    static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
    static String[] split(String s, char sep) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                v.addElement(s.substring(start, i));
                start = i + 1;
            }
        }
        v.addElement(s.substring(start));
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }
}

// ═══════════════════════════════════════════════════════════════
//  Canvas: URL bar + Speed Dial + Page + loading
// ═══════════════════════════════════════════════════════════════

class BrowserCanvas extends Canvas {
    MiniBrowser midlet;
    Font font;
    String status = "";
    int selectedLink = -1;

    // Touch
    private int pressX, pressY;
    private long pressTime;
    private int lastDragY;
    private long lastDragTime;
    private float velocity;
    private boolean isDragging;
    private long lastTapTime;
    private int lastTapX, lastTapY;
    private volatile boolean momentumCancelled = false;

    // Loading
    private volatile boolean loading = false;
    private volatile int loadingFrame = 0;
    private Thread loadingThread;

    // Layout constants
    private static final int URLBAR_H = 16;

    BrowserCanvas(MiniBrowser m) {
        midlet = m;
        font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    void setStatus(String s) { status = s; repaint(); }

    void startLoading() {
        if (loading) return;
        loading = true; loadingFrame = 0;
        loadingThread = new Thread(new Runnable() {
            public void run() {
                while (loading) {
                    loadingFrame = (loadingFrame + 1) % 4;
                    try { Thread.sleep(180); } catch (Exception e) { break; }
                    repaint();
                }
            }
        });
        loadingThread.start();
    }

    void stopLoading() { loading = false; loadingThread = null; }

    int urlBarHeight() {
        int h = font.getHeight() + 4;
        return h < URLBAR_H ? URLBAR_H : h;
    }

    int contentTop() { return urlBarHeight(); }
    int contentViewH() { return getHeight() - contentTop(); }

    // ── Paint ──
    protected void paint(Graphics g) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();

        // Background
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, getWidth(), getHeight());

        // Content
        if ("home://".equals(t.url)) {
            drawSpeedDial(g, ubH, getHeight());
        } else if (t.image != null) {
            g.drawImage(t.image, 0, ubH - t.scrollY, Graphics.TOP | Graphics.LEFT);
            drawLinkHighlight(g, t, ubH);
            drawScrollbar(g, t, ubH);
        } else if (loading) {
            g.setColor(0x888888);
            g.setFont(font);
            String msg = "Đang tải" + dots(loadingFrame);
            int tw = font.stringWidth(msg);
            g.drawString(msg, (getWidth() - tw) / 2,
                getHeight() / 2 - font.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        } else if (status.length() > 0) {
            g.setColor(0x666666);
            g.setFont(font);
            int tw = font.stringWidth(status);
            g.drawString(status, (getWidth() - tw) / 2,
                getHeight() / 2 - font.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        }

        // URL bar (vẽ đè lên trên)
        drawUrlBar(g, ubH, t);
    }

    void drawUrlBar(Graphics g, int h, MiniBrowser.Tab t) {
        // Nền
        g.setColor(loading ? 0xAADDFF : 0xEEEEEE);
        g.fillRect(0, 0, getWidth(), h);
        g.setColor(0xCCCCCC);
        g.drawLine(0, h - 1, getWidth(), h - 1);

        // Nội dung: [<] url [x]
        int btnW = 14;
        g.setColor(0x000000);
        g.setFont(font);

        // Nút lùi
        g.drawString("◄", 2, 2, Graphics.TOP | Graphics.LEFT);
        // Nút tải lại / dừng
        g.drawString(loading ? "×" : "↻", getWidth() - btnW, 2, Graphics.TOP | Graphics.LEFT);

        // URL ở giữa
        String shown;
        if ("home://".equals(t.url)) shown = "Speed Dial";
        else shown = t.url;
        if (loading) shown = "Đang tải" + dots(loadingFrame);

        int maxW = getWidth() - 2 * btnW - 6;
        shown = truncate(shown, maxW);
        g.setColor(loading ? 0x000088 : 0x333333);
        g.drawString(shown, btnW + 2, 2, Graphics.TOP | Graphics.LEFT);
    }

    void drawLinkHighlight(Graphics g, MiniBrowser.Tab t, int top) {
        if (selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        int y1 = lr.y1 + top - t.scrollY - 1;
        int y2 = lr.y2 + top - t.scrollY + 1;
        if (y2 < top || y1 > getHeight()) return;
        g.setColor(0xFF8800);
        g.drawRect(lr.x1 - 1, y1, (lr.x2 - lr.x1) + 2, y2 - y1);
        g.drawRect(lr.x1 - 2, y1 - 1, (lr.x2 - lr.x1) + 4, (y2 - y1) + 2);
    }

    void drawScrollbar(Graphics g, MiniBrowser.Tab t, int top) {
        int viewH = getHeight() - top;
        if (t.image == null || t.image.getHeight() <= viewH || viewH < 10) return;
        int sbW = 3;
        int trackH = viewH - 4;
        int thumbH = Math.max(15, trackH * viewH / t.image.getHeight());
        int maxScroll = Math.max(1, t.image.getHeight() - viewH);
        int thumbY = top + 2 + (trackH - thumbH) * t.scrollY / maxScroll;
        g.setColor(0xAAAAAA);
        g.fillRect(getWidth() - sbW - 1, thumbY, sbW, thumbH);
    }

    void drawSpeedDial(Graphics g, int top, int bottom) {
        int w = getWidth(), h = bottom - top;
        int cols = 3, rows = 3;
        int cellW = w / cols, cellH = h / rows;
        int gap = 5;

        g.setColor(0xFFFFFF);
        g.fillRect(0, top, w, h);

        int n = midlet.speedDial.size();
        for (int i = 0; i < cols * rows; i++) {
            int col = i % cols, row = i / cols;
            int x = col * cellW + gap;
            int y = top + row * cellH + gap;
            int cw = cellW - 2 * gap;
            int ch = cellH - 2 * gap;
            if (i >= n) continue;

            String[] entry = (String[]) midlet.speedDial.elementAt(i);

            // Màu gradient giả
            int shade = 0x335577 + (i * 0x111122);
            if (shade > 0xFFFFFF) shade = 0xFFFFFF;
            g.setColor(shade);
            g.fillRoundRect(x, y, cw, ch, 8, 8);

            // Viền
            g.setColor(0xFFFFFF);
            g.drawRoundRect(x, y, cw, ch, 8, 8);

            // Tên (chữ cái đầu to + tên nhỏ)
            g.setColor(0xFFFFFF);
            Font fBig = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
            g.setFont(fBig);
            String initial = entry[0].length() > 0 ? entry[0].substring(0, 1).toUpperCase() : "?";
            int iw = fBig.stringWidth(initial);
            g.drawString(initial, x + (cw - iw) / 2, y + 6, Graphics.TOP | Graphics.LEFT);

            g.setFont(font);
            String title = truncate(entry[0], cw - 4);
            int tw = font.stringWidth(title);
            g.drawString(title, x + (cw - tw) / 2, y + ch - font.getHeight() - 4,
                Graphics.TOP | Graphics.LEFT);
        }
    }

    String dots(int n) {
        switch (n) { case 1: return "."; case 2: return ".."; case 3: return "..."; }
        return "";
    }

    String truncate(String s, int maxW) {
        if (s == null) return "";
        if (font.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.stringWidth(s + "…") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    void scroll(int dy) {
        MiniBrowser.Tab t = midlet.activeTab();
        int viewH = contentViewH();
        int pageH = t.image != null ? t.image.getHeight() : 0;
        t.scrollY += dy;
        if (t.scrollY < 0) t.scrollY = 0;
        int max = Math.max(0, pageH - viewH);
        if (t.scrollY > max) t.scrollY = max;
        repaint();
    }

    // ── Touch ──
    protected void pointerPressed(int x, int y) {
        momentumCancelled = true;
        pressX = x; pressY = y; pressTime = System.currentTimeMillis();
        lastDragY = y; lastDragTime = pressTime;
        velocity = 0; isDragging = false;
    }

    protected void pointerDragged(int x, int y) {
        if (System.currentTimeMillis() - pressTime < 40) return;
        int dy = lastDragY - y;
        if (dy == 0) return;
        long now = System.currentTimeMillis();
        long dt = now - lastDragTime;
        if (dt > 0 && dt < 300) {
            float instV = dy * 16f / dt;
            velocity = velocity * 0.6f + instV * 0.4f;
        }
        scroll(dy);
        isDragging = true;
        lastDragY = y; lastDragTime = now;
    }

    protected void pointerReleased(int x, int y) {
        long dur = System.currentTimeMillis() - pressTime;
        int dx = Math.abs(x - pressX);
        int dy = Math.abs(y - pressY);

        if (isDragging) {
            if (Math.abs(velocity) > 1.5f) applyMomentum(velocity);
            return;
        }
        if (dur > 500 && dx < 15 && dy < 15) { longPress(x, y); return; }

        long now = System.currentTimeMillis();
        if (now - lastTapTime < 400 && Math.abs(x - lastTapX) < 40 && Math.abs(y - lastTapY) < 40) {
            handleDoubleTap(x, y);
            lastTapTime = 0;
            return;
        }
        lastTapTime = now; lastTapX = x; lastTapY = y;
        handleTap(x, y);
    }

    void applyMomentum(final float v0) {
        momentumCancelled = false;
        new Thread(new Runnable() {
            public void run() {
                float v = v0;
                while (Math.abs(v) > 0.5f && !momentumCancelled) {
                    scroll((int) v);
                    v *= 0.90f;
                    try { Thread.sleep(16); } catch (Exception e) { break; }
                }
            }
        }).start();
    }

    void handleTap(int x, int y) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();

        // Tap URL bar
        if (y < ubH) {
            int btnW = 14;
            if (x < btnW + 4) { midlet.goBack(); return; }
            if (x > getWidth() - btnW - 4) {
                if (loading) { midlet.abortCurrent(); setStatus("Đã dừng"); }
                else midlet.reload();
                return;
            }
            midlet.promptUrl();
            return;
        }

        // Speed Dial tap
        if ("home://".equals(t.url)) {
            int top = ubH;
            int w = getWidth(), h = getHeight() - top;
            int cellW = w / 3, cellH = h / 3;
            int col = x / cellW, row = (y - top) / cellH;
            int idx = row * 3 + col;
            if (idx >= 0 && idx < midlet.speedDial.size()) {
                String[] entry = (String[]) midlet.speedDial.elementAt(idx);
                t.url = entry[1];
                t.image = null; t.links = null; t.scrollY = 0;
                midlet.loadAsync(entry[1]);
            }
            return;
        }

        // Page link hit
        int py = y - ubH + t.scrollY;
        if (t.links != null) {
            for (int i = 0; i < t.links.size(); i++) {
                MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
                if (x >= lr.x1 && x <= lr.x2 && py >= lr.y1 && py <= lr.y2) {
                    selectedLink = i;
                    repaint();
                    activateLink(lr);
                    return;
                }
            }
        }

        // Tap zones
        int viewH = contentViewH();
        int tapY = y - ubH;
        if (tapY < viewH / 4) scroll(-viewH * 2 / 3);
        else if (tapY > viewH * 3 / 4) scroll(viewH * 2 / 3);
    }

    void handleDoubleTap(int x, int y) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.scrollY > 0) t.scrollY = 0;
        else {
            int viewH = contentViewH();
            int pageH = t.image != null ? t.image.getHeight() : 0;
            t.scrollY = Math.max(0, Math.min(pageH / 2, pageH - viewH));
        }
        repaint();
    }

    void longPress(int x, int y) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.links == null) return;
        int py = y - ubH + t.scrollY;
        for (int i = 0; i < t.links.size(); i++) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
            if (x >= lr.x1 && x <= lr.x2 && py >= lr.y1 && py <= lr.y2) {
                setStatus(lr.url);
                return;
            }
        }
    }

    void activateLink(MiniBrowser.LinkRect lr) {
        MiniBrowser.Tab t = midlet.activeTab();
        t.url = lr.url;
        t.image = null; t.links = null; t.scrollY = 0;
        midlet.loadAsync(lr.url);
    }

    // ── Keypad ──
    protected void keyPressed(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}
        if (action == UP || keyCode == KEY_NUM2) scroll(-20);
        else if (action == DOWN || keyCode == KEY_NUM8) scroll(20);
        else if (action == FIRE || keyCode == KEY_NUM5) {
            MiniBrowser.Tab t = midlet.activeTab();
            if (t.links != null && t.links.size() > 0) {
                selectedLink = (selectedLink + 1) % t.links.size();
                ensureVisible();
            }
        }
    }

    void ensureVisible() {
        MiniBrowser.Tab t = midlet.activeTab();
        if (selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        int viewH = contentViewH();
        if (lr.y1 < t.scrollY) t.scrollY = lr.y1;
        else if (lr.y2 > t.scrollY + viewH) t.scrollY = lr.y2 - viewH + 8;
        if (t.scrollY < 0) t.scrollY = 0;
        repaint();
    }
}
