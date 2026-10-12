package com.golda.patchertiktok;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.golda.patchertiktok.I18n.S;

/**
 * Fires in the module's own process when TikTok is in the background and a streak
 * is about to expire. This receiver cannot see streak details — TikTok's data is
 * not readable from here — so the nudge is generic: open TikTok to keep streaks.
 */
public final class StreakAlarmReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "tiktokpatchxposed_streaks";
    private static final int ID = 0x7470_0004;

    @Override
    @SuppressLint("NotificationPermission")
    public void onReceive(Context context, Intent intent) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, I18n.get(S.STREAK_CHANNEL),
                    NotificationManager.IMPORTANCE_DEFAULT));
            String text = I18n.get(S.STREAK_REMINDER_BG_TEXT);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(I18n.get(S.STREAK_REMINDER_TITLE))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build();
            manager.notify(ID, notification);
        } catch (RuntimeException ignored) { }
    }
}
