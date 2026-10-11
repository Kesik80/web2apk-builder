package com.web2apk.template;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_GEO = 1;
    private static final int REQ_MEDIA = 2;
    private static final int REQ_FILE = 3;

    private static final int IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private WebView web;
    private String startHost = "";
    private String screenMode = "normal";
    private String orientation = "auto";
    private String barsScript = "";
    private boolean lightStatus = false;
    private boolean lightNav = false;

    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

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
        } catch (Exception ignored) {
        }
        setTitle(name);
        barsScript = readAsset("bars.js");
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

        web.addJavascriptInterface(new Bridge(), "W2A");

        web.setWebViewClient(new WebViewClient() {
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
                if (!barsScript.isEmpty() && isSameSite(u.getHost())) {
                    view.evaluateJavascript(barsScript, null);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
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

        setContentView(web);
        updateUi();
        web.loadUrl(url);
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
