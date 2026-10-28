/*
 * Peanut Cam
 * Copyright (c) 2026 James Scott. All rights reserved.
 * Unauthorised copying, modification, redistribution or reuse is prohibited.
 * See COPYRIGHT.md and LICENSE. Contact: jamscoot22@gmail.com
 */
package com.beanhead.peanutcam.audio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.beanhead.peanutcam.R;

/**
 * The foreground service Android requires while phone sound is captured (type mediaProjection). It only holds
 * the notification; once it's in the foreground it hands the consent back to {@link PhoneAudio} to start.
 */
public class PhoneAudioService extends Service {

    private static final String CHANNEL = "phone_audio";
    private static final int NOTE_ID = 8;

    static void start(Context c, int resultCode, Intent data) {
        Intent i = new Intent(c, PhoneAudioService.class).putExtra("code", resultCode).putExtra("data", data);
        try { c.startForegroundService(i); }
        catch (Exception e) { PhoneAudio p = PhoneAudio.instance; if (p != null) p.stop(); }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, PhoneAudioService.class));
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        PhoneAudio p = PhoneAudio.instance;
        if (intent == null || p == null) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Phone sound", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while Peanut Cam records the sound playing on your phone");
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Peanut Cam is capturing phone sound")
                .setContentText("For your recording or live stream. Only sound is captured.")
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NOTE_ID, n);
        } catch (Exception e) {
            stopSelf(); p.stop(); return START_NOT_STICKY;
        }
        Intent data = intent.getParcelableExtra("data");
        p.begin(this, intent.getIntExtra("code", 0), data);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
