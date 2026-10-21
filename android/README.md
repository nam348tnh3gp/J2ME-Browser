# MiniBrowser Android Port

Native Android port of the J2ME MiniBrowser.

## Structure
All Android project files are under the `android/` directory.

## Building

### Locally
```bash
cd android
# Requires JDK 17 + Android SDK
./gradlew assembleDebug   # or gradle assembleDebug if no wrapper yet
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions
See `.github/workflows/android.yml`. It builds the debug APK on push to the android/ directory.

## Notes
- Adaptive icons are provided for Android 8+ (API 26).
- For older densities, you may generate PNGs from the vector drawable using Android Studio Asset Studio and place them in `app/src/main/res/mipmap-*dpi/`.
- Proxy configuration is required on first run (same protocol as J2ME version).
- The project uses cleartext traffic for HTTP proxy connections.

## Proxy Protocol
Same as the original:
```
Client → Proxy:  int width, int height, int urlLen, byte[urlLen] url
Proxy → Client:  int w, int h, int imgLen, byte[imgLen], int linkCount,
                 [int x1, int y1, int x2, int y2, int urlLen, byte[urlLen]] * linkCount
```
