package com.minibrowser;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.net.*;
import java.util.Vector;

public class BrowserEngine {
    public interface Callback {
        void onStart();
        void onResult(String url, Bitmap bm, Vector<LinkRect> links, int bytes);
        void onError(String msg);
        void onStop();
    }

    private volatile Socket socket;
    private volatile boolean stop;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public void load(final String host, final int port, final String url,
                     final int w, final int h, final Callback cb) {
        abort();
        stop = false;
        ui.post(cb::onStart);
        new Thread(() -> doLoad(host, port, url, w, h, cb)).start();
    }

    private void doLoad(String host, int port, String url, int w, int h, Callback cb) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 15000);
            synchronized (this) {
                if (stop) { s.close(); return; }
                socket = s;
            }
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            out.writeInt(w);
            out.writeInt(h);
            byte[] ub = url.getBytes("UTF-8");
            out.writeInt(ub.length);
            out.write(ub);
            out.flush();

            DataInputStream in = new DataInputStream(s.getInputStream());
            int rw = in.readInt(), rh = in.readInt(), len = in.readInt();
            if (len <= 0 || len > 5 * 1024 * 1024) throw new IOException("size");
            byte[] data = new byte[len];
            in.readFully(data);

            int n = in.readInt();
            Vector<LinkRect> links = new Vector<>();
            for (int i = 0; i < n; i++) {
                int x1 = in.readInt(), y1 = in.readInt();
                int x2 = in.readInt(), y2 = in.readInt();
                int ul = in.readInt();
                byte[] ubb = new byte[ul];
                in.readFully(ubb);
                links.add(new LinkRect(x1, y1, x2, y2, new String(ubb, "UTF-8")));
            }
            try { s.close(); } catch (Exception ignored) {}
            s = null;

            if (stop) return;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length);
            final Bitmap fbm = bm;
            final Vector<LinkRect> flinks = links;
            ui.post(() -> {
                cb.onResult(url, fbm, flinks, data.length);
                cb.onStop();
            });
        } catch (Exception e) {
            if (!stop) ui.post(() -> { cb.onError(e.getMessage()); cb.onStop(); });
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
            synchronized (this) { if (socket == s) socket = null; }
        }
    }

    public void abort() {
        stop = true;
        Socket s = socket;
        socket = null;
        if (s != null) try { s.close(); } catch (Exception ignored) {}
    }
}
