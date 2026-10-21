import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.imageio.ImageIO;

public class MiniProxy {
    static final int PAGE_WIDTH = 240;
    static final int MAX_HEIGHT = 4000;
    static final int PORT = 9999;
    static final int MAX_IMG_BYTES = 500 * 1024;

    static final Map<String, BufferedImage> imgCache = new HashMap<>();

    public static void main(String[] args) throws Exception {
        ensureTempDir();
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("MiniProxy listening on port " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() {
                public void run() { handle(s); }
            }).start();
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
                    System.out.println("[MiniProxy] tmpdir -> " + f.getAbsolutePath());
                    return;
                }
            } catch (Exception ignore) {}
        }
        System.err.println("[MiniProxy] WARN: không tìm được tmpdir ghi được");
    }

    @SuppressWarnings("deprecation")
    static URL toURL(String spec) throws MalformedURLException {
        try { return URI.create(spec).toURL(); }
        catch (IllegalArgumentException e) { return new URL(spec); }
    }

    @SuppressWarnings("deprecation")
    static URL resolveURL(URL base, String href) throws MalformedURLException {
        try { return base.toURI().resolve(href).toURL(); }
        catch (Exception e) { return new URL(base, href); }
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
            Page page = render(url);
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

    static class Style {
        Color color = Color.BLACK;
        boolean bold = false, italic = false;
        int size = 11;
        String family = "SansSerif";

        Style copy() {
            Style s = new Style();
            s.color = color; s.bold = bold; s.italic = italic;
            s.size = size; s.family = family;
            return s;
        }
        Font font() {
            int st = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
            return new Font(family, st, size);
        }
        int lineHeight() { return size + 4; }
    }

    static class StyleOverride {
        Color color = null;
        Boolean bold = null, italic = null;
        Integer size = null;

        void applyTo(Style s) {
            if (color != null) s.color = color;
            if (bold != null) s.bold = bold;
            if (italic != null) s.italic = italic;
            if (size != null) s.size = size;
        }
    }

    static class TagCtx {
        String name;
        Style savedStyle;
        boolean isHeading = false, isLink = false, isOrdered = false;
        int listCounter = 0;
    }

    static Page render(String url) throws Exception {
        String html = fetch(url);
        html = stripScriptsStyles(html);

        BufferedImage img = new BufferedImage(PAGE_WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH, MAX_HEIGHT);

        List<Link> links = new ArrayList<>();
        URL base = null;
        try { base = toURL(url); } catch (Exception e) {}

        Style curStyle = new Style();
        Stack<TagCtx> tagStack = new Stack<>();
        int x = 4, y = 16;

        boolean inLink = false;
        String linkUrl = null;
        int linkStartX = 0, linkStartY = 0, linkLastX = 0;

        int i = 0, hlen = html.length();

        while (i < hlen && y < MAX_HEIGHT - 30) {
            int lt = html.indexOf('<', i);
            String textPart;
            if (lt < 0) { textPart = html.substring(i); i = hlen; }
            else { textPart = html.substring(i, lt); i = lt; }

            if (textPart.length() > 0) {
                String decoded = decodeEntities(textPart);
                String[] words = decoded.split("\\s+");
                g.setFont(curStyle.font());
                FontMetrics fm = g.getFontMetrics();
                for (String w : words) {
                    if (w.length() == 0) continue;
                    int ww = fm.stringWidth(w + " ");
                    if (x + ww > PAGE_WIDTH - 4 && x > 4) {
                        x = 4;
                        y += curStyle.lineHeight();
                        if (y > MAX_HEIGHT - 30) break;
                    }
                    g.setColor(inLink ? new Color(0, 0, 180) : curStyle.color);
                    g.drawString(w, x, y);
                    if (inLink) {
                        g.drawLine(x, y + 2, x + fm.stringWidth(w), y + 2);
                        linkLastX = x + fm.stringWidth(w);
                    }
                    x += ww;
                }
            }

            if (i >= hlen) break;
            int gt = html.indexOf('>', i + 1);
            if (gt < 0) break;
            String rawTag = html.substring(i + 1, gt).trim();
            i = gt + 1;

            boolean closing = rawTag.startsWith("/");
            if (closing) rawTag = rawTag.substring(1).trim();
            boolean selfClose = rawTag.endsWith("/");
            if (selfClose) rawTag = rawTag.substring(0, rawTag.length() - 1).trim();

            String tagName = rawTag, attrs = "";
            int sp = rawTag.indexOf(' ');
            if (sp > 0) {
                tagName = rawTag.substring(0, sp).toLowerCase();
                attrs = rawTag.substring(sp + 1);
            }
            tagName = tagName.toLowerCase();

            if (closing) {
                int idx = -1;
                for (int j = tagStack.size() - 1; j >= 0; j--) {
                    if (tagStack.get(j).name.equals(tagName)) { idx = j; break; }
                }
                if (idx >= 0) {
                    while (tagStack.size() > idx) {
                        TagCtx c = tagStack.pop();
                        if (c.savedStyle != null) curStyle = c.savedStyle;
                        if (c.isHeading) { y += 6; x = 4; }
                        else if (c.name.equals("p") || c.name.equals("div")
                              || c.name.equals("tr") || c.name.equals("table")) {
                            y += curStyle.lineHeight(); x = 4;
                        }
                        if (c.isLink) {
                            if (linkUrl != null) {
                                links.add(new Link(linkStartX, Math.max(0, linkStartY - 10),
                                    Math.max(linkLastX, linkStartX + 4), y + 2, linkUrl));
                            }
                            inLink = false; linkUrl = null;
                        }
                    }
                }
                continue;
            }

            if (tagName.equals("br")) { x = 4; y += curStyle.lineHeight(); continue; }
            if (tagName.equals("hr")) {
                x = 4; y += 4;
                g.setColor(new Color(180, 180, 180));
                g.drawLine(4, y, PAGE_WIDTH - 4, y);
                y += 6;
                continue;
            }
            if (tagName.equals("img")) {
                String src = attrValue(attrs, "src");
                if (src != null && base != null) {
                    try { src = resolveURL(base, src).toString(); } catch (Exception e) {}
                    x = 4; y += 2;
                    BufferedImage im = loadImage(src);
                    if (im != null) {
                        int iw = im.getWidth(), ih = im.getHeight();
                        int maxW = PAGE_WIDTH - 8;
                        if (iw > maxW) { ih = ih * maxW / iw; iw = maxW; }
                        if (ih > 220) { iw = iw * 220 / ih; ih = 220; }
                        if (iw > 0 && ih > 0 && y + ih < MAX_HEIGHT - 30) {
                            g.drawImage(im, 4, y, iw, ih, null);
                            if (inLink && linkUrl != null) {
                                links.add(new Link(4, y, 4 + iw, y + ih, linkUrl));
                            }
                            y += ih + 4;
                        }
                    } else {
                        g.setColor(new Color(150, 150, 150));
                        g.drawString("[ảnh: " + shorten(src, 40) + "]", 4, y);
                        y += curStyle.lineHeight();
                    }
                }
                continue;
            }

            StyleOverride ov = parseStyleAttr(attrs);
            TagCtx ctx = new TagCtx();
            ctx.name = tagName;

            if (tagName.equals("a")) {
                String href = attrValue(attrs, "href");
                if (href != null && base != null && !inLink) {
                    try { href = resolveURL(base, href).toString(); } catch (Exception e) {}
                    inLink = true;
                    linkUrl = href;
                    linkStartX = x; linkStartY = y; linkLastX = x;
                    ctx.isLink = true;
                }
            } else if (tagName.equals("b") || tagName.equals("strong")) {
                ctx.savedStyle = curStyle;
                curStyle = curStyle.copy(); curStyle.bold = true;
            } else if (tagName.equals("i") || tagName.equals("em")) {
                ctx.savedStyle = curStyle;
                curStyle = curStyle.copy(); curStyle.italic = true;
            } else if (tagName.length() == 2 && tagName.charAt(0) == 'h'
                    && tagName.charAt(1) >= '1' && tagName.charAt(1) <= '6') {
                int lv = tagName.charAt(1) - '0';
                ctx.savedStyle = curStyle;
                curStyle = curStyle.copy();
                curStyle.bold = true;
                curStyle.size = 20 - lv * 2;
                y += 6; x = 4;
                ctx.isHeading = true;
            } else if (tagName.equals("ul") || tagName.equals("ol")) {
                ctx.isOrdered = tagName.equals("ol");
                y += 2;
            } else if (tagName.equals("li")) {
                TagCtx list = null;
                for (int j = tagStack.size() - 1; j >= 0; j--) {
                    String nm = tagStack.get(j).name;
                    if (nm.equals("ul") || nm.equals("ol")) { list = tagStack.get(j); break; }
                }
                if (list != null) {
                    list.listCounter++;
                    if (list.listCounter > 1) { y += curStyle.lineHeight(); x = 4; }
                    int indent = 4;
                    for (int j = 0; j < tagStack.size(); j++) {
                        String nm = tagStack.get(j).name;
                        if (nm.equals("ul") || nm.equals("ol")) indent += 12;
                    }
                    String prefix = list.isOrdered ? (list.listCounter + ". ") : "\u2022 ";
                    x = indent - 8;
                    g.setColor(curStyle.color);
                    g.setFont(curStyle.font());
                    g.drawString(prefix, x, y);
                    x += g.getFontMetrics().stringWidth(prefix);
                }
            }

            if (ov != null) {
                if (ctx.savedStyle == null) ctx.savedStyle = curStyle;
                Style tmp = curStyle.copy();
                ov.applyTo(tmp);
                curStyle = tmp;
            }

            if (!selfClose && tagStack.size() < 200) tagStack.push(ctx);
        }

        if (inLink && linkUrl != null) {
            links.add(new Link(linkStartX, Math.max(0, linkStartY - 10),
                Math.max(linkLastX, linkStartX + 4), y + 2, linkUrl));
        }

        int finalH = Math.min(y + 12, MAX_HEIGHT);
        g.dispose();

        BufferedImage result = new BufferedImage(PAGE_WIDTH, finalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = result.createGraphics();
        g2.drawImage(img.getSubimage(0, 0, PAGE_WIDTH, finalH), 0, 0, null);
        g2.dispose();
        return new Page(result, links);
    }

    static String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile(
            "(?is)\\b" + name + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
        ).matcher(attrs);
        if (m.find()) {
            if (m.group(2) != null) return m.group(2);
            if (m.group(3) != null) return m.group(3);
            if (m.group(4) != null) return m.group(4);
        }
        return null;
    }

    static StyleOverride parseStyleAttr(String attrs) {
        String style = attrValue(attrs, "style");
        if (style == null || style.length() == 0) return null;
        StyleOverride ov = new StyleOverride();
        for (String p : style.split(";")) {
            int idx = p.indexOf(':');
            if (idx < 0) continue;
            String k = p.substring(0, idx).trim().toLowerCase();
            String v = p.substring(idx + 1).trim().toLowerCase();
            try {
                if (k.equals("color")) {
                    Color c = parseColor(v);
                    if (c != null) ov.color = c;
                } else if (k.equals("font-weight")) {
                    ov.bold = v.equals("bold") || v.equals("700")
                           || v.equals("800") || v.equals("900");
                } else if (k.equals("font-style")) {
                    ov.italic = v.equals("italic") || v.equals("oblique");
                } else if (k.equals("font-size")) {
                    int sz = parseFontSize(v);
                    if (sz > 0) ov.size = sz;
                }
            } catch (Exception ignore) {}
        }
        return ov;
    }

    static Color parseColor(String v) {
        if (v.startsWith("#")) {
            String h = v.substring(1);
            if (h.length() == 3)
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            if (h.length() == 6) {
                try { return new Color(Integer.parseInt(h, 16)); } catch (Exception e) {}
            }
        } else if (v.startsWith("rgb(")) {
            try {
                String[] p = v.substring(4, v.length() - 1).split(",");
                return new Color(
                    Integer.parseInt(p[0].trim()),
                    Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()));
            } catch (Exception e) {}
        }
        switch (v) {
            case "black": return Color.BLACK;
            case "white": return Color.WHITE;
            case "red": return Color.RED;
            case "green": return new Color(0, 128, 0);
            case "blue": return Color.BLUE;
            case "gray": case "grey": return Color.GRAY;
            case "yellow": return Color.YELLOW;
            case "orange": return Color.ORANGE;
            case "purple": return new Color(128, 0, 128);
            case "navy": return new Color(0, 0, 128);
            case "pink": return Color.PINK;
            case "cyan": return Color.CYAN;
        }
        return null;
    }

    static int parseFontSize(String v) {
        try {
            if (v.endsWith("px"))
                return Math.max(8, Math.min(24, Integer.parseInt(v.substring(0, v.length() - 2).trim())));
            if (v.endsWith("pt"))
                return Math.max(8, Math.min(24, (int)(Integer.parseInt(v.substring(0, v.length() - 2).trim()) * 1.33)));
        } catch (Exception e) {}
        return 0;
    }

    static BufferedImage loadImage(String url) {
        synchronized (imgCache) {
            if (imgCache.containsKey(url)) return imgCache.get(url);
        }
        try {
            HttpURLConnection c = (HttpURLConnection) toURL(url).openConnection();
            c.setRequestProperty("User-Agent", "MiniProxy/2.0");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getContentLength() > MAX_IMG_BYTES) return null;
            InputStream is = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n, total = 0;
            while ((n = is.read(b)) > 0) {
                total += n;
                if (total > MAX_IMG_BYTES) { is.close(); return null; }
                buf.write(b, 0, n);
            }
            is.close();
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(buf.toByteArray()));
            if (im != null) {
                synchronized (imgCache) {
                    if (imgCache.size() > 100) imgCache.clear();
                    imgCache.put(url, im);
                }
            }
            return im;
        } catch (Exception e) {
            return null;
        }
    }

    static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) toURL(url).openConnection();
        c.setRequestProperty("User-Agent", "MiniProxy/2.0 (J2ME)");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setInstanceFollowRedirects(true);
        InputStream is = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
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

    static String stripScriptsStyles(String html) {
        html = html.replaceAll("(?is)<script.*?</script>", " ");
        html = html.replaceAll("(?is)<style.*?</style>", " ");
        html = html.replaceAll("(?is)<!--.*?-->", " ");
        return html;
    }

    static String decodeEntities(String s) {
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                int semi = s.indexOf(';', i);
                if (semi > 0 && semi - i < 10) {
                    String ent = s.substring(i + 1, semi);
                    String dec = null;
                    if (ent.startsWith("#")) {
                        try {
                            int code = ent.startsWith("#x") || ent.startsWith("#X")
                                ? Integer.parseInt(ent.substring(2), 16)
                                : Integer.parseInt(ent.substring(1));
                            dec = new String(Character.toChars(code));
                        } catch (Exception e) {}
                    } else {
                        switch (ent) {
                            case "amp": dec = "&"; break;
                            case "lt": dec = "<"; break;
                            case "gt": dec = ">"; break;
                            case "quot": dec = "\""; break;
                            case "apos": dec = "'"; break;
                            case "nbsp": dec = " "; break;
                            case "copy": dec = "©"; break;
                            case "reg": dec = "®"; break;
                            case "hellip": dec = "…"; break;
                            case "mdash": dec = "—"; break;
                            case "ndash": dec = "–"; break;
                        }
                    }
                    if (dec != null) { sb.append(dec); i = semi + 1; continue; }
                }
            }
            sb.append(c); i++;
        }
        return sb.toString();
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
