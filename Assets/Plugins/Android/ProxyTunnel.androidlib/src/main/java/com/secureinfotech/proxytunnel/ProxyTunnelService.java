package com.secureinfotech.proxytunnel;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicLong;

public final class ProxyTunnelService extends Service implements TunnelConnectionManager.Listener {
    public static final String ACTION_START = "com.secureinfotech.proxytunnel.START";
    public static final String ACTION_STOP = "com.secureinfotech.proxytunnel.STOP";

    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_USE_TLS = "useTls";

    private static final AtomicLong UPLOADED_BYTES = new AtomicLong();
    private static final AtomicLong DOWNLOADED_BYTES = new AtomicLong();
    private static volatile String status = TunnelStatus.DISCONNECTED;
    private static volatile String lastError = "";

    private TunnelNotificationManager notificationManager;
    private TunnelConnectionManager connectionManager;

    public static String getStatus() {
        return status;
    }

    public static void setStatus(String value) {
        status = value == null ? TunnelStatus.DISCONNECTED : value;
    }

    public static long getUploadedBytes() {
        return UPLOADED_BYTES.get();
    }

    public static long getDownloadedBytes() {
        return DOWNLOADED_BYTES.get();
    }

    public static String getLastError() {
        return lastError == null ? "" : lastError;
    }

    public static void setLastError(String value) {
        lastError = value == null ? "" : value;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = new TunnelNotificationManager(this);
        setStatus(TunnelStatus.DISCONNECTED);
        startForeground(
                TunnelNotificationManager.NOTIFICATION_ID,
                notificationManager.build(getStatus(), getUploadedBytes(), getDownloadedBytes()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopForTerminalError("Tunnel credentials are unavailable. Start the tunnel again from Unity.");
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopTunnel();
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(intent.getAction())) {
            stopForTerminalError("Unsupported tunnel service action.");
            return START_NOT_STICKY;
        }

        String host = intent.getStringExtra(EXTRA_HOST);
        int port = intent.getIntExtra(EXTRA_PORT, 0);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        boolean useTls = intent.getBooleanExtra(EXTRA_USE_TLS, true);
        String validationError = validateStartInput(host, port, token);
        if (validationError != null) {
            stopForTerminalError(validationError);
            return START_NOT_STICKY;
        }

        TunnelConfig config = new TunnelConfig(host.trim(), port, useTls);
        TunnelConfig.persist(this, config, true);
        UPLOADED_BYTES.set(0L);
        DOWNLOADED_BYTES.set(0L);
        setLastError("");
        setStatus(TunnelStatus.CONNECTING);
        updateNotification();

        if (connectionManager != null) {
            connectionManager.stop();
        }
        connectionManager = new TunnelConnectionManager(getApplicationContext(), config, token, this);
        connectionManager.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (connectionManager != null) {
            connectionManager.stop();
            connectionManager = null;
        }
        super.onDestroy();
    }

    @Override
    public void onStatusChanged(String newStatus) {
        setStatus(newStatus);
        updateNotification();
    }

    @Override
    public void onBytesChanged(long uploadedBytes, long downloadedBytes) {
        UPLOADED_BYTES.set(uploadedBytes);
        DOWNLOADED_BYTES.set(downloadedBytes);
        updateNotification();
    }

    @Override
    public void onError(String message) {
        setLastError(message);
        updateNotification();
    }

    @Override
    public void onTerminalDisconnect(String message) {
        setStatus(TunnelStatus.DISCONNECTED);
        setLastError(message);
        TunnelConfig.setUserEnabled(this, false);
        if (connectionManager != null) {
            connectionManager.shutdownAfterTerminalFailure();
            connectionManager = null;
        }
        stopForeground(true);
        stopSelf();
    }

    private void stopForTerminalError(String message) {
        TunnelConfig.setUserEnabled(this, false);
        if (connectionManager != null) {
            connectionManager.stop();
            connectionManager = null;
        }
        setStatus(TunnelStatus.DISCONNECTED);
        setLastError(message);
        updateNotification();
        stopForeground(true);
        stopSelf();
    }

    private void stopTunnel() {
        TunnelConfig.setUserEnabled(this, false);
        if (connectionManager != null) {
            connectionManager.stop();
            connectionManager = null;
        }
        setStatus(TunnelStatus.DISCONNECTED);
        setLastError("");
        updateNotification();
        stopForeground(true);
        stopSelf();
    }

    private void updateNotification() {
        if (notificationManager != null) {
            notificationManager.update(getStatus(), getUploadedBytes(), getDownloadedBytes());
        }
    }

    private static String validateStartInput(String host, int port, String token) {
        if (host == null || host.trim().length() == 0) {
            return "Gateway host is required.";
        }
        if (port < 1 || port > 65535) {
            return "Gateway port must be between 1 and 65535.";
        }
        if (token == null || token.length() == 0) {
            return "Authentication token is required.";
        }
        if (token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
            return "Authentication token must not contain newline characters.";
        }
        return null;
    }
}
