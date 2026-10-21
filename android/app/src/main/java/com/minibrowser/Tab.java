package com.minibrowser;

import android.graphics.Bitmap;
import java.util.Vector;

public class Tab {
    public String url = "home://";
    public Bitmap bitmap;
    public Vector<LinkRect> links;
    public int scrollY = 0;
    public int lastBytes = 0;
}
