package com.golda.patchertiktok;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.golda.patchertiktok.I18n.S;

import java.util.List;

/**
 * Nudges the user when a streak is about to expire, for those who keep auto
 * streaks off. While TikTok is open the reminder is smart (it names the chats);
 * when TikTok goes to the background with streaks at risk, a best-effort alarm
 * is scheduled in the module's own process. That alarm cannot see streak details —
 * it only reminds the user to open TikTok — and it fires only if the device
 * delivers it; doze and battery savers may delay it.
 */
final class StreakReminder {
    private static final String CHANNEL = "tiktokpatchxposed_streaks";
    private static final int ID = 0x7470_0002;
    private static final int ALARM_ID = 0x7470_0003;
    /** The module's own package: the alarm receiver runs there, not in TikTok. */
    private static final String MODULE_PACKAGE = "com.golda.patchertiktok";
    private static final String RECEIVER = "com.golda.patchertiktok.StreakAlarmReceiver";
    /** Remind this long before the streak window closes. */
    private static final long LEAD_MILLIS = 60 * 60 * 1000L;

    private StreakReminder() { }

    /** Smart reminder while TikTok is open: names the chats whose streak is at risk. */
    @SuppressLint("NotificationPermission")
    static void notifyAtRisk(Context context, List<String> peers) {
        if (peers.isEmpty()) return;
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, I18n.get(S.STREAK_CHANNEL),
                    NotificationManager.IMPORTANCE_DEFAULT));
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent open = launch == null ? null : PendingIntent.getActivity(context, ID, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String names = peers.size() <= 3 ? String.join(", ", peers)
                    : peers.size() + " chats";
            String text = String.format(java.util.Locale.getDefault(), I18n.get(S.STREAK_REMINDER_TEXT), names);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(I18n.get(S.STREAK_REMINDER_TITLE))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            manager.notify(ID, notification);
        } catch (RuntimeException error) {
            RuntimeLog.log("streak reminder unavailable: " + error.getClass().getSimpleName());
        }
    }

    /** Best-effort: schedule a nudge in the module's own process when TikTok backgrounds. */
    static void scheduleBackgroundAlarm(Context context, long riskAtMillis) {
        cancelBackgroundAlarm(context);
        long triggerAt = riskAtMillis - LEAD_MILLIS;
        if (triggerAt <= System.currentTimeMillis() + 60_000L) return;
        try {
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms == null) return;
            PendingIntent alarm = alarmIntent(context);
            if (alarm == null) return;
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, alarm);
            RuntimeLog.log("streak reminder: background alarm scheduled");
        } catch (RuntimeException error) {
            RuntimeLog.log("streak reminder alarm unavailable: " + error.getClass().getSimpleName());
        }
    }

    static void cancelBackgroundAlarm(Context context) {
        try {
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            PendingIntent alarm = alarmIntent(context);
            if (alarms != null && alarm != null) alarms.cancel(alarm);
        } catch (RuntimeException ignored) { }
    }

    private static PendingIntent alarmIntent(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(MODULE_PACKAGE, RECEIVER);
            intent.setAction(MODULE_PACKAGE + ".STREAK_REMINDER");
            return PendingIntent.getBroadcast(context, ALARM_ID, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Elapsed-realtime timestamp for in-process scheduling. */
    static long elapsedFor(long wallMillis) {
        return SystemClock.elapsedRealtime() + (wallMillis - System.currentTimeMillis());
    }
}
