import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jsoup.Jsoup;
import org.w3c.dom.Document;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.simple.Graphics2DRenderer;
import org.xhtmlrenderer.swing.NaiveUserAgent;

public class MiniProxy {
    static final int PAGE_WIDTH = 240;
    static final int RENDER_HEIGHT = 8000;
    static final int PORT = 9999;
    static final String UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) MiniProxy/4.0 Mobile Safari/537.36";

    // Cookie toàn cục (session persistence)
    static final CookieManager cookieManager = new CookieManager();

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sun.net.client.defaultConnectTimeout", "6000");
        System.setProperty("sun.net.client.defaultReadTimeout", "6000");

        ensureTempDir();
        checkFonts();

        // Khởi tạo cookie manager
        CookieHandler.setDefault(cookieManager);

        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("MiniProxy v4.0 listening on " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() {
                public void run() { handle(s); }
            }).start();
        }
    }

    // ── Font check ──
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

    // ── Protocol handler ──
    static void handle(Socket s) {
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            int urlLen = in.readInt();
            byte[] urlBytes = new byte[urlLen];
            in.readFully(urlBytes);
            String url = new String(urlBytes, "UTF-8");
            System.out.println("Request: " + url);

            Page page;
            try {
                page = render(url);
            } catch (Exception e) {
                System.err.println("Render failed: " + e);
                e.printStackTrace();
                page = errorPage("Lỗi render: " + e.getMessage());
            }

            // Gửi ảnh đã tối ưu
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
            e.printStackTrace();
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (out != null) out.close(); } catch (Exception e) {}
            try { s.close(); } catch (Exception e) {}
        }
    }

    // ── Render chính ──
    static Page render(String url) throws Exception {
        String html = fetchHtml(url);

        // 1. HTML → XHTML qua jsoup
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html, url);
        jsoupDoc.select("script,noscript,iframe,object,embed,video,audio,canvas").remove();
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

        // 2. Parse XML
        Document dom;
        try {
            dom = parseXml(xhtml, true);
        } catch (Exception e1) {
            System.err.println("namespaceAware=true fail: " + e1.getMessage());
            dom = parseXml(xhtml, false);
        }

        // 3. Render với Graphics2DRenderer
        Graphics2DRenderer renderer = new Graphics2DRenderer();
        renderer.setDocument(dom, url);
        renderer.getSharedContext().setUserAgentCallback(new ProxyUserAgent());

        BufferedImage fullImg = new BufferedImage(PAGE_WIDTH, RENDER_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fullImg.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH, RENDER_HEIGHT);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        renderer.layout(g, new Dimension(PAGE_WIDTH, RENDER_HEIGHT));
        renderer.render(g);
        g.dispose();

        // 4. Crop
        int usedH = RENDER_HEIGHT;
        try {
            Rectangle minSize = renderer.getMinimumSize();
            if (minSize != null && minSize.height > 0) {
                usedH = Math.min(RENDER_HEIGHT, minSize.height + 12);
            }
        } catch (Exception e) {}
        if (usedH < 40) usedH = 40;

        BufferedImage result = new BufferedImage(PAGE_WIDTH, usedH, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = result.createGraphics();
        rg.drawImage(fullImg, 0, 0, null);
        rg.dispose();

        // 5. TỐI ƯU PNG: quantize xuống 256 màu
        result = quantizeToIndexed(result);

        // 6. Links
        List<Link> links = new ArrayList<>();
        try {
            Field panelField = Graphics2DRenderer.class.getDeclaredField("panel");
            panelField.setAccessible(true);
            Object panel = panelField.get(renderer);
            java.lang.reflect.Method getRootBox = panel.getClass().getMethod("getRootBox");
            Box root = (Box) getRootBox.invoke(panel);
            if (root != null) collectLinks(root, links, url);
        } catch (Exception e) {
            System.err.println("Link extract fail: " + e);
        }

        return new Page(result, links);
    }

    // ── Quantize PNG: giảm màu để giảm kích thước ──
    static BufferedImage quantizeToIndexed(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        // Tạo bảng màu 256 màu (web-safe 6x6x6 + vài màu xám)
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
        // Thêm 40 mức xám
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

    static Page errorPage(String msg) {
        BufferedImage img = new BufferedImage(PAGE_WIDTH, 220, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, PAGE_WIDTH, 220);
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
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*");
        c.setRequestProperty("Accept-Language", "vi,en;q=0.8");
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
            } catch (IOException e) {
                return null;
            }
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
}
