/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.beanhead.peanutcam.MainActivity;
import com.beanhead.peanutcam.R;

/**
 * Keeps a live stream going while Peanut Cam is in the background: a foreground service with an
 * "End stream" notification, plus Wi-Fi and CPU locks so the connection doesn't stall when the screen
 * dims. It holds no stream state itself; {@link LiveController} owns the stream.
 *
 * The picture comes from the page, which Android stops drawing while the app is hidden, so viewers see
 * the last frame until you come back; the sound and the connection carry on.
 */
public class LiveService extends Service {

    static final String ACTION_STOP = "com.beanhead.peanutcam.live.STOP";
    private static final String CHANNEL = "live";
    private static final int NOTE_ID = 7;

    /** Set by the controller: what the notification's "End stream" button does. */
    static volatile Runnable onStopRequested;

    private WifiManager.WifiLock wifi;
    private PowerManager.WakeLock cpu;

    static void start(Context c, boolean mic) {
        Intent i = new Intent(c, LiveService.class).putExtra("mic", mic);
        try { c.startForegroundService(i); } catch (Exception ignored) { /* the stream still works in the foreground */ }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, LiveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable r = onStopRequested;
            if (r != null) r.run();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean mic = intent != null && intent.getBooleanExtra("mic", false);
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // the microphone type exists from Android 11, which is also when background mic use became restricted
                int type = mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                        ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                try {
                    startForeground(NOTE_ID, n, type);
                } catch (Exception e) {                   // e.g. the mic permission was taken away
                    startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                }
            } else {
                startForeground(NOTE_ID, n);
            }
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        acquireLocks();
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live streaming", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Peanut Cam is streaming live");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent end = PendingIntent.getService(this, 1,
                new Intent(this, LiveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Peanut Cam is live")
                .setContentText("Tap to go back to the app. The picture pauses while it's hidden.")
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_launcher), "End stream", end).build())
                .build();
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        try {
            if (wifi == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifi = wm.createWifiLock(mode, "PeanutCam:live");
                wifi.setReferenceCounted(false);
                wifi.acquire();
            }
            if (cpu == null) {
                cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PeanutCam:live");
                cpu.setReferenceCounted(false);
                cpu.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // swiped away from recents: end the stream rather than leave it running with no picture
        Runnable r = onStopRequested;
        if (r != null) r.run();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        try { if (wifi != null) wifi.release(); } catch (Exception ignored) { }
        try { if (cpu != null) cpu.release(); } catch (Exception ignored) { }
        wifi = null;
        cpu = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
