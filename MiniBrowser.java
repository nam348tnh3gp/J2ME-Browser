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
    static final int CACHE_MAX = 6;

    static String proxyHost = DEFAULT_HOST;
    static int proxyPort = DEFAULT_PORT;

    Display display;
    BrowserCanvas canvas;
    Vector tabs = new Vector();
    int activeTab = 0;
    boolean firstRun = false;

    Command menuCmd, backCmd;

    Hashtable cache = new Hashtable();
    volatile boolean stopRequested = false;
    volatile SocketConnection currentConnection = null;

    Vector bookmarks = new Vector();
    Vector speedDial = new Vector();
    static final String[][] DEFAULT_DIAL = {
        {"Google", "http://google.com"}, {"Wikipedia", "http://vi.wikipedia.org"},
        {"VNExpress", "http://vnexpress.net"}, {"Dan Tri", "http://dantri.com.vn"},
        {"YouTube", "http://m.youtube.com"}, {"Facebook", "http://m.facebook.com"},
        {"BaoMoi", "http://baomoi.com"}, {"Tinhte", "http://tinhte.vn"},
        {"Example", "http://example.com"}
    };

    static class Tab {
        String url = "home://";
        Image image;
        Vector links;
        int scrollY = 0;      // pixel ảnh (== pixel màn hình vì ảnh khớp width)
        int lastBytes = 0;
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
        }
    }

    public void pauseApp() {}
    public void destroyApp(boolean u) { abortCurrent(); }

    public void commandAction(Command c, Displayable d) {
        if (c == menuCmd) showMenu();
        else if (c == backCmd) goBack();
    }

    Tab activeTab() { return (Tab) tabs.elementAt(activeTab); }

    void showMenu() {
        List menu = new List("Menu", List.IMPLICIT);
        menu.append("URL...", null);
        menu.append("Home", null);
        menu.append("New tab", null);
        menu.append("Tabs (" + tabs.size() + ")", null);
        menu.append("Reload", null);
        menu.append("Stop", null);
        menu.append("Bookmarks (" + bookmarks.size() + ")", null);
        menu.append("Save bookmark", null);
        menu.append("Clear cache", null);
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
                    case 8: cache.clear(); canvas.setStatus("Đã xoá cache"); break;
                    case 9: promptSettings(); break;
                    case 10: notifyDestroyed(); break;
                }
            }
        });
        display.setCurrent(menu);
    }

    void goHome() {
        Tab t = activeTab();
        t.url = "home://"; t.image = null; t.links = null; t.scrollY = 0;
        canvas.selectedLink = -1;
        canvas.repaint();
    }

    void newTab() {
        if (tabs.size() >= MAX_TABS) { canvas.setStatus("Tối đa " + MAX_TABS + " tab"); return; }
        tabs.addElement(new Tab());
        activeTab = tabs.size() - 1;
        canvas.selectedLink = -1;
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
                if (c.getCommandType() == Command.CANCEL) display.setCurrent(canvas);
                else if (c.getLabel().equals("Đóng tab này")) {
                    if (tabs.size() > 1) {
                        tabs.removeElementAt(activeTab);
                        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
                    }
                    canvas.selectedLink = -1;
                    display.setCurrent(canvas); canvas.repaint();
                } else {
                    int idx = l.getSelectedIndex();
                    if (idx >= 0 && idx < tabs.size()) { activeTab = idx; canvas.selectedLink = -1; canvas.repaint(); }
                    display.setCurrent(canvas);
                }
            }
        });
        display.setCurrent(list);
    }

    void reload() {
        Tab t = activeTab();
        cache.clear();
        t.image = null; t.links = null; t.scrollY = 0;
        canvas.selectedLink = -1;
        if (!"home://".equals(t.url)) loadAsync(t.url);
    }

    void goBack() {
        Tab t = activeTab();
        if (t.image != null || (t.links != null && t.links.size() > 0)) goHome();
        else if (tabs.size() > 1) {
            tabs.removeElementAt(activeTab);
            if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
            canvas.selectedLink = -1;
            canvas.repaint();
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
                    display.setCurrent(canvas); return;
                }
                int idx = l.getSelectedIndex();
                if (idx >= 0) {
                    String[] bm = (String[]) bookmarks.elementAt(idx);
                    Tab t = activeTab();
                    t.url = bm[1]; t.image = null; t.links = null; t.scrollY = 0;
                    canvas.selectedLink = -1;
                    loadAsync(bm[1]);
                }
                display.setCurrent(canvas);
            }
        });
        display.setCurrent(list);
    }

    void saveBookmark() {
        Tab t = activeTab();
        if ("home://".equals(t.url) || t.url == null) { canvas.setStatus("Không có trang"); return; }
        for (int i = 0; i < bookmarks.size(); i++) {
            String[] bm = (String[]) bookmarks.elementAt(i);
            if (bm[1].equals(t.url)) { canvas.setStatus("Đã có bookmark"); return; }
        }
        bookmarks.addElement(new String[]{ shorten(t.url, 40), t.url });
        saveBookmarks();
        canvas.setStatus("Đã lưu bookmark");
    }

    boolean loadSettings() {
        RecordStore rs = null; boolean exists = false;
        try {
            rs = RecordStore.openRecordStore(RMS_CFG, true);
            if (rs.getNumRecords() > 0) {
                String s = new String(rs.getRecord(1), "UTF-8");
                int idx = s.lastIndexOf(':');
                if (idx > 0) { proxyHost = s.substring(0, idx); proxyPort = Integer.parseInt(s.substring(idx + 1)); exists = true; }
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
        } catch (Exception e) {}
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
                    if (bar > 0) bookmarks.addElement(new String[]{ lines[i].substring(0, bar), lines[i].substring(bar + 1) });
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
        for (int i = 0; i < DEFAULT_DIAL.length; i++)
            speedDial.addElement(new String[]{ DEFAULT_DIAL[i][0], DEFAULT_DIAL[i][1] });
    }

    void promptSettings() {
        final TextBox tb = new TextBox("Proxy (host:port)", proxyHost + ":" + proxyPort, 100, TextField.URL);
        tb.addCommand(new Command("Lưu", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.addCommand(new Command("Mặc định", Command.SCREEN, 3));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) applyProxyInput(tb.getString());
                else if (c.getLabel().equals("Mặc định")) {
                    proxyHost = DEFAULT_HOST; proxyPort = DEFAULT_PORT; saveSettings();
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
                    Tab t = activeTab();
                    t.url = u; t.image = null; t.links = null; t.scrollY = 0;
                    canvas.selectedLink = -1;
                    loadAsync(u);
                }
                display.setCurrent(canvas);
            }
        });
        display.setCurrent(tb);
    }

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
        int screenW = canvas.getWidth();
        String cacheKey = url + "@" + screenW;
        Object c = cache.get(cacheKey);
        if (c != null) {
            Object[] arr = (Object[]) c;
            Tab t = activeTab();
            t.image = (Image) arr[0]; t.links = (Vector) arr[1]; t.scrollY = 0; t.url = url;
            canvas.selectedLink = -1;
            canvas.stopLoading(); canvas.repaint();
            canvas.setStatus(url + " [cache]");
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
            out.writeInt(canvas.getWidth());
            out.writeInt(canvas.getHeight());
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length); out.write(ub); out.flush();

            DataInputStream in = new DataInputStream(sc.openInputStream());
            int w = in.readInt(); int h = in.readInt(); int len = in.readInt();
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
            cache.put(cacheKey, new Object[]{ img, links });

            Tab t = activeTab();
            t.url = url; t.image = img; t.links = links; t.scrollY = 0;
            t.lastBytes = imgData.length;
            canvas.selectedLink = -1;   // FIX: không giữ highlight link của trang cũ

            canvas.stopLoading(); canvas.repaint();
            canvas.setStatus(url + "  (" + (imgData.length / 1024) + " KB)");
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

    static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String[] split(String s, char sep) {
        Vector v = new Vector();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) { v.addElement(s.substring(start, i)); start = i + 1; }
        }
        v.addElement(s.substring(start));
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }
}

// ═══════════════════════════════════════════════════════════════
//  BrowserCanvas — KHÔNG scale (ảnh khớp 1:1 màn hình)
//  Cảm ứng kiểu Chrome Android: tap / drag (touch slop) / fling /
//  long-press / double-tap
// ═══════════════════════════════════════════════════════════════

class BrowserCanvas extends Canvas {
    MiniBrowser midlet;
    Font font;
    String status = "";
    int selectedLink = -1;

    // ── trạng thái cảm ứng ──
    private int pressX, pressY;
    private long pressTime;
    private int lastDragY;
    private long lastDragTime;
    private float velocity;
    private boolean isDragging;          // true khi đã vượt ngưỡng touch slop
    private boolean longPressed;
    private long lastTapTime;
    private int lastTapX, lastTapY;
    private int touchSlop = 8;           // tính lại theo kích thước màn hình
    private volatile boolean momentumCancelled = false;
    private Timer timer;
    private TimerTask lpTask;

    private volatile boolean loading = false;
    private volatile int loadingFrame = 0;
    private Thread loadingThread;

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

    int softBarHeight() {
        int h = font.getHeight() + 6;
        return h < 18 ? 18 : h;
    }

    int contentViewH() { return getHeight() - urlBarHeight() - softBarHeight(); }

    // ── Xoay màn hình → reload theo width mới ──
    protected void sizeChanged(int w, int h) {
        MiniBrowser.Tab t = midlet.activeTab();
        selectedLink = -1;
        if (t.image != null && t.image.getWidth() != w) {
            midlet.cache.clear();
            t.image = null; t.links = null; t.scrollY = 0;
            if (!"home://".equals(t.url)) midlet.loadAsync(t.url);
        }
    }

    // ── Paint ──
    protected void paint(Graphics g) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();

        g.setColor(0xFFFFFF); g.fillRect(0, 0, getWidth(), getHeight());

        int sbH = softBarHeight();
        int contentBottom = getHeight() - sbH;

        if ("home://".equals(t.url)) {
            drawSpeedDial(g, ubH, contentBottom);
        } else if (t.image != null) {
            g.drawImage(t.image, 0, ubH - t.scrollY, Graphics.TOP | Graphics.LEFT);
            drawLinkHighlight(g, t, ubH);
            drawScrollbar(g, t, ubH);
        } else if (loading) {
            g.setColor(0x888888); g.setFont(font);
            String msg = "Đang tải" + dots(loadingFrame);
            int tw = font.stringWidth(msg);
            g.drawString(msg, (getWidth() - tw) / 2, getHeight() / 2 - font.getHeight() / 2,
                Graphics.TOP | Graphics.LEFT);
        } else if (status.length() > 0) {
            g.setColor(0x666666); g.setFont(font);
            int tw = font.stringWidth(status);
            g.drawString(status, (getWidth() - tw) / 2, getHeight() / 2 - font.getHeight() / 2,
                Graphics.TOP | Graphics.LEFT);
        }

        drawUrlBar(g, ubH, t);
        drawSoftBar(g);
    }

    void drawSoftBar(Graphics g) {
        int h = softBarHeight();
        int y = getHeight() - h;
        g.setColor(0xDDDDDD);
        g.fillRect(0, y, getWidth(), h);
        g.setColor(0xAAAAAA);
        g.drawLine(0, y, getWidth(), y);
        g.setColor(0x000000);
        g.setFont(font);
        g.drawString("Menu", 4, y + 3, Graphics.TOP | Graphics.LEFT);
        String right = "Lùi";
        int rw = font.stringWidth(right);
        g.drawString(right, getWidth() - rw - 4, y + 3, Graphics.TOP | Graphics.LEFT);
    }

    void drawUrlBar(Graphics g, int h, MiniBrowser.Tab t) {
        g.setColor(loading ? 0xAADDFF : 0xEEEEEE);
        g.fillRect(0, 0, getWidth(), h);
        g.setColor(0xCCCCCC); g.drawLine(0, h - 1, getWidth(), h - 1);
        int btnW = 14;
        g.setColor(0x000000); g.setFont(font);
        g.drawString("◄", 2, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString(loading ? "×" : "↻", getWidth() - btnW, 2, Graphics.TOP | Graphics.LEFT);
        String shown = "home://".equals(t.url) ? "Speed Dial" : t.url;
        if (loading) shown = "Đang tải" + dots(loadingFrame);
        shown = truncate(shown, getWidth() - 2 * btnW - 6);
        g.setColor(loading ? 0x000088 : 0x333333);
        g.drawString(shown, btnW + 2, 2, Graphics.TOP | Graphics.LEFT);
    }

    void drawLinkHighlight(Graphics g, MiniBrowser.Tab t, int top) {
        if (selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        int y1 = top + (lr.y1 - t.scrollY) - 1;
        int y2 = top + (lr.y2 - t.scrollY) + 1;
        if (y2 < top || y1 > getHeight() - softBarHeight()) return;
        g.setColor(0xFF8800);
        g.drawRect(lr.x1 - 1, y1, (lr.x2 - lr.x1) + 2, y2 - y1);
        g.drawRect(lr.x1 - 2, y1 - 1, (lr.x2 - lr.x1) + 4, (y2 - y1) + 2);
    }

    void drawScrollbar(Graphics g, MiniBrowser.Tab t, int top) {
        int viewH = contentViewH();
        int imgH = t.image.getHeight();
        if (imgH <= viewH || viewH < 10) return;
        int sbW = 3;
        int trackH = viewH - 4;
        int thumbH = Math.max(15, trackH * viewH / imgH);
        int maxScroll = Math.max(1, imgH - viewH);
        int thumbY = top + 2 + (trackH - thumbH) * t.scrollY / maxScroll;
        g.setColor(0xAAAAAA);
        g.fillRect(getWidth() - sbW - 1, thumbY, sbW, thumbH);
    }

    void drawSpeedDial(Graphics g, int top, int bottom) {
        int w = getWidth(), h = bottom - top;
        int cols = 3, rows = 3;
        int cellW = w / cols, cellH = h / rows;
        int gap = 5;
        g.setColor(0xFFFFFF); g.fillRect(0, top, w, h);
        int n = midlet.speedDial.size();
        for (int i = 0; i < cols * rows; i++) {
            int col = i % cols, row = i / cols;
            int x = col * cellW + gap;
            int y = top + row * cellH + gap;
            int cw = cellW - 2 * gap, ch = cellH - 2 * gap;
            if (i >= n) continue;
            String[] entry = (String[]) midlet.speedDial.elementAt(i);
            int shade = 0x335577 + (i * 0x111122);
            if (shade > 0xFFFFFF) shade = 0xFFFFFF;
            if (i == selectedLink) shade = 0xFF8800;   // highlight ô đang chọn bằng phím
            g.setColor(shade);
            g.fillRoundRect(x, y, cw, ch, 8, 8);
            g.setColor(i == selectedLink ? 0xFFFF00 : 0xFFFFFF);
            g.drawRoundRect(x, y, cw, ch, 8, 8);
            if (i == selectedLink) g.drawRoundRect(x - 1, y - 1, cw + 2, ch + 2, 8, 8);
            Font fBig = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
            g.setFont(fBig); g.setColor(0xFFFFFF);
            String initial = entry[0].length() > 0 ? entry[0].substring(0, 1).toUpperCase() : "?";
            int iw = fBig.stringWidth(initial);
            g.drawString(initial, x + (cw - iw) / 2, y + 6, Graphics.TOP | Graphics.LEFT);
            g.setFont(font);
            String title = truncate(entry[0], cw - 4);
            int tw = font.stringWidth(title);
            g.drawString(title, x + (cw - tw) / 2, y + ch - font.getHeight() - 4, Graphics.TOP | Graphics.LEFT);
        }
    }

    String dots(int n) {
        switch (n) { case 1: return "."; case 2: return ".."; case 3: return "..."; }
        return "";
    }

    String truncate(String s, int maxW) {
        if (s == null) return "";
        if (font.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.stringWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ── Scroll — pixel ảnh == pixel màn hình ──
    void scroll(int dy) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.image == null) return;
        int viewH = contentViewH();
        t.scrollY += dy;
        if (t.scrollY < 0) t.scrollY = 0;
        int max = Math.max(0, t.image.getHeight() - viewH);
        if (t.scrollY > max) t.scrollY = max;
        repaint();
    }

    // ═══════════════════════════════════════════════════════════
    //  TOUCH — kiểu Chrome Android
    // ═══════════════════════════════════════════════════════════

    /** Tìm link tại toạ độ màn hình (x, y). Vùng bấm nới theo độ rộng màn hình,
     *  nếu nhiều link chồng nhau thì chọn link có tâm gần điểm chạm nhất. */
    int linkAt(int x, int y) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.image == null || t.links == null) return -1;
        int pad = Math.max(4, getWidth() / 40);
        int ix = x, iy = y - urlBarHeight() + t.scrollY;
        int best = -1; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < t.links.size(); i++) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
            if (ix >= lr.x1 - pad && ix <= lr.x2 + pad
                && iy >= lr.y1 - pad && iy <= lr.y2 + pad) {
                long dx = ix - (lr.x1 + lr.x2) / 2, dy = iy - (lr.y1 + lr.y2) / 2;
                long d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = i; }
            }
        }
        return best;
    }

    protected void pointerPressed(int x, int y) {
        momentumCancelled = true;                       // chạm vào để dừng fling
        touchSlop = Math.max(6, Math.min(getWidth(), getHeight()) / 30);
        pressX = x; pressY = y; pressTime = System.currentTimeMillis();
        lastDragY = y; lastDragTime = pressTime;
        velocity = 0; isDragging = false; longPressed = false;
        selectedLink = (y >= urlBarHeight()) ? linkAt(x, y) : -1;   // phản hồi ngay khi chạm
        repaint();
        startLongPress(x, y);
    }

    protected void pointerDragged(int x, int y) {
        if (!isDragging) {
            // Ngón tay luôn rung nhẹ khi chạm → chỉ coi là vuốt khi vượt ngưỡng slop
            if (Math.abs(x - pressX) <= touchSlop && Math.abs(y - pressY) <= touchSlop) return;
            isDragging = true;
            cancelLongPress();
            selectedLink = -1;
            lastDragY = pressY;                         // nội dung bám theo ngón tay
            lastDragTime = System.currentTimeMillis();
        }
        int dy = lastDragY - y;
        if (dy == 0) return;
        long now = System.currentTimeMillis();
        long dt = now - lastDragTime;
        if (dt > 0 && dt < 300) {
            float instV = dy * 16f / dt;
            velocity = velocity * 0.6f + instV * 0.4f;
        }
        scroll(dy);
        lastDragY = y; lastDragTime = now;
    }

    protected void pointerReleased(int x, int y) {
        cancelLongPress();
        if (longPressed) { selectedLink = -1; repaint(); return; }   // long-press đã xử lý

        if (isDragging) {                                            // vuốt → fling
            if (Math.abs(velocity) > 1.5f) applyMomentum(velocity);
            return;
        }

        // ===== TAP =====
        boolean onLink = (y >= urlBarHeight()) && linkAt(x, y) >= 0;
        long now = System.currentTimeMillis();
        // Double-tap chỉ áp dụng ở vùng trống (tap vào link phải mở link ngay)
        if (!onLink && now - lastTapTime < 300
            && Math.abs(x - lastTapX) < touchSlop * 3 && Math.abs(y - lastTapY) < touchSlop * 3) {
            handleDoubleTap(x, y); lastTapTime = 0; return;
        }
        lastTapTime = now; lastTapX = x; lastTapY = y;
        handleTap(x, y);
    }

    void startLongPress(final int x, final int y) {
        cancelLongPress();
        if (timer == null) timer = new Timer();
        lpTask = new TimerTask() {
            public void run() {
                if (!isDragging) { longPressed = true; longPress(x, y); }
            }
        };
        timer.schedule(lpTask, 500);
    }

    void cancelLongPress() {
        if (lpTask != null) { lpTask.cancel(); lpTask = null; }
    }

    void applyMomentum(final float v0) {
        momentumCancelled = false;
        new Thread(new Runnable() {
            public void run() {
                float v = v0;
                while (Math.abs(v) > 0.5f && !momentumCancelled) {
                    scroll((int) v); v *= 0.90f;
                    try { Thread.sleep(16); } catch (Exception e) { break; }
                }
            }
        }).start();
    }

    // ── Tap — hit test trực tiếp, không scale ──
    void handleTap(int x, int y) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();
        selectedLink = -1;

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

        if ("home://".equals(t.url)) {
            int w = getWidth(), h = getHeight() - ubH;
            int col = Math.min(2, x * 3 / w);                 // clamp: tránh lệch ô khi w không chia hết cho 3
            int row = Math.min(2, (y - ubH) * 3 / h);
            int idx = row * 3 + col;
            if (idx >= 0 && idx < midlet.speedDial.size()) {
                String[] entry = (String[]) midlet.speedDial.elementAt(idx);
                t.url = entry[1]; t.image = null; t.links = null; t.scrollY = 0;
                midlet.loadAsync(entry[1]);
            }
            return;
        }

        int i = linkAt(x, y);
        if (i >= 0) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
            t.url = lr.url; t.image = null; t.links = null; t.scrollY = 0;
            midlet.loadAsync(lr.url);
        }
        repaint();
    }

    void handleDoubleTap(int x, int y) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.scrollY > 0) t.scrollY = 0;
        else if (t.image != null) {
            int viewH = contentViewH();
            int pageH = t.image.getHeight();
            t.scrollY = Math.max(0, Math.min(pageH / 2, pageH - viewH));
        }
        repaint();
    }

    // Nhấn giữ link → hiện URL (Alert tự tắt sau 3s)
    void longPress(int x, int y) {
        if (y < urlBarHeight()) return;
        MiniBrowser.Tab t = midlet.activeTab();
        int i = linkAt(x, y);
        if (i < 0) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
        Alert a = new Alert("Link", lr.url, null, AlertType.INFO);
        a.setTimeout(3000);
        midlet.display.setCurrent(a, this);
    }

    // ── Phím: điều khiển kiểu trình duyệt chuẩn (J2ME / feature phone) ──
    // UP/DOWN (2/8): cuộn trang
    // LEFT/RIGHT (4/6): chuyển focus link trước/sau
    // FIRE / 5 / 0: kích hoạt link đang chọn (mở URL)
    // * : về đầu trang
    // # : reload
    // Soft keys: Menu / Back (đã gắn Command)
    protected void keyPressed(int keyCode) {
        // Soft keys (Nokia-style and common mappings)
        if (keyCode == -6 || keyCode == 21) { // Left soft
            midlet.showMenu();
            return;
        }
        if (keyCode == -7 || keyCode == 22) { // Right soft
            midlet.goBack();
            return;
        }

        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}

        MiniBrowser.Tab t = midlet.activeTab();

        // Home / Speed Dial
        if ("home://".equals(t.url)) {
            handleHomeKeys(keyCode, action);
            return;
        }

        // Trang nội dung
        if (action == UP || keyCode == KEY_NUM2) {
            scroll(-32);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            scroll(32);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            moveLinkSelection(-1);
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            moveLinkSelection(1);
        } else if (action == FIRE || keyCode == KEY_NUM5 || keyCode == KEY_NUM0) {
            activateSelectedLink();
        } else if (keyCode == KEY_STAR) {
            t.scrollY = 0;
            selectedLink = -1;
            repaint();
        } else if (keyCode == KEY_POUND) {
            midlet.reload();
        }
    }

    protected void keyRepeated(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}
        if (action == UP || keyCode == KEY_NUM2) {
            scroll(-48);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            scroll(48);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            moveLinkSelection(-1);
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            moveLinkSelection(1);
        }
    }

    void handleHomeKeys(int keyCode, int action) {
        int n = midlet.speedDial.size();
        if (n == 0) return;

        // Số 1-9: mở thẳng speed dial
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM9) {
            int idx = keyCode - KEY_NUM1;
            if (idx < n) openSpeedDial(idx);
            return;
        }

        // D-pad điều hướng lưới 3x3
        int cols = 3;
        if (selectedLink < 0) selectedLink = 0;

        if (action == UP || keyCode == KEY_NUM2) {
            selectedLink = Math.max(0, selectedLink - cols);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            selectedLink = Math.min(n - 1, selectedLink + cols);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            if (selectedLink % cols != 0) selectedLink--;
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            if (selectedLink % cols != cols - 1 && selectedLink < n - 1) selectedLink++;
        } else if (action == FIRE || keyCode == KEY_NUM5 || keyCode == KEY_NUM0) {
            openSpeedDial(selectedLink);
            return;
        }
        repaint();
    }

    void openSpeedDial(int idx) {
        if (idx < 0 || idx >= midlet.speedDial.size()) return;
        String[] entry = (String[]) midlet.speedDial.elementAt(idx);
        MiniBrowser.Tab t = midlet.activeTab();
        t.url = entry[1];
        t.image = null;
        t.links = null;
        t.scrollY = 0;
        selectedLink = -1;
        midlet.loadAsync(entry[1]);
    }

    void moveLinkSelection(int delta) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.links == null || t.links.size() == 0) return;

        int n = t.links.size();
        if (selectedLink < 0) {
            selectedLink = (delta > 0) ? 0 : n - 1;
        } else {
            selectedLink = (selectedLink + delta + n) % n;
        }
        ensureVisible();
        repaint();
    }

    void activateSelectedLink() {
        MiniBrowser.Tab t = midlet.activeTab();
        if (selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) {
            // Nếu chưa chọn link nào → chọn link đầu tiên nhìn thấy hoặc link đầu
            if (t.links != null && t.links.size() > 0) {
                selectedLink = 0;
                ensureVisible();
                repaint();
            }
            return;
        }
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        t.url = lr.url;
        t.image = null;
        t.links = null;
        t.scrollY = 0;
        selectedLink = -1;
        midlet.loadAsync(lr.url);
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

