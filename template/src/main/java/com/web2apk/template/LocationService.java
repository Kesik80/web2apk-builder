package com.web2apk.template;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background location for the wrapped site.
 *
 * A web page loses GPS as soon as the screen is off or another app is in front.
 * This foreground service keeps receiving positions and sends each one straight
 * to a URL given by the page (JSON PATCH), so it works while the WebView sleeps.
 *
 * Started by the page through W2A.bgLocationStart(json), see MainActivity.Bridge.
 * Config fields: url (https, required), extra (object merged into every update),
 * stop (object sent once when tracking ends), intervalMs, title, text.
 */
public class LocationService extends Service implements LocationListener {
    static final String EXTRA_CONFIG = "config";
    static final String ACTION_STOP = "com.web2apk.template.STOP_LOCATION";

    private static final String CHANNEL = "w2a_location";
    private static final int NOTIFICATION_ID = 4711;

    /** Read by the page via W2A.bgLocationRunning(). */
    static volatile boolean running = false;

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final AtomicReference<String> latest = new AtomicReference<>();

    private LocationManager lm;
    private PowerManager.WakeLock wakeLock;
    private String url;
    private JSONObject extra = new JSONObject();
    private JSONObject stopBody;
    private long lastGpsFix = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // "Stop" in the notification: tell the server, then finish
            finish(true);
            return START_NOT_STICKY;
        }

        String json = intent == null ? null : intent.getStringExtra(EXTRA_CONFIG);
        JSONObject c;
        try {
            c = new JSONObject(json == null ? "{}" : json);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        url = c.optString("url", "");
        if (!url.startsWith("https://")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        JSONObject ex = c.optJSONObject("extra");
        extra = ex != null ? ex : new JSONObject();
        stopBody = c.optJSONObject("stop");
        long interval = Math.max(2000, c.optLong("intervalMs", 5000));

        try {
            Notification n = buildNotification(
                    c.optString("title", String.valueOf(getApplicationInfo().loadLabel(getPackageManager()))),
                    c.optString("text", ""));
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            // Android refuses a location service started from the background
            // (for example a restart after the process was killed)
            stopSelf();
            return START_NOT_STICKY;
        }

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":location");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(12 * 60 * 60 * 1000L);   // safety limit: 12 hours

        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            lm.removeUpdates(this);
            boolean any = false;
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.getAllProviders().contains(p)) {
                    lm.requestLocationUpdates(p, interval, 0f, this, Looper.getMainLooper());
                    any = true;
                }
            }
            if (!any) {
                stopSelf();
                return START_NOT_STICKY;
            }
        } catch (SecurityException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        // Redeliver the config if Android restarts the service
        return START_REDELIVER_INTENT;
    }

    private Notification buildNotification(String title, String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, label("Standort", "Местоположение",
                    "Місцезнаходження", "Location"), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (open == null) open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, LocationService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setOngoing(true)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null,
                        label("Beenden", "Остановить", "Зупинити", "Stop"), stopPi).build());
        if (text != null && !text.isEmpty()) b.setContentText(text);
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    private static String label(String de, String ru, String uk, String en) {
        switch (Locale.getDefault().getLanguage()) {
            case "de": return de;
            case "ru": return ru;
            case "uk": return uk;
            default: return en;
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        long now = System.currentTimeMillis();
        if (LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
            lastGpsFix = now;
        } else if (now - lastGpsFix < 30000) {
            return;   // network position is worse; use it only while GPS is silent (tunnel, garage)
        }
        try {
            JSONObject body = new JSONObject();
            Iterator<String> keys = extra.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                body.put(k, extra.get(k));
            }
            body.put("lat", Math.round(loc.getLatitude() * 1e6) / 1e6);
            body.put("lng", Math.round(loc.getLongitude() * 1e6) / 1e6);
            body.put("ts", now);
            if (loc.hasAccuracy()) body.put("acc", Math.round(loc.getAccuracy()));
            if (loc.hasSpeed()) body.put("speed", Math.round(loc.getSpeed() * 3.6));   // km/h
            body.put("native", true);
            queue(body.toString());
        } catch (Exception ignored) {
        }
    }

    /** Only the newest position matters: if the network is slow, older ones are skipped. */
    private void queue(String body) {
        if (latest.getAndSet(body) == null) {
            net.execute(() -> {
                String b = latest.getAndSet(null);
                if (b != null) patch(b);
            });
        }
    }

    private void patch(String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            // HttpURLConnection has no PATCH; Firebase and many APIs accept this override
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            c.getResponseCode();
        } catch (Exception ignored) {
            // no network right now: the next position will try again
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void finish(boolean sendStop) {
        running = false;
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
        }
        latest.set(null);
        final JSONObject s = sendStop ? stopBody : null;
        stopBody = null;
        net.execute(() -> {
            if (s != null && url != null) patch(s.toString());
            stopForeground(true);
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lm != null) {
            try { lm.removeUpdates(this); } catch (Exception ignored) { }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        net.shutdown();
        super.onDestroy();
    }

    // Older Android versions call these; nothing to do
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }
}
