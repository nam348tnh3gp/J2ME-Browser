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
    static final int CACHE_MAX = 4;          // ảnh decode rất tốn RAM → giữ ít
    static final int HISTORY_MAX = 20;
    static final String HOME = "home://";
    static final String SEARCH_URL = "https://html.duckduckgo.com/html/?q=";

    static String proxyHost = DEFAULT_HOST;
    static int proxyPort = DEFAULT_PORT;

    Display display;
    BrowserCanvas canvas;
    Vector tabs = new Vector();
    int activeTab = 0;
    boolean firstRun = false;

    Command menuCmd, backCmd;

    Hashtable cache = new Hashtable();
    Vector cacheOrder = new Vector();
    volatile int loadGen = 0;                       // mỗi lần tải/huỷ tăng 1 → luồng cũ tự nhận ra mình đã hết hạn
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
        String url = HOME;
        Image image;
        Vector links;
        int scrollY = 0;         // pixel ảnh (== pixel màn hình vì ảnh khớp width)
        int pendingScroll = 0;   // vị trí cuộn cần khôi phục khi trang tải xong (Lùi / Reload)
        int lastBytes = 0;
        Vector history = new Vector();   // mỗi phần tử: Object[]{ String url, Integer scrollY }
        Tab() {}

        void push() {
            if (url == null) return;
            if (history.size() > 0) {
                Object[] top = (Object[]) history.elementAt(history.size() - 1);
                if (url.equals(top[0])) { top[1] = new Integer(scrollY); return; }
            }
            if (history.size() >= HISTORY_MAX) history.removeElementAt(0);
            history.addElement(new Object[]{ url, new Integer(scrollY) });
        }
    }

    static class LinkRect {
        int x1, y1, x2, y2; String url;
        LinkRect(int a, int b, int c, int d, String u) { x1=a; y1=b; x2=c; y2=d; url=u; }
    }

    public void startApp() {
        if (canvas != null) {            // quay lại từ pauseApp: giữ nguyên trạng thái, không dựng lại
            display.setCurrent(canvas);
            return;
        }
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
            canvas.setStatus("Chưa cấu hình proxy — Menu > Settings");
            promptSettings();
        }
    }

    public void pauseApp() {}
    public void destroyApp(boolean u) {
        abortCurrent();
        if (canvas != null) canvas.dispose();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == menuCmd) showMenu();
        else if (c == backCmd) goBack();
    }

    Tab activeTab() { return (Tab) tabs.elementAt(activeTab); }

    // ── Menu ──
    void showMenu() {
        List menu = new List("Menu", List.IMPLICIT);
        menu.append("URL / Tìm kiếm...", null);
        menu.append("Home", null);
        menu.append("New tab", null);
        menu.append("Tabs (" + tabs.size() + ")", null);
        menu.append("Reload", null);
        menu.append("Stop", null);
        menu.append("Bookmarks (" + bookmarks.size() + ")", null);
        menu.append("Save bookmark", null);
        menu.append("Clear cache", null);
        menu.append("Settings", null);
        menu.append("Phím tắt", null);
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
                    case 5: stopLoad(); break;
                    case 6: showBookmarks(); break;
                    case 7: saveBookmark(); break;
                    case 8: cacheClear(); canvas.setStatus("Đã xoá cache"); break;
                    case 9: promptSettings(); break;
                    case 10: showHelp(); break;
                    case 11: notifyDestroyed(); break;
                }
            }
        });
        display.setCurrent(menu);
    }

    void showHelp() {
        Form f = new Form("Phím tắt");
        f.append("Lên/Xuống (2/8): chọn link trước/sau\n"
               + "Trái/Phải (4/6): cuộn 1 trang\n"
               + "OK / 5 / 0: mở link đang chọn\n"
               + "*: đầu trang    #: cuối trang\n"
               + "1: nhập URL/tìm kiếm    3: trang chủ\n"
               + "7: tải lại/dừng    9: bookmark\n"
               + "Phím mềm trái: Menu\n"
               + "Phím mềm phải / Clear: Lùi\n"
               + "Cảm ứng: chạm mở link, kéo để cuộn,\n"
               + "giữ để xem URL, chạm đúp lên đầu trang");
        f.addCommand(new Command("Đóng", Command.BACK, 1));
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) { display.setCurrent(canvas); }
        });
        display.setCurrent(f);
    }

    // ── Điều hướng / lịch sử ──
    // Mọi lần mở URL mới đều đi qua đây để có lịch sử cho nút Lùi.
    void navigate(String url) {
        Tab t = activeTab();
        t.push();
        t.url = url; t.image = null; t.links = null; t.scrollY = 0; t.pendingScroll = 0;
        canvas.selectedLink = -1;
        loadAsync(url);
    }

    void goHome() {
        Tab t = activeTab();
        abortCurrent();
        canvas.stopLoading();
        if (!HOME.equals(t.url)) t.push();
        t.url = HOME; t.image = null; t.links = null; t.scrollY = 0; t.pendingScroll = 0;
        canvas.selectedLink = -1;
        canvas.repaint();
    }

    void goBack() {
        Tab t = activeTab();
        if (t.history.size() > 0) {
            int last = t.history.size() - 1;
            Object[] e = (Object[]) t.history.elementAt(last);
            t.history.removeElementAt(last);
            showEntry(t, (String) e[0], ((Integer) e[1]).intValue());
            return;
        }
        if (!HOME.equals(t.url)) { showEntry(t, HOME, 0); return; }
        if (tabs.size() > 1) closeActiveTab();
    }

    void showEntry(Tab t, String u, int scrollY) {
        abortCurrent();
        canvas.stopLoading();
        t.image = null; t.links = null; t.scrollY = 0;
        canvas.selectedLink = -1;
        t.url = u;
        if (HOME.equals(u)) { t.pendingScroll = 0; canvas.repaint(); return; }
        t.pendingScroll = scrollY;
        loadAsync(u);
    }

    void closeActiveTab() {
        if (tabs.size() <= 1) return;
        abortCurrent();
        canvas.stopLoading();
        tabs.removeElementAt(activeTab);
        if (activeTab >= tabs.size()) activeTab = tabs.size() - 1;
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
            String u = HOME.equals(t.url) ? "Home (Speed Dial)" : t.url;
            list.append((i == activeTab ? "● " : "  ") + (i + 1) + ": " + shorten(u, 30), null);
        }
        list.addCommand(new Command("Đóng tab này", Command.SCREEN, 1));
        list.addCommand(new Command("Hủy", Command.CANCEL, 2));
        final List l = list;
        list.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.CANCEL) display.setCurrent(canvas);
                else if (c.getLabel().equals("Đóng tab này")) {
                    closeActiveTab();
                    display.setCurrent(canvas);
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
        if (HOME.equals(t.url)) return;
        cacheRemove(t.url + "@" + canvas.getWidth());
        t.pendingScroll = t.scrollY;
        t.image = null; t.links = null; t.scrollY = 0;
        canvas.selectedLink = -1;
        loadAsync(t.url);
    }

    // ── Bookmarks ──
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
                display.setCurrent(canvas);
                if (idx >= 0) {
                    String[] bm = (String[]) bookmarks.elementAt(idx);
                    navigate(bm[1]);
                }
            }
        });
        display.setCurrent(list);
    }

    void saveBookmark() {
        Tab t = activeTab();
        if (HOME.equals(t.url) || t.url == null) { canvas.setStatus("Không có trang"); return; }
        for (int i = 0; i < bookmarks.size(); i++) {
            String[] bm = (String[]) bookmarks.elementAt(i);
            if (bm[1].equals(t.url)) { canvas.setStatus("Đã có bookmark"); return; }
        }
        bookmarks.addElement(new String[]{ shorten(t.url, 40), t.url });
        saveBookmarks();
        canvas.setStatus("Đã lưu bookmark");
    }

    // ── Lưu trữ RMS ──
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
        cacheClear();
        canvas.setStatus("Đã lưu: " + proxyHost + ":" + proxyPort);
        display.setCurrent(canvas);
    }

    // Nhập URL hoặc từ khoá tìm kiếm (giống thanh địa chỉ Opera Mini)
    void promptUrl() {
        Tab t = activeTab();
        String cur = HOME.equals(t.url) ? "" : t.url;
        final TextBox tb = new TextBox("URL hoặc từ khoá:", cur, 300, TextField.ANY);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                display.setCurrent(canvas);
                if (c.getCommandType() == Command.OK) {
                    String u = tb.getString().trim();
                    if (u.length() == 0) return;
                    navigate(resolveInput(u));
                }
            }
        });
        display.setCurrent(tb);
    }

    static String resolveInput(String u) {
        if (u.indexOf("://") >= 0) return u;
        boolean hostLike = u.indexOf(' ') < 0
            && (u.indexOf('.') > 0 || u.indexOf(':') > 0 || u.startsWith("localhost"));
        if (hostLike) return "http://" + u;
        return SEARCH_URL + urlEncode(u);
    }

    // ── Tải trang ──
    synchronized void abortCurrent() {
        loadGen++;
        SocketConnection old = currentConnection;
        currentConnection = null;
        if (old != null) try { old.close(); } catch (Exception e) {}
    }

    void stopLoad() {
        abortCurrent();
        canvas.stopLoading();
        canvas.setStatus("Đã dừng");
    }

    void loadAsync(final String url) {
        final Tab target = activeTab();      // trang tải xong sẽ vào đúng tab đã yêu cầu
        final int gen;
        synchronized (this) { abortCurrent(); gen = loadGen; }
        canvas.startLoading();
        new Thread(new Runnable() {
            public void run() { loadUrl(url, gen, target); }
        }).start();
    }

    void loadUrl(String url, int gen, Tab t) {
        int screenW = canvas.getWidth();
        String cacheKey = url + "@" + screenW;
        Object[] hit = cacheGet(cacheKey);
        if (hit != null) {
            if (gen == loadGen) applyPage(t, url, (Image) hit[0], (Vector) hit[1], url + " [cache]");
            return;
        }

        SocketConnection sc = null;
        try {
            sc = (SocketConnection) Connector.open("socket://" + proxyHost + ":" + proxyPort);
            try { sc.setSocketOption(SocketConnection.DELAY, 0); } catch (Exception e) {}
            synchronized (this) {
                if (gen != loadGen) { sc.close(); sc = null; return; }
                currentConnection = sc;
            }

            DataOutputStream out = new DataOutputStream(sc.openOutputStream());
            out.writeInt(screenW);
            out.writeInt(canvas.getHeight());
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length); out.write(ub); out.flush();

            DataInputStream in = new DataInputStream(sc.openInputStream());
            int w = in.readInt(); int h = in.readInt(); int len = in.readInt();
            if (len <= 0 || len > 5 * 1024 * 1024) throw new IOException("Kích thước sai");

            byte[] imgData = new byte[len];
            in.readFully(imgData);

            int linkCount = in.readInt();
            if (linkCount < 0 || linkCount > 2000) throw new IOException("Số link sai");
            Vector links = new Vector();
            for (int i = 0; i < linkCount; i++) {
                int x1 = in.readInt(), y1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt();
                int ul = in.readInt();
                if (ul < 0 || ul > 8192) throw new IOException("URL link sai");
                byte[] ubb = new byte[ul];
                in.readFully(ubb);
                links.addElement(new LinkRect(x1, y1, x2, y2, new String(ubb, "UTF-8")));
            }
            sortLinks(links);

            synchronized (this) { if (currentConnection == sc) currentConnection = null; }
            try { sc.close(); } catch (Exception e) {}
            sc = null;

            if (gen != loadGen) return;

            Image img = Image.createImage(imgData, 0, imgData.length);
            imgData = null;
            cachePut(cacheKey, img, links);
            if (gen != loadGen) return;

            t.lastBytes = len;
            applyPage(t, url, img, links, url + "  (" + (len / 1024) + " KB, " + links.size() + " link)");
        } catch (Exception e) {
            if (gen == loadGen) {
                String m = e.getMessage();
                if (m == null) m = e.toString();
                canvas.stopLoading();
                canvas.setStatus("Lỗi: " + m);
            }
        } catch (OutOfMemoryError oom) {
            cacheClear();
            if (gen == loadGen) {
                canvas.stopLoading();
                canvas.setStatus("Hết bộ nhớ — thử trang nhẹ hơn");
            }
        } finally {
            if (sc != null) try { sc.close(); } catch (Exception e) {}
            synchronized (this) { if (currentConnection == sc) currentConnection = null; }
        }
    }

    void applyPage(Tab t, String url, Image img, Vector links, String note) {
        canvas.stopLoading();
        if (tabs.indexOf(t) < 0) return;                   // tab đã bị đóng
        t.url = url; t.image = img; t.links = links;
        int max = Math.max(0, img.getHeight() - canvas.contentViewH());
        t.scrollY = Math.min(t.pendingScroll, max);
        t.pendingScroll = 0;
        if (t == activeTab()) {
            canvas.autoFocus();
            canvas.setStatus(note);
        }
        canvas.repaint();
    }

    // Sắp link theo vị trí (trên→dưới, trái→phải) để phím điều hướng đi đúng thứ tự nhìn thấy
    static void sortLinks(Vector v) {
        for (int i = 1; i < v.size(); i++) {
            LinkRect k = (LinkRect) v.elementAt(i);
            int j = i - 1;
            while (j >= 0 && linkBefore(k, (LinkRect) v.elementAt(j))) {
                v.setElementAt(v.elementAt(j), j + 1);
                j--;
            }
            v.setElementAt(k, j + 1);
        }
    }

    static boolean linkBefore(LinkRect a, LinkRect b) {
        int ba = a.y1 / 8, bb = b.y1 / 8;
        if (ba != bb) return ba < bb;
        return a.x1 < b.x1;
    }

    // ── Cache LRU ──
    synchronized Object[] cacheGet(String key) { return (Object[]) cache.get(key); }

    synchronized void cachePut(String key, Image img, Vector links) {
        if (cache.containsKey(key)) cacheOrder.removeElement(key);
        cache.put(key, new Object[]{ img, links });
        cacheOrder.addElement(key);
        while (cacheOrder.size() > CACHE_MAX) {
            cache.remove(cacheOrder.elementAt(0));
            cacheOrder.removeElementAt(0);
        }
    }

    synchronized void cacheRemove(String key) {
        cache.remove(key);
        cacheOrder.removeElement(key);
    }

    synchronized void cacheClear() {
        cache.clear();
        cacheOrder.removeAllElements();
    }

    // ── Tiện ích ──
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

    static String urlEncode(String s) {
        byte[] b;
        try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
        String hex = "0123456789ABCDEF";
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~') sb.append((char) c);
            else if (c == ' ') sb.append('+');
            else { sb.append('%'); sb.append(hex.charAt(c >> 4)); sb.append(hex.charAt(c & 15)); }
        }
        return sb.toString();
    }
}

// ═══════════════════════════════════════════════════════════════
//  BrowserCanvas — KHÔNG scale (ảnh khớp 1:1 màn hình)
//  Cảm ứng kiểu Chrome Android + phím điều hướng kiểu Opera Mini
// ═══════════════════════════════════════════════════════════════

class BrowserCanvas extends Canvas {
    MiniBrowser midlet;
    Font font;
    String status = "";
    int selectedLink = -1;   // trên trang: chỉ số link; ở Speed Dial: chỉ số ô

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
        // Thanh phím mềm (Menu/Lùi) do app tự vẽ → phải toàn màn hình, nếu không máy sẽ vẽ thêm
        // một thanh lệnh nữa (bị trùng) và phím mềm không tới được keyPressed.
        setFullScreenMode(true);
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

    void stopLoading() { loading = false; loadingThread = null; repaint(); }

    void dispose() {
        loading = false;
        momentumCancelled = true;
        cancelLongPress();
        if (timer != null) { timer.cancel(); timer = null; }
    }

    int urlBarHeight() {
        int h = font.getHeight() + 4;
        return h < URLBAR_H ? URLBAR_H : h;
    }

    int softBarHeight() {
        int h = font.getHeight() + 6;
        return h < 18 ? 18 : h;
    }

    int contentViewH() { return getHeight() - urlBarHeight() - softBarHeight(); }

    // Phải khớp với proxy: renderW = clamp(200..480, screenW)
    static int renderWidthFor(int w) { return Math.max(200, Math.min(480, w)); }

    // ── Đổi kích thước / xoay màn hình → tải lại theo width mới ──
    protected void sizeChanged(int w, int h) {
        if (midlet.tabs.size() == 0) return;
        MiniBrowser.Tab t = midlet.activeTab();
        selectedLink = -1;
        if (t.image != null && t.image.getWidth() != renderWidthFor(w)) {
            midlet.cacheClear();
            t.pendingScroll = 0;
            t.image = null; t.links = null; t.scrollY = 0;
            if (!MiniBrowser.HOME.equals(t.url)) midlet.loadAsync(t.url);
        }
        repaint();
    }

    // ── Paint ──
    protected void paint(Graphics g) {
        int ubH = urlBarHeight();
        MiniBrowser.Tab t = midlet.activeTab();

        g.setColor(0xFFFFFF); g.fillRect(0, 0, getWidth(), getHeight());

        int sbH = softBarHeight();
        int contentBottom = getHeight() - sbH;

        if (MiniBrowser.HOME.equals(t.url)) {
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
            String s = truncate(status, getWidth() - 8);
            int tw = font.stringWidth(s);
            g.drawString(s, (getWidth() - tw) / 2, getHeight() / 2 - font.getHeight() / 2,
                Graphics.TOP | Graphics.LEFT);
        }

        drawUrlBar(g, ubH, t);
        drawSoftBar(g, t);
    }

    // Thanh dưới: Menu | URL của link đang chọn | Lùi
    void drawSoftBar(Graphics g, MiniBrowser.Tab t) {
        int h = softBarHeight();
        int y = getHeight() - h;
        g.setColor(0xDDDDDD);
        g.fillRect(0, y, getWidth(), h);
        g.setColor(0xAAAAAA);
        g.drawLine(0, y, getWidth(), y);
        g.setColor(0x000000);
        g.setFont(font);
        String left = "Menu", right = "Lùi";
        int lw = font.stringWidth(left), rw = font.stringWidth(right);
        g.drawString(left, 4, y + 3, Graphics.TOP | Graphics.LEFT);
        g.drawString(right, getWidth() - rw - 4, y + 3, Graphics.TOP | Graphics.LEFT);
        if (selectedLink >= 0 && t.links != null && selectedLink < t.links.size()
            && !MiniBrowser.HOME.equals(t.url)) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
            int avail = getWidth() - lw - rw - 20;
            if (avail > 20) {
                g.setColor(0x0000AA);
                g.drawString(truncate(lr.url, avail), lw + 10, y + 3, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    void drawUrlBar(Graphics g, int h, MiniBrowser.Tab t) {
        g.setColor(loading ? 0xAADDFF : 0xEEEEEE);
        g.fillRect(0, 0, getWidth(), h);
        g.setColor(0xCCCCCC); g.drawLine(0, h - 1, getWidth(), h - 1);
        int btnW = 14;
        g.setColor(0x000000); g.setFont(font);
        g.drawString("◄", 2, 2, Graphics.TOP | Graphics.LEFT);
        g.drawString(loading ? "×" : "↻", getWidth() - btnW, 2, Graphics.TOP | Graphics.LEFT);
        String shown = MiniBrowser.HOME.equals(t.url) ? "Speed Dial" : t.url;
        if (loading) shown = "Đang tải" + dots(loadingFrame);
        shown = truncate(shown, getWidth() - 2 * btnW - 6);
        g.setColor(loading ? 0x000088 : 0x333333);
        g.drawString(shown, btnW + 2, 2, Graphics.TOP | Graphics.LEFT);
    }

    void drawLinkHighlight(Graphics g, MiniBrowser.Tab t, int top) {
        if (selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        int bottom = getHeight() - softBarHeight();
        int y1 = top + (lr.y1 - t.scrollY);
        int y2 = top + (lr.y2 - t.scrollY);
        if (y2 < top || y1 > bottom) return;
        if (y1 < top) y1 = top;
        if (y2 > bottom) y2 = bottom;
        if (y2 <= y1) return;
        int x1 = Math.max(1, lr.x1);
        int x2 = Math.min(getWidth() - 2, lr.x2);
        if (x2 <= x1) x2 = x1 + 20;
        g.setColor(0xFF6600);
        g.drawRect(x1 - 1, y1 - 1, (x2 - x1) + 2, (y2 - y1) + 2);
        g.drawRect(x1, y1, x2 - x1, y2 - y1);
        g.fillRect(x1, y1, 12, 10);
        g.setColor(0xFFFFFF);
        g.setFont(font);
        g.drawString(String.valueOf(selectedLink + 1), x1 + 1, y1, Graphics.TOP | Graphics.LEFT);
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
            if (i == selectedLink) shade = 0xFF8800;
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

    // Ô Speed Dial tại toạ độ (x, y) — dùng đúng công thức chia ô như drawSpeedDial
    int dialIndexAt(int x, int y) {
        int top = urlBarHeight(), bottom = getHeight() - softBarHeight();
        int cellW = getWidth() / 3, cellH = (bottom - top) / 3;
        if (cellW <= 0 || cellH <= 0 || y < top || y >= bottom) return -1;
        int col = Math.min(2, x / cellW), row = Math.min(2, (y - top) / cellH);
        return row * 3 + col;
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
        int imgH = t.image.getHeight();
        t.scrollY += dy;
        if (t.scrollY < 0) t.scrollY = 0;
        int max = Math.max(0, imgH - viewH);
        if (t.scrollY > max) t.scrollY = max;
        repaint();
    }

    // ═══════════════════════════════════════════════════════════
    //  TOUCH — kiểu Chrome Android
    // ═══════════════════════════════════════════════════════════

    /** Tìm link tại toạ độ màn hình. Vùng bấm nới theo độ rộng màn hình,
     *  nhiều link chồng nhau thì chọn link có tâm gần điểm chạm nhất. */
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

    boolean inContent(int y) {
        return y >= urlBarHeight() && y < getHeight() - softBarHeight();
    }

    protected void pointerPressed(int x, int y) {
        momentumCancelled = true;                       // chạm vào để dừng fling
        touchSlop = Math.max(6, Math.min(getWidth(), getHeight()) / 30);
        pressX = x; pressY = y; pressTime = System.currentTimeMillis();
        lastDragY = y; lastDragTime = pressTime;
        velocity = 0; isDragging = false; longPressed = false;
        if (inContent(y)) {
            if (MiniBrowser.HOME.equals(midlet.activeTab().url)) {
                int idx = dialIndexAt(x, y);
                selectedLink = (idx >= 0 && idx < midlet.speedDial.size()) ? idx : -1;
            } else {
                selectedLink = linkAt(x, y);            // phản hồi ngay khi chạm
            }
        } else {
            selectedLink = -1;
        }
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
        if (longPressed) { selectedLink = -1; repaint(); return; }

        if (isDragging) {                                            // vuốt → fling
            if (Math.abs(velocity) > 1.5f) applyMomentum(velocity);
            return;
        }

        // ===== TAP =====
        boolean onLink = inContent(y) && linkAt(x, y) >= 0;
        long now = System.currentTimeMillis();
        // Double-tap chỉ áp dụng ở vùng trống (tap vào link phải mở link ngay)
        if (!onLink && inContent(y) && now - lastTapTime < 300
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

        // Thanh phím mềm (vì đã toàn màn hình nên phải tự xử lý chạm)
        if (y >= getHeight() - softBarHeight()) {
            if (x < getWidth() / 2) midlet.showMenu();
            else midlet.goBack();
            return;
        }

        if (y < ubH) {
            int btnW = 14;
            if (x < btnW + 4) { midlet.goBack(); return; }
            if (x > getWidth() - btnW - 4) {
                if (loading) midlet.stopLoad();
                else midlet.reload();
                return;
            }
            midlet.promptUrl();
            return;
        }

        if (MiniBrowser.HOME.equals(t.url)) {
            int idx = dialIndexAt(x, y);
            if (idx >= 0 && idx < midlet.speedDial.size()) openSpeedDial(idx);
            else repaint();
            return;
        }

        int i = linkAt(x, y);
        if (i >= 0) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
            midlet.navigate(lr.url);
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
        if (!inContent(y)) return;
        MiniBrowser.Tab t = midlet.activeTab();
        int i = linkAt(x, y);
        if (i < 0) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
        Alert a = new Alert("Link", lr.url, null, AlertType.INFO);
        a.setTimeout(3000);
        midlet.display.setCurrent(a, this);
    }

    // ═══════════════════════════════════════════════════════════
    //  PHÍM — kiểu Opera Mini
    //   Lên/Xuống (2/8) : chọn link trước/sau (ô cam), tự cuộn theo
    //   Trái/Phải (4/6) : cuộn 1 trang
    //   OK / 5 / 0      : mở link đang chọn
    //   *               : đầu trang      #: cuối trang
    //   1: URL/tìm kiếm  3: trang chủ  7: tải lại/dừng  9: bookmark
    //   Phím mềm trái: Menu   Phím mềm phải / Clear: Lùi
    // ═══════════════════════════════════════════════════════════
    protected void keyPressed(int keyCode) {
        momentumCancelled = true;

        if (keyCode == -6 || keyCode == -21 || keyCode == 21) { midlet.showMenu(); return; }
        if (keyCode == -7 || keyCode == -22 || keyCode == 22 || keyCode == -8) { midlet.goBack(); return; }

        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}

        MiniBrowser.Tab t = midlet.activeTab();

        if (MiniBrowser.HOME.equals(t.url)) {
            handleHomeKeys(keyCode, action);
            return;
        }

        int viewH = contentViewH();
        boolean hasLinks = t.links != null && t.links.size() > 0;

        if (action == UP || keyCode == KEY_NUM2) {
            if (hasLinks) moveLinkSelection(-1); else scroll(-32);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            if (hasLinks) moveLinkSelection(1); else scroll(32);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            scroll(-(viewH - 20));
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            scroll(viewH - 20);
        } else if (action == FIRE || keyCode == KEY_NUM5 || keyCode == KEY_NUM0) {
            activateSelectedLink();
        } else if (keyCode == KEY_STAR) {
            t.scrollY = 0;
            autoFocus();
            repaint();
        } else if (keyCode == KEY_POUND) {
            if (t.image != null) {
                t.scrollY = Math.max(0, t.image.getHeight() - viewH);
                selectedLink = hasLinks ? firstVisibleLink(t, false) : -1;
                repaint();
            }
        } else if (keyCode == KEY_NUM1) {
            midlet.promptUrl();
        } else if (keyCode == KEY_NUM3) {
            midlet.goHome();
        } else if (keyCode == KEY_NUM7) {
            if (loading) midlet.stopLoad(); else midlet.reload();
        } else if (keyCode == KEY_NUM9) {
            midlet.showBookmarks();
        }
    }

    protected void keyRepeated(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}
        MiniBrowser.Tab t = midlet.activeTab();
        if (MiniBrowser.HOME.equals(t.url)) return;
        int viewH = contentViewH();
        boolean hasLinks = t.links != null && t.links.size() > 0;
        if (action == UP || keyCode == KEY_NUM2) {
            if (hasLinks) moveLinkSelection(-1); else scroll(-48);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            if (hasLinks) moveLinkSelection(1); else scroll(48);
        } else if (action == LEFT || keyCode == KEY_NUM4) {
            scroll(-(viewH - 20));
        } else if (action == RIGHT || keyCode == KEY_NUM6) {
            scroll(viewH - 20);
        }
    }

    void handleHomeKeys(int keyCode, int action) {
        int n = midlet.speedDial.size();
        if (n == 0) return;

        // Số 1-9: mở thẳng ô speed dial
        if (keyCode >= KEY_NUM1 && keyCode <= KEY_NUM9) {
            int idx = keyCode - KEY_NUM1;
            if (idx < n) openSpeedDial(idx);
            return;
        }

        int cols = 3;
        if (selectedLink < 0 || selectedLink >= n) selectedLink = 0;

        if (action == UP) {
            selectedLink = Math.max(0, selectedLink - cols);
        } else if (action == DOWN) {
            selectedLink = Math.min(n - 1, selectedLink + cols);
        } else if (action == LEFT) {
            if (selectedLink % cols != 0) selectedLink--;
        } else if (action == RIGHT) {
            if (selectedLink % cols != cols - 1 && selectedLink < n - 1) selectedLink++;
        } else if (action == FIRE || keyCode == KEY_NUM0) {
            openSpeedDial(selectedLink);
            return;
        }
        repaint();
    }

    void openSpeedDial(int idx) {
        if (idx < 0 || idx >= midlet.speedDial.size()) return;
        String[] entry = (String[]) midlet.speedDial.elementAt(idx);
        selectedLink = -1;
        midlet.navigate(entry[1]);
    }

    // ── Chọn link ──
    boolean isLinkVisible(MiniBrowser.Tab t, MiniBrowser.LinkRect lr) {
        return lr.y2 >= t.scrollY && lr.y1 <= t.scrollY + contentViewH();
    }

    // Link nhìn thấy đầu tiên (fromTop) hoặc cuối cùng; -1 nếu không có (links đã sắp theo vị trí)
    int firstVisibleLink(MiniBrowser.Tab t, boolean fromTop) {
        if (t.links == null) return -1;
        int n = t.links.size();
        for (int k = 0; k < n; k++) {
            int i = fromTop ? k : n - 1 - k;
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(i);
            if (isLinkVisible(t, lr)) return i;
        }
        return -1;
    }

    // Gọi khi trang vừa tải xong: đặt con trỏ ở link đầu tiên nhìn thấy (không tự cuộn trang)
    void autoFocus() {
        MiniBrowser.Tab t = midlet.activeTab();
        selectedLink = firstVisibleLink(t, true);
    }

    void moveLinkSelection(int delta) {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.links == null || t.links.size() == 0) return;
        int n = t.links.size();

        boolean valid = selectedLink >= 0 && selectedLink < n
            && isLinkVisible(t, (MiniBrowser.LinkRect) t.links.elementAt(selectedLink));

        if (!valid) {
            // Chưa chọn, hoặc link đang chọn đã cuộn khỏi màn hình → bắt đầu từ link nhìn thấy
            int i = firstVisibleLink(t, delta > 0);
            selectedLink = (i >= 0) ? i : (delta > 0 ? 0 : n - 1);
        } else {
            // Một link xuống nhiều dòng có thể có nhiều vùng cùng URL → bỏ qua các vùng trùng
            String cur = ((MiniBrowser.LinkRect) t.links.elementAt(selectedLink)).url;
            int i = selectedLink;
            for (int k = 0; k < n; k++) {
                i = (i + delta + n) % n;
                if (!cur.equals(((MiniBrowser.LinkRect) t.links.elementAt(i)).url)) break;
            }
            selectedLink = i;
        }
        ensureVisible();
        repaint();
    }

    void activateSelectedLink() {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.links == null || t.links.size() == 0) return;
        if (selectedLink < 0 || selectedLink >= t.links.size()) {
            autoFocus();
            if (selectedLink < 0) { selectedLink = 0; ensureVisible(); }
            repaint();
            return;
        }
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        if (!isLinkVisible(t, lr)) {                 // link đã cuộn khỏi màn hình → đưa vào tầm nhìn trước
            ensureVisible();
            repaint();
            return;
        }
        selectedLink = -1;
        midlet.navigate(lr.url);
    }

    void ensureVisible() {
        MiniBrowser.Tab t = midlet.activeTab();
        if (t.image == null || selectedLink < 0 || t.links == null || selectedLink >= t.links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) t.links.elementAt(selectedLink);
        int viewH = contentViewH();
        if (lr.y1 < t.scrollY) t.scrollY = lr.y1 - 8;
        else if (lr.y2 > t.scrollY + viewH) t.scrollY = lr.y2 - viewH + 8;
        if (t.scrollY < 0) t.scrollY = 0;
        int max = Math.max(0, t.image.getHeight() - viewH);
        if (t.scrollY > max) t.scrollY = max;
        repaint();
    }
}
