import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import java.io.*;
import java.util.*;

public class MiniBrowser extends MIDlet implements CommandListener {

    static final String DEFAULT_HOST = "192.168.1.100";
    static final int DEFAULT_PORT = 9999;
    static final String RMS_NAME = "MiniBrowserCfg";
    static final int CACHE_MAX = 10;

    static String proxyHost = DEFAULT_HOST;
    static int proxyPort = DEFAULT_PORT;

    Display display;
    BrowserCanvas canvas;
    Command exitCmd, openCmd, backCmd, refreshCmd, settingsCmd, stopCmd;
    String currentUrl = "http://example.com/";
    Vector history = new Vector();
    boolean firstRun = false;

    Hashtable cache = new Hashtable();
    volatile boolean stopRequested = false;
    volatile SocketConnection currentConnection = null;

    static class PageData {
        Image img; Vector links;
        PageData(Image i, Vector l) { img = i; links = l; }
    }

    public void startApp() {
        display = Display.getDisplay(this);
        firstRun = !loadSettings();

        exitCmd     = new Command("Thoát", Command.EXIT, 1);
        openCmd     = new Command("URL", Command.SCREEN, 1);
        backCmd     = new Command("Lùi", Command.BACK, 2);
        refreshCmd  = new Command("Tải lại", Command.SCREEN, 3);
        settingsCmd = new Command("Cấu hình", Command.SCREEN, 4);
        stopCmd     = new Command("Dừng", Command.STOP, 1);

        canvas = new BrowserCanvas(this);
        canvas.addCommand(exitCmd);
        canvas.addCommand(openCmd);
        canvas.addCommand(backCmd);
        canvas.addCommand(refreshCmd);
        canvas.addCommand(settingsCmd);
        canvas.addCommand(stopCmd);
        canvas.setCommandListener(this);

        display.setCurrent(canvas);

        if (firstRun) {
            canvas.setStatus("Chưa cấu hình proxy — mở menu > Cấu hình");
            promptSettings();
        } else {
            loadAsync(currentUrl);
        }
    }

    public void pauseApp() {}
    public void destroyApp(boolean u) { abortCurrent(); }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) { abortCurrent(); notifyDestroyed(); }
        else if (c == openCmd) promptUrl();
        else if (c == refreshCmd) { cache.remove(currentUrl); loadAsync(currentUrl); }
        else if (c == settingsCmd) promptSettings();
        else if (c == stopCmd) { abortCurrent(); canvas.setStatus("Đã dừng"); }
        else if (c == backCmd) {
            if (!history.isEmpty()) {
                currentUrl = (String) history.lastElement();
                history.removeElementAt(history.size() - 1);
                loadAsync(currentUrl);
            }
        }
    }

    boolean loadSettings() {
        RecordStore rs = null;
        boolean exists = false;
        try {
            rs = RecordStore.openRecordStore(RMS_NAME, true);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                String s = new String(data, "UTF-8");
                int idx = s.lastIndexOf(':');
                if (idx > 0) {
                    proxyHost = s.substring(0, idx);
                    proxyPort = Integer.parseInt(s.substring(idx + 1));
                    exists = true;
                }
            }
        } catch (Exception e) { System.err.println("loadSettings: " + e); }
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
        return exists;
    }

    void saveSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_NAME, true);
            byte[] data = (proxyHost + ":" + proxyPort).getBytes("UTF-8");
            if (rs.getNumRecords() > 0) rs.setRecord(1, data, 0, data.length);
            else rs.addRecord(data, 0, data.length);
        } catch (Exception e) { canvas.setStatus("Lỗi lưu: " + e.getMessage()); }
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    void promptSettings() {
        final String initial = proxyHost + ":" + proxyPort;
        final TextBox tb = new TextBox("Proxy (host:port)", initial, 100, TextField.URL);
        tb.addCommand(new Command("Lưu", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.addCommand(new Command("Mặc định", Command.SCREEN, 3));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) applyProxyInput(tb.getString());
                else if (c.getLabel().equals("Mặc định")) {
                    proxyHost = DEFAULT_HOST;
                    proxyPort = DEFAULT_PORT;
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
        if (s.length() == 0) { canvas.setStatus("Địa chỉ trống"); display.setCurrent(canvas); return; }
        String newHost;
        int newPort;
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) { newHost = s; newPort = DEFAULT_PORT; }
        else {
            newHost = s.substring(0, idx).trim();
            try { newPort = Integer.parseInt(s.substring(idx + 1).trim()); }
            catch (NumberFormatException ne) { canvas.setStatus("Port không hợp lệ"); display.setCurrent(canvas); return; }
        }
        if (newHost.length() == 0 || newPort < 1 || newPort > 65535) {
            canvas.setStatus("Host/port không hợp lệ"); display.setCurrent(canvas); return;
        }
        proxyHost = newHost;
        proxyPort = newPort;
        saveSettings();
        canvas.setStatus("Đã lưu: " + proxyHost + ":" + proxyPort);
        display.setCurrent(canvas);
    }

    void promptUrl() {
        final TextBox tb = new TextBox("URL:", currentUrl, 200, TextField.URL);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) {
                    history.addElement(currentUrl);
                    String u = tb.getString();
                    if (u.indexOf("://") < 0) u = "http://" + u;
                    currentUrl = u;
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
        new Thread(new Runnable() {
            public void run() { loadUrl(url); }
        }).start();
    }

    void loadUrl(String url) {
        PageData cached = (PageData) cache.get(url);
        if (cached != null) {
            canvas.stopLoading();
            canvas.setPage(cached.img, cached.links);
            canvas.setStatus(url + "  [cache]");
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

            canvas.setStatus("Qua " + proxyHost + ":" + proxyPort);

            DataOutputStream out = new DataOutputStream(sc.openOutputStream());
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length); out.write(ub); out.flush();

            DataInputStream in = new DataInputStream(sc.openInputStream());
            int w = in.readInt();
            int h = in.readInt();
            int len = in.readInt();
            if (len <= 0 || len > 5 * 1024 * 1024) throw new IOException("Bad image size");

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
            PageData pd = new PageData(img, links);
            if (cache.size() > CACHE_MAX) cache.clear();
            cache.put(url, pd);

            canvas.stopLoading();
            canvas.setPage(img, links);
            canvas.setStatus(url);
        } catch (Exception e) {
            canvas.stopLoading();
            if (stopRequested) canvas.setStatus("Đã dừng");
            else { canvas.setStatus("Lỗi: " + e.getMessage()); e.printStackTrace(); }
        } finally {
            if (sc != null) try { sc.close(); } catch (Exception e) {}
            synchronized (this) { if (currentConnection == sc) currentConnection = null; }
        }
    }

    static class LinkRect {
        int x1, y1, x2, y2; String url;
        LinkRect(int a, int b, int c, int d, String u) { x1=a; y1=b; x2=c; y2=d; url=u; }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Canvas với touch đầy đủ + UI/UX nâng cao
// ═══════════════════════════════════════════════════════════════

class BrowserCanvas extends Canvas {
    MiniBrowser midlet;
    Image page;
    Vector links = new Vector();
    int scrollY = 0;
    int selectedLink = -1;
    String status = "Sẵn sàng";
    Font font;

    // Touch state
    private int pressX, pressY;
    private long pressTime;
    private int lastDragY;
    private long lastDragTime;
    private float velocity;
    private boolean isDragging;

    // Double tap
    private long lastTapTime;
    private int lastTapX, lastTapY;

    // Momentum
    private volatile boolean momentumCancelled = false;

    // Loading
    private volatile boolean loading = false;
    private volatile int loadingFrame = 0;
    private Thread loadingThread;

    BrowserCanvas(MiniBrowser m) {
        midlet = m;
        font = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    void setStatus(String s) { status = s; repaint(); }

    void setPage(Image img, Vector l) {
        page = img;
        links = l;
        scrollY = 0;
        selectedLink = -1;
        stopLoading();
        repaint();
    }

    // ── Loading animation ──
    void startLoading() {
        if (loading) return;
        loading = true;
        loadingFrame = 0;
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

    void stopLoading() {
        loading = false;
        loadingThread = null;
    }

    // ── Touch: press ──
    protected void pointerPressed(int x, int y) {
        momentumCancelled = true;   // dừng quán tính nếu có
        pressX = x; pressY = y;
        pressTime = System.currentTimeMillis();
        lastDragY = y;
        lastDragTime = pressTime;
        velocity = 0;
        isDragging = false;
    }

    // ── Touch: drag ──
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
        lastDragY = y;
        lastDragTime = now;
    }

    // ── Touch: release ──
    protected void pointerReleased(int x, int y) {
        long duration = System.currentTimeMillis() - pressTime;
        int dx = Math.abs(x - pressX);
        int dy = Math.abs(y - pressY);

        // Swipe với quán tính
        if (isDragging) {
            if (Math.abs(velocity) > 1.5f) applyMomentum(velocity);
            return;
        }

        // Long press (>500ms, không di chuyển)
        if (duration > 500 && dx < 15 && dy < 15) { longPress(x, y); return; }

        // Double tap
        long now = System.currentTimeMillis();
        if (now - lastTapTime < 400 && Math.abs(x - lastTapX) < 40 && Math.abs(y - lastTapY) < 40) {
            handleDoubleTap(x, y);
            lastTapTime = 0;
            return;
        }

        // Single tap
        lastTapTime = now;
        lastTapX = x; lastTapY = y;
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
        int sb = font.getHeight() + 2;
        int viewH = getHeight() - sb;

        // Link hit test
        int py = y - sb + scrollY;
        for (int i = 0; i < links.size(); i++) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(i);
            if (x >= lr.x1 && x <= lr.x2 && py >= lr.y1 && py <= lr.y2) {
                selectedLink = i;
                repaint();
                activateLink(i);
                return;
            }
        }

        // Tap zones
        int tapY = y - sb;
        if (tapY < viewH / 4) scroll(-viewH * 2 / 3);
        else if (tapY > viewH * 3 / 4) scroll(viewH * 2 / 3);
    }

    void handleDoubleTap(int x, int y) {
        // Về đầu nếu đã scroll; ngược lại nhảy giữa trang
        if (scrollY > 0) scrollY = 0;
        else {
            int viewH = getHeight() - font.getHeight() - 2;
            int pageH = (page != null) ? page.getHeight() : 0;
            scrollY = Math.max(0, Math.min(pageH / 2, pageH - viewH));
        }
        repaint();
    }

    void longPress(int x, int y) {
        int sb = font.getHeight() + 2;
        int py = y - sb + scrollY;
        for (int i = 0; i < links.size(); i++) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(i);
            if (x >= lr.x1 && x <= lr.x2 && py >= lr.y1 && py <= lr.y2) {
                setStatus(lr.url);
                return;
            }
        }
    }

    // ── Paint ──
    protected void paint(Graphics g) {
        int sb = font.getHeight() + 2;
        int viewH = getHeight() - sb;

        // Background
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, getWidth(), getHeight());

        // Page content
        if (page != null) {
            g.drawImage(page, 0, sb - scrollY, Graphics.TOP | Graphics.LEFT);
        } else if (loading) {
            g.setColor(0x888888);
            g.setFont(font);
            String msg = "Đang tải" + dots(loadingFrame);
            int tw = font.stringWidth(msg);
            g.drawString(msg, (getWidth() - tw) / 2,
                getHeight() / 2 - font.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        }

        // Link highlight (chỉ vẽ nếu rect nằm trong viewport)
        if (selectedLink >= 0 && selectedLink < links.size()) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(selectedLink);
            int y1 = lr.y1 + sb - scrollY - 1;
            int y2 = lr.y2 + sb - scrollY + 1;
            if (y2 >= sb && y1 <= getHeight()) {
                g.setColor(0xFF8800);
                g.drawRect(lr.x1 - 1, y1, (lr.x2 - lr.x1) + 2, y2 - y1);
                g.drawRect(lr.x1 - 2, y1 - 1, (lr.x2 - lr.x1) + 4, (y2 - y1) + 2);
            }
        }

        // Scrollbar
        if (page != null && page.getHeight() > viewH && viewH > 10) {
            int sbW = 3;
            int trackH = viewH - 4;
            int thumbH = Math.max(15, trackH * viewH / page.getHeight());
            int maxScroll = Math.max(1, page.getHeight() - viewH);
            int thumbY = sb + 2 + (trackH - thumbH) * scrollY / maxScroll;
            g.setColor(0xAAAAAA);
            g.fillRect(getWidth() - sbW - 1, thumbY, sbW, thumbH);
        }

        // Status bar
        g.setColor(loading ? 0x99CCFF : 0xDDDDDD);
        g.fillRect(0, 0, getWidth(), sb);
        g.setColor(0x000000);
        g.setFont(font);
        String displayStatus = loading ? ("Đang tải" + dots(loadingFrame)) : status;
        g.drawString(truncate(displayStatus, getWidth() - 4), 2, 1, Graphics.TOP | Graphics.LEFT);
    }

    String dots(int n) {
        switch (n) {
            case 1: return ".";
            case 2: return "..";
            case 3: return "...";
            default: return "";
        }
    }

    String truncate(String s, int maxW) {
        if (s == null) return "";
        if (font.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && font.stringWidth(s + "...") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    void scroll(int dy) {
        int sb = font.getHeight() + 2;
        int pageH = (page != null) ? page.getHeight() : 0;
        int viewH = getHeight() - sb;
        scrollY += dy;
        if (scrollY < 0) scrollY = 0;
        int maxScroll = Math.max(0, pageH - viewH);
        if (scrollY > maxScroll) scrollY = maxScroll;
        repaint();
    }

    protected void keyPressed(int keyCode) {
        int action = 0;
        try { action = getGameAction(keyCode); } catch (Exception e) {}

        if (action == UP || keyCode == KEY_NUM2) scroll(-20);
        else if (action == DOWN || keyCode == KEY_NUM8) scroll(20);
        else if (action == LEFT) selectPrevLink();
        else if (action == RIGHT) selectNextLink();
        else if (action == FIRE || keyCode == KEY_NUM5) {
            if (selectedLink >= 0) activateLink(selectedLink);
            else selectNextLink();
        }
    }

    void activateLink(int idx) {
        if (idx < 0 || idx >= links.size()) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(idx);
        midlet.history.addElement(midlet.currentUrl);
        midlet.currentUrl = lr.url;
        midlet.loadAsync(lr.url);
    }

    void selectNextLink() {
        if (links.size() == 0) return;
        selectedLink = (selectedLink + 1) % links.size();
        ensureVisible();
    }

    void selectPrevLink() {
        if (links.size() == 0) return;
        selectedLink = (selectedLink - 1 + links.size()) % links.size();
        ensureVisible();
    }

    void ensureVisible() {
        if (selectedLink < 0) return;
        MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(selectedLink);
        int sb = font.getHeight() + 2;
        int viewH = getHeight() - sb;
        if (lr.y1 < scrollY) scrollY = lr.y1;
        else if (lr.y2 > scrollY + viewH) scrollY = lr.y2 - viewH + 8;
        if (scrollY < 0) scrollY = 0;
        repaint();
    }
}
