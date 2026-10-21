import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.simple.Graphics2DRenderer;
import org.xhtmlrenderer.swing.NaiveUserAgent;

public class MiniProxy {
    static final int PORT = 9999;
    static final int MAX_RENDER_HEIGHT = 4000;
    static final int MAX_PIXELS = 1200000;   // ~2M pixels → giới hạn heap client
    static final int MAX_URL_LEN = 4096;
    static final int MAX_REDIRECTS = 5;

    // UA kiểu Opera Mini / Android — nhiều site lớn tin cậy hơn
    static final String UA =
        "Opera/9.80 (Android; Opera Mini/8.0.1807/191.379; U; en) Presto/2.12.423 Version/12.16";

    static final CookieManager cookieManager = new CookieManager();

    static final Pattern META_REFRESH = Pattern.compile(
        "<meta[^>]*?http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]*?content\\s*=\\s*[\"']?\\s*\\d+\\s*;\\s*url\\s*=\\s*[\"']?([^\"'\\s>]+)",
        Pattern.CASE_INSENSITIVE);

    static final Pattern JS_REDIR = Pattern.compile(
        "(?:window\\.|document\\.|top\\.|self\\.|parent\\.)?location(?:\\.href)?\\s*=\\s*[\"']([^\"']+)[\"']|" +
        "(?:window\\.|document\\.|top\\.|self\\.|parent\\.)?location\\.(?:replace|assign)\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)",
        Pattern.CASE_INSENSITIVE);

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sun.net.client.defaultConnectTimeout", "6000");
        System.setProperty("sun.net.client.defaultReadTimeout", "6000");

        ensureTempDir();
        checkFonts();
        CookieHandler.setDefault(cookieManager);

        ServerSocket ss = new ServerSocket(PORT, 50, java.net.InetAddress.getByName("0.0.0.0"));
        System.out.println("MiniProxy v5.0 listening on " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() {
                public void run() { handle(s); }
            }).start();
        }
    }

    static void checkFonts() {
        try {
            String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
            System.out.println("[MiniProxy] Fonts available: " + fonts.length);
            if (fonts.length == 0) {
                System.err.println("!!! KHÔNG CÓ FONT — cài: pkg install fontconfig ttf-dejavu");
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

    @SuppressWarnings("deprecation")
    static URL toURL(String spec) throws MalformedURLException {
        try { return URI.create(spec).toURL(); }
        catch (IllegalArgumentException e) { return new URL(spec); }
    }

    // ── Protocol v5 ──
    static void handle(Socket s) {
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));

            int screenW = in.readInt();
            int screenH = in.readInt();
            int urlLen = in.readInt();
            if (urlLen <= 0 || urlLen > MAX_URL_LEN) throw new IOException("Bad urlLen");
            byte[] urlBytes = new byte[urlLen];
            in.readFully(urlBytes);
            String url = new String(urlBytes, "UTF-8");

            int renderW = Math.max(200, Math.min(480, screenW));
            System.out.println("Request [w=" + screenW + ", renderW=" + renderW + "]: " + url);

            Page page;
            try {
                page = render(url, renderW, screenH);
            } catch (Exception e) {
                System.err.println("Render failed: " + e);
                e.printStackTrace();
                page = errorPage("Lỗi render: " + e.getMessage(), renderW);
            }

            // Check client still connected before sending (avoid broken pipe)
            if (s.isClosed() || !s.isConnected()) {
                System.err.println("  Client already gone, skip send");
                return;
            }
            out.writeInt(page.image.getWidth());
            out.writeInt(page.image.getHeight());
            ByteArrayOutputStream imgBuf = new ByteArrayOutputStream();
            ImageIO.write(page.image, "png", imgBuf);
            byte[] imgData = imgBuf.toByteArray();
            out.writeInt(imgData.length);
            out.write(imgData);

            out.writeInt(page.links.size());
            for (Link l : page.links) {
                out.writeInt(l.x1); out.writeInt(l.y1);
                out.writeInt(l.x2); out.writeInt(l.y2);
                byte[] ub = l.url.getBytes("UTF-8");
                out.writeInt(ub.length); out.write(ub);
            }
            out.flush();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (e instanceof java.net.SocketException && 
                (msg.contains("Broken pipe") || msg.contains("Connection reset") || msg.contains("closed"))) {
                // Client closed early — normal when user navigates or times out
                return;
            } else {
                e.printStackTrace();
            }
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
            try { s.close(); } catch (Exception e) {}
        }
    }

    // ── Render ──

    // ── Headless Chrome render (WRP / Opera Mini style) ──
    static Page renderWithChrome(String url, int renderW) throws Exception {
        String tmpDir = System.getProperty("java.io.tmpdir");
        String shot = tmpDir + "/j2me_" + System.currentTimeMillis() + ".png";
        String htmlOut = tmpDir + "/j2me_" + System.currentTimeMillis() + ".html";

        // Screenshot
        String chromeBin = findChrome();
        if (chromeBin == null) throw new IOException("No Chrome/Chromium binary found");
        ProcessBuilder pb = new ProcessBuilder(
            chromeBin, "--headless", "--disable-gpu", "--no-sandbox",
            "--window-size=" + renderW + ",1200",
            "--screenshot=" + shot,
            "--virtual-time-budget=8000",
            url
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // Consume output
        java.io.InputStream is = p.getInputStream();
        byte[] buf = new byte[1024];
        while (is.read(buf) > 0) {}
        int code = p.waitFor();
        if (code != 0) throw new IOException("Chrome exit " + code);

        BufferedImage img = ImageIO.read(new java.io.File(shot));
        if (img == null) throw new IOException("Chrome screenshot failed");

        // Crop to content height if possible
        int usedH = Math.min(img.getHeight(), 4000);
        if (img.getHeight() > usedH) {
            img = img.getSubimage(0, 0, img.getWidth(), usedH);
        }

        // Extract links via a simple HTML dump (Chrome --dump-dom is limited; use jsoup on fetched HTML)
        List<Link> links = new ArrayList<>();
        try {
            String html = fetchHtml(url);
            links = extractLinksFromHtml(html, url, renderW);
        } catch (Exception e) {
            System.err.println("  Chrome link extract fail: " + e.getMessage());
        }

        // Cleanup
        new java.io.File(shot).delete();

        img = quantizeToIndexed(img);
        return new Page(img, links);
    }

    static Page render(String url, int renderW, int screenH) throws Exception {
        // Prefer mobile versions of common sites
        url = preferMobileUrl(url);

        // Try headless Chrome first (WRP / Opera Mini style)
        try {
            System.out.println("  Trying Chrome render...");
            return renderWithChrome(url, renderW);
        } catch (Exception e) {
            System.err.println("  Chrome failed, fallback to Flying Saucer: " + e.getMessage());
        }

        // 1. Fetch + follow redirects (google, meta, JS)
        String[] fetchResult = fetchWithRedirects(url);
        String html = fetchResult[0];
        String finalUrl = fetchResult[1];
        System.out.println("  → final: " + finalUrl);

        // 2. HTML → XHTML + inject CSS ép giãn theo width
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html, finalUrl);
        // Aggressive cleanup (inspired by WRP simple-HTML mode)
        jsoupDoc.select("script,noscript,iframe,object,embed,video,audio,canvas,svg,use,link[rel=stylesheet]").remove();
        // Remove comments that can break XML
        for (org.jsoup.nodes.Node n : jsoupDoc.childNodes()) {
            if (n instanceof org.jsoup.nodes.Comment) n.remove();
        }
        // Strip any xlink:* attributes (breaks XML namespace parsing on Google etc.)
        for (org.jsoup.nodes.Element el : jsoupDoc.getAllElements()) {
            java.util.Iterator<org.jsoup.nodes.Attribute> it = el.attributes().iterator();
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            while (it.hasNext()) {
                String key = it.next().getKey();
                if (key != null && key.toLowerCase().startsWith("xlink")) toRemove.add(key);
            }
            for (String k : toRemove) el.removeAttr(k);
        }

        // Inject viewport + force mobile layout CSS
        org.jsoup.nodes.Element head = jsoupDoc.head();
        if (head == null) {
            head = jsoupDoc.prependElement("head");
        }
        // Viewport meta — nhiều site dùng để chọn bản mobile
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

        jsoupDoc.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .charset("UTF-8")
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .prettyPrint(false);

        String xhtml = jsoupDoc.outerHtml().replaceFirst("(?i)<!DOCTYPE[^>]*>", "").trim();
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

        // 3. Render height theo width
        int renderH = Math.min(MAX_RENDER_HEIGHT,
                               Math.max(3000, MAX_PIXELS / Math.max(240, renderW)));

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
            if (minSize != null && minSize.height > 0) {
                usedH = Math.min(renderH, minSize.height + 12);
            }
        } catch (Exception e) {}
        if (usedH < 40) usedH = 40;

        BufferedImage result = new BufferedImage(renderW, usedH, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = result.createGraphics();
        rg.drawImage(fullImg, 0, 0, null);
        rg.dispose();

        // 4. Quantize (giảm size)
        result = quantizeToIndexed(result);

        // 5. Links — try Flying Saucer box tree, fallback to HTML scan
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
        // Fallback: if FS found nothing, scan HTML for <a href>
        if (links.isEmpty()) {
            links = extractLinksFromHtml(html, finalUrl, renderW);
            System.out.println("  HTML fallback links: " + links.size());
        }

        return new Page(result, links);
    }

    // ── Fetch + redirects ──
    static String[] fetchWithRedirects(String url) throws Exception {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            if (seen.contains(url)) throw new IOException("Redirect loop: " + url);
            seen.add(url);

            // Google redirect decode TRƯỚC fetch
            String decoded = decodeGoogleRedirect(url);
            if (decoded != null && !decoded.equals(url)) {
                if (seen.contains(decoded)) throw new IOException("Google redirect loop");
                seen.add(decoded);
                System.out.println("  Google decode: " + decoded);
                url = decoded;
            }

            String html = fetchHtml(url);
            String sample = html.length() > 10000 ? html.substring(0, 10000) : html;

            // Meta refresh
            Matcher m = META_REFRESH.matcher(sample);
            if (m.find()) {
                String r = resolveUrl(url, m.group(1).trim());
                if (!r.equals(url)) {
                    System.out.println("  Meta refresh: " + r);
                    url = r;
                    continue;
                }
            }

            // JS redirect
            Matcher jm = JS_REDIR.matcher(sample);
            if (jm.find()) {
                String target = jm.group(1) != null ? jm.group(1) : jm.group(2);
                if (target != null) {
                    String r = resolveUrl(url, target.trim());
                    if (!r.equals(url) && (r.startsWith("http://") || r.startsWith("https://"))) {
                        System.out.println("  JS redirect: " + r);
                        url = r;
                        continue;
                    }
                }
            }

            return new String[]{ html, url };
        }
        throw new IOException("Quá nhiều redirect");
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

    static BufferedImage quantizeToIndexed(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        byte[] r = new byte[256], g = new byte[256], b = new byte[256];
        int idx = 0;
        for (int ri = 0; ri < 6; ri++)
            for (int gi = 0; gi < 6; gi++)
                for (int bi = 0; bi < 6; bi++) {
                    r[idx] = (byte)(ri * 51);
                    g[idx] = (byte)(gi * 51);
                    b[idx] = (byte)(bi * 51);
                    idx++;
                }
        for (int i = 0; i < 40; i++) {
            byte v = (byte)(i * 6);
            r[idx] = g[idx] = b[idx] = v;
            idx++;
        }
        IndexColorModel cm = new IndexColorModel(8, 256, r, g, b);
        BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, cm);
        Graphics2D g2 = indexed.createGraphics();
        g2.drawImage(src, 0, 0, null);
        g2.dispose();
        return indexed;
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
                && !href.startsWith("javascript:")
                && !href.startsWith("mailto:")) {
                String full = resolveUrl(baseUrl, href);
                int x = (int) box.getAbsX();
                int y = (int) box.getAbsY();
                int w = (int) box.getWidth();
                int h = (int) box.getHeight();
                if (w > 1 && h > 1 && x >= 0 && y >= 0) {
                    out.add(new Link(x, y, x + w, y + h, full));
                }
            }
        }
        int n = box.getChildCount();
        for (int i = 0; i < n; i++) collectLinks(box.getChild(i), out, baseUrl);
    }

    static String resolveUrl(String base, String href) {
        try { return URI.create(base).resolve(href).toString(); }
        catch (Exception e) {
            try { return new URL(new URL(base), href).toString(); }
            catch (Exception e2) { return href; }
        }
    }

    static Page errorPage(String msg, int width) {
        BufferedImage img = new BufferedImage(width, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, 220);
        g.setColor(new Color(200, 0, 0));
        g.setFont(new Font("SansSerif", Font.BOLD, 13));
        g.drawString("Lỗi render", 10, 26);
        g.setColor(Color.BLACK);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        String m = msg == null ? "?" : msg;
        int y = 50, i = 0;
        while (i < m.length() && y < 210) {
            int end = Math.min(i + 40, m.length());
            g.drawString(m.substring(i, end), 10, y);
            y += 14; i = end;
        }
        g.dispose();
        return new Page(img, new ArrayList<Link>());
    }

    static String fetchHtml(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) toURL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        c.setRequestProperty("Accept-Language", "vi,en;q=0.8");
        c.setRequestProperty("Accept-Encoding", "identity");
        c.setRequestProperty("Upgrade-Insecure-Requests", "1");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        InputStream is = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = is.read(b)) > 0) buf.write(b, 0, n);
        is.close();
        String charset = "UTF-8";
        String ct = c.getContentType();
        if (ct != null) {
            String lc = ct.toLowerCase();
            int idx = lc.indexOf("charset=");
            if (idx >= 0) charset = ct.substring(idx + 8).split("[;\\s]")[0].trim();
        }
        return new String(buf.toByteArray(), charset);
    }

    static class ProxyUserAgent extends NaiveUserAgent {
        @Override
        protected InputStream openStream(String uri) throws IOException {
            try {
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
        Link(int a, int b, int c, int d, String u) { x1=a; y1=b; x2=c; y2=d; url=u; }
    }
    static class Page {
        BufferedImage image; List<Link> links;
        Page(BufferedImage i, List<Link> l) { image = i; links = l; }
    }

    // Ép một số site lớn sang bản mobile
    static String preferMobileUrl(String url) {
        if (url == null) return url;
        String lower = url.toLowerCase();
        // Google
        if (lower.contains("www.google.") || lower.contains("//google.")) {
            if (!lower.contains("/search") && !lower.contains("m.google")) {
                return url.replaceFirst("(?i)https?://(www\\.)?google\\.", "https://m.google.");
            }
        }
        // Wikipedia
        if (lower.contains("wikipedia.org") && !lower.contains("m.wikipedia") && !lower.contains(".m.wikipedia")) {
            return url.replaceFirst("(?i)https?://([a-z]{2}\\.)?wikipedia\\.org", "https://$1m.wikipedia.org");
        }
        // Facebook / YouTube basic
        if (lower.contains("facebook.com") && !lower.contains("mbasic.facebook")) {
            return url.replaceFirst("(?i)https?://(www\\.|m\\.)?facebook\\.com", "https://mbasic.facebook.com");
        }
        if (lower.contains("youtube.com") && !lower.contains("m.youtube")) {
            return url.replaceFirst("(?i)https?://(www\\.)?youtube\\.com", "https://m.youtube.com");
        }
        return url;
    }


    // Fallback: extract <a href> from raw HTML when Flying Saucer box tree fails
    static List<Link> extractLinksFromHtml(String html, String baseUrl, int renderW) {
        List<Link> out = new ArrayList<>();
        if (html == null) return out;
        try {
            org.jsoup.nodes.Document doc = Jsoup.parse(html, baseUrl);
            org.jsoup.select.Elements as = doc.select("a[href]");
            int y = 30;
            int lineH = 22;
            for (org.jsoup.nodes.Element a : as) {
                String href = a.attr("href");
                if (href == null || href.length() == 0) continue;
                if (href.startsWith("#") || href.startsWith("javascript:") || href.startsWith("mailto:")) continue;
                String full = resolveUrl(baseUrl, href);
                String text = a.text();
                if (text == null) text = "";
                text = text.trim();
                if (text.length() == 0) text = href;
                // Estimate height based on text length
                int estW = Math.max(40, Math.min(renderW - 16, text.length() * 7));
                int h = lineH;
                if (text.length() > 40) h = lineH * 2;
                out.add(new Link(8, y, 8 + estW, y + h, full));
                y += h + 6;
                if (y > 3500 || out.size() > 80) break;
            }
            System.out.println("  extractLinksFromHtml: " + out.size() + " links");
        } catch (Exception e) {
            System.err.println("  HTML link fallback fail: " + e.getMessage());
        }
        return out;
    }


    static String findChrome() {
        String[] candidates = {
            "chromium", "chromium-browser", "google-chrome", "chrome",
            "/data/data/com.termux/files/usr/bin/chromium",
            "/usr/bin/chromium", "/usr/bin/chromium-browser"
        };
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder("which", c).start();
                if (p.waitFor() == 0) {
                    java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()));
                    String line = br.readLine();
                    if (line != null && line.length() > 0) return line.trim();
                }
            } catch (Exception e) {}
            // Also try direct file check for absolute paths
            if (c.startsWith("/") && new java.io.File(c).exists()) return c;
        }
        return null;
    }

}
