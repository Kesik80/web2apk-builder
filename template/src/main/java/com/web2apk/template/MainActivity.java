package com.web2apk.template;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.PermissionRequest;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_GEO = 1;
    private static final int REQ_MEDIA = 2;
    private static final int REQ_FILE = 3;
    private static final int REQ_BG_LOCATION = 4;

    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    /** In ZIP mode the site is served from assets/site under this virtual HTTPS origin. */
    private static final String ZIP_HOST = "appassets.androidplatform.net";

    private WebView web;
    private boolean zipMode = false;
    private String startHost = "";
    private String screenMode = "normal";
    private String orientation = "auto";
    private String barsScript = "";
    private String earlyScript = "";
    private boolean lightStatus = false;
    private boolean lightNav = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private String pendingBgConfig;

    private String readAsset(String file) {
        try (InputStream in = getAssets().open(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void colors(final String top, final String bottom) {
            runOnUiThread(() -> applyBars(top, bottom));
        }

        // ---- Background location (see LocationService) ----

        @JavascriptInterface
        public boolean bgLocationAvailable() {
            return true;
        }

        @JavascriptInterface
        public boolean bgLocationRunning() {
            return LocationService.running;
        }

        @JavascriptInterface
        public void bgLocationStart(final String configJson) {
            runOnUiThread(() -> {
                // Only the wrapped site may start it, not a foreign iframe
                if (web == null || !isSameSite(Uri.parse(String.valueOf(web.getUrl())).getHost())) return;
                pendingBgConfig = configJson;
                ensureBgPermissions();
            });
        }

        @JavascriptInterface
        public void bgLocationStop() {
            runOnUiThread(() -> {
                pendingBgConfig = null;
                stopService(new Intent(MainActivity.this, LocationService.class));
            });
        }

        @JavascriptInterface
        public void bgBatterySettings() {
            runOnUiThread(() -> openBatterySettings());
        }
    }

    private void ensureBgPermissions() {
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (need.isEmpty()) {
            startBgLocation();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_BG_LOCATION);
        }
    }

    private void startBgLocation() {
        String cfg = pendingBgConfig;
        pendingBgConfig = null;
        if (cfg == null) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent i = new Intent(this, LocationService.class).putExtra(LocationService.EXTRA_CONFIG, cfg);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {
            return;
        }
        // Battery optimization stops the service with the screen off on many phones.
        // Ask once; afterwards the page can open the dialog via W2A.bgBatterySettings().
        SharedPreferences prefs = getSharedPreferences("w2a", MODE_PRIVATE);
        if (!prefs.getBoolean("askedBattery", false)) {
            prefs.edit().putBoolean("askedBattery", true).apply();
            openBatterySettings();
        }
    }

    private void openBatterySettings() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String url = "about:blank";
        String name = "Web App";
        try {
            JSONObject c = new JSONObject(readAsset("config.json"));
            url = c.optString("url", url);
            name = c.optString("name", name);
            screenMode = c.optString("screen", "normal");
            orientation = c.optString("orientation", "auto");
            zipMode = "zip".equals(c.optString("mode", "url"));
        } catch (Exception ignored) {
        }
        setTitle(name);
        barsScript = readAsset("bars.js");
        earlyScript = readAsset("early.js");
        String host = Uri.parse(url).getHost();
        startHost = host == null ? "" : host;

        if ("nostatus".equals(screenMode)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        applyOrientation();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " Web2APK");

        web.addJavascriptInterface(new Bridge(), "W2A");

        if (zipMode && Build.VERSION.SDK_INT >= 24) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        return serveAsset(request);
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return zipMode ? serveAsset(request) : null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if (("http".equals(scheme) || "https".equals(scheme)) && isSameSite(u.getHost())) {
                    return false;
                }
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String pageUrl) {
                Uri u = Uri.parse(pageUrl);
                if (isSameSite(u.getHost())) {
                    if (!earlyScript.isEmpty()) view.evaluateJavascript(earlyScript, null);
                    if (!barsScript.isEmpty()) view.evaluateJavascript(barsScript, null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // Inject as early as possible; the script guards itself against running twice.
                if (newProgress >= 5 && !earlyScript.isEmpty()) {
                    Uri u = Uri.parse(String.valueOf(view.getUrl()));
                    if (isSameSite(u.getHost())) view.evaluateJavascript(earlyScript, null);
                }
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_GEO);
                }
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                List<String> need = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                        need.add(Manifest.permission.CAMERA);
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                        need.add(Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (need.isEmpty()) {
                    request.deny();
                    return;
                }
                boolean allGranted = true;
                for (String p : need) {
                    if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allGranted = false;
                }
                if (allGranted) {
                    request.grant(request.getResources());
                } else {
                    if (pendingWebPermission != null) pendingWebPermission.deny();
                    pendingWebPermission = request;
                    requestPermissions(need.toArray(new String[0]), REQ_MEDIA);
                }
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                view.setBackgroundColor(Color.BLACK);
                ((FrameLayout) getWindow().getDecorView()).addView(view,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                if ("portrait".equals(orientation)) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                }
                updateUi();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        web.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) ->
                openExternal(Uri.parse(downloadUrl)));

        // Run early.js before any script of the page (sites that check "installed?" at load time
        // would otherwise see a plain browser). The later injections stay as a fallback; the script
        // guards itself against running twice.
        if (!earlyScript.isEmpty()
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            String b = bare(startHost);
            if (!b.isEmpty()) {
                for (String rule : new String[]{"https://" + b, "https://*." + b}) {
                    try {
                        WebViewCompat.addDocumentStartJavaScript(web, earlyScript,
                                Collections.singleton(rule));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        setContentView(web);
        updateUi();
        web.loadUrl(url);
    }

    // ---- ZIP mode: serve the packed site from assets/site ----

    private InputStream openSite(String rel) {
        try {
            return getAssets().open("site/" + rel, android.content.res.AssetManager.ACCESS_STREAMING);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeFor(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "webmanifest": return "application/manifest+json";
            case "svg": return "image/svg+xml";
            case "wasm": return "application/wasm";
            case "txt": return "text/plain";
            case "xml": return "application/xml";
            case "ico": return "image/x-icon";
            case "woff": return "font/woff";
            case "woff2": return "font/woff2";
            case "ttf": return "font/ttf";
            case "otf": return "font/otf";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }

    private static boolean isText(String mime) {
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("javascript");
    }

    private static class LimitedStream extends FilterInputStream {
        private long left;

        LimitedStream(InputStream in, long left) {
            super(in);
            this.left = left;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = super.read(buf, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    private static WebResourceResponse simple(int code, String reason) {
        return new WebResourceResponse("text/plain", "utf-8", code, reason,
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    /** Returns the packed file for requests to the virtual origin, or null for any other host. */
    private WebResourceResponse serveAsset(WebResourceRequest request) {
        Uri u = request.getUrl();
        if (!"https".equals(u.getScheme()) || !ZIP_HOST.equals(u.getHost())) return null;
        String path = u.getPath();
        if (path == null || path.isEmpty()) path = "/";
        if (path.endsWith("/")) path += "index.html";
        String rel = path.substring(1);
        if (rel.contains("..")) return simple(404, "Not Found");

        InputStream in = openSite(rel);
        boolean noExt = !rel.substring(rel.lastIndexOf('/') + 1).contains(".");
        if (in == null && noExt) {
            // /folder -> /folder/index.html, then single-page-app fallback for page navigations
            in = openSite(rel + "/index.html");
            if (in != null) {
                rel = rel + "/index.html";
            } else if (request.isForMainFrame()) {
                in = openSite("index.html");
                rel = "index.html";
            }
        }
        if (in == null) return simple(404, "Not Found");

        String mime = mimeFor(rel);
        String enc = isText(mime) ? "utf-8" : null;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept-Ranges", "bytes");

        String range = null;
        Map<String, String> req = request.getRequestHeaders();
        if (req != null) {
            for (Map.Entry<String, String> e : req.entrySet()) {
                if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            }
        }
        if (range != null && range.startsWith("bytes=")) {
            try {
                long total = in.available();
                String[] parts = range.substring(6).split(",")[0].trim().split("-", -1);
                long start;
                long end;
                if (parts[0].isEmpty()) {
                    long suffix = Long.parseLong(parts[1]);
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                } else {
                    start = Long.parseLong(parts[0]);
                    end = parts.length > 1 && !parts[1].isEmpty() ? Long.parseLong(parts[1]) : total - 1;
                }
                if (end >= total) end = total - 1;
                if (start >= total || start > end) {
                    in.close();
                    return simple(416, "Range Not Satisfiable");
                }
                long skipped = 0;
                while (skipped < start) {
                    long n = in.skip(start - skipped);
                    if (n <= 0) break;
                    skipped += n;
                }
                long len = end - start + 1;
                headers.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
                headers.put("Content-Length", String.valueOf(len));
                return new WebResourceResponse(mime, enc, 206, "Partial Content", headers,
                        new LimitedStream(in, len));
            } catch (Exception ignored) {
                // fall through to a plain full response
                try { in.close(); } catch (IOException ignored2) { }
                in = openSite(rel);
                if (in == null) return simple(404, "Not Found");
            }
        }
        return new WebResourceResponse(mime, enc, 200, "OK", headers, in);
    }

    private void applyOrientation() {
        int o = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if ("portrait".equals(orientation)) {
            o = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if ("landscape".equals(orientation)) {
            o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        }
        setRequestedOrientation(o);
    }

    private void hideCustomView() {
        if (customView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        applyOrientation();
        if (customCallback != null) {
            customCallback.onCustomViewHidden();
            customCallback = null;
        }
        updateUi();
    }

    private void applyBars(String top, String bottom) {
        try {
            int t = Color.parseColor(top);
            int b = Color.parseColor(bottom);
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(t);
            w.setNavigationBarColor(b);
            lightStatus = isLight(t);
            lightNav = isLight(b);
            updateUi();
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static boolean isLight(int c) {
        double l = 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c);
        return l / 255.0 > 0.5;
    }

    private void updateUi() {
        int f;
        if (customView != null || "immersive".equals(screenMode)) {
            f = IMMERSIVE_FLAGS;
        } else {
            f = 0;
            if (lightStatus) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (lightNav) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        getWindow().getDecorView().setSystemUiVisibility(f);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) updateUi();
    }

    private static String bare(String host) {
        if (host == null) return "";
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    private boolean isSameSite(String host) {
        String a = bare(host);
        String b = bare(startHost);
        return a.equals(b) || a.endsWith("." + b);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_GEO && pendingGeoCallback != null) {
            boolean any = false;
            for (int g : grantResults) if (g == PackageManager.PERMISSION_GRANTED) any = true;
            pendingGeoCallback.invoke(pendingGeoOrigin, any, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQ_BG_LOCATION) {
            startBgLocation();   // checks the location permission itself
        } else if (requestCode == REQ_MEDIA && pendingWebPermission != null) {
            boolean all = grantResults.length > 0;
            for (int g : grantResults) if (g != PackageManager.PERMISSION_GRANTED) all = false;
            if (all) {
                pendingWebPermission.grant(pendingWebPermission.getResources());
            } else {
                pendingWebPermission.deny();
            }
            pendingWebPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
