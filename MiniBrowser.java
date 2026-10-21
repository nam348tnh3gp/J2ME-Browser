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

    // ── Cache + stop ──
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
    public void destroyApp(boolean u) {
        abortCurrent();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            abortCurrent();
            notifyDestroyed();
        } else if (c == openCmd) {
            promptUrl();
        } else if (c == refreshCmd) {
            cache.remove(currentUrl);   // buộc tải lại tươi
            loadAsync(currentUrl);
        } else if (c == settingsCmd) {
            promptSettings();
        } else if (c == stopCmd) {
            abortCurrent();
            canvas.setStatus("Đã dừng");
        } else if (c == backCmd) {
            if (!history.isEmpty()) {
                currentUrl = (String) history.lastElement();
                history.removeElementAt(history.size() - 1);
                loadAsync(currentUrl);
            }
        }
    }

    // ── RMS ──

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
        } catch (Exception e) {
            System.err.println("loadSettings: " + e);
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
        return exists;
    }

    void saveSettings() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_NAME, true);
            byte[] data = (proxyHost + ":" + proxyPort).getBytes("UTF-8");
            if (rs.getNumRecords() > 0) {
                rs.setRecord(1, data, 0, data.length);
            } else {
                rs.addRecord(data, 0, data.length);
            }
        } catch (Exception e) {
            canvas.setStatus("Lỗi lưu cấu hình: " + e.getMessage());
        } finally {
            if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {}
        }
    }

    // ── Cấu hình proxy ──

    void promptSettings() {
        final String initial = proxyHost + ":" + proxyPort;
        final TextBox tb = new TextBox(
            "Proxy server (host:port)", initial, 100, TextField.URL);
        tb.addCommand(new Command("Lưu", Command.OK, 1));
        tb.addCommand(new Command("Hủy", Command.CANCEL, 2));
        tb.addCommand(new Command("Mặc định", Command.SCREEN, 3));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getCommandType() == Command.OK) {
                    applyProxyInput(tb.getString());
                } else if (c.getLabel().equals("Mặc định")) {
                    proxyHost = DEFAULT_HOST;
                    proxyPort = DEFAULT_PORT;
                    saveSettings();
                    canvas.setStatus("Đã đặt lại mặc định: " + proxyHost + ":" + proxyPort);
                    display.setCurrent(canvas);
                } else {
                    display.setCurrent(canvas);
                }
            }
        });
        display.setCurrent(tb);
    }

    void applyProxyInput(String raw) {
        String s = raw.trim();
        if (s.length() == 0) {
            canvas.setStatus("Địa chỉ trống");
            display.setCurrent(canvas);
            return;
        }
        String newHost;
        int newPort;
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx == s.length() - 1) {
            newHost = s;
            newPort = DEFAULT_PORT;
        } else {
            newHost = s.substring(0, idx).trim();
            try {
                newPort = Integer.parseInt(s.substring(idx + 1).trim());
            } catch (NumberFormatException ne) {
                canvas.setStatus("Port không hợp lệ");
                display.setCurrent(canvas);
                return;
            }
        }
        if (newHost.length() == 0 || newPort < 1 || newPort > 65535) {
            canvas.setStatus("Host/port không hợp lệ");
            display.setCurrent(canvas);
            return;
        }
        proxyHost = newHost;
        proxyPort = newPort;
        saveSettings();
        canvas.setStatus("Đã lưu: " + proxyHost + ":" + proxyPort);
        display.setCurrent(canvas);
    }

    // ── Tải URL ──

    void promptUrl() {
        final TextBox tb = new TextBox("Nhập URL:", currentUrl, 200, TextField.URL);
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

    /** Hủy request đang chạy (nếu có). */
    synchronized void abortCurrent() {
        stopRequested = true;
        SocketConnection old = currentConnection;
        currentConnection = null;
        if (old != null) {
            try { old.close(); } catch (Exception e) {}
        }
    }

    void loadAsync(final String url) {
        abortCurrent();
        new Thread(new Runnable() {
            public void run() { loadUrl(url); }
        }).start();
    }

    void loadUrl(String url) {
        // Cache hit → hiển thị ngay
        PageData cached = (PageData) cache.get(url);
        if (cached != null) {
            canvas.setPage(cached.img, cached.links);
            canvas.setStatus(url + " [cache]");
            return;
        }

        stopRequested = false;
        SocketConnection sc = null;
        try {
            sc = (SocketConnection) Connector.open(
                "socket://" + proxyHost + ":" + proxyPort);

            synchronized (this) {
                if (stopRequested) { sc.close(); return; }
                currentConnection = sc;
            }

            canvas.setStatus("Qua " + proxyHost + ":" + proxyPort + " — " + url);

            DataOutputStream out = new DataOutputStream(sc.openOutputStream());
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length);
            out.write(ub);
            out.flush();

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

            synchronized (this) {
                if (currentConnection == sc) currentConnection = null;
            }
            try { sc.close(); } catch (Exception e) {}
            sc = null;

            if (stopRequested) return;

            Image img = Image.createImage(imgData, 0, imgData.length);
            PageData pd = new PageData(img, links);
            if (cache.size() > CACHE_MAX) cache.clear();
            cache.put(url, pd);

            canvas.setPage(img, links);
            canvas.setStatus(url);
        } catch (Exception e) {
            if (stopRequested) {
                canvas.setStatus("Đã dừng");
            } else {
                canvas.setStatus("Lỗi: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            if (sc != null) try { sc.close(); } catch (Exception e) {}
            synchronized (this) {
                if (currentConnection == sc) currentConnection = null;
            }
        }
    }

    static class LinkRect {
        int x1, y1, x2, y2; String url;
        LinkRect(int a, int b, int c, int d, String u) { x1=a; y1=b; x2=c; y2=d; url=u; }
    }
}

class BrowserCanvas extends Canvas {
    MiniBrowser midlet;
    Image page;
    Vector links = new Vector();
    int scrollY = 0;
    int selectedLink = -1;
    String status = "Sẵn sàng";
    Font font;

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
        repaint();
    }

    protected void paint(Graphics g) {
        int sb = font.getHeight() + 2;
        g.setColor(0xFFFFFF);
        g.fillRect(0, 0, getWidth(), getHeight());

        if (page != null) {
            g.drawImage(page, 0, sb - scrollY, Graphics.TOP | Graphics.LEFT);
        }

        if (selectedLink >= 0 && selectedLink < links.size()) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(selectedLink);
            int y1 = lr.y1 + sb - scrollY - 1;
            int y2 = lr.y2 + sb - scrollY + 1;
            int x1 = lr.x1 - 1;
            int x2 = lr.x2 + 1;
            g.setColor(0xFF0000);
            g.drawRect(x1, y1, x2 - x1, y2 - y1);
        }

        g.setColor(0xDDDDDD);
        g.fillRect(0, 0, getWidth(), sb);
        g.setColor(0x000000);
        g.setFont(font);
        g.drawString(truncate(status, getWidth() - 4), 2, 1, Graphics.TOP | Graphics.LEFT);
    }

    String truncate(String s, int maxW) {
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

        if (action == UP || keyCode == KEY_NUM2) {
            scroll(-20);
        } else if (action == DOWN || keyCode == KEY_NUM8) {
            scroll(20);
        } else if (action == LEFT) {
            selectPrevLink();
        } else if (action == RIGHT) {
            selectNextLink();
        } else if (action == FIRE || keyCode == KEY_NUM5) {
            if (selectedLink >= 0) activateLink(selectedLink);
            else selectNextLink();
        }
    }

    protected void pointerPressed(int x, int y) {
        int sb = font.getHeight() + 2;
        int py = y - sb + scrollY;
        for (int i = 0; i < links.size(); i++) {
            MiniBrowser.LinkRect lr = (MiniBrowser.LinkRect) links.elementAt(i);
            if (x >= lr.x1 && x <= lr.x2 && py >= lr.y1 && py <= lr.y2) {
                selectedLink = i;
                activateLink(i);
                return;
            }
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
