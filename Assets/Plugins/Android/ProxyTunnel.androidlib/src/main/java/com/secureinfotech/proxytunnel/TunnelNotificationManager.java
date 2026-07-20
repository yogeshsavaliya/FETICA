package com.secureinfotech.proxytunnel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Locale;

public final class TunnelNotificationManager {
    public static final int NOTIFICATION_ID = 44170;

    private static final String CHANNEL_ID = "proxy_tunnel_status";
    private static final String CHANNEL_NAME = "Network sharing status";

    private final Service service;
    private final NotificationManager notificationManager;

    public TunnelNotificationManager(Service service) {
        this.service = service;
        this.notificationManager = (NotificationManager) service.getSystemService(Context.NOTIFICATION_SERVICE);
        ensureChannel();
    }

    public Notification build(String status, long uploadedBytes, long downloadedBytes) {
        Intent stopIntent = new Intent(service, ProxyTunnelService.class);
        stopIntent.setAction(ProxyTunnelService.ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                service,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(service, CHANNEL_ID)
                : new Notification.Builder(service);

        return builder
                .setSmallIcon(resolveSmallIcon())
                .setContentTitle("Network sharing active")
                .setContentText(status + " - " + formatBytes(uploadedBytes) + " up, "
                        + formatBytes(downloadedBytes) + " down")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Status: " + status
                                + "\nUploaded: " + formatBytes(uploadedBytes)
                                + "\nDownloaded: " + formatBytes(downloadedBytes)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(resolveSmallIcon(), "Stop", stopPendingIntent)
                .build();
    }

    public void update(String status, long uploadedBytes, long downloadedBytes) {
        notificationManager.notify(NOTIFICATION_ID, build(status, uploadedBytes, downloadedBytes));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the active foreground tunnel state.");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private int resolveSmallIcon() {
        int appIcon = service.getApplicationInfo().icon;
        if (appIcon != 0) {
            return appIcon;
        }
        return android.R.drawable.stat_sys_upload_done;
    }

    private static int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex]);
    }
}
