package com.secureinfotech.proxytunnel;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.lang.reflect.Method;

public final class ProxyTunnelBridge {
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 8017;

    private ProxyTunnelBridge() {
    }

    public static void startTunnel(Activity activity, String host, int port, String token, boolean useTls) {
        if (activity == null) {
            ProxyTunnelService.setLastError("Unity activity is unavailable.");
            return;
        }
        String validationError = validateInput(host, port, token);
        if (validationError != null) {
            ProxyTunnelService.setLastError(validationError);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST_CODE);
            ProxyTunnelService.setStatus(TunnelStatus.DISCONNECTED);
            ProxyTunnelService.setLastError("Notification permission requested. Press Start again after granting it.");
            return;
        }

        Context context = activity.getApplicationContext();
        Intent intent = new Intent(context, ProxyTunnelService.class);
        intent.setAction(ProxyTunnelService.ACTION_START);
        intent.putExtra(ProxyTunnelService.EXTRA_HOST, host.trim());
        intent.putExtra(ProxyTunnelService.EXTRA_PORT, port);
        intent.putExtra(ProxyTunnelService.EXTRA_TOKEN, token);
        intent.putExtra(ProxyTunnelService.EXTRA_USE_TLS, useTls);
        startForegroundServiceCompat(context, intent);
    }

    public static void stopTunnel(Context context) {
        if (context == null) {
            ProxyTunnelService.setLastError("Android context is unavailable.");
            return;
        }
        Intent intent = new Intent(context.getApplicationContext(), ProxyTunnelService.class);
        intent.setAction(ProxyTunnelService.ACTION_STOP);
        startForegroundServiceCompat(context.getApplicationContext(), intent);
    }

    public static String getStatus() {
        return ProxyTunnelService.getStatus();
    }

    public static long getUploadedBytes() {
        return ProxyTunnelService.getUploadedBytes();
    }

    public static long getDownloadedBytes() {
        return ProxyTunnelService.getDownloadedBytes();
    }

    public static String getLastError() {
        return ProxyTunnelService.getLastError();
    }

    private static String validateInput(String host, int port, String token) {
        if (host == null || host.trim().length() == 0) {
            return "Gateway host is required.";
        }
        if (port < 1 || port > 65535) {
            return "Gateway port must be between 1 and 65535.";
        }
        if (token == null || token.length() == 0) {
            return "Authentication token is required.";
        }
        if (containsLineBreak(token)) {
            return "Authentication token must not contain newline characters.";
        }
        return null;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    }

    private static void startForegroundServiceCompat(Context context, Intent intent) {
        try {
            Class<?> contextCompat = Class.forName("androidx.core.content.ContextCompat");
            Method method = contextCompat.getMethod("startForegroundService", Context.class, Intent.class);
            method.invoke(null, context, intent);
            return;
        } catch (Exception ignored) {
            // AndroidX is optional in this Unity project. Fall through to platform APIs.
        }

        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
