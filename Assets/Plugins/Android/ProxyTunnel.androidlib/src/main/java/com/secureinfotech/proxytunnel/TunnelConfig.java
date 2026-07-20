package com.secureinfotech.proxytunnel;

import android.content.Context;
import android.content.SharedPreferences;

public final class TunnelConfig {
    private static final String PREFS_NAME = "proxy_tunnel_config";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USE_TLS = "use_tls";
    private static final String KEY_USER_ENABLED = "user_enabled";

    public final String host;
    public final int port;
    public final boolean useTls;

    public TunnelConfig(String host, int port, boolean useTls) {
        this.host = host;
        this.port = port;
        this.useTls = useTls;
    }

    public static void persist(Context context, TunnelConfig config, boolean userEnabled) {
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit();
        editor.putString(KEY_HOST, config.host);
        editor.putInt(KEY_PORT, config.port);
        editor.putBoolean(KEY_USE_TLS, config.useTls);
        editor.putBoolean(KEY_USER_ENABLED, userEnabled);
        editor.apply();
    }

    public static void setUserEnabled(Context context, boolean userEnabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_USER_ENABLED, userEnabled)
                .apply();
    }

    public static boolean wasUserEnabled(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_USER_ENABLED, false);
    }

    public static TunnelConfig load(Context context) {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String host = prefs.getString(KEY_HOST, "");
        int port = prefs.getInt(KEY_PORT, 0);
        boolean useTls = prefs.getBoolean(KEY_USE_TLS, true);
        if (host == null || host.length() == 0 || port < 1 || port > 65535) {
            return null;
        }
        return new TunnelConfig(host, port, useTls);
    }
}
