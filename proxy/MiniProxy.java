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

    public static void main(String[] args) throws Exception {
        ServerSocket ss = new ServerSocket(PORT);
        System.out.println("MiniProxy listening on port " + PORT);
        while (true) {
            final Socket s = ss.accept();
            new Thread(new Runnable() {
                public void run() { handle(s); }
            }).start();
        }
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
                out.writeInt(l.x1);
                out.writeInt(l.y1);
                out.writeInt(l.x2);
                out.writeInt(l.y2);
                byte[] ub = l.url.getBytes("UTF-8");
                out.writeInt(ub.length);
                out.write(ub);
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
        String html = fetch(url);
        html = stripScriptsStyles(html);
        List<Token> tokens = tokenize(html, url);

        BufferedImage img = new BufferedImage(PAGE_WIDTH, MAX_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, PAGE_WIDTH, MAX_HEIGHT);

        Font regular = new Font("SansSerif", Font.PLAIN, 11);
        Font bold = new Font("SansSerif", Font.BOLD, 13);
        g.setFont(regular);

        List<Link> links = new ArrayList<>();
        int x = 4, y = 16, lineH = 13;

        int lsx = -1, lsy = -1;
        String pendingUrl = null;

        for (Token t : tokens) {
            if (y > MAX_HEIGHT - 30) break;

            switch (t.type) {
                case Token.LINK_START:
                    pendingUrl = t.value;
                    lsx = x;
                    lsy = y - 10;
                    g.setColor(new Color(0, 0, 180));
                    break;
                case Token.LINK_END:
                    if (pendingUrl != null) {
                        links.add(new Link(lsx, lsy, Math.min(x, PAGE_WIDTH - 2), y, pendingUrl));
                        pendingUrl = null;
                    }
                    g.setColor(Color.BLACK);
                    break;
                case Token.BREAK:
                    x = 4;
                    y += lineH;
                    break;
                case Token.HEADING:
                    x = 4;
                    y += 6;
                    g.setFont(bold);
                    g.setColor(Color.BLACK);
                    y = drawWrapped(g, t.value, x, y, PAGE_WIDTH - 8, bold);
                    y += 4;
                    g.setFont(regular);
                    break;
                case Token.TEXT:
                    g.setFont(regular);
                    String[] words = t.value.split("\\s+");
                    for (String w : words) {
                        if (w.isEmpty()) continue;
                        int ww = g.getFontMetrics().stringWidth(w + " ");
                        if (x + ww > PAGE_WIDTH - 4) {
                            x = 4;
                            y += lineH;
                        }
                        g.drawString(w, x, y);
                        if (pendingUrl != null) {
                            g.drawLine(x, y + 2, x + g.getFontMetrics().stringWidth(w), y + 2);
                        }
                        x += ww;
                    }
                    x += 2;
                    break;
            }
        }
        int finalH = Math.min(y + 12, MAX_HEIGHT);
        g.dispose();

        BufferedImage result = new BufferedImage(PAGE_WIDTH, finalH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = result.createGraphics();
        g2.drawImage(img.getSubimage(0, 0, PAGE_WIDTH, finalH), 0, 0, null);
        g2.dispose();
        return new Page(result, links);
    }

    static int drawWrapped(Graphics2D g, String text, int x, int y, int maxW, Font f) {
        FontMetrics fm = g.getFontMetrics();
        int cx = x;
        String[] words = text.split("\\s+");
        for (String w : words) {
            if (fm.stringWidth(w) + cx > maxW) { cx = x; y += fm.getHeight(); }
            g.drawString(w, cx, y);
            cx += fm.stringWidth(w + " ");
        }
        return y;
    }

    static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "MiniProxy/1.0 (J2ME)");
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
            if (idx >= 0) {
                charset = ct.substring(idx + 8).split("[;\\s]")[0].trim();
            }
        }
        return new String(buf.toByteArray(), charset);
    }

    static String stripScriptsStyles(String html) {
        html = html.replaceAll("(?is)<script.*?</script>", " ");
        html = html.replaceAll("(?is)<style.*?</style>", " ");
        html = html.replaceAll("(?is)<!--.*?-->", " ");
        return html;
    }

    static List<Token> tokenize(String html, String baseUrl) throws Exception {
        List<Token> tokens = new ArrayList<>();
        Pattern p = Pattern.compile(
            "(?is)<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>|</a>|" +
            "<(h[1-6])[^>]*>|</h[1-6]>|" +
            "<(?:p|div|br|li|tr|hr|table)[^>]*>|" +
            "<[^>]+>|([^<]+)"
        );
        Matcher m = p.matcher(html);
        String pendingHref = null;
        while (m.find()) {
            if (m.group(1) != null) {
                String href = m.group(1).trim();
                try { href = new URL(new URL(baseUrl), href).toString(); } catch (Exception e) {}
                tokens.add(new Token(Token.LINK_START, href));
                pendingHref = href;
            } else if (m.group(0).equalsIgnoreCase("</a>")) {
                tokens.add(new Token(Token.LINK_END, ""));
                pendingHref = null;
            } else if (m.group(2) != null) {
                tokens.add(new Token(Token.BREAK, ""));
            } else if (m.group(0).equalsIgnoreCase("</h1>") || m.group(0).equalsIgnoreCase("</h2>")
                    || m.group(0).equalsIgnoreCase("</h3>")) {
                tokens.add(new Token(Token.BREAK, ""));
            } else if (m.group(3) != null) {
                String text = decodeEntities(m.group(3).trim());
                if (!text.isEmpty()) {
                    boolean isHeading = false;
                    // Có thể kiểm tra tag trước đó nếu cần
                    tokens.add(new Token(isHeading ? Token.HEADING : Token.TEXT, text));
                }
            }
        }
        return tokens;
    }

    static String decodeEntities(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
                .replace("&#39;", "'").replace("&#160;", " ");
    }

    static class Token {
        static final int TEXT = 0, LINK_START = 1, LINK_END = 2, BREAK = 3, HEADING = 4;
        int type; String value;
        Token(int t, String v) { type = t; value = v; }
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
