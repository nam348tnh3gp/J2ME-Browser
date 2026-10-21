package com.minibrowser;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.*;

import java.util.Vector;

public class BrowserView extends View {

    public interface Listener {
        void onMenuClicked();
        void onBackClicked();
        void onUrlClicked();
        void onReloadClicked();
        void onStopClicked();
        void onHomeDialClicked(int index);
        void onLinkClicked(String url);
        void onLongPressLink(String url);
    }

    private Listener listener;
    private FontMetrics fm;
    private Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String status = "";
    private boolean loading = false;
    private int loadingFrame = 0;
    private Handler ui = new Handler(Looper.getMainLooper());
    private Runnable loadingTicker;

    public int selectedLink = -1;

    // touch state
    private float pressX, pressY;
    private long pressTime, lastDragTime, lastTapTime;
    private float lastDragY, velocity;
    private boolean isDragging, longPressed;
    private float lastTapX, lastTapY;
    private float slop;
    private volatile boolean momentumCancelled;
    private Runnable longPressTask;

    // current tab data (set by activity)
    public Tab tab = new Tab();
    public Vector<String[]> speedDial = new Vector<>();

    // cache of content size
    private int lastContentW, lastContentH;

    public BrowserView(Context c) { super(c); init(); }
    public BrowserView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        paint.setAntiAlias(true);
        fm = getPaintMetrics();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private FontMetrics getPaintMetrics() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        return p.getFontMetrics();
    }

    public void setListener(Listener l) { listener = l; }

    public void setStatus(String s) { status = s; invalidate(); }

    public void startLoading() {
        if (loading) return;
        loading = true; loadingFrame = 0;
        loadingTicker = new Runnable() {
            @Override public void run() {
                if (!loading) return;
                loadingFrame = (loadingFrame + 1) % 4;
                invalidate();
                ui.postDelayed(this, 180);
            }
        };
        ui.post(loadingTicker);
    }

    public void stopLoading() {
        loading = false;
        if (loadingTicker != null) { ui.removeCallbacks(loadingTicker); loadingTicker = null; }
    }

    // ── layout helpers ──
    private int urlBarH() { return (int)(Math.max(16, -fm.ascent + fm.descent + 6)); }
    private int softBarH() { return (int)(Math.max(22, -fm.ascent + fm.descent + 8)); }
    private int contentTop() { return urlBarH(); }
    private int contentBottom() { return getHeight() - softBarH(); }
    private int contentViewH() { return contentBottom() - contentTop(); }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (tab.bitmap != null && tab.bitmap.getWidth() != w && listener != null) {
            // rotate → ask activity to reload
            listener.onReloadClicked();
        }
    }

    // ── drawing ──
    @Override
    protected void onDraw(Canvas g) {
        super.onDraw(g);
        paint.setColor(0xFFFFFFFF);
        g.drawRect(0, 0, getWidth(), getHeight(), paint);

        int top = contentTop();
        int bottom = contentBottom();

        if ("home://".equals(tab.url)) {
            drawSpeedDial(g, top, bottom);
        } else if (tab.bitmap != null) {
            g.save();
            g.clipRect(0, top, getWidth(), bottom);
            g.drawBitmap(tab.bitmap, 0, top - tab.scrollY, paint);
            drawLinkHighlight(g, top);
            g.restore();
            drawScrollbar(g, top);
        } else if (loading) {
            drawCentered(g, "Đang tải" + dots(loadingFrame), 0xFF888888);
        } else if (!status.isEmpty()) {
            drawCentered(g, status, 0xFF666666);
        }

        drawUrlBar(g);
        drawSoftBar(g);
    }

    private void drawCentered(Canvas g, String msg, int color) {
        paint.setColor(color);
        float tw = paint.measureText(msg);
        float x = (getWidth() - tw) / 2f;
        float y = getHeight() / 2f;
        g.drawText(msg, x, y, paint);
    }

    private void drawUrlBar(Canvas g) {
        int h = urlBarH();
        paint.setColor(loading ? 0xFFAADDFF : 0xFFEEEEEE);
        g.drawRect(0, 0, getWidth(), h, paint);
        paint.setColor(0xFFCCCCCC);
        g.drawLine(0, h - 1, getWidth(), h - 1, paint);

        paint.setColor(0xFF000000);
        float ty = (h - (-fm.ascent + fm.descent)) / 2f - fm.ascent;
        float btnW = paint.measureText("↻") + 8;
        g.drawText("◄", 8, ty, paint);
        g.drawText(loading ? "×" : "↻", getWidth() - btnW, ty, paint);

        String shown = "home://".equals(tab.url) ? "Speed Dial" : tab.url;
        if (loading) shown = "Đang tải" + dots(loadingFrame);
        shown = truncate(shown, getWidth() - 2 * btnW - 20);
        paint.setColor(loading ? 0xFF000088 : 0xFF333333);
        g.drawText(shown, btnW + 4, ty, paint);
    }

    private void drawSoftBar(Canvas g) {
        int h = softBarH();
        int y = getHeight() - h;
        paint.setColor(0xFFDDDDDD);
        g.drawRect(0, y, getWidth(), getHeight(), paint);
        paint.setColor(0xFFAAAAAA);
        g.drawLine(0, y, getWidth(), y, paint);
        paint.setColor(0xFF000000);
        float ty = y + (h - (-fm.ascent + fm.descent)) / 2f - fm.ascent;
        g.drawText("Menu", 8, ty, paint);
        String r = "Lùi";
        float rw = paint.measureText(r);
        g.drawText(r, getWidth() - rw - 8, ty, paint);
    }

    private void drawLinkHighlight(Canvas g, int top) {
        if (selectedLink < 0 || tab.links == null || selectedLink >= tab.links.size()) return;
        LinkRect lr = tab.links.get(selectedLink);
        int y1 = top + (lr.y1 - tab.scrollY) - 2;
        int y2 = top + (lr.y2 - tab.scrollY) + 2;
        int bottom = contentBottom();
        if (y2 < top || y1 > bottom) return;
        if (y1 < top) y1 = top;
        if (y2 > bottom) y2 = bottom;
        int x1 = Math.max(0, lr.x1 - 2);
        int x2 = Math.min(getWidth() - 1, lr.x2 + 2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xFFFF6600);
        g.drawRect(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFF6600);
        g.drawRect(x1, y1, x1 + 18, y1 + 14, paint);
        paint.setColor(0xFFFFFFFF);
        g.drawText(String.valueOf(selectedLink + 1), x1 + 3, y1 + 12, paint);
    }

    private void drawScrollbar(Canvas g, int top) {
        int viewH = contentViewH();
        if (tab.bitmap == null) return;
        int imgH = tab.bitmap.getHeight();
        if (imgH <= viewH || viewH < 10) return;
        int sbW = 4;
        int trackH = viewH - 4;
        int thumbH = Math.max(20, trackH * viewH / imgH);
        int maxScroll = Math.max(1, imgH - viewH);
        int thumbY = top + 2 + (trackH - thumbH) * tab.scrollY / maxScroll;
        paint.setColor(0xAAAAAA);
        g.drawRect(getWidth() - sbW - 2, thumbY, getWidth() - 2, thumbY + thumbH, paint);
    }

    private void drawSpeedDial(Canvas g, int top, int bottom) {
        int w = getWidth(), h = bottom - top;
        int cols = 3, rows = 3;
        int cellW = w / cols, cellH = h / rows;
        int gap = 6;
        paint.setColor(0xFFFFFFFF);
        g.drawRect(0, top, w, bottom, paint);
        for (int i = 0; i < cols * rows && i < speedDial.size(); i++) {
            int col = i % cols, row = i / cols;
            int x = col * cellW + gap;
            int y = top + row * cellH + gap;
            int cw = cellW - 2 * gap, ch = cellH - 2 * gap;
            int shade = 0xFF335577 + i * 0x111122;
            if (i == selectedLink) shade = 0xFFFF8800;
            paint.setColor(shade);
            g.drawRoundRect(new RectF(x, y, x + cw, y + ch), 10, 10, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(i == selectedLink ? 3 : 1);
            paint.setColor(i == selectedLink ? 0xFFFFFF00 : 0xFFFFFFFF);
            g.drawRoundRect(new RectF(x, y, x + cw, y + ch), 10, 10, paint);
            paint.setStyle(Paint.Style.FILL);

            String[] e = speedDial.get(i);
            float oldSize = paint.getTextSize();
            paint.setTextSize(oldSize * 1.8f);
            paint.setColor(0xFFFFFFFF);
            String initial = e[0].isEmpty() ? "?" : e[0].substring(0, 1).toUpperCase();
            float iw = paint.measureText(initial);
            g.drawText(initial, x + (cw - iw) / 2f, y + ch * 0.5f, paint);
            paint.setTextSize(oldSize);

            String title = truncate(e[0], cw - 8);
            float tw = paint.measureText(title);
            g.drawText(title, x + (cw - tw) / 2f, y + ch - 8, paint);
        }
    }

    private String dots(int n) {
        return n == 1 ? "." : n == 2 ? ".." : n == 3 ? "..." : "";
    }

    private String truncate(String s, float maxW) {
        if (s == null) return "";
        if (paint.measureText(s) <= maxW) return s;
        while (s.length() > 1 && paint.measureText(s + "…") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ── scrolling ──
    public void scroll(int dy) {
        if (tab.bitmap == null) return;
        tab.scrollY += dy;
        if (tab.scrollY < 0) tab.scrollY = 0;
        int max = Math.max(0, tab.bitmap.getHeight() - contentViewH());
        if (tab.scrollY > max) tab.scrollY = max;
        invalidate();
    }

    // ── hit test ──
    int linkAt(int x, int y) {
        if (tab.bitmap == null || tab.links == null) return -1;
        int pad = Math.max(4, getWidth() / 40);
        int iy = y - contentTop() + tab.scrollY;
        int best = -1; long bestD = Long.MAX_VALUE;
        for (int i = 0; i < tab.links.size(); i++) {
            LinkRect lr = tab.links.get(i);
            if (x >= lr.x1 - pad && x <= lr.x2 + pad
                    && iy >= lr.y1 - pad && iy <= lr.y2 + pad) {
                long dx = x - (lr.x1 + lr.x2) / 2;
                long dy = iy - (lr.y1 + lr.y2) / 2;
                long d = dx * dx + dy * dy;
                if (d < bestD) { bestD = d; best = i; }
            }
        }
        return best;
    }

    // ── touch ──
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int x = (int) e.getX(), y = (int) e.getY();
        slop = Math.max(6, Math.min(getWidth(), getHeight()) / 30f);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                momentumCancelled = true;
                pressX = x; pressY = y; pressTime = System.currentTimeMillis();
                lastDragY = y; lastDragTime = pressTime;
                velocity = 0; isDragging = false; longPressed = false;
                selectedLink = y >= contentTop() ? linkAt(x, y) : -1;
                invalidate();
                scheduleLongPress(x, y);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!isDragging) {
                    if (Math.abs(x - pressX) <= slop && Math.abs(y - pressY) <= slop) return true;
                    isDragging = true;
                    cancelLongPress();
                    selectedLink = -1;
                    lastDragY = pressY;
                    lastDragTime = System.currentTimeMillis();
                }
                int dy = (int)(lastDragY - y);
                if (dy == 0) return true;
                long now = System.currentTimeMillis();
                long dt = now - lastDragTime;
                if (dt > 0 && dt < 300) {
                    float iv = dy * 16f / dt;
                    velocity = velocity * 0.6f + iv * 0.4f;
                }
                scroll(dy);
                lastDragY = y; lastDragTime = now;
                return true;

            case MotionEvent.ACTION_UP:
                cancelLongPress();
                if (longPressed) { selectedLink = -1; invalidate(); return true; }
                if (isDragging) {
                    if (Math.abs(velocity) > 1.5f) applyMomentum(velocity);
                    return true;
                }
                boolean onLink = y >= contentTop() && linkAt(x, y) >= 0;
                long now2 = System.currentTimeMillis();
                if (!onLink && now2 - lastTapTime < 300
                        && Math.abs(x - lastTapX) < slop * 3
                        && Math.abs(y - lastTapY) < slop * 3) {
                    handleDoubleTap();
                    lastTapTime = 0;
                    return true;
                }
                lastTapTime = now2; lastTapX = x; lastTapY = y;
                handleTap(x, y);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void scheduleLongPress(final int x, final int y) {
        cancelLongPress();
        longPressTask = () -> {
            if (!isDragging) {
                longPressed = true;
                if (listener != null && y >= contentTop()) {
                    int i = linkAt(x, y);
                    if (i >= 0) listener.onLongPressLink(tab.links.get(i).url);
                }
            }
        };
        ui.postDelayed(longPressTask, 500);
    }

    private void cancelLongPress() {
        if (longPressTask != null) { ui.removeCallbacks(longPressTask); longPressTask = null; }
    }

    private void applyMomentum(final float v0) {
        momentumCancelled = false;
        ui.post(new Runnable() {
            float v = v0;
            @Override public void run() {
                if (Math.abs(v) < 0.5f || momentumCancelled) return;
                scroll((int) v);
                v *= 0.90f;
                ui.postDelayed(this, 16);
            }
        });
    }

    private void handleTap(int x, int y) {
        int ubH = contentTop();
        selectedLink = -1;

        if (y < ubH) {
            float btnW = paint.measureText("↻") + 8;
            if (x < btnW) { if (listener != null) listener.onBackClicked(); return; }
            if (x > getWidth() - btnW) {
                if (listener != null) {
                    if (loading) listener.onStopClicked();
                    else listener.onReloadClicked();
                }
                return;
            }
            if (listener != null) listener.onUrlClicked();
            return;
        }

        if (y > getHeight() - softBarH()) {
            if (x < getWidth() / 2) { if (listener != null) listener.onMenuClicked(); }
            else { if (listener != null) listener.onBackClicked(); }
            return;
        }

        if ("home://".equals(tab.url)) {
            int w = getWidth(), h = contentBottom() - ubH;
            int col = Math.min(2, x * 3 / w);
            int row = Math.min(2, (y - ubH) * 3 / h);
            int idx = row * 3 + col;
            if (idx >= 0 && idx < speedDial.size() && listener != null)
                listener.onHomeDialClicked(idx);
            return;
        }

        int i = linkAt(x, y);
        if (i >= 0 && listener != null) listener.onLinkClicked(tab.links.get(i).url);
        invalidate();
    }

    private void handleDoubleTap() {
        if (tab.scrollY > 0) tab.scrollY = 0;
        else if (tab.bitmap != null) {
            int viewH = contentViewH();
            int pageH = tab.bitmap.getHeight();
            tab.scrollY = Math.max(0, Math.min(pageH / 2, pageH - viewH));
        }
        invalidate();
    }

    // ── d-pad ──
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (tab.bitmap == null) {
            return handleHomeKeys(keyCode, e);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    moveLink(-1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:  moveLink(1);  return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:  scroll(-48);  return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: scroll(48);   return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:      activate();   return true;
            case KeyEvent.KEYCODE_MENU:       if (listener != null) listener.onMenuClicked(); return true;
            case KeyEvent.KEYCODE_BACK:       if (listener != null) listener.onBackClicked(); return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private boolean handleHomeKeys(int keyCode, KeyEvent e) {
        int n = speedDial.size();
        if (n == 0) return false;
        int cols = 3;
        if (selectedLink < 0) selectedLink = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    selectedLink = Math.max(0, selectedLink - cols); break;
            case KeyEvent.KEYCODE_DPAD_DOWN:  selectedLink = Math.min(n - 1, selectedLink + cols); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:  if (selectedLink % cols != 0) selectedLink--; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (selectedLink % cols != cols - 1 && selectedLink < n - 1) selectedLink++; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (listener != null) listener.onHomeDialClicked(selectedLink);
                return true;
            default: return false;
        }
        invalidate();
        return true;
    }

    private void moveLink(int delta) {
        if (tab.links == null || tab.links.isEmpty()) return;
        int n = tab.links.size();
        if (selectedLink < 0) selectedLink = delta > 0 ? 0 : n - 1;
        else selectedLink = (selectedLink + delta + n) % n;
        ensureVisible();
        invalidate();
    }

    private void activate() {
        if (tab.links == null || tab.links.isEmpty()) return;
        if (selectedLink < 0) selectedLink = 0;
        if (listener != null) listener.onLinkClicked(tab.links.get(selectedLink).url);
    }

    public void ensureVisible() {
        if (selectedLink < 0 || tab.links == null || selectedLink >= tab.links.size()) return;
        LinkRect lr = tab.links.get(selectedLink);
        int viewH = contentViewH();
        if (lr.y1 < tab.scrollY) tab.scrollY = lr.y1;
        else if (lr.y2 > tab.scrollY + viewH) tab.scrollY = lr.y2 - viewH + 8;
        if (tab.scrollY < 0) tab.scrollY = 0;
        invalidate();
    }
}
