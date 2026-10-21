import java.awt.*;
import java.awt.image.BufferedImage;
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
        "(KHTML, like Gecko) MiniProxy/3.2 Mobile Safari/537.36";

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("sun.net.client.defaultConnectTimeout", "6000");
        System.setProperty("sun.net.client.defaultReadTimeout", "6000");

        ensureTempDir();
        checkFonts();

        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("MiniProxy v3.2 listening on " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() {
                public void run() { handle(s); }
            }).start();
        }
    }

    // ── Kiểm tra font (nguyên nhân chính của "một đống kí tự") ──
    static void checkFonts() {
        try {
            String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
            System.out.println("[MiniProxy] Fonts available: " + fonts.length);
            int shown = 0;
            for (String f : fonts) {
                System.out.println("  ✓ " + f);
                if (++shown >= 5) break;
            }
            if (fonts.length == 0) {
                System.err.println();
                System.err.println("════════════════════════════════════════");
                System.err.println("!!! KHÔNG CÓ FONT NÀO !!!");
                System.err.println("Trang sẽ hiển thị toàn hộp vuông (tofu).");
                System.err.println("Sửa: pkg install fontconfig ttf-dejavu");
                System.err.println("════════════════════════════════════════");
            }
        } catch (Throwable t) {
            System.err.println("[MiniProxy] Font check lỗi: " + t);
        }
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

    static Page render(String url) throws Exception {
        String html = fetchHtml(url);

        // 1. HTML → XHTML
        org.jsoup.nodes.Document jsoupDoc = Jsoup.parse(html, url);
        jsoupDoc.select("script,noscript,iframe,object,embed,video,audio,canvas,svg").remove();
        jsoupDoc.outputSettings()
                .syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                .charset("UTF-8")
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml)
                .prettyPrint(false);

        String xhtml = jsoupDoc.outerHtml();
        // Bỏ HTML5 doctype (invalid với XHTML)
        xhtml = xhtml.replaceFirst("(?i)<!DOCTYPE[^>]*>", "").trim();

        // ── Đảm bảo <html> có xmlns (nếu thiếu, FS coi là XML generic → render text) ──
        int htmlTagStart = xhtml.toLowerCase().indexOf("<html");
        if (htmlTagStart < 0) {
            throw new IOException("Không tìm thấy <html> — nội dung không phải HTML?");
        }
        int htmlTagEnd = xhtml.indexOf('>', htmlTagStart);
        if (htmlTagEnd < 0) {
            throw new IOException("<html> không đóng — HTML hỏng?");
        }
        String htmlOpenTag = xhtml.substring(htmlTagStart, htmlTagEnd);
        if (!htmlOpenTag.toLowerCase().contains("xmlns=")) {
            xhtml = xhtml.substring(0, htmlTagEnd) +
                " xmlns=\"http://www.w3.org/1999/xhtml\"" +
                xhtml.substring(htmlTagEnd);
        }

        // Thêm XML declaration nếu thiếu
        if (!xhtml.trim().startsWith("<?xml")) {
            xhtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + xhtml;
        }

        // 2. Parse XML — thử namespaceAware trước, fallback nếu fail
        Document dom = null;
        try {
            dom = parseXml(xhtml, true);
        } catch (Exception e1) {
            System.err.println("[MiniProxy] namespaceAware=true fail: " + e1.getMessage());
            System.err.println("[MiniProxy] XHTML đầu: " + xhtml.substring(0, Math.min(300, xhtml.length())));
            dom = parseXml(xhtml, false);
        }

        // 3. Render
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
        } catch (Exception e) {
            System.err.println("[MiniProxy] getMinimumSize fail: " + e);
        }
        if (usedH < 40) {
            System.err.println("[MiniProxy] WARN: ảnh chỉ cao " + usedH + "px — HTML có thể không render được. Kiểm tra font!");
            usedH = 40;
        }

        BufferedImage result = new BufferedImage(PAGE_WIDTH, usedH, BufferedImage.TYPE_INT_RGB);
        Graphics2D rg = result.createGraphics();
        rg.drawImage(fullImg, 0, 0, null);
        rg.dispose();

        // 5. Links
        List<Link> links = new ArrayList<>();
        try {
            Field panelField = Graphics2DRenderer.class.getDeclaredField("panel");
            panelField.setAccessible(true);
            Object panel = panelField.get(renderer);
            java.lang.reflect.Method getRootBox = panel.getClass().getMethod("getRootBox");
            Box root = (Box) getRootBox.invoke(panel);
            if (root != null) collectLinks(root, links, url);
        } catch (Exception e) {
            System.err.println("[MiniProxy] Link extract fail: " + e);
        }

        return new Page(result, links);
    }

    static Document parseXml(String xhtml, boolean nsAware) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(nsAware);
        dbf.setValidating(false);
        dbf.setExpandEntityReferences(false);
        try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); } catch (Exception ignore) {}
        try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) {}
        try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) {}
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
        g.setColor(new Color(120, 120, 120));
        g.drawString("Kiểm tra log server", 10, 210);
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
                System.err.println("UA skip: " + uri + " (" + e.getMessage() + ")");
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
