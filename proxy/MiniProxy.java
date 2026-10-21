import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.simple.Graphics2DRenderer;
import org.xhtmlrenderer.swing.NaiveUserAgent;

/**
 * MiniProxy v6.0 — proxy render trang web thành ảnh PNG + danh sách vùng link cho MiniBrowser (J2ME).
 *
 * Giao thức (không đổi so với v5):
 *   client → proxy : int screenW, int screenH, int urlLen, byte[] url (UTF-8)
 *   proxy → client : int w, int h, int pngLen, byte[] png, int linkCount,
 *                    { int x1, y1, x2, y2, int urlLen, byte[] url } * linkCount
 *
 * Biến môi trường (tuỳ chọn):
 *   MINIPROXY_PORT       cổng (mặc định 9999, hoặc tham số dòng lệnh đầu tiên)
 *   MINIPROXY_CHROME     đường dẫn Chrome/Chromium
 *   MINIPROXY_NO_CHROME  =1 để chỉ dùng Flying Saucer
 *   MINIPROXY_SANDBOX    =1 để KHÔNG truyền --no-sandbox cho Chrome
 *   MINIPROXY_BLOCK_LAN  =1 để chặn truy cập địa chỉ nội bộ (localhost, 192.168.x.x ...)
 */
public class MiniProxy {
    static final int DEFAULT_PORT = 9999;
    static final int MAX_RENDER_W = 480;
    static final int MAX_RENDER_H = 4000;
    static final int MAX_PIXELS = 1200000;          // giới hạn heap phía client
    static final int MAX_URL_LEN = 4096;
    static final int MAX_REDIRECTS = 8;
    static final int MAX_BODY = 4 * 1024 * 1024;
    static final int MAX_LINKS = 400;
    static final int MAX_PNG = 4500 * 1024;         // client từ chối > 5 MB
    static final int CHROME_TIMEOUT_S = 40;

    // UA kiểu Opera Mini / Android — nhiều site lớn tin cậy hơn
    static final String UA =
        "Opera/9.80 (Android; Opera Mini/8.0.1807/191.379; U; en) Presto/2.12.423 Version/12.16";

    static final boolean NO_CHROME  = "1".equals(System.getenv("MINIPROXY_NO_CHROME"));
    static final boolean USE_SANDBOX = "1".equals(System.getenv("MINIPROXY_SANDBOX"));
    static final boolean BLOCK_LAN  = "1".equals(System.getenv("MINIPROXY_BLOCK_LAN"));

    static final Semaphore RENDER_SLOTS = new Semaphore(2, true);
    static final ExecutorService POOL = Executors.newFixedThreadPool(16);
    static final CookieManager cookieManager = new CookieManager();

    static final Pattern REFRESH_CONTENT = Pattern.compile(
        "^\\s*\\d+\\s*;\\s*url\\s*=\\s*['\"]?([^'\"\\s]+)", Pattern.CASE_INSENSITIVE);

    static final Pattern JS_REDIR = Pattern.compile(
        "(?:window\\.|document\\.|top\\.|self\\.|parent\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']|" +
        "(?:window\\.|document\\.|top\\.|self\\.|parent\\.)?location\\.(?:replace|assign)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    static final Pattern CHARSET_RE = Pattern.compile(
        "charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);

    static final Pattern ATTR_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.:\\-]*$");

    // Script chạy TRONG Chrome (chỉ ở lượt đo, khi URL có #measure): ghi chiều cao trang và
    // toạ độ thật của từng <a> vào DOM để Java đọc lại bằng --dump-dom.
    static final String MEASURE_JS =
        "<script>(function(){" +
        "function run(){try{" +
        "var out=[],as=document.querySelectorAll('a[href]'),i,j;" +
        "for(i=0;i<as.length&&out.length<" + MAX_LINKS + ";i++){" +
        "var a=as[i],h=a.getAttribute('href');" +
        "if(!h||/^\\s*(#|javascript:|mailto:|tel:|data:)/i.test(h))continue;" +
        "var rs=a.getClientRects();" +
        "for(j=0;j<rs.length&&j<3;j++){var r=rs[j];if(r.width<3||r.height<3)continue;" +
        "out.push([Math.round(r.left+window.pageXOffset),Math.round(r.top+window.pageYOffset)," +
        "Math.round(r.right+window.pageXOffset),Math.round(r.bottom+window.pageYOffset),a.href]);}}" +
        "var de=document.documentElement,b=document.body;" +
        "var H=Math.max(de.scrollHeight,b?b.scrollHeight:0);" +
        "var box=document.createElement('div');box.id='mpbox';" +
        "var hd=document.createElement('div');hd.id='mph';hd.textContent=H;box.appendChild(hd);" +
        "for(i=0;i<out.length;i++){var d=document.createElement('div');d.className='mpl';" +
        "d.textContent=out[i].join(' ');box.appendChild(d);}" +
        "(b||de).appendChild(box);}catch(e){}}" +
        "if(location.hash!=='#measure')return;" +
        "if(document.readyState==='complete')setTimeout(run,50);" +
        "else window.addEventListener('load',function(){setTimeout(run,50);});" +
        "})();</script>";

    static final String MOBILE_CSS =
        "html,body{margin:0;padding:0;max-width:100%;overflow-x:hidden;}" +
        "body{font-size:15px;line-height:1.4;word-wrap:break-word;}" +
        "img,video,svg{max-width:100%;height:auto;}" +
        "table{max-width:100%;}" +
        "pre{white-space:pre-wrap;}";

    static volatile String chromePath;
    static volatile boolean chromeSearched;
    static volatile int chromeFailures = 0;
    static volatile long chromeDisabledUntil = 0;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sun.net.client.defaultConnectTimeout", "8000");
        System.setProperty("sun.net.client.defaultReadTimeout", "15000");

        int port = DEFAULT_PORT;
        try {
            if (args.length > 0) port = Integer.parseInt(args[0].trim());
            else if (System.getenv("MINIPROXY_PORT") != null) port = Integer.parseInt(System.getenv("MINIPROXY_PORT").trim());
        } catch (NumberFormatException e) {
            System.err.println("Cổng không hợp lệ, dùng " + DEFAULT_PORT);
        }

        ensureTempDir();
        checkFonts();
        CookieHandler.setDefault(cookieManager);

        String chrome = NO_CHROME ? null : findChrome();
        ServerSocket ss = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        System.out.println("MiniProxy v6.0 listening on " + port
            + " | chrome=" + (chrome == null ? "không (dùng Flying Saucer)" : chrome)
            + " | blockLan=" + BLOCK_LAN);
        while (true) {
            final Socket s = ss.accept();
            try {
                POOL.execute(new Runnable() { public void run() { handle(s); } });
            } catch (RejectedExecutionException e) {
                try { s.close(); } catch (Exception ignore) {}
            }
        }
    }

    static void checkFonts() {
        try {
            String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
            System.out.println("[MiniProxy] Fonts available: " + fonts.length);
            if (fonts.length == 0) {
                System.err.println("!!! KHÔNG CÓ FONT — cài: pkg install fontconfig ttf-dejavu (hoặc apt install fonts-dejavu)");
            }
        } catch (Throwable t) {}
    }

    static void ensureTempDir() {
        String cur = System.getProperty("java.io.tmpdir");
        if (cur != null) {
            File f = new File(cur);
            if (f.isDirectory() && f.canWrite()) return;
        }
        List<String> cand = new ArrayList<>();
        cand.add(System.getenv("TMPDIR"));
        cand.add(System.getenv("TEMP"));
        cand.add(System.getenv("TMP"));
        String prefix = System.getenv("PREFIX");
        if (prefix != null) cand.add(prefix + "/tmp");
        String home = System.getProperty("user.home");
        if (home != null) cand.add(home + "/.cache/tmp");
        String cwd = System.getProperty("user.dir");
        if (cwd != null) cand.add(cwd);
        for (String c : cand) {
            if (c == null || c.length() == 0) continue;
            try {
                File f = new File(c);
                if (!f.exists() && !f.mkdirs()) continue;
                if (f.isDirectory() && f.canWrite()) {
                    System.setProperty("java.io.tmpdir", f.getAbsolutePath());
                    return;
                }
            } catch (Exception ignore) {}
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Xử lý 1 kết nối
    // ═══════════════════════════════════════════════════════════
    static void handle(Socket s) {
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            s.setSoTimeout(15000);   // client không gửi đủ request → thả kết nối
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            int screenW = in.readInt();
            int screenH = in.readInt();
            int urlLen = in.readInt();
            if (urlLen <= 0 || urlLen > MAX_URL_LEN) throw new IOException("Bad urlLen");
            byte[] urlBytes = new byte[urlLen];
            in.readFully(urlBytes);
            String url = normalizeUrl(new String(urlBytes, "UTF-8"));

            int renderW = clampRenderW(screenW);
            System.out.println("Request [w=" + screenW + ", h=" + screenH + ", renderW=" + renderW + "]: " + url);

            Page page;
            try {
                checkUrl(url);
                if (!RENDER_SLOTS.tryAcquire(60, TimeUnit.SECONDS))
                    throw new IOException("Proxy đang bận, thử lại sau");
                try { page = render(url, renderW); }
                finally { RENDER_SLOTS.release(); }
            } catch (Exception e) {
                System.err.println("Render failed: " + e);
                page = errorPage("Lỗi: " + (e.getMessage() == null ? e.toString() : e.getMessage()), renderW);
            }
            writePage(out, page);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof SocketException || e instanceof SocketTimeoutException || e instanceof EOFException
                || msg.contains("Broken pipe") || msg.contains("Connection reset")) {
                // Client đóng sớm (người dùng bấm Stop / chuyển trang) — bình thường
            } else {
                e.printStackTrace();
            }
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
            try { s.close(); } catch (Exception e) {}
        }
    }

    static void writePage(DataOutputStream out, Page page) throws IOException {
        BufferedImage img = page.image;
        List<Link> links = clipLinks(page.links, img.getWidth(), img.getHeight());
        byte[] png;
        while (true) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bo);
            png = bo.toByteArray();
            if (png.length <= MAX_PNG || img.getHeight() <= 400) break;
            img = crop(img, img.getHeight() * 3 / 4);     // PNG quá lớn → cắt bớt phần cuối
            links = clipLinks(links, img.getWidth(), img.getHeight());
        }
        out.writeInt(img.getWidth());
        out.writeInt(img.getHeight());
        out.writeInt(png.length);
        out.write(png);
        out.writeInt(links.size());
        for (Link l : links) {
            out.writeInt(l.x1); out.writeInt(l.y1);
            out.writeInt(l.x2); out.writeInt(l.y2);
            byte[] ub = l.url.getBytes("UTF-8");
            out.writeInt(ub.length); out.write(ub);
        }
        out.flush();
    }

    // ═══════════════════════════════════════════════════════════
    //  Render
    // ═══════════════════════════════════════════════════════════
    static Page render(String url, int renderW) throws Exception {
        url = preferMobileUrl(url);

        Fetched f = fetchWithRedirects(url);
        System.out.println("  → final: " + f.url);

        org.jsoup.nodes.Document doc = Jsoup.parse(f.html, f.url);

        String chrome = NO_CHROME ? null : findChrome();
        if (chrome != null && System.currentTimeMillis() >= chromeDisabledUntil) {
            try {
                Page p = renderWithChrome(chrome, doc.clone(), f.url, renderW);
                chromeFailures = 0;
                return p;
            } catch (Exception e) {
                System.err.println("  Chrome lỗi → dùng Flying Saucer: " + e.getMessage());
                if (++chromeFailures >= 2) {
                    chromeFailures = 0;
                    chromeDisabledUntil = System.currentTimeMillis() + 5 * 60 * 1000L;
                    System.err.println("  Tạm tắt Chrome 5 phút (lỗi liên tiếp)");
                }
            }
        }
        return renderWithFlyingSaucer(doc, f.url, renderW);
    }

    // ── Headless Chrome: 2 lượt (đo toạ độ link + chụp ảnh toàn trang) ──
    // Trang HTML được tải bằng Java (UA Opera Mini, cookie, redirect), bỏ hết <script> của trang,
    // lưu ra file tạm có <base href>, rồi Chrome chỉ dựng layout + chụp. Nhờ vậy:
    //  - không chạy JS lạ của website trong Chrome (--no-sandbox an toàn hơn),
    //  - toạ độ link lấy trực tiếp từ DOM nên khớp 100% với ảnh chụp.
    static Page renderWithChrome(String chrome, org.jsoup.nodes.Document doc, String baseUrl, int renderW) throws Exception {
        prepareForChrome(doc, baseUrl);
        String html = doc.outerHtml();
        int bi = html.lastIndexOf("</body>");
        String full = (bi >= 0)
            ? html.substring(0, bi) + MEASURE_JS + html.substring(bi)
            : html + MEASURE_JS;

        File htmlFile = File.createTempFile("j2me_", ".html");
        File shot = File.createTempFile("j2me_", ".png");
        File profile = Files.createTempDirectory("j2me_chrome_").toFile();
        try {
            Files.write(htmlFile.toPath(), full.getBytes("UTF-8"));
            String fileUrl = toFileUrl(htmlFile);
            int winH = 1150;

            // Lượt 1: đo
            List<String> c1 = chromeCmd(chrome, profile, renderW, winH);
            c1.add("--virtual-time-budget=6000");
            c1.add("--dump-dom");
            c1.add(fileUrl + "#measure");
            String dump = new String(runChrome(c1, true), "UTF-8");
            org.jsoup.nodes.Document md = Jsoup.parse(dump);
            org.jsoup.nodes.Element he = md.getElementById("mph");
            if (he == null) throw new IOException("Chrome không trả về số đo trang");
            int pageH = Integer.parseInt(he.text().trim());
            int usedH = Math.max(100, Math.min(pageH, maxHeightFor(renderW)));

            List<Link> links = new ArrayList<>();
            for (org.jsoup.nodes.Element le : md.select("div.mpl")) {
                String[] p = le.text().trim().split(" ", 5);
                if (p.length < 5) continue;
                try {
                    links.add(new Link(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                       Integer.parseInt(p[2]), Integer.parseInt(p[3]), p[4].trim()));
                } catch (NumberFormatException ignore) {}
            }

            // Lượt 2: chụp ảnh (cửa sổ cao hơn 150px để bù khung trình duyệt của new-headless)
            List<String> c2 = chromeCmd(chrome, profile, renderW, Math.max(winH, usedH + 150));
            c2.add("--virtual-time-budget=4000");
            c2.add("--screenshot=" + shot.getAbsolutePath());
            c2.add(fileUrl);
            runChrome(c2, false);
            if (shot.length() == 0) throw new IOException("Chrome không tạo được ảnh");
            BufferedImage img = ImageIO.read(shot);
            if (img == null) throw new IOException("Không đọc được ảnh Chrome");

            // Cắt theo chiều cao đo được rồi bỏ phần trắng thừa ở cuối
            int h = Math.min(img.getHeight(), usedH);
            h = Math.min(h, lastContentRow(img, h) + 24);
            h = Math.max(h, Math.min(100, img.getHeight()));
            BufferedImage rgb = new BufferedImage(img.getWidth(), h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), h);
            g.drawImage(img, 0, 0, null);
            g.dispose();

            BufferedImage result = quantizeToIndexed(rgb);
            System.out.println("  Chrome: " + result.getWidth() + "x" + result.getHeight()
                + ", " + links.size() + " link");
            return new Page(result, clipLinks(links, result.getWidth(), result.getHeight()));
        } finally {
            htmlFile.delete();
            shot.delete();
            deleteRecursive(profile);
        }
    }

    static void prepareForChrome(org.jsoup.nodes.Document doc, String baseUrl) {
        doc.select("base,script,noscript,iframe,frame,frameset,object,embed,video,audio,canvas").remove();
        for (org.jsoup.nodes.Element m : doc.select("meta[http-equiv]")) {
            String v = m.attr("http-equiv").trim().toLowerCase();
            if (v.equals("refresh") || v.contains("content-security-policy")) m.remove();
        }
        for (org.jsoup.nodes.Element l : doc.select("link[integrity],link[crossorigin]")) {
            l.removeAttr("integrity");
            l.removeAttr("crossorigin");
        }
        org.jsoup.nodes.Element head = doc.head();
        org.jsoup.nodes.Element vp = head.appendElement("meta");
        vp.attr("name", "viewport");
        vp.attr("content", "width=device-width, initial-scale=1");
        org.jsoup.nodes.Element st = head.appendElement("style");
        st.attr("type", "text/css");
        st.text(MOBILE_CSS);
        org.jsoup.nodes.Element base = doc.createElement("base");
        base.attr("href", baseUrl);
        head.prependChild(base);
        org.jsoup.nodes.Element cs = doc.createElement("meta");
        cs.attr("charset", "utf-8");
        head.prependChild(cs);
    }

    static List<String> chromeCmd(String chrome, File profile, int w, int h) {
        List<String> c = new ArrayList<>();
        c.add(chrome);
        c.add("--headless");
        c.add("--disable-gpu");
        if (!USE_SANDBOX) c.add("--no-sandbox");
        c.add("--disable-dev-shm-usage");
        c.add("--disable-extensions");
        c.add("--hide-scrollbars");
        c.add("--mute-audio");
        c.add("--no-first-run");
        c.add("--force-device-scale-factor=1");
        c.add("--default-background-color=FFFFFFFF");
        c.add("--user-data-dir=" + profile.getAbsolutePath());
        c.add("--window-size=" + w + "," + h);
        return c;
    }

    // Chạy Chrome, stdout/stderr ghi ra file tạm (tránh treo pipe khi tiến trình con còn sống)
    static byte[] runChrome(List<String> cmd, boolean capture) throws Exception {
        File outF = File.createTempFile("j2me_out_", ".txt");
        File errF = File.createTempFile("j2me_err_", ".txt");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(outF);
            pb.redirectError(errF);
            Process p = pb.start();
            try { p.getOutputStream().close(); } catch (IOException ignore) {}
            if (!p.waitFor(CHROME_TIMEOUT_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("Chrome quá thời gian (" + CHROME_TIMEOUT_S + "s)");
            }
            byte[] data = capture ? Files.readAllBytes(outF.toPath()) : new byte[0];
            if (capture && data.length == 0) {
                throw new IOException("Chrome exit " + p.exitValue() + ": " + tail(errF, 300));
            }
            return data;
        } finally {
            outF.delete();
            errF.delete();
        }
    }

    static String tail(File f, int n) {
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            int from = Math.max(0, b.length - n);
            return new String(b, from, b.length - from, "UTF-8").trim();
        } catch (Exception e) { return ""; }
    }

    static int lastContentRow(BufferedImage img, int limitH) {
        int w = img.getWidth();
        int[] row = new int[w];
        for (int y = Math.min(limitH, img.getHeight()) - 1; y >= 0; y--) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int p = row[x];
                if ((p >>> 24) != 0 && (p & 0xFFFFFF) != 0xFFFFFF) return y;
            }
        }
        return 0;
    }

    static String toFileUrl(File f) {
        String u = f.toURI().toString();              // file:/tmp/x.html
        if (u.startsWith("file:/") && !u.startsWith("file://")) u = "file://" + u.substring(5);
        return u;                                     // file:///tmp/x.html
    }

    static void deleteRecursive(File f) {
        if (f == null) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    static synchronized String findChrome() {
        if (chromeSearched) return chromePath;
        chromeSearched = true;
        String env = System.getenv("MINIPROXY_CHROME");
        if (env != null && env.length() > 0) { chromePath = env; return chromePath; }
        String[] names = { "google-chrome-stable", "google-chrome", "chromium", "chromium-browser", "chrome" };
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                for (String n : names) {
                    File f = new File(dir, n);
                    if (f.isFile() && f.canExecute()) { chromePath = f.getAbsolutePath(); return chromePath; }
                }
            }
        }
        String[] abs = {
            "/usr/bin/google-chrome", "/opt/google/chrome/chrome",
            "/usr/bin/chromium", "/usr/bin/chromium-browser",
            "/data/data/com.termux/files/usr/bin/chromium"
        };
        for (String a : abs) {
            File f = new File(a);
            if (f.isFile() && f.canExecute()) { chromePath = a; return chromePath; }
        }
        return null;
    }

    // ── Flying Saucer (dự phòng khi không có Chrome) ──
    static Page renderWithFlyingSaucer(org.jsoup.nodes.Document doc, String finalUrl, int renderW) throws Exception {
        List<Anchor> anchors = collectAnchors(doc, finalUrl);

        doc.select("script,noscript,iframe,frame,object,embed,video,audio,canvas,svg,use,link[rel=stylesheet]").remove();
        for (org.jsoup.nodes.Element el : doc.getAllElements()) {
            for (org.jsoup.nodes.Node n : new ArrayList<org.jsoup.nodes.Node>(el.childNodes())) {
                if (n instanceof org.jsoup.nodes.Comment) n.remove();
            }
            // Thuộc tính tên lạ (@click, :class, xlink:href...) làm hỏng XML
            List<String> rm = new ArrayList<>();
            for (org.jsoup.nodes.Attribute at : el.attributes()) {
                String k = at.getKey();
                if (k == null || !ATTR_NAME.matcher(k).matches() || k.toLowerCase().startsWith("xlink")) rm.add(k);
            }
            for (String k : rm) el.removeAttr(k);
        }

        org.jsoup.nodes.Element head = doc.head();
        org.jsoup.nodes.Element viewport = head.appendElement("meta");
        viewport.attr("name", "viewport");
        viewport.attr("content", "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no");
        org.jsoup.nodes.Element style = head.appendElement("style");
        style.attr("type", "text/css");
        style.text(
            "html,body{width:100%!important;max-width:100%!important;margin:0!important;padding:0!important;overflow-x:hidden;}" +
            "body{font-size:14px;line-height:1.4;}" +
            "img,video,iframe,table,div,p,span,a,section,article,header,footer,main,aside,ul,ol,li,h1,h2,h3,h4,h5,h6{" +
            "max-width:100%!important;box-sizing:border-box;}" +
            "img{height:auto!important;display:block;}" +
            "table{width:100%!important;table-layout:fixed;border-collapse:collapse;}" +
            "td,th{word-wrap:break-word;}" +
            "*{box-sizing:border-box;}"
        );

        doc.outputSettings()
           .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
           .charset("UTF-8")
           .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
           .prettyPrint(false);

        String xhtml = doc.outerHtml().replaceFirst("(?i)<!DOCTYPE[^>]*>", "").trim();
        if (!xhtml.toLowerCase().contains("xmlns=")) {
            xhtml = xhtml.replaceFirst("(?i)<html", "<html xmlns=\"http://www.w3.org/1999/xhtml\"");
        }
        if (!xhtml.startsWith("<?xml")) {
            xhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xhtml;
        }

        Document dom;
        try { dom = parseXml(xhtml, true); }
        catch (Exception e1) {
            System.err.println("  nsAware=true fail: " + e1.getMessage());
            dom = parseXml(xhtml, false);
        }

        int renderH = maxHeightFor(renderW);
        Graphics2DRenderer renderer = new Graphics2DRenderer();
        renderer.setDocument(dom, finalUrl);
        renderer.getSharedContext().setUserAgentCallback(new ProxyUserAgent());

        BufferedImage fullImg = new BufferedImage(renderW, renderH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fullImg.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, renderW, renderH);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        renderer.layout(g, new Dimension(renderW, renderH));
        renderer.render(g);
        g.dispose();

        int usedH = renderH;
        try {
            Rectangle minSize = renderer.getMinimumSize();
            if (minSize != null && minSize.height > 0) usedH = Math.min(renderH, minSize.height + 12);
        } catch (Exception e) {}
        if (usedH < 40) usedH = 40;

        BufferedImage cropped = new BufferedImage(renderW, usedH, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = cropped.createGraphics();
        rg.drawImage(fullImg, 0, 0, null);
        rg.dispose();

        // Link từ cây box của Flying Saucer (chỉ thấy link dạng block/inline-block)
        List<Link> links = new ArrayList<>();
        try {
            Field panelField = Graphics2DRenderer.class.getDeclaredField("panel");
            panelField.setAccessible(true);
            Object panel = panelField.get(renderer);
            java.lang.reflect.Method getRootBox = panel.getClass().getMethod("getRootBox");
            Box root = (Box) getRootBox.invoke(panel);
            if (root != null) collectLinks(root, links, finalUrl);
            System.out.println("  FS links: " + links.size());
        } catch (Exception e) {
            System.err.println("  FS link extract fail: " + e.getMessage());
        }

        // Nếu FS bỏ sót nhiều link (link inline) → thêm bảng "Liên kết" cuối trang với toạ độ do ta tự vẽ,
        // nên luôn khớp với ảnh (khác bản cũ: toạ độ bịa không trùng chỗ nào trên ảnh).
        Set<String> distinct = new HashSet<>();
        for (Anchor a : anchors) distinct.add(a.url);
        Page page;
        if (!anchors.isEmpty() && links.size() < Math.max(1, distinct.size() / 2)) {
            page = appendLinkPanel(cropped, anchors, links, renderW);
            System.out.println("  Link panel: " + anchors.size() + " link");
        } else {
            page = new Page(cropped, links);
        }
        BufferedImage result = quantizeToIndexed(page.image);
        return new Page(result, clipLinks(page.links, result.getWidth(), result.getHeight()));
    }

    static Page appendLinkPanel(BufferedImage img, List<Anchor> anchors, List<Link> fsLinks, int renderW) {
        int lineH = 18, header = 22, pad = 8;
        int avail = maxHeightFor(renderW) - img.getHeight() - header - pad;
        int rows = Math.min(anchors.size(), Math.max(0, avail / lineH));
        if (rows <= 0) return new Page(img, fsLinks);
        int y0 = img.getHeight();
        int newH = y0 + header + rows * lineH + pad;
        BufferedImage out = new BufferedImage(renderW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, renderW, newH);
        g.drawImage(img, 0, 0, null);
        g.setColor(new Color(0xEEEEEE));
        g.fillRect(0, y0, renderW, newH - y0);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x444444));
        g.setFont(new Font("SansSerif", Font.BOLD, 12));
        g.drawString("Liên kết (" + rows + ")", pad, y0 + 15);
        Font f = new Font("SansSerif", Font.PLAIN, 12);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        List<Link> links = new ArrayList<>(fsLinks);
        for (int i = 0; i < rows; i++) {
            Anchor a = anchors.get(i);
            int yy = y0 + header + i * lineH;
            String label = (i + 1) + ". " + a.text;
            while (label.length() > 4 && fm.stringWidth(label) > renderW - 2 * pad) {
                label = label.substring(0, label.length() - 2) + "…";
                if (fm.stringWidth(label) <= renderW - 2 * pad) break;
            }
            g.setColor(new Color(0x0000CC));
            g.drawString(label, pad, yy + 13);
            links.add(new Link(0, yy, renderW, yy + lineH, a.url));
        }
        g.dispose();
        return new Page(out, links);
    }

    static List<Anchor> collectAnchors(org.jsoup.nodes.Document doc, String baseUrl) {
        List<Anchor> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (org.jsoup.nodes.Element a : doc.select("a[href]")) {
            String href = a.attr("href").trim();
            String lh = href.toLowerCase();
            if (href.length() == 0 || href.startsWith("#") || lh.startsWith("javascript:")
                || lh.startsWith("mailto:") || lh.startsWith("tel:")) continue;
            String full = resolveUrl(baseUrl, href);
            if (!isHttp(full) || full.length() > 2000) continue;
            if (!seen.add(full)) continue;
            String text = a.text().trim();
            if (text.length() == 0) text = a.attr("title").trim();
            if (text.length() == 0) text = a.select("img[alt]").attr("alt").trim();
            if (text.length() == 0) text = full;
            out.add(new Anchor(text, full));
            if (out.size() >= 120) break;
        }
        return out;
    }

    static Document parseXml(String xhtml, boolean nsAware) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(nsAware);
        dbf.setValidating(false);
        dbf.setExpandEntityReferences(false);
        try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
        return dbf.newDocumentBuilder()
                  .parse(new org.xml.sax.InputSource(new StringReader(xhtml)));
    }

    static void collectLinks(Box box, List<Link> out, String baseUrl) {
        if (box == null) return;
        org.w3c.dom.Element e = box.getElement();
        if (e != null && "a".equalsIgnoreCase(e.getLocalName() != null ? e.getLocalName() : e.getNodeName())) {
            String href = e.getAttribute("href");
            if (href != null && href.length() > 0
                && !href.startsWith("#")
                && !href.toLowerCase().startsWith("javascript:")
                && !href.toLowerCase().startsWith("mailto:")) {
                String full = resolveUrl(baseUrl, href);
                int x = (int) box.getAbsX();
                int y = (int) box.getAbsY();
                int w = (int) box.getWidth();
                int h = (int) box.getHeight();
                if (w > 1 && h > 1 && x >= 0 && y >= 0) out.add(new Link(x, y, x + w, y + h, full));
            }
        }
        int n = box.getChildCount();
        for (int i = 0; i < n; i++) collectLinks(box.getChild(i), out, baseUrl);
    }

    // ═══════════════════════════════════════════════════════════
    //  Tải trang: redirect HTTP (kể cả http→https), meta refresh, JS redirect
    // ═══════════════════════════════════════════════════════════
    static class Fetched { String html; String url; }

    static Fetched fetchWithRedirects(String url) throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            String decoded = decodeGoogleRedirect(url);
            if (decoded != null) { System.out.println("  Google decode: " + decoded); url = decoded; }
            if (!seen.add(url)) throw new IOException("Vòng lặp chuyển hướng: " + url);

            Fetched f = fetchPage(url);
            String next = findClientRedirect(f.html, f.url);
            if (next != null && !next.equals(f.url) && !seen.contains(next)) {
                System.out.println("  Client redirect: " + next);
                url = next;
                continue;
            }
            return f;
        }
        throw new IOException("Quá nhiều chuyển hướng");
    }

    // HttpURLConnection KHÔNG tự theo redirect khác giao thức (http → https) nên phải tự xử lý.
    // Trước đây http://vnexpress.net → https trả về thân rỗng → trang trắng.
    static Fetched fetchPage(String startUrl) throws Exception {
        String url = startUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            checkUrl(url);
            HttpURLConnection c = (HttpURLConnection) toURL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            c.setRequestProperty("Accept-Language", "vi,en;q=0.8");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null || loc.trim().length() == 0) throw new IOException("Redirect HTTP " + code + " thiếu Location");
                url = resolveUrl(url, loc.trim());
                continue;
            }
            String ct = c.getContentType();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] body = is == null ? new byte[0] : readLimited(is, MAX_BODY);
            c.disconnect();
            if (ct != null && !isTextual(ct)) throw new IOException("Không hỗ trợ loại nội dung: " + ct);
            String html = decodeBody(body, ct);
            if (code >= 400 && html.trim().length() == 0) throw new IOException("HTTP " + code);
            Fetched f = new Fetched();
            f.html = html;
            f.url = url;
            return f;
        }
        throw new IOException("Quá nhiều chuyển hướng HTTP");
    }

    static byte[] readLimited(InputStream is, int limit) throws IOException {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = is.read(b)) > 0) {
                buf.write(b, 0, n);
                if (buf.size() >= limit) break;     // cắt bớt trang quá lớn thay vì tràn heap
            }
            return buf.toByteArray();
        } finally {
            try { is.close(); } catch (IOException ignore) {}
        }
    }

    static boolean isTextual(String ct) {
        String l = ct.toLowerCase();
        return l.startsWith("text/") || l.contains("html") || l.contains("xml");
    }

    static String decodeBody(byte[] body, String contentType) {
        String cs = null;
        if (contentType != null) {
            Matcher m = CHARSET_RE.matcher(contentType);
            if (m.find()) cs = m.group(1);
        }
        if (cs == null) {
            int n = Math.min(body.length, 2048);
            String head = new String(body, 0, n, Charset.forName("ISO-8859-1"));
            Matcher m = CHARSET_RE.matcher(head);
            if (m.find()) cs = m.group(1);
        }
        Charset c = Charset.forName("UTF-8");
        if (cs != null) { try { c = Charset.forName(cs.trim()); } catch (Exception ignore) {} }
        int off = 0;
        if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) off = 3;
        return new String(body, off, body.length - off, c);
    }

    // meta refresh (ngoài <noscript>) và JS redirect (chỉ khi trang gần như rỗng — tránh nhận nhầm
    // đoạn "location.href=..." nằm trong script bình thường)
    static String findClientRedirect(String html, String base) {
        try {
            String sample = html.length() > 20000 ? html.substring(0, 20000) : html;
            org.jsoup.nodes.Document d = Jsoup.parse(sample, base);
            for (org.jsoup.nodes.Element m : d.select("meta[http-equiv]")) {
                if (!"refresh".equalsIgnoreCase(m.attr("http-equiv").trim())) continue;
                boolean inNoscript = false;
                for (org.jsoup.nodes.Element p : m.parents()) if ("noscript".equals(p.tagName())) inNoscript = true;
                if (inNoscript) continue;
                Matcher mm = REFRESH_CONTENT.matcher(m.attr("content"));
                if (mm.find()) {
                    String r = resolveUrl(base, mm.group(1).trim());
                    if (isHttp(r)) return r;
                }
            }
            String bodyText = d.body() == null ? "" : d.body().text();
            if (bodyText.length() < 80) {
                Matcher jm = JS_REDIR.matcher(sample);
                if (jm.find()) {
                    String target = jm.group(1) != null ? jm.group(1) : jm.group(2);
                    if (target != null) {
                        String r = resolveUrl(base, target.trim());
                        if (isHttp(r)) return r;
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }

    static String decodeGoogleRedirect(String url) {
        if (!url.contains("google.") || !url.contains("/url")) return null;
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String p : query.split("&")) {
                if (p.startsWith("q=") || p.startsWith("url=")) {
                    String v = URLDecoder.decode(p.substring(p.indexOf('=') + 1), "UTF-8");
                    if (v.startsWith("http://") || v.startsWith("https://")) return v;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    // ═══════════════════════════════════════════════════════════
    //  URL helpers + bảo mật
    // ═══════════════════════════════════════════════════════════
    static String normalizeUrl(String u) {
        u = u == null ? "" : u.trim();
        if (u.startsWith("//")) u = "http:" + u;
        else if (u.indexOf("://") < 0) u = "http://" + u;
        return u;
    }

    static boolean isHttp(String u) {
        if (u == null) return false;
        String l = u.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://");
    }

    // Chỉ cho http/https. Trước đây client có thể gửi file:///etc/passwd để Chrome chụp lại.
    static void checkUrl(String url) throws IOException {
        URL u;
        try { u = toURL(url); }
        catch (Exception e) { throw new IOException("URL không hợp lệ: " + url); }
        String p = u.getProtocol();
        if (!"http".equalsIgnoreCase(p) && !"https".equalsIgnoreCase(p))
            throw new IOException("Chỉ hỗ trợ http/https");
        String host = u.getHost();
        if (host == null || host.length() == 0) throw new IOException("Thiếu tên miền");
        if (BLOCK_LAN) {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress())
                    throw new IOException("Địa chỉ nội bộ bị chặn");
            }
        }
    }

    @SuppressWarnings("deprecation")
    static URL toURL(String spec) throws MalformedURLException {
        try { return URI.create(spec).toURL(); }
        catch (IllegalArgumentException e) { return new URL(spec); }
    }

    static String resolveUrl(String base, String href) {
        try { return URI.create(base).resolve(href).toString(); }
        catch (Exception e) {
            try { return new URL(new URL(base), href).toString(); }
            catch (Exception e2) { return href; }
        }
    }

    // Ép một số site lớn sang bản nhẹ. So khớp theo HOST (bản cũ so khớp cả chuỗi URL nên
    // http://abc.com/?u=www.google.com cũng bị viết lại sai). Google không còn bị viết lại:
    // google.com tự phục vụ bản nhẹ cho UA Opera Mini.
    static String preferMobileUrl(String url) {
        try {
            URI u = new URI(url);
            String host = u.getHost();
            if (host == null) return url;
            String h = host.toLowerCase();
            String nh = null;
            Matcher wm = Pattern.compile("^([a-z\\-]+)\\.wikipedia\\.org$").matcher(h);
            if (wm.matches() && !wm.group(1).equals("www") && !wm.group(1).equals("m")) {
                nh = wm.group(1) + ".m.wikipedia.org";
            } else if (h.equals("facebook.com") || h.equals("www.facebook.com") || h.equals("m.facebook.com")) {
                nh = "mbasic.facebook.com";
            } else if (h.equals("youtube.com") || h.equals("www.youtube.com")) {
                nh = "m.youtube.com";
            }
            if (nh == null) return url;
            return new URI(u.getScheme(), u.getUserInfo(), nh, u.getPort(),
                           u.getPath(), u.getQuery(), u.getFragment()).toString();
        } catch (Exception e) {
            return url;
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Ảnh
    // ═══════════════════════════════════════════════════════════
    static int clampRenderW(int screenW) { return Math.max(200, Math.min(MAX_RENDER_W, screenW)); }

    static int maxHeightFor(int renderW) {
        return Math.min(MAX_RENDER_H, Math.max(1500, MAX_PIXELS / Math.max(200, renderW)));
    }

    static BufferedImage quantizeToIndexed(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        int idx = 0;
        for (int ri = 0; ri < 6; ri++)
            for (int gi = 0; gi < 6; gi++)
                for (int bi = 0; bi < 6; bi++) {
                    r[idx] = (byte) (ri * 51);
                    g[idx] = (byte) (gi * 51);
                    b[idx] = (byte) (bi * 51);
                    idx++;
                }
        for (int i = 0; i < 40; i++) {
            byte v = (byte) (i * 6);
            r[idx] = g[idx] = b[idx] = v;
            idx++;
        }
        IndexColorModel cm = new IndexColorModel(8, 256, r, g, b);
        BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
        Graphics2D g2 = indexed.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, w, h);                 // ảnh trong suốt không bị thành đen
        g2.drawImage(src, 0, 0, null);
        g2.dispose();
        return indexed;
    }

    static BufferedImage crop(BufferedImage src, int h) {
        BufferedImage dst;
        if (src.getColorModel() instanceof IndexColorModel) {
            dst = new BufferedImage(src.getWidth(), h, BufferedImage.TYPE_BYTE_INDEXED,
                                    (IndexColorModel) src.getColorModel());
        } else {
            dst = new BufferedImage(src.getWidth(), h, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    static List<Link> clipLinks(List<Link> in, int w, int h) {
        List<Link> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Link l : in) {
            int x1 = Math.max(0, l.x1), y1 = Math.max(0, l.y1);
            int x2 = Math.min(w, l.x2), y2 = Math.min(h, l.y2);
            if (x2 - x1 < 2 || y2 - y1 < 2) continue;
            if (l.url == null || l.url.length() > 2000 || !isHttp(l.url)) continue;
            String key = x1 + "," + y1 + "," + x2 + "," + y2 + "," + l.url;
            if (!seen.add(key)) continue;
            out.add(new Link(x1, y1, x2, y2, l.url));
            if (out.size() >= MAX_LINKS) break;
        }
        return out;
    }

    static Page errorPage(String msg, int width) {
        BufferedImage img = new BufferedImage(width, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, 220);
        g.setColor(new Color(200, 0, 0));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("Lỗi", 10, 26);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        String m = msg == null ? "?" : msg;
        int perLine = Math.max(20, width / 6);
        int y = 50, i = 0;
        while (i < m.length() && y < 210) {
            int end = Math.min(i + perLine, m.length());
            g.drawString(m.substring(i, end), 10, y);
            y += 14; i = end;
        }
        g.dispose();
        return new Page(quantizeToIndexed(img), new ArrayList<Link>());
    }

    // ═══════════════════════════════════════════════════════════
    //  Kiểu dữ liệu
    // ═══════════════════════════════════════════════════════════
    static class ProxyUserAgent extends NaiveUserAgent {
        @Override
        protected InputStream openStream(String uri) throws IOException {
            try {
                checkUrl(uri);
                HttpURLConnection c = (HttpURLConnection) toURL(uri).openConnection();
                c.setRequestProperty("User-Agent", UA);
                c.setRequestProperty("Accept", "*/*");
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setInstanceFollowRedirects(true);
                return c.getInputStream();
            } catch (IOException e) { return null; }
        }
    }

    static class Link {
        int x1, y1, x2, y2; String url;
        Link(int a, int b, int c, int d, String u) { x1 = a; y1 = b; x2 = c; y2 = d; url = u; }
    }

    static class Anchor {
        String text, url;
        Anchor(String t, String u) { text = t; url = u; }
    }

    static class Page {
        BufferedImage image; List<Link> links;
        Page(BufferedImage i, List<Link> l) { image = i; links = l; }
    }
}
